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
        description =
            "What the agent should do, in plain language. Once: tell me whether Long Nose Pliers is in stock. " +
                "Repeat: notify me when Long Nose Pliers is in stock.",
    )
    fun goal(): String

    @ScriptConfigurationItem(
        position = 1,
        keyName = "startUrl",
        name = "Start URL",
        description = "The page the agent starts on, e.g. https://example.com.",
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
        keyName = "repeatInterval",
        name = "Check every (seconds)",
        description = "How long to wait between checks.",
        stateModifier = CheckEveryState::class,
    )
    fun repeatInterval(): Int = 300

    @ScriptConfigurationItem(
        position = 4,
        keyName = "provider",
        name = "Provider",
        description =
            "The company that runs the AI. You pay them directly with your own API key. Anthropic is recommended. " +
                "Google Gemini has a free tier with no card, but Google may use your content to improve its products. " +
                "Custom: any OpenAI-compatible server, such as Ollama or LM Studio.",
    )
    fun provider(): Provider = Provider.Anthropic

    @ScriptConfigurationItem(
        position = 5,
        keyName = "apiKey",
        name = "API key",
        description =
            "Your key from the provider above, sent only to them. Get one at: Anthropic https://console.anthropic.com/settings/keys · " +
                "Gemini https://aistudio.google.com/apikey · OpenAI https://platform.openai.com/api-keys · " +
                "OpenRouter https://openrouter.ai/settings/keys. Optional for Custom.",
        stateModifier = ApiKeyState::class,
    )
    fun apiKey(): Secret?

    @ScriptConfigurationItem(
        position = 6,
        keyName = "model",
        name = "Model",
        description =
            "Leave empty for the recommended model (Anthropic: Claude Sonnet 5.5, Gemini: Gemini 3.8 Flash, OpenAI: GPT-5.6 Luna, " +
                "OpenRouter: Claude Sonnet 5.5). " +
                "Required for Custom.",
        stateModifier = ModelState::class,
    )
    fun model(): String?

    // Was "Base URL"; the keyName stays.
    @ScriptConfigurationItem(
        position = 7,
        keyName = "baseUrl",
        name = "Server URL",
        description = "Your server's OpenAI-compatible address, e.g. http://localhost:11434/v1 (Ollama) or http://localhost:1234/v1 (LM Studio).",
        stateModifier = ServerUrlState::class,
    )
    fun baseUrl(): String?

    @ScriptConfigurationItem(
        position = 31,
        keyName = "maxSteps",
        name = "Max steps per run",
        description = "Each model call is one step. A run that reaches this many steps stops.",
    )
    fun maxSteps(): Int = 40

    @ScriptConfigurationItem(
        position = 32,
        keyName = "maxRunCost",
        name = "Max $ per run",
        description =
            "A run stops once its model calls have cost this many dollars. " +
                "A typical run on the recommended model costs about \$0.20; simple goals less, long ones up to \$1.",
    )
    fun maxRunCost(): Double = 2.0

    @ScriptConfigurationItem(
        position = 33,
        keyName = "maxTaskCost",
        name = "Max $ per task",
        description = "The task stops once all its runs together have cost this many dollars. Resets when the task restarts.",
    )
    fun maxTaskCost(): Double = 5.0

    @ScriptConfigurationItem(
        position = 20,
        keyName = "secret1Name",
        name = "Secret 1 label",
        description =
            "What this is, in one word, e.g. email or password. Use the word in your goal (log in with my email and password) " +
                "and the agent types the value for you. The AI never sees the value.",
        stateModifier = Secret1LabelState::class,
    )
    fun secret1Name(): String?

    @ScriptConfigurationItem(
        position = 21,
        keyName = "secret1Value",
        name = "Secret 1 value",
        description = "The value itself, e.g. your password. Hidden from the AI and the logs.",
        stateModifier = Secret1ValueState::class,
    )
    fun secret1Value(): Secret?

    @ScriptConfigurationItem(
        position = 22,
        keyName = "secret2Name",
        name = "Secret 2 label",
        description = "Another word, e.g. password.",
        stateModifier = Secret2LabelState::class,
    )
    fun secret2Name(): String?

    @ScriptConfigurationItem(
        position = 23,
        keyName = "secret2Value",
        name = "Secret 2 value",
        description = "The value itself. Hidden from the AI and the logs.",
        stateModifier = Secret2ValueState::class,
    )
    fun secret2Value(): Secret?

    @ScriptConfigurationItem(
        position = 24,
        keyName = "secret3Name",
        name = "Secret 3 label",
        description = "Another word, e.g. password.",
        stateModifier = Secret3LabelState::class,
    )
    fun secret3Name(): String?

    @ScriptConfigurationItem(
        position = 25,
        keyName = "secret3Value",
        name = "Secret 3 value",
        description = "The value itself. Hidden from the AI and the logs.",
        stateModifier = Secret3ValueState::class,
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

/** One slot as (trimmed label, value); blank counts as empty. */
private fun slot(label: String?, value: Secret?) = label?.trim()?.ifEmpty { null } to value?.takeIf { it.reveal().isNotEmpty() }

/** Slots 1..3 as (label, value). */
fun AgentConfiguration.secretSlots(): List<Pair<String?, Secret?>> =
    listOf(slot(secret1Name(), secret1Value()), slot(secret2Name(), secret2Value()), slot(secret3Name(), secret3Value()))

/** The form's slots 1..3, read the same way at Save. */
private fun ScriptConfig.secretSlots(): List<Pair<String?, Secret?>> =
    (1..3).map { n ->
        slot(
            (valueForKey("secret${n}Name") as? ScriptConfigValue.StringScriptConfigValue)?.value,
            (valueForKey("secret${n}Value") as? ScriptConfigValue.SecretScriptConfigValue)?.value,
        )
    }

/** The name the agent knows a label by: lowercase, spaces to `_` ("My Password" is `my_password`). */
fun secretName(label: String) = label.trim().lowercase().replace(Regex("\\s+"), "_")

/** The usable secrets by name: slots with both a label and a value. */
fun AgentConfiguration.secrets(): Map<String, Secret> =
    secretSlots().mapNotNull { (label, value) -> if (label != null && value != null) secretName(label) to value else null }.toMap()

/** Slot [i]'s errors as (under its label, under its value). */
fun secretSlotErrors(slots: List<Pair<String?, Secret?>>, i: Int): Pair<String?, String?> {
    val (label, value) = slots[i]
    val labelError =
        when {
            label == null -> if (value != null) "Give this value a label, e.g. password" else null
            slots.take(i).any { (other) -> other != null && secretName(other) == secretName(label) } -> "The label '$label' is already used by another secret"
            else -> null
        }
    return labelError to if (label != null && value == null) "Enter the value for '$label'" else null
}

enum class RunMode { Once, Repeat }

/** A preset fixes the server and recommends a model; Custom takes both from the form. */
enum class Provider(val baseUrl: String?, val recommendedModel: String?, val keyUrl: String?) {
    Anthropic(AnthropicClient.DEFAULT_BASE_URL, "claude-sonnet-5-5", "https://console.anthropic.com/settings/keys"),
    Gemini("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash", "https://aistudio.google.com/apikey"),
    OpenAI(OpenAiCompatibleClient.DEFAULT_BASE_URL, "gpt-5.6-luna", "https://platform.openai.com/api-keys"),
    OpenRouter("https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5.5", "https://openrouter.ai/settings/keys"),
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

object CheckEveryState : StateModifier {
    override fun getVisibility(config: ScriptConfig) =
        if ((config.valueForKey("runMode") as? ScriptConfigValue.EnumScriptConfigValue)?.value == RunMode.Repeat) Visibility.VisibleRequired else Visibility.Hidden

    override fun getError(config: ScriptConfig): String? = null
}

/** Secret slot N shows once slot N-1 has a label; its errors show under the label or the value field. */
abstract class SecretState(private val slot: Int, private val onValue: Boolean) : StateModifier {
    override fun getVisibility(config: ScriptConfig) =
        if (slot == 0 || config.secretSlots()[slot - 1].first != null) Visibility.VisibleOptional else Visibility.Hidden

    override fun getError(config: ScriptConfig): String? {
        if (getVisibility(config) == Visibility.Hidden) return null
        val (labelError, valueError) = secretSlotErrors(config.secretSlots(), slot)
        return if (onValue) valueError else labelError
    }
}

object Secret1LabelState : SecretState(0, false)

object Secret1ValueState : SecretState(0, true)

object Secret2LabelState : SecretState(1, false)

object Secret2ValueState : SecretState(1, true)

object Secret3LabelState : SecretState(2, false)

object Secret3ValueState : SecretState(2, true)
