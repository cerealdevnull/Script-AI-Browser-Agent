package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.component.llm.LlmResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/*
 * Opt-in acceptance runs (#81): a real claude-opus-5-5 on Toolshop, through the test-only [OpenAiCompatibleClient].
 * Tagged "acceptance", so only `./gradlew acceptanceTest` runs them; they skip when ANTHROPIC_API_KEY is absent.
 * They cost real money.
 * How to run and where to record results: docs/acceptance.md.
 */

const val ACCEPTANCE_MODEL = "claude-opus-5-5"

const val ANTHROPIC_OPENAI = "https://api.anthropic.com/v1"

/** The run's model: [ACCEPTANCE_MODEL] on Anthropic's OpenAI-compatible endpoint, recording every request and answer. */
fun acceptanceClient(key: String) = OpenAiCompatibleClient(ANTHROPIC_OPENAI, key, ACCEPTANCE_MODEL)

fun env(name: String, default: String? = null): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/** The key, or the test is skipped. */
fun anthropicKeyOrSkip(): String = env("ANTHROPIC_API_KEY").also { assumeTrue(it != null, "ANTHROPIC_API_KEY not set: acceptance run skipped") }!!

private val HTTP = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(10, TimeUnit.MINUTES).build()
private val JSON = "application/json".toMediaType()

/** The password check: no request the client sent (its raw JSON, exactly what left the machine) contains [secret]. */
fun OpenAiCompatibleClient.assertNeverSent(secret: String, what: String) =
    assertTrue(exchanges.none { secret in it.request }, "$what found in recorded model requests")

/** What one goal run cost, printed as a docs/acceptance.md table row and appended to build/acceptance/results.md. */
class RunStats(val goal: String, val run: String, responses: List<LlmResponse>, val result: ExecutionResult, val dialogs: Int) {
    val steps = responses.size
    val models = responses.map { it.model }.distinct()
    val cost =
        responses.sumOf { Prices.cost(it.model, it.usage.toUsage()) ?: 0.0 }

    fun row() = "| $goal | $run | $steps | ${usd(cost, 4)} | ${models.joinToString().ifEmpty { "-" }} | $dialogs | ${result::class.simpleName}: ${result.message} |"

    fun report() =
        also {
            println("ACCEPTANCE $HEADER\nACCEPTANCE ${row()}")
            File("build/acceptance").mkdirs()
            File("build/acceptance/results.md").appendText(row() + "\n")
        }

    companion object {
        const val HEADER = "| Goal | Run | Steps | $ | Model | Confirmations | Result |"
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
