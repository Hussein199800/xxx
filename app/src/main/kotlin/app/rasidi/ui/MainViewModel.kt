package app.rasidi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rasidi.AppContainer
import app.rasidi.RasidiApp
import app.rasidi.core.data.*
import app.rasidi.core.ledger.*
import app.rasidi.core.money.*
import app.rasidi.data.LedgerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

data class HomeState(
    val netWorth: NetWorth,
    val monthIncome: Map<String, Money>,
    val monthExpense: Map<String, Money>,
    val nearestDue: Triple<Debt, LocalDate, Money>?,
    val recent: List<TxnRow>,
)

/** صف حركة جاهز للعرض. */
data class TxnRow(
    val id: String, val type: TxnType, val date: LocalDate, val note: String, val party: String,
    val amounts: List<Money>, val accountNames: List<String>, val categoryName: String?, val reversed: Boolean, val isReversal: Boolean,
)

data class DebtRow(val debt: Debt, val paid: Money, val remaining: Money, val status: DebtStatus, val nextDue: LocalDate?, val archived: Boolean)

data class UiSettings(val theme: String = "system", val fontScale: Float = 1f, val hideAmounts: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val rapp = app as RasidiApp
    val container: AppContainer get() = rapp.container
    val repo get() = container.repo
    val device get() = rapp.device

    private val _view = MutableStateFlow<LedgerView?>(null)
    val view: StateFlow<LedgerView?> = _view
    private val _home = MutableStateFlow<HomeState?>(null)
    val home: StateFlow<HomeState?> = _home
    private val _debts = MutableStateFlow<List<DebtRow>>(emptyList())
    val debts: StateFlow<List<DebtRow>> = _debts
    private val _settings = MutableStateFlow(UiSettings())
    val settings: StateFlow<UiSettings> = _settings
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _demo = MutableStateFlow(container.demo)
    val demo: StateFlow<Boolean> = _demo

    data class UiMessage(val text: String, val undoTxnId: String? = null)

    /** اليوم بتوقيت الدفتر المحلي. */
    fun today(): LocalDate = LocalDate.now()

    init { observe() }

    private var observeJob: kotlinx.coroutines.Job? = null
    private fun observe() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.ensureCategories()
            loadSettings()
            repo.version.collectLatest { refresh() }
        }
    }

    suspend fun refresh() {
        val v = withContext(Dispatchers.IO) { repo.view() }
        val derived = withContext(Dispatchers.Default) { derive(v) }
        _view.value = v; _home.value = derived.first; _debts.value = derived.second
        _ready.value = true
    }

    private suspend fun loadSettings() {
        _settings.value = UiSettings(
            repo.setting("ui.theme") ?: "system",
            repo.setting("ui.fontScale")?.toFloatOrNull() ?: 1f,
            repo.setting("ui.hideAmounts") == "1",
        )
    }

    fun setSetting(key: String, value: String) = launchSafe { repo.putSetting(key, value); loadSettings() }

    private fun derive(v: LedgerView): Pair<HomeState, List<DebtRow>> {
        val today = today()
        val nw = v.netWorth(today)
        val mt = PeriodTotals.of(v.entries, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
        val debtRows = v.debts.map { d ->
            val ps = v.payments.filter { it.debtId == d.id }
            DebtRow(d, DebtCalc.paid(d, ps), DebtCalc.remaining(d, ps), DebtCalc.status(d, ps, today), DebtCalc.nextDue(d, ps), v.s.debts.first { it.id == d.id }.archived)
        }
        val nearest = debtRows.filter { it.nextDue != null && it.remaining.minor > 0 }.minByOrNull { it.nextDue!! }?.let { Triple(it.debt, it.nextDue!!, it.remaining) }
        return HomeState(nw, mt.income, mt.expense, nearest, rows(v, v.s.txns.sortedWith(compareByDescending<app.rasidi.core.data.TxnEntity> { it.date }.thenByDescending { it.createdAtUtc }).take(6))) to debtRows
    }

    fun rows(v: LedgerView, txns: List<app.rasidi.core.data.TxnEntity>): List<TxnRow> {
        val byTxn = v.entries.groupBy { it.txnId }
        return txns.map { t ->
            val es = byTxn[t.id].orEmpty()
            TxnRow(t.id, TxnType.valueOf(t.type), LocalDate.parse(t.date), t.note, t.party, es.map { it.amount },
                es.map { v.accountById[it.accountId]?.name ?: "" }.distinct(), es.firstNotNullOfOrNull { it.categoryId }?.let { v.categoryNames[it] },
                t.reversedById != null, t.reversesId != null)
        }
    }

    fun newRequestId() = UUID.randomUUID().toString()

    fun launchSafe(success: String? = null, undoable: Boolean = false, block: suspend () -> Any?) = viewModelScope.launch {
        try {
            val r = block()
            if (success != null) _messages.emit(UiMessage(success, if (undoable) r as? String else null))
        } catch (e: LedgerException) { _messages.emit(UiMessage(e.message ?: "تعذّر الحفظ")) }
        catch (e: IllegalArgumentException) { _messages.emit(UiMessage(e.message ?: "قيمة غير صالحة")) }
        catch (e: Exception) { _messages.emit(UiMessage("حدث خطأ: ${e.message ?: e.javaClass.simpleName}")) }
    }

    fun message(text: String) { viewModelScope.launch { _messages.emit(UiMessage(text)) } }

    fun undo(txnId: String) = launchSafe("تم التراجع") { repo.undoRecent(txnId) }

    fun switchDemo(demo: Boolean) {
        rapp.switchDemo(demo)
        _demo.value = demo
        _ready.value = false
        observe()
    }
}
