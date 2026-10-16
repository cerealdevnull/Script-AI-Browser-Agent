---
name: field-test
description: Field-test the script with a field-test JSON file (goal, start URL, expected result), judge the run against it, and rank what to improve.
disable-model-invocation: true
---

# Field test

A **field test** runs the real script (real Chrome, a real cheap model) on one goal and start URL, then judges what
it did against the **oracle**: the result the user expects. The input is a **field-test file**,
`field-tests/<id>.json`; the output is a ranked list of improvements to the script, each traced to code. Vocabulary
(goal run, Check, replay cache, miss, heal…) is in `CONTEXT.md`.

## 1. Read the field-test file

The fields and their defaults are the `FieldTest` class in `src/test/kotlin/com/cereal/script/aiagent/SmokeTest.kt`;
`field-tests/toolshop-invoice.json` is a complete example. When the user gives a goal and URL instead of a file,
write the file for them and show it.

Hold each field to its job:

- **mode**: a question goal is `Once`; a condition goal is `Repeat` (which defaults to 3 runs: one learn run and two
  replays). A goal that reads both ways is a question for the user.
- **expected** is the oracle: each line checkable against `notifications.txt` and `log.txt`. For `Once`, the actual
  value the notification must say ("the price is €89,99"). For `Repeat`, whether the condition holds now (run 1
  notifies, or stays silent and returns `Loop`) and that replays make 0 model calls. Rewrite a fuzzy line ("it should
  work") with the user; open the start URL in the browser pane to find the real value when they don't know it.
- **secrets** maps each sign-in secret label (`email`, `password`) to the environment variable that holds
  its value. The file holds names only, so it can be committed.

Done when the file passes `./gradlew test --tests '*FieldTestFileTest'` and every `expected` line is checkable.

## 2. Run the script

```bash
SMOKE_FILE=field-tests/<id>.json ./gradlew acceptanceTest --tests '*SmokeTest'
```

- It runs on `gemini-3.8-flash` (cents per run) and needs `GEMINI_API_KEY` in the environment, plus every variable
  the file's `secrets` names. This session's shell loads the user's shell profile. When the test reports *skipped*
  (no key) or fails with `… is not set`, ask the user to add those exports to their profile and restart the session,
  or to run the command in their own terminal and tell you when it's done. Values stay out of chat, files and your
  commands; you work with variable names only.
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

A file with secrets carries one more oracle line: no secret value reaches the model or the log. Check it with a count,
so the value stays off screen, once per variable the file names:
`grep -cF -- "$<VARIABLE>" build/smoke/<id>/traffic.json build/smoke/<id>/log.txt`. Any count above 0 is a
**missed** line and ranks first in the report.

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
