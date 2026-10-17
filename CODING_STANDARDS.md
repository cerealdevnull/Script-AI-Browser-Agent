# Coding standards

Judgement calls for review. Mechanical rules live in tests and CI instead: `NeutralTextTest` keeps provider names and model ids out of shipped strings, and the `sign-off` job in `.github/workflows/push.yml` requires `Signed-off-by` on every commit.

## A doc the diff touches is true end to end

When a change makes a line in `readme.md`, `CONTEXT.md`, `STORELISTING.md`, `docs/` or a code comment false, the same diff fixes it, including lines the ticket never named. A doc the diff edits is read in full, not just the edited hunk. Flag any claim the change contradicts, such as a dependency it calls bundled that is now test-only, or a secret it says gets scrubbed that no longer exists.

## No dead branches after a merge

A merge of parallel work leaves no unreachable code: a `when` arm a new `catch` above it already takes, a branch kept "only so the when stays exhaustive", a fallback no caller reaches. Restructure so the exhaustive `when` handles each case where it is live, or delete the arm.
