package com.roomrentmanager.domain

import com.roomrentmanager.domain.finance.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {

    @Test
    fun rupeesToPaise_convertsExactValues() {
        assertEquals(150000L, Money.rupeesToPaise("1500"))
        assertEquals(150050L, Money.rupeesToPaise("1500.5"))
        assertEquals(150055L, Money.rupeesToPaise("1500.55"))
        assertEquals(0L, Money.rupeesToPaise("0"))
    }

    @Test
    fun rupeesToPaise_roundsHalfUp() {
        assertEquals(29L, Money.rupeesToPaise("0.285"))
        assertEquals(28L, Money.rupeesToPaise("0.2849"))
        assertEquals(100L, Money.rupeesToPaise("0.995"))
        assertEquals(-29L, Money.rupeesToPaise("-0.285"))
    }

    @Test
    fun rupeesToPaise_rejectsOverflowAndGarbage() {
        assertThrows(ArithmeticException::class.java) { Money.rupeesToPaise(BigDecimal("1e30")) }
        assertThrows(NumberFormatException::class.java) { Money.rupeesToPaise("abc") }
    }

    @Test
    fun paiseToRupees_isExact() {
        assertEquals(BigDecimal("1500.05"), Money.paiseToRupees(150005))
        assertEquals(BigDecimal("0.01"), Money.paiseToRupees(1))
    }

    @Test
    fun parseRupees_acceptsValidInput() {
        assertEquals(150000L, Money.parseRupees("1500"))
        assertEquals(150000L, Money.parseRupees(" 1,500 "))
        assertEquals(150050L, Money.parseRupees("1500.5"))
        assertEquals(1L, Money.parseRupees("0.01"))
        assertEquals(0L, Money.parseRupees("0"))
        assertEquals(Money.MAX_PAISE, Money.parseRupees("1000000000"))
    }

    @Test
    fun parseRupees_rejectsInvalidInput() {
        listOf("", "  ", "abc", "-5", "1.234", "1e3", "NaN", "Infinity", ".5", "5.", "1000000000.01", "1 500")
            .forEach { assertNull("'$it' should be rejected", Money.parseRupees(it)) }
    }

    @Test
    fun legacyRupeesToPaise_usesShortestDecimalRepresentation() {
        // 0.285 * 100 in binary floating point is 28.499999..., which naive rounding turns into 28.
        assertEquals(29L, Money.legacyRupeesToPaise(0.285))
        assertEquals(100010L, Money.legacyRupeesToPaise(1000.1))
        assertEquals(150000L, Money.legacyRupeesToPaise(1500.0))
        assertEquals(33334L, Money.legacyRupeesToPaise(333.335))
        assertNull(Money.legacyRupeesToPaise(Double.NaN))
        assertNull(Money.legacyRupeesToPaise(Double.POSITIVE_INFINITY))
        assertNull(Money.legacyRupeesToPaise(1e300))
    }

    @Test
    fun largeValuesStayExact() {
        val big = 9_000_000_000_000_000L
        assertEquals(big, Money.rupeesToPaise(Money.paiseToRupees(big)))
    }

    @Test
    fun toInputText_roundTrips() {
        assertEquals("1500", Money.toInputText(150000))
        assertEquals("1500.50", Money.toInputText(150050))
        assertEquals(150050L, Money.parseRupees(Money.toInputText(150050)))
    }

    @Test
    fun format_usesRupeeSymbolAndIndianGrouping() {
        assertEquals("₹1,23,456.50", Money.format(12345650))
        assertEquals("₹12,34,56,789.00", Money.format(123456789_00))
        assertEquals("₹999.05", Money.format(99905))
        assertEquals("₹1,000.00", Money.format(100000))
        assertEquals("₹0.00", Money.format(0))
        assertEquals("-₹500.00", Money.format(-50000))
    }
}
