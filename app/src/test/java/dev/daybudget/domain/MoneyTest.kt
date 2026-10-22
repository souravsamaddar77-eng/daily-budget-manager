package dev.daybudget.domain

import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    private fun rejects(text: String, signed: Boolean = false) {
        try { Money.parse(text, signed); fail("Expected rejection of '$text'") } catch (_: BudgetException) { }
    }

    @Test fun parsesDecimalsDirectlyToPaise() {
        assertEquals(400_000L, Money.parse("4000"))
        assertEquals(1L, Money.parse("0.01"))
        assertEquals(29L, Money.parse("0.29"))
        assertEquals(12_350L, Money.parse("123.5"))
        assertEquals(12_345_678L, Money.parse("₹1,23,456.78"))
        assertEquals(12_345_678L, Money.parse("123,456.78"))
        assertEquals(-125L, Money.parse("-1.25", true))
        assertEquals(-125L, Money.parse("−1.25", true))
    }

    @Test fun rejectsExtraDecimalsExponentMalformedGroupingAndNegativeBudgets() {
        listOf("", "1.234", "1e3", "1.2.3", "1,23.00", "NaN", "Infinity", "-0.01", "1 000", ".5").forEach { rejects(it) }
    }

    @Test fun formatsIndianGroupingWithExactlyTwoDecimals() {
        assertEquals("₹0.00", Money.format(0))
        assertEquals("₹0.01", Money.format(1))
        assertEquals("₹1,000.00", Money.format(100_000))
        assertEquals("₹1,23,456.78", Money.format(12_345_678))
        assertEquals("−₹66.67", Money.format(-6_667))
        assertEquals("123.45", Money.format(12_345, false))
    }

    @Test fun guardsBothLongLimitsWithoutFloatingPointOrAbsOverflow() {
        assertEquals(Long.MAX_VALUE, Money.parse("92233720368547758.07"))
        assertEquals(Long.MIN_VALUE, Money.parse("-92233720368547758.08", true))
        rejects("92233720368547758.08")
        rejects("-92233720368547758.09", true)
        assertEquals("−₹92,23,37,20,36,85,47,758.08", Money.format(Long.MIN_VALUE))
    }
}
