Describe a goal in plain language and a start URL, and the agent drives your installed Chrome until the goal is met, fails, or hits a limit you set. It runs on your own AI model and API key, either once or as a repeating monitor that notifies you when a condition holds.

## Features

- Works with Anthropic or any OpenAI-compatible server (OpenAI, OpenRouter, Gemini, Ollama, LM Studio); your API key is sent only to the base URL you choose
- Two run modes: **Once** reaches the goal and notifies the result; **Repeat** checks the site on an interval and notifies once when the goal is met
- Repeat mode learns the steps on the first run and replays them later without calling the model; if the site changes, the model picks up where replay stopped and updates what it learned
- Step and dollar limits per run and per task, with cost tracked for common Anthropic, OpenAI and Gemini models
- Up to three named secrets (e.g. email and password) typed into the site on request; the model never sees their values, and they are removed from logs
- Navigation stays on the start URL's domain plus any domains you allow (e.g. a login or payment provider)
- Asks you to confirm before any irreversible step, such as buying, paying, deleting or sending
- Visible Chrome window with a separate profile per task, so sign-ins persist between runs
- Stops when it repeats the same action without progress

## Use Cases

- Check whether a product is in stock, or monitor until it is
- Read a value from a page, such as a price, order status or appointment slot
- Sign in to a site and report information from your account
- Fill in a form or complete a multi-step flow on a site you use

## Requirements

- Google Chrome installed
- An API key for Anthropic or an OpenAI-compatible provider, or a local OpenAI-compatible server (Ollama, LM Studio)
- Model usage is billed by your provider to your own account
