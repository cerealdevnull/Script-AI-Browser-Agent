# How is the layering enforced, in desktop-client and here?

Research for [#140](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/140), part of the clean-architecture map [#139](https://github.com/cerealdevnull/Script-AI-Browser-Agent/issues/139).
Sources read on 2026-10-17: desktop-client at `origin/HEAD` = `ab0b215`, this repo at `origin/master`, and Maven Central.

## Answer

- **desktop-client enforces nothing mechanically.** The layer rule is written down in the per-layer `AGENTS.md` files and checked in PR review. There is no Konsist, no ArchUnit, no forbidden-import rule and no module boundary.
- **The written rule has already drifted.** 20 domain files import an application class, and 3 application files import presentation code.
- **Recommended here:** one plain JUnit test, about 20 lines and no new dependency. It reads the `.kt` files under `aiagent/domain` and `aiagent/application` and fails on forbidden package prefixes. `NeutralTextTest` already scans `src/main/kotlin` the same way.
- **Cost:** a few milliseconds per `./gradlew test`, nothing in `release.jar`, and no ProGuard change. Konsist or ArchUnit would add a test dependency (Konsist pulls in a 58 MB Kotlin compiler) to answer a question a text scan already answers in a single-module, 10-file codebase.

## desktop-client (`origin/HEAD`)

| Mechanism | Present? | Evidence |
|---|---|---|
| Konsist / ArchUnit | No | No match for `konsist` or `archunit` anywhere in the tree, including `gradle/libs.versions.toml` |
| Architecture test | No | No test under `cereal-client/src/test` checks imports or packages |
| ktlint | Yes, style only | `build.gradle.kts` applies `org.jlleitschuh.gradle.ktlint` 14.2.0 and CI runs `ktlintCheck`. There are no custom rule sets, and ktlint's standard rules have no layer rule |
| detekt | Yes, not layering | `config/detekt/detekt.yml` (detekt 1.23.8) turns on `WildcardImport` but not `ForbiddenImport`. CI (`.github/workflows/ci.yml`) runs build, test, kover and ktlint but no `detekt` job. `CONTRIBUTING.md` asks for it to be run locally |
| Gradle modules | Not per layer | `settings.gradle.kts` includes `cereal-client`, `cereal-client:sekret`, `cereal-script-sample` and `cereal-licensing`. The `domain`, `application`, `infrastructure` and `presentation` layers are packages inside the single `cereal-client` module |
| Written rules | Yes | Root `AGENTS.md` (§ Imports: "Domain layer: **never** import Compose, Android, AWT, or infrastructure classes"), plus `cereal-client/src/main/java/com/cereal/client/{domain,application,infrastructure,presentation}/AGENTS.md`, each with a PR checklist |

ADR `docs/adr/0007-core-domain-vs-application-models.md` states the position directly:

> The split is convention, not tooling … The PR checklist and AGENTS.md rule are the enforcement surface.

The only mechanical guard that ADR mentions is the compiler: a type named by a domain interface cannot be moved out of the domain without breaking the compile.

The written rule already leaks, as a `git grep` on `origin/HEAD` shows:

- 20 files under `domain/model/exception/` contain `import com.cereal.client.application.exception.CerealException`, so the domain depends on the application layer.
- `application/interactor/script/validator/ScriptConfigurationValuesValidator.kt`, `application/script/ScriptInstanceValidation.kt` and `application/task/TaskManager.kt` contain `import com.cereal.client.presentation.tasks.script.overview.configuration.isValid`.
- No framework imports (Compose, AWT, OkHttp, Koin, Ktor, Retrofit, Room) appear under `domain/`. The parts of the rule that are easy to see hold. The cross-layer ones drift.

So we copy the rule's wording from desktop-client, but not its (missing) enforcement.

## This repo: what a check would guard

On `origin/master` the code is one package, so there are no layers yet. Map #139 fixes the target layout: `aiagent.domain`, `aiagent.application` and `aiagent.infrastructure` in one Gradle module, with `AiAgentScript` at the root. The things the domain must not touch are already confined by their imports:

- kdriver/Chrome: `dev.kdriver.*`, used only in `Browser.kt`. `KBrowser` is an import alias, `import dev.kdriver.core.browser.Browser as KBrowser`, so the check has to match the package `dev.kdriver.` and not the name `KBrowser`.
- The LLM component: `com.cereal.sdk.component.llm.LlmComponent`. SDK value types from the same package (`LlmTurn`, `LlmUsage`) stay allowed in the domain (#139 Notes), so the check forbids that one class and not the whole package.
- Our own outer layers: `com.cereal.script.aiagent.infrastructure.` (for domain and application) and `com.cereal.script.aiagent.application.` (for domain).

All three layers sit in different packages, so any reference across them needs an `import` or a fully qualified name. A plain text search for the forbidden prefix anywhere in the file catches both.

## Options and their cost here

Build facts that apply to every option. `shadowJar` packs `runtimeClasspath`, and `scriptJar` (ProGuard) reads only `build/libs/release.jar`. A `testImplementation` dependency therefore never reaches `release.jar`, `script.pro` or the mapping, under any option. The cost lies in the test classpath and in test time.

| Option | New dependency (Maven Central) | Test-time cost | Fit |
|---|---|---|---|
| **Plain JUnit source scan** | None (`kotlin("test")` and `java.io.File` are already used, see `NeutralTextTest`) | Reads about 10–20 small files: milliseconds | Enough. Forbidden prefixes cover every rule in #139 |
| Konsist `0.18.1` ([docs: `assertArchitecture`](https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert)) | `com.lemonappdev:konsist:0.18.1` (1.6 MB). Its POM adds runtime `kotlin-compiler-embeddable:2.4.21` (58.6 MB) and `kotlin-stdlib-jdk8:2.4.21` | Parses every source file with the Kotlin compiler PSI: seconds, plus a 58 MB first download | Poor. It would also raise the test classpath's stdlib to 2.4.21, while `libs.versions.toml` pins Kotlin 2.3.21 to match the host, so tests would run on a different stdlib than the host |
| ArchUnit `1.5.1` ([user guide: `layeredArchitecture()`](https://www.archunit.org/userguide/html/000_Index.html)) | `com.tngtech.archunit:archunit-junit5:1.5.1`: `archunit` (4.6 MB, which depends on `slf4j-api`), `-api` and `-engine` | Scans the compiled bytecode: well under a second for this size | Works, and it sees through typealiases and inlined code. Not needed while a ban on a few prefixes covers every rule |
| detekt `ForbiddenImport` ([rule docs](https://detekt.dev/docs/rules/style)) | A new Gradle plugin (`io.gitlab.arturbosch.detekt`) and its config | A separate `detekt` task, seconds per run | One forbidden list per rule set, scoped by path globs. It is a lint task, not a test, and it brings in a tool this repo doesn't use for anything else |
| Gradle modules | None | Slower configuration | Out of scope per #139 |

## Recommendation

Add one test, for example `LayeringTest`, in the slice that creates the packages. The shape:

```kotlin
class LayeringTest {
    private val root = File("src/main/kotlin/com/cereal/script/aiagent")
    private val outer = listOf("dev.kdriver.", "com.cereal.sdk.component.llm.LlmComponent", "com.cereal.script.aiagent.infrastructure.")

    @Test fun `domain is pure`() = assertNoRefs("domain", outer + "com.cereal.script.aiagent.application.")
    @Test fun `application does not touch infrastructure`() = assertNoRefs("application", outer)

    private fun assertNoRefs(layer: String, forbidden: List<String>) {
        val files = File(root, layer).walk().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources under $layer: package moved?")
        val hits = files.flatMap { f -> f.readLines().filter { l -> forbidden.any { it in l } }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), hits)
    }
}
```

Notes:

- The `isNotEmpty` guard stops the test from passing silently if the packages are renamed.
- The test runs as part of `./gradlew test` and needs no tag, Chrome or fixture server.
- The plain-text scan has known gaps: a forbidden type reached through a typealias declared in an allowed layer, and a string that happens to contain a forbidden prefix (which would fail the test, not pass it). Move to ArchUnit if either becomes real. Its `layeredArchitecture()` rule replaces this test in about the same number of lines.
- The layer rules write-up from #139 should name this test as the enforcement point, so the written rule and the check stay together. desktop-client has only the written half.
