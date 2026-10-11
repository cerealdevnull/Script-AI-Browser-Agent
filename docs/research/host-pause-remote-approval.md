# What desktop-client can do for pausing and remote approval (#21)

Research for issue #21. Question: how does a script ask the user something today, can a run wait indefinitely, is
there any path to answer from another device, and what would a host change for "pause until the user says resume" and
"approve from your phone" touch?

Sources (all read on 2026-10-11):

- **SDK 1.12.0**: the jar this script builds against
  (`~/.gradle/caches/.../cereal-sdk/1.12.0/.../cereal-sdk-1.12.0.jar`, read with `javap`) and the source at
  [`Cereal-Automation/sdk`](https://github.com/Cereal-Automation/sdk) tag `1.12.0` (= `master` @ `bab8a47`). Paths
  below starting with `sdk/` are in that repo.
- **Host**: [`Cereal-Automation/desktop-client`](https://github.com/Cereal-Automation/desktop-client) `master` @
  `d5eb0fe` (2026-10-09). Paths below starting with `client/` mean `cereal-client/src/main/java/com/cereal/client/`.
- **Cereal docs**: `Cereal-Automation/Documentation` (private) @ `375e145`,
  `docs/developers/components/user_interaction.md` and `notification.md`.
- **This script**: `src/main/kotlin/com/cereal/script/aiagent/AiAgentScript.kt` (`confirm`, line 173 on).

## Short answer

- **Asking the user** is a suspend call on `UserInteractionComponent`. It offers four calls: `showUrl`, `showHtml`,
  `requestInput` and `showContinueButton`. The host parks the coroutine's continuation on the in-memory task, and the
  desktop UI resumes it. None of the four has a timeout.
- **Waiting indefinitely already works**, but only while the app keeps running. Nothing on the SDK or host side has a
  timeout. `showContinueButton()` is a Continue button in the task row: a ready-made "pause until resume" on the
  desktop. A run does not survive quitting the app.
- **Answering from another device is impossible today.** Every external channel is send-only: Discord webhook,
  Telegram `sendMessage`, SMTP email and OS notifications. The host has no inbound endpoint except a loopback-only
  OAuth callback. No mobile app or relay exists among the org's repos.
- **Sizing.** A desktop "pause until resume" needs **no host change** (use `showContinueButton`); small host fixes
  would improve it. "Approve from your phone" can work **script-only via the Telegram Bot API** today. Doing it
  properly in the host means a **new SDK method plus host work in four places**, and a web or mobile path also needs
  a **relay server**.

## 1. How a script asks the user today

The SDK surface (`sdk/.../userinteraction/UserInteractionComponent.kt`; the 1.12.0 jar has the same signatures):

| Call | Shows | Returns | Ends when |
|---|---|---|---|
| `showUrl(title, url, headers, shouldFinish)` | a URL in a browser window | the `WebResourceRequest` that matched `shouldFinish` | a request matches, or the user closes the window (`UserInteractionCanceledException`) |
| `showHtml(title, html, shouldFinish)` | inline HTML in a browser window | same | same |
| `requestInput(title, description)` | a small text-input window | the string | Submit, or close (`UserInteractionCanceledException`) |
| `showContinueButton()` | a green **Continue** button in the task row | `Unit` | the user clicks Continue |

No call takes a timeout, a default answer or a channel. The docs say the same thing
(`Documentation/docs/developers/components/user_interaction.md`).

This script's confirmation (`AiAgentScript.confirm`, lines 173-197) sends a notification, then calls `showHtml` with a
small table and two links to `https://cereal-confirm.invalid/{confirm,decline}`. `shouldFinish` matches either link.
Any exception, including a closed window, counts as **Decline**.

How the host implements it:

- `client/infrastructure/sdkcomponent/UserInteractionComponentImpl.kt` (lines 41-136): each call is a
  `suspendCancellableCoroutine`. The host wraps the continuation in a `UserInteraction` (`Browser`, `ContinueButton` or
  `TextInput`; `client/domain/model/task/Task.kt` lines 12-32) and stores it on the task with
  `tasksRepository.setUserInteraction(taskId, …)`.
- That store is an in-memory map (`client/infrastructure/data/repository/TasksRepositoryImpl.kt` line 28,
  `setUserInteraction` lines 167-174). Its flow drives the UI.
- `client/presentation/tasks/TasksScreen.kt` lines 367-380 renders one `UserInteractionWindow` per task with a pending
  interaction. For `Browser` (`showUrl`/`showHtml`), `UserInteractionWindow.kt` line 119 starts a **separate,
  visible Chrome** through kdriver (`createBrowser(headless = false)`). It loads the URL or injects the HTML, then
  watches `requestWillBeSent` until `shouldFinish` matches (lines 173-200). `TextInput` is a Compose `Window`
  (lines 247-300).
- `ContinueButton` gets no window. `client/presentation/tasks/TaskRow.kt` lines 155-169 draws it in the row, and the
  click resumes the continuation (`TasksScreen.kt` lines 210-219).
- `client/application/task/TaskExecutor.kt` lines 77-104 and 141-147 run `execute` inside
  `runInterruptible { runBlocking { … } }` on a dedicated thread, with no timeout around it.

Notes from reading the source (not yet tried in the running app):

- **The confirmation window belongs to the Tasks screen.** `UserInteractionWindow` sits inside the `TasksScreen`
  composable, and `Root.kt` (lines 56-66) only composes `TasksScreen` while the Tasks route is shown. If the user
  switches to another screen, `BrowserWindow`'s `onDispose` (`UserInteractionWindow.kt` lines 91-101) resumes the
  continuation with `UserInteractionCanceledException`. For this script, that means **navigating away during a
  confirmation declines it**. `onDispose` also never clears the task's interaction, so returning to Tasks can reopen a
  window for a continuation that has already completed. `TextInput` does not have this problem: disposal hides the
  window, but the continuation stays pending.
- **Stop during a confirmation can look like a Decline.** `TaskManager.stopTask` (`client/application/task/TaskManager.kt`
  lines 174-200) first cancels a pending `Browser` continuation with `UserInteractionCanceledException`, and only then
  cancels the job. `confirm` catches that exception, and `ensureActive()` may still pass inside the `runBlocking`
  context. The agent can then log or notify "declined" before the interrupt arrives.
- **No per-row Stop while paused.** While a `ContinueButton` is pending, `TaskRow` shows Continue *instead of* Stop
  (`if … else if (taskItem.isRunning)`). Only "Stop all" remains.

## 2. Can a run wait indefinitely?

Yes, for as long as the app process lives:

- None of the SDK calls has a timeout, and the host implementation adds none (`suspendCancellableCoroutine` with no
  `withTimeout`). The `TaskExecutor` loop has no wall-clock limit either.
- `showContinueButton()` is the best indefinite pause. It lives in the task row rather than a window, so it survives
  screen changes. It has no label or reason parameter, so the script has to explain the pause through `statusUpdate`
  (the row's status text) and/or a notification.
- `TaskStatus` only has `Idle`, `Running`, `Success` and `Error` (`Task.kt` lines 47-75). A paused task looks like
  `Running`, and no "waiting for you" state or badge exists.
- **Tasks do not survive quitting the app.** The job and the interaction exist only in memory. The 1.9.0 release
  notes (`docs/releases/cereal-1.9.0-release-notes-2026-04-26.md`) mention a fix so that tasks interrupted by a quit
  no longer show "Running" after restart, which confirms there is no resume across restarts. Limits outside Cereal
  still apply during a long pause: site sessions expire, and the agent's own Chrome stays open.

## 3. Is there any path to answer from another device?

No. The full list of what exists:

| Channel | What the host does | Inbound? |
|---|---|---|
| Desktop OS notification | `client/infrastructure/data/datasource/os/{Mac,Windows,Linux}NotificationDataSource.kt` | No (the Mac source carries a comment about maybe moving to APNS push someday) |
| Discord | POST to a webhook URL (`client/infrastructure/data/notification/DiscordNotificationStrategy.kt`) | No; a webhook cannot receive replies or button clicks |
| Telegram | `TelegramHttpClient.sendMessage` only (`client/infrastructure/data/notification/telegram/TelegramHttpClient.kt` lines 29-44) | No; no `getUpdates`, no webhook, no `reply_markup` |
| Email | SMTP send (`EmailNotificationStrategy.kt`) | No |
| Local HTTP server | `SystemBrowserOAuthDataSource.kt` line 44: `HttpServer` on `127.0.0.1`, random port, only for the OAuth callback | Loopback only, not a remote path |

- Searching the host source for `getUpdates`, `callback_query`, `reply_markup`, `setWebhook`, websocket servers,
  FCM/APNS and "mobile" turned up nothing beyond the table above. The `datasource/discord/*` files are Discord
  **Rich Presence** (RPC to the local Discord app), not a bot.
- No mobile app or remote-control service appears among the org's repos (`gh repo list Cereal-Automation`). The
  private `Web`, `Website` and `Marketplace` repos are the only places a relay could live; this research did not open
  them.
- What the SDK offers here is send-only too. `Notification` carries `DiscordMessage`, `TelegramMessage` and
  `EmailMessage` (jar `javap`; `sdk/.../notification/`). `TelegramMessage` has `chatId`, `text`, `parseMode`,
  `disableWebPagePreview`, `disableNotification`, `replyToMessageId` and `botToken`, but no keyboard or buttons.
  Scripts cannot read the user's global notification settings, because `ComponentProvider` has no settings component.

## 4. What the two features would touch

### "Pause until the user says resume" (human handoff on the desktop)

**Script-only, works today:** `statusUpdate("Your turn: <why>. Click Continue when done")`, then `notify(...)`, then
`provider.userInteraction().showContinueButton()`. The user acts in the agent's own visible Chrome, clicks Continue,
and the agent resumes. It waits indefinitely and survives screen changes.

**Optional host polish** (each change is small and lives in the host):

1. Let `showContinueButton` take a label or reason. That is an **SDK change**
   (`UserInteractionComponent.showContinueButton(reason: String)` or a default-arg overload), plus
   `UserInteraction.ContinueButton` and `TaskRow`.
2. Keep the per-row **Stop** visible next to Continue (`TaskRow.kt` lines 155-176).
3. Add a "waiting for you" state: a `TaskStatus.Waiting` or a row badge derived from `task.userInteraction != null`
   (`Task.kt`, `TaskRow`, the notification center).
4. Fix the issues from §1 that affect confirmation:
   - Move `UserInteractionWindow` out of `TasksScreen` into the app root, so a confirmation survives screen changes.
   - Clear the interaction when the continuation is cancelled: `invokeOnCancellation { setUserInteraction(taskId, null) }`
     in `UserInteractionComponentImpl`.

A user-started pause (a Pause button on a running task, as opposed to the script handing off) has **no hook at all**
today. The host has no way to signal a running script except cancelling it. It would need an SDK signal (for example a
`pauseRequested` flow, or a suspend point the script checks between steps), plus `TaskManager.pauseTask`, a row button
and a `Paused` status.

**Survive an app restart:** out of reach without a large host change (persisting script state and re-entering
`execute`). The cheap stand-in is to fail with a clear message and rely on the replay cache.

### "Approve from your phone"

**Option A, script-only (no host or SDK change).** The script talks to the Telegram Bot API itself over its own HTTP
client, the same way it already calls Anthropic:

- `sendMessage` with an inline keyboard (Confirm / Decline), then long-poll `getUpdates` for the `callback_query`.
- Race that against the existing `showHtml` dialog and take whichever answers first.
- Needs a bot token and chat id in the script's own config, since the host's global Telegram settings are not exposed
  to scripts.
- Must check the sender's chat id, and bind each approval to a one-time nonce so a late tap cannot approve a later step.
- Discord is not an option this way: answering needs a bot with a gateway or interactions endpoint, not a webhook.
- The catch: cancelling the losing `showHtml` from the script side leaves its window open, because the host does not
  clear the interaction on cancel. That is the `invokeOnCancellation` fix from above.

**Option B, host-supported.**

- **SDK:** a new suspend call, for example
  `UserInteractionComponent.requestApproval(title, details, choices): String`, or notification actions. Adding an
  abstract method is safe: the host refuses to start a script whose manifest `sdk_version` is newer than its own
  (`client/application/interactor/script/StartScriptInteractor.kt` lines 35-38, `ClientUpdateRequiredException`).
- **Host:**
  - a new `UserInteraction` variant, implemented in `UserInteractionComponentImpl`;
  - the desktop UI for it (`UserInteractionWindow` / `TaskRow`);
  - a remote leg that sends the question with actions and resumes the continuation on the first valid answer, then
    clears the other side.
  - For Telegram that means extending `TelegramHttpClient` (`reply_markup`, `getUpdates` or a webhook,
    `answerCallbackQuery`) and the settings (which chat may approve).
  - Discord would need a real bot (gateway or interactions endpoint) and a new settings flow; email needs a server.
- **Web or mobile approval:** needs a **Cereal-run relay**, since the desktop is behind NAT and only has a loopback
  server. The desktop holds an outbound connection or polls; the phone (push via APNS/FCM, or a web page) posts the
  answer to the relay. That is a new service plus auth (pairing a phone to a desktop user), so it is the largest item
  by far.

## Open points

- The two §1 findings (a screen change declines the confirmation; Stop can look like a Decline) come from reading the
  source. A short manual check in the client would confirm them.
- Whether the private `Web` / `Marketplace` repos already have a user-to-desktop channel that a relay could reuse was
  not checked.
