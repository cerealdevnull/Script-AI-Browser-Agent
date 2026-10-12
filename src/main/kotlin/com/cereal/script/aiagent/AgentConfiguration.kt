package com.cereal.script.aiagent

import com.cereal.sdk.ScriptConfiguration
import com.cereal.sdk.ScriptConfigurationItem
import com.cereal.sdk.ScriptConfigurationListItem
import com.cereal.sdk.models.Secret
import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import com.cereal.sdk.statemodifier.StateModifier
import com.cereal.sdk.statemodifier.Visibility

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
        description =
            "Once: reach the goal and notify the result. Repeat: a monitor. The first run learns with the model; later checks replay " +
                "it without the model and notify once when the goal is met.",
    )
    fun runMode(): RunMode = RunMode.Once

    @ScriptConfigurationItem(
        position = 3,
        keyName = "provider",
        name = "Provider",
        description =
            "The company that runs the AI. You pay them directly with your own API key. Anthropic is recommended. " +
                "Google Gemini has a free tier with no card, but Google may use your content to improve its products. " +
                "Custom: any OpenAI-compatible server, such as Ollama or LM Studio.",
    )
    fun provider(): Provider = Provider.Anthropic

    @ScriptConfigurationItem(
        position = 4,
        keyName = "apiKey",
        name = "API key",
        description =
            "Your key from the provider above, sent only to them. Get one at: Anthropic https://console.anthropic.com/settings/keys · " +
                "Gemini https://aistudio.google.com/apikey · OpenAI https://platform.openai.com/api-keys. Optional for Custom.",
        stateModifier = ApiKeyState::class,
    )
    fun apiKey(): Secret?

    @ScriptConfigurationItem(
        position = 5,
        keyName = "model",
        name = "Model",
        description =
            "Leave empty for the recommended model (Anthropic: Claude Sonnet 5.5, Gemini: Gemini 3.8 Flash, OpenAI: GPT-5.6 Luna). " +
                "Required for Custom.",
        stateModifier = ModelState::class,
    )
    fun model(): String?

    // Was "Base URL"; the keyName stays.
    @ScriptConfigurationItem(
        position = 6,
        keyName = "baseUrl",
        name = "Server URL",
        description = "Your server's OpenAI-compatible address, e.g. http://localhost:11434/v1 (Ollama) or http://localhost:1234/v1 (LM Studio).",
        stateModifier = ServerUrlState::class,
    )
    fun baseUrl(): String?

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
        position = 10,
        keyName = "repeatInterval",
        name = "Repeat interval (seconds)",
        description = "Repeat mode only: how long to wait between checks.",
    )
    fun repeatInterval(): Int = 300

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

    @ScriptConfigurationItem(
        position = 40,
        keyName = "reLearn",
        name = "Re-learn",
        description =
            "Forget what was learned for this goal when the task starts (e.g. after the site changed). " +
                "The Chrome profile is kept: log out on the site, or delete the task, to reset it.",
    )
    fun reLearn(): Boolean = false

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

enum class RunMode { Once, Repeat }

/** A preset fixes the server and recommends a model; Custom takes both from the form. */
enum class Provider(val baseUrl: String?, val recommendedModel: String?, val keyUrl: String?) {
    Anthropic(AnthropicClient.DEFAULT_BASE_URL, "claude-sonnet-5-5", "https://console.anthropic.com/settings/keys"),
    Gemini("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash", "https://aistudio.google.com/apikey"),
    OpenAI(OpenAiCompatibleClient.DEFAULT_BASE_URL, "gpt-5.6-luna", "https://platform.openai.com/api-keys"),
    Custom(null, null, null),
}

private fun ScriptConfig.provider() = (valueForKey("provider") as? ScriptConfigValue.EnumScriptConfigValue)?.value ?: Provider.Anthropic

private fun visibleRequiredIf(required: Boolean) = if (required) Visibility.VisibleRequired else Visibility.VisibleOptional

object ApiKeyState : StateModifier {
    override fun getVisibility(config: ScriptConfig) = visibleRequiredIf(config.provider() != Provider.Custom)

    override fun getError(config: ScriptConfig): String? = null
}

object ModelState : StateModifier {
    override fun getVisibility(config: ScriptConfig) = visibleRequiredIf(config.provider() == Provider.Custom)

    override fun getError(config: ScriptConfig): String? = null
}

object ServerUrlState : StateModifier {
    override fun getVisibility(config: ScriptConfig) = if (config.provider() == Provider.Custom) Visibility.VisibleRequired else Visibility.Hidden

    override fun getError(config: ScriptConfig): String? = null
}
