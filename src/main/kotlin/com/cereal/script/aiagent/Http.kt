package com.cereal.script.aiagent

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** POSTs [body] as JSON and returns the parsed 2xx response; any other status becomes `ModelException("HTTP <code>: <error.message>")`. */
internal suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
    val request = Request.Builder().url(url).apply { headers.forEach(::header) }.post(body.toString().toRequestBody(JSON_TYPE)).build()
    val (code, text) = HTTP.newCall(request).await()
    val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    if (code !in 200..299) {
        val message = (json?.get("error") as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: text.take(300)
        throw ModelException("HTTP $code: $message")
    }
    return json ?: throw ModelException("unreadable response: ${text.take(300)}")
}

internal fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

/** Runs the call on OkHttp's threads; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(ModelException("network error: ${e.message}"))

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { it.code to it.body.string() } }
                    result.fold(cont::resume) { cont.resumeWithException(ModelException("network error: ${it.message}")) }
                }
            },
        )
    }
