// Our bridge for @duckduckgo/autoconsent (autoconsent.playwright.js, vendored unmodified, runs right after this).
// It answers init itself, in reject mode, and keeps what the script reads in window.__cerealConsent:
// the latest lifecycle state and every optOutResult. __RULES__ is replaced with compact-rules.json.
(() => {
    if (window.autoconsentSendMessage) return;
    const rules = __RULES__;
    const consent = (window.__cerealConsent = { state: 'loading', results: [] });
    window.autoconsentSendMessage = (msg) => {
        if (msg.type === 'init') {
            // Async: the bundle sends init before it defines autoconsentReceiveMessage.
            setTimeout(() =>
                window.autoconsentReceiveMessage({
                    type: 'initResp',
                    rules: { compact: rules },
                    config: { autoAction: 'optOut', heuristicMode: 'reject', isMainWorld: true, enableCosmeticRules: true },
                }),
            );
        } else if (msg.type === 'report') {
            consent.state = msg.state.lifecycle;
        } else if (msg.type === 'optOutResult') {
            consent.results.push({ cmp: msg.cmp, result: msg.result });
        }
        return Promise.resolve();
    };
})();
