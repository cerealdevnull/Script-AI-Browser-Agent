# Default model and provider, and what a goal run costs

Research for #8 (map #6). Checked 2026-10-11 against the provider pages linked at each claim. Prices are USD per
million tokens, standard tier, short-context rate.

## How the script spends tokens

Read from the code on `master` (c69d50a):

- **Fixed prefix, about 2.1K tokens per step.** The system prompt (`Agent.SYSTEM_PROMPT`, about 1.1K chars), 12 tool
  definitions with strict schemas (`Agent.TOOLS`, about 3.4K chars of source), the goal message, and on Anthropic
  the tool-use system prompt the API adds (286 tokens on Opus/Sonnet/Haiku 5.5 with `tool_choice: auto`,
  [pricing page](https://platform.claude.com/docs/en/about-claude/pricing)).
- **Snapshot: up to 14,000 chars** (`Browser.MAX_CHARS`). That is about 4K tokens on OpenAI/Gemini tokenizers and
  about 5K on Claude, whose newer tokenizer "produces approximately 30% more tokens for the same text"
  ([pricing page](https://platform.claude.com/docs/en/about-claude/pricing)). Busy pages hit the cap.
- **Anthropic adapter: full history plus automatic caching.** `AnthropicClient` sends a top-level
  `cache_control: ephemeral`, and the history is append-only: every earlier snapshot and thinking block stays in
  the prompt. With automatic caching, each request reads the previous prompt from cache and writes only the new
  tail ([prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)). The prompt grows
  by about 5.4K tokens per step: a 20-step run ends near a 110K-token prompt. Effort is fixed at `medium` with
  adaptive thinking, and the request always carries `fallbacks: "default"` plus the
  `server-side-fallback-2026-07-01` beta header.
- **OpenAI-compatible adapter: trimmed history, provider-side caching only.** `OpenAiCompatibleClient` keeps the
  first snapshot and the last two tool-result snapshots whole and replaces older ones with
  `[older snapshot removed]`. The prompt stays at about 14-15K tokens. It sends no cache hints, so caching is
  whatever the provider does on its own. Trimming changes one old message per step, so only the prefix before it
  stays cacheable (about 6K tokens). The other ~8-9K are new input every step.
- **No measured numbers yet.** The results tables in `docs/acceptance.md` are still empty, so everything below is
  an estimate from the request shape. Running G1-G3 once will replace it.

### Cost model

Per step: 400 output tokens (a tool call plus some adaptive thinking at `medium`). The same turn is echoed back as
input on the next step. Anthropic: step 1 writes the whole prompt to cache. Step n reads step n-1's prompt and
writes the new tail at the 5-minute write rate (1.25x input). OpenAI-compatible: the cached prefix is billed at the
cached rate and the rest at the input rate, or at 1.25x on GPT-5.6 and later, which charge for cache writes
([OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)). If thinking runs at
1,500 tokens instead of 400, add 1.1K output tokens per step: +$0.011/step on Sonnet 5.5 and +$0.0006 on
Haiku 5.5.

## Shortlist

| Provider (adapter) | Model id | $/M input | $/M cached input | $/M output | $/step (10-20 steps) | $ per 10-step run | $ per 20-step run |
|---|---|---|---|---|---|---|---|
| Anthropic | `claude-opus-5-5` (today's default) | 4.00 | 0.20 (write 5.00) | 20.00 | 0.041-0.046 | 0.41 | 0.92 |
| Anthropic | `claude-sonnet-5-5` | 2.00 | 0.10 (write 2.50) | 10.00 | 0.021-0.023 | 0.21 | 0.46 |
| Anthropic | `claude-haiku-5-5` | 0.10 | 0.01 (write 0.125) | 0.50 | 0.0012-0.0022 | 0.012 | 0.043 * |
| Google (OpenAI-compat) | `gemini-3.8-flash` | 0.75 (1.50 from 2027-01-01) | 0.075 | 3.75 (7.50 from 2027) | 0.008 (0.016 in 2027) | 0.076 | 0.16 |
| Google (OpenAI-compat) | `gemini-3.5-flash-lite` | 0.30 | 0.03 | 2.50 | 0.0034-0.0036 | 0.034 | 0.073 |
| OpenAI (OpenAI-compat) | `gpt-5.6-luna` | 0.20 | 0.02 | 1.20 | 0.0025 | 0.025 | 0.053 |
| OpenAI (OpenAI-compat) | `gpt-6-luna` (needs a fix, see below) | 0.10 | 0.01 | 0.50 | 0.0012 | 0.012 | 0.026 |
| OpenRouter (OpenAI-compat) | `anthropic/claude-sonnet-5.5` | 2.00 | 0.10, but unused | 10.00 | 0.037-0.041 | 0.37 | 0.82 |
| OpenRouter (OpenAI-compat) | `anthropic/claude-haiku-5.5` | 0.10 | 0.01, but unused | 0.50 | 0.0019-0.0021 | 0.019 | 0.041 |

\* Haiku 5.5 charges 5x on every token of a prompt over 100,000 tokens
([Haiku 5.5 page](https://platform.claude.com/docs/en/models/haiku-5-5/overview)). On the Anthropic adapter the
prompt passes 100K at about step 19, so a 20-step run costs $0.043 rather than the $0.028 the flat rate gives. A
40-step run costs about $0.29, not $0.08.

Sources for the rates:
- Anthropic: [pricing](https://platform.claude.com/docs/en/about-claude/pricing). Cache reads cost 0.05x input on
  Opus 5.5 and Sonnet 5.5 and 0.1x on the others.
- OpenAI: [pricing](https://developers.openai.com/api/docs/pricing).
- Google: [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing). Gemini 3.8 Flash and 3.6 Flash double on
  2027-01-01.
- OpenRouter: the [model list](https://openrouter.ai/api/v1/models) shows the same per-token rates for every row
  above. The 5.5% credit-purchase fee is on top.

The Gemini rows assume implicit caching hits (4,096-token minimum on 3.8 Flash,
[caching docs](https://ai.google.dev/gemini-api/docs/caching)). If it does not apply through the OpenAI endpoint,
or the endpoint does not report `cached_tokens`, the upper bound is $0.12 / $0.27 per 10 / 20 steps on 3.8 Flash
and $0.05 / $0.11 on 3.5 Flash-Lite.

The current cap calibration ("Gemini 3.5 Flash-Lite at about $0.0015/step", `docs/acceptance.md`) is about half of
this model's $0.0035. The calibration may have assumed a smaller snapshot.

Repeat mode: a learned run replays with 0 model calls, so a monitor costs one learning run, plus a heal now and
then.

## Tool-use reliability

There are no measured runs of this script yet, so this rests on vendor benchmarks and API constraints.

- **Opus 5.5 / Sonnet 5.5.** OSWorld 2.1 (partial scoring): Opus 5.5 81.8%, Sonnet 5.5 80.1%
  ([Sonnet 5.5 announcement](https://www.anthropic.com/claude-sonnet-5-5)). Both accept this adapter's request
  as-is: adaptive thinking, `effort: medium`, strict tools, `tool_choice: auto`. For Sonnet 5.5, Anthropic's
  guidance is "for agentic coding and multistep tool use, start with `medium`"
  ([effort](https://platform.claude.com/docs/en/build-with-claude/effort)). Both have server-side fallback.
- **Haiku 5.5** (released 2026-10-07). OSWorld 2.1 offline subset: 72.4%, against 83.9% for Sonnet 5.5 and 48.9% for
  GPT-6 Luna on the same subset ([Haiku 5.5 announcement](https://www.anthropic.com/claude-haiku-5-5)). Anthropic
  says it "works especially well for speed-sensitive tasks like live customer support and browser use". The effort
  docs warn that in long agent prompts it "is more likely to skip a search, stop early, or skip a check at `low`".
  This adapter uses `medium`. **Open risk:** "Claude Haiku 5.5 has no server-side fallback"
  ([refusals and fallback](https://platform.claude.com/docs/en/build-with-claude/refusals-and-fallback)). The docs
  do not say whether a request that still sends `fallbacks: "default"` is ignored or rejected with a 400. Check
  with one call before Haiku becomes a default.
- **OpenAI GPT-6 line through Chat Completions: broken today.**
  - `gpt-6-sol` and `gpt-6-luna`: "Chat Completions supports function calling only with `reasoning_effort` set to
    `none`" ([gpt-6-luna](https://developers.openai.com/api/docs/models/gpt-6-luna),
    [gpt-6-sol](https://developers.openai.com/api/docs/models/gpt-6-sol)). The adapter sends no
    `reasoning_effort`, and the default is `medium`.
  - `gpt-6.1-sol`: "Chat Completions is supported without tool calling"
    ([gpt-6.1-sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol)). It cannot run this adapter at all.
  - `gpt-5.6-luna` lists function calling with no such restriction
    ([gpt-5.6-luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna)).
- **Gemini through the OpenAI endpoint.** Function calling is supported, but "Support for the OpenAI libraries is
  still in beta", and reasoning cannot be turned off on Gemini 3 models
  ([OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)). The adapter already echoes the assistant
  message as received, which carries Gemini's thought signatures. Google calls 3.8 Flash "our most intelligent
  Flash model" for autonomous agents, and 3.5 Flash-Lite "optimized for high-volume agentic tasks"
  ([pricing](https://ai.google.dev/gemini-api/docs/pricing)).

## Getting a key (non-technical user)

| Provider | Free? | Card needed? | Notes |
|---|---|---|---|
| Google AI Studio | Yes: a free tier on 3.8 Flash, 3.6 Flash, 3.5 / 3.1 Flash-Lite | No for the free tier. Tier 1 needs "an active billing account" ([rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)) | Easiest: AI Studio "automatically creates a default Google Cloud project and API key after you accept the Terms of Service" ([API key](https://ai.google.dev/gemini-api/docs/api-key)). But on the free tier, "Content used to improve our products" ([pricing](https://ai.google.dev/gemini-api/docs/pricing)), and this agent sends logged-in pages (orders, invoices). Free-tier RPM/RPD are only shown inside AI Studio. The user must also switch Provider and Base URL away from today's defaults. |
| Anthropic Console | "New users receive a small amount of free credits" ([pricing FAQ](https://platform.claude.com/docs/en/about-claude/pricing)) | Yes for real use. The minimum top-up is $5, per third-party guides; no first-party page found | It works with today's defaults (Provider = Anthropic, no Base URL). |
| OpenAI | No free API tier documented | Yes: prepaid, $5 minimum, credits expire after a year ([help.openai.com 8264778](https://help.openai.com/en/articles/8264778-what-is-prepaid-billing)) | Needs Provider = OpenAI-compatible. GPT-6 models need an adapter fix. |
| OpenRouter | Some `:free` models with tools (Gemma 4, Nemotron 3); 50 requests/day without credits ([FAQ](https://openrouter.ai/docs/faq)) | Yes for paid models: card, AliPay or USDC; 5.5% fee ($0.80 minimum) on card purchases | One key for every vendor. Prices pass through "the same rate as you would directly with the provider". |

## OpenRouter as a single-key option

It works through the OpenAI-compatible adapter, with three gaps:

1. **No caching for Claude.** OpenRouter caches Anthropic only when the request carries `cache_control`, top-level or
   per block ([OpenRouter prompt caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching)), and
   `OpenAiCompatibleClient` never sends it. The trimmed history keeps this from blowing up: Sonnet 5.5 costs about
   $0.82 per 20 steps through OpenRouter, against $0.46 direct. OpenAI and Gemini models cache automatically there.
2. **Claude runs are unpriced.** OpenRouter ids use dots (`anthropic/claude-haiku-5.5`). `Prices.cost` strips the
   `anthropic/` prefix and looks up `claude-haiku-5.5`, which matches no key (`claude-haiku-5-5`). The script then
   counts $0 and logs "only the step cap applies", so the dollar caps never fire. The `openai/gpt-…` and
   `google/gemini-…` ids do match. This is inferred from the model list and `Prices.kt`. The exact `model` string
   in an OpenRouter response was not captured.
3. **GPT-6 tool calls.** It is not verified whether OpenRouter lifts the Chat Completions tool-calling restriction
   on `openai/gpt-6-*`. Its model list marks `tools` as supported for all of them.

## Prices.kt check

Every shortlisted id is in the table. What is wrong or missing:

- **`claude-sonnet-5-5` cache read is wrong.** The table has `0.20`. The list price is $0.10 (0.05x input). This
  overstates Sonnet 5.5's cache-read cost 2x.
- **The Haiku 5.5 >100K-token rate is wrong to ignore.** The `ponytail:` comment says snapshots "stay well below
  it". That holds for each snapshot, not for the prompt. On the Anthropic adapter the history keeps every snapshot,
  so the prompt passes 100K at about step 19 and the cost is undercounted 5x from there.
- **OpenRouter Claude ids (dotted) don't match**, as covered above.
- **GPT-5.6+ cache writes (1.25x) are not modelled.** The OpenAI adapter prices all uncached prompt tokens at 1.0x
  input, so uncached tokens are undercounted by up to 25%. OpenAI reports writes as
  `input_tokens_details.cache_write_tokens` on the Responses API. The Chat Completions field was not found.
- **Gemini 3.8 / 3.6 Flash 2027 rates are absent.** The comment notes "rates until 2026-12-31". From 2027-01-01
  these models cost 2x.
- **Not in the table (not on the shortlist):** `gpt-5.2`, `gpt-5.1`, `gpt-4.1-nano`, `o3`, `o4-mini`,
  `claude-opus-4-5`, `claude-sonnet-4-5`, and the limited-access `claude-mythos-5`/`-5-1`.

## Recommendation (for the grilling ticket to decide)

**Default: Provider Anthropic, model `claude-sonnet-5-5`.** It is about 2x cheaper than Opus 5.5 per run ($0.21 /
$0.46 for 10 / 20 steps), close to Opus on computer-use benchmarks, and Anthropic's own guidance names `medium` for
multistep tool use. It accepts the current request unchanged, server-side fallback included, so the switch is a
one-line change to `AgentConfiguration.model()` plus the Prices.kt cache-read fix.

Caps that fit it: **40 steps / $2 per run / $5 per task.** A 40-step run costs about $1.13 at 400 output
tokens/step and about $1.60 with heavy thinking, so $2 stops only runaways. A typical 20-step run uses about a
quarter of the cap, within the "half to a hundredth" band in `docs/acceptance.md`. $5 per task covers a learning
run, a few heals and a re-learn.

**Runner-up: `claude-haiku-5-5`,** 10-17x cheaper ($0.01-0.04 per run). Anthropic positions it for browser use,
but it is four days old, scores lower on OSWorld (72% against 84% for Sonnet 5.5) and has no server-side fallback.
Make it the default only after G1-G3 pass on it and the `fallbacks` question is settled. Caps for it:
**40 steps / $0.50 per run / $2 per task.**

**Onboarding path, not the default: Gemini 3.8 Flash on the free tier,** for users without a card. Point out that
free-tier content trains Google's models.

Before either switch: run the G1-G3 acceptance suite on the chosen model and fill in the results and cap tables in
`docs/acceptance.md`. This doc is an estimate until then.
