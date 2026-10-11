# What CDP and kdriver offer for iframes and popups

Research for issue #20. Question: how can the script see and act inside iframes (same-origin and cross-origin/out-of-process) and follow popups and new tabs with kdriver 0.6.1 and raw CDP? The answer should be enough to size the iframe and popup candidates.

Sources:
- [P] the CDP protocol definition ([`browser_protocol.json`](https://github.com/ChromeDevTools/devtools-protocol/blob/master/json/browser_protocol.json), the file kdriver's `cdp-generate` builds from) and the [CDP docs](https://chromedevtools.github.io/devtools-protocol/).
- [K] kdriver source at tag [`0.6.1`](https://github.com/cdpdriver/kdriver/tree/0.6.1) (commit `1bce6ca`).
- [C] Chromium source and design docs.
- [E] a probe I ran against local Chrome 154.0.8037.98 (headless=new, default site isolation). The page was served from `127.0.0.1` and held a same-origin iframe, a cross-origin iframe on `localhost` (a different site, so it becomes an OOPIF), a `target=_blank` link and a `window.open` button. The probe script is not committed. It used raw CDP over WebSocket from Node.
- [B] this repo's `src/main/kotlin/com/cereal/script/aiagent/Browser.kt`.

## How the script works today [B]

- There is one tab: `browser.mainTab`. Every command goes over that tab's own WebSocket.
- `snapshot()` calls `tab.accessibility.getFullAXTree()` with no arguments. It walks from the root node and maps each ref to a `backendDOMNodeId`.
- Actions resolve the node on the same tab: `dom.describeNode`/`resolveNode(backendNodeId)` and then `DefaultElement`. `click()` is a JS `this.click()` and `sendKeys` dispatches `Input.dispatchKeyEvent` `char` events to the focused element.
- `url()`, `title()`, `rawEvaluate`, fingerprints (`RESOLVE_JS`) and `refFor` (xpath via `document.evaluate`) all run against the **top** document.

## 1. Is `getFullAXTree` per frame?

Yes. The tree for one call covers one document only.

- Protocol: `Accessibility.getFullAXTree(depth?, frameId?)` fetches the tree "for whose document … If omitted, the root frame is used." `AXNode` has a `frameId` ("the frame ID for the frame associated with this node's document"). The method is still marked experimental. [P]
- Observed: with no `frameId`, the root tree contains the top page's nodes plus two `Iframe` nodes, both with `childIds: []`. **No iframe content shows up, same-origin or cross-origin.** [E]
- `getFullAXTree(frameId = <same-origin child>)` on the page connection returns the child document's tree (`RootWebArea`, `SameOriginButton`, `SameField`). [E]
- `getFullAXTree(frameId = <OOPIF id>)` on the page connection fails with `-32602 Frame with the given frameId is not found`. `Page.getFrameTree` on the page connection also lists **only** the same-process child frame, so the OOPIF does not appear in it. An OOPIF's tree is only reachable through the OOPIF's own target. [E]
- `DOM.getFrameOwner(frameId)` returns the `backendNodeId` of the owning `<iframe>` element in the parent. It works for both kinds of frame: for an OOPIF, pass its targetId. That is how a child tree gets spliced under the right `Iframe` node. `DOM.Node.frameId` / `contentDocument` give the same link from the element side. [P][E]
- So the script cannot see any iframe content today, and this is not a `SKIP`-list issue: the tree it fetches simply does not contain those nodes.

## 2. How OOPIF targets are attached

Under site isolation, a cross-site iframe renders in another process and appears as its own CDP target of type `"iframe"`. Its `TargetInfo.parentId` and `parentFrameId` point at the page. ([Chromium: Out-of-Process iframes](https://www.chromium.org/developers/design-documents/oop-iframes/), [Site Isolation](https://www.chromium.org/Home/chromium-security/site-isolation/)) [C][P]

Chrome offers three ways to drive one:

| Way | What it is | Works with kdriver 0.6.1? |
|---|---|---|
| **Flat sessions** | `Target.setAutoAttach(autoAttach=true, flatten=true, waitForDebuggerOnStart=false)` on the page session. Chrome emits `Target.attachedToTarget{sessionId, targetInfo}` for existing and future OOPIFs, and commands for the child carry a top-level `sessionId`. The protocol says flat mode will become the default and non-flat will be retired ([crbug.com/991325](https://crbug.com/991325)). | **No.** kdriver's wire `Request` is `{id, method, params}` with no `sessionId` field, and `MessageSerializer` ignores `sessionId`, so child-session events would be dispatched as if they came from the page. [K: `cdp/.../Request.kt`, `Message.kt`] |
| **Non-flat sessions** | `Target.attachToTarget(flatten=false)` → `Target.sendMessageToTarget(message, sessionId)` / `receivedMessageFromTarget`. Commands are wrapped as JSON strings. | It works through kdriver's typed `target` domain, but `sendMessageToTarget` is **deprecated** [P], and you lose kdriver's typed domains for the child. |
| **A WebSocket per target** | Connect to `ws://host:port/devtools/page/<iframeTargetId>`. Chrome's HTTP handler accepts any target id under `/devtools/page/` ([`devtools_http_handler.cc`](https://chromium.googlesource.com/chromium/src/+/main/content/browser/devtools/devtools_http_handler.cc): `kPageUrlPrefix = "/devtools/page/"`, `GetForId(target_id)`, any other prefix gets a 404), and `/json/list` advertises iframe targets with that URL. | **Yes, with a workaround.** `DefaultTab(websocketUrl, scope, targetInfo, browser)` is a public open class [K: `tab/DefaultTab.kt:43`], so a `Tab` (with all typed domains, `DefaultElement`, `waitForReadyState`) can be pointed at the OOPIF. |

Observed with a per-target WebSocket at `/devtools/page/<oopifId>`: `getFullAXTree()` returns the OOPIF's tree (`RootWebArea`, `CrossOriginButton`). The same result came back via a flat session. [E]

**kdriver bug to work around:** with `autoDiscoverTargets` on (the default) kdriver already registers iframe targets, but it builds their URL as `/devtools/${type}/${targetId}`, which gives `/devtools/iframe/<id>` [K: `DefaultBrowser.upsertTarget`]. Chrome answers that with a 404. The probe's WebSocket to that path errored, while `/devtools/page/<id>` worked [E][C]. kdriver connects lazily [K: `DefaultConnection.send` → `connect()`], so these broken entries in `browser.targets` cause no harm until something uses them. Use them only for discovery (`targetId`, `parentFrameId`, `url`) and open a `DefaultTab` on the `/devtools/page/` URL yourself (`browser.config.host`/`port`).

## 3. Refs and backend node ids across frames

- **Same-origin, same-process frames share one id space with the page.** A `backendDOMNodeId` from the child frame's AX tree resolves on the **page** connection to the right element. `Runtime.callFunctionOn(this.click())` clicked it (its `ownerDocument.URL` was the child's), and `DOM.getBoxModel` returned coordinates in the top viewport. Today's `click`/`type`/`select`/`isTextInput`/`form` would therefore work unchanged on refs into same-origin frames. [E]
- **OOPIFs have their own id space, and ids collide silently.** The OOPIF button had backend id `2`. Resolved on the OOPIF's session it is a `button`. Resolved on the **parent** session, the same `2` is `#document`. There is no error, just the wrong node. [E] (`DOM.BackendNodeId` is only described as a "unique DOM node identifier" [P]. In practice it is unique per renderer/target.) So a ref must become `(target/connection, backendNodeId)`. A bare `Int` is unsafe once more than one target feeds the snapshot.
- Coordinates: `DOM.getContentQuads` on the OOPIF session is **frame-local**. The button was at `(8,8)` while its `<iframe>` owner box sat at `(398,68)` in the page [E]. Coordinate-based input would have to add the owner offsets up the chain (Puppeteer does this). The script clicks via JS, so this only matters for `scrollIntoViewIfNeeded`: run on the OOPIF session it scrolls inside the frame, and the parent may also need scrolling to the `<iframe>`.
- Keyboard: once an OOPIF input is focused, `Input.dispatchKeyEvent` reached it both via the **page** session and via the OOPIF session [E]. kdriver's `sendKeys` dispatches on `element.tab`, so a `DefaultElement` built on the OOPIF's `DefaultTab` is fine.
- The JS-in-`document` helpers (fingerprint/`RESOLVE_JS`, `refFor` xpath, `XPATH_JS`) only search the top document. Readouts and replay inside frames need those calls scoped to the frame: the frame's tab for an OOPIF, or `Page.createIsolatedWorld(frameId)` / an execution context for a same-origin frame. [P][B]
- `DOM.getDocument(depth=-1, pierce=true)` crosses into same-origin iframes but not OOPIFs. [E]

## 4. Detecting and switching to popups and new tabs

- `Target.setDiscoverTargets(true)` emits `targetCreated` / `targetInfoChanged` / `targetDestroyed`. A popup is a new `"page"` target whose `TargetInfo.openerId` is the opener's targetId (also `openerFrameId`, `canAccessOpener`). [P]
- Observed: both a `target=_blank` link and `window.open(…)` produced `targetCreated{type:"page", openerId:<our tab>}`. The URL starts blank and is filled in later by `targetInfoChanged`. `openerId` was set even for the `_blank` link, whose features include `noopener`. [E]
- `Page.windowOpen{url, windowName, windowFeatures, userGesture}` fires on the **opener** before the window opens. It needs `Page.enable` and is handy for "this click opened X". [P][E]
- `Target.setAutoAttach(flatten=true)` on the page session did **not** auto-attach the popups. Auto-attach covers "directly related" targets such as iframes and workers. [P][E]
- `Target.activateTarget(targetId)` brings a tab to the front, which matters for headed Chrome. `Target.closeTarget` closes a tab. [P]

What kdriver already does [K: `DefaultBrowser.kt`]:
- `autoDiscoverTargets = true` (default) calls `setDiscoverTargets(true)` and keeps `browser.targets` / `browser.tabs` current. Each new `"page"` target becomes a `DefaultTab` with its own WebSocket, and `targetDestroyed` removes it. So popups **already appear in `browser.tabs`**. Each one carries `targetInfo.openerId` (`BrowserTarget.openerId` / `openerFrameId`).
- `mainTab` is `tabs.maxByOrNull { type == "page" }`, which in practice is the **first** registered page. It does not follow a popup. If the original tab closes, it quietly becomes the next page.
- `browser.connection.target.targetCreated` (public) can be collected directly to wait for "the tab this click opened".
- kdriver has no "follow popup" or "current tab" concept.

## 5. kdriver 0.6.1: wrapped vs. raw

| Need | kdriver 0.6.1 | Raw CDP / glue needed |
|---|---|---|
| AX tree per frame | `accessibility.getFullAXTree(frameId=…)` typed | none |
| Frame list / owner | `page.getFrameTree()`, `dom.getFrameOwner()`, `DOM.Node.frameId` typed | none |
| Same-origin iframe elements | `Tab.select/find(includeFrames=true)` uses `contentDocument` (CSS/text search only, same-origin only) [K: `DefaultTab.kt:392,565`] | the script uses AX refs, not selectors, so this helps little |
| OOPIF target discovery | yes, in `browser.targets` (type `"iframe"`, `parentFrameId`) | none |
| OOPIF connection | broken URL (`/devtools/iframe/…`) | build `DefaultTab("…/devtools/page/<id>")` yourself |
| Flat sessions (`sessionId`) | **not supported** | would need a kdriver change or a separate client |
| Popup discovery | yes, `browser.tabs`, `openerId`, `targetCreated` flow | none |
| Switching the active tab | none (`mainTab` fixed) | a `current` tab in `Browser.kt`, plus `target.activateTarget` |

## 6. Sizing (rough, one developer, including tests on the local fixture pages)

**(a) Same-origin iframes: small, about 1 day.**
- In `snapshot()`, at each `Iframe` node, use `dom.describeNode(backendId).node.frameId` (or `getFrameOwner`) and fetch `getFullAXTree(frameId)`. Splice its tree in under that node, recursing for nested frames.
- Refs keep working because the id space is shared. Actions need no change.
- Add about half a day so that `fingerprint`/`resolve`/`refFor` replay runs inside the frame instead of the top `document`.

**(b) Cross-origin OOPIFs: medium, about 3–5 days.**
- One `DefaultTab` per OOPIF target via `/devtools/page/<id>`, discovered from `browser.targets` (type `iframe`, `parentFrameId`).
- Splice each tree under the `Iframe` whose owner `getFrameOwner(targetId)` returns, recursing for nested OOPIFs.
- Turn refs into `(tab, backendNodeId)` and route `element`/`node`/`form`/`fingerprint` through the ref's tab.
- Handle OOPIF targets that come and go with navigation, and scroll the parent to the `<iframe>`.
- Safety: `Agent` line ~445 checks "type a secret only on an allowed domain" against `browser.url()`, which is the **top** frame. Inside an OOPIF it must check the frame's URL, or a third-party widget passes as the host site. The same applies to the confirmation's `url`.
- Flat sessions would be cleaner but kdriver cannot send `sessionId`, so they are out unless kdriver changes.

**(c) Popups and new tabs: small to medium, about 1–2 days.**
- Replace `browser.mainTab` with a `current` tab.
- After each action, check `browser.tabs` for a new page with `openerId == current.targetId`, or collect `targetCreated`. Switch to it (`activateTarget`, `waitForReadyState`) and fall back to the opener when it is destroyed, for example when an OAuth window closes itself.
- Report open tabs in the step prompt, and optionally add `switch_tab` / `close_tab` tools.
- `alive` and `loading()` should follow `current`.
- The popup's domain must go through the same allowlist and secret rules. A click can open an off-allowlist OAuth domain, which the `navigate` tool would refuse.

## Open questions

- I measured with headless=new. Headed Chrome uses the same site-isolation process model by default, but popup window focus (`activateTarget`) was not checked headed.
- Not verified: whether `DOM.describeNode` on an OOPIF's `<iframe>` element returns the OOPIF targetId as `frameId`. `getFrameOwner(targetId)` was verified and is enough on its own.
