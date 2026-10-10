package com.cereal.script.aiagent

import com.cereal.sdk.ScriptConfiguration
import com.cereal.sdk.ScriptConfigurationItem
import com.cereal.sdk.models.Secret

// keyNames are persisted by the host: never change them.
interface AgentConfiguration : ScriptConfiguration {
    @ScriptConfigurationItem(
        position = 0,
        keyName = "goal",
        name = "Goal",
        description = "What the agent should do, in plain language. For example: tell me whether Long Nose Pliers is in stock.",
    )
    fun goal(): String

    @ScriptConfigurationItem(
        position = 1,
        keyName = "startUrl",
        name = "Start URL",
        description = "The http(s) page the agent starts on.",
    )
    fun startUrl(): String

    @ScriptConfigurationItem(
        position = 2,
        keyName = "runMode",
        name = "Run mode",
        description = "Once: reach the goal and notify the result.",
    )
    fun runMode(): RunMode = RunMode.Once

    @ScriptConfigurationItem(
        position = 3,
        keyName = "provider",
        name = "Provider",
        description = "Who runs the model. Your API key is sent only to this provider.",
    )
    fun provider(): Provider = Provider.Anthropic

    @ScriptConfigurationItem(
        position = 4,
        keyName = "baseUrl",
        name = "Base URL",
        description = "Leave empty for the provider's default API address.",
    )
    fun baseUrl(): String?

    @ScriptConfigurationItem(
        position = 5,
        keyName = "apiKey",
        name = "API key",
        description = "Your own API key for the provider.",
    )
    fun apiKey(): Secret?

    @ScriptConfigurationItem(
        position = 6,
        keyName = "model",
        name = "Model",
        description = "The model id, e.g. claude-opus-5-5.",
    )
    fun model(): String = "claude-opus-5-5"

    @ScriptConfigurationItem(
        position = 20,
        keyName = "secret1Name",
        name = "Secret 1 name",
        description = "The name the agent sees for this secret, e.g. email or password (lowercase, digits, _).",
    )
    fun secret1Name(): String?

    @ScriptConfigurationItem(
        position = 21,
        keyName = "secret1Value",
        name = "Secret 1 value",
        description = "Typed into the site when the agent asks for it by name; never shown to the model.",
    )
    fun secret1Value(): Secret?

    @ScriptConfigurationItem(
        position = 22,
        keyName = "secret2Name",
        name = "Secret 2 name",
        description = "The name the agent sees for this secret, e.g. email or password (lowercase, digits, _).",
    )
    fun secret2Name(): String?

    @ScriptConfigurationItem(
        position = 23,
        keyName = "secret2Value",
        name = "Secret 2 value",
        description = "Typed into the site when the agent asks for it by name; never shown to the model.",
    )
    fun secret2Value(): Secret?

    @ScriptConfigurationItem(
        position = 24,
        keyName = "secret3Name",
        name = "Secret 3 name",
        description = "The name the agent sees for this secret, e.g. email or password (lowercase, digits, _).",
    )
    fun secret3Name(): String?

    @ScriptConfigurationItem(
        position = 25,
        keyName = "secret3Value",
        name = "Secret 3 value",
        description = "Typed into the site when the agent asks for it by name; never shown to the model.",
    )
    fun secret3Value(): Secret?
}

/** Slots 1..3 as (name, value); blank counts as empty. */
fun AgentConfiguration.secretSlots(): List<Pair<String?, Secret?>> =
    listOf(secret1Name() to secret1Value(), secret2Name() to secret2Value(), secret3Name() to secret3Value())
        .map { (name, value) -> name?.trim()?.ifEmpty { null } to value?.takeIf { it.reveal().isNotEmpty() } }

/** The usable secrets by name: slots with both a name and a value. */
fun AgentConfiguration.secrets(): Map<String, Secret> =
    secretSlots().mapNotNull { (name, value) -> if (name != null && value != null) name to value else null }.toMap()

enum class RunMode { Once }

enum class Provider { Anthropic }
