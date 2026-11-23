package app.rasidi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * القبول: فتح أول مرة (يمكن تشغيله في وضع الطيران)، إضافة محفظة، تسجيل مصروف عبر الإضافة السريعة،
 * ثم إعادة إنشاء النشاط والتحقق أن الرصيد نفسه.
 * التشغيل: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FirstRunOfflineTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun clean() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("rasidi.device", 0).edit().clear().commit()
    }

    @Test fun walletExpenseReopen() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("ابدأ محلياً").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("أموالي اليوم").fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodesWithText("ابدأ محلياً").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("ابدأ محلياً").performClick()
            rule.onNodeWithText("الرصيد الافتتاحي (اختياري) (USD)").performTextInput("100")
            rule.onNodeWithText("ابدأ").performClick()
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("أموالي اليوم").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("إضافة حركة").performClick()
        rule.onNodeWithText("مصروف").performClick()
        rule.onNodeWithText("المبلغ (USD)").performTextInput("١٢٫٥٠")
        rule.onNodeWithText("أكل").performClick()
        rule.onNodeWithText("احفظ").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("أموالي اليوم").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("87.50 USD", substring = true).onFirst().assertExists()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("أموالي اليوم").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("87.50 USD", substring = true).onFirst().assertExists()
    }
}
