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

## Manual smoke check (not pass/fail)

A read-only stock/price monitor on a real sneaker product page, to find what breaks outside Toolshop.

1. In the Cereal client (or a scratch test copying `G1MonitorTest` without the stock flip), create a task:
   Run mode `Repeat`, interval 300, Anthropic, your key, model `claude-opus-5-5`, no secrets.
   - Goal: "Notify me when size 43 is in stock" (pick a size that is sold out), or "Notify me when the price drops
     below €X".
   - Start URL: a Nike (`nike.com/…/t/…`) or Zalando (`zalando.nl/…html`) product page.
2. Watch the first run in the visible Chrome and read the log. Let it loop two or three times.
3. Never buy, never add to a cart, never sign in.
4. Write down what you saw:

| Site / URL | Bot wall / CAPTCHA | Consent banner (dismissed? how) | Heavy JS / snapshot problems | Steps, $ for run 1 | Replay worked (0 calls)? | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |
