# Prior art: rejecting cookie consent without a model

Research for issue #22. Researched 2026-10-11 against primary sources (repos cloned at HEAD, vendor docs, the IAB spec). Constraints from the issue: closed-source marketplace jar, ~5.5 MB, no vendor SDKs, drives the user's Chrome over CDP via kdriver.

## Answer

**Embed DuckDuckGo autoconsent's prebuilt browser bundle, unmodified, as a jar resource and inject it over CDP.** It is MPL-2.0, which allows bundling an unmodified file in a closed-source product. It has the widest coverage (about 1,000 rules plus a heuristic fallback), it is maintained (a release went out the day before this note), and its runtime is a single self-contained IIFE with no extension APIs. Expect about 0.45 MB uncompressed in the jar, or about 0.1 MB compressed.

Don't port it to Kotlin. A port is a derivative of MPL files, so those Kotlin files would have to be published under MPL. The rules also have to run in the page, because they act on the DOM and call page globals like `UC_UI` and `Cookiebot`, so Kotlin would only add a round-trip per step.

Consent-O-Matic is the MIT fallback if MPL is unacceptable. Its rules can be ported or embedded freely. The catch is that its engine is tied to the extension, and its project is less active.

## Comparison

| | DDG autoconsent | Consent-O-Matic | IAB TCF `__tcfapi` | CMP vendor JS APIs | Chrome / CDP native |
|---|---|---|---|---|---|
| Can reject? | Yes (`autoAction: 'optOut'`) | Yes (all categories off) | **No**, read-only | Yes, per vendor | **No** |
| Coverage | 423 hand-written JSON rules, 567 generated site rules, 12 class-based CMPs, text heuristics | 408 CMP/site entries in 204 files | Any TCF CMP, detection only | 4 vendors, one call each | none |
| License | MPL-2.0 | MIT | Spec only, nothing to embed | n/a (you call the site's own script) | n/a |
| Format | JSON steps (`detectCmp`/`detectPopup`/`optOut`/`optIn`/`test`) plus TS classes | JSON `detectors` + `methods` (`HIDE_CMP`, `OPEN_OPTIONS`, `DO_CONSENT`, `SAVE_CONSENT`) | n/a | n/a | n/a |
| Detects by | CSS/XPath selector existence and visibility, cookie match, eval snippets, regex over popup text | `presentMatcher` / `showingMatcher` (CSS, checkbox) | `__tcfapi` exists, or a `__tcfapiLocator` frame; `cmpuishown` event | the global object exists | n/a |
| Runtime | Prebuilt IIFE (`dist/autoconsent.playwright.js`, 144 KB) + `rules/compact-rules.json` (299 KB), or `dist/autoconsent.standalone.js` (466 KB, rules inlined) | Extension content script (`Extension/*.js`, about 191 KB) | n/a | n/a | n/a |
| Activity | v16.50.0 released 2026-10-10 | last commit 2025-11-07 | TCF v2.2 | | |

## 1. DuckDuckGo autoconsent

Source: https://github.com/duckduckgo/autoconsent (v16.50.0, `LICENSE` = MPL-2.0; npm `@duckduckgo/autoconsent` declares `"license": "MPL-2.0"`).

- **What it is.** "A library of rules for navigating through common consent popups … opt-in and opt-out options can be selected automatically" (`readme.md`). DuckDuckGo's browsers use it in production.
- **Rule format** (`docs/rule-syntax.md`). Each rule has `name`, `detectCmp`, `detectPopup`, `optOut`, `optIn`, and optionally `prehideSelectors`, `cosmetic`, `runContext` (`main`/`frame`/`urlPattern`) and `test`. Steps include `exists`, `visible`, `waitFor`, `waitForVisible`, `click`, `waitForThenClick`, `hide`, `cookieContains`, `eval`, `if/then/else`, `any`, `negated` and `optional`. Selectors are CSS or `xpath/…`. An array selector descends into open shadow roots and same-origin iframes. Complex CMPs (OneTrust, Cookiebot, Sourcepoint, TrustArc, consentmanager…) are TS classes in `lib/cmps/`.
- **Detection.** First a rule's `detectCmp` (is the CMP present?), then `detectPopup` (is it showing?), retried `detectRetries` times. With `enableHeuristicDetection`, `lib/heuristics.ts` also scans popup-like elements against `DETECT_PATTERNS` regexes ("accept cookies", "reject all", "we use cookies", plus FR/DE/… variants) and sorts the buttons into reject, acknowledge, settings and accept.
- **Reject vs accept, the trap.** `heuristicMode` (`lib/types.ts`) is one of `'reject'`, `'tier1'` (click "acknowledge/OK" when there is no reject button) or `'tier2'` (**click accept** when there is no reject, acknowledge or settings button). The `standalone` bundle hard-codes `heuristicMode: 'tier2'` (`standalone/content.ts`). To reject only, use the `playwright` bundle and pass `heuristicMode: 'reject'` (or `'off'`) in the config yourself. `cosmetic` rules only hide a banner and record no choice; set `enableCosmeticRules: false` if hiding is not acceptable.
- **How vendor APIs are used.** Eval snippets are named functions compiled into the bundle (`lib/eval-snippets.ts`), for example `EVAL_DIDOMI_OPT_OUT` → `Didomi.setUserDisagreeToAll()`, `EVAL_USERCENTRICS_API_2` → `UC_UI.denyAllConsents()`, and `EVAL_COOKIEBOT_3` → `Cookiebot.withdraw()`. With `isMainWorld: true` they are called directly with `snippet.call(globalThis)` (`lib/cmps/base.ts`). Nothing runs through `eval()` of a string, so page CSP does not block them. OneTrust is handled by clicking `#onetrust-reject-all-handler` / `.ot-pc-refuse-all-handler` (`lib/cmps/onetrust.ts`). autoconsent never calls `__tcfapi` to set consent.
- **Embedding outside an extension** (`docs/puppeteer.md`). Inject `dist/autoconsent.playwright.js` before page scripts run (`evaluateOnNewDocument`). The script calls `window.autoconsentSendMessage({type:'init'})`. The host replies through `window.autoconsentReceiveMessage({type:'initResp', config, rules})`, and the script then posts `cmpDetected`, `popupFound`, `optOutResult`, `autoconsentDone` and `autoconsentError`. "The `initResp` message **must** include a `rules` object" (`rules.json` or the smaller opt-out-only `compact-rules.json`).

### How it would run in this script

1. Ship two files, unmodified, as jar resources: `autoconsent.playwright.js` (144 KB) and `compact-rules.json` (299 KB). About 0.45 MB raw, around 0.1 MB deflated (the 466 KB standalone file gzips to 97 KB). ProGuard ignores resources.
2. Add a short glue script of our own that defines `window.autoconsentSendMessage` and answers `init` locally, with the config (`autoAction:'optOut'`, `heuristicMode:'reject'`, `isMainWorld:true`, `enableCosmeticRules` per policy) and the rules inlined. Its result messages go to a CDP binding (`Runtime.addBinding`, string payload, so `JSON.stringify` first) or a `window` array that Kotlin polls with `rawEvaluate`, which `Browser.kt` already does. With `isMainWorld:true`, `eval` messages never come back to Kotlin.
3. Inject glue and bundle with `Page.addScriptToEvaluateOnNewDocument` (kdriver exposes the generated CDP domains; `Browser.kt` already uses `tab.runtime.evaluate`). Then run them once with `rawEvaluate` for the page that is already loaded, as `docs/puppeteer.md` step 5 does.
4. Caveats: CMPs inside cross-origin iframes (for example Sourcepoint `sourcepoint-frame.ts`, which has `runContext.frame`) are out-of-process frames and need `Target.setAutoAttach` to receive the script. The main world exposes `window.autoconsentReceiveMessage` to page scripts.

### License obligations (MPL-2.0, not legal advice)

MPL is file-level copyleft. §3.3 lets you distribute a "Larger Work" that combines Covered Software with other code under other terms. §3.2 requires that you tell recipients how to obtain the Covered Software's source form, and that you keep the notices. For an unmodified bundle that means a third-party notice naming `@duckduckgo/autoconsent@<version>` (MPL-2.0) with a link to its source, which can go in the listing or an about/licenses screen. Modifying the JS, or porting it to Kotlin, puts the changed or derived files under MPL, so those files would have to be published. Glue code in separate files stays proprietary.

## 2. Consent-O-Matic (Aarhus University, CAVI)

Source: https://github.com/cavi-au/Consent-O-Matic (`LICENSE` = MIT; GitHub shows "NOASSERTION" only because the file carries a custom copyright line).

- **Rule format** (README "Extending Consent-O-Matic"). Named entries, each with `detectors` (`presentMatcher` and `showingMatcher`, both CSS or checkbox matchers, all of which must match) and `methods` that run in the order `HIDE_CMP → OPEN_OPTIONS → HIDE_CMP → DO_CONSENT → SAVE_CONSENT`. Actions are `click`, `list`, `consent`, `slide`, `ifcss`, `waitcss`, `foreach`, `wait`, `hide` and `close`. A `consent` action toggles a category by type: D (storage/access), A (preferences), B (analytics), E (content), F (ads) or X (other). Rejecting means every category off.
- **Coverage.** 408 entries across `rules/*.json` (204 files). Rules are fetched remotely through `rules-list.json`.
- **Detection.** CSS present/showing matchers only, with no text heuristics, so unknown banners are missed.
- **Embedding.** There is no standalone build. The engine (`ConsentEngine.js`, `Action.js`, `Matcher.js`, `Detector.js`, `CMP.js`) is mostly plain DOM code, but `contentScript.js` and `Tools.js` call `chrome.*`/`browser.*`, so it would need a shim or a rewrite.
- **Fit.** MIT is the easiest license, since attribution is all it asks. You could port the engine to JS glue, or interpret the rules from Kotlin through `rawEvaluate`, and either is legal without publishing source. It is still more work than autoconsent and covers less (no heuristics, no vendor-API snippets). The last commit was 2025-11-07.

## 3. IAB TCF v2.2 `__tcfapi`

Source: `TCFv2/IAB Tech Lab - CMP API v2.md` in https://github.com/InteractiveAdvertisingBureau/GDPR-Transparency-and-Consent-Framework

- **It is read-only.** The required commands are `ping`, `addEventListener` and `removeEventListener`, and the optional ones are `getInAppTCData` and `getVendorList` (`getTCData` was deprecated in v2.2). The API exists so that "parties … access these preferences managed by the CMP". **No command records or rejects consent**, which the CMP UI alone does.
- **Still useful as a detector and verifier.** "Scripts can check for the presence of a function named `__tcfapi`", or look for a `__tcfapiLocator` iframe in a parent frame. In `addEventListener`, `eventStatus: 'cmpuishown'` means the UI is up and `'useractioncomplete'` means the choice is saved. After a reject, `purpose.consents` should all be false, which gives a cheap post-check if one is wanted. Rejection itself still needs the vendor-specific route.

## 4. CMP vendor JS APIs

Each is a call into the site's own CMP script, so there is no SDK to bundle. Run it in the main world after the CMP is ready.

| Vendor | Reject call | Detect / ready | Source |
|---|---|---|---|
| OneTrust | `OneTrust.RejectAll()`: "Disables consent for all categories/purposes, hosts, and vendors, except those marked as strictly necessary." `OneTrust.IsAlertBoxClosed()` tells whether consent is already stored. | `window.OneTrust`, `#onetrust-banner-sdk` | https://developer.onetrust.com/onetrust/docs/javascript-api |
| Cookiebot | **No `decline()`.** `Cookiebot.withdraw()` withdraws consent; `submitCustomConsent(false,false,false)` is meant "outside the context of the cookie banner"; `hide()` closes the dialog. The read-only `declined`/`hasResponse` properties confirm the outcome. | `window.Cookiebot` | https://www.cookiebot.com/en/developer/ |
| Didomi | `Didomi.setUserDisagreeToAll()`: disables "consents and legitimate interests for all purposes and vendors"; it "will log the user choice … and close the notice". `Didomi.notice.isVisible()`. | must be called from `window.didomiOnReady.push(fn)` | https://developers.didomi.io/cmp/web-sdk/reference/api |
| Usercentrics | v3: `window.__ucCmp.denyAllConsents()` → `Promise<void>`, which fires `uc-consent`. v2 (legacy): `UC_UI.denyAllConsents()`. | `window.__ucCmp` / `window.UC_UI` | https://usercentrics.com/docs/web/features/api/control-functionality/ |

These four calls are the "minimal, no-library" option: about 20 lines of our own JS, with no license burden. They only cover those four vendors, and the APIs change. autoconsent already wraps these same calls (plus DOM fallbacks) and keeps them current. For example, it has snippets for both `UC_UI` and the newer Usercentrics flows.

## 5. Chrome-native options

- **Chrome has no built-in banner rejection.** `chrome://settings/cookies` covers third-party cookie blocking only.
- **CDP has no consent domain.** The tip-of-tree domain list (`ChromeDevTools/devtools-protocol/pdl/domains`) has nothing for CMPs. The closest is `Extensions.loadUnpacked`, which would let us load autoconsent's own MV3 add-on. It needs Chrome started with extension-debugging flags and changes the user's browser, so it is a worse fit than injecting the script.
- **Other browsers, for reference.** Firefox's cookie-banner reduction used `mozilla/cookie-banner-rules-list` (MPL-2.0, now **archived** on GitHub). Brave and ad blockers hide banners with EasyList cookie lists, which hides them without recording a reject.

## Recommendation summary

1. **Do this:** inject `@duckduckgo/autoconsent` `dist/autoconsent.playwright.js` + `rules/compact-rules.json`, unmodified, from jar resources through `Page.addScriptToEvaluateOnNewDocument`, plus our own glue (`autoAction:'optOut'`, `heuristicMode:'reject'`, `isMainWorld:true`). Kotlin reads `autoconsentDone`/`optOutResult`. Cost: about 0.1 MB compressed, an MPL notice, and a version bump now and then.
2. **If MPL is a no-go:** port Consent-O-Matic's rules under MIT and add the 4-vendor JS calls from section 4.
3. **Don't:** rely on `__tcfapi` to reject (it can't), port autoconsent to Kotlin (that triggers MPL source publication and gains nothing), or use the standalone bundle as shipped (`heuristicMode: 'tier2'` clicks accept).
