package com.cereal.script.aiagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Price-table lookup: not cheaply reachable through the main seam. */
class PricesTest {
    @Test
    fun `prices every token kind`() {
        // 1M of each at $4 / $20 / $0.20 / $5.
        assertEquals(29.2, Prices.cost("claude-opus-5-5", Usage(1_000_000, 1_000_000, 1_000_000, 1_000_000))!!, 1e-9)
    }

    @Test
    fun `matches dated snapshots and provider-prefixed ids`() {
        assertEquals(10.0, Prices.cost("gpt-4o-2024-08-06", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(0.6, Prices.cost("gpt-4o-mini-2024-07-18", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(20.0, Prices.cost("anthropic/claude-opus-5-5", Usage(0, 1_000_000))!!, 1e-9)
    }

    @Test
    fun `prices OpenRouter model ids, which write the Claude version with a dot`() {
        assertEquals(10.0, Prices.cost("anthropic/claude-sonnet-5.5", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(5.0, Prices.cost("anthropic/claude-haiku-4.5", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(15.0, Prices.cost("openai/gpt-5.4", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(10.0, Prices.cost("google/gemini-2.5-pro", Usage(0, 1_000_000))!!, 1e-9)
    }

    @Test
    fun `unknown model has no price`() {
        assertNull(Prices.cost("llama3.3:70b", Usage(1000, 100)))
        assertNull(Prices.cost(null, Usage(1000, 100)))
    }
}
