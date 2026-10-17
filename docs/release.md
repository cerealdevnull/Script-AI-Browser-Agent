# Release checklist: model calls through Settings → AI

From the spec, issue #129. Every box is ticked before the release goes out.

- [ ] A desktop client release contains `LlmComponent` (desktop-client#114 is merged but in no released client yet).
- [ ] L1–L5 closed; `cereal-sdk` 1.14.0, `version_code` 2 in `src/main/resources/manifest.json`.
- [ ] Manual check in that client: the `toolshop-invoice` Once goal (`field-tests/toolshop-invoice.json`) with Settings → AI
      set up, and one run with Settings → AI empty, which shows the `NotConfigured` message.

Clients older than the one with `LlmComponent` silently keep the previous release.
