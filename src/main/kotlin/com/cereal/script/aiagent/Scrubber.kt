package com.cereal.script.aiagent

import com.cereal.sdk.models.Secret

/**
 * Keeps secret values out of model traffic and logs. Each value revealed through [reveal] becomes `{{secret:name}}`.
 */
class Scrubber {
    private val revealed = mutableMapOf<String, String>() // value -> name

    fun reveal(name: String, secret: Secret): String = secret.reveal().also { if (it.isNotEmpty()) revealed[it] = name }

    /** True once any secret was revealed (typed) in this run. */
    val anyRevealed: Boolean get() = revealed.isNotEmpty()

    // Longest first, so a value containing another value is replaced whole. Scrub before any truncation: a cut-off
    // value no longer matches (the snapshot does this per node, see Browser.snapshot).
    fun forModel(text: String): String =
        revealed.entries.sortedByDescending { it.key.length }.fold(text) { t, (value, name) -> t.replace(value, "{{secret:$name}}") }

    fun forLog(text: String): String = forModel(text)
}
