package com.cereal.script.aiagent

import com.cereal.test.TestScriptRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CancellationTest {
    private val site = FixtureServer()

    @AfterTest
    fun tearDown() = site.close()

    @Test
    fun `stopping the task closes Chrome and does not swallow the cancellation`() =
        runBlocking {
            val host = Host()
            val profiles = tempProfiles()
            val modelCalled = CompletableDeferred<Unit>()
            val model =
                ScriptedModel({
                    modelCalled.complete(Unit)
                    awaitCancellation()
                })
            val script = AgentScript(profiles).also { host.llm.responder = model::respond }
            val recording = RecordingScript(script)
            val config = TestConfig(startUrl = site.url())

            val task =
                launch(Dispatchers.Default) {
                    // Like the host: onFinish runs from `finally`, inside the cancelled coroutine.
                    try {
                        TestScriptRunner(recording).run(config, host)
                    } finally {
                        script.onFinish(config, host.create())
                    }
                }
            modelCalled.await()
            assertTrue(chromeRunning(profiles), "Chrome runs while the model is thinking")

            task.cancelAndJoin()

            assertNull(recording.last, "execute() rethrew the cancellation instead of returning a result")
            assertTrue(task.isCancelled)
            assertTrue(eventually { !chromeRunning(profiles) }, "Chrome is closed")
        }

    private fun chromeRunning(profiles: Path) =
        ProcessHandle.allProcesses().anyMatch { p -> p.info().commandLine().orElse("").contains(profiles.toString()) }

    private suspend fun eventually(check: () -> Boolean): Boolean {
        repeat(50) {
            if (check()) return true
            delay(100)
        }
        return check()
    }
}
