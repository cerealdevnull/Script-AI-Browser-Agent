package com.cereal.script.aiagent

import com.cereal.sdk.Script
import com.cereal.sdk.ScriptConfiguration
import com.cereal.sdk.component.llm.LlmRequest
import com.cereal.sdk.component.llm.LlmResponse
import com.cereal.sdk.component.llm.LlmUsage
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Loads the shrunk `build/cereal/release.jar` like the host does and runs one goal: real Chrome on a fixture page,
 * and a fake model behind the host's `provider.llm()`. Run with `./gradlew scriptJarTest`.
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
        assertTrue(functions.getValue("secrets").returnType.isMarkedNullable)
        assertTrue(functions.getValue("allowedDomains").returnType.isMarkedNullable)
        assertFalse("onNextStart" in functions)
        val row = loader.loadClass("${configClass.name}\$AllowedDomain").kotlin.memberFunctions.single { it.name == "domain" }
        assertFalse(row.returnType.isMarkedNullable)
        val secretRow = loader.loadClass("${configClass.name}\$NamedSecret").kotlin.memberFunctions.associateBy { it.name }
        assertEquals(Secret::class, secretRow.getValue("value").returnType.classifier)
        assertFalse(secretRow.getValue("label").returnType.isMarkedNullable)
        // The host gets the Model dropdown's source as the annotation's objectInstance.
        val model = configClass.getMethod("model").getAnnotation(com.cereal.sdk.ScriptConfigurationItem::class.java)
        val options = model.options.objectInstance ?: error("ModelOptions is no Kotlin object in the jar")
        assertEquals(setOf("provider", "apiKey", "baseUrl"), options.dependsOn)
        val empty =
            object : com.cereal.sdk.statemodifier.ScriptConfig {
                override fun valueForKey(key: String) = com.cereal.sdk.statemodifier.ScriptConfigValue.NullScriptConfigValue
            }
        assertEquals(emptyList(), runBlocking { options.load(empty) })

        FixtureServer().use { site ->
            run {
                val product = javaClass.getResource("/fixtures/product.html")!!.readText()
                site.pages["product.html"] = product.replace("In stock", "Out of stock")
                val repeat = loader.loadClass("${scriptClass.packageName}.RunMode").enumConstants.single { it.toString() == "Repeat" }
                val custom = loader.loadClass("${scriptClass.packageName}.Provider").enumConstants.single { it.toString() == "Custom" }
                val values =
                    mapOf(
                        "goal" to "Tell me when Long Nose Pliers is in stock",
                        "startUrl" to site.url(),
                        "runMode" to repeat,
                        "provider" to custom,
                        "baseUrl" to "http://localhost:1/v1",
                        "model" to "llama3",
                        "apiKey" to Secret("test-key"),
                    )
                // Like the host's config proxy: stored values, or the interface default.
                val config =
                    Proxy.newProxyInstance(loader, arrayOf(configClass)) { proxy, method, args ->
                        values[method.name] ?: if (method.isDefault) InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray())) else null
                    } as ScriptConfiguration

                @Suppress("UNCHECKED_CAST")
                // As the host's TaskExecutor.newInstance: the first Kotlin constructor, called with no arguments. Its kotlin-reflect
                // rejects a constructor with parameters even when all have defaults, so the jar's class must have just the no-arg one.
                assertEquals(listOf(0), scriptClass.constructors.map { it.parameterCount })
                val script = scriptClass.kotlin.constructors.first().call() as Script<ScriptConfiguration>
                val host = Host()
                host.llm.responder = ::fakeModel
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

                assertEquals(2, host.llm.requests.size) // learning: click, then finish; the replay calls no model
                val (watching, met) = host.notifications.map { it.message }
                assertTrue(watching.startsWith("Watching: Long Nose Pliers shows “In stock”."), watching)
                assertTrue(met.startsWith("Now holds: Long Nose Pliers shows “In stock”\nCost: unknown (no price for llama3) over 2 checks since "), met)

                // The task menu's resets: the host calls the jar's TaskAction objects through the SDK interface.
                val cacheKey = ReplayCache.key(values.getValue("goal") as String, site.url(), emptyList())
                assertNotNull(runBlocking { provider.preference().getString(cacheKey) })
                val actions = script.taskActions().associateBy { it.name }
                assertEquals(listOf("Forget learned steps", "Forget learned steps and sign out"), actions.keys.toList())
                assertEquals("Forgot learned steps and sign-ins", runBlocking { actions.getValue("Forget learned steps and sign out").run(config, provider) })
                assertNull(runBlocking { provider.preference().getString(cacheKey) })
            }
        }
    }

    @Test
    fun `the host can instantiate every form field's state modifier and options from the shrunk jar`() {
        val manifest = ZipFile(jar).use { zip -> zip.getInputStream(zip.getEntry("manifest.json")).reader().readText() }
        val loader = HostLikeScriptClassLoader(jar.toURI().toURL(), javaClass.classLoader)
        val scriptClass = loader.loadClass(Json.parseToJsonElement(manifest).jsonObject["script"]!!.jsonPrimitive.content)
        val configClass = scriptClass.genericInterfaces.filterIsInstance<ParameterizedType>()
            .first { it.rawType == Script::class.java }.actualTypeArguments.single() as Class<*>

        // Like the host's ScriptConfigurationDefinitionBuilder: a null objectInstance fails the whole form.
        val items =
            (listOf(configClass) + configClass.declaredClasses).flatMap { it.methods.toList() }
                .mapNotNull { m -> m.getAnnotation(com.cereal.sdk.ScriptConfigurationItem::class.java)?.let { m.name to it } }
        assertEquals(16, items.size, items.map { it.first }.toString())
        val modifiers =
            items.associate { (name, item) ->
                assertNotNull(item.options.objectInstance, "$name: ${item.options} is no Kotlin object in the jar")
                name to (item.stateModifier.objectInstance ?: error("$name: ${item.stateModifier} is no Kotlin object in the jar"))
            }

        val custom = loader.loadClass("${scriptClass.packageName}.Provider").enumConstants.single { it.toString() == "Custom" } as Enum<*>
        fun config(vararg values: Pair<String, com.cereal.sdk.statemodifier.ScriptConfigValue>) =
            object : com.cereal.sdk.statemodifier.ScriptConfig {
                override fun valueForKey(key: String) =
                    values.toMap()[key] ?: com.cereal.sdk.statemodifier.ScriptConfigValue.NullScriptConfigValue
            }
        val customProvider = "provider" to com.cereal.sdk.statemodifier.ScriptConfigValue.EnumScriptConfigValue(custom)
        assertEquals("Model is required for Custom", modifiers.getValue("model").getError(config(customProvider)))
        assertEquals("claude-sonnet-5-5", modifiers.getValue("model").getPlaceholder(config()))
        assertEquals("http://localhost:11434/v1", modifiers.getValue("baseUrl").getPlaceholder(config(customProvider)))
        val ftp = "startUrl" to com.cereal.sdk.statemodifier.ScriptConfigValue.StringScriptConfigValue("ftp://example.com")
        assertEquals("Start URL must be an http(s) URL", modifiers.getValue("startUrl").getError(config(ftp)))
        assertEquals("Goal is required", modifiers.getValue("goal").getError(config()))
    }

    @Test
    fun `the jar carries the vendored autoconsent files unmodified, as VERSION records them`() {
        ZipFile(jar).use { zip ->
            val version = zip.getInputStream(zip.getEntry("autoconsent/VERSION")).reader().readText()
            for (name in listOf("autoconsent.playwright.js", "compact-rules.json")) {
                val bytes = zip.getInputStream(zip.getEntry("autoconsent/$name") ?: error("no $name in the jar")).readBytes()
                val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertTrue("$sha  $name" in version, "$name has sha256 $sha, VERSION says:\n$version")
            }
        }
    }

    /** Clicks the product link, then finishes "not met" with a Check on the stock text. */
    private fun fakeModel(request: LlmRequest): LlmResponse {
        val body = request.text()
        val ref = Regex("""\[(\d+)] link "Long Nose Pliers"""").find(body)?.groupValues?.get(1)
        val stock = Regex("""\[(t\d+)] StaticText "Out of stock"""").find(body)?.groupValues?.get(1)
        val call =
            if (stock != null) {
                call("finish", """{"goal_met":false,"summary":"Out of stock.","readouts":null,"check":{"ref":"$stock","condition":"textContains","expected":"In stock"}}""")
            } else {
                call("click", """{"ref":$ref}""")
            }
        return response(listOf(call), model = "llama3", usage = LlmUsage(10, 0, 0, 5))
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
