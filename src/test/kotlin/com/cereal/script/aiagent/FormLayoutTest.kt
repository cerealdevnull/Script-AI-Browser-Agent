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

/** S6: a Once task shows no interval and a secrets list; Repeat shows Check every. */
class FormLayoutTest {
    @Test
    fun `Check every shows only for Repeat`() {
        assertEquals(Visibility.Hidden, CheckEveryState.getVisibility(form()))
        assertEquals(Visibility.Hidden, CheckEveryState.getVisibility(form("runMode" to ScriptConfigValue.EnumScriptConfigValue(RunMode.Once))))
        assertEquals(Visibility.VisibleRequired, CheckEveryState.getVisibility(form("runMode" to ScriptConfigValue.EnumScriptConfigValue(RunMode.Repeat))))
    }

    @Test
    fun `the secrets list names the row to fix at Save`() {
        fun row(label: String?, value: String?) =
            object : ScriptConfig {
                override fun valueForKey(key: String): ScriptConfigValue =
                    when (key) {
                        "label" -> label?.let { ScriptConfigValue.StringScriptConfigValue(it) }
                        "value" -> value?.let { ScriptConfigValue.SecretScriptConfigValue(Secret(it)) }
                        else -> null
                    } ?: ScriptConfigValue.NullScriptConfigValue
            }
        fun secrets(vararg rows: ScriptConfig) = form("secrets" to ScriptConfigValue.ListScriptConfigValue(rows.toList()))

        assertEquals(null, SecretsState.getError(form()))
        assertEquals(null, SecretsState.getError(secrets(row("Email", "a"), row("Password", "b"))))
        assertEquals("Secret 2 ('Email') needs a value", SecretsState.getError(secrets(row("Password", "b"), row(" Email ", ""))))
        assertEquals("Secret 1 needs a label, e.g. password", SecretsState.getError(secrets(row(" ", "x"))))
        assertEquals(
            "Secret 3's label 'my  password' is already used by secret 1",
            SecretsState.getError(secrets(row("My Password", "a"), row("Email", "b"), row("my  password", "c"))),
        )
        assertEquals(
            "Secret 1 needs a label, e.g. password. Secret 1 needs a value",
            SecretsState.getError(secrets(row(null, null))),
        )
    }

    @Test
    fun `the secrets list explains how labels are used`() {
        val description = item("secrets").description
        listOf("same words in your goal", "never sees the value").forEach { assertTrue(it in description, description) }
    }

    @Test
    fun `fields are grouped into Task, Sign-in secrets and a collapsed Advanced section`() {
        val sections =
            AgentConfiguration::class.java.getAnnotation(com.cereal.sdk.ScriptConfigurationSections::class.java)!!.value
                .map { Triple(it.key, it.name, it.collapsed) }
        assertEquals(
            listOf(
                Triple("task", "Task", false),
                Triple("secrets", "Sign-in secrets", false),
                Triple("advanced", "Advanced", true),
            ),
            sections,
        )
        val bySection =
            AgentConfiguration::class.java.methods
                .mapNotNull { it.getAnnotation(com.cereal.sdk.ScriptConfigurationItem::class.java) }
                .sortedBy { it.position }
                .groupBy({ it.section }, { it.keyName })
        assertEquals(listOf("goal", "startUrl", "runMode", "repeatInterval"), bySection["task"])
        assertEquals(setOf("task", "secrets", "advanced"), bySection.keys, "no provider section: Settings → AI picks the model")
        assertEquals(listOf("secrets"), bySection["secrets"])
        assertEquals(listOf("allowedDomains", "maxSteps", "maxRunCost", "maxTaskCost"), bySection["advanced"])
        assertEquals(null, bySection[""], "unsectioned fields render first")
    }

    @Test
    fun `Start URL shows its example as a placeholder, not in the description`() {
        assertEquals("https://example.com", StartUrlState.getPlaceholder(form()))
        assertEquals("The page the agent starts on.", item("startUrl").description)
    }

    @Test
    fun `a new user sees 40 steps, 2 dollars per run, 5 per task and what an unpriced model costs, always visible`() {
        val fresh =
            object : AgentConfiguration {
                override fun goal() = ""
                override fun startUrl() = ""
                override fun secrets() = null
                override fun allowedDomains() = null
            }
        assertEquals(Triple(40, 2.0, 5.0), Triple(fresh.maxSteps(), fresh.maxRunCost(), fresh.maxTaskCost()))

        val items =
            AgentConfiguration::class.java.methods
                .mapNotNull { it.getAnnotation(com.cereal.sdk.ScriptConfigurationItem::class.java) }
                .associateBy { it.keyName }
        assertTrue(
            items.getValue("maxRunCost").description.endsWith(
                "Models without a known price count as \$0, so only Max steps per run limits them.",
            ),
        )
        assertTrue(items.getValue("maxTaskCost").description.endsWith("Resets when the task restarts."))
        listOf("maxSteps", "maxRunCost", "maxTaskCost").forEach {
            assertEquals(Visibility.VisibleOptional, items.getValue(it).stateModifier.objectInstance!!.getVisibility(form()), it)
        }
    }

    @Test
    fun `the manifest points at Settings → AI and is version 2`() {
        val manifest = Json.parseToJsonElement(javaClass.getResource("/manifest.json")!!.readText()).jsonObject
        assertEquals(
            "Set up your AI provider and model in Settings → AI. Write a goal and a start URL: Once does the task or answers the question, " +
                "Repeat checks until a condition is true. A visible Chrome window opens, and the agent asks before it buys, pays or sends anything. " +
                "Your AI provider bills the model's usage; each run is capped at \$2 by default.",
            manifest.getValue("instructions").jsonPrimitive.content,
        )
        assertEquals("2", manifest.getValue("version_code").jsonPrimitive.content)
    }

    @Test
    fun `the store listing says what it does, what it costs, where to get a key and what to try`() {
        val text = java.io.File("STORELISTING.md").readText()
        val sections = listOf("## Getting started", "## Features", "## Use Cases", "## Requirements")
        assertEquals(sections, sections.sortedBy { text.indexOf(it) }, text)
        assertTrue(sections.all { it in text }, text)
        fun section(name: String) = text.substringAfter(name).substringBefore("\n## ")
        assertTrue((1..4).all { "\n$it. " in section("## Getting started") } && "\n5. " !in text, "4 numbered steps")
        assertTrue("Settings → AI" in section("## Getting started").substringAfter("\n1. ").substringBefore("\n"))
        assertTrue("cost is tracked for common models, and shown as unknown otherwise" in section("## Features"))
        val useCases = section("## Use Cases").trim().lines()
        assertEquals(4, useCases.size, useCases.toString())
        assertTrue(useCases.all { "Once" in it || "Repeat" in it }, useCases.toString())
        // The Goal field's example pair, word for word.
        listOf("tell me whether Long Nose Pliers is in stock", "notify me when Long Nose Pliers is in stock").forEach { assertTrue(it in text, it) }
        val requirements = section("## Requirements")
        assertTrue("billed by your AI provider; default limits are \$2 per run and \$5 per task, which you can change" in requirements, requirements)
        listOf("API key", "\$0.20", "https://", "Works with Anthropic").forEach { assertTrue(it !in text.substringBefore("Includes @duckduckgo"), it) }
        assertTrue(Regex("\\bChecks?\\b").find(text) == null, "check is lower-case")
    }

    private fun item(keyName: String) =
        AgentConfiguration::class.java.methods
            .mapNotNull { it.getAnnotation(com.cereal.sdk.ScriptConfigurationItem::class.java) }
            .single { it.keyName == keyName }

    private fun form(vararg values: Pair<String, ScriptConfigValue>) =
        object : ScriptConfig {
            override fun valueForKey(key: String): ScriptConfigValue = values.toMap()[key] ?: ScriptConfigValue.NullScriptConfigValue
        }
}
