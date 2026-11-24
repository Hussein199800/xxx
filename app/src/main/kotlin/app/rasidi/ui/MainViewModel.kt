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
import app.rasidi.ui.components.LedgerStatus
import app.rasidi.ui.components.Tone
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

data class UiSettings(val theme: String = "system", val fontScale: Float = 1f, val hideAmounts: Boolean = false, val all: Map<String, String> = emptyMap()) {
    fun get(k: String, def: String = "") = all[k] ?: def
    /** خيارات السندات من «البيانات الشخصية» و«إعدادات السندات» و«إعدادات الطباعة». */
    fun voucherOptions() = app.rasidi.core.voucher.VoucherOptions(
        app.rasidi.core.voucher.Profile(get("profile.name"), get("profile.city"), get("profile.phone")),
        runCatching { app.rasidi.core.voucher.Paper.valueOf(get("print.paper", "A4")) }.getOrDefault(app.rasidi.core.voucher.Paper.A4),
        get("voucher.showBalance", "1") == "1",
        get("voucher.signatures", "المستلم|المحاسب").split('|').map { it.trim() }.filter { it.isNotBlank() },
        get("voucher.footer"),
    )
    val homeButtons: List<String> get() = get("home.buttons", HomeButtons.DEFAULT).split(',').filter { it in HomeButtons.ALL }
}

/** أزرار الواجهة الرئيسية القابلة للتخصيص (مثل «تخصيص أزرار الواجهة الرئيسية»). */
object HomeButtons {
    val ALL = linkedMapOf(
        "receipt" to "سند قبض", "payment" to "سند صرف", "exchange" to "صرف عملات", "statement" to "كشف حساب",
        "today" to "حركة اليوم", "debtpay" to "دفعة دين", "debt" to "دين جديد", "search" to "بحث سريع",
        "inventory" to "الجرد", "calc" to "الحاسبة", "report" to "تقرير", "rates" to "الأسعار",
    )
    const val DEFAULT = "receipt,payment,exchange,statement,today,debtpay"
}

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

    private val _status = MutableStateFlow(LedgerStatus("دفتري", "", Tone.INFO))
    /** حالة الدفتر للشريط العلوي: النسخة الاحتياطية أو الوضع التجريبي. */
    val status: StateFlow<LedgerStatus> = _status

    fun refreshStatus() {
        val d = device
        if (container.demo) { _status.value = LedgerStatus("دفتر تجريبي", "بيانات اصطناعية منفصلة", Tone.GOLD); return }
        val last = maxOf(d.lastVerifiedAt, d.lastLocalBackupAt)
        val days = if (last > 0) ((System.currentTimeMillis() - last) / 86_400_000L) else -1
        _status.value = when {
            d.driveRelinkNeeded -> LedgerStatus("دفتري", "أعد ربط Google", Tone.DANGER)
            last <= 0 -> LedgerStatus("دفتري", "لا نسخة احتياطية بعد", Tone.WARN)
            days == 0L -> LedgerStatus("دفتري", "نُسخ اليوم" + if (d.lastVerifiedAt == last) " · Drive" else " · ملف", Tone.OK)
            days <= 7 -> LedgerStatus("دفتري", "آخر نسخة منذ $days يوم", Tone.OK)
            else -> LedgerStatus("دفتري", "آخر نسخة منذ $days يوماً", Tone.WARN)
        }
    }

    /** رسالة للمستخدم؛ للأخطاء: سبب وحلّ وزر يأخذه إلى المكان المناسب. */
    data class UiMessage(val text: String, val undoTxnId: String? = null, val action: app.rasidi.core.help.ErrorHelp.Action? = null)

    private suspend fun emitError(msg: String?) {
        val e = app.rasidi.core.help.ErrorHelp.explain(msg)
        _messages.emit(UiMessage(if (e.fix.isBlank()) e.title else "${e.title}\n${e.fix}", action = e.action.takeIf { it != app.rasidi.core.help.ErrorHelp.Action.NONE }))
    }

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
        refreshStatus()
        _ready.value = true
    }

    private suspend fun loadSettings() {
        val all = repo.snapshot(includeAudit = false).settings.associate { it.key to it.value }
        _settings.value = UiSettings(
            all["ui.theme"] ?: "system",
            all["ui.fontScale"]?.toFloatOrNull() ?: 1f,
            all["ui.hideAmounts"] == "1",
            all,
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
        } catch (e: LedgerException) { emitError(e.message ?: "تعذّر الحفظ") }
        catch (e: IllegalArgumentException) { emitError(e.message ?: "قيمة غير صالحة") }
        catch (e: Exception) { emitError(e.message) }
    }

    fun message(text: String) { viewModelScope.launch { _messages.emit(UiMessage(text)) } }
    /** خطأ من شاشة (مثل الاستعادة أو التصدير) يُعرض بسببه وحلّه. */
    fun error(text: String?) { viewModelScope.launch { emitError(text) } }

    fun undo(txnId: String) = launchSafe("تم التراجع") { repo.undoRecent(txnId) }

    fun switchDemo(demo: Boolean) {
        rapp.switchDemo(demo)
        _demo.value = demo
        _ready.value = false
        observe()
    }
}
