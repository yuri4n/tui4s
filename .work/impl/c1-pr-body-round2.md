After the merge, a static kyo-ui value (`kyo.UI`) is laid out in terminal cells and painted into a jatatui buffer: `tui4s.internal.Layout` and `tui4s.internal.Paint`, carried from the prototype runner, compile under the strict flags and have golden-screen tests. The build moves to sbt-tpolecat (D-8) and the core depends on kyo-ui 1.0.0-RC7 (D-19). The kyo-ui surface guard (RC-12, C1 part) pins the `UI.Ast` types and the `Style.Prop` cases.

## P-1 One purpose

A kyo-ui value renders to cells. Nothing else works yet: no signals, no keys, no terminal (see P-12).

**Round 2 (2026-10-12, after the Codex review):** six bugs of the carried layout and paint code are fixed, one commit each with its regression test (P-6); the tip is f525808. The sections below say "round 2" where they changed.

## P-2 Size

Hand-written main lines, added or changed: **68** (`git diff origin/main..HEAD --numstat -- modules/core/src/main`, without the four carried files; plus the delta inside the carried files from the format commit): `internal/Render.scala` 45, `internal/package.scala` 8, `tui4s/package.scala` 12, `internal/Layout.scala` +3/-6 (the strict-flag fix). The carried files are **1671 lines at the tip** (Geometry 68, Props 383, Layout 763, Paint 457): 1674 after the format commit 774ca64, minus 3 lines of the flag fix 3c10d8c (see P-8). Tests: 668 lines (503 Scala and the 165-line surface list). Build and docs: 181 lines. Whole PR: 21 files, +2585/-39. Limit: about 1500.

## P-3 Working base

Base: `origin/main` = 863db67 (green CI: build, CI, release automation, AGENTS.md). This PR adds to it. `sbt check` on the tip 66d9299, alone on the machine and in CI mode (`SBT_TPOLECAT_CI=1`): green, 26 tests passed, 0 warnings (`.work/impl/c1-final-check.log`). `bash scripts/version-order-test.sh`: 55 of 55 cases passed (`.work/impl/c1-final-version-order-test.log`).

## P-4 Final shape first

This PR adds no public API in package `tui4s`: `tui4s/package.scala` is a Scaladoc-only package object. The carried types live in `tui4s.internal` (Loc, Rect, Size, Sides, Props, TextAttrs, Glyphs, Border, Dir, Content, Scene, Kind, Node, Laid, Damage, Item, Flat, Layout, Canvas, Paint). They are public inside that package, as in the prototype; the package name says "not API". The render helper `tui4s.internal.Render` and the `discard` helper are `private[tui4s]`. The C2 API review (RC-36) decides if `tui4s.internal` gets a `private[tui4s]` seal.

## P-5 Never port the replaced

No file of this PR is replaced by a later PR of the stack. The four carried files are the final home of layout and paint; C2 adds `World`, `Mount`, `Runner` next to them. `Keys.scala` (replaced by `EventMapping` in T1) is NOT carried.

## P-6 Fix with the code

Round 1: no known bug of the carried files was fixed (the overflow fix of 2026-10-03 is already in the prototype's `Layout.arrange`). Negative controls in the P-6 style: the surface guard (P-13, `.work/impl/c1-guard-negative-control.log`) and the CI-mode `-Werror` (P-13, `.work/impl/c1-ci-mode-negative-control.log`).

Round 2: the Codex review found six bugs in the carried code. All six are real (verified by reading the code before the fix). Each has its own commit with a regression test. The negative control is one run of the six new tests against the old code 66d9299 (a temporary worktree with only the tests): all six fail for the reported reason (`.work/impl/c1-fix-negative-control.log`: 5 golden screens differ; `c1-fix-negative-control-relayout.log`: the second cells at x=2 and x=9).

| Bug (Codex thread) | Commit | Test |
| --- | --- | --- |
| A long word was split by UTF-16 units, not by cells (`Layout.scala:114`) | d028b2c | GoldenScreenTest "a long word of wide characters splits by cells, not by characters" |
| Flex-shrink rounded every share up: three one-cell items in two cells all got width 0 (`Layout.scala:399`) | 49dfcf0 | GoldenScreenTest "flexShrink removes only the deficit; the last item loses the cell" |
| The table relayout compared the new columns with themselves; a retained row kept stale columns (`Layout.scala:703`) | d0ac460 | RelayoutTest "a retained table row follows the new column widths" (a region swaps the widths of two cells, so the table keeps its size) |
| `rowReverse` and `columnReverse` lost the reverse flag (`Props.scala:303`) | 707fd10 | GoldenScreenTest "rowReverse and columnReverse place the items from the end" |
| A percentage height resolved against the screen, not the parent (`Layout.scala:264`) | d46f2d0 | GoldenScreenTest "a percentage height is relative to the parent, not to the screen" |
| A constant range value painted at zero (`Paint.scala:393`) | f525808 | GoldenScreenTest "a range input shows its constant value" |

The percentage fix is the one with substance: `measureItem` and `measureElement` take the height of the containing block when it is definite (the screen, an explicit height, or a size the flex parent imposed; `Layout.definiteContentH`), and a percentage height under an auto-height parent is `auto`, as in CSS. The relayout path uses the owner's definite content height, as the first layout does. The reverse fix adds a `reverse` field to `Props`; `arrange` places a reversed container's items in the opposite order with the main start at the far end (so `justify: start` packs them there, as in CSS) and returns the rects in the items' own order.

## P-7 Commit map

| # | Commit | What |
| --- | --- | --- |
| 0 | 8084cef build | sbt-tpolecat 0.5.7 (D-8), kyo-ui at `Versions.kyo`, jatatui-widgets dropped (D-12), kyo-test-runner and tasty-query (`Versions.tastyQuery = "1.9.0"`), forked test JVM, `SBT_TPOLECAT_CI` in `ci.yml`. The empty core compiles under the new flags; one throwaway file compiled with 0 warnings (`.work/impl/c1-step0-compile.log`). |
| 1 | 83b7d38 carry | `Geometry`, `Props`, `Layout`, `Paint` = the blobs of fe06611 (spike S6 carry): new path and package only. `.work/impl/c1-carry-verify.log`: every code line of the prototype is in the carried file; the carry adds only the package line and one carry-mark line per file. Does not compile alone by design (P-7). |
| 2 | 774ca64 format | `sbt fmt` only, plus the `// claude <date>` marks removed: **6 before** (4 carry headers, 2 prototype marks: `Layout.arrange`, `Paint.apply`), **0 after**. The Layout mark becomes a plain comment that keeps its explanation; the Paint mark repeated the Scaladoc and is deleted. 1060 lines become 1674 (the commit message says 1685: that is the count before the mark removal). `project/Versions.scala` comments shortened to 80 columns and reworded (scalafmt had re-wrapped them into block comments; see "Problems", R1-5). |
| 3a | dae6b81 build | `-Ycheck-all-patmat` excluded from the tpolecat set (a false alarm on Kyo's opaque `Maybe`; see "Problems"). |
| 3b | 3c10d8c flags | The one warning in the carried files: E198 unused parameter `kids` in `Layout.naturalOf` (S6 found the same). **3 lines changed** (+3/-6 with the mark line). 0 warnings after (`.work/impl/c1-step3-warnings.log`). |
| 4a | 55400a1 render | `private[tui4s] Render` (one frame of a static value into a `TestBackend`, as the runner's first frame), `internal/package.scala` `discard` (OS-12 part), `ScreenSuite`, `RenderTest` (2 cases). kyo-test runs under sbt 1.13 in the forked JVM. |
| 4b | c94a6d1 test | `GoldenScreenTest`, 22 cases: borders, colours and text attributes, row and column flex, gap and padding, overflow clip, text wrap in a column, wide characters, the Img and SvgNode placeholders, decorations. Test JVM prints UTF-8. |
| 4c | 2eb2022 test | `KyoUiSurfaceTest` + `src/test/resources/kyo-ui-surface.txt` (RC-12, C1 part). |
| 4e | a12bd0c docs | `tui4s/package.scala` Scaladoc (OS-13 part). |
| 4f | 8373e76 docs | AGENTS.md and README (P-10). |
| 4g | 9a974d2 docs | `.github/pull_request_template.md`. |
| 5 | ef59f61 format | `sbt fmt` on the files that C1 adds. |
| 6a | 31e73c7 docs | Review R1-3, R2-5, R2-7: AGENTS.md names tasty-query among the shared versions with the reason (the Versions.scala comment too); the cats row of the modules table aligns. |
| 6b | 0c2093a docs | Review R1-4, R2-3: the package Scaladoc in plain words, no PR ids. |
| 6c | a316c9c build | Review R1-7, R2-2: the `-Wconf` comment says that the rule does nothing under the tpolecat set (no `-Wunused:nowarn`) and why it stays. Comment only. |
| 6d | 66d9299 test | Review R1-6: the surface list gets its `# claude 2026-10-12: created` line (the parser skips `#` lines; the 157 data lines are the same). |
| 7a | d028b2c fix | Round 2: wrap by cell width (`Layout.chunksByWidth`); test. |
| 7b | 49dfcf0 fix | Round 2: flex-shrink cuts add up to the deficit (largest-fraction rounding, the last item first when equal, never below the floor); test. |
| 7c | d0ac460 fix | Round 2: the table relayout compares the old node's columns (`o.cols == tableCols`); RelayoutTest. |
| 7d | 707fd10 fix | Round 2: `Props.reverse`; `arrange` for reversed containers; test. |
| 7e | d46f2d0 fix | Round 2: `availH` through `measureItem`/`measureElement`, `definiteContentH`, `naturalOf`; test. |
| 7f | f525808 fix | Round 2: the range input reads its constant value; test. |

Round 2 changed 4 files (+195/-34 after `sbt fmt`; the carried `Layout.scala`, `Props.scala`, `Paint.scala` and `GoldenScreenTest.scala`) and added `RelayoutTest.scala`. Step 4d (OS-12 `discard`): in 4a; `Render` and the tests use it. `Paint` keeps its own private `discard`, as carried (C2 can unify). The review fixes 6a-6d change no code: `sbt fmt` after them changed nothing (`.work/impl/c1-fix-fmt.log`).

## P-8 Delta guide

The carried files come from the prototype `.work/proto/kyoui-runner/src/tui4s/kyoui/internal/` (commit 1d100ed) through the S6 carry fe06611 (new path and package only). The format commit 774ca64 is the formatted, mark-free state. The hand-written delta is the diff from 774ca64. The review fixes of round 1 (6a-6d) do not touch the carried files; the round-2 fixes (7a-7f) do: `git diff 774ca64 f525808 -- <carried file>` shows the flag fix plus the round-2 fixes, each marked `claude 2026-10-12 ... (PR #10 review: Codex, <file>:<line>)` at the changed fragment.

| New file | Source | Delta command |
| --- | --- | --- |
| `modules/core/src/main/scala/tui4s/internal/Geometry.scala` | `fe06611:modules/core/src/main/scala/tui4s/internal/Geometry.scala` | `git diff 774ca64 66d9299 -- modules/core/src/main/scala/tui4s/internal/Geometry.scala` (empty); whole: `git diff -w fe06611 66d9299 -- <path>` |
| `modules/core/src/main/scala/tui4s/internal/Props.scala` | `fe06611:modules/core/src/main/scala/tui4s/internal/Props.scala` | `git diff 774ca64 66d9299 -- modules/core/src/main/scala/tui4s/internal/Props.scala` (empty) |
| `modules/core/src/main/scala/tui4s/internal/Layout.scala` | `fe06611:modules/core/src/main/scala/tui4s/internal/Layout.scala` | `git diff 774ca64 66d9299 -- modules/core/src/main/scala/tui4s/internal/Layout.scala` (+3/-6: `naturalOf`) |
| `modules/core/src/main/scala/tui4s/internal/Paint.scala` | `fe06611:modules/core/src/main/scala/tui4s/internal/Paint.scala` | `git diff 774ca64 66d9299 -- modules/core/src/main/scala/tui4s/internal/Paint.scala` (empty) |

Format delta (carry to format commit): `git diff --stat 83b7d38 774ca64 -- modules/core` = 4 files, +1196/-582 (`-w`: +831/-217). Whole delta from the S6 carry, the four files named (fe06611 also holds the files of C2, C3 and T1 under the same directory, so the command must name the files): `git diff -w --stat fe06611 66d9299 -- modules/core/src/main/scala/tui4s/internal/{Geometry,Props,Layout,Paint}.scala` = 4 files, +829/-218.

## P-9 No rewrite

No amend, rebase or force push. The review fixes are the new commits 6a-6d (round 1) and 7a-7f (round 2).

## P-10 Docs true at each merge

AGENTS.md: "What tui4s is" (a terminal runner for kyo-ui; the DSL is kyo-ui; the modules table has a "UI DSL" column: core kyo-ui, zio and cats a DSL over kyo-ui, planned; what the core does today), Toolchain (sbt-tpolecat modes: DevMode by default, `SBT_TPOLECAT_CI` in CI, `tpolecatCiMode` for a local session), Rules for code (the S6 Java-boundary rule: `-Wnonunit-statement`, `-Wvalue-discard`, `-Yexplicit-nulls` at a Java call; check for null where the Java source or the JNI contract allows it), Daily commands (core tests use kyo-test), Dependencies and versions (`project/Versions.scala` holds tasty-query too, with the reason). README: "What tui4s is" lines; the zio and cats rows say "planned". The published package Scaladoc says what this version does without PR ids. All true after the merge: the core renders a static value; the runner, keys and terminal are later PRs.

## P-11 WIP limit

C1 is the first PR of the kyo-ui stack; the user has reviewed none yet. 1 PR above the newest reviewed state (`main`).

## P-12 Not in this PR

- `World`, `Mount`, `Runner`, `SchedulerProbe`, `Headless`, `Screen`, `FrameStats`, `spi.Settle`: C2.
- `Dispatch` (keys reach the UI) and the write seam: C3.
- `Component`, `Hooks`, `ComponentSpec`: C4.
- The terminal: `Keys` (replaced by `EventMapping`), `TerminalIO`, `Session`, `InputThread`, `OutputGuard`, `Tui.run`: T1.
- The surface guard's rule hashes (dispatch, regions, focus; shape hash; literal negative control): C2 and C3.
- Examples, PTY cases, docs site: T1, T2, D.
- A `private[tui4s]` seal of `tui4s.internal` and one shared `discard` (Paint keeps its own): the C2 API review (RC-36).
- `-Wunused:nowarn`: not in the tpolecat set; the `-Wconf` rule waits for it (R1-7, R2-2). `SBT_TPOLECAT_CI` in `release.yml`: not planned; the release builds a commit that passed `ci.yml` in CI mode (R2-6, refuted). A "today" sentence in the README: not added; the README sends the reader to AGENTS.md, which has it (R2-4, refuted).

## P-13 Zero warnings

**Round 2, at the tip f525808:** `SBT_TPOLECAT_CI=1 sbt clean check` alone: exit 0, 0 `[warn]`, 0 `[error]`, 32 tests passed (GoldenScreenTest 27, RenderTest 2, KyoUiSurfaceTest 2, RelayoutTest 1), scalafmt checks and `versionPolicyCheck` green (`.work/impl/c1-fix-final-check.log`); `bash scripts/version-order-test.sh`: 55 of 55 (`c1-fix-version-order-test.log`).

**Round 1, at 66d9299: 0 warnings.** `sbt check` alone in CI mode (`SBT_TPOLECAT_CI=1`, so `-Werror` is on) at 66d9299: 26 tests passed (RenderTest 2, GoldenScreenTest 22, KyoUiSurfaceTest 2), scalafmt checks and `versionPolicyCheck` green, 0 `[warn]` lines, 0 `[error]` lines, exit 0 (`.work/impl/c1-final-check.log`; the full compile of the 7 main and 5 test sources under the same options is `.work/impl/c1-check.log` at ef59f61; the review fixes change one main source, which 66d9299 recompiled).

CI mode shown (review R2-1): `SBT_TPOLECAT_CI=1 sbt "show core/tpolecatOptionsMode" "show core/scalacOptions" "show core/Test/scalacOptions"` prints `CiMode` and the option list with `-Werror` and without `-Ycheck-all-patmat`, for Compile and Test (`.work/impl/c1-ci-mode-options.log`). Negative control: one throwaway file with an unused local under `SBT_TPOLECAT_CI=1`: `core/compile` reports E198 and `No warnings can be incurred under -Werror`, sbt exits 1; the file is removed, the tree is clean (`.work/impl/c1-ci-mode-negative-control.log`).

Effective options: the tpolecat default for 3.9.0 (`-encoding utf8 -deprecation -feature -unchecked -language:experimental.macros -language:implicitConversions -Xkind-projector -Wvalue-discard -Wnonunit-statement -Wunused:implicits,explicits,imports,locals,params,patvars,privates`) plus `-Yexplicit-nulls` and the `-Wconf` rule for the `@nowarn` noise; minus `-Ycheck-all-patmat`; plus `-Werror` in CI mode. Scala 3.9.0 accepts every option (no "bad option", no deprecation notice). The hand-written `-Wunused:all` of the old build also turned on the `nowarn` check (unused `@nowarn`), which the tpolecat set does not; that check is the only source of the message that the `-Wconf` rule silences, so the rule does nothing today and the effective checks are the same (R1-7, R2-2).

Surface guard negative control (acceptance 6.1): with the `UI.Ast.Div` line removed from `kyo-ui-surface.txt`, `sbt "core/testOnly tui4s.KyoUiSurfaceTest"` fails with `ADDED ast.type UI.Ast.Div case class public extends UI,Block,Interactive` and sbt exits 1; with the full list the test passes (`.work/impl/c1-guard-negative-control.log`, `.work/impl/c1-step4c-guard.log`). The reader's output equals the spike S3 dump of kyo-ui 1.0.0-RC7 for these kinds (81 `ast.type` + 76 `enum.case Style.Prop.*` = 157 lines; field lists sorted by name). The list file has 165 lines: 8 `#` lines (one is the mark) and 157 data lines.

`grep -rn 'jatatui.widgets' modules/core`: 0 hits.

Marks: every new or changed file has a `claude 2026-10-12` mark, except the carried files, which lose their marks by rule (Layout has the one of the flag fix).

## P-14 New sbt projects

None. No `version`, no `publishTo` set. `Versions.tastyQuery` is in `project/Versions.scala` because the facade tests will use it too (AGENTS.md "Dependencies and versions" says so since 6a).

## Review fixes

Reviewers R1 and R2, skeptics K1 and K2. No blocker, no major. All findings are minor.

| Finding | Verdict | Fix |
| --- | --- | --- |
| R1-1 the `-w` delta command gives +882/-1129 as written | confirmed | PR body P-8: the command names the four files (+829/-218). |
| R1-2 "1671" and "1674" both called "after the format" | confirmed | PR body P-2: 1671 at the tip = 1674 after the format commit minus 3 lines of the flag fix. |
| R1-3, R2-5 AGENTS.md shared-version list has no tasty-query | confirmed | 31e73c7: the list names tasty-query with the reason; the Versions.scala comment too. |
| R1-4, R2-3 the package Scaladoc names PR ids | confirmed | 0c2093a: plain words. |
| R1-5 the format commit also rewords Versions.scala comments | confirmed | Not changed (P-9 forbids a rewrite); disclosed in P-7 row 2. Lesson for C2: the build commit is fmt-clean before the carry. |
| R1-6 the surface list has no mark | confirmed | 66d9299: a `#` mark line. |
| R1-7, R2-2 the `-Wconf` rule is inert; "the same checks" is not exact | confirmed | a316c9c: the comment says it; PR body P-13 says it. The rule stays (OS-39). |
| R2-1 no log shows `-Werror` in CI mode | confirmed | `.work/impl/c1-ci-mode-options.log` (option list in CiMode) and `.work/impl/c1-ci-mode-negative-control.log` (a warning fails `core/compile`, exit 1). |
| R2-4 README core row says "in the terminal" before T1 | refuted (K2) | Not changed: a purpose statement that plan 6.1 asks for; the status line and AGENTS.md give the today-state. |
| R2-6 release.yml has no `SBT_TPOLECAT_CI` | refuted (K2) | Not changed: the task scopes the variable to `ci.yml`; `release.sh` refuses a tag without a green `ci.yml` run. Noted in P-12. |
| R2-7 the cats row is wider than the header; "will let" | confirmed (alignment only) | 31e73c7: the column is 39 wide in every row. The tense stays: "will" is approved STE. |
| R2-8 the guard needs the forked JVM | refuted (K2) | Not changed: `Test / fork := true` is the rule of the module; the finding asks for no change. |

## Problems and decisions in this PR

- sbt-tpolecat's default set has `-Ycheck-all-patmat`, a compiler-testing flag. On the carried `Props.isFocusable` it reports a match on Kyo's opaque `Maybe` (`Present`, `Absent`) as not exhaustive: a false alarm that every later PR would meet. Excluded with `ThisBuild / tpolecatExcludeOptions += ScalacOptions.privateCheckAllPatmat` (dae6b81). Sealed types keep their exhaustivity check.
- Hand-written `-Wunused:all` is replaced by tpolecat's granular `-Wunused:*` set: the same checks, except the `nowarn` check (see P-13). The `-Wconf` rule stays for the day that check is on.
- The format commit 774ca64 also rewords the `project/Versions.scala` comments, because the build commit 8084cef was not fmt-clean (its comments were over 80 columns). Disclosed, not rewritten (P-9).
- kyo-ui's base CSS has `ul, ol { list-style: none }`, so a list marker needs an explicit `listStyle`; the golden test documents it. A root-level block stretches to the screen width (flex `align: stretch`); a button keeps its width inside a row. The cell under the right half of a wide character reads as a space.
- Maven Central answered HTTP 429 at the first sbt start (3 retries with a backoff).

## Logs (`.work/impl/`, on branch `wip/handoff`)

Implementer: `c1-notes.txt`, `c1-step0-compile.log`, `c1-carry-verify.log`, `c1-step2-fmt.log`, `c1-step3-warnings.log`, `c1-step4a-test.log`, `c1-step4b-test.log`, `c1-step4c-guard.log`, `c1-guard-negative-control.log`, `c1-fmt-final.log`, `c1-check.log`, `c1-version-order-test.log`. Skeptic K1: `c1-k1-check.log`. Fixer: `c1-fix-fmt.log`, `c1-ci-mode-options.log`, `c1-ci-mode-negative-control.log`, `c1-final-check.log`, `c1-final-version-order-test.log`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_013i7PNgzYKkSSTk4McEPUvn
