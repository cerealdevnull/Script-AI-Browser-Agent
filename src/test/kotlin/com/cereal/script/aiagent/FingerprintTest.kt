package com.cereal.script.aiagent

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Anchor-relative fingerprints (Readout now, Check in replay): they must find the same node after its own text
 * changed. Tested on [Browser] because a single goal run can't change the page between fingerprint and resolve.
 */
class FingerprintTest {
    private val site = FixtureServer()

    @AfterTest
    fun tearDown() = site.close()

    private fun textRef(snapshot: String, role: String, name: String) =
        Regex("""\[(t\d+)] ${Regex.escape(role)} "${Regex.escape(name)}"""").find(snapshot)!!.groupValues[1]

    /** [changed] has [old] replaced by [new] and its layout shifted, so a plain xpath would hit the wrong node. */
    private fun survivesNewText(page: String, changed: String, role: String, old: String, new: String) =
        runBlocking {
            val browser = Browser.launch(tempProfiles())
            try {
                site.pages["page.html"] = page
                browser.navigate(site.url("page.html"))
                val fingerprint = browser.fingerprint(textRef(browser.snapshot(), role, old))
                assertEquals(listOf(old), browser.resolve(fingerprint))

                site.pages["page.html"] = changed
                browser.navigate(site.url("page.html"))
                assertEquals(listOf(new), browser.resolve(fingerprint))
            } finally {
                browser.close()
            }
        }

    @Test
    fun `a table cell is found by its column header and row after a new invoice number`() {
        val invoices = javaClass.getResource("/fixtures/invoices.html")!!.readText()
        // A new latest invoice: the number changes, the anchor (column "Invoice number", first row) doesn't.
        val changed =
            invoices
                .replace("INV-2026000042", "INV-2026000043")
                .replace("<table>", "<table><tr><th>Order</th><th>Status</th></tr><tr><td>ORD-7</td><td>Open</td></tr></table>\n<table>")
        survivesNewText(invoices, changed, "cell", "INV-2026000042", "INV-2026000043")
    }

    @Test
    fun `a value outside a table is found relative to its label`() {
        val page =
            """<html><body><h1>Order</h1><dl><dt>Status</dt><dd>Shipped</dd><dt>Total</dt><dd>$27.30</dd></dl></body></html>"""
        val changed = page.replace("<dt>Total</dt><dd>$27.30</dd>", "<dt>Shipping</dt><dd>$3.00</dd><dt>Total</dt><dd>$31.00</dd>")
        survivesNewText(page, changed, "StaticText", "$27.30", "$31.00")
    }
}
