# AGENTS.md

## Scope
- Chronicle Runtime Compiler (OpenHFT) repository.

## Build and test
- Preferred full check:
  - `mkdir -p logs`
  - `mvn verify -l logs/mvn-verify.log`
- Module-scoped example:
  - `mvn -pl <module> -am verify -l logs/mvn-verify.log`
- Test example:
  - `mvn -Dtest=ClassName test -l logs/mvn-test.log`
- Review logs:
  - `rg -n '^\[(WARNING|ERROR)\]|SLF4J\(W\)|\bWARNING:|\bwarning:' logs/mvn-verify.log`
- Do not commit logs/.

## Constraints
- Java baseline: 8 (avoid newer language features).
- Use a full JDK (8, 11, 17, or 21). On Java 11+ you may need add-exports/add-opens flags from `README.adoc`.
- Source files must stay ISO-8859-1 (code points 0-255). Prefer ASCII; avoid smart quotes and non-breaking spaces.
- Preserve public APIs unless explicitly requested.
- Treat warnings as defects; keep logs clean.

## Docs and review checklist
- Keep AsciiDoc, tests, and code synchronised; update docs for behaviour changes.
- Javadoc must add behavioural contracts, edge cases, thread safety, units, or performance notes.
- Run `mvn spotless:apply` before pushing changes.
- For large mechanical changes, declare the transformation rule and keep it consistent.

## References
- `src/main/docs/decision-log.adoc` and `src/main/adoc/project-requirements.adoc`.
- `OpenHFT/docs/Company-Wide-Tagging.adoc` for tagging and AsciiDoc conventions.
