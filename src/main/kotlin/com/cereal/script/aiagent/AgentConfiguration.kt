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
        position = 7,
        keyName = "maxSteps",
        name = "Max steps per run",
        description = "Each model call is one step. A run that reaches this many steps stops.",
    )
    fun maxSteps(): Int = 40

    @ScriptConfigurationItem(
        position = 8,
        keyName = "maxRunCost",
        name = "Max $ per run",
        description = "A run stops once its model calls have cost this many dollars.",
    )
    fun maxRunCost(): Double = 3.0

    @ScriptConfigurationItem(
        position = 9,
        keyName = "maxTaskCost",
        name = "Max $ per task",
        description = "The task stops once all its runs together have cost this many dollars. Resets when the task restarts.",
    )
    fun maxTaskCost(): Double = 10.0
}

enum class RunMode { Once }

enum class Provider { Anthropic }
