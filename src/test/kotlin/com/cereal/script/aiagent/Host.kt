package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.Script
import com.cereal.sdk.component.ComponentProvider
import com.cereal.sdk.component.logger.LoggerComponent
import com.cereal.sdk.component.notification.Notification
import com.cereal.sdk.component.notification.NotificationComponent
import com.cereal.sdk.component.preference.PreferenceComponent
import com.cereal.sdk.models.Secret
import com.cereal.test.ComponentProviderFactory
import com.cereal.test.TestScriptRunner
import com.cereal.test.components.RecordingArtifactComponent
import com.cereal.test.components.TestComponentProvider
import com.cereal.test.components.TestComponentProviderFactory
import com.cereal.test.components.TestLicenseComponent
import com.cereal.test.components.TestScriptLauncherComponent
import com.cereal.sdk.component.userinteraction.UserInteractionComponent
import com.cereal.sdk.component.userinteraction.WebResourceRequest
import java.nio.file.Files
import java.nio.file.Path

/** Stands in for the Cereal host: the test-utils fakes, but with notifications and logs recorded. */
class Host : ComponentProviderFactory {
    val notifications = mutableListOf<Notification>()
    val logs = mutableListOf<String>()

    /** Every status line the script showed, in order. */
    val statuses = mutableListOf<String>()

    /** The HTML of every `showHtml` dialog, in order. */
    val dialogs = mutableListOf<String>()

    /** What the next `showHtml` calls answer: a [WebResourceRequest] the dialog navigated to, or a Throwable to throw. */
    val dialogAnswers = ArrayDeque<Any>()

    private val provider =
        TestComponentProvider(
            object : LoggerComponent {
                override fun error(message: String?, throwable: Throwable?, vararg args: Any?) = log("ERROR", message)
                override fun warn(message: String?, vararg args: Any?) = log("WARN", message)
                override fun info(message: String?, vararg args: Any?) = log("INFO", message)
                override fun debug(message: String?, vararg args: Any?) = log("DEBUG", message)
            },
            object : NotificationComponent {
                override suspend fun sendNotification(notification: Notification) {
                    notifications += notification
                }
            },
            TestLicenseComponent(),
            TaskPreferences(),
            TestScriptLauncherComponent(TestComponentProviderFactory(), null),
            object : UserInteractionComponent {
                override suspend fun showHtml(title: String, html: String, shouldFinish: (WebResourceRequest) -> Boolean): WebResourceRequest {
                    dialogs += html
                    val answer = dialogAnswers.removeFirstOrNull() ?: error("Host: no answer left for dialog ${dialogs.size}")
                    if (answer is Throwable) throw answer
                    return (answer as WebResourceRequest).also { check(shouldFinish(it)) { "shouldFinish rejected ${it.url}" } }
                }

                override suspend fun showUrl(title: String, url: String, headers: Map<String, String>, shouldFinish: (WebResourceRequest) -> Boolean) =
                    error("showUrl is not used")

                override suspend fun requestInput(title: String, description: String) = error("requestInput is not used")

                override suspend fun showContinueButton() = error("showContinueButton is not used")
            },
            RecordingArtifactComponent(),
        )

    private fun log(level: String, message: String?) {
        synchronized(logs) { logs += "$level $message" }
        println("$level $message")
    }

    override fun create(): ComponentProvider = provider

    /** Runs the script like the host does, through [TestScriptRunner], and returns the last [ExecutionResult]. */
    suspend fun run(script: AiAgentScript, config: AgentConfiguration): ExecutionResult? {
        val recording = RecordingScript(script, statuses)
        TestScriptRunner(recording).run(config, this)
        return recording.last
    }
}

/** One task's preferences. TestPreferenceComponent shares one static map across every instance, i.e. across tasks. */
class TaskPreferences : PreferenceComponent {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override suspend fun delete(key: String) {
        values.remove(key)
    }

    override suspend fun getString(key: String) = values[key] as? String
    override suspend fun setString(key: String, value: String) { values[key] = value }
    override suspend fun getInt(key: String) = values[key] as? Int
    override suspend fun setInt(key: String, value: Int) { values[key] = value }
    override suspend fun getLong(key: String) = values[key] as? Long
    override suspend fun setLong(key: String, value: Long) { values[key] = value }
    override suspend fun getFloat(key: String) = values[key] as? Float
    override suspend fun setFloat(key: String, value: Float) { values[key] = value }
    override suspend fun getBoolean(key: String) = values[key] as? Boolean
    override suspend fun setBoolean(key: String, value: Boolean) { values[key] = value }
}

/** Remembers the last result of execute(), which [TestScriptRunner] doesn't expose. */
class RecordingScript(private val script: AiAgentScript, private val statuses: MutableList<String> = mutableListOf()) : Script<AgentConfiguration> by script {
    @Volatile var last: ExecutionResult? = null

    override suspend fun execute(
        configuration: AgentConfiguration,
        provider: ComponentProvider,
        statusUpdate: suspend (message: String) -> Unit,
    ): ExecutionResult = script.execute(configuration, provider) { statuses += it; statusUpdate(it) }.also { last = it }
}

class TestConfig(
    val goal: String = "Tell me whether Long Nose Pliers is in stock",
    val startUrl: String = "http://127.0.0.1:1/",
    val apiKey: Secret? = Secret("test-key"),
    val baseUrl: String? = null,
    val maxSteps: Int = 40,
    val maxRunCost: Double = 3.0,
    val maxTaskCost: Double = 10.0,
    val provider: Provider = Provider.Anthropic,
    val model: String? = "claude-opus-5-5",
    /** Secret slots 1..3 as (name, value). */
    val secrets: List<Pair<String?, Secret?>> = emptyList(),
    val allowedDomains: List<String> = emptyList(),
    val runMode: RunMode = RunMode.Once,
    val repeatInterval: Int = 300,
    val onNextStart: OnNextStart = OnNextStart.Keep,
) : AgentConfiguration {
    override fun runMode() = runMode
    override fun repeatInterval() = repeatInterval
    override fun onNextStart() = onNextStart
    override fun goal() = goal
    override fun startUrl() = startUrl
    override fun baseUrl() = baseUrl
    override fun apiKey() = apiKey
    override fun maxSteps() = maxSteps
    override fun maxRunCost() = maxRunCost
    override fun maxTaskCost() = maxTaskCost
    override fun provider() = provider
    override fun model() = model
    override fun secret1Name() = secrets.getOrNull(0)?.first
    override fun secret1Value() = secrets.getOrNull(0)?.second
    override fun secret2Name() = secrets.getOrNull(1)?.first
    override fun secret2Value() = secrets.getOrNull(1)?.second
    override fun secret3Name() = secrets.getOrNull(2)?.first
    override fun secret3Value() = secrets.getOrNull(2)?.second
    override fun allowedDomains() =
        allowedDomains.map { d -> object : AgentConfiguration.AllowedDomain { override fun domain() = d } }.ifEmpty { null }
}

/** What the dialog's Confirm / Decline links navigate to. */
val confirmClicked = WebResourceRequest("GET", emptyMap(), "https://cereal-confirm.invalid/confirm", null)
val declineClicked = WebResourceRequest("GET", emptyMap(), "https://cereal-confirm.invalid/decline", null)

fun tempProfiles(): Path = Files.createTempDirectory("ai-agent-profiles")
