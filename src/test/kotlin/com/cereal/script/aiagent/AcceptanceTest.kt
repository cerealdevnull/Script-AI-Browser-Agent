package com.cereal.script.aiagent

import com.cereal.sdk.ExecutionResult
import com.cereal.sdk.models.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Path
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** One task start of the real script (the test-only model client via [ModelTraffic], real Chrome): onStart, goal runs, onFinish. */
private class AcceptanceTask(val goalName: String, val config: TestConfig, val host: Host, val traffic: ModelTraffic, profiles: Path = tempProfiles()) {
    private val script = AgentScript(profiles)
    private val provider = host.create().also { host.model = OpenAiCompatibleClient(traffic.baseUrl, config.apiKey!!.reveal(), config.model!!) }

    /** One goal run (one execute), reported as a results row. */
    suspend fun run(label: String): RunStats {
        val mark = traffic.mark()
        val dialogs = host.dialogs.size
        val result = script.execute(config, provider) {}
        return RunStats(goalName, label, traffic.since(mark), result, host.dialogs.size - dialogs).report()
    }

    fun use(block: suspend AcceptanceTask.() -> Unit) =
        runBlocking {
            assertTrue(script.onStart(config, provider), "onStart rejected the config")
            try {
                block()
            } finally {
                script.onFinish(config, provider)
            }
        }
}

private fun money(total: Double) = String.format(Locale.ROOT, "%.2f", total)

/** Public demo Toolshop with the published customer account in Secret slots `email` / `password`. */
private object Demo {
    val url = env("TOOLSHOP_DEMO_URL", "https://practicesoftwaretesting.com")!!
    val api = ToolshopApi(env("TOOLSHOP_DEMO_API", "https://api.practicesoftwaretesting.com")!!)
    val email = env("TOOLSHOP_EMAIL", "customer@practicesoftwaretesting.com")!!
    val password = env("TOOLSHOP_PASSWORD", "welcome01")!!
    val secrets = listOf<Pair<String?, Secret?>>("email" to Secret(email), "password" to Secret(password))
}

/**
 * G1 monitor on a self-hosted Toolshop (docker/toolshop.yml), so the test can flip the stock of Long Nose Pliers:
 * learn once (Loop, silent), replay with 0 model calls, then one notification once it's back in stock.
 */
@Tag("acceptance")
class G1MonitorTest {
    private val ui = env("TOOLSHOP_LOCAL_URL", "http://localhost:4200")!!
    private val api = env("TOOLSHOP_LOCAL_API", "http://localhost:8091")!!
    private val host = Host()
    private val traffic = ModelTraffic()
    private lateinit var key: String

    @BeforeTest
    fun setUp() {
        val apiKey = anthropicKeyOrSkip()
        assumeTrue(reachable(ui) && reachable("$api/products"), "self-hosted Toolshop not reachable at $ui / $api: see docs/acceptance.md")
        key = apiKey
    }

    @AfterTest
    fun tearDown() {
        traffic.close()
        if (::key.isInitialized) setStock(0)
    }

    /** Sets `stock` (the API's `in_stock` is `stock > 0`) and drops the API's 5-minute product cache. */
    private fun setStock(stock: Int) {
        docker("exec", "-T", "mariadb", "mysql", "-uroot", "-proot", "toolshop", "-e", "UPDATE products SET stock = $stock WHERE name = 'Long Nose Pliers'")
        docker("exec", "-T", "laravel-api", "php", "artisan", "cache:clear")
    }

    private fun docker(vararg args: String) {
        val process = ProcessBuilder("docker", "compose", "-f", File("docker/toolshop.yml").absolutePath, *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "docker compose ${args.joinToString(" ")} failed:\n$output")
    }

    @Test
    fun `G1 learns once, replays with zero model calls, then notifies once when Long Nose Pliers is back in stock`() {
        setStock(0)
        val replays = env("ACCEPTANCE_G1_REPLAYS", "3")!!.toInt()
        val config =
            TestConfig(
                goal = "Notify me when Long Nose Pliers is back in stock",
                startUrl = ui,
                apiKey = Secret(key),
                model = ACCEPTANCE_MODEL,
                runMode = RunMode.Repeat,
                repeatInterval = 60,
            )

        AcceptanceTask("G1", config, host, traffic).use {
            val learned = run("run 1 (learn, out of stock)")
            assertIs<ExecutionResult.Loop>(learned.result, learned.result.message)
            assertTrue(host.notifications.isEmpty(), "run 1 notified: ${host.notifications.map { it.message }}")
            learned.assertCacheReadsOnLaterSteps()

            for (i in 2..replays + 1) {
                val replay = run("run $i (replay, out of stock)")
                assertIs<ExecutionResult.Loop>(replay.result, replay.result.message)
                assertEquals(0, replay.steps, "run $i called the model")
            }
            assertTrue(host.notifications.isEmpty(), "a not-yet run notified: ${host.notifications.map { it.message }}")

            setStock(10)
            val flipped = run("run ${replays + 2} (replay, back in stock)")
            assertIs<ExecutionResult.Success>(flipped.result, flipped.result.message)
            val notification = host.notifications.single()
            assertTrue("Long Nose Pliers" in notification.title + "\n" + notification.message, "${notification.title}\n${notification.message}")
        }
        traffic.assertFallbacksAccepted()
    }
}

/** G2 login + read on the public demo: invoice number, date and total of the latest order, checked against the API. */
@Tag("acceptance")
class G2LoginReadTest {
    private val host = Host()
    private val traffic = ModelTraffic()
    private lateinit var key: String
    private lateinit var token: String

    @BeforeTest
    fun setUp() {
        key = anthropicKeyOrSkip()
        token = Demo.api.login(Demo.email, Demo.password)
    }

    @AfterTest
    fun tearDown() = traffic.close()

    private fun latestInvoice(): JsonObject = Demo.api.invoices(token)["data"]!!.jsonArray.maxBy { it.jsonObject["invoice_date"]!!.jsonPrimitive.content }.jsonObject

    private fun config(goal: String, runMode: RunMode) =
        TestConfig(
            goal = goal,
            startUrl = Demo.url,
            apiKey = Secret(key),
            model = ACCEPTANCE_MODEL,
            secrets = Demo.secrets,
            runMode = runMode,
        )

    @Test
    fun `G2 notifies the latest invoice's number, date and total and never sends the password to the model`() {
        AcceptanceTask("G2", config("Log in and tell me the invoice number, date and total of my most recent order", RunMode.Once), host, traffic).use {
            val stats = run("Once (learn)")
            assertIs<ExecutionResult.Success>(stats.result, stats.result.message)
            stats.assertCacheReadsOnLaterSteps()
        }

        val invoice = latestInvoice()
        val message = host.notifications.single().message
        val number = invoice["invoice_number"]!!.jsonPrimitive.content
        val date = invoice["invoice_date"]!!.jsonPrimitive.content.substringBefore(' ')
        val total = money(invoice["total"]!!.jsonPrimitive.double)
        for (expected in listOf(number, date, total)) assertTrue(expected in message, "'$expected' (API: $invoice) missing from:\n$message")
        assertTrue(!message.startsWith("Goal "), "the headline is the model's answer: $message")
        traffic.assertNeverContains(Demo.password, "the password")
        traffic.assertFallbacksAccepted()
    }

    /**
     * Repeat needs a not-yet Check, so the monitor watches for a new invoice. A second task start with a fresh
     * (logged-out) Chrome profile replays the login from the cache: zero model calls, zero confirmations.
     */
    @Test
    fun `G2 in Repeat mode replays the login with zero confirmations`() {
        val number = latestInvoice()["invoice_number"]!!.jsonPrimitive.content
        val goal = "Log in and notify me when the invoice number of my most recent order is no longer $number"

        AcceptanceTask("G2", config(goal, RunMode.Repeat), host, traffic).use {
            val learned = run("Repeat run 1 (learn)")
            assertIs<ExecutionResult.Loop>(learned.result, learned.result.message)
            learned.assertCacheReadsOnLaterSteps()
        }
        assertNotNull(
            runBlocking { host.create().preference().getString(ReplayCache.key(goal, Demo.url, listOf("email", "password"))) },
            "run 1 wrote no replay cache: ${host.logs.filter { "replay cache" in it }}",
        )

        AcceptanceTask("G2", config(goal, RunMode.Repeat), host, traffic).use {
            val replay = run("Repeat run 2 (replay, fresh profile)")
            assertIs<ExecutionResult.Loop>(replay.result, replay.result.message)
            assertEquals(0, replay.steps, "the replay called the model: ${host.logs.filter { "replay miss" in it }}")
        }
        assertEquals(emptyList(), host.dialogs, "the login asked for confirmation")
        assertTrue(host.notifications.isEmpty(), host.notifications.map { it.message }.toString())
        traffic.assertNeverContains(Demo.password, "the password")
        traffic.assertFallbacksAccepted()
    }
}

/**
 * G3 buy with confirmation on the public demo. Decline must leave no invoice; Accept must create exactly one Claw
 * Hammer invoice. Accept places a real (fake-paid) order on the shared demo account.
 */
@Tag("acceptance")
class G3BuyTest {
    private val host = Host()
    private val traffic = ModelTraffic()
    private lateinit var key: String
    private lateinit var token: String

    @BeforeTest
    fun setUp() {
        key = anthropicKeyOrSkip()
        token = Demo.api.login(Demo.email, Demo.password)
    }

    @AfterTest
    fun tearDown() = traffic.close()

    private fun invoices() = Demo.api.invoices(token)

    private fun count() = invoices()["total"]!!.jsonPrimitive.int

    // Fresh profile (empty cart) and forgotten steps per run: a cached purchase must never replay into the other test.
    private fun task() =
        AcceptanceTask(
            "G3",
            TestConfig(
                goal = "Log in and buy one Claw Hammer, paying cash on delivery",
                startUrl = Demo.url,
                apiKey = Secret(key),
                model = ACCEPTANCE_MODEL,
                secrets = Demo.secrets,
            ),
            host,
            traffic,
        )

    @Test
    fun `G3 Decline at the confirmation places no order`() {
        val before = count()
        host.dialogAnswers += declineClicked

        task().use {
            val stats = run("Decline")
            assertIs<ExecutionResult.Success>(stats.result)
            assertTrue(stats.result.message.matches(Regex("Stopped: you declined to .+\\. Nothing was done\\.")), stats.result.message)
            assertEquals(1, stats.dialogs, "one confirmation, declined")
        }

        assertEquals(before, count(), "an invoice was created after Decline")
        assertTrue(host.notifications.any { it.message.contains("\nConfirmation needed: ") }, host.notifications.map { it.message }.toString())
        assertTrue(host.notifications.last().message.startsWith("Stopped: you declined to "), host.notifications.last().message)
        traffic.assertNeverContains(Demo.password, "the password")
        traffic.assertFallbacksAccepted()
    }

    @Test
    fun `G3 Accept places exactly one Claw Hammer order`() {
        val before = count()
        repeat(10) { host.dialogAnswers += confirmClicked }

        task().use {
            val stats = run("Accept")
            assertIs<ExecutionResult.Success>(stats.result, stats.result.message)
            assertTrue(stats.dialogs >= 1, "no confirmation before the order was placed")
            stats.assertCacheReadsOnLaterSteps()
        }

        assertEquals(before + 1, count(), "expected exactly one new invoice")
        val newest = invoices()["data"]!!.jsonArray.maxBy { it.jsonObject["invoice_date"]!!.jsonPrimitive.content }.jsonObject
        val lines = newest["invoicelines"]!!.jsonArray.map { it.jsonObject["product"]!!.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["quantity"]!!.jsonPrimitive.int }
        assertEquals(listOf("Claw Hammer" to 1), lines, "newest invoice: $newest")
        traffic.assertNeverContains(Demo.password, "the password")
        traffic.assertFallbacksAccepted()
    }
}
