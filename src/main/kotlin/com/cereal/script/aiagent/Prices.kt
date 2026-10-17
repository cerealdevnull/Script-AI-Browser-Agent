package com.cereal.script.aiagent

/** Token usage of one call; [input] is the uncached input (the SDK's `LlmUsage.inputUncached`). */
class Usage(val input: Long, val output: Long, val cacheRead: Long = 0, val cacheWrite: Long = 0)

/**
 * $ per million tokens. [input] is uncached input; cache writes are the 5-minute TTL rate. [over100K] replaces every
 * rate when the prompt (input + cache reads + cache writes) is over 100,000 tokens.
 */
class Price(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double = 0.0, val over100K: Price? = null)

/** The built-in price table, shipped with each script release (standard tier, short-context rates). */
object Prices {
    // Sources, checked 2026-10-11: platform.claude.com/docs/en/about-claude/pricing,
    // developers.openai.com/api/docs/pricing, ai.google.dev/gemini-api/docs/pricing (rates until 2026-12-31).
    private fun claude(input: Double, output: Double, cacheRead: Double = input * 0.1, over100K: Price? = null) =
        Price(input, output, cacheRead, input * 1.25, over100K)

    // GPT-5.6 and later bill cache writes at 1.25x input.
    private fun gpt(input: Double, output: Double, cacheRead: Double) = Price(input, output, cacheRead, input * 1.25)

    private val table =
        mapOf(
            "claude-fable-5-1" to claude(10.0, 50.0, 0.25),
            "claude-fable-5" to claude(10.0, 50.0),
            "claude-opus-5-5" to claude(4.0, 20.0, 0.20),
            "claude-opus-5" to claude(5.0, 25.0),
            "claude-opus-4-8" to claude(5.0, 25.0),
            "claude-opus-4-7" to claude(5.0, 25.0),
            "claude-opus-4-6" to claude(5.0, 25.0),
            "claude-sonnet-5-5" to claude(2.0, 10.0, 0.10),
            "claude-sonnet-5" to claude(2.0, 10.0),
            "claude-sonnet-4-6" to claude(3.0, 15.0),
            // The Anthropic history keeps every snapshot, so a run's prompt passes 100K around step 19.
            "claude-haiku-5-5" to claude(0.10, 0.50, over100K = claude(0.50, 2.50)),
            "claude-haiku-4-5" to claude(1.0, 5.0),
            "gpt-6-astra" to gpt(10.0, 50.0, 1.0),
            "gpt-6.1-sol" to gpt(2.0, 10.0, 0.10),
            "gpt-6-sol" to gpt(2.0, 10.0, 0.20),
            "gpt-6-luna" to gpt(0.10, 0.50, 0.01),
            "gpt-5.6-sol" to gpt(4.0, 20.0, 0.40),
            "gpt-5.6-terra" to gpt(2.0, 12.0, 0.20),
            "gpt-5.6-luna" to gpt(0.20, 1.20, 0.02),
            "gpt-5.5" to Price(5.0, 30.0, 0.50),
            "gpt-5.4" to Price(2.50, 15.0, 0.25),
            "gpt-5.4-mini" to Price(0.75, 4.50, 0.075),
            "gpt-5.4-nano" to Price(0.20, 1.25, 0.02),
            "gpt-5" to Price(1.25, 10.0, 0.125),
            "gpt-5-mini" to Price(0.25, 2.0, 0.025),
            "gpt-5-nano" to Price(0.05, 0.40, 0.005),
            "gpt-4.1" to Price(2.0, 8.0, 0.50),
            "gpt-4.1-mini" to Price(0.40, 1.60, 0.10),
            "gpt-4o" to Price(2.50, 10.0, 1.25),
            "gpt-4o-mini" to Price(0.15, 0.60, 0.075),
            "gemini-3.8-flash" to Price(0.75, 3.75, 0.075),
            "gemini-3.6-flash" to Price(0.75, 3.75, 0.075),
            "gemini-3.5-flash-lite" to Price(0.30, 2.50, 0.03),
            "gemini-3.1-pro-preview" to Price(2.0, 12.0, 0.20),
            "gemini-3.1-flash-lite" to Price(0.25, 1.50, 0.025),
            "gemini-3-flash-preview" to Price(0.50, 3.0, 0.05),
            "gemini-2.5-pro" to Price(1.25, 10.0, 0.125),
            "gemini-2.5-flash" to Price(0.30, 2.50, 0.03),
            "gemini-2.5-flash-lite" to Price(0.10, 0.40, 0.01),
        )

    /**
     * Dollars for one call by [model] (the response's model id), or null when the table doesn't know it. Accepts a
     * `provider/` prefix (OpenRouter) and a `-<snapshot>` suffix (`gpt-4o-2024-08-06`); the longest matching id wins.
     * OpenRouter's Claude ids (`claude-sonnet-5.5`, canonical `claude-4.6-sonnet-20260217`) map to Anthropic's.
     */
    fun cost(model: String?, usage: Usage): Double? {
        val id = model?.substringAfterLast('/')?.let { if (it.startsWith("claude-")) it.replace(OPENROUTER_CLAUDE, "claude-$2-$1").replace('.', '-') else it } ?: return null
        val base = table.entries.filter { id == it.key || id.startsWith(it.key + "-") }.maxByOrNull { it.key.length }?.value ?: return null
        val price = base.over100K?.takeIf { usage.input + usage.cacheRead + usage.cacheWrite > 100_000 } ?: base
        return (usage.input * price.input + usage.output * price.output + usage.cacheRead * price.cacheRead + usage.cacheWrite * price.cacheWrite) / 1e6
    }

    private val OPENROUTER_CLAUDE = Regex("^claude-([\\d.]+)-([a-z]+)")
}

/** `$1.23`; [decimals] 4 for per-step amounts. */
fun usd(amount: Double, decimals: Int = 2) = "$" + String.format(java.util.Locale.ROOT, "%.${decimals}f", amount)
