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
    fun `cache reads on Sonnet 5_5 are 0_05x input`() {
        assertEquals(0.10, Prices.cost("claude-sonnet-5-5", Usage(0, 0, cacheRead = 1_000_000))!!, 1e-9)
    }

    @Test
    fun `Haiku 5_5 bills every token at 5x once the prompt is over 100K`() {
        // The threshold counts cache reads and writes; 100K exactly stays on the short-prompt rate.
        assertEquals(0.001, Prices.cost("claude-haiku-5-5", Usage(0, 0, cacheRead = 100_000))!!, 1e-12)
        assertEquals((1_000 * 0.50 + 1_000 * 2.50 + 95_000 * 0.05 + 5_000 * 0.625) / 1e6, Prices.cost("claude-haiku-5-5", Usage(1_000, 1_000, 95_000, 5_000))!!, 1e-12)
    }

    @Test
    fun `OpenRouter's dotted and canonical Claude ids are priced`() {
        assertEquals(10.0, Prices.cost("anthropic/claude-sonnet-5.5", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(10.0, Prices.cost("anthropic/claude-sonnet-5.5-20260928", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(15.0, Prices.cost("anthropic/claude-4.6-sonnet-20260217", Usage(0, 1_000_000))!!, 1e-9)
        assertEquals(50.0, Prices.cost("anthropic/claude-5-fable-20260609", Usage(0, 1_000_000))!!, 1e-9)
        // Dots stay in non-Claude ids.
        assertEquals(8.0, Prices.cost("openai/gpt-4.1", Usage(0, 1_000_000))!!, 1e-9)
    }

    @Test
    fun `GPT-5_6 and later bill cache writes at 1_25x input`() {
        assertEquals(0.125, Prices.cost("gpt-6-luna", Usage(0, 0, cacheWrite = 1_000_000))!!, 1e-9)
        assertEquals(0.0, Prices.cost("gpt-5.5", Usage(0, 0, cacheWrite = 1_000_000))!!, 1e-9)
    }

    @Test
    fun `unknown model has no price`() {
        assertNull(Prices.cost("llama3.3:70b", Usage(1000, 100)))
        assertNull(Prices.cost(null, Usage(1000, 100)))
    }
}
