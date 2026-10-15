package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.Script
import com.cereal.sdk.TaskAction
import com.cereal.sdk.component.ComponentProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The script the host runs. The host builds it with kotlin-reflect, `constructors.first().call()`, which fails on a
 * constructor with parameters even when they all have defaults. So: a single no-arg constructor; the seams live in [AgentScript].
 */
class AiAgentScript : Script<AgentConfiguration> by AgentScript()

internal class AgentScript(
    private val newModelClient: (AgentConfiguration) -> ModelClient = ::modelClient,
    private val profilesDir: Path = Path.of(System.getProperty("user.home"), ".cereal", "ai-browser-agent", "profiles"),
    private val pageLoadTimeoutMs: Long = 60_000,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val launchBrowser: suspend (Path, Long) -> Browser = Browser::launch,
) : Script<AgentConfiguration> {
    private var configError: String? = null
    private var browser: Browser? = null

    // The task cap counts every run of this task; held in memory only, so it resets when the task restarts.
    private var taskSpent = 0.0

    // Repeat: runs (checks) since the task started, in memory like taskSpent.
    private var checks = 0

    // Repeat: the day the task started, for the goal-met cost line; and whether the last check healed (one heal notice per streak).
    private var startedOn = LocalDate.now(clock)
    private var healing = false

    // Models without a known price used this task start (the task total is then partly unknown).
    private var taskUnpriced = setOf<String?>()

    // One per task: values revealed in an earlier run (e.g. a replayed login) stay scrubbed in later Loop iterations.
    private var scrubber: Scrubber? = null

    // Repeat: consecutive iterations that failed transiently (page load, network, model 429/5xx after retries).
    private var transientFailures = 0

    // Repeat: the 80% Max $ per task warning was sent this task start.
    private var capWarned = false

    // The pre-flight key and model check passed this task start.
    private var checked = false

    // The no-price line is shown once per task, before its first step.
    private var priceAnnounced = false

    // True until the first execute() after a task start.
    private var firstRun = true

    // onStart can only answer true/false (false shows a generic host error), so the first execute() reports it.
    override suspend fun onStart(configuration: AgentConfiguration, provider: ComponentProvider): Boolean =
        logged(provider) { start(configuration, provider) }

    override suspend fun execute(
        configuration: AgentConfiguration,
        provider: ComponentProvider,
        statusUpdate: suspend (message: String) -> Unit,
    ): ExecutionResult = logged(provider) { run(configuration, provider, statusUpdate) }

    // The host shows an unexpected exception as "The script failed without error message." and keeps the trace to
    // itself: log it, and print it to stderr too (the mock flavor's logger is a no-op; a release keeps Logs/stderr.log).
    private suspend fun <T> logged(provider: ComponentProvider, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val scrub = { text: String -> scrubber?.forLog(text) ?: text }
            System.err.println("AI Browser Agent: " + scrub(e.stackTraceToString()))
            provider.logger().error(scrub(e.toString()))
            throw e
        }

    private suspend fun start(configuration: AgentConfiguration, provider: ComponentProvider): Boolean {
        configError = validate(configuration)
        taskSpent = 0.0
        checks = 0
        startedOn = LocalDate.now(clock)
        healing = false
        taskUnpriced = emptySet()
        scrubber = null
        transientFailures = 0
        checked = false
        capWarned = false
        priceAnnounced = false
        firstRun = true
        return true
    }

    private suspend fun run(
        configuration: AgentConfiguration,
        provider: ComponentProvider,
        statusUpdate: suspend (message: String) -> Unit,
    ): ExecutionResult {
        val report = Report(configuration, provider, statusUpdate)
        configError?.let { return report.error(it, cost = COST_ZERO) }
        val scrubber = scrubber ?: Scrubber(configuration.apiKey()?.reveal()).also { scrubber = it }
        val log = provider.logger()
        val model = newModelClient(configuration)
        if (!checked) preflight(configuration, model, report, statusUpdate) { log.warn(scrubber.forLog(it)) }?.let { return it }
        val browser =
            browser?.takeIf { it.alive } ?: try {
                this.browser?.let { dead ->
                    log.warn("Chrome was closed or crashed: starting it again")
                    this.browser = null
                    dead.close()
                }
                statusUpdate("Starting Chrome")
                launchBrowser(profilesDir.resolve(profileName(configuration, provider)), pageLoadTimeoutMs).also { this.browser = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Chrome could not be started: ${e.message}")
                return report.error("Couldn't start Google Chrome.", "Check that Chrome is installed and opens normally, then start the task again.", COST_ZERO)
            }
        browser.log = { log.info(scrubber.forLog(it)) }
        val modelId = configuration.modelId()
        val modelPriced = Prices.cost(modelId, Usage(0, 0)) != null
        if (!priceAnnounced && !modelPriced) {
            val line = "No price known for $modelId: the \$ caps can't stop this task, only Max steps can."
            log.warn(line)
            statusUpdate(line)
        }
        priceAnnounced = true
        val caps = Caps(configuration.maxSteps(), configuration.maxRunCost(), configuration.maxTaskCost(), taskSpent)
        val agent =
            Agent(
                browser,
                // A key revoked mid-run gets the pre-flight's wording.
                object : ModelClient {
                    override suspend fun send(request: ModelRequest) =
                        try {
                            model.send(request)
                        } catch (e: ModelException) {
                            if (e.status != 401 && e.status != 403) throw e
                            log.error(scrubber.forLog(e.message.orEmpty()))
                            throw ModelException(keyRejected(configuration), status = e.status)
                        }
                },
                configuration.goal(),
                configuration.startPage(),
                caps,
                { log.info(scrubber.forLog(it)) },
                { log.warn(scrubber.forLog(it)) },
                configuration.secretsByName(),
                scrubber,
                configuration.allowedDomainSet(),
                {
                    log.info(scrubber.forLog("Asking to confirm: ${it.action} on ${it.element} at ${it.url}"))
                    statusUpdate(scrubber.forLog("Waiting for your OK: ${it.question}"))
                    confirm(configuration, provider, scrubber, it)
                },
                configuration.runMode(),
                { statusUpdate(scrubber.forLog(it)) },
                configuration.provider().let { if (it == Provider.Custom) "your server" else it.name },
                modelPriced,
            )
        val repeat = configuration.runMode() == RunMode.Repeat
        val key = cacheKey(configuration)
        var replayed = false
        var cache: ReplayCache? = null
        // A replay that ends not met where the learned run met it: the learned steps likely no longer fit.
        var learnedMet = false
        val cost = {
            "Cost: ${spent(agent.cost, agent.unpricedModels)}" +
                when {
                    repeat -> ""
                    agent.healed -> " (the page changed, so it re-learned part of it)"
                    replayed -> " (reused what it learned last time)"
                    else -> ""
                }
        }
        val ran =
            try {
                // A Repeat run replays only with a Check; a Once run also with Readouts. Otherwise it learns.
                cache = ReplayCache.parse(provider.preference().getString(key))?.takeIf { it.check != null || (!repeat && it.readouts.isNotEmpty()) }
                replayed = cache != null
                learnedMet = cache?.met == true
                // A learned or healed run rewrites the cache (a heal keeps the steps before the miss: see Agent.replay).
                (cache?.let { agent.replay(it) } ?: agent.run()).also {
                    // A met change Check also rewrites it on a plain replay: the new value is the next baseline.
                    if (it is Outcome.Finished && (!replayed || agent.healed || it.changed)) writeCache(provider, key, it, repeat, cache?.check?.takeUnless { agent.checkMissed }) { m -> log.info(scrubber.forLog(m)) }
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
                taskUnpriced += agent.unpricedModels
                checks++
            }
        // Repeat: "Goal met" only ever comes from a verified Check; an unverified one is not met and loops quietly.
        val unverifiedMet = repeat && ran is Outcome.Finished && ran.goalMet && !ran.verified
        if (unverifiedMet) log.warn(scrubber.forLog("goal_met true was not verified by a Check: treated as not met"))
        val outcome = if (unverifiedMet) Outcome.Finished(false, ran.summary, ran.steps, ran.readouts, verified = false) else ran
        // The current tab's URL at the end of the run, unless it held a secret; a dead Chrome gets no link.
        val url =
            withTimeoutOrNull(5_000) {
                try {
                    browser.url()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        report.url = pageLink(url, scrubber)
        // Once: the summary's first sentence is the headline, the rest leads the details. A replay has no summary.
        // ponytail: splits at the first ". " so "e.g. " cuts early; ask the model for a separate answer field if that bites.
        val answer =
            if (outcome !is Outcome.Finished || repeat) emptyList() else scrubber.forLog(outcome.summary).take(300).trim().split(Regex("(?<=[.!?])\\s+"), limit = 2)
        val verdict =
            scrubber.forLog(
                when (outcome) {
                    is Outcome.Failed -> outcome.reason
                    is Outcome.Stopped -> outcome.reason
                    is Outcome.Finished if outcome.changed -> if (outcome.check!!.condition == "decreased") "Price dropped" else "Changed"
                    is Outcome.Finished -> answer.firstOrNull()?.ifEmpty { null } ?: if (outcome.goalMet) "Goal met" else "Goal not met"
                    is Outcome.Declined -> outcome.step.declined
                },
            )
        // Between checks the task row tells when it checked and when it checks next; the times keep each line unique.
        val notYet =
            if (repeat && outcome is Outcome.Finished && !outcome.goalMet) {
                "Not yet · checked ${hhmm(0)} · next ${hhmm(configuration.repeatInterval())} · ${spent(taskSpent, taskUnpriced).removeSuffix(".00")} of ${usd(configuration.maxTaskCost()).removeSuffix(".00")} total"
            } else {
                null
            }
        statusUpdate(notYet ?: verdict)
        // Once, when the task total first passes 80% of the task cap (a run stopped by the cap says so itself).
        val maxTask = configuration.maxTaskCost()
        if (repeat && !capWarned && taskSpent >= 0.8 * maxTask && taskSpent < maxTask) {
            capWarned = true
            notify(configuration, provider, ResultMessage(Kind.Watching, "This monitor has spent ${usd(taskSpent)} of its ${usd(maxTask)} limit.", "Raise Max \$ per task to keep it running past ${usd(maxTask)}."))
        }
        log.info(
            "run: ${configuration.runMode()}, ${if (agent.healed) "healed" else if (replayed) "replayed" else "learned"}, ${stepCount(agent.steps)}, ${usd(agent.cost)}, $verdict" +
                if (repeat) ", check $checks" else "",
        )
        // A Repeat run that learned or healed no Check can't be re-checked for free: stop rather than pay on every check.
        // A heal is a failed heal: the learned steps no longer fit; a learned run asks for a reworded goal.
        if (repeat && outcome is Outcome.Finished && !outcome.goalMet && outcome.check == null && (!replayed || agent.healed) && !unverifiedMet) {
            return report.error(NOTHING_TO_WATCH, if (agent.healed) FORGET_STEPS else REWORD, cost = cost())
        }
        // A monitor survives a bad minute: retry at the next interval, end the task after MAX_TRANSIENT_FAILURES in a row.
        // A start page that won't load on the first run is most likely a mistyped Start URL: report it at once.
        val badStartUrl = firstRun && outcome is Outcome.Failed && outcome.startPage
        firstRun = false
        transientFailures = if (outcome is Outcome.Failed && outcome.transient) transientFailures + 1 else 0
        if (repeat && !badStartUrl && transientFailures in 1 until MAX_TRANSIENT_FAILURES) {
            log.warn(scrubber.forLog("transient failure $transientFailures of $MAX_TRANSIENT_FAILURES, retrying at the next interval: $verdict"))
            return retry(configuration, scrubber.forLog((outcome as Outcome.Failed).unreachable!!), statusUpdate)
        }
        val total = { "Cost: ${spent(taskSpent, taskUnpriced)} over ${if (checks == 1) "1 check" else "$checks checks"} since ${startedOn.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))}" }
        return when (outcome) {
            is Outcome.Failed if repeat && outcome.transient && !badStartUrl ->
                report.error(
                    scrubber.forLog("The monitor stopped: $MAX_TRANSIENT_FAILURES checks in a row couldn't reach ${outcome.unreachable}."),
                    "Start the task again when it's back.",
                    cost(),
                )
            is Outcome.Failed -> report.error(verdict, outcome.action, cost())
            is Outcome.Stopped -> report.error(verdict, outcome.action, cost())
            // A No is a normal finish, not an Error; it ends a monitor too.
            is Outcome.Declined -> report.notify(Kind.NotMet, verdict, cost = cost()).let { ExecutionResult.Success(verdict) }
            // A monitor notifies only on change: a learned run that saved a condition, the first heal in a streak, goal met.
            is Outcome.Finished if repeat && !outcome.goalMet -> {
                val watching = outcome.check
                when {
                    watching == null -> {}
                    !replayed ->
                        report.notify(
                            Kind.Watching,
                            scrubber.forLog("Watching: ${watching.words()}. I'll check every ${every(configuration.repeatInterval())} and notify you when it does. Later checks are free."),
                            cost = cost(),
                            status = false,
                        )
                    !healing -> report.notify(Kind.Watching, "The page changed, so I re-learned it for ${spent(agent.cost, agent.unpricedModels)}. Still watching.", status = false)
                }
                healing = agent.healed
                ExecutionResult.Loop(notYet!!, configuration.repeatInterval() * 1000L)
            }
            // A change: tell old → new and keep watching from the new baseline.
            is Outcome.Finished if outcome.changed -> {
                val move = "${cut(cache?.check?.expected.orEmpty())} → ${cut(outcome.check!!.expected.orEmpty())}"
                val readouts = outcome.readouts.map { scrubber.forLog(it.label) to scrubber.forLog(it.text) }
                report.notify(Kind.Change, verdict, summary = scrubber.forLog(move), readouts = readouts, cost = total())
                healing = agent.healed
                ExecutionResult.Loop(verdict, configuration.repeatInterval() * 1000L)
            }
            is Outcome.Finished -> {
                val readouts = outcome.readouts.map { scrubber.forLog(it.label) to scrubber.forLog(it.text) }
                val met = (outcome.check ?: cache?.check)?.takeIf { repeat }?.let { scrubber.forLog("Now holds: ${it.words()}") }
                val stale = replayed && !outcome.goalMet && learnedMet
                report.notify(
                    if (outcome.goalMet) Kind.Met else Kind.NotMet,
                    met ?: verdict,
                    FORGET_STEPS.takeIf { stale },
                    answer.getOrNull(1),
                    readouts,
                    cost = if (repeat) total() else cost(),
                )
                // A Once run that answers "not met" still completed its job.
                ExecutionResult.Success(met ?: verdict)
            }
        }
    }

    /** A temporary failure 1-2 of 3 shows only on the task row: `Couldn't reach <host> (1 of 3) · retrying 14:47`. */
    private suspend fun retry(config: AgentConfiguration, host: String, statusUpdate: suspend (String) -> Unit): ExecutionResult {
        val line = "Couldn't reach $host ($transientFailures of $MAX_TRANSIENT_FAILURES) · retrying ${hhmm(config.repeatInterval())}"
        statusUpdate(line)
        return ExecutionResult.Loop(line, config.repeatInterval() * 1000L)
    }

    private fun hhmm(inSeconds: Int) = LocalTime.now(clock).plusSeconds(inSeconds.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))

    // The host calls this from `finally`, also when the task is cancelled: stop Chrome even in a cancelled coroutine.
    override suspend fun onFinish(configuration: AgentConfiguration, provider: ComponentProvider) {
        val browser = browser ?: return
        this.browser = null
        withContext(NonCancellable) { browser.close() }
    }

    /**
     * Once per task start, before Chrome: a free lookup of the key and model. Returns the result that ends this run, or
     * null to go on. A transient failure fails a Once run; Repeat retries it under the three-in-a-row rule.
     */
    private suspend fun preflight(
        config: AgentConfiguration,
        model: ModelClient,
        report: Report,
        statusUpdate: suspend (String) -> Unit,
        warn: (String) -> Unit,
    ): ExecutionResult? {
        statusUpdate("Checking your API key and model")
        val who = who(config)
        val id = config.modelId()
        val clear = if (config.provider() == Provider.Custom) "Pick another Model" else "Clear Model to use the recommended one"
        val problem =
            try {
                when (model.check()) {
                    Preflight.BadKey -> keyRejected(config)
                    Preflight.UnknownModel -> "${who.replaceFirstChar(Char::uppercase)} has no model called `$id`. $clear, or check the spelling."
                    Preflight.NoTools -> "`$id` can't control the browser here. $clear."
                    null -> null.also { checked = true }
                }
            } catch (e: ModelException) {
                warn("pre-flight check failed: ${e.message}")
                val headline = "Couldn't reach $who (${e.status?.let { "HTTP $it" } ?: "network error"}). Try again later."
                if (config.runMode() == RunMode.Repeat && ++transientFailures < MAX_TRANSIENT_FAILURES) return retry(config, who, statusUpdate)
                headline
            }
        return problem?.let { report.error(it, cost = COST_ZERO) }
    }

    /**
     * Stores what a finished learned or healed run can replay. Not when the Check or Readouts failed validation; a run
     * that found the goal already met can't show its Check tells met from not met, so it keeps the [cached] Check (a
     * heal's, unless that Check just missed) or none (a learned run's); a Once run needs a Check or a Readout.
     */
    private suspend fun writeCache(provider: ComponentProvider, key: String, run: Outcome.Finished, repeat: Boolean, cached: Check?, log: (String) -> Unit) {
        val check = if (run.goalMet && !run.changed) cached else run.check
        val skipped =
            when {
                !run.verified -> "the Check or Readouts failed validation"
                !repeat && check == null && run.readouts.isEmpty() -> "a Once run without a Check or Readout"
                else -> null
            }
        if (skipped != null) return log("replay cache not written: $skipped")
        val readouts = run.readouts.map { CachedReadout(it.label, it.fingerprint.json) }
        provider.preference().setString(key, ReplayCache(run.recorded, check, readouts, run.goalMet).toJson())
    }

    /** Notifies, then asks one plain question (no timeout). Closing the dialog, or any dialog error, is a No. */
    private suspend fun confirm(
        config: AgentConfiguration,
        provider: ComponentProvider,
        scrubber: Scrubber,
        step: Confirmation,
    ): Boolean {
        val action = "Confirmation needed: answer in the window that opened. The task waits until you answer."
        notify(config, provider, ResultMessage(Kind.Confirmation, scrubber.forLog(step.question), action, url = pageLink(step.url, scrubber)))
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
     * A result notification: the headline (also the final status line unless [status] is false), an optional action
     * line, summary, Readouts and notes (success only), then the cost line, with the run's page link. Raw causes (HTTP
     * bodies, exception text) go to the log, not here.
     */
    private inner class Report(val config: AgentConfiguration, val provider: ComponentProvider, val statusUpdate: suspend (String) -> Unit) {
        var url: String? = null

        suspend fun notify(
            kind: Kind,
            headline: String,
            action: String? = null,
            summary: String? = null,
            readouts: List<Pair<String, String>> = emptyList(),
            notes: List<String> = emptyList(),
            cost: String? = null,
            status: Boolean = true,
        ) {
            if (status) statusUpdate(headline)
            notify(config, provider, ResultMessage(kind, headline, action, summary, readouts, notes, cost, url))
        }

        suspend fun error(headline: String, action: String? = null, cost: String): ExecutionResult {
            notify(Kind.Failed, headline, action, cost = cost)
            return ExecutionResult.Error(headline)
        }
    }

    private suspend fun notify(config: AgentConfiguration, provider: ComponentProvider, message: ResultMessage) {
        val goal = runCatching { config.goal() }.getOrDefault("") // unset on the host: see validate
        provider.notification().sendNotification(message.notification(goal))
    }

    // Every error at once, in the words shown at Save. ponytail: upgrade to a host onStart message if Cereal ships one (Ask 8).
    private fun validate(config: AgentConfiguration): String? {
        val errors =
            listOfNotNull(
                // The host throws for a required item with no value, and a throw from onStart reaches the user without its message.
                goalError(runCatching { config.goal() }.getOrNull()),
                startUrlError(runCatching { config.startUrl() }.getOrNull()),
                apiKeyError(config.provider(), config.apiKey()?.reveal()),
                modelError(config.provider(), config.model()),
                serverUrlError(config.provider(), config.baseUrl()),
                positiveError("Max steps per run", config.maxSteps()),
                positiveError("Max \$ per run", config.maxRunCost()),
                positiveError("Max \$ per task", config.maxTaskCost()),
                positiveError("Check every", config.repeatInterval()).takeIf { config.runMode() == RunMode.Repeat },
            ) + secretRowErrors(config.secretRows())
        if (errors.isEmpty()) return null
        return "Fix these in the task settings: " + errors.joinToString(" ") { it.removeSuffix(".") + "." }
    }

    // Resets, from the task's menu while it's stopped (the host never runs them during a run).
    override fun taskActions(): List<TaskAction<AgentConfiguration>> =
        listOf(
            reset("Forget learned steps", "Forgot learned steps", "The next start learns the steps again, which costs model calls.") { config, provider ->
                provider.preference().delete(cacheKey(config))
            },
            reset(
                "Forget learned steps and sign out",
                "Forgot learned steps and sign-ins",
                "The next start learns the steps again, which costs model calls, and you must sign in to the site again.",
            ) { config, provider ->
                val prefs = provider.preference()
                prefs.delete(cacheKey(config))
                val taskId = prefs.getString(TASK_ID_KEY)
                // Every Chrome profile of this task (one per set of secrets).
                if (taskId != null) profilesDir.toFile().listFiles { f -> f.name.startsWith("$taskId-") }?.forEach { it.deleteRecursively() }
            },
        )

    private fun reset(name: String, done: String, confirm: String, block: suspend (AgentConfiguration, ComponentProvider) -> Unit) =
        object : TaskAction<AgentConfiguration> {
            override val name = name
            override val confirm = confirm

            override suspend fun run(configuration: AgentConfiguration, provider: ComponentProvider): String = done.also { block(configuration, provider) }
        }

    private fun cacheKey(config: AgentConfiguration) = ReplayCache.key(config.goal(), config.startUrl(), config.secretsByName().keys)

    // Keyed by a task id the script stores in the task's own preferences (new task, new id: a deleted task's profile is
    // never reused) plus a hash of the secrets (another account gets its own cookies). Rewording the goal keeps sign-ins.
    // ponytail: profiles of deleted tasks stay on disk; remove the folder if Cereal ever offers a task-deletion hook.
    private suspend fun profileName(config: AgentConfiguration, provider: ComponentProvider): String {
        val prefs = provider.preference()
        val taskId = prefs.getString(TASK_ID_KEY) ?: UUID.randomUUID().toString().also { prefs.setString(TASK_ID_KEY, it) }
        val secrets = config.secretsByName().toSortedMap().map { (name, value) -> "$name=${value.reveal()}" }.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(secrets.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$taskId-${hash.take(16)}"
    }
}

/** A preset's own server (a leftover Server URL is ignored: the key must not go elsewhere); Custom's Server URL. */
internal fun modelClient(config: AgentConfiguration): ModelClient {
    val provider = config.provider()
    val baseUrl = provider.baseUrl ?: config.baseUrl()!!.trim()
    return when (provider) {
        Provider.Anthropic -> AnthropicClient(baseUrl, config.apiKey()!!.reveal(), config.modelId())
        else -> OpenAiCompatibleClient(baseUrl, config.apiKey()?.reveal(), config.modelId(), custom = provider == Provider.Custom)
    }
}

private fun AgentConfiguration.modelId() = model()?.trim()?.ifEmpty { null } ?: provider().recommendedModel!!

private fun who(config: AgentConfiguration) = if (config.provider() == Provider.Custom) "your server" else config.provider().name

private fun keyRejected(config: AgentConfiguration): String {
    val from = config.provider().keyUrl?.let { "from $it " }.orEmpty()
    return "${who(config).replaceFirstChar(Char::uppercase)} rejected your API key. Copy it again ${from}into API key."
}
private const val MAX_TRANSIENT_FAILURES = 3
private const val NOTHING_TO_WATCH = "Couldn't find something on the page to watch."
private const val REWORD = "Reword the goal as a condition, e.g. notify me when … is in stock, then start the task again."
internal const val FORGET_STEPS = "Pick Forget learned steps in the task's menu, then start the task again."
internal const val FORGET_AND_SIGN_OUT = "Pick Forget learned steps and sign out in the task's menu, then start the task again."
private const val TASK_ID_KEY = "taskId"
private const val CONFIRM_URL = "https://cereal-confirm.invalid/confirm"
private const val DECLINE_URL = "https://cereal-confirm.invalid/decline"
private const val BUTTON = "flex: 1; text-align: center; padding: 14px; border: 2px solid #c4c8cf; border-radius: 10px; font-weight: 600; text-decoration: none"

private fun escapeHtml(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

internal fun stepCount(steps: Int) = if (steps == 1) "1 step" else "$steps steps"

private const val COST_ZERO = "Cost: \$0"

/** One side of a change's `old → new`: at most 100 characters. */
private fun cut(s: String) = if (s.length > 100) s.take(99) + "…" else s

/** A cost amount: `$0.21`, `$0`, `unknown (no price for x)` or `$0.12 + unknown`. */
private fun spent(amount: Double, unpriced: Set<String?>) =
    when {
        unpriced.isEmpty() -> usd(amount).takeUnless { it == "\$0.00" } ?: "\$0"
        amount == 0.0 -> "unknown (no price for ${unpriced.map { it ?: "unknown model" }.joinToString()})"
        else -> "${usd(amount)} + unknown"
    }

/** A Repeat interval in words: `7 seconds`, `15 minutes`, `hour`. */
private fun every(seconds: Int): String {
    val (n, unit) = if (seconds % 3600 == 0) seconds / 3600 to "hour" else if (seconds % 60 == 0) seconds / 60 to "minute" else seconds to "second"
    return if (n == 1) unit else "$n ${unit}s"
}
