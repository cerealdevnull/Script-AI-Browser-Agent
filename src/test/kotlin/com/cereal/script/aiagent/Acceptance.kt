package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/*
 * Opt-in acceptance runs (#81): a real claude-opus-5-5 on Toolshop. Tagged "acceptance", so only
 * `./gradlew acceptanceTest` runs them; they skip when ANTHROPIC_API_KEY is absent. They cost real money.
 * How to run and where to record results: docs/acceptance.md.
 */

const val ACCEPTANCE_MODEL = "claude-opus-5-5"

fun env(name: String, default: String? = null): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/** The key, or the test is skipped. */
fun anthropicKeyOrSkip(): String = env("ANTHROPIC_API_KEY").also { assumeTrue(it != null, "ANTHROPIC_API_KEY not set: acceptance run skipped") }!!

private val HTTP = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(10, TimeUnit.MINUTES).build()
private val JSON = "application/json".toMediaType()

/**
 * A local pass-through to the real model API ([upstream], Anthropic by default) that keeps every request body and
 * response: the script's Base URL points here, so this is exactly the model traffic that left the machine.
 */
class ModelTraffic(private val upstream: String = AnthropicClient.DEFAULT_BASE_URL) : AutoCloseable {
    class Exchange(val request: String, val code: Int, val response: String)

    val exchanges = CopyOnWriteArrayList<Exchange>()

    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = request.body!!.utf8()
                        val forward =
                            Request.Builder().url(upstream.trimEnd('/') + request.url.encodedPath)
                                .apply { FORWARDED.forEach { h -> request.headers[h]?.let { header(h, it) } } }
                                .post(body.toRequestBody(JSON)).build()
                        HTTP.newCall(forward).execute().use { r ->
                            val text = r.body.string()
                            exchanges += Exchange(body, r.code, text)
                            return MockResponse.Builder().code(r.code).body(text)
                                .apply { r.header("retry-after")?.let { addHeader("retry-after", it) } }.build()
                        }
                    }
                }
            start()
        }

    val baseUrl: String get() = server.url("/").toString().removeSuffix("/")

    fun mark() = exchanges.size

    /** Answered model calls (steps) since [mark]. */
    fun since(mark: Int): List<JsonObject> =
        exchanges.drop(mark).filter { it.code == 200 }.map { Json.parseToJsonElement(it.response).jsonObject }

    fun assertNeverContains(secret: String, what: String) =
        assertTrue(exchanges.none { secret in it.request }, "$what found in captured model traffic")

    /** Every request asked for `fallbacks: "default"`, and the API answered at least one of them (no 400 for it). */
    fun assertFallbacksAccepted() {
        assertTrue(exchanges.isNotEmpty() && exchanges.all { "\"fallbacks\":\"default\"" in it.request }, "requests without fallbacks:\"default\"")
        assertTrue(exchanges.any { it.code == 200 }, "no request was accepted: ${exchanges.map { it.code }}")
    }

    override fun close() = server.close()

    private companion object {
        val FORWARDED = listOf("x-api-key", "anthropic-version", "anthropic-beta", "Authorization")
    }
}

/** What one goal run cost, printed as a docs/acceptance.md table row and appended to build/acceptance/results.md. */
class RunStats(val goal: String, val run: String, responses: List<JsonObject>, val result: ExecutionResult, val dialogs: Int) {
    private fun JsonObject.usage(key: String) = (this["usage"] as? JsonObject)?.get(key)?.jsonPrimitive?.longOrNull ?: 0L

    val steps = responses.size
    val cacheReads = responses.map { it.usage("cache_read_input_tokens") }
    val models = responses.map { it["model"]?.jsonPrimitive?.content }
    val cost =
        responses.sumOf { r ->
            Prices.cost(r["model"]?.jsonPrimitive?.content, Usage(r.usage("input_tokens"), r.usage("output_tokens"), r.usage("cache_read_input_tokens"), r.usage("cache_creation_input_tokens"))) ?: 0.0
        }
    val fallbacks = models.filterNotNull().filterNot { it.startsWith(ACCEPTANCE_MODEL) }.distinct()

    /** Later steps of a multi-step run read from the prompt cache. */
    fun assertCacheReadsOnLaterSteps() {
        if (steps >= 2) assertTrue(cacheReads.drop(1).any { it > 0 }, "no cache-read tokens after step 1: $cacheReads")
    }

    fun row() =
        "| $goal | $run | $steps | ${usd(cost, 4)} | ${cacheReads.joinToString(" ").ifEmpty { "-" }} | " +
            "${fallbacks.joinToString().ifEmpty { "no" }} | $dialogs | ${result::class.simpleName}: ${result.message} |"

    fun report() =
        also {
            println("ACCEPTANCE $HEADER\nACCEPTANCE ${row()}")
            File("build/acceptance").mkdirs()
            File("build/acceptance/results.md").appendText(row() + "\n")
        }

    companion object {
        const val HEADER = "| Goal | Run | Steps | $ | Cache-read tokens per step | Fallback answered | Confirmations | Result |"
    }
}

/** Toolshop's REST API: the source of truth the notifications and orders are checked against. */
class ToolshopApi(private val baseUrl: String) {
    fun login(email: String, password: String): String {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        val json = call(Request.Builder().url("$baseUrl/users/login").post(body.toString().toRequestBody(JSON)))
        return json["access_token"]?.jsonPrimitive?.content
            ?: error("Toolshop login failed for $email: $json. The shared demo password may have been changed; try TOOLSHOP_EMAIL=customer2@practicesoftwaretesting.com")
    }

    /** Page 1 of the account's invoices (newest first) plus the pagination `total`. */
    fun invoices(token: String): JsonObject = call(Request.Builder().url("$baseUrl/invoices").header("Authorization", "Bearer $token"))

    private fun call(request: Request.Builder): JsonObject =
        HTTP.newCall(request.header("Accept", "application/json").build()).execute().use { Json.parseToJsonElement(it.body.string()).jsonObject }
}

fun reachable(url: String) = runCatching { HTTP.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } }.getOrDefault(false)
