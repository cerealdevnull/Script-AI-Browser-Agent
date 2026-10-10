package com.cereal.script.aiagent

import dev.kdriver.cdp.domain.Accessibility
import dev.kdriver.cdp.domain.accessibility
import dev.kdriver.cdp.domain.dom
import dev.kdriver.cdp.domain.runtime
import dev.kdriver.core.browser.createBrowser
import dev.kdriver.core.dom.DefaultElement
import dev.kdriver.core.tab.ReadyState
import dev.kdriver.core.tab.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.kdriver.core.browser.Browser as KBrowser

/** The user's Chrome, headed, on a persistent profile; one tab. Element refs come from the latest [snapshot]. */
class Browser private constructor(private val browser: KBrowser) {
    private val tab: Tab get() = browser.mainTab ?: error("Chrome has no open tab")
    private var refs: Map<Int, Int> = emptyMap() // ref -> backendDOMNodeId
    private var names: Map<Int, String> = emptyMap() // ref -> role "name"
    private var textRefs: Map<String, Int> = emptyMap() // "tN" -> backendDOMNodeId

    suspend fun url(): String = (tab.rawEvaluate("location.href") as? JsonPrimitive)?.contentOrNull ?: "?"

    suspend fun title(): String = (tab.rawEvaluate("document.title") as? JsonPrimitive)?.contentOrNull.orEmpty()

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

    /** Picks the `<select>` option whose visible text or value is [option]. */
    suspend fun select(ref: Int, option: String) {
        val js =
            "el => { const o = [...(el.options || [])].find(o => o.text.trim() === ${JsonPrimitive(option)} || o.value === ${JsonPrimitive(option)}); " +
                "if (!o) return false; el.value = o.value; " +
                "el.dispatchEvent(new Event('input', {bubbles: true})); el.dispatchEvent(new Event('change', {bubbles: true})); return true; }"
        require((element(ref).rawApply(js) as? JsonPrimitive)?.contentOrNull == "true") { "[$ref] has no option '$option'" }
        settle()
    }

    suspend fun scroll(down: Boolean) {
        if (down) tab.scrollDown(80) else tab.scrollUp(80)
        settle()
    }

    suspend fun goBack() {
        tab.back()
        settle()
    }

    suspend fun wait(seconds: Int) {
        delay(seconds * 1000L)
        settle()
    }

    suspend fun screenshotJpeg(): String = tab.screenshotB64()

    /** True for an enabled, writable text-like `<input>` or `<textarea>` (where a secret may be typed). */
    suspend fun isTextInput(ref: Int): Boolean {
        val (node, attrs) = node(ref)
        if ("disabled" in attrs || "readonly" in attrs) return false
        return when (node.nodeName.uppercase()) {
            "TEXTAREA" -> true
            "INPUT" -> attrs["type"].orEmpty().lowercase() in TEXT_INPUT_TYPES
            else -> false
        }
    }

    /** A password, email or payment-card field, judged by its type, autocomplete, name, id and accessible name. */
    suspend fun isSensitiveField(ref: Int): Boolean {
        val (_, attrs) = node(ref)
        val text = listOf("type", "autocomplete", "name", "id").joinToString(" ") { attrs[it].orEmpty() } + " " + describe(ref)
        return SENSITIVE.containsMatchIn(text.lowercase())
    }

    private suspend fun node(ref: Int) =
        tab.dom.describeNode(backendNodeId = backendId(ref)).node.let { node ->
            node to node.attributes.orEmpty().chunked(2).associate { it[0].lowercase() to it.getOrElse(1) { "" } }
        }

    /**
     * The page as an indented accessibility tree. Interactive elements carry an `[n]` ref; static text, cells and
     * headings a read-only `[tN]` ref (own ref space). Refs from older snapshots become invalid. [scrub] runs on names and values
     * before they are cut, so a long secret can't leak in part.
     */
    suspend fun snapshot(scrub: (String) -> String = { it }): String {
        val nodes = tab.accessibility.getFullAXTree().nodes
        val byId = nodes.associateBy { it.nodeId }
        val newRefs = mutableMapOf<Int, Int>()
        val newNames = mutableMapOf<Int, String>()
        val newTextRefs = mutableMapOf<String, Int>()
        val out = StringBuilder()

        fun walk(id: String, depth: Int, parentName: String) {
            val node = byId[id] ?: return
            val role = node.role.text()
            val name = scrub(node.name.text()).replace(WHITESPACE, " ").take(160)
            var childDepth = depth
            if (!node.ignored && role !in SKIP && !(role == "StaticText" && (name.isEmpty() || name == parentName))) {
                out.append("  ".repeat(depth))
                val backendId = node.backendDOMNodeId
                if (role in INTERACTIVE && backendId != null) {
                    val ref = newRefs.size + 1
                    newRefs[ref] = backendId
                    newNames[ref] = "$role \"$name\""
                    out.append("[$ref] ")
                } else if (role in TEXT && backendId != null && name.isNotEmpty()) {
                    val ref = "t${newTextRefs.size + 1}"
                    newTextRefs[ref] = backendId
                    out.append("[$ref] ")
                }
                out.append(role)
                if (name.isNotEmpty()) out.append(" \"").append(name).append('"')
                val value = scrub(node.value.text())
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
        textRefs = newTextRefs
        return if (out.length > MAX_CHARS) out.substring(0, MAX_CHARS) + "\n…(truncated)" else out.toString()
    }

    /**
     * An anchor-relative [Fingerprint] of the node behind a `tN` or `n` ref from the latest snapshot: the nearest
     * stable labelled node (column header, else a preceding heading/label/term) + tag + relative position, with an
     * xpath fallback. The node's own text is not part of it, so it survives the value changing.
     */
    suspend fun fingerprint(ref: String): Fingerprint {
        val backendId =
            textRefs[ref] ?: ref.toIntOrNull()?.let(refs::get)
                ?: throw IllegalArgumentException("unknown ref $ref; use a ref from the latest snapshot")
        val objectId = tab.dom.resolveNode(backendNodeId = backendId).`object`.objectId
        val result = tab.runtime.callFunctionOn(FINGERPRINT_JS, objectId = objectId, returnByValue = true)
        result.exceptionDetails?.let { error("fingerprint failed: ${it.text}") }
        return Fingerprint((result.result.value as JsonPrimitive).content)
    }

    /** The whitespace-collapsed text of every node [fingerprint] matches on the live page: unique when size is 1. */
    suspend fun resolve(fingerprint: Fingerprint): List<String> =
        (tab.rawEvaluate("($RESOLVE_JS)(${fingerprint.json})") as JsonArray).map { it.jsonPrimitive.content }

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
        private val SENSITIVE = Regex("password|passwd|e-?mail|cc-|card|cvc|cvv|iban")
        private val FLAGS = setOf("disabled", "checked", "expanded", "selected")
        private val SKIP = setOf("none", "generic", "InlineTextBox", "LineBreak", "RootWebArea", "paragraph", "group", "list")
        private val TEXT = setOf("StaticText", "cell", "gridcell", "columnheader", "rowheader", "heading")
        private val INTERACTIVE =
            setOf(
                "button", "link", "textbox", "searchbox", "checkbox", "radio", "combobox", "listbox", "option",
                "menuitem", "tab", "switch", "slider", "spinbutton",
            )

        // Shared by FINGERPRINT_JS and RESOLVE_JS. Leaves = elements with their own text, in document order; an anchor
        // is a leaf that is (inside) a heading/label/term/caption, or whose text ends with ':'.
        private const val JS_HELPERS = """
            const norm = s => (s || '').replace(/\s+/g, ' ').trim();
            const own = e => norm(Array.from(e.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent).join(' '));
            const leaves = () => Array.from(document.body.querySelectorAll('*')).filter(e => own(e));
            const isAnchor = e => !!e.closest('h1,h2,h3,h4,h5,h6,label,th,dt,caption,legend') || own(e).endsWith(':');
            const header = t => t.tHead ? t.tHead.rows[0] : t.rows[0];
            const bodyRows = t => Array.from(t.rows).filter(r => r !== header(t) && r.parentElement.tagName !== 'THEAD');
        """

        private const val FINGERPRINT_JS = """function() {
            $JS_HELPERS
            const node = this.nodeType === 3 ? this.parentElement : this;
            let el = node;
            const xpath = n => {
                const parts = [];
                for (; n && n.nodeType === 1; n = n.parentElement) {
                    let i = 1;
                    for (let s = n.previousElementSibling; s; s = s.previousElementSibling) if (s.tagName === n.tagName) i++;
                    parts.unshift(n.tagName.toLowerCase() + '[' + i + ']');
                }
                return '/' + parts.join('/');
            };
            const cell = node.closest('td,th');
            const table = cell && cell.closest('table');
            if (table && cell.parentElement !== header(table)) {
                const col = Array.prototype.indexOf.call(cell.parentElement.cells, cell);
                const th = header(table) && header(table).cells[col];
                const row = bodyRows(table).indexOf(cell.parentElement);
                if (th && norm(th.textContent) && row >= 0)
                    return JSON.stringify({tag: cell.tagName.toLowerCase(), xpath: xpath(cell), column: norm(th.textContent), row: row});
            }
            const all = leaves();
            if (!own(el)) el = all.find(e => node.contains(e)) || el;
            const i = all.indexOf(el);
            for (let j = i - 1; j >= 0; j--) {
                const a = all[j];
                if (isAnchor(a) && !a.contains(el))
                    return JSON.stringify({tag: el.tagName.toLowerCase(), xpath: xpath(el),
                        anchor: {tag: a.tagName.toLowerCase(), text: own(a)}, offset: i - j});
            }
            return JSON.stringify({tag: el.tagName.toLowerCase(), xpath: xpath(el)});
        }"""

        private const val RESOLVE_JS = """function(fp) {
            $JS_HELPERS
            const text = e => norm(e.textContent);
            let found = null;
            if (fp.column !== undefined) {
                found = [];
                for (const t of document.querySelectorAll('table')) {
                    const h = header(t);
                    if (!h) continue;
                    Array.from(h.cells).forEach((c, col) => {
                        if (norm(c.textContent) !== fp.column) return;
                        const r = bodyRows(t)[fp.row];
                        const cell = r && r.cells[col];
                        if (cell && cell.tagName.toLowerCase() === fp.tag) found.push(cell);
                    });
                }
            } else if (fp.anchor) {
                const all = leaves();
                found = [];
                all.forEach((a, j) => {
                    if (a.tagName.toLowerCase() !== fp.anchor.tag || own(a) !== fp.anchor.text || !isAnchor(a)) return;
                    const e = all[j + fp.offset];
                    if (e && e.tagName.toLowerCase() === fp.tag) found.push(e);
                });
            }
            // ponytail: an anchor that is gone falls back to the xpath; an ambiguous anchor stays ambiguous (never guess).
            if (!found || found.length === 0) {
                const e = document.evaluate(fp.xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                found = e && e.tagName.toLowerCase() === fp.tag ? [e] : [];
            }
            return found.map(text);
        }"""

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

/** Where a Check or Readout target is, independent of its own text: opaque JSON, evaluated by [Browser.resolve]. */
data class Fingerprint(val json: String)
