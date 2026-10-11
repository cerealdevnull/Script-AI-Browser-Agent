# What Cereal SDK 1.12.0 gives the script for its UX

Research for [#7](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/7), part of map #6. Written 2026-10-11.

## Answer

The SDK can do more than the script uses today. A `StateModifier` on any config item can **hide or show fields** based on other
values, mark them required or optional, and **show a per-field error in the form before the run**. The host blocks Save until
those errors are fixed. The form has no sections, headers, placeholders or collapsible "advanced" groups. Enum labels come from
`toString()`. The status line is one ellipsized line, but the user can read every `statusUpdate` and every `info`/`warn`/`error`
log in the task's log panel. Notifications are title plus message on the desktop. Discord, Telegram and email can take richer
payloads, and every notification is kept in an in-app Notification center. `showHtml` opens a **separate, visible Chrome** with
the HTML injected. It ignores its `title` argument and has no size or timeout setting, and it is cancelled when the user leaves
the Tasks screen. The SDK exposes **no task id** and no custom actions, and `onStart` returning false shows only a generic
"Failed to initialize task.". This script always has exactly one task per script instance, so `preference()` (scoped per script
instance) works as a per-task store.

## Sources and how sure each claim is

| Tag | Source | What it proves |
|---|---|---|
| **[SDK]** | `cereal-sdk-1.12.0.jar` from the Gradle cache (`javap -p -v`), and [Cereal-Automation/sdk at tag `1.12.0`][sdk] (`bab8a47`, the same commit as `master`) | The API a 1.12.0 script can call. Confirmed. |
| **[Host]** | [Cereal-Automation/desktop-client][host], `main` at `d5eb0fe` (2026-10-09). It bundles `cereal-sdk = "1.12.0"` (`gradle/libs.versions.toml:43`) | How the desktop client renders the SDK. **Read in the code, not seen running.** `main` is 15 commits ahead of the latest release `cereal-client/1.15.0` (2026-09-23). Where those commits change behaviour, this file says so. |
| **[Docs]** | Cereal-Automation/Documentation (private), `docs/developers/**` at `375e145` | The documented contract. One line is stale (see "Config form"). |
| **[Inferred]** | My reading of the above | Not confirmed. Each one is flagged. |

## 1. Config form

**Field types and widgets** ([SDK] `Configuration.kt` KDoc; [Host] `ScriptConfigurationItemMapper.kt`, `ScriptConfigurationView.kt`):

| Return type | Widget | Notes |
|---|---|---|
| `String` / `String?` | single-line text field (`TextField` default `singleLine = true`) | A blank value is stored as "no value" (`StringTextFieldState.getValidatedValue`). There is no multi-line input, so the Goal field is a one-line box. |
| `Int`, `Float`, `Double` | text field with a type check ("not a number" errors) | No min/max annotation. Use `getError` for ranges. |
| `Boolean` | switch | An unset switch reads as `false`. |
| `Enum` | dropdown | **The label is `toString()`** (`DropdownField.kt:50,136`) and **the stored value is `name`** (`KeyValueRoomMapper.kt:163-166`). Overriding `toString()` therefore gives friendly labels without breaking stored values. A nullable enum gets a clearable dropdown. |
| `Secret` / `Secret?` | masked field with a reveal toggle | Shown as `***` in summaries. No default value is possible: a default is silently ignored. It cannot be the script identifier. |
| `List<T : ScriptConfigurationListItem>` | one card per row, Add, Import from CSV (5000 rows max) | No defaults and no pre-seeded rows. A non-null list has at least one row. `List<String>` was **removed** in 1.12.0 (sdk PR #6, "Remove List<String>…"). [Docs] `configuration.md` still mentions it, which is stale. |
| `Proxy`, `RandomProxy` | proxy-group dropdown | Not relevant here. |

**Layout** ([Host] `ScriptConfigurationView.kt:100-185`, `SettingsComponents.kt:220-262`):
- The manifest `instructions` appear in an info box at the top of the config screen ([Docs] `manifest.md`).
- Each visible item is one row. The left half shows the `name`, with a `*` when the item is required, and under it the
  `description` as small grey text with **URLs auto-linked** (`ClickableUrlText`). The right half holds the widget, with the
  `name` repeated as the field label.
- Items are sorted by `position`, then alphabetically by `name` (`ScriptConfigurationDefinitionBuilder.kt:198`).
- The footer reads "* indicates required field".
- The host adds a **Notification overrides** section (per-instance Discord, Telegram and email settings). It shows a "# of
  concurrent tasks" field only when an item uses `valuePerTask` or a proxy. This script has neither.
- **There are no sections, headings, separators, placeholders, tooltips or collapsible groups.** Ordering by `position` is the
  only grouping tool.

**Required vs optional** ([Host] `ScriptConfigurationItemDefinitionExt.kt`):
`isRequired = !isNullable || stateModifier.getVisibility(config) == VisibleRequired`. A non-null return type is always required.
A nullable one is optional unless its modifier says `VisibleRequired`.

**Defaults:** a Kotlin default body pre-fills the field ([Docs] `configuration.md`). If the user clears a defaulted field, the
script still gets the default at run time (`ConfigInvocationHandler.kt:170-175`).

**Conditional fields exist.** Use `@ScriptConfigurationItem(stateModifier = X::class)`, where `X` is an `object : StateModifier`
with `getVisibility(ScriptConfig): Visibility` (`VisibleRequired`, `VisibleOptional` or `Hidden`) and `getError(ScriptConfig):
String?` ([SDK] `statemodifier/StateModifier.kt`). The host re-evaluates visibility and required-ness on **every value change**
(`ConfigurationItemsFormSection.applyStateModifiers`). `ScriptConfig.valueForKey(key)` returns typed wrappers, or
`NullScriptConfigValue` when unset. A `Secret` arrives as `SecretScriptConfigValue`, so its format can be checked.

Pitfalls, all from [Host] or [Docs]:
- **An item that can be `Hidden` must have a nullable return type.** Otherwise it is required but invisible, and the form can't
  be saved ([Docs] `advanced-configuration.md`; the `isRequired` rule above).
- **Hidden fields keep their values, and the script still receives them.** The form saves every field's value whether or not
  it is visible (`getScriptConfigurationValues`). The script must ignore a hidden Base URL itself.
- **A hidden field's `getError` still blocks Save**, because `validateConfigurationForm` validates every field state, visible
  or not. Return `null` from `getError` while the field is hidden.
- A modifier must be a Kotlin `object`. The host reads `KClass.objectInstance`, which needs Kotlin metadata
  (`ScriptConfigurationDefinitionBuilder.kt:122-130`). [Inferred] This repo's `scriptJar` puts back only `AgentConfiguration*.class`
  after ProGuard strips metadata. So the modifier objects should be nested inside `AgentConfiguration`, as `AllowedDomain`
  already is, or the restore glob widened. Then confirm with `./gradlew scriptJarTest`. `proguard-rules/script.pro:66` already
  keeps `StateModifier` implementations.
- [Inferred] Modifiers run on the UI path on every keystroke. Keep them pure and fast, with no network calls, so they can't
  check that an API key works.

**Script identifier:** `isScriptIdentifier = true` on one non-secret item shows its value as the subtitle of the Tasks header and
in the "Copy from existing script" dropdown ([Host] `TasksViewStateBuilder.kt:67-70`, `ScriptConfigurationView.kt:113-121`).
This script doesn't set it today. Goal or Start URL are the candidates.

## 2. Validation

- **`onStart` returning false** puts the task into Error with the fixed text **"Failed to initialize task."** and logs "Failed
  to start script." ([Host] `TaskExecutor.kt:72-75`). The script cannot add any text of its own. The script's current workaround
  (return true, then fail in the first `execute()`) is still the only way to show a script-written message *at run time*.
- **A script can report config errors before the first run:** `StateModifier.getError` shows the message in red under the
  field ([Host] `FormField.kt`). It runs when the field loses focus and again on Save, and **Save stays blocked** until it returns
  null (`ScriptSelectionDialog.kt:111` → `ScriptConfigurationViewModel.validate`).
- Built-in checks with host-written messages: required ("A value is required" and similar) and number format.
- **Start-time checks:** creating or starting a script instance runs the full validation, modifiers included, and fails with
  "Invalid configuration for script provided, please correct the configuration." (`StartScriptInteractor` →
  `ScriptConfigurationValuesValidator`). Starting a *task* re-validates with `isTaskConfiguration = true`, which **skips modifier
  errors** ([Host] `ScriptConfigurationDefinitionExtensions.kt:47`, `TaskManager.kt:125`). [Docs/changelog] An invalid saved
  configuration, for example after a script update adds a required field, shows a warning icon on the script.
- [Inferred] Keep the script's own `validate()` as a backstop for configurations saved before the modifiers existed. Its
  messages then appear as the task's Error status.

## 3. Status and logs

- `statusUpdate(msg)` appends a `Running(msg)` entry to the task's status history (`TaskManager.kt:140-147`).
  `ExecutionResult.Loop(msg, …)` posts `msg` the same way. `Success`/`Error` messages become the final status, and a blank
  Success becomes "Script executed successfully." ([Host] `TaskExecutor.kt:93-101`).
- **The task row shows only the latest status, on one line with an ellipsis** (`TaskRow.kt:110-113`, `maxLines = 1`). The
  row also shows a status chip and a spinner while running. The host sets no length limit.
- **History:** a status with the same text and type as the previous one is dropped. The last **250** statuses per task are
  kept and persisted (`JobTask.kt:33-55`).
- **The user sees `logger()` output.** Selecting a task opens a log panel. It merges the task's logger events (persisted per
  task, last **500**, `RoomLogEventDataSource.kt:57`) with its status history, sorted by time (`TasksViewModel.kt:514-536`).
  `error`, `warn` and `info` always show. `debug` shows only with the "Show debug logs" setting. A `Throwable` passed to `error`
  adds its stack trace. Logs also go to the app log file through SLF4J ([Host] `LoggerProviderImpl.kt`).
- A thrown exception becomes "The script failed without error message." and is sent to the host's crash reporter **with the
  task configuration** attached (`TaskExecutor.kt:116-127`). The script already avoids this by returning `Error`.
- An Error row shows a **Report issue** button when the marketplace listing has a support URL. It opens a pre-filled GitHub
  issue with the error message, plus the stack trace for exceptions (`ReportScriptIssueInteractor.kt`). Error text can
  therefore end up in a public issue, so keep it scrubbed.
- While a `showUrl`/`showHtml` window is open, the host overwrites the status with **"Waiting for browser interaction..."**
  (`UserInteractionWindow.kt:122`). The script's "Waiting for your confirmation…" line moves into history.

## 4. Notifications

- [SDK] `Notification(title: String?, message: String, discordMessage?, telegramMessage?, emailMessage?)`, or the
  `notification(message) { title { … }; discordMessage { … }; telegramMessage { … }; emailMessage { … } }` DSL.
- [Host] Channels (`NotificationResolver.kt`): **desktop** if enabled globally. **Discord, Telegram and email** if enabled
  globally *or* configured in the instance's Notification overrides. The script's own value wins over the override, which wins
  over the global setting.
- **Desktop:** the native OS notification, title and message only. macOS uses `terminal-notifier` or `osascript`, Windows a
  tray balloon, Linux `notify-send`. There is no click action, image or button, and the host doesn't truncate, so the OS does.
- **Default text when the script sends only title and message:** Discord `**title**\nmessage`, which must be **≤ 2000
  chars** or that channel fails. Email uses the title as the subject (≤ 998 chars) and the message as the body. Telegram uses
  `title\nmessage` as plain text (≤ 4096) on `main`. **The shipped 1.15.0 sends Telegram as Markdown (`*title*\nmessage`), and
  Telegram rejects that when the text has an unpaired `_` or `*`, which URLs often do** (fixed after 1.15.0, see the compare
  `cereal-client/1.15.0...d5eb0fe`). Script-only fallback: always pass an explicit `telegramMessage { text { … } }` with no parse
  mode. The bot token and chat id still fall back to the override or global settings.
- **Richer is possible per channel:** Discord embeds (fields, colour, URL, image or thumbnail, author, footer; ≤ 10 embeds, ≤ 25
  fields, ≤ 6000 chars per embed), Telegram parse modes, and email. Desktop stays title plus message.
- **Notification center** ([Host] `NotificationCenterScreen.kt`, `CONTEXT.md`): every notification is stored with its title,
  message, time and a per-channel delivery result, whether or not it was delivered. A collapsed row shows the title on 1 line
  and the message on 2. Expanding shows everything and the channel results. Each entry has an "Open task" link, and the sidebar
  shows a badge for unseen entries. Entries are deleted with their task.
- A delivery failure never throws into the script. It is recorded as FAILURE in the Notification center. Exception: an invalid
  *script-built* payload (for example a blank embed field name) throws a host `CerealException` from `sendNotification`
  (`NotificationComponentImpl.kt`).
- [Docs] Delivery is best-effort, and users can disable channels.

## 5. Dialogs and other interaction primitives

[SDK] `UserInteractionComponent`: `showUrl(title, url, headers, shouldFinish)`, `showHtml(title, html, shouldFinish)`,
`requestInput(title, description): String`, `showContinueButton()`. A dismissed interaction throws
`UserInteractionCanceledException`, and a page that fails to load throws `WebResourceException`.

**`showHtml`** ([Host] `UserInteractionWindow.kt:108-200`):
- It launches a **new, visible Chrome** through kdriver (`createBrowser(headless = false)`), navigates to `about:blank` and
  injects the HTML with CDP `Page.setDocumentContent`. [Inferred] That Chrome is a fresh browser, separate from the agent's own
  Chrome and profile. Without Chrome installed it throws `ChromeNotInstalledException`.
- **The `title` argument is ignored** for this window, which takes its title from the HTML `<title>`. Only `requestInput`
  uses its title.
- CSS: it is real Chrome, so inline CSS renders. **JS: not verified.** There is no API for size, position, always-on-top or a
  timeout.
- It finishes on the first outgoing request that `shouldFinish` accepts: a link click, a navigation, or a form submit, with
  urlencoded POST bodies parsed into `postData`. The window then closes.
- **The window exists only while the Tasks screen is on screen.** Interaction windows are composed inside `TasksScreen`, and
  only the active route is composed (`navigation/Root.kt:56-64`). Leaving the screen disposes the window and resumes with
  `UserInteractionCanceledException` (`onDispose`), which this script treats as a **Decline**. In the shipped **1.15.0**, even
  **selecting another script** in the Tasks list cancels it, because only the selected script's interactions were rendered.
  This was fixed after 1.15.0 (`TasksViewStateBuilder.extractUserInteractions`). A dialog requested while the user is on another
  screen opens only after they come back to Tasks.
- Stopping the task cancels it with `UserInteractionCanceledException` (`TaskManager.stopTask`).

**`requestInput`:** a 400×250 always-on-top window titled "Task #n - {title}". It shows the description as plain text above a
single-line, **unmasked** text field and a Submit button. Closing it cancels (`UserInteractionWindow.kt:250-300`). It is not fit
for secrets.

**`showContinueButton`:** a green **Continue** button in the task row, with no text or label parameter. It suspends until
clicked (`TaskRow.kt:150-164`).

**Not an interaction, but useful output:** [SDK] `artifact().emit(name, bytes, mimeType?)` (SDK ≥ 1.10). Each call adds a
downloadable file to the task. Files persist until the task is deleted ([Docs] `components/artifact.md`).

No primitive has a timeout. The script has to wrap one itself, with `withTimeout`.

## 6. Lifecycle, task identity and actions

- **There is no task id in the SDK.** `ComponentProvider` exposes none, and `ScriptParameters` is only for child scripts. The
  host has task ids and uses them internally for the logger tag, artifacts and notification history (`TaskScopeModule.kt`).
- **A new `Script` object is constructed on every start of a task** (`TaskExecutor.kt:55`). Fields survive the `Loop`
  iterations of one start and are reset on restart. Then `onStart` runs once, `execute` loops, and `onFinish` always runs in
  `finally`, also when the task is stopped.
- **`preference()` is scoped to the script instance**, not the task (`PreferenceComponentImpl(scriptPackageInstanceId)`,
  `ScriptPackageInstanceScopeModule.kt:25-32`). This script has no `valuePerTask` or proxy item, so each instance has exactly
  **one task** ([Docs] `configuration.md` "Concurrency and tasks"). **Preference is therefore effectively per task.** A new
  instance, including one made with "Copy from existing script", starts empty. Rows are never deleted when an instance is
  deleted: there is no foreign key, and nothing calls `deleteByInstanceId`.
  - Script-only stand-in for a task id: `preference().getString("taskId") ?: UUID.randomUUID().toString().also { setString(…) }`.
    [Inferred] This would let the Chrome profile be keyed per task instead of by a hash of goal, URL and secrets.
- **The script gets no hook for task deletion, config edit or instance deletion.** It cannot clean up on-disk state such as
  Chrome profiles under `~/.cereal/…`. So "delete the task to reset it", in the `reLearn` description, doesn't delete the
  profile today. A new task with the same goal, URL and secrets reuses it.
- **There are no custom actions, buttons or menu items.** The host has its own Start, Stop, Restart (with confirmation) and
  Delete. The ways to offer a "reset":
  1. A Boolean config field, as `reLearn` does now. It is **sticky**: it stays on until the user turns it off, so it runs on
     every start. To make it one-shot, the script could store a hash of the config in `preference()` and act only when it changes.
  2. A mid-run prompt: `showContinueButton`, `requestInput`, or a `showHtml` with links.

## What this changes in other tickets

1. **Config form:** conditional fields are possible. A Simple/Advanced enum or switch can hide Base URL, Model and the cost and
   step caps. Repeat interval can show only in Repeat mode, and secret slots 2 and 3 only after slot 1 is filled. Hideable items
   must be nullable, hidden values still reach the script, and hidden errors must return null. Friendly enum labels come from
   `toString()`. There are no headings or sections, so grouping is by `position` and description text only.
2. **Validation:** most of `validate()` can move into per-field `getError` (blank goal, URL scheme, API key required for
   Anthropic, numbers > 0, secret name pattern, uniqueness, value without a name), shown at Save. `onStart = false` can never
   carry a message. Keep the run-time check as a backstop.
3. **Status:** the row is one ellipsized line, so the key word goes first. Full text, logs and history are in the log panel. A
   pending confirmation shows the host's "Waiting for browser interaction...", not the script's text.
4. **Notifications:** keep the default text under Discord's 2000 chars. Send an explicit plain `telegramMessage` so 1.15.0
   users don't lose Telegram on URLs. The Notification center already gives a persistent history with a link to the task.
5. **Confirmation dialog:** it is fragile on 1.15.0 (switching scripts declines) and on any version (leaving the Tasks screen
   declines). It opens a second Chrome, ignores `title` and has no timeout. Design for "closed by accident" and consider a
   desktop notification plus a `showContinueButton`-style fallback.
6. **Lifecycle:** there is no task id, but `preference()` gives a per-task store for this script. Use it for the profile key and
   for a one-shot Re-learn.

## Questions only Cereal, or a running client, can answer

- What does closing the confirmation Chrome window by hand do: throw `UserInteractionCanceledException`, throw something else,
  or hang? The code has no explicit handler for a closed browser.
- Does inline `<script>` run in a page injected with `Page.setDocumentContent`?
- Does a `description` containing `\n` render line breaks?
- How does each OS truncate long desktop notification titles and bodies?
- When will the post-1.15.0 fixes (interaction windows across scripts, plain-text Telegram) ship?
- Candidate "ask Cereal" items: a task id in the SDK; form sections, headings, placeholders and multi-line text; a script
  message for `onStart` failures; `showHtml` title, size and timeout, and keeping it alive across navigation; a desktop
  notification click that opens the task; a hook or cleanup when an instance or task is deleted; custom task actions.

## Source links

- SDK 1.12.0: [Configuration.kt][sdk-conf], [StateModifier.kt][sdk-sm], [ScriptConfig.kt][sdk-sc], [Script.kt][sdk-script],
  [ExecutionResult.kt][sdk-er], [UserInteractionComponent.kt][sdk-ui], [Notification.kt][sdk-notif],
  [ArtifactComponent.kt][sdk-art], [LoggerComponent.kt][sdk-log]
- Host (`main` @ `d5eb0fe`): [TaskExecutor.kt][h-exec], [TaskManager.kt][h-mgr], [TaskRow.kt][h-row],
  [TasksViewModel.kt][h-tvm], [JobTask.kt][h-job], [LoggerProviderImpl.kt][h-logp], [ScriptConfigurationView.kt][h-cfgview],
  [ConfigurationItemsFormSection.kt][h-section], [ScriptConfigurationItemMapper.kt][h-mapper],
  [ScriptConfigurationDefinitionExtensions.kt][h-validate], [ScriptConfigurationItemDefinitionExt.kt][h-req],
  [ConfigInvocationHandler.kt][h-invoke], [UserInteractionWindow.kt][h-uiw], [UserInteractionComponentImpl.kt][h-uic],
  [NotificationResolver.kt][h-resolver], [NotificationComponentImpl.kt][h-notifc], [PreferenceComponentImpl.kt][h-pref],
  [navigation/Root.kt][h-root]
- Changes since the 1.15.0 release: [compare cereal-client/1.15.0...d5eb0fe][h-compare]
- Docs (private repo `Cereal-Automation/Documentation` @ `375e145`): `docs/developers/configuration.md`,
  `advanced-configuration.md`, `manifest.md`, `components/{notification,user_interaction,logger,artifact,preference}.md`

[sdk]: https://github.com/Cereal-Automation/sdk/tree/1.12.0
[sdk-conf]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/Configuration.kt
[sdk-sm]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/statemodifier/StateModifier.kt
[sdk-sc]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/statemodifier/ScriptConfig.kt
[sdk-script]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/Script.kt
[sdk-er]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/ExecutionResult.kt
[sdk-ui]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/component/userinteraction/UserInteractionComponent.kt
[sdk-notif]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/component/notification/Notification.kt
[sdk-art]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/component/artifact/ArtifactComponent.kt
[sdk-log]: https://github.com/Cereal-Automation/sdk/blob/1.12.0/cereal-sdk/src/main/java/com/cereal/sdk/component/logger/LoggerComponent.kt
[host]: https://github.com/Cereal-Automation/desktop-client/tree/d5eb0fea20c115018fef79217ebbd39cf8211960
[h-exec]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/application/task/TaskExecutor.kt#L72-L101
[h-mgr]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/application/task/TaskManager.kt#L110-L190
[h-row]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/tasks/TaskRow.kt#L108-L118
[h-tvm]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/tasks/TasksViewModel.kt#L460-L536
[h-job]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/domain/model/task/JobTask.kt#L24-L56
[h-logp]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/infrastructure/provider/LoggerProviderImpl.kt
[h-cfgview]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/tasks/script/overview/configuration/ScriptConfigurationView.kt#L100-L185
[h-section]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/tasks/script/overview/configuration/model/ConfigurationItemsFormSection.kt
[h-mapper]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/tasks/script/overview/configuration/ScriptConfigurationItemMapper.kt
[h-validate]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/tasks/script/overview/configuration/ScriptConfigurationDefinitionExtensions.kt#L22-L50
[h-req]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/view/fields/validator/ScriptConfigurationItemDefinitionExt.kt
[h-invoke]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/application/task/ConfigInvocationHandler.kt#L160-L175
[h-uiw]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/tasks/UserInteractionWindow.kt
[h-uic]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/infrastructure/sdkcomponent/UserInteractionComponentImpl.kt
[h-resolver]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/domain/model/notification/NotificationResolver.kt
[h-notifc]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/infrastructure/sdkcomponent/NotificationComponentImpl.kt
[h-pref]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/infrastructure/sdkcomponent/PreferenceComponentImpl.kt
[h-root]: https://github.com/Cereal-Automation/desktop-client/blob/d5eb0fea20c115018fef79217ebbd39cf8211960/cereal-client/src/main/java/com/cereal/client/presentation/navigation/Root.kt#L50-L70
[h-compare]: https://github.com/Cereal-Automation/desktop-client/compare/cereal-client/1.15.0...d5eb0fea20c115018fef79217ebbd39cf8211960
