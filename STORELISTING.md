Describe a goal in plain language and a start URL, and the agent drives your installed Chrome until the goal is met, fails, or hits a limit you set. It runs on your own AI model and API key, either once or as a repeating monitor that notifies you when a condition holds.

## Getting started

1. Pick a provider and paste its API key (the key's description says where to get one).
2. Write a goal and a start URL.
3. Choose a run mode: Once does the task or answers the question, Repeat checks until a condition is true.
4. Start the task. A visible Chrome window opens, and the agent asks before it buys, pays or sends anything.

## Features

- Asks you to confirm before any irreversible step, such as buying, paying, deleting or sending
- **Repeat** checks the site on an interval and notifies you once when the condition holds; **Once** reaches the goal and notifies the result
- Repeat learns the steps on the first run and replays them later without calling the model; if the site changes, the model picks up where replay stopped
- Dollar and step limits per run and per task; cost is tracked for common Anthropic, OpenAI, Gemini and OpenRouter models, and shown as unknown otherwise
- Up to three secrets (e.g. email and password) typed into the site on request; the AI never sees their values, and they are removed from logs
- **On next start** lets you forget the learned steps, or also sign out, when a site changed or the agent keeps doing the wrong thing
- Navigation stays on the start URL's domain plus any domains you allow (e.g. a login or payment provider)
- Visible Chrome window with a separate profile per task, so sign-ins persist between runs
- Rejects cookie consent banners itself (never accepts all), so no steps or money go to them
- Stops when it repeats the same action without progress
- Works with Anthropic or any OpenAI-compatible server; your API key is sent only to the provider you choose

## Use Cases

- **Repeat**, wait for stock: notify me when Long Nose Pliers is in stock. (Just asking? **Once**: tell me whether Long Nose Pliers is in stock.)
- **Once**, read a value: tell me the price of the cheapest hammer.
- **Once**, a signed-in lookup: log in with my email and password and tell me the status of my last order.
- **Once**, fill in a form: fill in the contact form with my name and question and send it. The agent asks you before it sends.

## Requirements

- Google Chrome installed
- An API key from your provider:
  - Anthropic (the default, recommended): https://console.anthropic.com/settings/keys
  - Gemini: https://aistudio.google.com/apikey
  - OpenAI: https://platform.openai.com/api-keys
  - OpenRouter: https://openrouter.ai/settings/keys
  - or any OpenAI-compatible server, including local ones such as Ollama or LM Studio
- Model usage is billed by your provider; about $0.20 a run with the default model (an estimate); default limits are $2 per run and $5 per task, which you can change

Includes @duckduckgo/autoconsent v16.43.1 (MPL-2.0), source: https://github.com/duckduckgo/autoconsent
