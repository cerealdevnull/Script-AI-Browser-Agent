package com.cereal.script.aiagent

import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test

/**
 * Manual smoke check (docs/acceptance.md): the real script on real sites, to find what breaks outside Toolshop.
 * Runs on gemini-3.8-flash (the Gemini preset's recommended model) through the Custom provider (so the traffic proxy can sit in between); skips without GEMINI_API_KEY.
 * Not pass/fail: every site's log and model traffic land in build/smoke/<id>/ to be judged afterwards.
 * Read-only goals, and any confirmation is declined. Rerun a subset with SMOKE_ONLY=nike,ad.
 */
@Tag("acceptance")
class SmokeTest {
    private class Site(val id: String, val url: String, val goal: String, val mode: RunMode, val runs: Int = 1, val secrets: List<Pair<String?, Secret?>> = emptyList())

    // One ad-hoc site from the environment (the field-test skill) replaces the fixed list.
    // Each SMOKE_SECRET_<label>=<value> fills one of the config's three secret slots, for goals that sign in.
    private val adHoc =
        env("SMOKE_URL")?.let { url ->
            val secrets = System.getenv().filterKeys { it.startsWith(SECRET_PREFIX) }.toSortedMap().map { (k, v) -> k.removePrefix(SECRET_PREFIX) to Secret(v) }
            check(secrets.size <= 3) { "at most 3 ${SECRET_PREFIX}* variables, the config has 3 secret slots" }
            Site(env("SMOKE_ID", "adhoc")!!, url, env("SMOKE_GOAL")!!, RunMode.valueOf(env("SMOKE_MODE", "Once")!!), env("SMOKE_RUNS", "1")!!.toInt(), secrets)
        }

    private val sites =
        adHoc?.let(::listOf) ?: listOf(
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
        for (site in sites.filter { only == null || it.id in only }) {
            val host = Host().apply { repeat(5) { dialogAnswers += declineClicked } }
            ModelTraffic(GEMINI).use { traffic ->
                val config =
                    TestConfig(
                        goal = site.goal, startUrl = site.url, provider = Provider.Custom, apiKey = Secret(key),
                        baseUrl = traffic.baseUrl, model = MODEL, runMode = site.mode, onNextStart = OnNextStart.ForgetSteps,
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
        const val SECRET_PREFIX = "SMOKE_SECRET_"
        const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai"
    }
}
