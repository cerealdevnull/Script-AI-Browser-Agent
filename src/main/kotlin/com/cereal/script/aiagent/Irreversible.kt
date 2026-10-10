package com.cereal.script.aiagent

/**
 * Decides on the live page whether a `click` / `type` is irreversible. A step asks for confirmation when
 * `heuristic(...) || (modelFlag && !signInSubmit(...))`; replay applies the same rule with the cached model flag.
 * One per goal run: it remembers which inputs `type_secret` filled ([secretTyped]).
 */
class Irreversible(private val browser: Browser, private val allowedDomains: Set<String>) {
    private val secretInputs = mutableSetOf<Int>()

    fun secretTyped(ref: Int) {
        secretInputs += browser.nodeId(ref)
    }

    /**
     * Deterministic: an off-domain page, a buy/pay/... name on the target or the form it submits, or a submit on a
     * checkout URL. A `type` that doesn't submit changes nothing yet, so a field named e.g. "Order number" doesn't ask.
     */
    suspend fun heuristic(action: String, ref: Int, text: String?): Boolean {
        if (!isAllowedHost(hostOf(browser.url()), allowedDomains)) return true
        val form = browser.form(ref)
        val submits = submits(action, text, form)
        val name = browser.describe(ref).substringAfter('"')
        if ((action == "click" || submits) && IRREVERSIBLE.containsMatchIn(name.lowercase())) return true
        return submits && (IRREVERSIBLE.containsMatchIn(form.submitName.lowercase()) || CHECKOUT.containsMatchIn(browser.url().lowercase()))
    }

    /** A submit of a form with exactly one password input that `type_secret` filled, on an allowed domain, outside checkout/payment. */
    suspend fun signInSubmit(action: String, ref: Int, text: String?): Boolean {
        val url = browser.url()
        val form = browser.form(ref)
        return submits(action, text, form) && form.passwordNode in secretInputs &&
            isAllowedHost(hostOf(url), allowedDomains) && !CHECKOUT.containsMatchIn(url.lowercase())
    }

    // ponytail: Enter in a text field is assumed to submit its form; browsers skip implicit submission in a few cases.
    private fun submits(action: String, text: String?, form: FormInfo) =
        if (action == "click") form.clickSubmits else form.inForm && text.orEmpty().contains('\n')

    companion object {
        // ponytail: English + Dutch keywords; add languages as users need them.
        private val IRREVERSIBLE =
            Regex(
                "\\b(buy|pay|order|place|confirm|submit|delete|send|cancel subscription)\\b|" +
                    "\\b(bestellen|bestel|betalen|betaal|afrekenen|kopen|koop|bevestigen|bevestig|verwijderen|verwijder|verzenden|verstuur|versturen|abonnement opzeggen)\\b",
            )
        private val CHECKOUT = Regex("checkout|payment|afrekenen|betalen")
    }
}
