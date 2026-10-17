package com.cereal.script.aiagent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The user picks the AI provider in Settings → AI, so no string the script ships names one or a model id. */
class NeutralTextTest {
    private val named = Regex("anthropic|openai|gemini|claude|gpt-", RegexOption.IGNORE_CASE)
    private val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

    @Test
    fun `no provider or model id in a main string literal or the manifest`() {
        // Prices.kt keys its table by model id; those ids are looked up, never shown.
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" && it.name != "Prices.kt" }
        val hits =
            sources.flatMap { file -> literal.findAll(file.readText()).map { "${file.name}: ${it.value}" } }
                .plus(File("src/main/resources/manifest.json").readLines().map { "manifest.json: $it" })
                .filter { named.containsMatchIn(it.substringAfter(": ")) }
                .toList()
        assertEquals(emptyList(), hits)
    }
}
