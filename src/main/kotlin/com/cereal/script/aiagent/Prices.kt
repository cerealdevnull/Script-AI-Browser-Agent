package com.cereal.script.aiagent

/** $ per million tokens. [input] is uncached input; cache writes are the 5-minute TTL rate. */
class Price(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double = 0.0)

/** The built-in price table, shipped with each script release (standard tier, short-context rates). */
object Prices {
    // Sources, checked 2026-10-10: Anthropic model pricing (claude-api reference, cached 2026-10-06),
    // developers.openai.com/api/docs/pricing, ai.google.dev/gemini-api/docs/pricing (rates until 2026-12-31).
    private fun claude(input: Double, output: Double, cacheRead: Double = input * 0.1) = Price(input, output, cacheRead, input * 1.25)

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
            // ponytail: Haiku 5.5's > 100K-token prompt rate ($0.50 / $2.50) is ignored; snapshots stay well below it.
            "claude-haiku-5-5" to claude(0.10, 0.50),
            "claude-haiku-4-5" to claude(1.0, 5.0),
            "gpt-6-astra" to Price(10.0, 50.0, 1.0),
            "gpt-6.1-sol" to Price(2.0, 10.0, 0.10),
            "gpt-6-sol" to Price(2.0, 10.0, 0.20),
            "gpt-6-luna" to Price(0.10, 0.50, 0.01),
            "gpt-5.6-sol" to Price(4.0, 20.0, 0.40),
            "gpt-5.6-terra" to Price(2.0, 12.0, 0.20),
            "gpt-5.6-luna" to Price(0.20, 1.20, 0.02),
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
     * `provider/` prefix and dotted Claude versions (OpenRouter) and a `-<snapshot>` suffix (`gpt-4o-2024-08-06`); the longest matching id wins.
     */
    fun cost(model: String?, usage: Usage): Double? {
        // OpenRouter writes Claude versions with dots (claude-sonnet-5.5).
        val id = model?.substringAfterLast('/')?.let { if (it.startsWith("claude-")) it.replace('.', '-') else it } ?: return null
        val price = table.entries.filter { id == it.key || id.startsWith(it.key + "-") }.maxByOrNull { it.key.length }?.value ?: return null
        return (usage.input * price.input + usage.output * price.output + usage.cacheRead * price.cacheRead + usage.cacheWrite * price.cacheWrite) / 1e6
    }
}

/** `$1.23`; [decimals] 4 for per-step amounts. */
fun usd(amount: Double, decimals: Int = 2) = "$" + String.format(java.util.Locale.ROOT, "%.${decimals}f", amount)
