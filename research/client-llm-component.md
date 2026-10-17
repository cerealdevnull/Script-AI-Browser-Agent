# How the desktop client implements `LlmComponent`

Research for [#121](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/121), 2026-10-17.

Source: `Cereal-Automation/desktop-client` `master` at `abc7256` (merge of
[desktop-client#114](https://github.com/Cereal-Automation/desktop-client/pull/114), "LlmComponent for scripts
with an app-wide API key", merged 2026-10-16; closes spec #103 and tickets #106-#113). SDK side:
[sdk#13](https://github.com/Cereal-Automation/sdk/pull/13) (SDK 1.14.0, merged 2026-10-16). No later commits
or open PRs touch the LLM code.

Paths below are relative to `cereal-client/src/main/java/com/cereal/client/` unless they start with `cereal-sdk/`.
Short names: `LlmHttp` = `infrastructure/data/datasource/network/llm/LlmHttp.kt`, `Anthropic` =
`.../llm/AnthropicLlmDataSource.kt`, `OpenAi` = `.../llm/OpenAiCompatibleLlmDataSource.kt`, `Component` =
`infrastructure/sdkcomponent/LlmComponentImpl.kt`, `CallEx` = `domain/model/exception/LlmCallException.kt`.

Call path: `LlmComponentImpl.complete` → `CompleteLlmRequestInteractor` (reads Settings → AI config fresh each
call, `NotConfigured` before any network call; `application/interactor/llm/CompleteLlmRequestInteractor.kt:23-26`)
→ `LlmProviderImpl` (routes by provider, validates tool args / response schema;
`infrastructure/provider/LlmProviderImpl.kt:37-66`) → `AnthropicLlmDataSource` or
`OpenAiCompatibleLlmDataSource` → `LlmHttp`.

## Prompt caching

- **Anthropic: automatic caching only.** The request body gets a single top-level
  `"cache_control": {"type": "ephemeral"}` (`Anthropic:89`; class doc `Anthropic:30-31` says "automatic prompt
  caching"). No per-block breakpoints on system, tools or the newest turn. Anthropic's automatic mode places the
  breakpoint on the last cacheable block, so each agent step reads the previous step's prefix from cache and writes
  the new tail. Default 5-minute TTL (no `ttl` set).
- **OpenAI-compatible (incl. OpenRouter): nothing.** `OpenAi:57-97` sets no `cache_control` anywhere. OpenAI and
  Gemini cache implicitly on their side; Anthropic models via OpenRouter get no cache breakpoints from the client.
- Cache usage is reported: Anthropic `cache_read_input_tokens` / `cache_creation_input_tokens` (`Anthropic:161-167`);
  OpenAI `prompt_tokens_details.cached_tokens` and `cache_write_tokens`, subtracted from `prompt_tokens`
  (`OpenAi:158-185`; the cache-write assumption is marked unverified at `OpenAi:159`).
- The rest of the Anthropic body is fixed too: `thinking: adaptive`, `output_config.effort: medium`
  (`Anthropic:79-88`). Scripts can't change these.

## Images next to tool results

Both providers accept `UserPart.Image` in the same `LlmTurn.User` as `UserPart.ToolResult`; the client rearranges it.

- **Anthropic:** one `user` message; parts are stable-sorted so `tool_result` blocks come first, then text/image
  blocks (`Anthropic:94-99`). `tool_result.content` is the result's text only (`Anthropic:131-138`); the image is a
  sibling `image` block in the same message, not inside the tool result.
- **OpenAI-compatible:** the turn is split: one `role:"tool"` message per tool result (text only; `isError`
  becomes an `"Error: "` prefix), followed by one `role:"user"` message carrying the remaining text and
  `image_url` data-URL parts (`OpenAi:99-132`, `OpenAi:143-148`). So the image reaches the model as a user
  message right after the tool messages.
- Not covered by tests: the component tests send images only in plain user turns
  (`cereal-client/src/test/.../AnthropicLlmComponentTest.kt:65`, `OpenAiCompatibleLlmComponentTest.kt:63`); no
  test mixes images and tool results, and PR #114's manual checklist leaves the Anthropic image smoke test unticked.

## Retries and blocking time

All in `LlmHttp.execute` (`LlmHttp:102-123`).

- **Retried statuses:** 429 (`RateLimited`), 408 and 500-599 (`Unavailable`) (`LlmHttp:117`, `LlmHttp:141-152`).
- **Count and backoff:** fixed delays 1 s, 2 s, 4 s, so up to 4 attempts (`LlmHttp:218`). One `warn` to the
  app log (not the task log) per retry (`LlmHttp:119`).
- **`retry-after` is not honoured for the wait** (`ponytail:` note at `LlmHttp:120`). It is parsed (seconds or
  HTTP-date, `LlmHttp:164-173`) and passed through as `LlmException.RateLimited.retryAfter` once retries run out
  (`LlmHttp:146-147`, `Component:123-124`).
- **Network errors and timeouts are not retried**, despite the class doc (`LlmHttp:39-41`): any `IOException`
  (including `SocketTimeoutException`) throws `Unavailable` immediately from inside the loop (`LlmHttp:114-115`).
- **Timeouts:** shared `OkHttpClient` has 30 s connect/read/write
  (`infrastructure/di/modules/DataSourceModule.kt:66-72`); the LLM client overrides read to 10 minutes "for thinking
  models" (`DataSourceModule.kt:120-123`). No call timeout.
- **Worst case before an exception:** `RateLimited`/`Unavailable` from HTTP status: 4 responses + 7 s of backoff;
  each attempt can take up to 10 min of read time, so ~40 min theoretical. `Unavailable` from a read timeout: one
  attempt, ~10 min. A script wanting a tighter bound must wrap the call in its own `withTimeout`.
- A failed schema check (`InvalidOutput`) is never retried (`LlmProviderImpl.kt:30-36`).

## Exception messages

The client throws its own `LlmCallException` (a `CerealException`) and maps each to the SDK type, passing its
message through (`Component:113-147`). Messages are written for the user, point at Settings → AI, and name
**neither the provider nor the model** (except the `InvalidRequest` text, which is the provider's own):

| SDK exception | Message | Source |
|---|---|---|
| `NotConfigured` | "No AI provider is set up. Choose one in Settings → AI." | `CallEx:17` |
| `Authentication` | "The AI provider rejected the API key. Check it in Settings → AI." | `CallEx:20-23` |
| `RateLimited` | "The AI provider is rate limiting requests. Try again later." (+ `retryAfter`) | `CallEx:26-30` |
| `InvalidRequest` | provider's `error.message` verbatim, else "The AI provider rejected the request (HTTP n)." | `LlmHttp:154-158` |
| `InvalidRequest` (wrong base URL on `/models`) | "Not found: there are no models at this base URL. ..." | `LlmHttp:219` |
| `InvalidRequest` (assistant payload from the other provider) | "This conversation has a reply from a different AI provider. ..." | `LlmHttp:220-221` |
| `InvalidRequest` (schema doesn't compile) | "The tool \"x\" schema isn't a valid JSON Schema." / "The response schema ..." | `LlmProviderImpl.kt:68-77` |
| `InvalidOutput` | "The model's output doesn't match the requested schema." | `CallEx:51` |
| `Unavailable` | "The AI provider can't be reached right now. Try again later." | `CallEx:55-58` |
| `Unavailable` (unparseable 2xx) | "The AI provider sent a response Cereal couldn't read." | `LlmHttp:186` |

- An unknown model is `InvalidRequest` with the provider's message; the component also writes a task-log warning
  naming the configured model (`Component:45-47`).
- Gemini's 400 "API key" error maps to `Authentication` (`LlmHttp:136-140`).
- The SDK cause is the client exception; the SDK defaults for `Authentication`/`RateLimited`/`Unavailable` are
  `null` messages, so the client's text is what scripts see (`cereal-sdk/.../llm/LlmException.kt:20-65`).
- An uncaught `LlmException` fails the task as an expected error (no Sentry)
  (`application/task/TaskExecutor.kt:215-216`).

## Logging

`Component:58-63`: one task-log `info` line per billed call, including calls that end in `InvalidOutput`
(`Component:48-50`):

> AI call to `<model>` used `<n>` input, `<n>` cache read, `<n>` cache write and `<n>` output tokens.

`<model>` is the response's model id. Plus the missing-model `warn` (`Component:46`). No prompt/response content,
no cost, no step/latency. Retries go to the app log only (`LlmHttp:119`). A script should not log its own token
line per call.

## `LlmResponse.model`

Yes, the provider's id: the response body's `model` field, unmodified. Anthropic `MessageResponse.model`
(`Anthropic:168`, `:179`); OpenAI-compatible `ChatResponse.model` (`OpenAi:186`, `:215`). Passed through
`Component:109`. So it's whatever the provider echoes (`claude-opus-5-5` on Anthropic, OpenAI's id, OpenRouter's
`vendor/model` id). Providers that resolve aliases may echo a more specific id than the one configured in
Settings → AI; the client doesn't normalise it.

## Release

- Merged to `master` 2026-10-16 (#114, `abc7256`), on SDK `cereal-sdk = "1.14.0"` (`gradle/libs.versions.toml:44`).
- **Not in a released client yet.** The latest release is `cereal-client/1.15.0` (2026-09-23), before the merge.
  `gradle.properties` says `version=2.0.0` but "The CI pipeline sets the version", so the next version number is
  not fixed in the repo. `changelog.md` has no LLM / Settings → AI entry yet.
- PR #114's manual checklist is mostly unticked: only the Gemini OpenAI layer was smoke-tested live; Anthropic,
  OpenAI, Ollama/LM Studio, and a ProGuard release build with `json-schema-validator` were not.
- SDK 1.14.0 is tagged in `Cereal-Automation/sdk`.
