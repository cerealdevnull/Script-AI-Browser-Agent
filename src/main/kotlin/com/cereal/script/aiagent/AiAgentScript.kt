package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.Script
import com.cereal.sdk.component.ComponentProvider
import com.cereal.sdk.component.notification.Notification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest

class AiAgentScript(
    private val newModelClient: (AgentConfiguration) -> ModelClient = ::modelClient,
    private val profilesDir: Path = Path.of(System.getProperty("user.home"), ".cereal", "ai-browser-agent", "profiles"),
) : Script<AgentConfiguration> {
    private var configError: String? = null
    private var browser: Browser? = null

    // onStart can only answer true/false (false shows a generic host error), so the first execute() reports it.
    override suspend fun onStart(configuration: AgentConfiguration, provider: ComponentProvider): Boolean {
        configError = validate(configuration)
        return true
    }

    override suspend fun execute(
        configuration: AgentConfiguration,
        provider: ComponentProvider,
        statusUpdate: suspend (message: String) -> Unit,
    ): ExecutionResult {
        configError?.let { return fail(configuration, provider, it) }
        val scrubber = Scrubber(configuration.apiKey()?.reveal())
        val log = provider.logger()
        val browser =
            browser ?: try {
                Browser.launch(profilesDir.resolve(profileName(configuration))).also { browser = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(configuration, provider, "Chrome could not be started: ${e.message}")
            }
        val outcome =
            try {
                Agent(
                    browser,
                    newModelClient(configuration),
                    configuration.goal(),
                    configuration.startUrl().trim(),
                    { log.info(scrubber.forLog(it)) },
                    configuration.secrets(),
                    scrubber,
                    configuration.allowedDomainSet(),
                ).run()
            } catch (e: ModelException) {
                return fail(configuration, provider, scrubber.forLog("Model error: ${e.message}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A thrown exception becomes a generic host error (and a report carrying the config): return Error.
                // Logged as scrubbed text, not as a Throwable: its message could carry a secret or the API key.
                log.error(scrubber.forLog("goal run failed: ${e.stackTraceToString()}"))
                return fail(configuration, provider, scrubber.forLog("Unexpected error: ${e.message}"))
            }
        return when (outcome) {
            is Outcome.Failed -> fail(configuration, provider, outcome.reason, "\n" + stepCount(outcome.steps))
            is Outcome.Finished -> {
                val verdict = if (outcome.goalMet) "Goal met" else "Goal not met"
                notify(configuration, provider, "$verdict\n${outcome.summary.take(300)}\n" + stepCount(outcome.steps))
                // A Once run that answers "not met" still completed its job.
                ExecutionResult.Success(verdict)
            }
        }
    }

    // The host calls this from `finally`, also when the task is cancelled: stop Chrome even in a cancelled coroutine.
    override suspend fun onFinish(configuration: AgentConfiguration, provider: ComponentProvider) {
        val browser = browser ?: return
        this.browser = null
        withContext(NonCancellable) { browser.close() }
    }

    private suspend fun fail(
        config: AgentConfiguration,
        provider: ComponentProvider,
        reason: String,
        suffix: String = "",
    ): ExecutionResult {
        notify(config, provider, "Failed: $reason$suffix")
        return ExecutionResult.Error(reason)
    }

    private suspend fun notify(config: AgentConfiguration, provider: ComponentProvider, body: String) {
        provider.notification().sendNotification(Notification("AI agent: " + config.goal().take(60), body))
    }

    private fun validate(config: AgentConfiguration): String? {
        val scheme = runCatching { URI(config.startUrl().trim()).scheme?.lowercase() }.getOrNull()
        return when {
            config.goal().isBlank() -> "Goal is required"
            config.startUrl().isBlank() -> "Start URL is required"
            scheme != "http" && scheme != "https" -> "Start URL must be an http(s) URL"
            config.provider() == Provider.Anthropic && config.apiKey()?.reveal().isNullOrBlank() -> "API key is required for Anthropic"
            config.model().isBlank() -> "Model is required"
            else -> validateSecrets(config)
        }
    }

    private fun validateSecrets(config: AgentConfiguration): String? {
        val slots = config.secretSlots()
        slots.forEachIndexed { i, (name, value) ->
            if (name != null && !SECRET_NAME.matches(name)) return "Secret ${i + 1} name must match ${SECRET_NAME.pattern}"
            if (name == null && value != null) return "Secret ${i + 1} has a value but no name"
        }
        val names = slots.mapNotNull { it.first }
        return names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { "Secret names must be unique: $it" }
    }

    // ponytail: the SDK gives scripts no task id, so the profile is keyed by goal + start URL. Two tasks with the
    // same goal and URL share a profile (and can't run at the same time); key by task id if the SDK ever offers one.
    private fun profileName(config: AgentConfiguration): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${config.goal()}\n${config.startUrl().trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
}

private fun modelClient(config: AgentConfiguration): ModelClient {
    val baseUrl = config.baseUrl()?.trim()?.takeIf(String::isNotEmpty)
    val model = config.model().trim()
    return when (config.provider()) {
        Provider.Anthropic -> AnthropicClient(baseUrl ?: AnthropicClient.DEFAULT_BASE_URL, config.apiKey()!!.reveal(), model)
        Provider.OpenAICompatible -> OpenAiCompatibleClient(baseUrl ?: OpenAiCompatibleClient.DEFAULT_BASE_URL, config.apiKey()?.reveal(), model)
    }
}
private val SECRET_NAME = Regex("[a-z][a-z0-9_]*")

private fun stepCount(steps: Int) = if (steps == 1) "1 step" else "$steps steps"
