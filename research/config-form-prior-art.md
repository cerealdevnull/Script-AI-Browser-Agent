# Prior art: config forms that fetch options, validate remotely and run actions

Research for [#101](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/101), part of map [#99](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/99).

**Question.** How do other plugin and script hosts let a plugin author declare (a) sections and advanced fields, (b) options fetched at form time, (c) async validation such as testing an API key, (d) one-shot action buttons and (e) links in help text? What does the author write, what are the trade-offs, and what maps onto Cereal's annotation-based Kotlin API?

**Cereal today** (`cereal-sdk/src/main/java/com/cereal/sdk/Configuration.kt`, `statemodifier/`): each field is a function on a `ScriptConfiguration` interface, annotated with `@ScriptConfigurationItem(position, keyName, name, description, valuePerTask, stateModifier, isScriptIdentifier)`. A `StateModifier` receives a read-only `ScriptConfig` and synchronously returns `Visibility` and an error string. There are no sections, no async calls, no actions, and descriptions are plain text.

**Platforms covered:** n8n (TypeScript node descriptions), Home Assistant (Python config flows), Jenkins (Java descriptors with Stapler naming conventions), the IntelliJ Kotlin UI DSL, react-jsonschema-form (JSON Schema + uiSchema), VS Code `contributes.configuration`, and Obsidian's `Setting` API. Jenkins and IntelliJ are the closest to Cereal: both are JVM, and Jenkins wires forms up from reflection over method names and annotations.

---

## Summary

| Need | n8n | Home Assistant | Jenkins | IntelliJ UI DSL | RJSF / VS Code |
|---|---|---|---|---|---|
| Sections / advanced | `notice` with `sectionHeader`; `collection` "Additional Fields" (nests values) | `section(schema, {"collapsed": bool})` (nests values) | `<f:section>`, `<f:advanced>` (layout only) | `group()`, `collapsibleGroup()` (layout only) | VS Code: array of categories with `title`/`order`; RJSF: custom template |
| Options fetched at form time | `typeOptions.loadOptionsMethod` + `loadOptionsDependsOn`; `resourceLocator` with list and ID modes | the step's Python builds the schema before `async_show_form` | `doFillXyzItems(@QueryParameter ...)` returns `ListBoxModel`; dependencies inferred from the parameters | arbitrary Kotlin (imperative) | static `enum` only |
| Free entry beside options | `resourceLocator` modes `list` + `id` | `SelectSelector(custom_value=True)` | `f:combobox` | `comboBox` (editable) | none |
| Async / remote validation | credential `test` request or `credentialTest` function → `{status, message}` | validate on submit, return `errors` (mandatory to test the connection) | `doCheckXyz` on change → `FormValidation.ok/warning/error` | `validationOnInput` / `validationOnApply` (sync lambdas) | RJSF `extraErrors` (display only) |
| One-shot action | `button` type, limited to built-in actions | menu steps / separate flows, no in-form button | `<f:validateButton method="foo" with="a,b" progress="...">` → `doFoo` | `button(text) { ... }` | Obsidian `addButton().onClick` |
| Links in help | HTML `<a href>` in `description` | Markdown in `strings.json`, but URLs must go through `description_placeholders` | `help` attribute (URL of an HTML page, shown behind a (?) icon) | HTML in `comment()`, link icon added for http(s) | VS Code `markdownDescription`; RJSF `enableMarkdownInDescription` |

---

## (a) Sections and advanced fields

Two families. One is **layout only**: a section is a visual group and the stored values stay flat. The other is **data-shaping**: a section is a nested object, so a field's stored key includes its section.

- **Home Assistant: data-shaping.** `homeassistant/data_entry_flow.py` defines `class section` with `SectionConfig(collapsed: bool)`, used as `vol.Required("ssl_options"): section(vol.Schema({...}), {"collapsed": False})` ([data entry flow docs](https://developers.home-assistant.io/docs/data_entry_flow_index/)). The section is a key in the schema, so the submitted data nests under it (`user_input["ssl_options"][...]`). Section titles and per-field help live in `strings.json` under `sections` / `data_description` (validated in `script/hassfest/translations.py`).
- **n8n: both.** `typeOptions.sectionHeader` on a `notice` property "renders as a section-header divider instead of a notice box" (`packages/workflow/src/interfaces.ts`, `INodePropertyTypeOptions`). That one is layout only. The common "Additional Fields" / "Options" pattern is a `collection` property (`placeholder: 'Add Field'`), which the docs describe as the type to use "when you need to display optional fields" ([UI elements](https://docs.n8n.io/integrations/creating-nodes/build/reference/ui-elements/)). Values nest under the collection, as in `$parameter.options?.baseURL` in the OpenAI chat model node. `fixedCollection` has `hideOptionalFields` with an "add optional field" button.
- **Jenkins: layout only.** `<f:section title="..." description="...">` is a "section header in the form table". `<f:advanced title="...">` is an "expandable section that shows 'advanced...' button by default" ([Jelly taglib reference](https://reports.jenkins.io/core-taglib/jelly-taglib-ref.html)). `<f:optionalBlock>` folds controls behind a checkbox and binds that checkbox to a field.
- **IntelliJ Kotlin UI DSL: layout only.** `group(title) { ... }` and `collapsibleGroup(title) { ... }` ([Kotlin UI DSL v2](https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html); `platform/platform-api/src/com/intellij/ui/dsl/builder/Panel.kt`).
- **VS Code: layout only.** `contributes.configuration` may be "an array of objects, representing multiple categories of settings", each with `title` and `order`. These show up as a submenu in the settings table of contents ([contribution points](https://code.visualstudio.com/api/references/contribution-points)).

**Trade-off for Cereal.** Map #99 requires that persisted keyNames never change. Data-shaping sections (HA, n8n `collection`) would nest values and break that, so the layout-only family is the one that fits. Every layout-only host declares the section **once**, with its title, description and collapsed state, and puts fields inside it. None of them repeats section metadata on each field. In an annotation API that means a section reference on the item (`section = "advanced"`) plus one section declaration that holds the title, order and `collapsed`. A free-text section name repeated on every item would let titles drift apart. The "advanced" pattern in Jenkins and n8n is a collapsed-by-default group, not a global switch, which bears on #102's question about the Advanced switch.

## (b) Options fetched at form time, depending on another field

- **n8n.** On an `options` property: `typeOptions: { loadOptionsMethod: 'getModels', loadOptionsDependsOn: ['provider'] }`, plus `methods.loadOptions.getModels(this: ILoadOptionsFunctions): Promise<INodePropertyOptions[]>` returning `{ name, value }[]` (`INodePropertyTypeOptions` in `interfaces.ts`; [programmatic-style parameters](https://docs.n8n.io/integrations/creating-nodes/build/reference/node-base-files/programmatic-style-parameters/)). The method runs on the n8n server with the node's credentials. Dependencies are an **explicit list of field names**. There is also a declarative form, `typeOptions.loadOptions.routing`, where the author writes the HTTP request and post-processing (`rootProperty`, `filter`, `setKeyValue`, `sort`) instead of code.
  - **Direct precedent for the Model field.** n8n's OpenAI chat model node (`packages/@n8n/nodes-langchain/nodes/llms/LMChatOpenAi/LmChatOpenAi.node.ts`) first declared `model` as an `options` field loaded from `GET {baseURL}/models` and filtered to chat models. Newer versions (`@version >= 1.2`) **replace it with a `resourceLocator`** that has two modes: `list` (`searchListMethod: 'searchModels', searchable: true`, placeholder `'Select a model...'`) and `id` (free `string`, placeholder `'gpt-5-mini'`). The description reads "Choose from the list, or specify an ID." `listSearch` methods take `(filter?, paginationToken?)`. So n8n kept a fetched list and added free entry as a sibling mode, because a plain fetched dropdown could not express "a model the list doesn't show".
- **Jenkins.** A descriptor method `doFillXyzItems(@QueryParameter String provider, ...)` returns a `ListBoxModel`. `Descriptor.calcFillSettings` (`core/src/main/java/hudson/model/Descriptor.java`) finds it by name through reflection. `buildFillDependencies` turns each `@QueryParameter` into an entry in the field's `fillDependsOn` attribute, so changing any of those fields refetches the list. **Dependencies are inferred from the method signature.** `doAutoCompleteXyz` and `<f:combobox>` follow the same convention for free text with suggestions.
- **Home Assistant.** No declarative hook. The step's Python code builds the `vol.Schema` (for example a `SelectSelector` with options it fetched) before calling `async_show_form`. Options that depend on an earlier answer come from **multi-step flows**: one step picks the provider, the next shows models. `SelectSelector` has `custom_value: true` ("Allows the user to enter and select a custom value"), `mode: list|dropdown` and `multiple` ([selectors](https://www.home-assistant.io/docs/blueprint/selectors/)).
- **VS Code / RJSF / Obsidian (declarative).** Options are static: `enum` + `enumDescriptions` / `enumItemLabels` in VS Code, `enum` in JSON Schema, an object literal for Obsidian's declarative dropdown. Dynamic options mean writing a custom widget, or imperative code in Obsidian.

**Trade-off for Cereal.** In Kotlin, Jenkins-style inference does not carry over directly. A `StateModifier` reads fields through `ScriptConfig.valueForKey("provider")`, which the host cannot see by reflection. That leaves two options: re-run the provider whenever any value changes, or declare `dependsOn = ["provider", "apiKey"]` explicitly as n8n does. Re-running a network fetch on every keystroke is costly and can hit rate limits, which argues for an explicit list. Both n8n (`resourceLocator` `id` mode) and HA (`custom_value`) keep free entry beside a fetched list, which matches #105's requirement that Custom keep free entry. There are two separable layers. Options that are static per provider can be computed synchronously from `ScriptConfig`, which is what a `StateModifier` already does. Options fetched from the provider's API need an async call that receives the secret.

## (c) Async validation, such as testing an API key

- **n8n.** The credential type declares `test: ICredentialTestRequest = { request: { baseURL: '={{$credentials?.url}}', url: '/models' } }` (`packages/nodes-base/credentials/OpenAiApi.credentials.ts`). The interface also accepts `rules` on response codes or the response body. Alternatively, a node references `testedBy: 'fnName'`, a function in `methods.credentialTest` with signature `(credential) => Promise<{ status: 'OK' | 'Error'; message: string }>` (`interfaces.ts`). Testing is attached to the **credential**, not to any field.
- **Home Assistant.** Validation happens **on submit**, inside the step: `valid = await is_valid(user_input)`, else `errors["base"] = "auth_error"` and the form is shown again. `base` holds errors that belong to no particular field ([data entry flow](https://developers.home-assistant.io/docs/data_entry_flow_index/)). The [test-before-configure](https://developers.home-assistant.io/docs/core/integration-quality-scale/rules/test-before-configure) quality rule makes this **required**: "it's required to test the connection in the config flow". Long checks can use `async_show_progress(progress_task=...)`, which the framework can cancel (`async_cancel_progress_task` in `data_entry_flow.py`).
- **Jenkins.** `doCheckXyz(@QueryParameter String value, @QueryParameter String other)` returns `FormValidation.ok()`, `.warning(msg)` or `.error(msg)`. It "gets invoked in response to the onchange event" ([form validation](https://www.jenkins.io/doc/developer/forms/form-validation/)). Network checks such as testing a connection normally go behind a `validateButton` instead (see (d)). Jenkins' security guidance says these endpoints can be abused for SSRF and to leak private information, and recommends `@POST` plus permission checks ([form validation security](https://www.jenkins.io/doc/developer/security/form-validation/)).
- **IntelliJ.** `validationOnInput { ... }` and `validationOnApply { ... }` on a cell return a `ValidationInfo?` synchronously (`Cell.kt`). Anything remote is left to the plugin author's own code.
- **RJSF.** `customValidate(formData, errors)` is synchronous. Server-side results are injected through the `extraErrors` prop, and these errors are "display only" and do not block submit ([validation](https://rjsf-team.github.io/react-jsonschema-form/docs/usage/validation/)).

**Trade-off for Cereal.** No host runs network validation on every keystroke as its main mechanism. Remote checks run either on an explicit button (Jenkins `validateButton`, n8n credential test) or on submit (HA). All of them return a three-level result: ok/warning/error, or OK/Error with a message. That fits a new async hook better than extending `StateModifier.getError`, which is synchronous and runs on every change.

## (d) One-shot action buttons

- **Jenkins.** `<f:validateButton title="Test connection" progress="Testing..." method="testConnection" with="apiKey,provider"/>` calls `doTestConnection(@QueryParameter String apiKey, @QueryParameter String provider)` on the descriptor. The method returns `FormValidation`, which is shown inline under the button. `method` is required: "if this is 'foo', you need 'doFoo' on your descriptor class". `with` lists the fields to send, and `progress` is the caption shown while the call runs ([taglib reference](https://reports.jenkins.io/core-taglib/jelly-taglib-ref.html)). This is a **general action button**. Despite the name, nothing restricts the handler to validation, so it covers both "test the key" and "do something now".
- **n8n.** A `button` property type exists with `typeOptions.buttonConfig: { action?: string | NodePropertyAction, label?, hasInputField? }`. `NodePropertyAction` is currently only `{ type: 'askAiCodeGeneration', handler?, target? }`, and `methods.actionHandler` holds the handler (`interfaces.ts`). In practice it is reserved for built-in host actions rather than arbitrary node code.
- **Obsidian.** `new Setting(el).addButton(b => b.setButtonText('...').onClick(async () => ...))`. Imperative, and the plugin owns the result UI ([settings](https://docs.obsidian.md/Plugins/User+interface/Settings)).
- **IntelliJ.** `button(text) { event -> ... }` (`Row.kt`). Also imperative.
- **Home Assistant.** No in-form button. Distinct actions become separate steps (`async_show_menu(step_id, menu_options=[...])`), an options flow, or a `reconfigure` step.

**Trade-off for Cereal.** Jenkins is closest to an annotation API: a named method on the configuration type, the fields it reads, a progress caption, and a typed ok/warning/error result rendered inline. One construct covers both #106 cases. A "reset what was learned" action also needs access to the script's persisted state from the form, outside a run. None of the declarative hosts above offers that. Jenkins can do it only because the descriptor is a live singleton inside the server, which is a question for #106 about where action code runs.

## (e) Links in help text

- **n8n.** `description` is rendered as HTML. Nodes embed `<a href="...">Learn more</a>`, for example the OpenAI model field. `hint` is a second line of text, and a node-level hint "can use HTML".
- **Home Assistant.** A step `description` in `strings.json` is "Markdown that is shown with the step", and `data_description` gives per-field help. hassfest **rejects URLs in translatable strings**: "the string should not contain URLs, please use description placeholders instead" (`script/hassfest/translations.py`). The URL is passed from code through `description_placeholders={"docs_url": ...}` and the string uses `[Learn more]({docs_url})`.
- **VS Code.** `markdownDescription` is parsed as Markdown, and `#other.setting.id#` links to another setting.
- **RJSF.** `ui:enableMarkdownInDescription` / `enableMarkdownInHelp`, plus `ui:help` and `ui:placeholder`.
- **IntelliJ.** `comment()` accepts HTML except `<html>`, and "links with href to http/https are automatically marked with an additional arrow icon". `browserLink(text, url)` and `contextHelp(description)` also exist (`Cell.kt`, `Row.kt`).
- **Jenkins.** `<f:entry help="/plugin/x/help-foo.html">` adds a (?) icon that shows an HTML help page.

**Trade-off for Cereal.** The common choice is a Markdown subset in the description, or HTML in older and JVM hosts. HA keeps URLs out of the text because its strings are translated. Cereal scripts are not localized, so that reason does not apply. A separate `placeholder` attribute shows up in n8n, RJSF and HA (`suggested_value`), so it is a common enough extra to consider beside Markdown for #104 ("leave empty for the recommended model").

---

## What maps onto an annotation-based Kotlin API

Sketches only. They show the declaration shapes the prior art points to, not decisions.

```kotlin
// (a) layout-only sections: declared once, referenced by items; keyNames stay flat
@ScriptConfigurationSection(id = "advanced", title = "Advanced", position = 30, collapsed = true)
interface AgentConfiguration : ScriptConfiguration {

    @ScriptConfigurationItem(position = 31, keyName = "model", name = "Model",
        description = "See [models](https://...).",            // (e) markdown subset
        section = "advanced",
        optionsProvider = ModelOptions::class)                  // (b)
    fun model(): String
}

// (b) n8n loadOptionsDependsOn + resourceLocator list/id, Jenkins doFillXyzItems
object ModelOptions : OptionsProvider {
    override val dependsOn = listOf("provider", "apiKey")       // explicit: ScriptConfig reads aren't reflectable
    override val allowCustom = true                             // HA custom_value / n8n id mode
    override suspend fun options(config: ScriptConfig): List<Option> = ...
}

// (c)+(d) Jenkins validateButton: named action, fields it uses, inline ok/warning/error
@ScriptConfigurationAction(position = 5, name = "Test API key", progress = "Checking...", section = "...")
suspend fun testApiKey(config: ScriptConfig): ActionResult      // Ok(msg) / Warning(msg) / Error(msg)
```

Points for the decision tickets:

1. **Sections (#102).** Use the layout-only family (Jenkins, IntelliJ, VS Code). HA and n8n `collection` nest values and would change persisted keys. Declare each section once and reference it from items.
2. **Model options (#105).** n8n's own OpenAI node moved from a fetched dropdown to "list or ID". Fetched lists need explicit `dependsOn` in Kotlin, because unlike Jenkins `@QueryParameter`, the host cannot reflect over which fields a function reads.
3. **Key check and reset (#106).** Jenkins `validateButton` is one general construct that covers both. HA and n8n tie testing to submit or to the credential. Every host returns a three-level or OK/Error result with a message, and Jenkins and HA both show a progress state.
4. **Remote calls run on an explicit trigger,** never per keystroke, across all hosts surveyed. Jenkins documents the security risks of form-time endpoints that take secrets and URLs.
5. **Links (#104).** A Markdown subset is the dominant choice. `placeholder` is a common separate attribute.

## Sources

- n8n: `packages/workflow/src/interfaces.ts` (`INodePropertyTypeOptions`, `INodeProperties`, `ICredentialTestRequest`, `INodeCredentialTestResult`, `methods`), `packages/@n8n/nodes-langchain/nodes/llms/LMChatOpenAi/LmChatOpenAi.node.ts`, `packages/nodes-base/credentials/OpenAiApi.credentials.ts` (github.com/n8n-io/n8n, master); docs: [UI elements](https://docs.n8n.io/integrations/creating-nodes/build/reference/ui-elements/), [programmatic-style parameters](https://docs.n8n.io/integrations/creating-nodes/build/reference/node-base-files/programmatic-style-parameters/), [credentials files](https://docs.n8n.io/integrations/creating-nodes/build/reference/credentials-files/).
- Home Assistant: `homeassistant/data_entry_flow.py` (`section`, `SectionConfig`, progress tasks), `script/hassfest/translations.py` (URL rule, `data_description`, `sections`) (github.com/home-assistant/core); docs: [data entry flow](https://developers.home-assistant.io/docs/data_entry_flow_index/), [config flow handler](https://developers.home-assistant.io/docs/config_entries_config_flow_handler/), [options flow](https://developers.home-assistant.io/docs/config_entries_options_flow_handler/), [test-before-configure](https://developers.home-assistant.io/docs/core/integration-quality-scale/rules/test-before-configure), [selectors](https://www.home-assistant.io/docs/blueprint/selectors/), [core i18n](https://developers.home-assistant.io/docs/internationalization/core/).
- Jenkins: `core/src/main/java/hudson/model/Descriptor.java` (`calcFillSettings`, `buildFillDependencies`) (github.com/jenkinsci/jenkins); docs: [form validation](https://www.jenkins.io/doc/developer/forms/form-validation/), [form validation security](https://www.jenkins.io/doc/developer/security/form-validation/), [Jelly taglib reference](https://reports.jenkins.io/core-taglib/jelly-taglib-ref.html).
- IntelliJ: `platform/platform-api/src/com/intellij/ui/dsl/builder/{Panel,Row,Cell}.kt` (github.com/JetBrains/intellij-community); [Kotlin UI DSL v2](https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html).
- RJSF: [validation](https://rjsf-team.github.io/react-jsonschema-form/docs/usage/validation/), [uiSchema](https://rjsf-team.github.io/react-jsonschema-form/docs/api-reference/uiSchema/).
- VS Code: [contribution points: configuration](https://code.visualstudio.com/api/references/contribution-points).
- Obsidian: [settings](https://docs.obsidian.md/Plugins/User+interface/Settings).
