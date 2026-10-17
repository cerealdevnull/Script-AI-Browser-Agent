package com.cereal.script.aiagent

import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Manual smoke check (docs/acceptance.md): the real script on real sites, to find what breaks outside Toolshop.
 * Runs on gemini-3.8-flash through the test-only model client (via the traffic proxy); skips without GEMINI_API_KEY.
 * Not pass/fail: every site's log and model traffic land in build/smoke/<id>/ to be judged afterwards.
 * Read-only goals, and any confirmation is declined. Rerun a subset with SMOKE_ONLY=nike,ad.
 */
@Tag("acceptance")
class SmokeTest {
    class Site(val id: String, val url: String, val goal: String, val mode: RunMode, val runs: Int = 1, val secrets: List<Pair<String?, Secret?>> = emptyList())

    private val sites =
        listOf(
            Site("nike", "https://www.nike.com/nl/w?q=air%20force%201", "Notify me when the men's Nike Air Force 1 '07 costs less than €50", RunMode.Repeat, 3),
            Site("zalando", "https://www.zalando.nl/catalogus/?q=nike+air+force+1", "Notify me when the Nike Sportswear Air Force 1 '07 sneaker is in stock in size 48.5", RunMode.Repeat, 3),
            Site("ticketmaster", "https://www.ticketmaster.nl/", "Notify me when Ticketmaster lists any Taylor Swift event in the Netherlands", RunMode.Repeat, 2),
            Site("ad", "https://www.ad.nl/", "Tell me the headline of the top story", RunMode.Once),
            // "Try it Yourself" opens a new tab whose result pane is an iframe.
            Site("w3schools", "https://www.w3schools.com/tags/att_a_target.asp", "Click the first 'Try it Yourself' button and tell me the text of the link shown in the result pane", RunMode.Once),
        )

    @Test
    fun `smoke check on real sites`() {
        val key = env("GEMINI_API_KEY").also { assumeTrue(it != null, "GEMINI_API_KEY not set: smoke check skipped") }!!
        val only = env("SMOKE_ONLY")?.split(",")?.map { it.trim() }
        // A field-test file (the /field-test skill) replaces the fixed list.
        val chosen = env("SMOKE_FILE")?.let { listOf(FieldTest.read(File(it)).site()) } ?: sites.filter { only == null || it.id in only }
        for (site in chosen) {
            val host = Host().apply { repeat(5) { dialogAnswers += declineClicked } }
            ModelTraffic(GEMINI).use { traffic ->
                host.model = OpenAiCompatibleClient(traffic.baseUrl, key, MODEL)
                val config =
                    TestConfig(
                        goal = site.goal, startUrl = site.url, provider = Provider.Custom, apiKey = Secret(key),
                        baseUrl = traffic.baseUrl, model = MODEL, runMode = site.mode,
                        secrets = site.secrets,
                    )
                val script = AgentScript(profilesDir = tempProfiles())
                val provider = host.create()
                // ponytail: a crashed site is recorded in its log and the next site still runs.
                runCatching {
                    runBlocking {
                        check(script.onStart(config, provider)) { "onStart rejected the config" }
                        try {
                            repeat(site.runs) { script.execute(config, provider) {} }
                        } finally {
                            script.onFinish(config, provider)
                        }
                    }
                }.onFailure { synchronized(host.logs) { host.logs += "SMOKE crashed: $it" } }
                val dir = File("build/smoke/${site.id}").apply { mkdirs() }
                File(dir, "log.txt").writeText(synchronized(host.logs) { host.logs.joinToString("\n") })
                File(dir, "notifications.txt").writeText(host.notifications.joinToString("\n---\n") { "${it.title}\n${it.message}" })
                File(dir, "traffic.json").writeText(
                    buildJsonArray { traffic.exchanges.forEach { addJsonObject { put("code", it.code); put("request", it.request); put("response", it.response) } } }.toString(),
                )
            }
        }
    }

    private companion object {
        const val MODEL = "gemini-3.8-flash"
        const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai"
    }
}

/** A field-test file (`field-tests/<id>.json`): one goal for [SmokeTest] to run and the /field-test skill to judge. */
@Serializable
class FieldTest(
    val id: String,
    val startUrl: String,
    val goal: String,
    val mode: RunMode = RunMode.Once,
    val runs: Int = if (mode == RunMode.Repeat) 3 else 1,
    /** What a correct run produces, one checkable line each. The skill judges against these; the test never reads them. */
    val expected: List<String>,
    /** Sign-in secret label to the environment variable holding its value; the file never holds a value. */
    val secrets: Map<String, String> = emptyMap(),
) {
    init {
        require(expected.isNotEmpty()) { "$id: expected is empty" }
    }

    fun site() =
        SmokeTest.Site(id, startUrl, goal, mode, runs, secrets.map { (label, variable) -> label to Secret(env(variable) ?: error("$id: $variable is not set (secret '$label')")) })

    companion object {
        fun read(file: File): FieldTest = Json.decodeFromString(file.readText())
    }
}

/** Every committed field-test file parses, so a typo fails `./gradlew test` instead of a paid run. */
class FieldTestFileTest {
    @Test
    fun `field-test files parse`() {
        val files = File("field-tests").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty(), "no .json files in field-tests/")
        files.forEach { runCatching { FieldTest.read(it) }.onFailure { e -> throw AssertionError("${it.name}: ${e.message}", e) } }
    }
}
