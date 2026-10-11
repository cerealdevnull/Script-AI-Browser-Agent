# Acceptance runs (#81)

Opt-in runs that prove the three goals end to end with a real `claude-opus-5-5` on Toolshop
(`practicesoftwaretesting.com`). They are tagged `acceptance`: `./gradlew test` never runs them. Each one
**costs real money** and skips itself when `ANTHROPIC_API_KEY` is not set.

Every run drives the real script (real Chrome, real Anthropic adapter). The script's Base URL points at a local
pass-through that forwards to `api.anthropic.com` and keeps every request and response. The tests use it to check
that the password never left the machine, that `fallbacks: "default"` was accepted, and that later steps read from the
prompt cache. Each goal run prints a table row (`ACCEPTANCE | …`) and appends it to `build/acceptance/results.md`.
Paste those rows into the tables below.

## Setup

- Chrome installed (as for `./gradlew test`).
- `export ANTHROPIC_API_KEY=sk-ant-…`
- G2/G3 use the published demo customer `customer@practicesoftwaretesting.com` / `welcome01`. The demo is shared,
  and others sometimes change that password (on 2026-10-10 it returned 401). If the test fails at login, use another
  published account:
  `export TOOLSHOP_EMAIL=customer2@practicesoftwaretesting.com TOOLSHOP_PASSWORD=welcome01`.
- G1 needs a self-hosted Toolshop in docker (about 1.5 GB of images):

  ```bash
  docker compose -f docker/toolshop.yml up -d
  # first start only, once the API container is up: create and seed the database
  docker compose -f docker/toolshop.yml exec laravel-api php artisan migrate:fresh --seed
  # check: UI on http://localhost:4200, API on http://localhost:8091/products
  ```

  The test sets the stock of Long Nose Pliers itself: `UPDATE products SET stock = … WHERE name = 'Long Nose Pliers'`
  in MariaDB, then `php artisan cache:clear`, because the API caches products for 5 minutes. The API's `in_stock`
  flag is `stock > 0`. The seed has the pliers at 0, and the test puts it back to 0 when it ends. Other ports or
  hosts: `TOOLSHOP_LOCAL_URL`, `TOOLSHOP_LOCAL_API`.

## Running

```bash
./gradlew acceptanceTest --tests '*G1MonitorTest'    # self-hosted Toolshop
./gradlew acceptanceTest --tests '*G2LoginReadTest'  # public demo, 2 tests
./gradlew acceptanceTest --tests '*G3BuyTest'        # public demo; Accept places a real (cash on delivery) order on the shared account
./gradlew acceptanceTest                              # all of them
```

What each test asserts:

| Test | Asserts |
|---|---|
| G1 | Repeat, "Notify me when Long Nose Pliers is back in stock". Run 1 returns `Loop` and sends no notification. Runs 2..N (`ACCEPTANCE_G1_REPLAYS`, default 3) return `Loop` with 0 model calls. After stock is set > 0: one notification naming the product, then `Success`. |
| G2 Once | "Log in and tell me the invoice number, date and total of my most recent order". One notification that contains the latest invoice's number, date (`yyyy-mm-dd`) and total, read from the Toolshop API after the run. The password never appears in captured model traffic. |
| G2 Repeat | A monitor goal that needs the same login ("…notify me when the invoice number of my most recent order is no longer INV-…"), because Repeat needs a not-yet Check. Run 1 learns (`Loop`). A second task start, with a fresh logged-out Chrome profile, replays the login from the cache with 0 model calls and **0 confirmations**. |
| G3 Decline | "Log in and buy one Claw Hammer, paying cash on delivery". One confirmation appears and is declined: `Error("declined by user")`, and the API shows the same invoice count as before. |
| G3 Accept | Same goal, every confirmation accepted: `Success`, at least one confirmation shown, exactly one new invoice holding exactly one Claw Hammer. |
| all | Every request carried `fallbacks: "default"` and the API accepted it. In any multi-step run, some step after the first had cache-read tokens > 0. |

Columns of a results row: steps (model calls), $ (priced by the response `model` with `Prices`), cache-read tokens per
step, which fallback model answered if any, confirmations shown, and the `ExecutionResult`.

## Results

| Goal | Run | Steps | $ | Cache-read tokens per step | Fallback answered | Confirmations | Result |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

Date / script commit:

## Cap defaults

The defaults are 40 steps / $3 per run and $10 per task, calibrated on Gemini 3.5 Flash-Lite (about $0.0015/step).
After the Opus runs, compare:

| | Measured on Opus | Default | Change? |
|---|---|---|---|
| Steps, longest goal run | | 40 | |
| $ per step (mean) | | — | |
| $, most expensive goal run | | $3.00 | |
| $, a full task (learn + replays, or G3) | | $10.00 | |

If the measured cost is far off (a normal goal run uses more than about half of a cap, or a hundredth of it), change
the defaults in `AgentConfiguration.kt` and update spec #69.

## Smoke check on real sites (not pass/fail)

`SmokeTest` runs the real script on five real sites, to find what breaks outside Toolshop. It uses
`gemini-3.5-flash-lite` through the OpenAI-compatible provider (a few cents in total) and skips itself without
`GEMINI_API_KEY`. The goals are read-only and every confirmation is declined. For each site it writes the log,
the notifications and every model request and response to `build/smoke/<id>/`, to be judged afterwards.

```bash
read -s "GEMINI_API_KEY?Gemini API key: " && export GEMINI_API_KEY
./gradlew acceptanceTest --tests '*SmokeTest'
SMOKE_ONLY=nike,ad ./gradlew acceptanceTest --tests '*SmokeTest'   # a subset
```

Results on 2026-10-11 (`gemini-3.5-flash-lite`, script commit c69d50a). The Gemini free tier answered a few
requests with 429. The script retried them, so they caused no failures.

| Site / goal | Mode | Bot wall / CAPTCHA | Consent banner | Snapshot problems | Steps, $ per run | Replay (0 calls)? | Notes |
|---|---|---|---|---|---|---|---|
| Nike search page: "notify me when the men's Air Force 1 '07 costs less than €50" | Repeat ×3 | none | none seen | none (about 7k input tokens) | 3, 3, 3 steps; about $0.01 each | no, every run re-learned | Read the price (€ 119,99) correctly, but every proposed Check was true now (`textContains "€ 119,99"`, `textAbsent "€ 50"`). There is no way to express "less than". Verification rejected all of them, so nothing was cached. |
| Zalando search page: "notify me when the Air Force 1 '07 is in stock in size 48.5" | Repeat ×3 | none | dismissed in 1 step ("OK") | The size picker opened (`button "Maat kiezen" expanded`), but no sizes appeared in the snapshot. Three clicks failed with `Node is detached from document`. One text ref matched 29 nodes. | 14, 9, 8 steps; $0.03, $0.02, $0.02 | no | **False "Goal met" in run 3.** After "Check is true but goal_met is false", the model flipped `goal_met` to true. Its summary still said "out of stock". It sent a Success notification and the Repeat task ended. |
| ticketmaster.nl: "notify me when Ticketmaster lists any Taylor Swift event in NL" | Repeat ×2 | none | none seen | none | 3 steps, $0.01; then 0 | **yes** (search replayed, Check passed) | The Check (`textAbsent "Taylor Swift"` on one result) can't tell tribute acts from the real artist. |
| ad.nl: "tell me the headline of the top story" | Once | none | full-page DPG wall on `myprivacy.dpgmedia.nl` | none | 1 step, $0.00 | n/a | The wall is off-domain, so the click on "Akkoord" asked for a confirmation. The test declined it, and the run ended `Declined by user`. The model chose "Akkoord" (accept all), not "Instellen". |
| w3schools "Try it Yourself": read the link text in the result pane | Once | none | none | The result pane shows as a bare `Iframe` with no content. A ref the model took from a screenshot was unknown. | 10 steps, $0.02 | n/a | Clicking the link opened a new tab, and the agent stayed on the old page. It got there by navigating to the href itself. "Goal met" only because the same text was in the page's source-code editor. |

Failure kinds, ranked by impact on restock and price monitors:

1. **False "Goal met" after a rejected finish** (Zalando). A finish retry may flip `goal_met` to pass verification, so the user gets a false alert and the monitor stops.
2. **The goal can't be expressed as a Check, so nothing is cached and every run re-learns** (Nike, Zalando). Numeric conditions and "size X in stock" don't fit `textContains`/`textAbsent` on one element. Every Repeat run then costs a full learn run.
3. **Content missing from the snapshot.** Iframe content (w3schools) and a custom size dropdown (Zalando) are invisible, so the agent reasons from the wrong elements.
4. **Off-domain consent walls trigger a confirmation** (ad.nl). An unattended monitor stalls on a cookie wall, and the model accepts all cookies.
5. **Detached nodes on re-rendering pages** (Zalando). Clicks on a ref whose node was re-rendered fail and waste steps.
6. **New tabs are not followed** (w3schools). The agent stays on the old page.
7. **Ambiguous text refs in Readouts** (Zalando). A minor problem: verification catches it.

Not seen: bot walls or CAPTCHAs (Nike, Zalando and Ticketmaster all loaded in headed Chrome), oversized snapshots
(the largest step had about 12k input tokens), shadow DOM (unconfirmed: the Zalando dropdown may be one).
