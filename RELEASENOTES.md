# Release notes

## Unreleased

- The model now comes from the client's **Settings → AI**: set up your AI provider and model there. The task form's AI provider section (Provider, API key, Model, Server URL) is gone; values saved there are ignored. Your AI provider bills the model's usage. Costs are tracked for common models and counted as $0 otherwise, so Max steps per run is the only limit for those. Needs a client with Settings → AI; older clients keep the previous version.
- Sign-in secrets are now one list with a row per secret (a label and a masked value), with no limit of three. **Re-enter your secrets:** the old Secret 1–3 fields are gone and their values are not carried over. Duplicate your task, add a row for each secret under Sign-in secrets, using the same labels as before, and start the copy. A goal that uses a secret the list does not have now stops with a message naming it.
