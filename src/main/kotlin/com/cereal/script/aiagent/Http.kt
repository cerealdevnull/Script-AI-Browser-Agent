package com.cereal.script.aiagent

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_TYPE = "application/json".toMediaType()
private val HTTP = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(10, TimeUnit.MINUTES).build()

/**
 * POSTs [body] as JSON and returns the parsed 2xx response. A 429, 5xx or network error is retried up to [MAX_RETRIES]
 * times: a 429 after the provider's delay (`retry-after`, Gemini's `RetryInfo.retryDelay`), otherwise with exponential
 * backoff; then it fails as a transient [ModelException]. 401/403/404 or an invalid-model 400 fail at once as
 * "Model/API key rejected"; any other status as a non-transient "Model error".
 */
internal suspend fun postJson(
    url: String,
    headers: Map<String, String>,
    body: JsonObject,
    sleep: suspend (Long) -> Unit,
): JsonObject = send(Request.Builder().url(url).apply { headers.forEach(::header) }.post(body.toString().toRequestBody(JSON_TYPE)).build(), sleep)

/** GETs [url] with the same retries and errors as [postJson]. */
internal suspend fun getJson(url: String, headers: Map<String, String>, sleep: suspend (Long) -> Unit): JsonObject =
    send(Request.Builder().url(url).apply { headers.forEach(::header) }.build(), sleep)

private suspend fun send(request: Request, sleep: suspend (Long) -> Unit): JsonObject {
    var attempt = 0
    while (true) {
        val reply =
            try {
                HTTP.newCall(request).await()
            } catch (e: IOException) {
                if (attempt == MAX_RETRIES) throw ModelException("Model error: network error: ${e.message}", transient = true)
                sleep(backoff(attempt++))
                continue
            }
        val (code, retryAfter, text) = reply
        val json = runCatching { Json.parseToJsonElement(text).let { it as? JsonObject ?: it.jsonArray.first().jsonObject } }.getOrNull()
        if (code in 200..299) return json ?: throw ModelException("Model error: unreadable response: ${text.take(300)}")
        val error = json?.get("error") as? JsonObject
        val message = "HTTP $code: " + (error?.str("message") ?: text.take(300))
        val transient = code == 429 || code >= 500
        if (!transient) {
            val rejected = code in setOf(401, 403, 404) || (code == 400 && "model" in message.lowercase())
            throw ModelException(if (rejected) "Model/API key rejected: $message" else "Model error: $message", status = code)
        }
        if (attempt == MAX_RETRIES) throw ModelException("Model error: $message", transient = true, status = code)
        val asked = if (code == 429) retryAfter?.toDoubleOrNull() ?: error?.let(::geminiRetryDelay) else null
        sleep(asked?.let { (it * 1000).toLong() } ?: backoff(attempt))
        attempt++
    }
}

/** A failed pre-flight lookup: 401/403 a bad key, 404 an unknown model; a transient failure is rethrown, any other passes. */
internal fun preflight(e: ModelException): Preflight? =
    when {
        e.transient -> throw e
        e.status == 401 || e.status == 403 -> Preflight.BadKey
        e.status == 404 -> Preflight.UnknownModel
        else -> null
    }

private const val MAX_RETRIES = 3

private fun backoff(attempt: Int) = 1_000L shl attempt

/** Seconds from Gemini's `details: [{"@type": "…RetryInfo", "retryDelay": "12.5s"}]`. */
private fun geminiRetryDelay(error: JsonObject): Double? =
    (error["details"] as? JsonArray).orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.str("retryDelay") }
        ?.removeSuffix("s")?.toDoubleOrNull()

internal fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

private data class Reply(val code: Int, val retryAfter: String?, val text: String)

/** Runs the call on OkHttp's threads; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Reply =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { Reply(it.code, it.header("retry-after"), it.body.string()) } }
                    result.fold(cont::resume) { cont.resumeWithException(it as? IOException ?: IOException(it.message, it)) }
                }
            },
        )
    }
