package com.cereal.script.aiagent

import com.cereal.sdk.ScriptConfigurationItem
import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import com.cereal.sdk.statemodifier.ScriptConfigValue.DoubleScriptConfigValue
import com.cereal.sdk.statemodifier.ScriptConfigValue.EnumScriptConfigValue
import com.cereal.sdk.statemodifier.ScriptConfigValue.IntScriptConfigValue
import com.cereal.sdk.statemodifier.ScriptConfigValue.StringScriptConfigValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1: red text under the field at Save, read through each field's stateModifier as the host does. */
class StaticErrorsTest {
    @Test
    fun `Goal blank`() {
        assertEquals("Goal is required", error("goal", "goal" to StringScriptConfigValue(" ")))
        assertEquals("Goal is required", error("goal"))
        assertNull(error("goal", "goal" to StringScriptConfigValue("check stock")))
    }

    @Test
    fun `Check every and caps must be above zero`() {
        val repeat = "runMode" to EnumScriptConfigValue(RunMode.Repeat)
        assertEquals("Check every must be > 0", error("repeatInterval", repeat, "repeatInterval" to IntScriptConfigValue(0)))
        assertNull(error("repeatInterval", repeat, "repeatInterval" to IntScriptConfigValue(60)))
        assertNull(error("repeatInterval", "repeatInterval" to IntScriptConfigValue(0)))
        assertEquals("Max steps per run must be > 0", error("maxSteps", "maxSteps" to IntScriptConfigValue(0)))
        assertEquals("Max \$ per run must be > 0", error("maxRunCost", "maxRunCost" to DoubleScriptConfigValue(0.0)))
        assertEquals("Max \$ per task must be > 0", error("maxTaskCost", "maxTaskCost" to DoubleScriptConfigValue(-1.0)))
        assertNull(error("maxTaskCost", "maxTaskCost" to DoubleScriptConfigValue(5.0)))
        assertNull(error("maxSteps"))
    }

    @Test
    fun `Start URL without a scheme gets https, other schemes and junk are errors`() {
        fun startUrl(value: String) = error("startUrl", "startUrl" to StringScriptConfigValue(value))
        assertEquals("Start URL is required", error("startUrl"))
        assertNull(startUrl("example.com"))
        assertNull(startUrl("localhost:8080/shop"))
        assertNull(startUrl("http://127.0.0.1:1/"))
        assertEquals("Start URL must be an http(s) URL", startUrl("ftp://example.com"))
        assertEquals("Start URL must be an http(s) URL", startUrl("file:///etc/passwd"))
        assertEquals("Start URL must be an http(s) URL", startUrl("file:/etc/passwd"))
        assertEquals("Start URL must be an http(s) URL", startUrl("exa mple.com"))
    }

    @Test
    fun `typing example_com as Start URL runs from https_example_com`() {
        val config = TestConfig(startUrl = " example.com ")
        assertEquals("https://example.com", config.startPage())
        assertEquals(setOf("example.com"), config.allowedDomainSet())
        assertEquals("http://127.0.0.1:1/", TestConfig().startPage())
    }

    private fun error(keyName: String, vararg values: Pair<String, ScriptConfigValue>): String? {
        val item =
            AgentConfiguration::class.java.methods
                .mapNotNull { it.getAnnotation(ScriptConfigurationItem::class.java) }
                .single { it.keyName == keyName }
        val form =
            object : ScriptConfig {
                override fun valueForKey(key: String): ScriptConfigValue = values.toMap()[key] ?: ScriptConfigValue.NullScriptConfigValue
            }
        return item.stateModifier.objectInstance!!.getError(form)
    }
}
