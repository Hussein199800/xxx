package app.rasidi

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.rasidi.core.demo.DemoData
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.RasidiRoot
import app.rasidi.ui.Routes
import app.rasidi.ui.theme.RasidiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * صور الواجهة من الدفتر التجريبي (بيانات اصطناعية) عبر Robolectric بالرسم الأصلي.
 * تُكتب في docs/screens. ليست بديلاً عن الفحص على جهاز حقيقي (انظر VALIDATION.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = RasidiApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("rasidi.screens.dir") ?: "build/screens").apply { mkdirs() }

    private fun shoot(name: String, route: String, theme: String = "light", fontScale: Float = 1f) {
        val app = ApplicationProvider.getApplicationContext<RasidiApp>()
        app.device.onboarded = true
        if (!app.container.demo) app.switchDemo(true)
        runBlocking {
            app.container.repo.replaceAll(DemoData.snapshot(LocalDate.now()))
            app.container.repo.ensureCategories()
        }
        val vm = MainViewModel(app)
        runBlocking { vm.refresh() }
        rule.setContent { RasidiTheme(theme, fontScale) { RasidiRoot(vm, route) } }
        rule.waitForIdle()
        Thread.sleep(300); rule.waitForIdle()
        // رسم برمجي لشجرة العرض (رسم Robolectric الأصلي) بدل PixelCopy غير المدعوم هنا
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(android.graphics.Canvas(bmp)) }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shoot("01-home-light", Routes.HOME)
    @Test fun homeDark() { RuntimeEnvironment.setQualifiers("+night"); shoot("02-home-dark", Routes.HOME, "dark") }
    @Test fun debts() = shoot("03-debts-light", Routes.DEBTS)
    @Test fun debtsDark() = shoot("04-debts-dark", Routes.DEBTS, "dark")
    @Test fun inventory() = shoot("05-inventory-light", Routes.INVENTORY)
    @Test fun backup() = shoot("06-backup-light", Routes.BACKUP)
    @Test fun transactions() = shoot("07-transactions-light", Routes.TXNS)
    @Test fun more() = shoot("08-more-dark", Routes.MORE, "dark")
    @Test fun calculator() = shoot("13-calculator-light", Routes.CALC)
    @Test fun budgets() = shoot("14-budgets-light", Routes.BUDGETS)
    @Test fun recurring() = shoot("15-recurring-dark", Routes.RECURRING, "dark")
    @Test fun members() = shoot("16-members-light", Routes.MEMBERS)
    @Test fun moreLight() = shoot("17-more-light", Routes.MORE)
    @Test fun addExpense() = shoot("09-add-expense-light", Routes.add("expense"))
    @Test fun largeFont() = shoot("10-home-large-font", Routes.HOME, "light", 1.3f)
    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi") fun tablet() = shoot("11-home-tablet", Routes.HOME)
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun narrowPhone() = shoot("12-debts-narrow", Routes.DEBTS, "light", 1.15f)
}
