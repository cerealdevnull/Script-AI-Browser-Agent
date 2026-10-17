package com.cereal.script.aiagent

import com.cereal.sdk.component.notification.telegram.model.TelegramParseMode
import com.cereal.sdk.models.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How each channel lays out a result message: a table of inputs, cheaper here than through the whole script. */
class ResultMessageTest {
    private val goal = "Tell me whether Long Nose Pliers is in stock"
    private val url = "https://shop.test/product/long-nose-pliers"

    private val onceMet =
        ResultMessage(
            Kind.Met,
            "Long Nose Pliers is in stock.",
            summary = "It ships tomorrow.",
            readouts = listOf("Price" to "$14.15", "Stock" to "In stock"),
            cost = "Cost: $0.01",
            url = url,
        )

    @Test
    fun `a met result is a green Discord embed that pings, with the page link, Readout fields and a footer`() {
        val n = onceMet.notification(goal)
        val discord = n.discordMessage!!
        assertEquals("@here", discord.content)
        val embed = discord.embeds!!.single()
        assertEquals("3066993", embed.color)
        assertEquals("Long Nose Pliers is in stock.", embed.title)
        assertEquals(url, embed.url)
        assertEquals("It ships tomorrow.", embed.description)
        assertEquals(listOf(Triple("Price", "$14.15", true), Triple("Stock", "In stock", true)), embed.fields!!.map { Triple(it.name, it.value, it.inline) })
        assertEquals("$goal · Cost: $0.01 · shop.test", embed.footer!!.text)
        assertEquals(null, embed.timestamp)
    }

    @Test
    fun `a met result is Telegram HTML with a sound, link previews off`() {
        val telegram = onceMet.notification(goal).telegramMessage!!
        assertEquals(TelegramParseMode.HTML, telegram.parseMode)
        assertEquals(true, telegram.disableWebPagePreview)
        assertEquals(false, telegram.disableNotification)
        assertEquals(
            """
            ✅ <b>Long Nose Pliers is in stock.</b>
            Price: <b>$14.15</b>
            Stock: <b>In stock</b>
            <i>It ships tomorrow.</i>
            <a href="$url">Open page</a> · Cost: $0.01
            $goal
            """.trimIndent(),
            telegram.text,
        )
    }

    @Test
    fun `desktop and email stay plain`() {
        val n = onceMet.notification(goal)
        assertEquals("AI agent: $goal", n.title)
        assertEquals("Long Nose Pliers is in stock.\nIt ships tomorrow.\nPrice: $14.15\nStock: In stock\nCost: $0.01", n.message)
    }

    @Test
    fun `each kind has its colour, and only met, change and confirmation ping or make a sound`() {
        // kind -> colour, @here, silent on Telegram
        val expected =
            mapOf(
                Kind.Met to Triple("3066993", "@here", false),
                Kind.Change to Triple("3066993", "@here", false),
                Kind.NotMet to Triple("15965202", null, true),
                Kind.Failed to Triple("15158332", null, false),
                Kind.Confirmation to Triple("5793266", "@here", false),
                Kind.Watching to Triple("9807270", null, true),
            )
        expected.forEach { (kind, e) ->
            val n = ResultMessage(kind, "Headline", cost = "Cost: $0").notification(goal)
            assertEquals(e, Triple(n.discordMessage!!.embeds!!.single().color, n.discordMessage!!.content, n.telegramMessage!!.disableNotification), kind.name)
        }
    }

    @Test
    fun `a not-met Repeat run shows the action line before the summary`() {
        val n = ResultMessage(Kind.NotMet, "Goal not met", action = FORGET_STEPS, summary = "Still sold out.", cost = "Cost: $0", url = url).notification(goal)
        assertEquals("$FORGET_STEPS\nStill sold out.", n.discordMessage!!.embeds!!.single().description)
        assertEquals(
            "⏳ <b>Goal not met</b>\n$FORGET_STEPS\n<i>Still sold out.</i>\n<a href=\"$url\">Open page</a> · Cost: $0\n$goal",
            n.telegramMessage!!.text,
        )
    }

    @Test
    fun `a change shows old to new as the description`() {
        val n = ResultMessage(Kind.Change, "Price dropped", summary = "€ 119,99 → € 99,99", cost = "Cost: $0", url = url).notification(goal)
        assertEquals("€ 119,99 → € 99,99", n.discordMessage!!.embeds!!.single().description)
        assertEquals("🔔 <b>Price dropped</b>\n<i>€ 119,99 → € 99,99</i>\n<a href=\"$url\">Open page</a> · Cost: $0\n$goal", n.telegramMessage!!.text)
    }

    @Test
    fun `a confirmation request says where to answer, with the page link and no fields`() {
        val n =
            ResultMessage(Kind.Confirmation, "Place this order on shop.test?", action = "Confirmation needed: answer in the window that opened.", url = url)
                .notification(goal)
        val embed = n.discordMessage!!.embeds!!.single()
        assertEquals("Place this order on shop.test?", embed.title)
        assertEquals("Answer in the Cereal window.", embed.description)
        assertEquals(url, embed.url)
        assertEquals(emptyList(), embed.fields.orEmpty())
        assertEquals("$goal · shop.test", embed.footer!!.text)
        assertEquals("❓ <b>Place this order on shop.test?</b>\nAnswer in the Cereal window.\n<a href=\"$url\">Open page</a>\n$goal", n.telegramMessage!!.text)
        assertEquals("Place this order on shop.test?\nConfirmation needed: answer in the window that opened.", n.message)
    }

    @Test
    fun `a failure without a page has no link, and its footer leaves out the host`() {
        val n = ResultMessage(Kind.Failed, "Couldn't start Google Chrome.", action = "Check that Chrome is installed.", cost = "Cost: $0").notification(goal)
        val embed = n.discordMessage!!.embeds!!.single()
        assertEquals(null, embed.url)
        assertEquals("$goal · Cost: $0", embed.footer!!.text)
        assertEquals("❌ <b>Couldn't start Google Chrome.</b>\nCheck that Chrome is installed.\nCost: $0\n$goal", n.telegramMessage!!.text)
    }

    @Test
    fun `a 3,000-character Readout and 30 Readouts stay within every limit, keeping headline and cost`() {
        val long = "x".repeat(3000)
        val messages =
            listOf(
                onceMet.copy(summary = "s".repeat(5000), readouts = listOf("Description" to long)),
                onceMet.copy(summary = "s".repeat(5000), readouts = (1..30).map { "Readout $it" to "v".repeat(3000) }),
                onceMet.copy(headline = "h".repeat(300), readouts = (1..30).map { "Readout $it" to "<&>".repeat(1000) }),
            )
        messages.forEach { m ->
            val n = m.notification("g".repeat(300))
            val embed = n.discordMessage!!.embeds!!.single()
            val fields = embed.fields.orEmpty()
            assertTrue(embed.title!!.length <= 256 && embed.description.orEmpty().length <= 4096 && embed.footer!!.text!!.length <= 2048)
            assertTrue(fields.size <= 25 && fields.all { it.name.length <= 256 && it.value.length in 1..1024 })
            val total = embed.title!!.length + embed.description.orEmpty().length + embed.footer!!.text!!.length + fields.sumOf { it.name.length + it.value.length }
            assertTrue(total <= 6000, "embed is $total characters")
            assertTrue("Cost: $0.01" in embed.footer!!.text!!)
            val text = n.telegramMessage!!.text
            assertTrue(text.length <= 4096, "Telegram text is ${text.length} characters")
            assertTrue(text.startsWith("✅ <b>h") || text.startsWith("✅ <b>Long Nose Pliers is in stock.</b>"))
            assertTrue("Cost: $0.01" in text)
        }
        // Over the limit only by the summary: the Readouts stay whole.
        val n = onceMet.copy(summary = "s".repeat(5000), readouts = listOf("Description" to long)).notification(goal)
        assertTrue("<b>$long</b>" in n.telegramMessage!!.text)
        assertTrue("<i>sss" in n.telegramMessage!!.text && "…</i>" in n.telegramMessage!!.text)
    }

    @Test
    fun `a 4,000-character goal, a long headline and action still fit Telegram, keeping the cost line`() {
        val m = onceMet.copy(headline = "h".repeat(3000), action = "a".repeat(3000))
        val text = m.notification("g".repeat(4000)).telegramMessage!!.text
        assertTrue(text.length <= 4096, "Telegram text is ${text.length} characters")
        assertTrue("Cost: $0.01" in text && text.startsWith("✅ <b>hhh"), text)
    }

    @Test
    fun `a URL with an underscore and text with angle brackets and ampersands arrive escaped and unbroken`() {
        val link = "https://shop.test/long_nose_pliers?a=1&b=2"
        val n = onceMet.copy(headline = "Pliers <b>& co</b> in stock.", readouts = listOf("Name" to "Tom & Jerry's <tools>"), url = link).notification(goal)
        val text = n.telegramMessage!!.text
        assertTrue("✅ <b>Pliers &lt;b&gt;&amp; co&lt;/b&gt; in stock.</b>" in text, text)
        assertTrue("Name: <b>Tom &amp; Jerry's &lt;tools&gt;</b>" in text, text)
        assertTrue("<a href=\"https://shop.test/long_nose_pliers?a=1&amp;b=2\">Open page</a>" in text, text)
        // Discord embeds take text as is.
        assertEquals(link, n.discordMessage!!.embeds!!.single().url)
        assertEquals("Pliers <b>& co</b> in stock.", n.discordMessage!!.embeds!!.single().title)
    }

    @Test
    fun `the page link is left out before a page loads and when the URL held a secret`() {
        val scrubber = Scrubber()
        scrubber.reveal("token", Secret("abc123"))
        assertEquals(null, pageLink(null, scrubber))
        assertEquals(null, pageLink("about:blank", scrubber))
        assertEquals(null, pageLink("chrome-error://chromewebdata/", scrubber))
        assertEquals(null, pageLink("https://shop.test/account?token=abc123", scrubber))
        assertEquals("https://shop.test/account", pageLink("https://shop.test/account", scrubber))
    }
}
