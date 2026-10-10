package com.cereal.script.aiagent

import dev.kdriver.cdp.domain.Accessibility
import dev.kdriver.cdp.domain.accessibility
import dev.kdriver.cdp.domain.dom
import dev.kdriver.core.browser.createBrowser
import dev.kdriver.core.dom.DefaultElement
import dev.kdriver.core.tab.ReadyState
import dev.kdriver.core.tab.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.kdriver.core.browser.Browser as KBrowser

/** The user's Chrome, headed, on a persistent profile; one tab. Element refs come from the latest [snapshot]. */
class Browser private constructor(private val browser: KBrowser) {
    private val tab: Tab get() = browser.mainTab ?: error("Chrome has no open tab")
    private var refs: Map<Int, Int> = emptyMap() // ref -> backendDOMNodeId
    private var names: Map<Int, String> = emptyMap() // ref -> role "name"

    suspend fun url(): String = (tab.rawEvaluate("location.href") as? JsonPrimitive)?.contentOrNull ?: "?"

    suspend fun navigate(url: String) {
        tab.get(url)
        settle()
    }

    fun describe(ref: Int): String = names[ref] ?: throw IllegalArgumentException("unknown ref $ref; use a ref from the latest snapshot")

    suspend fun click(ref: Int) {
        element(ref).click()
        settle()
    }

    suspend fun type(ref: Int, text: String) {
        val element = element(ref)
        element.clearInput()
        element.sendKeys(text)
    }

    /** True for an enabled, writable text-like `<input>` or `<textarea>` (where a secret may be typed). */
    suspend fun isTextInput(ref: Int): Boolean {
        val node = tab.dom.describeNode(backendNodeId = backendId(ref)).node
        val attrs = node.attributes.orEmpty().chunked(2).associate { it[0].lowercase() to it.getOrElse(1) { "" } }
        if ("disabled" in attrs || "readonly" in attrs) return false
        return when (node.nodeName.uppercase()) {
            "TEXTAREA" -> true
            "INPUT" -> attrs["type"].orEmpty().lowercase() in TEXT_INPUT_TYPES
            else -> false
        }
    }

    /**
     * The page as an indented accessibility tree. Interactive elements carry an `[n]` ref; refs from older snapshots
     * become invalid.
     */
    suspend fun snapshot(): String {
        val nodes = tab.accessibility.getFullAXTree().nodes
        val byId = nodes.associateBy { it.nodeId }
        val newRefs = mutableMapOf<Int, Int>()
        val newNames = mutableMapOf<Int, String>()
        val out = StringBuilder()

        fun walk(id: String, depth: Int, parentName: String) {
            val node = byId[id] ?: return
            val role = node.role.text()
            val name = node.name.text().replace(WHITESPACE, " ").take(160)
            var childDepth = depth
            if (!node.ignored && role !in SKIP && !(role == "StaticText" && (name.isEmpty() || name == parentName))) {
                out.append("  ".repeat(depth))
                val backendId = node.backendDOMNodeId
                if (role in INTERACTIVE && backendId != null) {
                    val ref = newRefs.size + 1
                    newRefs[ref] = backendId
                    newNames[ref] = "$role \"$name\""
                    out.append("[$ref] ")
                }
                out.append(role)
                if (name.isNotEmpty()) out.append(" \"").append(name).append('"')
                val value = node.value.text()
                if (value.isNotEmpty() && role in INTERACTIVE) out.append(" value=\"").append(value.take(80)).append('"')
                node.properties?.forEach { p ->
                    val flag = p.name.name.lowercase()
                    if (flag in FLAGS && p.value.text() == "true") out.append(' ').append(flag)
                }
                out.append('\n')
                childDepth = depth + 1
            }
            node.childIds?.forEach { walk(it, childDepth, name.ifEmpty { parentName }) }
        }
        nodes.firstOrNull { it.parentId == null }?.let { walk(it.nodeId, 0, "") }
        refs = newRefs
        names = newNames
        return if (out.length > MAX_CHARS) out.substring(0, MAX_CHARS) + "\n…(truncated)" else out.toString()
    }

    suspend fun close() = browser.stop()

    private fun backendId(ref: Int) = refs[ref] ?: throw IllegalArgumentException("unknown ref $ref; use a ref from the latest snapshot")

    private suspend fun element(ref: Int): DefaultElement {
        val backendId = backendId(ref)
        tab.dom.scrollIntoViewIfNeeded(backendNodeId = backendId)
        return DefaultElement(tab, tab.dom.describeNode(backendNodeId = backendId).node)
    }

    private suspend fun settle() {
        delay(300)
        tab.waitForReadyState(ReadyState.COMPLETE, PAGE_LOAD_TIMEOUT_MS)
        delay(300)
    }

    companion object {
        private const val PAGE_LOAD_TIMEOUT_MS = 60_000L
        private const val MAX_CHARS = 14_000
        private val TEXT_INPUT_TYPES = setOf("", "text", "email", "password", "search", "tel", "url")
        private val WHITESPACE = Regex("\\s+")
        private val FLAGS = setOf("disabled", "checked", "expanded", "selected")
        private val SKIP = setOf("none", "generic", "InlineTextBox", "LineBreak", "RootWebArea", "paragraph", "group", "list")
        private val INTERACTIVE =
            setOf(
                "button", "link", "textbox", "searchbox", "checkbox", "radio", "combobox", "listbox", "option",
                "menuitem", "tab", "switch", "slider", "spinbutton",
            )

        private fun Accessibility.AXValue?.text() = (this?.value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        suspend fun launch(profileDir: java.nio.file.Path): Browser =
            Browser(
                // Its own scope: the host runs every execute() in a short-lived runBlocking, and the browser's
                // websocket collectors must outlive it. kdriver detaches the browser's Job anyway; onFinish stops it.
                createBrowser(CoroutineScope(Dispatchers.IO + SupervisorJob())) {
                    userDataDir = Path(profileDir.toString())
                    headless = false
                    // A cold headed Chrome on a fresh profile is slow; kdriver's defaults are 500 ms x 60.
                    browserConnectionTimeout = 1_000
                    browserConnectionMaxTries = 90
                },
            )
    }
}
