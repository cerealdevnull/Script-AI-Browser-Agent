package com.cereal.script.aiagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest

/** What a finished goal run learned ([met]: that run met the goal; null in caches written before it was stored): replayed later without the model. Stored in `preference` under [key]. */
@Serializable
class ReplayCache(val steps: List<Step>, val check: Check? = null, val readouts: List<CachedReadout> = emptyList(), val met: Boolean? = null) {
    fun toJson() = Json.encodeToString(serializer(), this)

    companion object {
        /** `replay:` + sha256(goal, normalized start URL, sorted secret slot names). The model is not part of it. */
        fun key(goal: String, startUrl: String, secretNames: Collection<String>): String =
            "replay:" +
                MessageDigest.getInstance("SHA-256")
                    .digest("$goal\n${normalizeUrl(startUrl)}\n${secretNames.sorted().joinToString(",")}".toByteArray())
                    .joinToString("") { "%02x".format(it) }

        /** Null for a missing or unreadable entry (e.g. written by an incompatible release): the run learns again. */
        fun parse(json: String?): ReplayCache? = json?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() }

        private fun normalizeUrl(url: String) = url.trim().substringBefore('#').removeSuffix("/")
    }
}

/**
 * One action of a learned run. [fingerprint]: [Browser.stepTarget] JSON, null for tools without a ref; [args]: the
 * tool arguments without `ref`/`irreversible`, secrets as `{{secret:name}}`; [irreversible]: the model's raw flag;
 * [urlPattern]: [urlPattern] of the page after the step.
 */
@Serializable
class Step(val action: String, val fingerprint: String?, val args: JsonObject, val irreversible: Boolean, val urlPattern: String)

/** A true/false condition on one element, evaluated without the model. [decimal]: `","` or `"."` for a numeric Check, else null. */
@Serializable
class Check(val fingerprint: String, val condition: String, val expected: String? = null, val decimal: String? = null) {
    /** True/false on the live page; null when the target has no unique match (a miss, not "not yet"). */
    suspend fun evaluate(browser: Browser): Boolean? {
        val matches = browser.matches(Fingerprint(fingerprint))
        if (condition == "exists") return if (matches.size > 1) null else matches.size == 1
        return holds(matches.singleOrNull() ?: return null)
    }

    /**
     * True/false for [match]; null (a miss) when a numeric Check finds no single number, `decreased` finds 2+ (0 is
     * "not yet"), or for an unknown condition (a newer release's cache). A change Check compares with its baseline.
     */
    fun holds(match: Match): Boolean? {
        val expected = collapse(expected.orEmpty())
        if (condition == "changed") return collapse(match.text) != expected
        if (condition == "decreased") {
            val now = numbers(match.text, decimal ?: return null)
            if (now.size > 1) return null
            return now.isNotEmpty() && now[0] < (number(expected, decimal) ?: return null)
        }
        if (condition in NUMERIC) {
            val c = (number(match.text, decimal ?: return null) ?: return null).compareTo(expected.toBigDecimalOrNull() ?: return null)
            return when (condition) {
                "below" -> c < 0
                "atMost" -> c <= 0
                "above" -> c > 0
                else -> c >= 0
            }
        }
        return when (condition) {
            "textContains" -> expected in match.text
            "textAbsent" -> expected !in match.text
            "enabled" -> match.enabled
            else -> null
        }
    }

    val isChange get() = condition in CHANGE

    /** This change Check with [text] as its new baseline. */
    fun rebased(text: String) = Check(fingerprint, condition, collapse(text), decimal)

    /** The condition in words for notifications (never "Check"): `Long Nose Pliers shows “In stock”`. */
    fun words(): String {
        val fp = Json.parseToJsonElement(fingerprint).jsonObject
        // Named by its anchor (heading/label) or column header; else the page.
        val where = (fp["anchor"]?.jsonObject?.get("text") ?: fp["column"])?.jsonPrimitive?.content ?: "the page"
        val tag = fp["tag"]?.jsonPrimitive?.content ?: "element"
        return when (condition) {
            "textContains" -> "$where shows “$expected”"
            "textAbsent" -> "$where no longer shows “$expected”"
            "enabled" -> "the $tag at $where is enabled"
            "below" -> "$where is below $expected"
            "atMost" -> "$where is at most $expected"
            "above" -> "$where is above $expected"
            "atLeast" -> "$where is at least $expected"
            "changed" -> "$where changes from “$expected”"
            "decreased" -> "$where goes below “$expected”"
            else -> "the $tag at $where appears"
        }
    }

    companion object {
        val NUMERIC = setOf("below", "atMost", "above", "atLeast")
        val CHANGE = setOf("changed", "decreased")
        val CONDITIONS = setOf("textContains", "textAbsent", "enabled", "exists") + NUMERIC + CHANGE

        fun collapse(text: String) = text.replace(WHITESPACE, " ").trim()

        /** Every number in [text] with [decimal] (`,` or `.`) as separator; grouping marks only between groups of exactly 3 digits. */
        fun numbers(text: String, decimal: String): List<BigDecimal> {
            val group = Regex("[${if (decimal == ",") "." else ","}   ']")
            val number = Regex("""(?<!\d)(?:\d{1,3}(?:$group\d{3})+|\d+)(?:${Regex.escape(decimal)}\d+)?(?!\d)""")
            return number.findAll(text).map { BigDecimal(it.value.replace(group, "").replace(decimal, ".")) }.toList()
        }

        /** The one number in [text]; null for 0 or 2+ numbers. */
        fun number(text: String, decimal: String): BigDecimal? = numbers(text, decimal).singleOrNull()

        private val WHITESPACE = Regex("\\s+")
    }
}

@Serializable
class CachedReadout(val label: String, val fingerprint: String)

// ponytail: host + path, so a changed query string still matches; use a real pattern if sites put ids in the path.
fun urlPattern(url: String): String = runCatching { URI(url).let { "${it.host}${it.path}" } }.getOrDefault(url)
