package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.Script
import com.cereal.sdk.component.ComponentProvider
import com.cereal.sdk.component.notification.Notification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest

class AiAgentScript(
    private val newModelClient: (AgentConfiguration) -> ModelClient = ::modelClient,
    private val profilesDir: Path = Path.of(System.getProperty("user.home"), ".cereal", "ai-browser-agent", "profiles"),
    private val pageLoadTimeoutMs: Long = 60_000,
) : Script<AgentConfiguration> {
    private var configError: String? = null
    private var browser: Browser? = null

    // The task cap counts every run of this task; held in memory only, so it resets when the task restarts.
    private var taskSpent = 0.0

    // One per task: values revealed in an earlier run (e.g. a replayed login) stay scrubbed in later Loop iterations.
    private var scrubber: Scrubber? = null

    // Repeat: consecutive iterations that failed transiently (page load, network, model 429/5xx after retries).
    private var transientFailures = 0

    // onStart can only answer true/false (false shows a generic host error), so the first execute() reports it.
    override suspend fun onStart(configuration: AgentConfiguration, provider: ComponentProvider): Boolean {
        configError = validate(configuration)
        taskSpent = 0.0
        scrubber = null
        transientFailures = 0
        if (configError == null && configuration.reLearn()) provider.preference().delete(cacheKey(configuration))
        return true
    }

    override suspend fun execute(
        configuration: AgentConfiguration,
        provider: ComponentProvider,
        statusUpdate: suspend (message: String) -> Unit,
    ): ExecutionResult {
        configError?.let { return fail(configuration, provider, it) }
        val scrubber = scrubber ?: Scrubber(configuration.apiKey()?.reveal()).also { scrubber = it }
        val log = provider.logger()
        val browser =
            browser?.takeIf { it.alive } ?: try {
                this.browser?.let { dead ->
                    log.warn("Chrome was closed or crashed: starting it again")
                    this.browser = null
                    dead.close()
                }
                statusUpdate("Starting Chrome")
                Browser.launch(profilesDir.resolve(profileName(configuration)), pageLoadTimeoutMs).also { this.browser = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(configuration, provider, "Chrome could not be started: ${e.message}", "\n${stepCount(0)}, ${usd(0.0)}")
            }
        val caps = Caps(configuration.maxSteps(), configuration.maxRunCost(), configuration.maxTaskCost(), taskSpent)
        val agent =
            Agent(
                browser,
                newModelClient(configuration),
                configuration.goal(),
                configuration.startUrl().trim(),
                caps,
                { log.info(scrubber.forLog(it)) },
                { log.warn(scrubber.forLog(it)) },
                configuration.secrets(),
                scrubber,
                configuration.allowedDomainSet(),
                {
                    statusUpdate(scrubber.forLog("Waiting for your confirmation: ${it.action} on ${it.element}"))
                    confirm(configuration, provider, scrubber, it)
                },
                configuration.runMode(),
                { statusUpdate(scrubber.forLog(it)) },
            )
        val repeat = configuration.runMode() == RunMode.Repeat
        val key = cacheKey(configuration)
        var replayed = false
        val cost = {
            val steps = stepCount(agent.steps) + ", " + usd(agent.cost)
            "\n" + if (agent.healed) "replayed + healed in $steps" else if (replayed) "replayed, \$0" else steps
        }
        val outcome =
            try {
                // A Repeat run replays only with a Check; a Once run also with Readouts. Otherwise it learns.
                val cache = ReplayCache.parse(provider.preference().getString(key))?.takeIf { it.check != null || (!repeat && it.readouts.isNotEmpty()) }
                replayed = cache != null
                // A learned or healed run rewrites the cache (a heal keeps the steps before the miss: see Agent.replay).
                (cache?.let { agent.replay(it) } ?: agent.run()).also {
                    if (it is Outcome.Finished && (!replayed || agent.healed)) writeCache(provider, key, it, repeat, cache?.check?.takeUnless { agent.checkMissed }) { m -> log.info(scrubber.forLog(m)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A thrown exception becomes a generic host error (and a report carrying the config): return Error.
                // Logged as scrubbed text, not as a Throwable: its message could carry a secret or the API key.
                log.error(scrubber.forLog("goal run failed: ${e.stackTraceToString()}"))
                return fail(configuration, provider, scrubber.forLog("Unexpected error: ${e.message}"), cost())
            } finally {
                taskSpent += agent.cost
            }
        // Scrubbed once: a failure reason can carry a revealed secret (e.g. in a URL that did not load).
        val verdict =
            scrubber.forLog(
                when (outcome) {
                    is Outcome.Failed -> "Failed: ${outcome.reason}"
                    is Outcome.Stopped -> "Stopped: ${outcome.reason}"
                    is Outcome.Finished -> if (outcome.goalMet) "Goal met" else "Goal not met"
                    is Outcome.Declined -> "Declined by user"
                },
            )
        statusUpdate(verdict)
        log.info("run: ${configuration.runMode()}, ${if (agent.healed) "healed" else if (replayed) "replayed" else "learned"}, ${stepCount(agent.steps)}, ${usd(agent.cost)}, $verdict")
        // A monitor survives a bad minute: retry at the next interval, end the task after MAX_TRANSIENT_FAILURES in a row.
        transientFailures = if (outcome is Outcome.Failed && outcome.transient) transientFailures + 1 else 0
        if (repeat && transientFailures in 1 until MAX_TRANSIENT_FAILURES) {
            log.warn("transient failure $transientFailures of $MAX_TRANSIENT_FAILURES, retrying at the next interval: $verdict")
            return ExecutionResult.Loop(verdict, configuration.repeatInterval() * 1000L)
        }
        return when (outcome) {
            is Outcome.Failed -> fail(configuration, provider, scrubber.forLog(outcome.reason), cost())
            is Outcome.Stopped -> {
                notify(configuration, provider, verdict + cost())
                ExecutionResult.Error(outcome.reason)
            }
            is Outcome.Declined -> {
                notify(configuration, provider, verdict + cost())
                ExecutionResult.Error("declined by user")
            }
            // A monitor stays quiet until the goal is met.
            is Outcome.Finished if repeat && !outcome.goalMet -> ExecutionResult.Loop(verdict, configuration.repeatInterval() * 1000L)
            is Outcome.Finished -> {
                val readouts = outcome.readouts.joinToString("") { scrubber.forLog("${it.label}: ${it.text}\n") }
                val summary = if (repeat || outcome.summary.isEmpty()) "" else scrubber.forLog(outcome.summary).take(300) + "\n"
                notify(configuration, provider, "$verdict\n$readouts$summary".removeSuffix("\n") + cost())
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

    /**
     * Stores what a finished learned or healed run can replay. Not when the Check or Readouts failed validation; a run
     * that found the goal already met can't show its Check tells met from not met, so it keeps the [cached] Check (a
     * heal's, unless that Check just missed) or none (a learned run's); a Once run needs a Check or a Readout.
     */
    private suspend fun writeCache(provider: ComponentProvider, key: String, run: Outcome.Finished, repeat: Boolean, cached: Check?, log: (String) -> Unit) {
        val check = if (run.goalMet) cached else run.check
        val skipped =
            when {
                !run.verified -> "the Check or Readouts failed validation"
                !repeat && check == null && run.readouts.isEmpty() -> "a Once run without a Check or Readout"
                else -> null
            }
        if (skipped != null) return log("replay cache not written: $skipped")
        val readouts = run.readouts.map { CachedReadout(it.label, it.fingerprint.json) }
        provider.preference().setString(key, ReplayCache(run.recorded, check, readouts).toJson())
    }

    /** Notifies, then shows the Confirm / Decline dialog (no timeout). Closing it, or any dialog error, is a Decline. */
    private suspend fun confirm(
        config: AgentConfiguration,
        provider: ComponentProvider,
        scrubber: Scrubber,
        step: Confirmation,
    ): Boolean {
        notify(config, provider, scrubber.forLog("Confirmation needed: ${step.action} on ${step.element}"))
        fun row(label: String, value: String) = "<tr><th>$label</th><td>${escapeHtml(scrubber.forLog(value))}</td></tr>"
        val html =
            "<!doctype html><html><body style=\"font-family: sans-serif\"><h2>Confirm this step?</h2><table>" +
                row("Action", step.action) + row("Element", step.element) + row("Page", step.url) +
                row("Reason", step.reason ?: "(none given)") +
                "</table><p><a href=\"$CONFIRM_URL\">Confirm</a> &nbsp; <a href=\"$DECLINE_URL\">Decline</a></p></body></html>"
        return try {
            provider.userInteraction().showHtml("AI agent: confirm step", html) { it.url == CONFIRM_URL || it.url == DECLINE_URL }.url == CONFIRM_URL
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // UserInteractionCanceledException (window closed) or a dialog failure. A stopped task also cancels the job.
            currentCoroutineContext().ensureActive()
            false
        }
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
            (scheme != "http" && scheme != "https") || hostOf(config.startUrl().trim()) == null -> "Start URL must be an http(s) URL"
            config.provider() == Provider.Anthropic && config.apiKey()?.reveal().isNullOrBlank() -> "API key is required for Anthropic"
            config.model().isBlank() -> "Model is required"
            config.maxSteps() <= 0 -> "Max steps per run must be > 0"
            config.maxRunCost() <= 0 -> "Max \$ per run must be > 0"
            config.maxTaskCost() <= 0 -> "Max \$ per task must be > 0"
            config.repeatInterval() <= 0 -> "Repeat interval must be > 0"
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

    private fun cacheKey(config: AgentConfiguration) = ReplayCache.key(config.goal(), config.startUrl(), config.secrets().keys)

    // ponytail: the SDK gives scripts no task id, so the profile is keyed by goal + start URL + secret slots (names
    // and values, hashed: another account gets its own cookies). Two tasks with the same goal, URL and secrets share a
    // profile (and can't run at the same time); key by task id if the SDK ever offers one.
    private fun profileName(config: AgentConfiguration): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${config.goal()}\n${config.startUrl().trim()}\n${config.secrets().toSortedMap().map { (name, value) -> "$name=${value.reveal()}" }}".toByteArray())
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
private const val MAX_TRANSIENT_FAILURES = 3
private const val CONFIRM_URL = "https://cereal-confirm.invalid/confirm"
private const val DECLINE_URL = "https://cereal-confirm.invalid/decline"

private fun escapeHtml(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private val SECRET_NAME = Regex("[a-z][a-z0-9_]*")

private fun stepCount(steps: Int) = if (steps == 1) "1 step" else "$steps steps"
