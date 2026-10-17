Describe a goal in plain language and a start URL, and the agent drives your installed Chrome until the goal is met, fails, or hits a limit you set. It runs on the AI model you set up in the client, either once or as a repeating monitor that notifies you when a condition holds.

## Getting started

1. Set up your AI provider and model in Settings → AI.
2. Write a goal and a start URL.
3. Choose a run mode: Once does the task or answers the question, Repeat checks until a condition is true.
4. Start the task. A visible Chrome window opens, and the agent asks before it buys, pays or sends anything.

## Features

- Asks you to confirm before any irreversible step, such as buying, paying, deleting or sending
- **Repeat** checks the site on an interval and notifies you once when the condition holds; **Once** reaches the goal and notifies the result
- Repeat learns the steps on the first run and replays them later without calling the model; if the site changes, the model picks up where replay stopped
- Dollar and step limits per run and per task; cost is tracked for common models, and shown as unknown otherwise
- Sign-in secrets (e.g. email and password), as many as you need, typed into the site on request; the AI never sees their values, and they are removed from logs
- **Forget learned steps** (and optionally **sign out**) from the task's menu to start over when a site changed or the agent keeps doing the wrong thing
- Navigation stays on the start URL's domain plus any domains you allow (e.g. a login or payment provider)
- Visible Chrome window with a separate profile per task, so sign-ins persist between runs
- Rejects cookie consent banners itself (never accepts all), so no steps or money go to them
- Stops when it repeats the same action without progress

## Use Cases

- **Repeat**, wait for stock: notify me when Long Nose Pliers is in stock. (Just asking? **Once**: tell me whether Long Nose Pliers is in stock.)
- **Once**, read a value: tell me the price of the cheapest hammer.
- **Once**, a signed-in lookup: log in with my email and password and tell me the status of my last order.
- **Once**, fill in a form: fill in the contact form with my name and question and send it. The agent asks you before it sends.

## Requirements

- Google Chrome installed
- An AI provider and model set up in the client's Settings → AI
- Model usage is billed by your AI provider; default limits are $2 per run and $5 per task, which you can change

Includes @duckduckgo/autoconsent v16.43.1 (MPL-2.0), source: https://github.com/duckduckgo/autoconsent
