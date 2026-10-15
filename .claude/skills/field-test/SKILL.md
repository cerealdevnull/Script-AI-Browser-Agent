---
name: field-test
description: Field-test the script on a real site with a goal and start URL, judge the result against what the user expects, and rank what to improve.
disable-model-invocation: true
---

# Field test

A **field test** runs the real script (real Chrome, a real cheap model) on one goal and start URL, then judges what
it did against the **oracle**: the result the user expects. The output is a ranked list of improvements to the
script, each traced to code. Vocabulary (goal run, Check, replay cache, miss, heal…) is in `CONTEXT.md`.

## 1. Pin the oracle

From the user's message, collect:

- **goal** and **start URL**.
- **run mode**: a question goal is `Once`; a condition goal is `Repeat`. Infer it; ask only when the goal reads both ways.
- **oracle**: what a correct run produces, stated so a log can prove it right or wrong. For `Once`: what the
  notification must say (the actual value, e.g. "the price is €89,99"). For `Repeat`: whether the condition holds
  now (so run 1 should notify, or stay silent and return `Loop`), and that later runs replay with 0 model calls.

Done when every oracle line is checkable against `notifications.txt` and `log.txt`. A fuzzy oracle ("it should
work") gets one round of questions to the user; open the start URL in the browser pane to find the real value
yourself when the user doesn't know it.

## 2. Run the script

```bash
SMOKE_URL='<start url>' SMOKE_GOAL='<goal>' SMOKE_MODE=Once SMOKE_RUNS=1 SMOKE_ID=<short-slug> \
  ./gradlew acceptanceTest --tests '*SmokeTest'
```

- `Repeat` uses `SMOKE_RUNS=3`: one learn run and two replays.
- It runs on `gemini-3.8-flash` (cents per run) and needs `GEMINI_API_KEY` in the environment. When the test
  reports *skipped*, the key is missing from this session's shell, which loads the user's shell profile. Ask the
  user to add the export to that profile and restart the session, or to run the command in their own terminal and
  tell you when it's done. The key stays out of chat, files and your commands.
- Under Claude Code, Chrome runs headless. When the log or snapshot shows a bot wall, CAPTCHA or an empty page,
  rerun with `-Pheadless=false` before calling it a script problem.
- Every confirmation is declined, so a goal that buys, sends or deletes stops at its confirmation. That is correct
  behaviour; judge everything up to it.

Done when `build/smoke/<id>/` holds `log.txt`, `notifications.txt` and `traffic.json`.

## 3. Judge each goal run

Read the three files:

- `notifications.txt`: what the user would have seen.
- `log.txt`: steps, replay hits and misses, Check verification, consent rejects, crashes (`SMOKE crashed`).
- `traffic.json`: every model request and response. The last `<page_content>` in a request is exactly what the model
  saw at that step; its tool calls are what it decided.

Give each goal run a verdict: **met** or **missed**, per oracle line, with the evidence quoted (a notification line,
a log line, a snapshot excerpt). Also note costs the user would feel even on a met run: steps, dollars, a `Repeat`
replay that called the model, a confirmation an unattended monitor would stall on.

Done when every oracle line of every goal run has a verdict with evidence.

## 4. Trace each gap to its root cause

For every missed line and every felt cost, find the first point where the run went wrong, then the code that let it:
the snapshot (`Browser.kt`), the prompt, tools or finish verification (`Agent.kt`), replay (`ReplayCache.kt`), the
notification (`ResultMessage.kt`), or a cap (`AgentConfiguration.kt`). Read the snapshot the model saw before blaming
the model: a model mistake on a snapshot missing the right element is a snapshot problem.

Check the gap against the known failure kinds in `docs/acceptance.md` ("Smoke check on real sites"); a match names
that kind, a new one gets its own.

Done when every gap points to a `file:line`, or to the prompt or tool text that allowed the model's wrong choice.

## 5. Report

Write the report in chat:

1. **Verdict**: one line per goal run (met / missed, steps, $).
2. **Improvements**, ranked by how much each closes the gap between the run and the oracle. Each one gives:
   symptom (quoted evidence), root cause (`file:line`), proposed change, and the fixture test that would go red on
   it today (`src/test/resources/fixtures/` with `ScriptedModel`, as the existing tests do).
3. **Smoke row**: the result as a row for the table in `docs/acceptance.md`, for the user to add.

Stop at the report. Which improvement to build is the user's call.
