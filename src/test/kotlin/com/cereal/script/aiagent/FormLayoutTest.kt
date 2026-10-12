package com.cereal.script.aiagent

import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import com.cereal.sdk.statemodifier.Visibility
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
        assertEquals(Visibility.Hidden, Secret2State.getVisibility(empty))
        assertEquals(Visibility.Hidden, Secret2State.getVisibility(form("secret1Name" to ScriptConfigValue.StringScriptConfigValue(" "))))
        val oneLabel = form("secret1Name" to ScriptConfigValue.StringScriptConfigValue("email"))
        assertEquals(Visibility.VisibleOptional, Secret2State.getVisibility(oneLabel))
        assertEquals(Visibility.Hidden, Secret3State.getVisibility(oneLabel))
        assertEquals(Visibility.VisibleOptional, Secret3State.getVisibility(form("secret2Name" to ScriptConfigValue.StringScriptConfigValue("password"))))
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
                "allowedDomains", "maxSteps", "maxRunCost", "maxTaskCost", "reLearn",
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
            assertEquals(com.cereal.sdk.statemodifier.DefaultStateModifier::class.java, items.getValue(it).stateModifier.java, it)
        }
    }

    private fun form(vararg values: Pair<String, ScriptConfigValue>) =
        object : ScriptConfig {
            override fun valueForKey(key: String): ScriptConfigValue = values.toMap()[key] ?: ScriptConfigValue.NullScriptConfigValue
        }
}
