package com.cereal.script.aiagent

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Numeric Checks: reading the one number in an element's text, and comparing it. A table of inputs, so unit-tested. */
class CheckTest {
    @Test
    fun `reads prices in either decimal style, with grouping marks only between groups of three`() {
        assertEquals(BigDecimal("119.99"), Check.number("€ 119,99", ","))
        assertEquals(BigDecimal("1299.00"), Check.number("\$1,299.00", "."))
        assertEquals(BigDecimal("1299.00"), Check.number("1.299,00 €", ","))
        assertEquals(BigDecimal("99"), Check.number("from €99", ","))
        assertEquals(BigDecimal("1299"), Check.number("CHF 1'299", "."))
        assertEquals(BigDecimal("1299.5"), Check.number("1 299,5 €", ","))
    }

    @Test
    fun `an element without exactly one number, or read with the wrong decimal, has no number`() {
        assertNull(Check.number("Was €150 now €119,99", ","))
        assertNull(Check.number("Sold out", ","))
        assertNull(Check.number("€ 119,99", "."))
    }

    private fun check(condition: String, expected: String = "50") = Check("{}", condition, expected, ",")

    @Test
    fun `thresholds compare the number with expected`() {
        assertEquals(true, check("atMost").holds(Match("€ 50", true)))
        assertEquals(false, check("below").holds(Match("€ 50", true)))
        assertEquals(true, check("below").holds(Match("€ 49,99", true)))
        assertEquals(true, check("atLeast").holds(Match("€ 50,00", true)))
        assertEquals(false, check("above").holds(Match("€ 50,00", true)))
        assertEquals(true, check("above").holds(Match("€ 1.050", true)))
        assertNull(check("below").holds(Match("Sold out", true)), "no number is a miss")
    }

    @Test
    fun `an unknown condition, as from a newer release's cache, is a miss`() {
        assertNull(check("between").holds(Match("€ 50", true)))
        assertEquals(true, Check("{}", "enabled").holds(Match("x", true)))
    }
}
