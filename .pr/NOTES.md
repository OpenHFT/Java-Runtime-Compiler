# PR: Java-Runtime-Compiler#91 (VALID_FEATURE)

**Track C — Feature/docs PR**  ·  priority tier 6  ·  base `ea`  ·  branch `feat/Java-Runtime-Compiler-91-opt-in-methodhandles-lookup-child`
Issue: https://github.com/OpenHFT/Java-Runtime-Compiler/issues/91

## Planned change
Opt-in MethodHandles.Lookup/child-classloader path via JavaFileManager, keep Java 8 path; test JDK 17/21/25 without encapsulation flags.

## Quality bar (must clear before this PR merges)
- One issue, one PR; link with `Fixes #91`; scope limited to this issue.
- Regression test that fails before / passes after (re-enable the ignored test where one exists, else add one).
- Cross-repo discipline: Chronicle-Queue exposes integration points only; retention/roll-maintenance policy lives in CQE.
- Do not fork the in-flight QUEUE-143/144 PRs; branch off current `ea` and rebase.
- Docs + changelog updated; author credit retained on any rebase; CI proven green.

## Status
Ready to implement on this branch.
_This PR-NOTES commit is the local addressment scaffold; the code change lands on top._
