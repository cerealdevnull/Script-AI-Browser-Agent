package com.cereal.script.aiagent

import com.cereal.sdk.ScriptConfiguration
import com.cereal.sdk.ScriptConfigurationItem
import com.cereal.sdk.ScriptConfigurationListItem
import com.cereal.sdk.ScriptConfigurationSections
import com.cereal.sdk.Section
import com.cereal.sdk.models.Secret
import com.cereal.sdk.statemodifier.ScriptConfig
import com.cereal.sdk.statemodifier.ScriptConfigValue
import com.cereal.sdk.statemodifier.StateModifier
import com.cereal.sdk.statemodifier.Visibility

// keyNames are persisted by the host: never change them. Sections are layout only.
@ScriptConfigurationSections(
    Section("task", "Task"),
    Section("provider", "AI provider"),
    Section("secrets", "Sign-in secrets"),
    Section("advanced", "Advanced", collapsed = true),
)
interface AgentConfiguration : ScriptConfiguration {
    @ScriptConfigurationItem(
        position = 0,
        keyName = "goal",
        section = "task",
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
        section = "task",
        name = "Start URL",
        description = "The page the agent starts on.",
        stateModifier = StartUrlState::class,
    )
    fun startUrl(): String

    @ScriptConfigurationItem(
        position = 2,
        keyName = "runMode",
        section = "task",
        name = "Run mode",
        description =
            "Once: reach the goal and notify the result. Repeat: a monitor. The first run learns with the model; later checks replay " +
                "it without the model and notify once when the goal is met.",
    )
    fun runMode(): RunMode = RunMode.Once

    @ScriptConfigurationItem(
        position = 3,
        keyName = "repeatInterval",
        section = "task",
        name = "Check every (seconds)",
        description = "How long to wait between checks.",
        stateModifier = CheckEveryState::class,
    )
    fun repeatInterval(): Int = 300

    @ScriptConfigurationItem(
        position = 4,
        keyName = "provider",
        section = "provider",
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
        section = "provider",
        name = "API key",
        description =
            "Get a key: [Anthropic](https://console.anthropic.com/settings/keys) · [Gemini](https://aistudio.google.com/apikey) · " +
                "[OpenAI](https://platform.openai.com/api-keys) · [OpenRouter](https://openrouter.ai/settings/keys). Optional for Custom.",
        stateModifier = ApiKeyState::class,
    )
    fun apiKey(): Secret?

    @ScriptConfigurationItem(
        position = 6,
        keyName = "model",
        section = "provider",
        name = "Model",
        description = "Leave empty for the recommended model. Required for Custom.",
        stateModifier = ModelState::class,
        options = ModelOptions::class,
    )
    fun model(): String?

    // Was "Base URL"; the keyName stays.
    @ScriptConfigurationItem(
        position = 7,
        keyName = "baseUrl",
        section = "provider",
        name = "Server URL",
        description = "Your server's OpenAI-compatible address. The hint is Ollama's; LM Studio's is http://localhost:1234/v1.",
        stateModifier = ServerUrlState::class,
    )
    fun baseUrl(): String?

    @ScriptConfigurationItem(
        position = 31,
        keyName = "maxSteps",
        section = "advanced",
        name = "Max steps per run",
        description = "Each model call is one step. A run that reaches this many steps stops.",
        stateModifier = MaxStepsState::class,
    )
    fun maxSteps(): Int = 40

    @ScriptConfigurationItem(
        position = 32,
        keyName = "maxRunCost",
        section = "advanced",
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
        section = "advanced",
        name = "Max $ per task",
        description = "The task stops once all its runs together have cost this many dollars. Resets when the task restarts.",
        stateModifier = MaxTaskCostState::class,
    )
    fun maxTaskCost(): Double = 5.0

    // Replaces the secret slots: keyNames secret1Name..secret3Value are retired, never reuse them.
    @ScriptConfigurationItem(
        position = 20,
        keyName = "secrets",
        section = "secrets",
        name = "Sign-in secrets",
        description =
            "Things the agent types for you, such as your email and password. Give each a label and use the same words in your goal " +
                "(log in with my email and password). The AI never sees the value, and neither do the logs.",
        stateModifier = SecretsState::class,
    )
    fun secrets(): List<NamedSecret>?

    @ScriptConfigurationItem(
        position = 30,
        keyName = "allowedDomains",
        section = "advanced",
        name = "Allowed domains",
        description =
            "Extra domains the agent may open, besides the start URL's own (e.g. a login or payment provider). " +
                "Subdomains are included.",
    )
    fun allowedDomains(): List<AllowedDomain>?

    // Retired keyNames, never reuse: "reLearn", "onNextStart" (resets are task actions now).

    // Nested so the shrunk jar's metadata restore (AgentConfiguration*.class) covers it: the host reflects on it.
    interface AllowedDomain : ScriptConfigurationListItem {
        @ScriptConfigurationItem(keyName = "domain", name = "Domain", description = "E.g. accounts.example.com or example-pay.com.")
        fun domain(): String
    }

    // Nested for the same metadata restore as AllowedDomain.
    interface NamedSecret : ScriptConfigurationListItem {
        @ScriptConfigurationItem(keyName = "label", name = "Label", description = "What this is, e.g. email or password.")
        fun label(): String

        @ScriptConfigurationItem(keyName = "value", name = "Value", description = "The value itself, e.g. your password.")
        fun value(): Secret
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

fun modelError(provider: Provider, model: String?): String? {
    if (provider == Provider.Custom) return if (model.isNullOrBlank()) "Model is required for Custom" else null
    val id = model?.trim().orEmpty()
    val owner = modelOwner(id)?.takeIf { it != provider } ?: return null
    val article = if (owner.name.first() in "AEIOU") "an" else "a"
    return "`$id` is $article $owner model. Clear Model to use $provider's recommended model."
}

/** The preset an id clearly belongs to; null when unknown (the pre-flight judges those). */
private fun modelOwner(model: String) =
    model.removePrefix("models/").let { id -> // `models/<id>` is Gemini's own form, not OpenRouter's
        when {
            "/" in id -> Provider.OpenRouter
            id.startsWith("claude-") -> Provider.Anthropic
            id.startsWith("gemini-") -> Provider.Gemini
            id.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(id) -> Provider.OpenAI
            else -> null
        }
    }

fun serverUrlError(provider: Provider, baseUrl: String?) = if (provider == Provider.Custom && baseUrl.isNullOrBlank()) "Server URL is required for Custom" else null

fun positiveError(name: String, value: Number?) = if (value != null && value.toDouble() <= 0) "$name must be > 0" else null

/** A row as (trimmed label, value); blank or unset counts as empty. */
private fun row(label: String?, value: Secret?) = label?.trim()?.ifEmpty { null } to value?.takeIf { it.reveal().isNotEmpty() }

/** The Sign-in secrets rows as (label, value). getOrNull: an unset row field may reach us as null despite its type. */
fun AgentConfiguration.secretRows(): List<Pair<String?, Secret?>> =
    secrets().orEmpty().map { row(runCatching { it.label() }.getOrNull(), runCatching { it.value() }.getOrNull()) }

/** The form's rows, read the same way at Save. */
private fun ScriptConfig.secretRows(): List<Pair<String?, Secret?>> =
    (valueForKey("secrets") as? ScriptConfigValue.ListScriptConfigValue)?.items.orEmpty().map {
        row(
            (it.valueForKey("label") as? ScriptConfigValue.StringScriptConfigValue)?.value,
            (it.valueForKey("value") as? ScriptConfigValue.SecretScriptConfigValue)?.value,
        )
    }

/** The name the agent knows a label by: lowercase, spaces to `_` ("My Password" is `my_password`). */
fun secretName(label: String) = label.trim().lowercase().replace(Regex("\\s+"), "_")

/** The usable secrets by name: rows with both a label and a value. */
fun AgentConfiguration.secretsByName(): Map<String, Secret> =
    secretRows().mapNotNull { (label, value) -> if (label != null && value != null) secretName(label) to value else null }.toMap()

/** Every row's errors, each naming its row (1-based). */
fun secretRowErrors(rows: List<Pair<String?, Secret?>>): List<String> =
    rows.flatMapIndexed { i, (label, value) ->
        val n = i + 1
        val first = label?.let { rows.take(i).indexOfFirst { (other) -> other != null && secretName(other) == secretName(label) } } ?: -1
        listOfNotNull(
            "Secret $n needs a label, e.g. password".takeIf { label == null },
            "Secret $n's label '$label' is already used by secret ${first + 1}".takeIf { first >= 0 },
            (if (label == null) "Secret $n needs a value" else "Secret $n ('$label') needs a value").takeIf { value == null },
        )
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

internal fun ScriptConfig.provider() = (valueForKey("provider") as? ScriptConfigValue.EnumScriptConfigValue)?.value as? Provider ?: Provider.Anthropic

internal fun ScriptConfig.string(key: String) = (valueForKey(key) as? ScriptConfigValue.StringScriptConfigValue)?.value

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

object StartUrlState : ErrorOnlyState({ startUrlError(it.string("startUrl")) }) {
    override fun getPlaceholder(config: ScriptConfig) = "https://example.com"
}

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

    override fun getPlaceholder(config: ScriptConfig) = config.provider().recommendedModel
}

object ServerUrlState : StateModifier {
    override fun getVisibility(config: ScriptConfig) = if (config.provider() == Provider.Custom) Visibility.VisibleRequired else Visibility.Hidden

    override fun getError(config: ScriptConfig) = serverUrlError(config.provider(), config.string("baseUrl"))

    override fun getPlaceholder(config: ScriptConfig) = "http://localhost:11434/v1"
}

object CheckEveryState : StateModifier {
    private fun repeat(config: ScriptConfig) = (config.valueForKey("runMode") as? ScriptConfigValue.EnumScriptConfigValue)?.value == RunMode.Repeat

    override fun getVisibility(config: ScriptConfig) = if (repeat(config)) Visibility.VisibleRequired else Visibility.Hidden

    override fun getError(config: ScriptConfig) = if (repeat(config)) positiveError("Check every", config.number("repeatInterval")) else null
}

/** The rows' errors in one message on the list (the SDK has no per-row error). */
object SecretsState : ErrorOnlyState({ secretRowErrors(it.secretRows()).joinToString(". ").ifEmpty { null } })
