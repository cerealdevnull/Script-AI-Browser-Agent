package com.cereal.script.aiagent

import com.cereal.sdk.ScriptConfiguration
import com.cereal.sdk.ScriptConfigurationItem
import com.cereal.sdk.ScriptConfigurationListItem
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
        description = "Anthropic, or any OpenAI-compatible server (OpenAI, OpenRouter, Gemini, Ollama, LM Studio). Your API key is sent only to the base URL.",
    )
    fun provider(): Provider = Provider.Anthropic

    @ScriptConfigurationItem(
        position = 4,
        keyName = "baseUrl",
        name = "Base URL",
        description =
            "Leave empty for the provider's default (Anthropic: https://api.anthropic.com, OpenAI-compatible: https://api.openai.com/v1). " +
                "E.g. https://openrouter.ai/api/v1, https://generativelanguage.googleapis.com/v1beta/openai, " +
                "http://localhost:11434/v1 (Ollama), http://localhost:1234/v1 (LM Studio).",
    )
    fun baseUrl(): String?

    @ScriptConfigurationItem(
        position = 5,
        keyName = "apiKey",
        name = "API key",
        description = "Your own API key for the provider. Required for Anthropic; optional for OpenAI-compatible (local servers).",
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

    @ScriptConfigurationItem(
        position = 30,
        keyName = "allowedDomains",
        name = "Allowed domains",
        description =
            "Extra domains the agent may open, besides the start URL's own (e.g. a login or payment provider). " +
                "Subdomains are included.",
    )
    fun allowedDomains(): List<AllowedDomain>?

    // Nested so the shrunk jar's metadata restore (AgentConfiguration*.class) covers it: the host reflects on it.
    interface AllowedDomain : ScriptConfigurationListItem {
        @ScriptConfigurationItem(keyName = "domain", name = "Domain", description = "E.g. accounts.example.com or example-pay.com.")
        fun domain(): String
    }
}

/** The start URL's eTLD+1 plus the user's Allowed domains. */
fun AgentConfiguration.allowedDomainSet(): Set<String> =
    (listOfNotNull(hostOf(startUrl().trim())?.let(::registrableDomain)) + allowedDomains().orEmpty().mapNotNull { normalizeDomain(it.domain()) })
        .toSet()

/** Slots 1..3 as (name, value); blank counts as empty. */
fun AgentConfiguration.secretSlots(): List<Pair<String?, Secret?>> =
    listOf(secret1Name() to secret1Value(), secret2Name() to secret2Value(), secret3Name() to secret3Value())
        .map { (name, value) -> name?.trim()?.ifEmpty { null } to value?.takeIf { it.reveal().isNotEmpty() } }

/** The usable secrets by name: slots with both a name and a value. */
fun AgentConfiguration.secrets(): Map<String, Secret> =
    secretSlots().mapNotNull { (name, value) -> if (name != null && value != null) name to value else null }.toMap()

enum class RunMode { Once }

enum class Provider { Anthropic, OpenAICompatible }
