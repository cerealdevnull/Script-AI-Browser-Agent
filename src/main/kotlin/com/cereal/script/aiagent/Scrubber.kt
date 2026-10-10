package com.cereal.script.aiagent

import com.cereal.sdk.models.Secret

/**
 * Keeps secret values out of model traffic and logs. Each value revealed through [reveal] becomes `{{secret:name}}`;
 * logs additionally hide the API key.
 */
class Scrubber(private val apiKey: String?) {
    private val revealed = mutableMapOf<String, String>() // value -> name

    fun reveal(name: String, secret: Secret): String = secret.reveal().also { if (it.isNotEmpty()) revealed[it] = name }

    // Longest first, so a value containing another value is replaced whole.
    // ponytail: the snapshot caps field values at 80 chars and the page at 14k, so a cut-off value isn't matched;
    // scrub before truncating if longer secrets matter.
    fun forModel(text: String): String =
        revealed.entries.sortedByDescending { it.key.length }.fold(text) { t, (value, name) -> t.replace(value, "{{secret:$name}}") }

    fun forLog(text: String): String = forModel(text).let { if (apiKey.isNullOrEmpty()) it else it.replace(apiKey, "{{api_key}}") }
}
