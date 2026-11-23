package app.rasidi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import app.rasidi.ui.screens.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp

object Routes {
    const val HOME = "home"; const val TXNS = "txns"; const val DEBTS = "debts"; const val MORE = "more"
    const val ONBOARD = "onboard"
    const val ADD = "add/{type}?ref={ref}"; fun add(type: String, ref: String = "") = "add/$type?ref=$ref"
    const val TXN = "txn/{id}"; fun txn(id: String) = "txn/$id"
    const val DEBT = "debt/{id}"; fun debt(id: String) = "debt/$id"
    const val ACCOUNTS = "accounts"; const val ACCOUNT = "account/{id}"; fun account(id: String) = "account/$id"
    const val GOLD = "gold"; const val RATES = "rates"; const val REPORTS = "reports?kind={kind}&ref={ref}"; fun reports(kind: String = "", ref: String = "") = "reports?kind=$kind&ref=$ref"
    const val INVENTORY = "inventory"; const val ZAKAT = "zakat/{inv}"; fun zakat(inv: String) = "zakat/$inv"
    const val BACKUP = "backup"; const val SETTINGS = "settings"; const val CATEGORIES = "categories"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab(Routes.HOME, "الرئيسية", Icons.Filled.Home),
    Tab(Routes.TXNS, "الحركات", Icons.Filled.ReceiptLong),
    Tab(Routes.DEBTS, "الديون", Icons.Filled.Handshake),
    Tab(Routes.MORE, "المزيد", Icons.Filled.GridView),
)

@Composable
fun RasidiRoot(vm: MainViewModel, startOverride: String? = null) {
    val nav = rememberNavController()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var quickAdd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val r = snack.showSnackbar(m.text, actionLabel = if (m.undoTxnId != null) "تراجع" else null, withDismissAction = m.undoTxnId == null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed && m.undoTxnId != null) vm.undo(m.undoTxnId)
        }
    }
    if (!ready) { Box(Modifier.fillMaxSize().background(LocalR.current.bg)); return }
    val start = startOverride ?: if (!vm.device.onboarded && !demo) Routes.ONBOARD else Routes.HOME
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route
    val showBar = route in tabs.map { it.route }

    Box(Modifier.fillMaxSize().background(LocalR.current.bg)) {
        NavHost(nav, startDestination = start) {
            composable(Routes.ONBOARD) { OnboardingScreen(vm, nav) }
            composable(Routes.HOME) { HomeScreen(vm, nav) }
            composable(Routes.TXNS) { TransactionsScreen(vm, nav) }
            composable(Routes.DEBTS) { DebtsScreen(vm, nav) }
            composable(Routes.MORE) { MoreScreen(vm, nav) }
            composable(Routes.ADD) { e -> AddScreen(vm, nav, e.arguments?.getString("type") ?: "expense", e.arguments?.getString("ref").orEmpty()) }
            composable(Routes.TXN) { e -> TxnDetailScreen(vm, nav, e.arguments?.getString("id")!!) }
            composable(Routes.DEBT) { e -> DebtDetailScreen(vm, nav, e.arguments?.getString("id")!!) }
            composable(Routes.ACCOUNTS) { AccountsScreen(vm, nav) }
            composable(Routes.ACCOUNT) { e -> AccountDetailScreen(vm, nav, e.arguments?.getString("id")!!) }
            composable(Routes.GOLD) { GoldScreen(vm, nav) }
            composable(Routes.RATES) { RatesScreen(vm, nav) }
            composable(Routes.REPORTS) { e -> ReportsScreen(vm, nav, e.arguments?.getString("kind").orEmpty(), e.arguments?.getString("ref").orEmpty()) }
            composable(Routes.INVENTORY) { InventoryScreen(vm, nav) }
            composable(Routes.ZAKAT) { e -> ZakatScreen(vm, nav, e.arguments?.getString("inv")!!) }
            composable(Routes.BACKUP) { BackupScreen(vm, nav) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
            composable(Routes.CATEGORIES) { CategoriesScreen(vm, nav) }
        }
        if (showBar) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = Sp.sm, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // زر الإضافة السريعة ظاهر دائماً على الشاشات الرئيسية
                    LargeFloatingActionButton(onClick = { quickAdd = true }, shape = RoundedCornerShape(24.dp),
                        containerColor = LocalR.current.pri, contentColor = LocalR.current.priInk,
                        modifier = Modifier.padding(bottom = 10.dp).size(64.dp).semantics { contentDescription = "إضافة حركة" }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(30.dp))
                    }
                }
                BottomBar(route) { r -> nav.navigate(r) { popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true } }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (showBar) 150.dp else 16.dp))
    }
    if (quickAdd) QuickAddSheet(onDismiss = { quickAdd = false }) { type -> quickAdd = false; nav.navigate(Routes.add(type)) }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    val c = LocalR.current
    Row(Modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp)).background(c.tab).border(1.dp, c.line, RoundedCornerShape(32.dp)).padding(6.dp)) {
        tabs.forEach { t ->
            val on = current == t.route
            Column(Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(26.dp)).background(if (on) c.priSoft else androidx.compose.ui.graphics.Color.Transparent)
                .semantics { selected = on; role = Role.Tab }.clickable { onSelect(t.route) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(t.icon, null, tint = if (on) c.pri else c.mute, modifier = Modifier.size(26.dp))
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) c.pri else c.mute)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = LocalR.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = Sp.md).padding(bottom = Sp.lg)) {
            Text("إضافة سريعة", style = MaterialTheme.typography.titleLarge, color = c.txt)
            Spacer(Modifier.height(Sp.sm))
            val items = listOf(
                Triple("expense", "مصروف", Icons.Filled.ShoppingBag), Triple("income", "دخل", Icons.Filled.Payments),
                Triple("transfer", "تحويل", Icons.Filled.SwapHoriz), Triple("debt", "دين", Icons.Filled.Handshake), Triple("payment", "دفعة دين", Icons.Filled.TaskAlt),
            )
            items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    row.forEach { (k, l, ic) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(Sp.radius)).background(if (k == "expense") c.priSoft else c.soft).clickable { onPick(k) }.heightIn(min = 88.dp).padding(Sp.sm),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(ic, null, tint = if (k == "debt" || k == "payment") c.gold else c.pri, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(4.dp)); Text(l, style = MaterialTheme.typography.titleSmall, color = c.txt)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(Sp.xs))
            }
        }
    }
}
