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
        stateModifier = GoalState::class,
    )
    fun goal(): String

    @ScriptConfigurationItem(
        position = 1,
        keyName = "startUrl",
        name = "Start URL",
        description = "The page the agent starts on, e.g. https://example.com.",
        stateModifier = StartUrlState::class,
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
        stateModifier = MaxStepsState::class,
    )
    fun maxSteps(): Int = 40

    @ScriptConfigurationItem(
        position = 32,
        keyName = "maxRunCost",
        name = "Max $ per run",
        description =
            "A run stops once its model calls have cost this many dollars. " +
                "A typical run on the recommended model costs about \$0.20; simple goals less, long ones up to \$1.",
        stateModifier = MaxRunCostState::class,
    )
    fun maxRunCost(): Double = 2.0

    @ScriptConfigurationItem(
        position = 33,
        keyName = "maxTaskCost",
        name = "Max $ per task",
        description = "The task stops once all its runs together have cost this many dollars. Resets when the task restarts.",
        stateModifier = MaxTaskCostState::class,
    )
    fun maxTaskCost(): Double = 5.0

    @ScriptConfigurationItem(
        position = 20,
        keyName = "secret1Name",
        name = "Secret 1 label",
        description =
            "What this is, in one word, e.g. email or password. Use the word in your goal (log in with my email and password) " +
                "and the agent types the value for you. The AI never sees the value.",
    )
    fun secret1Name(): String?

    @ScriptConfigurationItem(
        position = 21,
        keyName = "secret1Value",
        name = "Secret 1 value",
        description = "The value itself, e.g. your password. Hidden from the AI and the logs.",
    )
    fun secret1Value(): Secret?

    @ScriptConfigurationItem(
        position = 22,
        keyName = "secret2Name",
        name = "Secret 2 label",
        description = "Another word, e.g. password.",
        stateModifier = Secret2State::class,
    )
    fun secret2Name(): String?

    @ScriptConfigurationItem(
        position = 23,
        keyName = "secret2Value",
        name = "Secret 2 value",
        description = "The value itself. Hidden from the AI and the logs.",
        stateModifier = Secret2State::class,
    )
    fun secret2Value(): Secret?

    @ScriptConfigurationItem(
        position = 24,
        keyName = "secret3Name",
        name = "Secret 3 label",
        description = "Another word, e.g. password.",
        stateModifier = Secret3State::class,
    )
    fun secret3Name(): String?

    @ScriptConfigurationItem(
        position = 25,
        keyName = "secret3Value",
        name = "Secret 3 value",
        description = "The value itself. Hidden from the AI and the logs.",
        stateModifier = Secret3State::class,
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
    (listOfNotNull(hostOf(startPage())?.let(::registrableDomain)) + allowedDomains().orEmpty().mapNotNull { normalizeDomain(it.domain()) })
        .toSet()

/** The Start URL as the agent opens it: `https://` added when the user typed none. */
fun AgentConfiguration.startPage(): String = normalizeStartUrl(startUrl()) ?: startUrl().trim()

private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?![0-9])")

/** An http(s) URL with a host, `https://` added when there is no scheme (a `host:port` has none); else null. */
fun normalizeStartUrl(raw: String): String? {
    val trimmed = raw.trim()
    val url = if (HAS_SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    return url.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() }
}

// The static checks, shared by the form (StateModifier.getError) and the run's own validation.
fun goalError(goal: String?) = if (goal.isNullOrBlank()) "Goal is required" else null

fun startUrlError(startUrl: String?) =
    when {
        startUrl.isNullOrBlank() -> "Start URL is required"
        normalizeStartUrl(startUrl) == null -> "Start URL must be an http(s) URL"
        else -> null
    }

fun apiKeyError(provider: Provider, apiKey: String?) = if (provider != Provider.Custom && apiKey.isNullOrBlank()) "API key is required for $provider" else null

fun modelError(provider: Provider, model: String?) = if (provider == Provider.Custom && model.isNullOrBlank()) "Model is required for Custom" else null

fun serverUrlError(provider: Provider, baseUrl: String?) = if (provider == Provider.Custom && baseUrl.isNullOrBlank()) "Server URL is required for Custom" else null

fun positiveError(name: String, value: Number?) = if (value != null && value.toDouble() <= 0) "$name must be > 0" else null

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
    OpenRouter("https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5.5", "https://openrouter.ai/settings/keys"),
    Custom(null, null, null),
}

private fun ScriptConfig.provider() = (valueForKey("provider") as? ScriptConfigValue.EnumScriptConfigValue)?.value as? Provider ?: Provider.Anthropic

private fun ScriptConfig.string(key: String) = (valueForKey(key) as? ScriptConfigValue.StringScriptConfigValue)?.value

private fun ScriptConfig.number(key: String): Number? =
    when (val v = valueForKey(key)) {
        is ScriptConfigValue.IntScriptConfigValue -> v.value
        is ScriptConfigValue.DoubleScriptConfigValue -> v.value
        is ScriptConfigValue.FloatScriptConfigValue -> v.value
        else -> null
    }

private fun visibleRequiredIf(required: Boolean) = if (required) Visibility.VisibleRequired else Visibility.VisibleOptional

/** An always-visible field with only a static error. */
abstract class ErrorOnlyState(private val error: (ScriptConfig) -> String?) : StateModifier {
    override fun getVisibility(config: ScriptConfig) = Visibility.VisibleOptional

    override fun getError(config: ScriptConfig) = error(config)
}

object GoalState : ErrorOnlyState({ goalError(it.string("goal")) })

object StartUrlState : ErrorOnlyState({ startUrlError(it.string("startUrl")) })

object MaxStepsState : ErrorOnlyState({ positiveError("Max steps per run", it.number("maxSteps")) })

object MaxRunCostState : ErrorOnlyState({ positiveError("Max \$ per run", it.number("maxRunCost")) })

object MaxTaskCostState : ErrorOnlyState({ positiveError("Max \$ per task", it.number("maxTaskCost")) })

object ApiKeyState : StateModifier {
    override fun getVisibility(config: ScriptConfig) = visibleRequiredIf(config.provider() != Provider.Custom)

    override fun getError(config: ScriptConfig) =
        apiKeyError(config.provider(), (config.valueForKey("apiKey") as? ScriptConfigValue.SecretScriptConfigValue)?.value?.reveal())
}

object ModelState : StateModifier {
    override fun getVisibility(config: ScriptConfig) = visibleRequiredIf(config.provider() == Provider.Custom)

    override fun getError(config: ScriptConfig) = modelError(config.provider(), config.string("model"))
}

object ServerUrlState : StateModifier {
    override fun getVisibility(config: ScriptConfig) = if (config.provider() == Provider.Custom) Visibility.VisibleRequired else Visibility.Hidden

    override fun getError(config: ScriptConfig) = serverUrlError(config.provider(), config.string("baseUrl"))
}

object CheckEveryState : StateModifier {
    private fun repeat(config: ScriptConfig) = (config.valueForKey("runMode") as? ScriptConfigValue.EnumScriptConfigValue)?.value == RunMode.Repeat

    override fun getVisibility(config: ScriptConfig) = if (repeat(config)) Visibility.VisibleRequired else Visibility.Hidden

    override fun getError(config: ScriptConfig) = if (repeat(config)) positiveError("Check every", config.number("repeatInterval")) else null
}

/** Secret slot N shows once slot N-1 has a label. */
abstract class NextSecretState(private val previousLabelKey: String) : StateModifier {
    override fun getVisibility(config: ScriptConfig) =
        if ((config.valueForKey(previousLabelKey) as? ScriptConfigValue.StringScriptConfigValue)?.value.isNullOrBlank()) Visibility.Hidden else Visibility.VisibleOptional

    override fun getError(config: ScriptConfig): String? = null
}

object Secret2State : NextSecretState("secret1Name")

object Secret3State : NextSecretState("secret2Name")
