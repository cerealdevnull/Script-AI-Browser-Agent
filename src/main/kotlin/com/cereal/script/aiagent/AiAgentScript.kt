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
import java.util.UUID

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
        val report = Report(configuration, provider, statusUpdate)
        configError?.let { return report.error(it, cost = COST_ZERO) }
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
                Browser.launch(profilesDir.resolve(profileName(configuration, provider)), pageLoadTimeoutMs).also { this.browser = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Chrome could not be started: ${e.message}")
                return report.error("Couldn't start Google Chrome.", "Check that Chrome is installed and opens normally, then start the task again.", COST_ZERO)
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
                    log.info(scrubber.forLog("Asking to confirm: ${it.action} on ${it.element} at ${it.url}"))
                    statusUpdate(scrubber.forLog("Waiting for your confirmation: ${it.action} on ${it.element}"))
                    confirm(configuration, provider, scrubber, it)
                },
                configuration.runMode(),
                { statusUpdate(scrubber.forLog(it)) },
                configuration.provider().let { if (it == Provider.Custom) "your server" else it.name },
            )
        val repeat = configuration.runMode() == RunMode.Repeat
        val key = cacheKey(configuration)
        var replayed = false
        val cost = {
            val spent = usd(agent.cost).takeUnless { it == "\$0.00" } ?: "\$0"
            "Cost: $spent" +
                when {
                    repeat -> ""
                    agent.healed -> " (the page changed, so it re-learned part of it)"
                    replayed -> " (reused what it learned last time)"
                    else -> ""
                }
        }
        val outcome =
            try {
                // A Repeat run replays only with a Check; a Once run also with Readouts. Otherwise it learns.
                val cache = ReplayCache.parse(provider.preference().getString(key))?.takeIf { it.check != null || (!repeat && it.readouts.isNotEmpty()) }
                replayed = cache != null
                // A learned or healed run rewrites the cache (a heal keeps the steps before the miss: see Agent.replay).
                (cache?.let { agent.replay(it) } ?: agent.run()).also {
                    if (it is Outcome.Finished && (!replayed || agent.healed)) writeCache(provider, key, it, repeat, cache?.check) { m -> log.info(scrubber.forLog(m)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A thrown exception becomes a generic host error (and a report carrying the config): return Error.
                // Logged as scrubbed text, not as a Throwable: its message could carry a secret or the API key.
                log.error(scrubber.forLog("goal run failed: ${e.stackTraceToString()}"))
                return report.error("Something went wrong inside the script.", "Start the task again. If it happens again, send the task log to the developer.", cost())
            } finally {
                taskSpent += agent.cost
            }
        val verdict =
            scrubber.forLog(
                when (outcome) {
                    is Outcome.Failed -> outcome.reason
                    is Outcome.Stopped -> outcome.reason
                    is Outcome.Finished -> if (outcome.goalMet) "Goal met" else "Goal not met"
                    is Outcome.Declined -> "Declined by user"
                },
            )
        statusUpdate(verdict)
        log.info("run: ${configuration.runMode()}, ${if (agent.healed) "healed" else if (replayed) "replayed" else "learned"}, ${stepCount(agent.steps)}, ${usd(agent.cost)}, $verdict")
        // A monitor survives a bad minute: retry at the next interval, end the task after MAX_TRANSIENT_FAILURES in a row.
        transientFailures = if (outcome is Outcome.Failed && outcome.transient) transientFailures + 1 else 0
        if (repeat && transientFailures in 1 until MAX_TRANSIENT_FAILURES) {
            log.warn(scrubber.forLog("transient failure $transientFailures of $MAX_TRANSIENT_FAILURES, retrying at the next interval: $verdict"))
            return ExecutionResult.Loop(verdict, configuration.repeatInterval() * 1000L)
        }
        return when (outcome) {
            is Outcome.Failed if repeat && outcome.transient ->
                report.error(
                    scrubber.forLog("The monitor stopped: $MAX_TRANSIENT_FAILURES checks in a row couldn't reach ${outcome.unreachable}."),
                    "Start the task again when it's back.",
                    cost(),
                )
            is Outcome.Failed -> report.error(verdict, outcome.action, cost())
            is Outcome.Stopped -> report.error(verdict, outcome.action, cost())
            is Outcome.Declined -> report.error(verdict, cost = cost()).let { ExecutionResult.Error("declined by user") }
            // A monitor stays quiet until the goal is met.
            is Outcome.Finished if repeat && !outcome.goalMet -> ExecutionResult.Loop(verdict, configuration.repeatInterval() * 1000L)
            is Outcome.Finished -> {
                val readouts = outcome.readouts.map { scrubber.forLog("${it.label}: ${it.text}") }
                val summary = if (repeat || outcome.summary.isEmpty()) emptyList() else listOf(scrubber.forLog(outcome.summary).take(300))
                report.notify(verdict, details = readouts + summary, cost = cost())
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
     * heal's) or none (a learned run's); a Once run needs a Check or a Readout.
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

    /** Notifies, then asks one plain question (no timeout). Closing the dialog, or any dialog error, is a No. */
    private suspend fun confirm(
        config: AgentConfiguration,
        provider: ComponentProvider,
        scrubber: Scrubber,
        step: Confirmation,
    ): Boolean {
        notify(config, provider, scrubber.forLog("${step.question}\nConfirmation needed: answer in the window that opened. The task waits until you answer."))
        fun esc(s: String) = escapeHtml(scrubber.forLog(s))
        val does = step.text?.let { "It will send: “${esc(it.trim())}”" } ?: "It will press “${esc(step.label)}”."
        val why = step.reason?.let(::esc) ?: "The agent gave no reason. It flagged this step because the button says “${esc(step.label)}”."
        val html =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body style=\"font-family: system-ui, sans-serif; max-width: 560px; margin: 40px auto; padding: 0 16px\">" +
                "<div style=\"font-size: 13px; color: #666\">AI Browser Agent · needs your OK</div>" +
                "<h1>${esc(step.question)}</h1><p>$does</p>" +
                "<p style=\"background: #f4f5f7; padding: 12px 16px; border-radius: 8px\">$why</p>" +
                "<p style=\"display: flex; gap: 12px\"><a style=\"$BUTTON\" href=\"$DECLINE_URL\">No, stop here</a>" +
                "<a style=\"$BUTTON\" href=\"$CONFIRM_URL\">Yes, ${esc(step.verb.replaceFirstChar(Char::lowercase))}</a></p>" +
                "<p style=\"font-size: 13px; color: #666\">Closing this window, or leaving the Tasks screen, counts as No. The task waits until you answer.</p>" +
                "</body></html>"
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

    /**
     * A result notification: the headline (also the final status line), an optional action line, details (success
     * only), then the cost line. Raw causes (HTTP bodies, exception text) go to the log, not here.
     */
    private inner class Report(val config: AgentConfiguration, val provider: ComponentProvider, val statusUpdate: suspend (String) -> Unit) {
        suspend fun notify(headline: String, action: String? = null, details: List<String> = emptyList(), cost: String) {
            statusUpdate(headline)
            notify(config, provider, (listOfNotNull(headline, action) + details + cost).joinToString("\n"))
        }

        suspend fun error(headline: String, action: String? = null, cost: String): ExecutionResult {
            notify(headline, action, cost = cost)
            return ExecutionResult.Error(headline)
        }
    }

    private suspend fun notify(config: AgentConfiguration, provider: ComponentProvider, body: String) {
        val goal = config.goal()
        provider.notification().sendNotification(Notification("AI agent: " + if (goal.length > 60) goal.take(60) + "…" else goal, body))
    }

    private fun validate(config: AgentConfiguration): String? {
        val scheme = runCatching { URI(config.startUrl().trim()).scheme?.lowercase() }.getOrNull()
        return when {
            config.goal().isBlank() -> "Goal is required"
            config.startUrl().isBlank() -> "Start URL is required"
            scheme != "http" && scheme != "https" -> "Start URL must be an http(s) URL"
            config.provider() != Provider.Custom && config.apiKey()?.reveal().isNullOrBlank() -> "API key is required for ${config.provider()}"
            config.provider() == Provider.Custom && config.model().isNullOrBlank() -> "Model is required for Custom"
            config.provider() == Provider.Custom && config.baseUrl().isNullOrBlank() -> "Server URL is required for Custom"
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

    // Keyed by a task id the script stores in the task's own preferences (new task, new id: a deleted task's profile is
    // never reused) plus a hash of the secrets (another account gets its own cookies). Rewording the goal keeps sign-ins.
    // ponytail: profiles of deleted tasks stay on disk; remove the folder if Cereal ever offers a task-deletion hook.
    private suspend fun profileName(config: AgentConfiguration, provider: ComponentProvider): String {
        val prefs = provider.preference()
        val taskId = prefs.getString(TASK_ID_KEY) ?: UUID.randomUUID().toString().also { prefs.setString(TASK_ID_KEY, it) }
        val secrets = config.secrets().toSortedMap().map { (name, value) -> "$name=${value.reveal()}" }.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(secrets.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$taskId-${hash.take(16)}"
    }
}

/** A preset's own server (a leftover Server URL is ignored: the key must not go elsewhere); Custom's Server URL. */
internal fun modelClient(config: AgentConfiguration): ModelClient {
    val provider = config.provider()
    val baseUrl = provider.baseUrl ?: config.baseUrl()!!.trim()
    val model = config.model()?.trim()?.ifEmpty { null } ?: provider.recommendedModel!!
    return when (provider) {
        Provider.Anthropic -> AnthropicClient(baseUrl, config.apiKey()!!.reveal(), model)
        else -> OpenAiCompatibleClient(baseUrl, config.apiKey()?.reveal(), model)
    }
}
private const val MAX_TRANSIENT_FAILURES = 3
private const val TASK_ID_KEY = "taskId"
private const val CONFIRM_URL = "https://cereal-confirm.invalid/confirm"
private const val DECLINE_URL = "https://cereal-confirm.invalid/decline"
private const val BUTTON = "flex: 1; text-align: center; padding: 14px; border: 2px solid #c4c8cf; border-radius: 10px; font-weight: 600; text-decoration: none"

private fun escapeHtml(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private val SECRET_NAME = Regex("[a-z][a-z0-9_]*")

internal fun stepCount(steps: Int) = if (steps == 1) "1 step" else "$steps steps"

private const val COST_ZERO = "Cost: \$0"
