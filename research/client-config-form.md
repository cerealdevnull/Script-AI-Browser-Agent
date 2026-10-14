# How the client renders and evaluates a script's config form today

Research for [#100](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/100), child of the map [#99](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/99). Read on 2026-10-14.

## Sources and versions

- **SDK** (`~/PProjects/sdk`): `origin/master` = tag `1.12.0` (`bab8a47`). Both in-flight branches named in the ticket are **already merged and released**: `feat/complex-list-config` (lists of records) landed in `1.11.0` via `e29c2a7`, and `claude/secret-strings-configuration-9388a0` (`Secret`) via `02fe909`. The other local `claude/secret-strings-*` branches have no commits beyond `553d895`/`02fe909`, so they hold nothing new. Local `master` is stale (`21bee9a`); every SDK citation below is `origin/master`. Nothing form-related is in flight in the SDK.
- **Desktop client** (`~/PProjects/desktop-client`): `master` (`d5eb0fe`). It builds against SDK `1.12.0` (`gradle/libs.versions.toml:43`). The unmerged `headless-mode` branch adds a second, terminal-based renderer of the same form (see the last section).
- This script: SDK `1.12.0` (`gradle/libs.versions.toml:7`).

Path prefixes: `S/` = `cereal-sdk/src/main/java/com/cereal/sdk/` (SDK repo). `C/` = `cereal-client/src/main/java/com/cereal/client/` (desktop-client repo).

## The pipeline in one paragraph

At load time the client reflects over the script's `ScriptConfiguration` interface and builds one `ScriptConfigurationItemDefinition` per annotated method (`C/infrastructure/data/datasource/filesystem/ScriptConfigurationDefinitionBuilder.kt:113-171`). The configuration screen asks `GetScriptConfigDefinitionInteractor` for items with their initial values (`C/application/interactor/script/GetScriptConfigDefinitionInteractor.kt:52-90`), maps each to a `FormFieldState` (`C/presentation/tasks/script/overview/configuration/ScriptConfigurationItemMapper.kt:28-173`), and renders a flat list of `SettingsItem` rows (`C/presentation/tasks/script/overview/configuration/ScriptConfigurationView.kt:154-166`). On every value change the client re-runs every item's `getVisibility` (`C/presentation/tasks/script/overview/configuration/model/ConfigurationItemsFormSection.kt:28-32, 52-68`). `getError` runs only inside field validators, which fire on blur and when the user presses Start. At run time the script reads values through a dynamic proxy, `ConfigInvocationHandler` (`C/application/task/ConfigInvocationHandler.kt:40-175`).

## 1. Field types and widgets

The SDK's supported return types are listed in `S/Configuration.kt:18-34` and `README.md:89-94`. The client enforces them in `ScriptConfigurationDefinitionBuilder.kt:34-68`. Widgets come from `ScriptConfigurationItemMapper.kt:33-172` and `ScriptConfigurationView.kt:214-248`:

| Return type | Form state | Widget |
|---|---|---|
| `String` | `StringTextFieldState` (mapper :101-113) | `OutlinedTextField`, **always single-line**, no placeholder (`C/presentation/view/fields/TextField.kt:24, 41-45`; the config screen passes no `hintText`, `ScriptConfigurationView.kt:255`) |
| `Int` / `Float` / `Double` | `Int/Float/DoubleTextFieldState` (mapper :59-99) | the same text field. The type check (e.g. `IntStringFieldValidator`) runs in the validator (`ScriptConfigurationItemDefinitionStringFieldValidator.kt:16, 30-43`). No spinner, no min/max. |
| `Boolean` | `SwitchFieldState` (mapper :34-44) | switch, initial `false` |
| enum | `DropDownFieldState` over `enumConstants` (mapper :46-57; options from `GetScriptConfigDefinitionInteractor.kt:63-71`) | dropdown labelled by each constant's `toString()` (`C/presentation/view/fields/DropdownField.kt:50, 136`). It is clearable only when the return type is nullable (mapper :49). This is how `OnNextStart` shows its labels. |
| `Secret` | `SecretTextFieldState` (mapper :115-129) | masked text field with a per-field reveal toggle, hidden by default (`C/presentation/view/fields/SecretTextField.kt:40-61`; `state/SecretTextFieldState.kt:30-35`) |
| `Proxy` / `RandomProxy` | `DropDownFieldState` of proxy groups plus an **Import** button (mapper :144-155; view :271-292) | at most one Proxy item (builder :82-87) |
| `List<T : ScriptConfigurationListItem>` | `ListFieldState` (mapper :157-172) | rows in a lazy column capped at 400dp. A record with one field gets one bare input per row; a record with two or more fields gets one card per row, numbered. Below the rows sit **Add** and **Import from CSV** (view :299-423). |
| any `valuePerTask` item | collected into a synthetic "Task data" `GroupedConfigItem` (builder :148-168) | one dropdown of custom datasets plus Import (mapper :131-142) |

Every row shows its `name` twice: as the `SettingsItem` title, with `*` appended when required (view :157-160), and as the text field's floating label (view :255; `TextField.kt:49-57`). A field's error appears in red under the widget (`C/presentation/view/fields/FormField.kt:37-44`).

## 2. How `description` is shown

- It is plain text in the `SettingsItem` left column, styled `bodySmall` in `contentTertiary` (`C/presentation/view/SettingsComponents.kt:247-249`).
- It goes through `ClickableUrlText` (`C/presentation/view/ClickableUrlText.kt:28-32, 45-125`). That component **auto-links bare URLs** matching `(https?://|ftp://|www\.)[^\s,;!?(){}<>'"]+`, underlines them, and opens them with `Desktop.browse` on tap (prefixing `https://` to `www.`).
- **No markdown and no link text.** You cannot write `[Anthropic](url)`. The full URL is the link text. Backticks, asterisks and newlines-as-structure have no effect, and the text is not trimmed or wrapped specially. A trailing `.` sticks to the link, because `.` is not in the stop set: in `AgentConfiguration.kt:29`, "e.g. https://example.com." links to `https://example.com.`.
- Errors from `getError` render as plain `CerealText` (`FormField.kt:39-43`). The backticks in this script's `modelError` (`AgentConfiguration.kt:246`) show literally.
- The manifest's `instructions` render at the top of the form in an `InfoBox`, which uses the same auto-linking (`ScriptConfigurationView.kt:101-107`; `C/presentation/view/InfoBox.kt:50-55`; `ManifestJson.kt:21-22`).
- List-record fields show their `description` nowhere. `ListRowField` renders only the name as the label (view :429-461). The `AllowedDomain.domain` description ("E.g. accounts.example.com…") is therefore never shown.

## 3. When, how often and on which thread `getVisibility` / `getError` run

The SDK contract says only "called every time a change in the configuration occurs" for `getVisibility` (`S/statemodifier/StateModifier.kt:7-16`). It says nothing about threads, I/O or blocking. `ScriptConfigurationItem.stateModifier` must be a Kotlin `object`, because the client reads `objectInstance` and throws otherwise (builder :122-130).

**`getVisibility`**
- **On every keystroke, toggle or selection in any field.** Each `FormFieldState` is built with `onValueChange = { applyStateModifiers() }` (`ConfigurationItemsFormSection.kt:28-32`; `TextFieldState.kt:20-23`; `SwitchFieldState.kt:35-39`; `DropDownFieldState.kt:24-33`; `ListFieldState.kt:104-108`).
- `applyStateModifiers` collects all values once, then calls `getVisibility` for **every** item to set `isVisible`, then calls it **again** through `isRequired` for every nullable item (`ConfigurationItemsFormSection.kt:52-68`; `isRequired` = `!isNullable || getVisibility(..) == VisibleRequired`, `C/presentation/view/fields/validator/ScriptConfigurationItemDefinitionExt.kt:7`). Each call gets a fresh `RawScriptConfigValues` snapshot.
- It also runs once after the form loads and after a copy or duplicate (`ScriptConfigurationViewModel.kt:101-107`; `ConfigurationItemsFormSection.kt:123`). It runs whenever a validator computes `isRequired`, and when the Task-data import dialog filters fields (`ScriptConfigurationViewModel.kt:179-186`).
- **Thread:** keystroke handlers run synchronously on the Compose UI thread (main/EDT). The initial `applyStateModifiers` runs in the interactor callback, which `InteractorRunner` switches to `dispatcherProvider.main` (`C/presentation/util/InteractorRunner.kt:37-47`). `CoroutinesDispatcherProvider` maps `main` to `Dispatchers.Main` (`C/application/CoroutinesDispatcherProvider.kt:15`).

**`getError`**
- It is called only from field validators: `ScriptConfigurationItemDefinitionStringFieldValidator.kt:11-22` (text, number and secret fields), `...AnyFieldValidator.kt:10-18` (enum and proxy), `...ListFieldValidator.kt:17-27` (lists), and the Boolean and dataset validators.
- Validators run in `FormFieldState.validate()` (`FormFieldState.kt:51-54`), which is triggered:
  - **on blur**, but only for a field that has been focused at least once (`FormFieldState.kt:38-47`; wired via `onFocusChanged`, `TextField.kt:61-63`);
  - **when the user presses Start** (the FAB): `viewModel.validate()` on the UI thread (`C/presentation/tasks/script/overview/ScriptSelectionDialog.kt:111-113` → `ScriptConfigurationViewModel.kt:155, 274-289`);
  - after a CSV import replaces list rows (`ListFieldState.kt:76-80`).
- **It is not re-run on keystrokes.** An error stays on screen until the next blur or Start, even after the user fixes the value. Changing field A does not refresh the error shown under field B.
- It runs again **off the UI thread** at launch: `StartScriptInteractor.run` (launched on `dispatcherProvider.io`, `ScriptSelectionViewModel.kt:229-245`) calls `ScriptConfigurationValuesValidator` (`C/application/interactor/script/StartScriptInteractor.kt:41-47`; `.../validator/ScriptConfigurationValuesValidator.kt:13-23`) → `ScriptConfigurationDefinition.validate` (`ScriptConfigurationDefinitionExtensions.kt:22-51`). That validator calls `getError` and `getVisibility` for every item. The same path re-runs on "start all tasks" and child-script start (`C/application/script/ScriptInstanceValidation.kt:7-15`). Per task, `TaskManager` validates with `isTaskConfiguration = true`, which **skips** `getError` (`ScriptConfigurationDefinitionExtensions.kt:47`; `C/application/task/TaskManager.kt:124-128`).
- **Hidden fields are still validated.** `validateConfigurationForm` validates every field state with no `isVisible` filter (`ScriptConfigurationViewModel.kt:276-286`). A modifier must return `null` from `getError` when its field is hidden, as `SecretState`, `CheckEveryState` and `ServerUrlState` already do. A hidden **non-nullable** item is always required (`ScriptConfigurationItemDefinitionExt.kt:7`), so a hidden, empty non-nullable item with no default silently blocks Start.

**Blocking and I/O.** Nothing guards these calls: there is no timeout, no try/catch around `applyStateModifiers` (`ConfigurationItemsFormSection.kt:52-68`), no coroutine and no debounce. Any blocking I/O in `getVisibility` freezes typing on the UI thread. An exception there propagates out of the Compose event handler. In effect, both methods must be fast, pure and non-throwing. Async work (fetching models, testing a key) has no hook today.

**What the modifier sees.** `RawScriptConfigValues` is built from each field's *validated* value (`ConfigurationItemsFormSection.kt:70-80`; `RawScriptConfigValues.kt:11-32`). So:
- a blank or unparsable field reads as `NullScriptConfigValue`, **not** its declared default;
- a cleared `repeatInterval` reads null although the run would use `300`;
- a `Secret` arrives as `SecretScriptConfigValue` (`S/statemodifier/ScriptConfig.kt:100-109`);
- a list arrives as `ListScriptConfigValue` whose items are per-row `ScriptConfig`s (`ScriptConfig.kt:81-98`).

## 4. Lists: can rows hold `Secret`, and how are rows edited?

**No `Secret` in a list row today, in either the SDK or the client.**
- The SDK documents the permitted record field types as `String`, `Int`, `Float`, `Double`, `Boolean`, enums and their nullable forms (`S/ScriptConfigurationListItem.kt:30-32`). The README states "Not supported: … lists of secrets. Both fail at load time" (`README.md:246-249`).
- The client allows these list-field types: `Boolean`, `String`, `Int`, `Float`, `Double`, `Enum`, with **no `Secret`** (`ScriptConfigurationDefinitionBuilder.kt:56-68`). It throws `InvalidScriptConfigurationDefinitionException` naming the field otherwise (:360-375). Desktop `CONTEXT.md:86` says "a `List<Secret>` is unsupported".
- Failure mode: `readJar` catches the exception and returns `null`. When the installed scripts are loaded, a `null` result **deletes the JAR** as "corrupted" (`C/infrastructure/data/datasource/filesystem/FileSystemScriptsDataSource.kt:188-193, 215-228`).
- To lift the restriction, the client would have to change at least these places:
  - the allowed set (builder :60-68);
  - `ListRowField`, which has no `SecretTextFieldState` branch. A secret is a `TextFieldState`, so it would fall into the plain `TextField` branch and **render unmasked** (`ScriptConfigurationView.kt:437-440`; contrast top level :219-223);
  - `ListRowInvocationHandler.resolveField`, which returns the raw domain value with no domain→SDK `Secret` conversion (`ConfigInvocationHandler.kt:226-243`; contrast the top-level conversion at :128-133);
  - CSV import of list rows (`ReadListFileInteractor`).

  Persistence probably needs nothing: a list is stored as one JSON-of-strings value (`C/infrastructure/data/datasource/database/room/mapper/KeyValueRoomMapper.kt:49, 396-436, 524-532`), and every configuration value is encrypted at rest (`CONTEXT.md:82`).

**Rule enforcement and behaviour today:**
- Rows start with one blank row (`ListFieldState.kt:27-31`). **Add** appends a blank row. The **×** removes a row, or clears it when it is the last one (:43-57).
- Rows the user never touched are dropped from the value (:38-41; `ListRowState.kt:86`). A nullable list left untouched arrives as `null` (`ConfigInvocationHandler.kt:141-150`).
- A non-nullable list requires at least one entered row ("At least one entry is required", `ScriptConfigurationItemDefinitionListFieldValidator.kt:22-26`).
- **Import from CSV** replaces all rows and asks first if the user had typed rows (`ScriptConfigurationViewModel.kt:244-272`).
- Fields inside a record may **not** declare a `stateModifier` (builder :317-323) or a default (:325-331). The list item itself may not have a default, `valuePerTask` or `isScriptIdentifier` (:255-281).
- Each row field's validator sees **its own row** as the `ScriptConfig` (`ScriptConfigurationItemMapper.kt:175-204`). Since the field's modifier is always the default one, in practice that only drives requiredness, from nullability: label `*` when non-nullable (`ScriptConfigurationView.kt:435-436`).
- The list item's own `stateModifier` sees the whole form and can count rows (`S/ScriptConfigurationListItem.kt:39-41`).
- There is no per-row hide, no row reordering, no per-row collapse and no row-level description.

## 5. Grouping, ordering, collapsing

- **Ordering:** items are sorted by `position`, then by `name` (`ScriptConfigurationDefinitionBuilder.kt:198-200`). `position` defaults to `-1` (`S/Configuration.kt:69`), so unpositioned items come first in alphabetical order. The stored definition `position` is set to `Int.MAX_VALUE` (builder :140), so only the list order matters. The "Task data" group always comes last (:155-168). Record fields inside a list sort the same way (:343).
- Rendering is one flat `Column`. Each item is a `SettingsItem` followed by an 8dp spacer, and **hidden items are simply skipped** (`ScriptConfigurationView.kt:154-166`). Gaps in `position` (0-7, 20-25, 30-33, 40) have **no visual effect**: there are no headers, separators, sections, collapse or "advanced" in the SDK annotation (`S/Configuration.kt:68-76`) or the client.
- The only grouping on the screen is fixed client chrome around the script's items:
  - the "Copy from existing script" dropdown above (view :109-152);
  - "# of concurrent tasks" (only when Task data exists, view :167-174);
  - the Notification overrides section and the "* indicates required field" caption below (view :176-185).
- Child-script sections come **before** the main script in render order (`ConfigurationItemsForm.kt:36, 43-47`). This script has no child scripts.

## 6. Defaults and empty optional fields

**Defaults**
- Defaults come from the interface method's body. The client calls it through a proxy, via `invokeDefault` or `DefaultImpls` (builder :132-133, 432-527). Only `Boolean`, `String`, `Int`, `Float`, `Double` and enum are supported. `Secret`, `Proxy` and lists get no default (:453-468; `S/Configuration.kt:36-45`).
- For a new form the default is the field's **initial value**: a stored value wins, otherwise the default (`GetScriptConfigDefinitionInteractor.kt:56-60`). The user sees a pre-filled `300`, `40`, `2.0`, or a pre-selected `Once` / `Anthropic` / `Keep`. A default is never shown as a placeholder or hint, and nothing marks it as "default".
- If the user clears a defaulted text field, the form stores nothing. Start-time validation accepts a missing required value when a default exists (`ScriptConfigurationDefinitionExtensions.kt:33`). At run time the proxy falls back to the default (`ConfigInvocationHandler.kt:162-175`). The form's own modifiers, though, see `null` in that case (section 3).

**Empty optional fields**
- An empty optional text, number or secret field is a blank box with no placeholder (`TextField.kt:41-45`). It validates to `null` (`StringTextFieldState.kt:10`; `SecretTextFieldState.kt:37`) and is omitted from the stored map (`ConfigurationItemsFormSection.kt:73-77`). The script gets `null`.
- A nullable enum shows an empty dropdown with a clear button once a value is set (`DropDownFieldState.kt:29-35`).
- The only visual difference between optional and required is the `*` suffix (view :157).
- `Visibility.VisibleOptional` vs `VisibleRequired` matters only for **nullable** items. A non-nullable item is required whatever the modifier returns (`ScriptConfigurationItemDefinitionExt.kt:7`).

## 7. How the manifest's SDK version gates which scripts load

- The manifest's `sdk_version` (`ManifestJson.kt:24`) is filled from the script's SDK dependency at build time. This repo: `src/main/resources/manifest.json` `"sdk_version": "@cereal-sdk-version@"`, replaced in `build.gradle.kts:89-93`.
- The client's own version is `BuildConfig.SDK_VERSION` = its SDK dependency (`cereal-client/cereal-client.gradle.kts:63-67`; `C/infrastructure/data/repository/ApplicationRepositoryImpl.kt:15`).
- **Rule:** an update is required when the client's `major.minor` < the script's `major.minor`. Patch versions are ignored, and a missing `sdk_version` imposes nothing (`C/domain/model/script/ScriptPackageExtensions.kt:16-25`).
- **At install or update**, an incompatible JAR is rejected with `ClientUpdateRequiredException` (`FileSystemScriptsDataSource.kt:155-162`).
- **At load of already-installed JARs**, incompatible scripts are kept so the UI can badge them "update required" (:220-222; `ScriptSelectionViewModel.kt:101, 147-149`; `MyScriptsViewModel.kt:74`).
- The Start button is disabled-looking and inert while an update is required (`ScriptSelectionDialog.kt:102-118`). `StartScriptInteractor` re-checks and throws (`StartScriptInteractor.kt:35-39`).
- **Caveat:** a JAR whose definition fails to build (for example a return type or rule the older client rejects) is not badged. `readJar` returns `null` and the JAR is **deleted** (`FileSystemScriptsDataSource.kt:188-193, 224-227`). So a new form capability needs the `sdk_version` bump to guarantee the install-time rejection; it cannot rely on the badge.
- The SDK tells authors to rely on this gate: a new type "Requires client SDK version 1.11.0 or newer; older clients prompt the user to update" (`S/ScriptConfigurationListItem.kt:59`; `README.md:243-244`).
- A new **annotation parameter with a default** is binary-compatible with an older client: the old client simply ignores it. A new **return type** or **SDK class** is not.

## Also relevant to the open decisions

- **No in-place edit of a configured script.** The task list offers View configuration, Duplicate, Move and Delete, but not Edit (`C/presentation/tasks/TasksViewModel.kt:63-96`). Changing a value means duplicating; the form is pre-filled through `applyConfigurationFromInstance` (`ConfigurationItemsForm.kt:56-67`). That matters for "On next start" (#106) and any one-shot action.
- **A second renderer is coming.** The unmerged `headless-mode` branch renders the same definitions in a terminal UI. It reads `description` (`C/presentation/headless/ScriptConfigPages.kt:517` on `headless-mode`) and calls `getVisibility` and `getError` itself (:507, 598-606). Any new form capability needs a rendering there too, or a defined fallback.
