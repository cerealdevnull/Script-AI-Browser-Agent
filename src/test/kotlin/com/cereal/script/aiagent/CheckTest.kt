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
    fun `a missing frame is a miss for every condition, while no match makes exists false`() {
        Check.CONDITIONS.forEach { assertNull(check(it).evaluate(null as List<Match>?), it) }
        assertEquals(false, check("exists").evaluate(emptyList()))
    }

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

/** Change Checks: compare the element's value now with the baseline in expected. */
class ChangeCheckTest {
    private fun decreased(baseline: String) = Check("{}", "decreased", baseline, ",")

    @Test
    fun `decreased holds when the one number went down, no number is not yet, two are a miss`() {
        assertEquals(true, decreased("€ 119,99").holds(Match("€ 99,99", true)))
        assertEquals(false, decreased("€ 119,99").holds(Match("€ 119,99", true)))
        assertEquals(false, decreased("€ 119,99").holds(Match("Sold out", true)), "no number: not yet, not a miss")
        assertNull(decreased("€ 119,99").holds(Match("Was €150 now €99", true)))
    }

    @Test
    fun `changed holds when the text differs, ignoring whitespace`() {
        val changed = Check("{}", "changed", "Out of stock")
        assertEquals(false, changed.holds(Match("  Out   of\nstock ", true)))
        assertEquals(true, changed.holds(Match("In stock", true)))
    }

    @Test
    fun `a new baseline is the text with whitespace collapsed`() {
        assertEquals("€ 99,99", decreased("€ 119,99").rebased(" €  99,99\n").expected)
    }
}
