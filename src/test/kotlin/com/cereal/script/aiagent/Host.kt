package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.Script
import com.cereal.sdk.component.ComponentProvider
import com.cereal.sdk.component.logger.LoggerComponent
import com.cereal.sdk.component.notification.Notification
import com.cereal.sdk.component.notification.NotificationComponent
import com.cereal.sdk.models.Secret
import com.cereal.test.ComponentProviderFactory
import com.cereal.test.TestScriptRunner
import com.cereal.test.components.RecordingArtifactComponent
import com.cereal.test.components.TestComponentProvider
import com.cereal.test.components.TestComponentProviderFactory
import com.cereal.test.components.TestLicenseComponent
import com.cereal.test.components.TestPreferenceComponent
import com.cereal.test.components.TestScriptLauncherComponent
import com.cereal.test.components.TestUserInteractionComponent
import java.nio.file.Files
import java.nio.file.Path

/** Stands in for the Cereal host: the test-utils fakes, but with notifications and logs recorded. */
class Host : ComponentProviderFactory {
    val notifications = mutableListOf<Notification>()
    val logs = mutableListOf<String>()

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
            TestPreferenceComponent(),
            TestScriptLauncherComponent(TestComponentProviderFactory(), null),
            TestUserInteractionComponent(emptyList(), emptyList(), emptyList()),
            RecordingArtifactComponent(),
        )

    private fun log(level: String, message: String?) {
        synchronized(logs) { logs += "$level $message" }
        println("$level $message")
    }

    override fun create(): ComponentProvider = provider

    /** Runs the script like the host does, through [TestScriptRunner], and returns the last [ExecutionResult]. */
    suspend fun run(script: AiAgentScript, config: AgentConfiguration): ExecutionResult? {
        val recording = RecordingScript(script)
        TestScriptRunner(recording).run(config, this)
        return recording.last
    }
}

/** Remembers the last result of execute(), which [TestScriptRunner] doesn't expose. */
class RecordingScript(private val script: AiAgentScript) : Script<AgentConfiguration> by script {
    @Volatile var last: ExecutionResult? = null

    override suspend fun execute(
        configuration: AgentConfiguration,
        provider: ComponentProvider,
        statusUpdate: suspend (message: String) -> Unit,
    ): ExecutionResult = script.execute(configuration, provider, statusUpdate).also { last = it }
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
    val model: String = "claude-opus-5-5",
) : AgentConfiguration {
    override fun goal() = goal
    override fun startUrl() = startUrl
    override fun baseUrl() = baseUrl
    override fun apiKey() = apiKey
    override fun maxSteps() = maxSteps
    override fun maxRunCost() = maxRunCost
    override fun maxTaskCost() = maxTaskCost
    override fun provider() = provider
    override fun model() = model
}

fun tempProfiles(): Path = Files.createTempDirectory("ai-agent-profiles")
