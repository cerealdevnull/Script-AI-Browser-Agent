package com.cereal.script.aiagent

import com.cereal.sdk.component.notification.Notification
import com.cereal.sdk.component.notification.discord.model.DiscordEmbed
import com.cereal.sdk.component.notification.discord.model.DiscordMessage
import com.cereal.sdk.component.notification.discord.model.embed.FieldEmbed
import com.cereal.sdk.component.notification.discord.model.embed.FooterEmbed
import com.cereal.sdk.component.notification.telegram.model.TelegramMessage
import com.cereal.sdk.component.notification.telegram.model.TelegramParseMode
import java.net.URI

/** What a result message is about: sets the Discord colour, the `@here` ping, the Telegram emoji and sound. */
internal enum class Kind(val color: String, val emoji: String, val ping: Boolean, val silent: Boolean) {
    Met("3066993", "✅", ping = true, silent = false),
    Change("3066993", "🔔", ping = true, silent = false),
    NotMet("15965202", "⏳", ping = false, silent = true),
    Failed("15158332", "❌", ping = false, silent = false),
    Confirmation("5793266", "❓", ping = true, silent = false),
    Watching("9807270", "👀", ping = false, silent = true),
}

/**
 * The result message (see CONTEXT.md): headline, action line, summary (or `old → new`), Readouts, notes, cost line,
 * page link. Desktop and email get it as plain lines; Discord as an embed; Telegram as HTML.
 */
internal data class ResultMessage(
    val kind: Kind,
    val headline: String,
    val action: String? = null,
    val summary: String? = null,
    val readouts: List<Pair<String, String>> = emptyList(),
    val notes: List<String> = emptyList(),
    val cost: String? = null,
    val url: String? = null,
) {
    val plain get() = (listOfNotNull(headline, action, summary) + readouts.map { (l, t) -> "$l: $t" } + notes + listOfNotNull(cost)).joinToString("\n")

    // A confirmation's plain action line points at the dialog; the chat apps can only say where it is.
    private val richAction get() = if (kind == Kind.Confirmation) "Answer in the Cereal window." else action

    fun notification(goal: String): Notification {
        val title = "AI agent: " + if (goal.length > 60) goal.take(60) + "…" else goal
        return Notification(title, plain, discord(goal), telegram(goal))
    }

    // Each text is cut to its own limit; then the summary, then the Readout values shrink until the embed fits.
    private fun discord(goal: String): DiscordMessage {
        val host = url?.let { runCatching { URI(it).host }.getOrNull() }
        val footer = listOfNotNull(goal.cut(100), cost, host).joinToString(" · ").cut(2048)
        val fields = readouts.take(25)
        fun embed(summaryMax: Int, valueMax: Int) =
            DiscordEmbed(
                title = headline.cut(256),
                description = (listOfNotNull(richAction, summary?.cut(summaryMax)) + notes).joinToString("\n").cut(4096).ifEmpty { null },
                url = url,
                color = kind.color,
                footer = FooterEmbed(text = footer),
                fields = fields.map { (l, t) -> FieldEmbed(l.cut(256), t.cut(minOf(valueMax, 1024)).ifEmpty { "-" }, true) },
                type = null, timestamp = null, image = null, thumbnail = null, video = null, provider = null, author = null,
            )
        val embed = fit(EMBED_MAX, ::embed) { e -> e.title.orEmpty().length + e.description.orEmpty().length + e.footer!!.text.orEmpty().length + e.fields!!.sumOf { it.name.length + it.value.length } }
        return DiscordMessage(content = "@here".takeIf { kind.ping }, embeds = listOf(embed))
    }

    private fun telegram(goal: String): TelegramMessage {
        fun text(summaryMax: Int, valueMax: Int) =
            (
                listOfNotNull("${kind.emoji} <b>${esc(headline.cut(256))}</b>", richAction?.let { esc(it.cut(512)) }) +
                    readouts.map { (l, t) -> "${esc(l)}: <b>${esc(t.cut(valueMax))}</b>" } +
                    listOfNotNull(summary?.let { "<i>${esc(it.cut(summaryMax))}</i>" }) +
                    notes.map { esc(it.cut(512)) } +
                    listOfNotNull(
                        listOfNotNull(url?.let { "<a href=\"${esc(it).replace("\"", "&quot;")}\">Open page</a>" }, cost?.let(::esc)).joinToString(" · ").ifEmpty { null },
                        esc(goal.cut(100)),
                    )
                ).joinToString("\n")
        val text = fit(TELEGRAM_MAX, ::text) { it.length }
        return TelegramMessage(text = text, parseMode = TelegramParseMode.HTML, disableWebPagePreview = true, disableNotification = kind.silent)
    }

    /**
     * Renders with the summary cut first, then the Readout values, until [size] is within [max]. ponytail: the
     * Readout labels are never cut (headline, action, notes and goal have fixed caps), so many very long labels could still overflow; nothing makes those today.
     */
    private fun <T> fit(max: Int, render: (Int, Int) -> T, size: (T) -> Int): T {
        var summaryMax = summary?.length ?: 0
        var valueMax = readouts.maxOfOrNull { it.second.length } ?: 0
        while (true) {
            val r = render(summaryMax, valueMax)
            val over = size(r) - max
            if (over <= 0) return r
            when {
                summaryMax > 1 -> summaryMax = maxOf(1, summaryMax - over)
                valueMax > 1 -> valueMax = maxOf(1, valueMax - maxOf(1, over / readouts.size))
                else -> return r
            }
        }
    }
}

/** The page link a notification may carry: an http(s) URL that held no secret (scrubbing would have changed it). */
internal fun pageLink(url: String?, scrubber: Scrubber): String? =
    url?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && scrubber.forLog(it) == it }

private const val EMBED_MAX = 6000
private const val TELEGRAM_MAX = 4096

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun String.cut(max: Int) = if (length > max) take(max - 1) + "…" else this
