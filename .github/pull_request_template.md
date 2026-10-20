<!-- claude 2026-10-20: changed (L6): a short template for all contributors; the rules of AGENTS.md as a checklist -->
## What changes

<!-- One or two sentences: what works after the merge, and why. Link the issue. -->

## Checks (AGENTS.md)

- [ ] `sbt fmt`, then `sbt check` (in CI mode: `SBT_TPOLECAT_CI=1`, no warning)
- [ ] `bash scripts/version-order-test.sh`, `bash scripts/changelog-section-test.sh` and `bash scripts/edge-check.sh` <!-- claude 2026-10-20: changed: the changelog test (ADR 0020) -->
- [ ] A line in `CHANGELOG.md` under `## [Unreleased]`, in the subsection of each published module that the change touches <!-- claude 2026-10-20: created: this item (ADR 0020) -->
- [ ] `examples/ptycheck/run.sh`, if the change touches the terminal code or the examples
- [ ] A test for each change of behaviour (on `Headless` or a fake crossterm; no real terminal)
- [ ] The docs and the Scaladoc in ASD-STE100; a new Scala block of a Markdown file is a region of a source (`MarkdownRegionsSuite`)
- [ ] `compat.sbt` set to `Compatibility.None` if the change breaks binary compatibility
- [ ] No impure code outside an edge file, except a `KEEP` site with its reason
