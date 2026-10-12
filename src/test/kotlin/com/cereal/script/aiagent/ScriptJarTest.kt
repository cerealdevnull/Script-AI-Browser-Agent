package com.cereal.script.aiagent

import com.cereal.sdk.Script
import com.cereal.sdk.ScriptConfiguration
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Tag
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipFile
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Loads the shrunk `build/cereal/release.jar` like the host does and runs one goal: real Chrome on a fixture page,
 * and the real Anthropic adapter talking to a fake Messages API. Run with `./gradlew scriptJarTest`.
 */
@Tag("scriptJar")
class ScriptJarTest {
    private val jar = File(System.getProperty("scriptJar") ?: error("run through ./gradlew scriptJarTest"))

    @Test
    fun `the shrunk jar loads through a host-like class loader and runs the loop`() {
        val manifest = ZipFile(jar).use { zip -> zip.getInputStream(zip.getEntry("manifest.json")).reader().readText() }
        val loader = HostLikeScriptClassLoader(jar.toURI().toURL(), javaClass.classLoader)
        val scriptClass = loader.loadClass(Json.parseToJsonElement(manifest).jsonObject["script"]!!.jsonPrimitive.content)
        val configClass = scriptClass.genericInterfaces.filterIsInstance<ParameterizedType>()
            .first { it.rawType == Script::class.java }.actualTypeArguments.single() as Class<*>

        // The host builds the config form with kotlin-reflect: Kotlin metadata must survive shrinking.
        val functions = configClass.kotlin.memberFunctions.associateBy { it.name }
        assertTrue(functions.getValue("baseUrl").returnType.isMarkedNullable)
        assertFalse(functions.getValue("goal").returnType.isMarkedNullable)
        assertTrue(functions.getValue("secret3Value").returnType.isMarkedNullable)
        assertTrue(functions.getValue("allowedDomains").returnType.isMarkedNullable)
        val row = loader.loadClass("${configClass.name}\$AllowedDomain").kotlin.memberFunctions.single { it.name == "domain" }
        assertFalse(row.returnType.isMarkedNullable)

        FixtureServer().use { site ->
            MockWebServer().use { api ->
                api.dispatcher = FakeMessagesApi()
                api.start()
                val product = javaClass.getResource("/fixtures/product.html")!!.readText()
                site.pages["product.html"] = product.replace("In stock", "Out of stock")
                val repeat = loader.loadClass("${scriptClass.packageName}.RunMode").enumConstants.single { it.toString() == "Repeat" }
                val values =
                    mapOf(
                        "goal" to "Tell me when Long Nose Pliers is in stock",
                        "startUrl" to site.url(),
                        "runMode" to repeat,
                        "baseUrl" to api.url("/").toString(),
                        "apiKey" to Secret("test-key"),
                    )
                // Like the host's config proxy: stored values, or the interface default.
                val config =
                    Proxy.newProxyInstance(loader, arrayOf(configClass)) { proxy, method, args ->
                        values[method.name] ?: if (method.isDefault) InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray())) else null
                    } as ScriptConfiguration

                @Suppress("UNCHECKED_CAST")
                val script = scriptClass.getDeclaredConstructor().newInstance() as Script<ScriptConfiguration>
                val host = Host()
                val provider = host.create()
                runBlocking {
                    script.onStart(config, provider)
                    try {
                        // Learns, writes the replay cache (serialization in the shrunk jar), then replays it.
                        assertEquals("Loop", script.execute(config, provider) {}.javaClass.simpleName)
                        site.pages["product.html"] = product
                        assertEquals("Success", script.execute(config, provider) {}.javaClass.simpleName)
                    } finally {
                        script.onFinish(config, provider)
                    }
                }

                assertEquals(2, api.requestCount)
                assertEquals("Goal met\nCost: $0", host.notifications.single().message)
            }
        }
    }

    /** Clicks the product link, then finishes "not met" with a Check on the stock text. */
    private class FakeMessagesApi : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body!!.utf8()
            val ref = Regex("""\[(\d+)] link \\"Long Nose Pliers\\"""").find(body)?.groupValues?.get(1)
            val stock = Regex("""\[(t\d+)] StaticText \\"Out of stock\\"""").find(body)?.groupValues?.get(1)
            val toolUse =
                if (stock != null) {
                    """{"type":"tool_use","id":"t2","name":"finish","input":{"goal_met":false,"summary":"Out of stock.","readouts":null,""" +
                        """"check":{"ref":"$stock","condition":"textContains","expected":"In stock"}}}"""
                } else {
                    """{"type":"tool_use","id":"t1","name":"click","input":{"ref":$ref}}"""
                }
            return MockResponse.Builder().body(
                """{"model":"claude-opus-5-5","stop_reason":"tool_use","content":[$toolUse],"usage":{"input_tokens":10,"output_tokens":5}}""",
            ).build()
        }
    }
}

/**
 * Mimics the host's `ScriptClassLoader` with a release (obfuscated) host: `com.cereal.sdk` and `kotlinx.coroutines`
 * parent-first; everything else child-first from the script jar; parent fallback only for what the host really
 * provides to scripts (the stdlib and the JDK). Service files come only from the parent, never from the jar.
 */
private class HostLikeScriptClassLoader(jar: URL, private val host: ClassLoader) : URLClassLoader(arrayOf(jar), null) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)
                ?: if (PARENT_FIRST.any(name::startsWith)) {
                    host.loadClass(name)
                } else {
                    try {
                        findClass(name)
                    } catch (e: ClassNotFoundException) {
                        if (!HOST_PROVIDED.any(name::startsWith)) throw e
                        host.loadClass(name)
                    }
                }
        }

    override fun getResource(name: String): URL? = if (name.startsWith("META-INF/services/")) null else host.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (name.startsWith("META-INF/services/")) Collections.emptyEnumeration() else host.getResources(name)

    companion object {
        val PARENT_FIRST = listOf("com.cereal.sdk.", "kotlinx.coroutines.")
        val HOST_PROVIDED = listOf("kotlin.", "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.")
    }
}
