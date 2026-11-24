package app.rasidi.core

import app.rasidi.core.help.ErrorHelp
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorHelpTest {
    @Test fun explainsKnownErrors() {
        assertEquals(ErrorHelp.Action.ACCOUNTS, ErrorHelp.explain("الرصيد في «نقد» لا يكفي. فعّل «السماح بالسالب»").action)
        assertEquals("ينقص سعر بتاريخ الجرد", ErrorHelp.explain("لا يمكن اعتماد جرد فيه 2 بند بلا سعر؛ احفظه مسودة").title)
        assertEquals(ErrorHelp.Action.GUIDE, ErrorHelp.explain("رمز الاستعادة غير صحيح أو الملف معدَّل").action)
        assertEquals(ErrorHelp.Action.BACKUP, ErrorHelp.explain("يلزم إعادة ربط Google").action.let { ErrorHelp.explain("أعد ربط Google").action })
        assertEquals("المبلغ أكبر من الباقي من الدين", ErrorHelp.explain("المبلغ يتجاوز المتبقي (200.00 USD)").title)
        assertEquals(ErrorHelp.Action.NONE, ErrorHelp.explain("شيء آخر").action)
    }
}
