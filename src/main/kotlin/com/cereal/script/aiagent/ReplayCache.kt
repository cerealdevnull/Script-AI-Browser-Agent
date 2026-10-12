package com.cereal.script.aiagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.MessageDigest

/** What a finished goal run learned: replayed later without the model. Stored in `preference` under [key]. */
@Serializable
class ReplayCache(val steps: List<Step>, val check: Check? = null, val readouts: List<CachedReadout> = emptyList()) {
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

/** A true/false condition on one element, evaluated without the model. */
@Serializable
class Check(val fingerprint: String, val condition: String, val expected: String? = null) {
    /** True/false on the live page; null when the target has no unique match (a miss, not "not yet"). */
    suspend fun evaluate(browser: Browser): Boolean? {
        val matches = browser.matches(Fingerprint(fingerprint))
        if (condition == "exists") return if (matches.size > 1) null else matches.size == 1
        val match = matches.singleOrNull() ?: return null
        val expected = expected.orEmpty().replace(WHITESPACE, " ").trim()
        return when (condition) {
            "textContains" -> expected in match.text
            "textAbsent" -> expected !in match.text
            else -> match.enabled
        }
    }

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
            else -> "the $tag at $where appears"
        }
    }

    companion object {
        val CONDITIONS = setOf("textContains", "textAbsent", "enabled", "exists")
        private val WHITESPACE = Regex("\\s+")
    }
}

@Serializable
class CachedReadout(val label: String, val fingerprint: String)

// ponytail: host + path, so a changed query string still matches; use a real pattern if sites put ids in the path.
fun urlPattern(url: String): String = runCatching { URI(url).let { "${it.host}${it.path}" } }.getOrDefault(url)
