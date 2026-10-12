package com.cereal.script.aiagent

import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import com.cereal.sdk.statemodifier.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun form(vararg values: Pair<String, ScriptConfigValue>) =
        object : ScriptConfig {
            override fun valueForKey(key: String): ScriptConfigValue = values.toMap()[key] ?: ScriptConfigValue.NullScriptConfigValue
        }
}
