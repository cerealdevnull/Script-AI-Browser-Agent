package com.cereal.script.aiagent

import com.cereal.sdk.models.Secret
import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import com.cereal.sdk.statemodifier.Visibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S6: a Once task shows no interval and one secret pair; Repeat shows Check every; a label reveals the next pair. */
class FormLayoutTest {
    @Test
    fun `Check every shows only for Repeat`() {
        assertEquals(Visibility.Hidden, CheckEveryState.getVisibility(form()))
        assertEquals(Visibility.Hidden, CheckEveryState.getVisibility(form("runMode" to ScriptConfigValue.EnumScriptConfigValue(RunMode.Once))))
        assertEquals(Visibility.VisibleRequired, CheckEveryState.getVisibility(form("runMode" to ScriptConfigValue.EnumScriptConfigValue(RunMode.Repeat))))
    }

    @Test
    fun `Secret 2 appears once Secret 1 has a label, Secret 3 once Secret 2 has one`() {
        val empty = form()
        assertEquals(Visibility.Hidden, Secret2LabelState.getVisibility(empty))
        assertEquals(Visibility.Hidden, Secret2LabelState.getVisibility(form("secret1Name" to ScriptConfigValue.StringScriptConfigValue(" "))))
        val oneLabel = form("secret1Name" to ScriptConfigValue.StringScriptConfigValue("email"))
        assertEquals(Visibility.VisibleOptional, Secret2LabelState.getVisibility(oneLabel))
        assertEquals(Visibility.Hidden, Secret3LabelState.getVisibility(oneLabel))
        assertEquals(Visibility.VisibleOptional, Secret3LabelState.getVisibility(form("secret2Name" to ScriptConfigValue.StringScriptConfigValue("password"))))
    }

    @Test
    fun `secret errors show at Save under the field to fix`() {
        fun label(text: String) = ScriptConfigValue.StringScriptConfigValue(text)
        fun value(text: String) = ScriptConfigValue.SecretScriptConfigValue(Secret(text))

        val noValue = form("secret1Name" to label(" Email "))
        assertEquals("Enter the value for 'Email'", Secret1ValueState.getError(noValue))
        assertEquals(null, Secret1LabelState.getError(noValue))

        assertEquals("Give this value a label, e.g. password", Secret1LabelState.getError(form("secret1Value" to value("x"))))

        val twice = form("secret1Name" to label("Email"), "secret1Value" to value("a"), "secret2Name" to label("email"), "secret2Value" to value("b"))
        assertEquals(null, Secret1LabelState.getError(twice))
        assertEquals("The label 'email' is already used by another secret", Secret2LabelState.getError(twice))
        assertEquals(null, Secret2ValueState.getError(twice))
    }

    @Test
    fun `fields follow the S6 order`() {
        val order =
            AgentConfiguration::class.java.methods
                .mapNotNull { it.getAnnotation(com.cereal.sdk.ScriptConfigurationItem::class.java) }
                .sortedBy { it.position }
                .map { it.keyName }
        assertEquals(
            listOf(
                "goal", "startUrl", "runMode", "repeatInterval", "provider", "apiKey", "model", "baseUrl",
                "secret1Name", "secret1Value", "secret2Name", "secret2Value", "secret3Name", "secret3Value",
                "allowedDomains", "maxSteps", "maxRunCost", "maxTaskCost", "onNextStart",
            ),
            order,
        )
    }

    @Test
    fun `a new user sees 40 steps, 2 dollars per run, 5 per task and the typical run cost, always visible`() {
        val fresh =
            object : AgentConfiguration {
                override fun goal() = ""
                override fun startUrl() = ""
                override fun apiKey() = null
                override fun model() = null
                override fun baseUrl() = null
                override fun secret1Name() = null
                override fun secret1Value() = null
                override fun secret2Name() = null
                override fun secret2Value() = null
                override fun secret3Name() = null
                override fun secret3Value() = null
                override fun allowedDomains() = null
            }
        assertEquals(Triple(40, 2.0, 5.0), Triple(fresh.maxSteps(), fresh.maxRunCost(), fresh.maxTaskCost()))

        val items =
            AgentConfiguration::class.java.methods
                .mapNotNull { it.getAnnotation(com.cereal.sdk.ScriptConfigurationItem::class.java) }
                .associateBy { it.keyName }
        assertTrue(
            items.getValue("maxRunCost").description.endsWith(
                "A typical run on the recommended model costs about \$0.20; simple goals less, long ones up to \$1.",
            ),
        )
        assertTrue(items.getValue("maxTaskCost").description.endsWith("Resets when the task restarts."))
        listOf("maxSteps", "maxRunCost", "maxTaskCost").forEach {
            assertEquals(Visibility.VisibleOptional, items.getValue(it).stateModifier.objectInstance!!.getVisibility(form()), it)
        }
    }

    @Test
    fun `the manifest instructions are a short quick start in form order`() {
        val manifest = javaClass.getResource("/manifest.json")!!.readText()
        val text = Json.parseToJsonElement(manifest).jsonObject.getValue("instructions").jsonPrimitive.content
        assertTrue(text.length <= 360, "${text.length} chars") // "under about 350"
        assertTrue(text.split(". ").size in 3..4, text)
        val order = listOf("provider", "API key", "goal", "start URL", "Once", "Repeat", "Chrome", "asks before", "\$0.20", "\$2")
        assertEquals(order.sortedBy { text.indexOf(it) }, order, text)
        assertTrue(order.all { it in text }, text)
        listOf("Secret", "Allowed domains", "On next start").forEach { assertTrue(it !in text, it) }
    }

    @Test
    fun `the store listing says what it does, what it costs, where to get a key and what to try`() {
        val text = java.io.File("STORELISTING.md").readText()
        val sections = listOf("## Getting started", "## Features", "## Use Cases", "## Requirements")
        assertEquals(sections, sections.sortedBy { text.indexOf(it) }, text)
        assertTrue(sections.all { it in text }, text)
        fun section(name: String) = text.substringAfter(name).substringBefore("\n## ")
        assertTrue((1..4).all { "\n$it. " in section("## Getting started") } && "\n5. " !in text, "4 numbered steps")
        assertTrue(section("## Features").trim().lines().last().contains("Works with Anthropic or any OpenAI-compatible server"))
        val useCases = section("## Use Cases").trim().lines()
        assertEquals(4, useCases.size, useCases.toString())
        assertTrue(useCases.all { "Once" in it || "Repeat" in it }, useCases.toString())
        // The Goal field's example pair, word for word.
        listOf("tell me whether Long Nose Pliers is in stock", "notify me when Long Nose Pliers is in stock").forEach { assertTrue(it in text, it) }
        val requirements = section("## Requirements")
        listOf(
            "billed by your provider; about \$0.20 a run with the default model (an estimate); default limits are \$2 per run and \$5 per task, which you can change",
            "or any OpenAI-compatible server, including local ones such as Ollama or LM Studio",
        ).forEach { assertTrue(it in requirements, it) }
        Provider.entries.mapNotNull { it.keyUrl }.forEach { assertTrue(it in requirements, it) }
        assertTrue(Regex("\\bChecks?\\b").find(text) == null, "check is lower-case")
    }

    private fun form(vararg values: Pair<String, ScriptConfigValue>) =
        object : ScriptConfig {
            override fun valueForKey(key: String): ScriptConfigValue = values.toMap()[key] ?: ScriptConfigValue.NullScriptConfigValue
        }
}
