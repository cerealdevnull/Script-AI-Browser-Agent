# AI Browser Agent (Cereal script)

A Cereal marketplace script: the user types a goal and a start URL, picks the model (and with it the AI provider) in the client's Settings → AI, and the
script drives their installed Chrome (headed, one profile per task) until the goal is met, fails, or a limit is hit.
Spec: [desktop-client#69](https://github.com/Cereal-Automation/desktop-client/issues/69). Vocabulary: `CONTEXT.md`.

## Requirements
* JDK 21 (kdriver 0.6.1 is built for Java 21)
* Google Chrome (the tests launch a real, visible Chrome; on Linux CI run them under `xvfb-run`)

## Commands
```sh
./gradlew test           # tests: execute() with a scripted model and real Chrome on local fixture pages
./gradlew test -Pheadless=true  # same, with Chrome headless (no window, no xvfb needed); the default under Claude Code
./gradlew scriptJar      # shrunk script jar -> build/cereal/release.jar (~5.5 MB)
./gradlew scriptJarTest  # loads that jar through a host-like ScriptClassLoader and runs one goal
```

## Packaging rules
* `kotlin-stdlib`, `kotlinx-coroutines` and `cereal-sdk` are provided by the host and never bundled.
  Kotlin is pinned to the host's stdlib version (2.3.21).
* kdriver and kotlinx-serialization are bundled (the host's copies are obfuscated). OkHttp is test-only. No vendor LLM SDKs.
* ProGuard rules live in `proguard-rules/`. ProGuard strips Kotlin metadata, so after shrinking `scriptJar` puts the
  compiler's original classes back for what the host reads with kotlin-reflect: the `AgentConfiguration` interface and
  its nested rows (the form: nullability, defaults) and the `*State` objects (each field's `stateModifier`
  objectInstance; without metadata it is null and the form fails to build). The restored classes call some code by its
  original name, so `script.pro` keeps those names in `AgentConfigurationKt` (the shared field checks). When a restored
  class calls something new, keep it there too; `ScriptJarTest` loads every field's objects from the jar.
* `src/main/resources/autoconsent/` is vendored unmodified from npm (version and sha256 in its `VERSION`); never edit
  or port it. Our own bridge to it is `consent-glue.js`. To upgrade: replace both files from the new npm package and
  update `VERSION`.

## Notices
Includes @duckduckgo/autoconsent v16.43.1 (MPL-2.0), source: https://github.com/duckduckgo/autoconsent
