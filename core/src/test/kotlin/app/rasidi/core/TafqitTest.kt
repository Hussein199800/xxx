package app.rasidi.core

import app.rasidi.core.money.Currencies
import app.rasidi.core.money.Money
import app.rasidi.core.voucher.Tafqit
import org.junit.Assert.assertEquals
import org.junit.Test

class TafqitTest {
    @Test fun numbers() {
        assertEquals("صفر", Tafqit.number(0))
        assertEquals("خمسمائة", Tafqit.number(500))
        assertEquals("أحد عشر", Tafqit.number(11))
        assertEquals("واحد وعشرون", Tafqit.number(21))
        assertEquals("مائة وخمسة", Tafqit.number(105))
        assertEquals("ألف ومائتان وخمسون", Tafqit.number(1250))
        assertEquals("ألفان", Tafqit.number(2000))
        assertEquals("ثلاثة آلاف وأربعمائة", Tafqit.number(3400))
        assertEquals("خمسة عشر ألف", Tafqit.number(15000))
        assertEquals("مائة ألف", Tafqit.number(100_000))
        assertEquals("مليونان وخمسمائة ألف", Tafqit.number(2_500_000))
        assertEquals("ثلاثة مليارات", Tafqit.number(3_000_000_000))
    }

    @Test fun money() {
        assertEquals("خمسمائة دولار أمريكي فقط لا غير", Tafqit.money(Money.of("500", Currencies.USD)))
        assertEquals("ألف ومائتان وخمسون ليرة تركية وخمسون قرش فقط لا غير", Tafqit.money(Money.of("1250.50", Currencies.TRY)))
        assertEquals("أربعة عشر ألف ومائة ليرة سورية جديدة فقط لا غير", Tafqit.money(Money.of("14100", Currencies.SYP)))
    }
}
