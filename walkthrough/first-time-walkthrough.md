# First-time walkthrough: friction log

For [First-time walkthrough: where does a new user get stuck?](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/9).

## Result (2026-10-11)

The live walkthrough hit **every suspected friction point** below. Nothing beyond them was reported. No per-step
notes, screenshots or costs were recorded, so the Expected text below stands as the record of what was seen.

Friction, ranked by the map's rule (first-run friction first: setup, then validation, then first result; ties go to
risk of lost trust or money), and the ticket that owns each item:

1. **Setup:** API key looks optional but is required. Base URL comes before the key. "Model id" is jargon. Defaults
   (Opus, $3 / $10) don't match the decided Sonnet with $2 / $5. Secret, interval and Re-learn fields show on goals that
   don't need them. Secret names are unclear. → [How should the form get a first-time user…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/10),
   [What cost caps and cost signals…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/18)
2. **Validation:** config errors show only after the start, one at a time. A wrong key or model fails only after
   Chrome opens, and both get the same message. A URL without `https://` is rejected.
   → [When and how should the script report a bad configuration?](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/11)
3. **First result:** "Goal not met" reads like a failure for a question goal. "steps" is jargon. The cap message
   doesn't say what to raise. → [What should each failure and stop message tell the user to do?](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/12),
   [How should goal wording fit the run mode…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/34)
4. **Money, Repeat:** a question goal in Repeat learns no Check, so every check pays for model steps.
   → [How should goal wording fit the run mode…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/34)
5. **Money, Re-learn:** Re-learn stays on and silently relearns on every start.
   → [How should a user reset what was learned…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/17)
6. **Trust, Repeat:** silence after the learned run; learned and replayed runs look alike.
   → [How does a Repeat monitor show it is alive…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/15)
7. **Trust, confirmation:** two Chrome windows, raw element text, leaving the Tasks screen declines, and a Decline ends
   as a red Error. → [What should the confirmation dialog look like…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/16)
8. **Status line:** long one-line step text, developer-style log lines.
   → [What should the status line show while a run is working?](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/13)

**How to use this:** each step lists what the code says you will see (**Expected**) and the friction the code
suggests (**Suspected friction**). During the live run, fill in **Observed** with what really happened, what confused
you, and a screenshot. Strike out a suspicion the run disproves. Add anything the code didn't predict. The live
observations are the evidence. The expectations only save you typing.

Expectations come from `master` @ `c69d50a` (`AgentConfiguration.kt`, `AiAgentScript.kt`, `Agent.kt`, `Http.kt`) and
the SDK research ([What can Cereal SDK 1.12.0 do…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/7)).

## Before you start

- Cereal client with the script jar loaded (`./gradlew scriptJar` → `build/cereal/release.jar`), Chrome, and a real
  Anthropic API key.
- Play the persona: a Cereal bot user who has an API key and doesn't know what a Base URL or model id is. Fill the form
  **only** from what it says.
- Step 4 uses the shared Toolshop demo account (`customer@practicesoftwaretesting.com` / `welcome01`, or
  `customer2@…` if that one returns 401). **Decline** the confirmation: Confirm places a real order.
- Note the cost of every run from its notification.

---

## 1. Once: "Tell me whether Long Nose Pliers is in stock"

Start URL `https://practicesoftwaretesting.com`, run mode Once.

**Expected: form.** 20 fields in one flat list, no sections (the SDK has none): Goal, Start URL, Run mode, Provider,
Base URL, API key, Model, Max steps per run, Max $ per run, Max $ per task, Repeat interval, Secret 1–3 name/value
(6 fields), Allowed domains, Re-learn. The manifest instructions box at the top says "pick a model and enter your own
API key". Defaults: Anthropic, model `claude-opus-5-5`, 40 steps, $3 per run, $10 per task, 300 s.

**Suspected friction**
- [ ] Base URL (with a paragraph of example URLs) sits *above* API key. A new user has to decide whether to fill it in.
- [ ] API key is nullable, so the form shows it as **optional**, but the default provider needs it. Leaving it empty
      passes Save and fails only after the task starts.
- [ ] The "Model" field asks for a "model id" with only one example. The default is Opus, not the Sonnet that
      [Which default model…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/8) recommends (about twice
      the cost). The caps ($3 / $10) differ from the recommended ones ($2 / $5).
- [ ] Repeat interval, all 6 secret fields, Allowed domains and Re-learn show up on a Once goal that needs none of them.
- [ ] The Re-learn description mentions "the Chrome profile" without explaining what that is.

**Expected: run.** A visible Chrome window opens. The status line goes "Starting Chrome" → "Opening https://…" →
"Step 1: deciding the next action" → "Step 1: click link "…"" → … → "Goal met" or "Goal not met". One notification,
titled "AI agent: Tell me whether Long Nose Pliers is in stock". The body is "Goal met" or "Goal not met", then the
model's summary (≤ 300 chars), then e.g. "6 steps, $0.42".

**Suspected friction**
- [ ] For a question goal, **"Goal not met" reads like a failure** when the answer is "not in stock". Which way the
      model sets `goal_met` for "tell me whether…" is up to the model.
- [ ] "steps" means nothing to the persona. Is the $ figure reassuring or alarming?
- [ ] The status line is one ellipsized line: are the long "Step n: click link "…"" lines readable?
- [ ] Log panel: info lines like `run: Once, learned, 6 steps, $0.42, Goal met` look like developer logs.

**Observed**

- Form:
- Run / status line:
- Notification (paste it):
- Cost:
- Confusion:
- Screenshots:

---

## 2. Repeat: same product, learn, then replay twice

Same task in Repeat mode. Set Repeat interval to 60 to save time.

**Expected.** Run 1 learns with the model. If the pliers are out of stock, the status ends at "Goal not met" and the
task loops **with no notification**. Later checks replay without the model: "Replaying …" → "Replay: checking the
result" → "Goal not met", cost $0. The Chrome window **stays open** between checks. If the goal is already met on run 1,
you get one notification and the task ends with Success.

**Suspected friction**
- [ ] The goal is phrased as a question ("tell me whether"), but Repeat expects a condition ("notify me when … is in
      stock"). Does the form or the run tell you that? If the model can't record a Check, nothing is cached ("Repeat mode
      needs a check" only shows in the logs) and every check costs model steps again.
- [ ] After run 1 there is silence. Is the monitor alive? Did it learn anything? (feeds
      [How does a Repeat monitor show it is alive…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/15))
- [ ] Nothing visible separates a learned run from a replayed one, except log lines and $0.
- [ ] A Chrome window sitting open for the whole monitor. If you close it: a warn log line and Chrome reopens at the
      next check.

**Observed**

- Run 1 (learn): status, cost, notification?
- Replay 1:
- Replay 2:
- Did you know it was working?
- Screenshots:

---

## 3. Failures on purpose

Run each from the step 1 task, with one change at a time.

| Change | Expected notification body | Suspected friction |
|---|---|---|
| Wrong API key | `Failed: Model/API key rejected: HTTP 401: invalid x-api-key` + `0 steps, $0.00` | Shown only after Chrome opened and the page loaded. No hint what to do or where to get a key. |
| Empty API key | `Failed: API key is required for Anthropic` | Save allowed it. Only one config error is reported per start. |
| Wrong model id (e.g. `claude-sonet-5-5`) | `Failed: Model/API key rejected: HTTP 404: model: …` | Key and model errors share one message: which one is wrong? |
| Bad start URL, not a URL (`practicesoftwaretesting.com` without `https://`) | `Failed: Start URL must be an http(s) URL` | A common paste fails. Could the script just add `https://`? |
| Bad start URL, typo domain (`https://practicesoftwaretestin.com`) | The load error goes to the model as "Current page (error: …)". Expect the model's own `fail` reason, or `Page load failed: …` | The wording depends on the model. Does it tell you the URL is wrong? |
| Max steps per run = 2 | `Stopped: Step/cost limit reached: max steps per run (2)` + `2 steps, $…` | Doesn't say whether the run was close, or what to raise and to how much. The task ends in a red Error. |

**Observed**

| Change | What you saw (status, notification, task state) | Confusion |
|---|---|---|
| Wrong API key | | |
| Empty API key | | |
| Wrong model id | | |
| Start URL without `https://` | | |
| Typo domain | | |
| Max steps = 2 | | |

---

## 4. Confirmation: buy, then Decline

Goal "Log in and buy one Claw Hammer, paying cash on delivery". Secret 1 `email`, Secret 2 `password` (demo account
above).

**Expected.** The status shows "Waiting for your confirmation: click button "…" on …", which the host then replaces with
"Waiting for browser interaction...". A "Confirmation needed: …" notification arrives. A **second Chrome window** opens
with a plain page: "Confirm this step?", an Action/Element/Page/Reason table, and two links, Confirm and Decline. Decline
sends a "Declined by user" + cost notification, and the task ends as **Error** ("declined by user").

**Suspected friction**
- [ ] Setting up secrets: does the persona know to type `email` and `password` as names? What does "the name the agent
      sees" mean to them?
- [ ] Is it clear which Chrome window is the dialog and which is the agent's?
- [ ] Is the Element text (accessibility role + name) enough to know what you are agreeing to? Is the amount or order
      visible?
- [ ] Leaving the Tasks screen while the dialog is open counts as **Decline**.
- [ ] A deliberate Decline shows as a red Error task.

**Observed**

- Secrets setup:
- Dialog (screenshot):
- What you thought you were confirming:
- After Decline:

---

## 5. Restart a Repeat task with Re-learn left on

Turn Re-learn on for the step 2 task, start it, stop it, start it again without touching the form.

**Expected.** Each start silently deletes the replay cache, so every start learns again with the model, at full cost.
No status or notification says that it forgot anything. The Chrome profile (cookies, logins) is kept.

**Suspected friction**
- [ ] Re-learn is a sticky switch: left on, it relearns on every start.
- [ ] Nothing tells you a relearn happened, apart from the cost.
- [ ] There is no way to reset the Chrome profile from the script (feeds
      [How should a user reset what was learned…](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/17)).

**Observed**

- Start 1:
- Start 2:
- Did you notice it relearned?

---

## Summary (fill in after the run)

Top friction points, worst first, with the step each came from:

1.
2.
3.

Anything the code didn't predict:

Total spend on the walkthrough:
