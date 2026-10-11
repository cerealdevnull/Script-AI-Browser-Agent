# Why Zalando's size list is missing from the snapshot

Research for issue #36. Question: on a Zalando product page, the snapshot shows `button "Maat kiezen" expanded` but no sizes. Is the list in a portal outside the snapshot root, in shadow DOM, under `aria-hidden`, or in a role the snapshot filters out? Is this a Zalando quirk, and what would including it cost?

**Answer: none of those. The sizes are in the snapshot, but `snapshot()` cuts the text at 14,000 chars and the size list starts at char ~65,000.** Zalando renders the dropdown into a portal at the end of the DOM, after the footer and about 60k chars of product carousels. The fix belongs in the truncation, not in the AX tree.

Sources:
- [B] this repo's `src/main/kotlin/com/cereal/script/aiagent/Browser.kt` (`snapshot()`, `MAX_CHARS`, `SKIP`, `INTERACTIVE`) and `Agent.kt:523`, where the snapshot goes into `<page_content>`.
- [P] the CDP protocol: [`Accessibility.getFullAXTree`](https://chromedevtools.github.io/devtools-protocol/tot/Accessibility/#method-getFullAXTree) and `AXProperty` `controls` (an `idrefList` with `relatedNodes`).
- [E1] the live page `https://www.zalando.nl/nike-sportswear-air-force-1-07-sneakers-laag-white-ni112n022-a11.html`, inspected in a headed browser on 2026-10-11. I declined non-essential cookies and only opened the size picker.
- [E2] a replay of `snapshot()`'s exact walk (same `SKIP`/`TEXT`/`INTERACTIVE`/`FLAGS` sets, same StaticText dedupe, no `frameId`) over raw CDP `Accessibility.getFullAXTree`. It ran against Chrome 154.0.8037.98 (`--headless=new`) on the same Zalando URL, before and after `#picker-trigger.click()`, and on a Nike PDP. The Node script is not committed.

## What `snapshot()` includes and excludes [B]

- It calls `getFullAXTree()` with no `frameId`, so it sees the main frame only (iframes are empty, see `docs/research/cdp-iframes-popups.md` on its branch).
- It walks from the root. A node is printed unless it is `ignored`, its role is in `SKIP` (`none generic InlineTextBox LineBreak RootWebArea paragraph group list`), or it is a StaticText that is empty or equals its parent's name. **Children of skipped and ignored nodes are still walked**, so portals and wrapper divs do not hide anything. Subtrees under `aria-hidden` or `inert` come back from Chrome as `ignored` all the way down, so those are dropped.
- `checkbox`, `radio`, `option` and `listbox` are all in `INTERACTIVE` and get `[n]` refs.
- The output is cut **from the tail**: `if (out.length > MAX_CHARS) out.substring(0, MAX_CHARS) + "\n…(truncated)"`, `MAX_CHARS = 14_000`. Anything after char 14,000 in document order never reaches the model.

## What the Zalando page does [E1]

- Before the click, the trigger is `<button id="picker-trigger" aria-haspopup="listbox" aria-expanded="false" aria-controls="«R35aqciaj5bp»">`, and no element with that id exists. The list is **rendered on demand**.
- After the click, a `div#«R35aqciaj5bp» role="listbox"` appears inside `div#portal-container`. That container is the **last** thing in the document: a text walk counted 12,933 visible chars before it and 0 after it, and the `<footer>` comes before it. It is a normal light-DOM div: no `aria-hidden`, no `inert`, no `aria-modal` on it or any ancestor. The page's only shadow root belongs to the Usercentrics consent banner, and its only iframe is the Usercentrics cross-domain bridge.
- Inside the listbox there is no `role="option"`. It holds a `tablist` (EU / "Maat op label") and a `form` with `role="list"`. Each size is a `role="listitem"` holding a native `<input type="checkbox" name="size-picker" value="NI112N022-A110065000">` labelled by `<label>` text such as "39 € 95,95". That is unusual ARIA (a listbox should own options), but Chrome still exposes the checkboxes.
- In this run all 20 sizes (38.5 to 52.5) had a price, and none were marked `disabled` in the AX tree. The ones I inspected had `data-testid="pdp-stockAvailable-label"`. I did not see how a sold-out size renders, so whether it gets `disabled` or a different label is unverified.

## What the snapshot actually contains [E2]

| | chars |
|---|---|
| snapshot before the click | 66,481 |
| snapshot after the click | 69,279 |
| `button "Maat kiezen" expanded` | at 3,861 |
| `[448] listbox` (the size list) | at **64,999** |
| `MAX_CHARS` cut | 14,000 |

The size list as `snapshot()` renders it, once you get past the cut:

```
[448] listbox
  tablist
    [449] tab "EU" selected
    [450] tab "Maat op label"
  form
    listitem
      [451] checkbox "38.5 € 95,95 Nu met korting"
      LabelText
        [t662] StaticText "38.5"
        [t663] StaticText "€ 95,95"
    listitem
      [452] checkbox "39 € 95,95 Nu met korting"
  …20 sizes, through [470] checkbox "52.5 € 95,95 Nu met korting"
```

- The AX path from the listbox to the root is `listbox < generic < generic < none(ignored) < … < RootWebArea`: nothing on it is skipped in a way that hides children.
- The button carries the relation `controls → relatedNodes[{backendDOMNodeId: 6044}]`, and 6044 is the listbox's `backendDOMNodeId`. So the AX tree already links the expanded trigger to its popup.
- The first 14,000 chars run out partway through the "Maak de look compleet" and "Varianten van deze look" carousels. From char ~5,000 to ~62,000 the snapshot is almost all product tiles from "Maak de look compleet", "Varianten van deze look", "Meer van Nike Sportswear" and "Vaak gezien met" (headings `[t40]` through `[t376]`, 309 links in total).
- This matches the smoke run in `docs/acceptance.md`: the model saw `expanded` (char 3,861) but never the list. The "Node is detached" errors in that run are a separate re-render problem (#37).

## Is this a Zalando quirk? [E2]

The cause has two parts. Only the first is a Zalando choice.

1. **The popup is portalled to the end of `<body>`.** This is the default in the common React UI kits (Radix `Popover/Select.Portal`, MUI `Popper`/`Menu`, Headless UI, React Aria `Overlay`). Any custom dropdown, date picker or modal built on them lands after the footer in document order. I did not verify this on other shops in this session.
2. **The page is long.** Product pages carry big recommendation carousels, so any portal sits far past 14k chars.

Nike's PDP (`nike.com/nl/t/air-force-1-07-herenschoenen-n8juM1H2/CW2288-111`) is the counter-example. The size grid is inline, always rendered, and exposed as `[49] radio "EU 35.5"` … `[74] radio "EU 52.5"`, with a sold-out size marked `radio "EU 50" disabled`. It starts at char ~2,900 of a 6,516-char snapshot, so the agent can read stock per size on Nike today. (The carousels below had not lazy-loaded in this headless run, so a scrolled page would be longer, but the grid would still be near the top.)

So: not a Zalando quirk in kind. Portalled popups are common, and on any long page they fall past the cut. Ticket sites were not checked. Their seat maps tend to be canvas or iframes, which are a different gap (#20).

## What including it would cost

The listbox subtree is about **4,400 chars**, about 1.1k tokens. Its 20 checkbox and tab lines alone are about 1,100 chars. The `LabelText` → `StaticText` children repeat the checkbox's name (the dedupe only drops exact matches with the parent name), so about 3/4 of those 4,400 chars are duplicates.

Options, cheapest first:

1. **Print popups first.** Before the main walk, collect the `backendDOMNodeId`s in the `controls` relation of every node whose `expanded` is true, plus any `dialog`/`alertdialog` node. Walk those subtrees first (or right under their trigger) and skip them in the main walk. With kdriver's `AXNode.properties` this is about 15 lines in `snapshot()`. It costs the popup's own size (4.4k chars here) and only while a popup is open. A caveat: the popup then pushes 4.4k chars of the page off the tail.
2. **Also drop StaticText that is a substring of the parent's name**, not just an exact match. That takes the Zalando list from about 4,400 to about 1,500 chars. It is a one-line change to the existing dedupe, and it shrinks every snapshot on every site. Test it so that real labels don't disappear.
3. **Raise `MAX_CHARS` to fit.** That would take 70k chars (about 17k tokens) per step on this page, roughly 5× the input cost of every step on long pages. Not worth it for one dropdown.
4. **Cut carousels instead of the tail** (for example, cap the children of repeated `listitem`/`link` runs). This is the general fix for long pages, but it is bigger and harder to keep right. It is a separate ticket if wanted.

Option 1, with option 2 alongside it, fixes this case for about 15 to 20 lines of code and +1.5k chars only while a popup is open.
