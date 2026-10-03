<!-- claude 2026-10-03: created this file (planning agent "reviewability first"; no build, no checkout, no commit) -->
# Plan draft: the kyo-ui rework as a stack of small, reviewable PRs

Status: DONE (2026-10-03). Planning only. I did no build, no checkout, no commit, no push and no GitHub write.
I read GitHub with `gh api` (read only) and git refs with `git show` / `git ls-tree` / `git log`.
Angle: REVIEWABILITY FIRST. Each PR has one purpose, is green, can be read in one sitting (aim: at most about
1500 changed lines of main code; tests are extra), and sits on top of a base that already works. The PR history
has as little churn as possible.

Short names for the evidence:

- OS = `.work/design/plan/inventory-old-stack.md` (items OS-1..OS-50, decisions D1-D12).
- RC = `.work/design/plan/inventory-runner.md` (items RC-1..RC-36, decisions D1-D12, findings K1-K9).
- FC = `.work/design/plan/inventory-facades.md` (items FC-1..FC-38, decisions FC-D1..FC-D11).
- RR, FR, IR = the REPORT.md of `.work/proto/kyoui-runner`, `kyoui-facades`, `kyoui-integration`.
- `proto/` = `.work/proto/`. Git refs are `origin/<branch>`. "PR 5 head" = `origin/feat/core-3-terminal`.
- ASSUMED = a working assumption that the user accepted with "I like it, go ahead". The user must still confirm it.
- PD-n = a decision of this plan (section 8). K-n = a risk (section 6).

---

## 0. Summary

1. The inventories propose six code PRs: (a) runner core, (b) terminal and app, (c) examples, PTY check, bench,
   then (gen), (zio), (cats). Two of them are far over the size aim. (a) holds the whole runner (2257 prototype
   lines, about 25% more after the 80-column format: section 9, item 1) plus fixes and features. (b) holds about
   1650 carried main lines plus about 1900 test lines (RC section 3.4).
2. This plan makes 15 PRs in ONE linear stack: 4 runner PRs, 2 terminal/app PRs, 4 facade PRs, 2 layout PRs,
   1 trace/bench PR, then 1 docs PR and 1 rules PR. The largest PR (T1, the real terminal) has about 1250 main
   lines, and about 1150 of them are carried from PR 5 with a delta guide.
3. A reviewer always reads a PR on top of a base that works: render, then signals, then input, then components,
   then the real terminal, then the main class with examples and the CI `terminal` job, then the facades.
4. Low churn: each public API lands in its final shape the first time (Kyo-free options R4 and quit as a value R5
   land in the runner PRs; the error channel lands with `Tui.run`). No PR ports code that a later PR of the stack
   replaces (`Keys.scala`, `OutputGuard`, the prototype terminal code, `TuiRun`, `CountingBackend`, the HTML
   stand-in, facade `Component.mount`, the UTF-16 text editing). Known bugs are fixed in the PR that brings the
   code (R1, R2, the three open bot comments), each in its own commit with a regression test.
5. GitHub objects: close PRs 3, 4, 5 and open new PRs from new branches (option A of OS section 5). No force push,
   no rebase. Review fixes go up the stack with `git merge` of the lower branch.
6. Merge with merge commits (not squash): then a stacked PR needs no rebase after its base merges. The repo has
   `delete_branch_on_merge: false` (`gh api repos/yuri4n/tui4s`), so GitHub does not retarget the next PR by
   itself. The user turns that setting on, or edits the base of the next PR by hand.
7. Every inventory item has a place (section 4): a PR, a deferral with a reason, or a drop.
8. Versioning: all modules stay unreleased; every `compat.sbt` stays `Compatibility.None`; `project/Versions.scala`
   gets kyo-ui as a note on the shared `kyo` constant (same version) and a new shared `tastyQuery` constant.
9. Implementation needs about 90 agent runs in 12 small workflows (at most 4 agents at once, at most 2 that build;
   one workflow of 14 agents must be announced). Two review gates with the user stop the work where an API decision
   ripples up the stack, and a third gate is the final review and merge.
10. Corrections to the inventories (section 9): PR 5 `GuardTest` drops (RC-27 lists it as carried); two branches
    are not in the inventories (`chore/cleanup-7p2rll`, `first-take`); the format growth makes (a) too large.

---

## 1. Rules for a reviewable stack

These rules apply to every PR of section 2. The PR description says how the PR meets them.

| Rule | What | Why |
| --- | --- | --- |
| P-1 One purpose | The PR title is one sentence that says what WORKS after the merge. | The reviewer knows what to check. |
| P-2 Size | At most about 1500 changed lines of hand-written main code. Tests, generated files, and carried files with a delta guide are extra. | One sitting. |
| P-3 Working base | The base PR is green (`sbt check`; from T2 on also the CI `terminal` job). The new PR adds to it. | The reviewer never reads code that sits on broken code. |
| P-4 Final shape first | A public type or method lands in its final shape. Later PRs only ADD members or fields. | No API churn in the history. |
| P-5 Never port the replaced | Do not port code that a later PR of this stack replaces. | No "add, then delete" in the history. |
| P-6 Fix with the code | A known bug is fixed in the PR that brings the code, in a separate commit, with a regression test. | The reviewer sees the fix and its proof. |
| P-7 Commit map | Commits in this order: (1) carry the files unchanged (new path and package only), (2) format only (`sbt fmt`), (3) strict-flag fixes, (4) one commit per behaviour change, each with its test. | The reviewer can skip (1) and (2) and read (3) and (4). |
| P-8 Delta guide | The PR body has a table: new file, source ref, and the command that shows the delta, for example `git diff origin/wip/handoff:.work/proto/kyoui-runner/src/tui4s/kyoui/internal/Mount.scala HEAD:modules/core/src/main/scala/tui4s/internal/Mount.scala`. | Carried code is read as a delta, not as new code. |
| P-9 No rewrite | Review fixes are new commits on the PR branch. Then each upper branch gets `git merge <lower branch>`. No rebase, no force push. | The user prefers non-destructive moves. |
| P-10 Docs true at each merge | Each PR changes the AGENTS.md / README lines that its content makes true or false. | Agents that implement the next PR read AGENTS.md (CLAUDE.md includes it). |
| P-11 WIP limit | Implementation runs at most 2 PRs ahead of the user's review. | A review change in a low PR costs little rework above it. |
| P-12 Not in this PR | The PR body lists what is NOT in the PR and the PR that brings it. | The reviewer does not ask for work that is planned later. |

The carry commit (P-7 step 1) does not compile alone. That is acceptable with merge commits: bisect the history of
`main` with `git bisect --first-parent` (merge commits only). Alternative: no separate carry commit (every commit
compiles, but then the delta guide of P-8 is the only way to see the change).

---

## 2. The PR stack

### 2.1 Overview

Base of the stack: `main` if the user merges PR 1 first (recommended, PD-3), else `chore/cleanup`. For this work both
have the same content (`origin/chore/cleanup` has build files only and no Scala source: `git ls-tree`).

Size = hand-written main lines, estimated. Source counts are `wc -l` of the prototype or of the PR 5 head; prototype
counts grow by about 25% under the 80-column format (section 9, item 1). G = generated lines (not in the budget).
Agents: impl = implementer (builds), rev = reviewer (read only), skep = skeptic (checks the findings; may run a
test), fix = fixer (builds).

| # | Branch | Base | Purpose (what works after the merge) | Main / test lines | Size | Agents |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | `feat/kyoui-01-render` | `main` (or `chore/cleanup`) | A kyo-ui `UI` value renders to terminal cells (layout and paint; no signals, no input). | ~1300 / ~450 | M | 6: impl, 2 rev, 2 skep, fix |
| C2 | `feat/kyoui-02-regions` | C1 | Signals drive the screen: a headless run repaints only the regions whose signals changed; `Headless` and `Screen` test kit. | ~1200 / ~600 | M | 8: impl, 3 rev, 3 skep, fix |
| C3 | `feat/kyoui-03-input` | C2 | Keys reach the UI: focus, handlers, text editing by grapheme, paste, quit. | ~650 / ~500 | M | 6 |
| C4 | `feat/kyoui-04-components` | C3 | Both component styles: hooks and the `Component` class, mounted by the runner. | ~300 / ~400 | S | 6 |
| T1 | `feat/kyoui-05-terminal` | C4 | `Tui.run` draws on the real terminal and restores it in all cases. | ~1250 / ~1300 | M-L | 8 |
| T2 | `feat/kyoui-06-app-examples` | T1 | `TuiApp` main class; the examples on kyo-ui; launchers; the PTY checklist in the CI `terminal` job. | ~720 Scala + ~550 scripts / ~550 | M | 6 |
| F1 | `feat/kyoui-07-codegen-zio-dsl` | T2 | ZIO users build a kyo-ui value with ZIO types: codegen project, checked-in ZIO mirror, drift check. | ~1450 (+G ~1120) / ~700 | M-L | 8 |
| F2 | `feat/kyoui-08-zio-run` | F1 | ZIO users run, test and ship the app: `Tui.run`, `TuiApp`, test kit, components, examples, PTY rows. | ~720 / ~900 | M | 8 |
| F3 | `feat/kyoui-09-cats-dsl` | F2 | Cats Effect users build a kyo-ui value with `F[_]`: Cats mirror, bridge, drift check. | ~480 (+G ~1120) / ~500 | M | 6 |
| F4 | `feat/kyoui-10-cats-run` | F3 | Cats users run, test and ship the app; screen parity of the three Counters. | ~740 / ~900 | M | 8 |
| L1 | `feat/kyoui-11-scroll` | F4 | Content below the screen is reachable: scroll in `overflow` columns; focus follows. | ~450 / ~300 | M | 6 |
| L2 | `feat/kyoui-12-layout-polish` | L1 | Narrow rows, ellipsis, 256-colour fallback, one log line per unsupported attribute kind. | ~250 / ~250 | S | 4: impl, rev, skep, fix |
| P1 | `feat/kyoui-13-trace-bench` | L2 | Measure it: a trace line per frame, bench probes, JMH benches, a new baseline. | ~1150 / ~200 | M | 4 |
| D | `docs/kyoui-14-guides` | P1 | The docs site describes kyo-ui on the terminal for Kyo, ZIO and Cats users. | ~1000 Markdown | M | 4 |
| R | `chore/kyoui-15-rules` | D | AGENTS.md and README agree with the code; every rule is true. | ~100 text | S | 2: impl, rev |

C1-C4 together are the inventories' PR (a). T1 plus the `TuiApp` part of T2 are PR (b). The examples, PTY and CI part
of T2 plus P1 are PR (c) (ASSUMED A1, refined: PD-2).

Order reason: the facades (F1-F4) are the critical path of DECISIONS round 5 and need only C1-T2. Scroll and layout
polish (L1, L2) depend on the first-release scope (PD-15) and block nothing. The bench (P1) measures the final runner,
so it comes after L1 and L2. Docs (D) describe what exists. Rules (R) sweep last.

### 2.2 Per PR

Each PR has these acceptance criteria in addition to the ones below:

- `sbt check` is green locally, run ALONE on the machine (no other sbt or test JVM), and the CI job `sbt` is green.
- From T2 on: the CI job `terminal` (`examples/ptycheck/run.sh`) is green.
- Timing-sensitive suites (C2, C3, C4, T1, F1-F4) are green in 3 local runs in a row.
- The PR body has the delta guide (P-8), the commit map (P-7) and "Not in this PR" (P-12).
- The reviewers' findings are fixed or answered, and the skeptics confirm the fixes.

#### C1 `feat/kyoui-01-render`: a kyo-ui value renders to cells

- Content (items): RC-16 build (add `kyo-ui`; drop `jatatui-widgets` (PD-12); test deps `kyo-test-runner` and
  `tasty-query`; `Test / fork` and the JVM flags; the OS-39 `-Wconf` for `@nowarn` from PR 3 `build.sbt`), RC-1
  package layout (PD-6), RC-18 format and strict flags for these files, RC-19 test framework set-up (kyo-test, PD-7),
  OS-37, OS-38, OS-12 part (`internal/package.scala` `discard` from PR 3), RC-12 part (surface guard: `UI.Ast`
  element classes and `Style.Prop` cases against a checked-in list), RC-17 part (accept the kyo-ui jars, PD-22),
  OS-13 part (new `package.scala` Scaladoc), OS-47 part (AGENTS.md "What tui4s is" in its final wording; toolchain
  line), `project/Versions.scala` (section 5).
- Files: proto `internal/Geometry.scala` 60, `Props.scala` 258, `Layout.scala` 461, `Paint.scala` 273 (with
  `Canvas`). A `private[tui4s]` render helper for tests (about 20 lines). The placeholders stay for `Img` and `Chart`
  (`Paint.scala:224-227`; PD-15).
- Not in this PR: `World`, `Mount`, `Runner`, `Dispatch`, `Headless`, `Screen` (C2, C3); the real terminal (T1).
- Acceptance: golden screens for borders, colours, row and column flex, gap and padding, overflow clip (the
  prototype overflow fix), text wrap in a column, wide characters, `Img`/`Chart` placeholders. The surface guard
  passes, and it fails when one name is removed from the list (the implementer checks this once and writes it in the
  PR body). No `jatatui.widgets` import. Real-terminal check: not applicable (no terminal code).
- Size: ~1050 prototype lines, ~1300 after the format; tests ~450.
- Agents and builds: 6 agents; 1 build at a time.
- Decisions before start: PD-6, PD-7, PD-12, PD-16 (the cell style type is used in C2).

#### C2 `feat/kyoui-02-regions`: signals drive the screen

- Content: proto `internal/World.scala` 60 (no `Key` and no component cases yet), `Mount.scala` (no component branch;
  about 150), `Runner.scala` (no key path; about 200), `SchedulerProbe.scala` 41. RC-6 (`FrameStats`, `FrameCause`;
  `Settle` in `tui4s.spi`), RC-3 part (Kyo-free `TuiOptions` core set; durations per PD-8), RC-7 (`testkit.Headless`:
  quit and wait before its scope ends, `awaitEnd` on the real clock, `resize`; no `TestBackend`, no jatatui `Cell`,
  no `kyo.Chunk` in its API), RC-28 + OS-12 (`testkit.Screen` from PR 3 with the tui4s cell style, PD-16), RC-8 for
  region watchers (R1: a `Scope` that the run owns), RC-9 (R2: settle after the initial mount plus a verify pass for
  young regions, PD-10), RC-11 (settle hardening: loud fallback, canary test, no stderr print at
  `Runner.scala:145-146`, a "settle timed out" counter in `FrameStats` for RR O6, `Settle.Hops` in spi, suites run
  one at a time), RC-12 part (pins K5, the scheduler field), RC-15 part (resize), RC-30 part (the IR `stress/` split
  and leak probes as ignored stress suites), OS-16 lessons (a name-clash test of `import kyo.*` + `import tui4s.*` +
  `import tui4s.testkit.*`; `CanEqual` of `FrameStats`, `TuiOptions`, `Screen`), OS-34 rule for the Kyo `Headless`
  (never join the run fiber from an interruptible wait), RC-36 (public API review: no jatatui type, no Kyo-internal
  type; `private[tui4s]` seams are public in bytecode).
- Not in this PR: keys, focus, handlers, quit (C3); components (C4); `Tui.run` (T1).
- Acceptance: tests where the TEST writes to a `SignalRef` and the screen follows: the Counter, two counters, Todo and
  Dashboard fixtures (they use plain `Signal.initRef`: `proto/apps/Counter.scala:11`, `Todo.scala:69`,
  `Dashboard.scala:29`). Idle: no frame without a change. Glitch-free: 500 changes, and no frame shows a mixed state.
  R1: after an interrupted run, no region watcher fiber is alive (a deterministic count); the IR leak probe, run once
  locally, keeps less than 5 MB per 150 runs (IR "What does not work" 1 measured 1754 KB per run). R2: 200 starts with
  a write at once give 0 late frames; the IR split probe numbers before and after the fix go in the PR body (if the
  late frames do not go to 0, stop and ask: K-4). Settle canary: `totalLoad() >= 0`. No `System.err` or `println` in
  the core main sources (grep result in the PR body). Real-terminal check: not applicable.
- Size: ~980 prototype-based lines, ~1200 after the format; tests ~600.
- Agents and builds: 8; 1 build at a time; the final runs are alone on the machine (the settle reads the JVM-wide
  scheduler).
- Decisions before start: PD-8, PD-10, PD-21.
- REVIEW GATE 1 (user): after C2. The user reviews the public run API (`TuiOptions`, `Headless`, `Screen`,
  `FrameStats`) before T1 and the facades build on it.

#### C3 `feat/kyoui-03-input`: keys reach the UI

- Content: proto `internal/Dispatch.scala` 242 (bubbling, `stopPropagation`, the bound ref written before `onInput`,
  Enter/Space activation, form submit, focus group, focus trap). `Msg.Key` and the key path of `Runner`. `Tui.quit`
  (the `Local`) and R5 quit as a value: the UI function takes `quit` (`Headless.start(...)(quit => ui)`, PD-9).
  `TuiOptions` fields `ctrlCQuits`, `autoFocus`. `Headless.key`, `char`, `typeText` (returns `Seq`), `paste`. OS-11
  (grapheme text editing from PR 3 `TextField.scala`: `BreakIterator`, Ctrl-A/E/U/K/W, the paste rule "line breaks
  become spaces"). It REPLACES the prototype UTF-16 editing (`Dispatch.scala:121-132`) in the carry PR itself, so
  that code never lands. RC-14 part (`Msg.Paste`: a paste is one edit and never submits). RC-12 part (`Attrs` fields,
  pins K2). RC-15 part (focus group, focus trap, `stopPropagation`). AGENTS.md rules: type handler values
  `Any < Async` (lift trap, RR O5); "the runner calls no `private[kyo]` member" (RR N9).
- Not in this PR: the crossterm event mapping (T1); mouse (deferred, PD-15).
- Acceptance: the Counter and Todo by keys; Tab order, focus group, focus trap, `stopPropagation`; PR 3
  `TextFieldTest` (126 lines) adapted: emoji, combining marks, CJK, Ctrl keys, paste; the R2 key variant (200 starts
  plus Enter at once give 0 late frames). Real-terminal check: not applicable.
- Size: ~650 after the format; tests ~500. Agents: 6; 1 build at a time.
- Decisions before start: PD-9.

#### C4 `feat/kyoui-04-components`: both component styles

- Content: RC-4 (`Hooks`: `useSignal`, `useHold`, `useFold`, `useEffect`, `useInterval`, `component(key)`), RC-5
  (`Component` class, `ComponentSpec` registry, K3), the component branch of `Mount` and `World` (`comps`,
  `pending`), `TuiOptions.pendingTimeout`, the overridable state key (FC section 2.7: `Mount.scala:117` keys by
  `getClass.getName`; facade adapters need their own key), RC-8 for component effects and hook bodies (R1 at
  `Mount.scala:128, 147`), RC-15 rest (`onMount` and the hooks that have no test: RR skeptic "refuted" 9), RR O7 tests.
- Acceptance: each hook has a test; the `CounterStyles` fixture (both styles); `Component` state survives a parent
  re-render; a hook component in a `when` loses its state (documented behaviour, RR O7); two `Component` classes at
  one position do not share state (state key); no effect fiber is alive after an interrupted run (R1). Real-terminal
  check: not applicable.
- Size: ~300; tests ~400. Agents: 6; 1 build.

#### T1 `feat/kyoui-05-terminal`: `Tui.run` on the real terminal

- Content (carried from the PR 5 head; that code already passes this repo's format and flags): RC-20 (`TerminalIO`
  188, `Crossterm` 155), RC-21 (`Session` + `InputThread` + `TerminalControl`/`HeadlessControl`, 249 less the engine
  types; `InputPort` 18 with the crossterm `Event` as element, PD-25), RC-22 (`EventMapping` 103: kyo-ui
  `KeyboardEvent`, `Msg.Resize`, `Msg.Paste`; the exhaustivity `-Wconf` error), RC-23 (`OutputCapture` 179, `LogSink`
  134, `TuiLog` 90; `Log.let` around the run), RC-10 part (R3: a start-time warning when `scala.Console.out` is not a
  tui4s forwarder), RC-2 part (`Tui.run(options)(quit => ui): Unit < (Async & Abort[TuiError])`; `TuiRun` merges
  into it), RC-3 part (terminal fields of `TuiOptions`: `paste`, `focusEvents`, poll interval, `inboxCapacity`,
  `captureOutput`, `logFile: Option[Path]`), OS-1, OS-2, OS-3, OS-4, OS-8, OS-10.
- The three open bot comments, each fixed in its own commit with a regression test: PR 5 `CountingBackend.scala:33`
  (P1: the restore waits for a draw in progress; the `close()` guard moves into `Canvas`); PR 5 `Crossterm.scala:103`
  (P2: an `UnsatisfiedLinkError` gets its own message, not `NativeAccessDenied`); PR 4 `Session.scala:150` (P2: the
  input thread stops when the acquire fails).
- Tests (carried and adapted): `TerminalIOTest` 507 (without the inline-viewport cases if PD-11 = defer),
  `SessionTest` 152, `EventMappingTest` 187, `OutputCaptureTest` 187, `NativeTerminalTest` 88, `FakeCrossterm` 141.
  `RecordingControl` (88) and `ScriptedInput` (59) move to TEST sources: they are test doubles, not user API (less
  binary surface).
- Not in this PR: `TuiApp`, exit codes, scheduler flags (T2); the inline viewport (PD-11); the trace (P1). Never
  ported (replaced here): `Keys.scala`, `OutputGuard`, the prototype `enter`/`restore` and its input fiber.
- Acceptance: as above, plus the real-terminal check done LOCALLY (the CI `terminal` job comes in T2): the implementer
  starts a test-fixture Counter in a PTY with a scratch launcher (`java -cp` from `sbt export core/Test/fullClasspath`;
  never `sbt run`) and the prototype kit (`proto/kyoui-runner/pty/vt.py`, `check.py`): screen checks after keys, `q`
  ends the run, `stty` is restored, a pipe as stdin gives `NotATerminal`. The log goes to `.work/impl/T1-pty.log`, and
  the PR body quotes the result.
- Size: ~1250 main (about 1150 carried with a delta guide); tests ~1300. The largest PR of the stack.
- Agents: 8; 1 build at a time.
- Decisions before start: PD-25, PD-11, PD-13.

#### T2 `feat/kyoui-06-app-examples`: run it, see it, check it in a real terminal

- Content: RC-24 + OS-6 (`TuiApp` for kyo-ui: `def ui`, `options`, `args`, `onExit`; the constructor calls
  `SchedulerDefaults.applyIfUnset()` and `OutputCapture.install()`, which closes R3 for `TuiApp` users; shutdown hook;
  exit codes per PD-14), RC-25 + OS-7 (`SchedulerDefaults` 63, unchanged), RC-33 + OS-18 (examples from
  `proto/apps/`: Counter, two counters, CounterStyles, Todo, Dashboard, Widgets; munit suites), OS-20 (launchers; the
  `feat/cats` script-name form), RC-31 + OS-19 (PTY kit: `vt.py` + `check.py` replace `analyze.py`; the cases of the
  PR 5 `checklist.exp`: `+ - q`, Ctrl-C, `kill -TERM` 143, `kill -INT` 130, pipe, native access denied, WINCH resize,
  bracketed paste, Tab/Space/Esc, Dashboard pause; plus CounterStyles), OS-41 (CI `terminal` job), OS-44
  (`FacadeParity`: never carried), OS-50 (`examples/README.md`), OS-48 part (README "Quick start" with the kyo-ui
  Counter; the code is included from `examples/`), RC-17 part (a real Counter run with `-verbose:class`: the PR body
  gives the number of kyo-http and kyo-net classes that load), OS-47 part (AGENTS.md rows: launchers, ptycheck, "no
  `sbt run`"). If PR 2 is merged by then: `docs/content/1.getting-started/1.installation.md` drops `run / fork` and
  `connectInput` and points to the launchers (PR 2's AGENTS.md rule "change its page in the same pull request").
- Tests: `TuiAppTest` 280 (exit codes, `onExit`, SIGTERM with both hooks, `System.exit` in a handler),
  `SchedulerDefaultsTest` 72, the example suites.
- Acceptance: as above; the CI `terminal` job is green 3 times before review (PTY timing on CI: K-7).
- Size: ~720 Scala main + ~550 lines of expect, Python and shell; tests ~550. Agents: 6; 1 build.
- Decisions before start: PD-14.

#### F1 `feat/kyoui-07-codegen-zio-dsl`: ZIO users build a kyo-ui value with ZIO types

- Content: FC-1..FC-8 (sub-project `codegen`: Scala 3, `publish / skip`, no `dependsOn` to zio or cats; class path and
  `Versions.kyo` as task inputs; both ecosystems in the rule table; fail closed; a scalafmt exclude for
  `**/src/*/generated/**`; the `sbt mirrors` alias; `codegen/mirrorsCheck` inside the `check` alias; generator tests;
  `codegen/README.md` with the kyo-ui bump steps). `Inventory.scala` (a dev report main) is not ported. FC-23 (the
  ZIO mirror, all 52 HTML elements, checked in at `modules/zio/src/main/generated/ZioUi.scala`), FC-25, FC-26, FC-31
  (PD-18: opaque event types with Scala accessors), FC-32 (drag and drop: the 9 names excluded), FC-30 (the generated
  `GuardSpec` for ZIO with exact messages; the canary "the lift trap is real WITHOUT the mirror" on the core
  `Headless`; raw-access guards; the FC-D8 nested-ZIO guard if a probe shows clear overload resolution), FC-29 +
  OS-17 (`SurfaceLeaks` in `codegen/src/surface`, added to the zio tests), FC-35 + OS-32 (ZIO name-clash test), FC-9
  (ZIO `SignalRef` (ASSUMED A3) + `internal.Bridge` + `useSignal`/`useStream`; Scaladoc of `changes`, FR O7), FC-11
  ZIO + OS-22 (`AmbientZIOs` 131 + `AmbientZIOsSuite` 229 from `feat/zio`; FR O5 texts fixed), FC-36 + OS-35
  (`changes` interrupt stress: the heap stays flat), FR F5/F6 (stress suite in the normal run; a `modify` contention
  test), FC-21 (zio build: generated source root, fork flags), FC-16 (package, PD-6), OS-30 ZIO part, OS-42, the
  AGENTS.md rule "never edit a generated file; run `sbt mirrors`".
- Smoke test: the ZIO Counter, built with the mirror, runs on the core `Headless` through a `private[zio]` test helper.
  The user-facing ZIO test kit comes in F2.
- Pre-flight (risk detection for F3; not in the PR): the implementer generates the Cats FULL mirror in scratch and
  compiles it with the Cats prototype runtime and fs2 3.14.0 (FC summary 3 and 16). The log goes to `.work/impl/`.
- Acceptance: as above; `sbt mirrors` twice gives no diff; the generator exits 1 on an unknown setter (a test);
  `SurfaceLeaks` allows only the listed aliases; the CI `terminal` job stays green (no change to it).
- Size: generator ~1000 after the format, plus ~150 for fail-closed, tasks and event types; ZIO runtime ~300 (131
  carried); build ~80. About 1450 (at the aim). Generated ~1120. Tests ~700. Agents: 8; 1 build.
- Decisions before start: PD-6, PD-18, A3, A4.
- REVIEW GATE 2 (user): after F1. The user reviews the generated ZIO API (the mirror file IS the API) before F2, F3
  and F4 build on it.

#### F2 `feat/kyoui-08-zio-run`: ZIO users run, test and ship the app

- Content: FC-12 ZIO + OS-25 (`Tui.run(options)(app): IO[TuiError, Unit]`; `Tui.quit` through a `FiberRef` plus the
  explicit `quit`; logger removal in its own `ZIO.scoped` (spec-3); the `zio.Console` service replaced for the run,
  FC-27, api-6/spec-6), FC-13 + OS-24 (`TuiApp extends ZIOAppDefault`; `args` through `ZIOAppArgs` (api-2); capture and
  scheduler flags in the constructor), FC-14 (`testkit.Headless`: a bounded `focus(id)`, `Screen`, `FrameStats`,
  `Seq`), FC-15 (re-export of the Kyo-free core types), FC-17 ZIO (runner-mounted `component(key)` and `Component`
  class over the core state key; FC-D11: no `Component.mount`), FC-19 + OS-28 (FR `ZioFacadeSuite` and IR
  `ZioOnTerminal` ported to the kit; `TuiAppSuite`: flags set, `NotATerminal` without a terminal), OS-31 (TestClock
  rule: documented and tested), OS-34 (a `timeout` around `Headless.start` ends; never join the Kyo run fiber), FC-34
  (Scaladoc: a long handler freezes the UI; use `fork`), FC-33 ZIO + OS-29 (examples `ziocounter`, `ziotodo`,
  `ziodashboard` with `useStream`; PTY rows: keys, Ctrl-C, `kill -TERM`, `kill -INT`, `stty` restored; the IR output
  probe `ZioOutputProbeEarlyMain` as a PTY row: no ZIO output on the screen), IR F1 (no HTML stand-in), IR F2 (the glue
  in the module), OS-30 (ZIO version literal per PD-23), OS-48 part (README ZIO quick start).
- Acceptance: as above; the CI `terminal` job with the ZIO rows.
- Size: ~520 glue + ~200 examples; tests ~900. Agents: 8; 1 build.
- Decisions before start: PD-23, PD-24.

#### F3 `feat/kyoui-09-cats-dsl`: Cats users build a kyo-ui value with `F[_]`

- Content: FC-24 (the Cats mirror, first full set, `modules/cats/src/main/generated/CatsUi.scala`; one new target line
  in the codegen task), FC-10 (`KyoSignallingRef` 111 compiled against fs2 3.14.0; FC-D4: the subtype
  `UiSignallingRef[F, A]` in the setter signatures, so a foreign ref does not compile; FR O3), FC-11 Cats + OS-23
  (`CatsBridge` 208, `AmbientDispatcher` 39, `CatsBridgeSuite` 264, `DispatcherSuite` 75 from `feat/cats`;
  `Dispatcher.parallel(await = false)`, FR F8; FR O5 and IR F3 texts fixed), FC-22 (cats build), FC-29/30/35 Cats
  parts (`SurfaceLeaks`; `GuardSpec`: a plain value and an `F[A]` that is not `F[Unit]` do not compile; the canary; a
  name-clash test with `cats.*`, `cats.effect.*`, `cats.syntax.all.*`, `fs2.*`, `fs2.concurrent.*`: the mirror
  `Signal` clashes with `fs2.concurrent.Signal`, so document it or rename it), OS-35 Cats (stress suite), OS-32 Cats.
- Smoke test: the Cats Counter on the core `Headless`.
- Acceptance: as above; the `CatsBridgeSuite` stress test ("interrupt right after the IO starts never leaks the IO,
  300 rounds") is green 3 times (it backs the "VERIFIED" text of FR O5).
- Size: ~480 hand-written (~360 carried); generated ~1120; tests ~500. Agents: 6; 1 build.

#### F4 `feat/kyoui-10-cats-run`: Cats users run, test and ship the app

- Content: FC-12 Cats + OS-25 (`Tui.run[F]`; `Tui.resource` as a CE bracket; the `end` waiter fiber; an explicit
  `quit: F[Unit]`, FC-D5), FC-13 Cats + OS-24 (`TuiApp extends IOApp` with `args`; the CPU starvation checker off;
  capture and flags in the constructor), FC-14, FC-15, FC-17 Cats, FC-20 + OS-28 (FR `CatsFacadeSuite`, IR
  `CatsOnTerminal`, `NativeTerminalSuite`: `NotATerminal`, `System.out` unchanged), OS-27 lessons as new tests (a
  cancel at any time leaves no terminal entered, `feat/cats` 9f4cc05; a cancelled wait does not stop the app,
  `ResourceSuite`; a burst of input is bounded), OS-34 Cats (the core join cascade, `cats-impl.md` "Open issues"),
  FC-33 Cats + OS-29 (`catscounter`, `catstodo`, `catsdashboard`; PTY rows, one of them with a `Tui.resource` app,
  which was verified only up to `NotATerminal`), OS-18 / old-stack D12 (screen-parity test: the same keys give the
  same screen for the Kyo, ZIO and Cats Counter; PD-20), OS-48 part (README Cats quick start).
- Acceptance: as above; the CI `terminal` job with the Cats rows.
- Size: ~540 glue + ~200 examples; tests ~900. Agents: 8; 1 build.

#### L1 `feat/kyoui-11-scroll`: content below the screen is reachable

- Content: RC-13 part (a scroll offset per container with `overflow`; focus follows the scroll; clip), RR N7, RR open
  question 2. A Todo with a long list in the examples; a PTY row.
- Size: ~450; tests ~300. Agents: 6; 1 build. Only if PD-15 keeps scroll in the first release.

#### L2 `feat/kyoui-12-layout-polish`: small layout gaps and unsupported attributes

- Content: RC-13 rest (row min-content width; ellipsis; 256-colour fallback when `COLORTERM` is not `truecolor`; check
  the full-width button of IR item 4 against kyo-ui `HtmlRenderer.baseCss` first), RC-14 rest (one log line per
  unsupported attribute kind at mount; the `onKeyUp` warning, RR open question 5), RR N8.
- Size: ~250; tests ~250. Agents: 4; 1 build.

#### P1 `feat/kyoui-13-trace-bench`: measure it

- Content: RC-26 + OS-9 (the PR 5 `Trace.scala` writers; one JSON line per frame from `FrameStats` and a "settle timed
  out" line, RR O6; `TuiOptions.trace`), RC-32 + OS-21 (bench probes on the kyo-ui apps and `Headless`: latency, idle,
  quit, soak, burst, Dashboard; proto `dev/Bench.scala`; split-aware "late frames", RR skeptic "refuted" 2; JMH
  benches of `Layout` and `Paint` replace `EngineBench` and `PaintBench`), RC-30 part (`GlitchProbe`), OS-40 (bench
  project, sbt-jmh 0.4.8, `.gitignore` `bench/results/`), RR N4/N5, PD-13 data (`BurstProbe`), OS-47 part (AGENTS.md
  bench row).
- Acceptance: as above; `TraceTest` adapted; a JMH smoke run; the probes run on an idle machine; a new
  `bench/BASELINE.md` section (machine, method, numbers).
- Size: Trace ~250 + bench ~900; tests ~200. Agents: 4; 1 build (the probe run alone on the machine).

#### D `docs/kyoui-14-guides`: the docs describe what exists

- Content: OS-49 (index: "one UI DSL (kyo-ui)"; "repaints only the regions whose signals changed"), getting started
  per ecosystem, concepts (UI values, signals, regions, hooks, `Component`, focus), testing (`Headless`, the facade
  kits, the TestClock rule), performance (settle, `FrameStats`, trace, scheduler flags), troubleshooting (no
  `sbt run`, launchers, the lift trap (RR O5), one import, the `fs2.concurrent.Signal` clash, the `scala.Console`
  limit (R3), long handlers (FC-34)), supported elements and attributes, the kyo-ui jar size (RC-17). Code comes from
  `examples/` files (no copies). Base and PR 2 handling: PD-4.
- Acceptance: the `docs` workflow (`pnpm build`) is green; no deploy from an agent; `sbt check` is unchanged.
- Agents: 4 (no sbt build; `pnpm build` only).

#### R `chore/kyoui-15-rules`: rules agree with the code

- Content: OS-47 and FC-37 sweep (AGENTS.md: each rule is true; the kyo-ui bump steps point to `codegen/README.md`;
  the release order core, zio, cats), OS-48 rest (README modules table: "UI DSL: kyo-ui").
- Acceptance: a reviewer checks each AGENTS.md rule against the repo; `sbt check` is green. R can fold into D (PD-19).

---

## 3. The open PRs, the old branches, and the merge order

### 3.1 PRs 3, 4, 5 (PD-1)

| Option | What | For | Against |
| --- | --- | --- | --- |
| A. Close and open new PRs (RECOMMENDED) | Close PRs 5, 4, 3 (top first) when C1 is open. The comment links the new stack and says where the three open bot comments are fixed (T1). Keep the old branches unchanged. Port files with `git show <old ref>:<path> > <new path>` (paths and packages change, so `git checkout <ref> -- <path>` is not enough). Each carry commit names its source commit. | No history rewrite. The old review stays readable. Each new PR shows only new or carried code, with a delta guide. | Three closed PRs in the list; new PR numbers; 3 bot comments copied by hand. |
| B. Reuse the branch names | Force-push new history to `feat/core-1-model-view`, `feat/core-2-engine`, `feat/core-3-terminal`. | Same PR numbers and links. | A history rewrite (against the user's preference). Old comments become "outdated". Only 3 PRs, so the 15-PR shape does not fit. |
| C. Keep PR 5, add commits | Retarget PR 5 to the base, close PRs 3 and 4, add commits that delete the old model and add the runner. | No rewrite; PR 5 keeps its number. | 34 old commits (10 + 6 + 18) and their removal stay in the history; one very large PR; noisy bisect and blame. |

Why A for this angle: only A gives each reviewer a small diff of new or carried code. B and C mix the dropped model
into the history of the PRs.

Who does what: the user closes the PRs, or tells Claude to close them. Claude never closes, merges or deletes on its
own.

### 3.2 The other branches

| Branch | Plan | Reason |
| --- | --- | --- |
| `chore/cleanup` (PR 1) | The user merges PR 1 now, with a merge commit (PD-3). | It is green and has nothing of the old model. The stack then starts from `main`. |
| `docs/docus` (PR 2) | Recommended: one small fix commit now (remove "One model, three effect systems"; the installation page points to launchers, not `run / fork`), then the user merges it after PR 1. The content comes in PR D. Alternatives: PD-4. | The 15k-line `pnpm-lock.yaml` stays out of the content review. |
| `feat/core-1-model-view`, `feat/core-2-engine`, `feat/core-3-terminal` | Keep unchanged as reference (source of the carried files). Delete only when the user says so, after the stack merges (PD-17). | The delta guides cite them. |
| `feat/zio`, `feat/cats` (no PR) | Keep unchanged as reference. F1-F4 carry files from them. | Same. Section 4.6 places the 14 confirmed ZIO findings. |
| `wip/handoff` | Never merge. Keep it until the stack merges: the delta guides cite the prototype at `origin/wip/handoff` (the pushed prototype is byte-identical to the local `.work/proto`: `cmp`, this session). | Prototype references must stay valid. |
| `chore/cleanup-7p2rll` | Not in the inventories. Same head as `chore/cleanup` (6f70179). Ask the user; it is harmless. | Unknown origin; no PR. |
| `first-take` | The user's branch on `main` (05599dd "First take on the Stateless/Stateful widget/element abstractions"). Do not touch. | Not a Claude branch. |

### 3.3 Merge order

PR 1 -> (PR 2, per PD-4) -> C1 -> C2 -> C3 -> C4 -> T1 -> T2 -> F1 -> F2 -> F3 -> F4 -> L1 -> L2 -> P1 -> D -> R.

- Only the user merges. Use "Create a merge commit" (PD-3). A squash merge breaks the stack: the next branch still has
  the original commits, so it needs a rebase (a history rewrite) or a merge with possible conflicts.
- After a merge, the base of the next PR must become `main`. With "Automatically delete head branches" on (PD-5),
  GitHub deletes the merged branch and retargets the next PR. Without it, the user edits the base by hand.
- The user can merge in batches, for example C1-C4 after gate 1, bottom first.
- A merge of docs to `main` can deploy the site (PR 2 AGENTS.md: "Vercel deploys it from the repository";
  `docs/vercel.json` builds when `docs/` changes). The user decides each docs merge.

---

## 4. Where every inventory item goes

"drop" = never carried. "defer" = after the first release, with the reason.

### 4.1 Old stack (OS)

| Item | Place | Item | Place |
| --- | --- | --- | --- |
| OS-1 TerminalIO, Crossterm, bot P2 | T1 | OS-26 old facade exports and sources | drop |
| OS-2 Session, InputThread, bot P2 | T1 | OS-27 old facade kits and tests | drop; lessons as tests in F4 (and F2) |
| OS-3 EventMapping, InputPort | T1 | OS-28 facade tests that adapt | F2, F4 |
| OS-4 OutputCapture, LogSink, TuiLog | T1 | OS-29 facade examples | F2, F4 |
| OS-5 TuiOptions, Viewport, TuiError | C2 (core set); C3, C4, T1, P1 add fields; `Viewport` per PD-11 | OS-30 facade build files | F1 (zio), F3 (cats) |
| OS-6 Kyo TuiApp | T2 | OS-31 api-1/runtime-3 TestClock | F2 |
| OS-7 SchedulerDefaults | T2 | OS-32 api-3 one import | F1, F3 (name-clash tests); F2, F4 (kits re-export shared names) |
| OS-8 Tui.run, Headless, quit value | C2 (Headless), C3 (quit), T1 (`Tui.run`) | OS-33 api-6/spec-6/spec-3 | T2 (capture), F2 (Console, loggers) |
| OS-9 Trace | P1 | OS-34 no unbounded acquire, no join cascade | C2 (Kyo Headless), F2, F4 |
| OS-10 draw/restore serialisation, bot P1 | T1 | OS-35 no race on a long-lived Kyo promise | F1, F3 |
| OS-11 grapheme editing | C3 | OS-36 spec-4 | none (obsolete) |
| OS-12 Screen, discard, ScriptedInput, RecordingControl | C1 (discard), C2 (Screen), T1 (test doubles into test sources) | OS-37 kyo-ui dependency, widgets | C1 |
| OS-13 old model files | drop (new package Scaladoc in C1) | OS-38 test framework, test JVM | C1 |
| OS-14 old engine files | drop. Lessons: batching -> PD-13 + P1 `BurstProbe`; clock rule -> C2, F2; `NotForeign` for core hooks -> defer (Kyo users only; the facades do not expose `useEffect`/`useInterval`) | OS-39 strict flags | every PR for its files (C1 first) |
| OS-15 terminal tests to carry | C2 (`ScreenTest`), C3 (`TextFieldTest`), T1, T2 | OS-40 bench project | P1 |
| OS-16 old tests; 2 lesson tests | drop; the lesson tests in C2 | OS-41 CI jobs | T2 (`terminal`), F1 (drift inside `check`) |
| OS-17 surface check location | F1 (`codegen/src/surface`) | OS-42 codegen + drift check | F1 |
| OS-18 examples; parity | T2; screen parity in F4 (PD-20) | OS-43 compat.sbt | no change (section 5) |
| OS-19 ptycheck | T2; rows in F2, F4, L1 | OS-44 FacadeParity | drop |
| OS-20 launchers | T2 | OS-45 release files | no change |
| OS-21 bench probes, JMH, baseline | P1 | OS-46 PR objects | section 3 |
| OS-22 AmbientZIOs + suite | F1 | OS-47 AGENTS.md | C1, C3, T2, F1, P1, R (P-10) |
| OS-23 CatsBridge, AmbientDispatcher + suites | F3 | OS-48 README | T2, F2, F4, R |
| OS-24 facade TuiApp | F2, F4 | OS-49 docs content | PR 2 fix commit (PD-4), D |
| OS-25 facade run entry | F2, F4 | OS-50 examples/README | T2 (rows added in F2, F4, L1) |

### 4.2 Runner (RC)

| Item | Place | Item | Place |
| --- | --- | --- | --- |
| RC-1 package layout | C1 | RC-19 test framework, sequential suites | C1 (set-up), C2 (sequential) |
| RC-2 `Tui` entry, quit value, `Abort[TuiError]` | C3 (quit), T1 (`Tui.run`, errors) | RC-20 TerminalIO, Crossterm | T1 (inline viewport per PD-11) |
| RC-3 Kyo-free options | C2, C3, C4, T1, P1 (each adds its fields) | RC-21 Session, InputThread | T1 |
| RC-4 Hooks | C4 | RC-22 EventMapping | T1 |
| RC-5 Component + state key hook | C4 | RC-23 OutputCapture, LogSink, TuiLog | T1 |
| RC-6 FrameStats, spi.Settle | C2 | RC-24 TuiApp | T2 |
| RC-7 Headless | C2 (C3 adds the key methods) | RC-25 SchedulerDefaults | T2 |
| RC-8 R1 | C2 (watchers), C4 (effects, hook bodies) | RC-26 Trace | P1 |
| RC-9 R2 | C2 (C3 adds the key test) | RC-27 carried PR 5 tests | T1, T2 (`GuardTest` drops: section 9) |
| RC-10 R3 | T1 (warning), T2 (`TuiApp` install), F2 (ZIO `Console`), D (limit) | RC-28 Screen style type | C2 |
| RC-11 settle hardening | C2 | RC-29 old files | drop |
| RC-12 kyo-ui surface guard | C1 (AST, Style.Prop), C2 (K5), C3 (Attrs, K2) | RC-30 stress probes | C2 (split, leak), P1 (glitch) |
| RC-13 layout gaps | L1 (scroll), L2 (rest). Defer: virtualisation, flexWrap, position, zIndex, tables, colspan (no first-release need, RC section 2.5) | RC-31 PTY kit | T2 |
| RC-14 unsupported elements/attributes | C3 (paste), L2 (log line, `onKeyUp`). Defer (PD-15): mouse, pickers, multi-line `Textarea`, `Select` drop-down, `href`, `focusRestore`, terminal focus events | RC-32 bench | P1 |
| RC-15 tests of unverified paths | C2 (resize), C3 (focus group/trap, `stopPropagation`), C4 (`onMount`, hooks) | RC-33 examples | T2 |
| RC-16 core build | C1 | RC-34 upstream issue DRAFT | not a PR: `.work/design/upstream-kyo-ui-issue.md`; the user reads it at gate 1; only the user posts it (A2) |
| RC-17 kyo-ui jar size | C1 (accept), T2 (`-verbose:class`), D (docs note), upstream draft (module split) | RC-35 PR restructure | section 3 |
| RC-18 strict flags, format | every PR (P-7) | RC-36 binary surface | C2 (review), every PR |

### 4.3 Facades (FC)

| Item | Place | Item | Place |
| --- | --- | --- | --- |
| FC-1..FC-8 codegen | F1 | FC-23 ZIO mirror | F1 |
| FC-9 ZIO state and runtime | F1 | FC-24 Cats mirror | F3 |
| FC-10 Cats state, ref type | F3 | FC-25 mirror scope | F1 (PD-18) |
| FC-11 vendored bridges | F1 (ZIO), F3 (Cats) | FC-26 reactive template | F1 |
| FC-12 `Tui` entry points | F2, F4 | FC-27 ZIO output during the run | F2 |
| FC-13 `TuiApp` | F2, F4 | FC-28 compat notes | F1 (`codegen/README.md`), D |
| FC-14 test kits | F2, F4 | FC-29 SurfaceLeaks | F1, F3 |
| FC-15 re-exports | F2, F4 | FC-30 lift-trap guards | F1, F3 |
| FC-16 package layout | F1 (PD-6) | FC-31 data-type leaks | F1 (generator, both ecosystems) |
| FC-17 runner-mounted components | C4 (core state key), F2, F4 | FC-32 drag and drop | F1 (excluded) |
| FC-18 HTML stand-in | drop | FC-33 facade examples, PTY rows | F2, F4 |
| FC-19 ZIO tests | F1 (stress, guards), F2 | FC-34 long handlers, TestClock | F2 (Scaladoc, test), D; a runner fork of long handlers: defer (PD-24) |
| FC-20 Cats tests | F3, F4 | FC-35 name-clash tests | F1, F3 |
| FC-21 ZIO build | F1 | FC-36 `changes` stress | F1 |
| FC-22 Cats build | F3 | FC-37 AGENTS.md, module docs | F1, R |
| | | FC-38 PR objects and order | section 3 |

### 4.4 Integration fixes (IR)

| Item | Place |
| --- | --- |
| R1 leak of an interrupted run | C2 (region watchers), C4 (component effects, hook bodies) |
| R2 first-key race | C2 (fix, signal-write test), C3 (key test) |
| R3 `scala.Console` capture | T1 (forwarders, warning), T2 (`TuiApp` install), F2 (ZIO `Console`), D (the documented limit for a foreign `main`) |
| R4 Kyo-free options | C2 (core set); later PRs add fields; the facades re-export them in F2, F4 |
| R5 quit as a value | C3 (`quit => ui`), T1 (`Tui.run` form); the facades drop `withQuit` in F2, F4 |
| F1 drop the HTML stand-in | F1, F2 (never carried; the tests use the terminal kit) |
| F2 glue into the modules | F2, F4 |
| F3 stale `AmbientDispatcher` message | F3 |

### 4.5 Prototype findings that need code

| Finding | Place |
| --- | --- |
| FR F1 the lift trap is real | F1, F3 (canary) |
| FR F2 an extension cannot shadow a member | F1 (keep opaque types; raw guards) |
| FR F3 trait-level forwarders | F1 (generator design) |
| FR F4 ZIO seals `Ref` | F1 (`SignalRef`, A3) |
| FR F5 two cells race | F1, F3 (stress suites in the normal run) |
| FR F6 CAS by reference | F1 (contention test) |
| FR F7 handler inline in dispatch | F2 (Scaladoc, TestClock test), D |
| FR F8 `Dispatcher.parallel(await = true)` loses a cancel | F3 (`await = false`, `DispatcherSuite`) |
| FR O1 drag and drop | F1 (exclude the 9 names, PD-18) |
| FR O2 Kyo data types through aliases | F1 (opaque event types, PD-18) |
| FR O3 Cats foreign `SignallingRef` | F3 (subtype, PD-18) |
| FR O4 headless drops a missing id | obsolete (stand-in dropped); a bounded `focus(id)` in F2, F4 |
| FR O5 stale texts in the vendored bridges | F1 (ZIO), F3 (Cats) |
| FR O6 folders not self-contained | obsolete (sbt) |
| FR O7 `changes` semantics | F1 (Scaladoc + stress) |
| FR O8 headless only | closed by IR; PTY rows in F2, F4 |
| RR O2 copied kyo-ui machinery | C1-C3 (surface guard); upstream draft rank 2 |
| RR O4 no synchronous change hook | C2 (the settle stays); upstream draft rank 1 |
| RR O5 lift trap for Kyo users | C3 (AGENTS.md rule), F1 (canary), D (docs page); upstream draft rank 4 |
| RR O6 a busy fiber holds the settle | C2 (`FrameStats` counter, no stderr), P1 (trace line), D |
| RR O7 component state rules | C4 (tests) |
| RR N1, N3 | C2 (keep the fixed code and `awaitEnd`) |
| RR N2 probe races | C2 (verify pass); upstream draft ranks 1 and 3 |
| RR N4, N5 measurements, idle CPU | P1; T2 (`SchedulerDefaults`) |
| RR N6 PTY Counter and Todo | T2 |
| RR N7 layout gaps | L1, L2; the rest deferred (section 4.2, RC-13) |
| RR N8 unsupported elements | C3, L2; the rest deferred (section 4.2, RC-14) |
| RR N9 kyo internals | C1-C3 (guard), C3 (AGENTS.md rule) |
| RR N10 old-core hardening | T1, T2 |
| RR skeptic: the bench "late frames" column cannot see splits | P1 |
| IR item 4: full-width button | L2 (check `HtmlRenderer.baseCss` first) |
| IR item 5: the facade apps have no quit key | F2, F4 (the examples have a quit key) |
| IR item 6: not tested (Todo in a PTY, resize, signals, pipe, facade CPU, Dashboard) | F2, F4 (PTY rows); facade CPU probes in P1 are optional (defer if time is short) |

### 4.6 Bot comments, ZIO findings, Cats open issues

| Item | Place |
| --- | --- |
| PR 5 `CountingBackend.scala:33` (P1: a draw races the restore) | T1 (`Canvas` lock and guard; regression test) |
| PR 5 `Crossterm.scala:103` (P2: a link error reported as `NativeAccessDenied`) | T1 |
| PR 4 `Session.scala:150` (P2: the input thread leaks on a failed acquire) | T1 |
| api-1, runtime-3 | F2 (rule, test); a runner fork of long handlers: defer (PD-24) |
| api-2 | F2 |
| api-3 | F1, F3 (name-clash tests); F2, F4 (kits re-export shared names) |
| api-6, spec-6 | T2 (`TuiApp` install), F2 (`Console` service) |
| spec-3 | F2 (`quietly` in its own `ZIO.scoped`) |
| runtime-1, spec-2 | F2 (rule + `timeout` test) |
| runtime-4, spec-5 | C2 (Kyo `Headless`), F2, F4 (never join the run fiber) |
| runtime-2, spec-1 | F1 (`changes` stress), F3 |
| spec-4 | none (obsolete) |
| Cats: the core join cascade | F4 |
| Cats: the `import fs2.*` clash | F3 (name-clash test; `fs2.concurrent.Signal`) |
| Cats: README and checklist rows | F4 |
| Cats: `Tui.resource` verified only up to `NotATerminal` | F4 (PTY row with a resource app) |

---

## 5. Versioning and compatibility

- Facts: no module is released (no `core-v*`, `zio-v*` or `cats-v*` tag; DECISIONS round 3 "publishing is
  deferred"). All three `compat.sbt` files say `Compatibility.None` (`origin/chore/cleanup`). `ModuleVersioning`
  watches `modules/<m>` and `project/Versions.scala` (`build.sbt`, `module(...)`).
- Per PR: NO PR of this plan changes a `compat.sbt`. `versionPolicyCheck` has no earlier release to compare with, so
  it passes. After the first release of a module, AGENTS.md "Compatibility" applies (set `BinaryCompatible`); that is
  not in this plan.

| PR | core `compat.sbt` | zio `compat.sbt` | cats `compat.sbt` | `project/Versions.scala` |
| --- | --- | --- | --- | --- |
| C1 | None (no change) | None | None | note on `kyo` (kyo-ui uses it); new `tastyQuery = "1.9.0"` |
| C2-R | None (no change) | None | None | no change (PD-23 can add a ZIO constant in F2 if the user chooses it) |

- `project/Versions.scala` (C1):
  - kyo-ui: NO new constant. kyo-ui is published with the same version as Kyo (`io/getkyo/kyo-ui_3` in the coursier
    cache; the prototype uses RC7 for both, `proto/kyoui-runner/project.scala`). C1 changes the note on `kyo` to "kyo-zio
    and kyo-ui must use the same version", and `modules/core/build.sbt` adds `"io.getkyo" %% "kyo-ui" % Versions.kyo`.
  - NEW `tastyQuery = "1.9.0"`: the core tests (C1 surface guard), `codegen` (F1) and the facade tests (F1, F3) use
    it, so it is a shared version (AGENTS.md "Dependencies and versions").
  - A change in this file marks all modules unreleased. There is no cost now, because none is released.
- The `codegen` project: `publish / skip`, no `compat.sbt` (the `examples` project on `chore/cleanup` has the same
  shape, and CI is green). A change only in `codegen/` does not mark zio or cats unreleased; a change in a generated
  mirror does (it is under `modules/<m>`). This is correct: only the output ships (FC section 1.1).
- Binary surface notes for the first release: the facade binary API names kyo-ui classes (opaque types erase; FC
  summary 8), so a kyo-ui bump after a release is a binary break for zio and cats (set `Compatibility.None` for core,
  zio and cats in that PR; F1 writes the steps in `codegen/README.md`). Generated file names never change (a rename is
  a binary break). `private[tui4s]` seams are public in bytecode (RC-36): keep them few (C2 review).
- Release order (not in this plan): core first, then zio and cats (AGENTS.md "Releasing"). Never release without the
  user.

---

## 6. Risks and early detection

| Id | Risk | How the plan finds it early | Fallback |
| --- | --- | --- | --- |
| K-1 | The strict flags (`-Wunused:all -Wvalue-discard -Wnonunit-statement -Yexplicit-nulls`) cost more than 150-250 lines (RC section 3.2). | C1 ports the first 1052 prototype lines and reports the real count; the C2 estimate is corrected from it. | Accept more lines; never relax the flags. |
| K-2 | The format growth pushes a PR over the aim. | Section 9 item 1 measured about +25% (awk over the prototype). The C1 implementer reports the real number. | Split the PR at a file border before it opens. |
| K-3 | The settle depends on Kyo internals (RC section 2.3): a silent fallback after a Kyo change. | C2 canary test (`totalLoad() >= 0`), loud fallback, glitch test. | Upstream rank 1 or 3. |
| K-4 | The R2 cause is a hypothesis (RC section 2.1). | C2 runs the IR split probe before and after the fix; the numbers go in the PR body. If late frames stay, stop and ask before the review. | The PD-10 alternatives (a shorter repair interval). |
| K-5 | Timing-sensitive tests are flaky on a shared 4-CPU machine or on CI (IR contention runs). | 3 local runs alone; core suites run one at a time; never two sbt builds during the final runs. | Raise only test timeouts; never hide a split frame. |
| K-6 | kyo-test and virtual time: `Headless.awaitEnd` and `pendingTimeout` stall under `Clock.withTimeControl` (RC section 3.3). | C2 documents "real clock only" and has a test that uses the real clock. | A real-clock option on `Headless`. |
| K-7 | PTY timing on CI runners (RC-31). | T2: the `terminal` job is green 3 times before review. | Longer expect timeouts; one retry in the script, with a log. |
| K-8 | The Cats full mirror was never generated or compiled (FC summary 3); fs2 3.14.0 against 3.12.2 (FC summary 16). | F1 pre-flight: generate and compile the Cats full mirror in scratch with fs2 3.14.0. | Fix the generator in F1, before gate 2. |
| K-9 | Generated code under the strict flags and scalafmt (FC summary 6, 7). | F1 compiles the ZIO mirror under the real flags; the scalafmt exclude is in F1. | `-Wconf` for `src/*/generated` (FC section 1.3). |
| K-10 | API ripple: a review change in C2/C3 (options, `Headless`, quit) or in F1 (mirror API) changes the PRs above. | Review gates 1 and 2; the WIP limit P-11. | Merge the lower branch up (P-9). |
| K-11 | A jatatui or Kyo type leaks into the public API (RC-36). | C2 API review; the surface guards; the facade `SurfaceLeaks`. | Wrap the type before the first release. |
| K-12 | The kyo-ui jars add about 15.2 MB and may load at run time (RC section 3.1). | T2 `-verbose:class` count in a real run. | The upstream module split (draft). |
| K-13 | Squash merges break the stack. | PD-3 says "merge commit" before the first merge. | Merge `main` into the next branch (no rebase). |
| K-14 | A docs merge deploys the site (PR 2 AGENTS.md; `docs/vercel.json`). | PD-4: the user decides each docs merge. | Keep PR 2 open until D. |
| K-15 | `wip/handoff` is deleted while PR bodies cite it. | Section 3.2: keep it until the stack merges. | Cite commit 1d100ed. |
| K-16 | The AGENTS.md edits of T2 and of PR 2 touch nearby lines ("Daily commands" and the new "Documentation" section). | Merge PR 2 before T2 opens (PD-4). | A merge commit resolves it. |
| K-17 | Review fatigue on a 15-PR stack. | Small PRs, delta guides, gates; the user can merge in batches. | PD-2 coarser grouping (10 PRs). |

---

## 7. Agents, workflows and builds

User limits: at most 4 agents at once; at most 2 of them build; one workflow at a time; announce a run of more than
10 agents first; agents save notes early in `.work/impl/`. AGENTS.md: one sbt process per checkout, so a second
building agent works in its own worktree (`.work/worktrees/<branch>`), as the earlier work did (HANDOFF "State of the
work").

Per PR: implementer (builds) -> reviewers and skeptics (read only; at most 4 at once) -> fixer (builds) -> 3 final
green runs alone on the machine. Small PRs use 1 reviewer and 1 skeptic.

| Workflow | PRs | Agents | Builds at once | Announce? |
| --- | --- | --- | --- | --- |
| W1 | C1 | 6 | 1 | no |
| W2 | C2 | 8 | 1 | no |
| gate 1 | the user reviews C1 and C2, and reads the upstream draft | - | - | - |
| W3 | C3 | 6 | 1 | no |
| W4 | C4 | 6 | 1 | no |
| W5 | T1 | 8 | 1 | no |
| W6 | T2 | 6 | 1 | no |
| W7 | F1 (+ the Cats pre-flight) | 8 | 1 | no |
| gate 2 | the user reviews F1 (the generated ZIO API) | - | - | - |
| W8 | F2 | 8 | 1 | no |
| W9 | F3 + F4 | 14 | 2 (the F4 implementer while F3 is fixed) | YES |
| W10 | L1 + L2 | 10 | 1 | no |
| W11 | P1 | 4 | 1 (the probes alone) | no |
| W12 | D + R | 6 | 0 sbt (`pnpm build` only for D) | no |
| gate 3 | the user reviews the whole stack and merges | - | - | - |

Total: about 90 agent runs in 12 workflows. Token estimate: about 1 to 2.5 M per PR (HANDOFF: 2.1-2.4 M for each of
the old W1-W3), so about 20-25 M in total. This is an estimate, not a measurement. The reusable one-PR workflow
script is on the desktop machine (HANDOFF "Facts the next session must keep"); a cloud session must write it again.

---

## 8. Decisions for the user

Grouped by the point where the work needs them. Each line gives the options, the tradeoff, and a recommendation.

### 8.1 Now (before C1)

- PD-1 PR objects for PRs 3-5: A close + new stacked PRs (RECOMMENDED: no rewrite, small diffs), B force-push the old
  branch names (same numbers, history rewrite), C keep PR 5 and add commits (34 old commits stay; one huge PR).
- PD-2 Stack granularity (refines ASSUMED A1): 15 PRs (RECOMMENDED for this angle: each PR is about one sitting), 10
  PRs (fold C3+C4, T2+P1, F3+F4, L1+L2, D+R: fewer retargets, PRs up to about 2000 lines), or the inventories' 6 code
  PRs (a)/(b)/(c)/(gen)/(zio)/(cats) (fewest PRs; (a) is about 3000 lines).
- PD-3 Merge method: merge commit (RECOMMENDED: the next stacked PR needs no rebase; bisect with `--first-parent`),
  squash (one commit per PR, but each next branch needs a rebase = force push), rebase-merge (same problem). Also:
  merge PR 1 now (RECOMMENDED; it has nothing of the old model).
- PD-4 PR 2 (docs): (a) one fix commit now, merge after PR 1, content in PR D (RECOMMENDED: the lockfile stays out of
  the content review; but a merge may deploy the site, so tell us if Vercel is connected); (b) keep PR 2 open; at the
  end merge `main` into `docs/docus` (a merge commit) and add the content there (PR 2 then mixes 15k lockfile lines
  with the content); (c) rebase PR 2 (a history rewrite; not recommended).
- PD-5 Turn on "Automatically delete head branches" (RECOMMENDED: GitHub then retargets the next stacked PR; today
  `delete_branch_on_merge: false`), or edit each base by hand.
- PD-6 Packages: the core user API in `tui4s` (RECOMMENDED; RC D2) or `tui4s.kyoui`; the facades flat
  `tui4s.interop.zio` / `.cats` (RECOMMENDED; DECISIONS round 4; FC-D1 A) or `.ui`.
- PD-7 Core tests: kyo-test (RECOMMENDED: DECISIONS round 4, and the larger carried body, about 1800 PR 5 test lines,
  is kyo-test; only 208 prototype lines are munit) or munit.
- PD-12 `jatatui-widgets`: drop (RECOMMENDED; the runner imports none) or keep it for a later `Chart`/`Img`.
- PD-16 `Screen` cell style: a small tui4s `CellStyle` (RECOMMENDED; no jatatui type in the API) or jatatui `Style`.
- PD-17 Old branches: keep `feat/core-*`, `feat/zio`, `feat/cats`, `wip/handoff` until the stack merges
  (RECOMMENDED); then the user decides. `chore/cleanup-7p2rll`: tell us if it is still needed. `first-take`: not
  touched.

### 8.2 Before C2 and C3

- PD-8 Duration type in the options: `java.time.Duration` (RECOMMENDED: the same type as `zio.Duration`; Kyo has
  `Duration.fromJava`), `FiniteDuration`, or `Long` milliseconds.
- PD-9 Quit: `quit => ui` in `Headless.start` and `Tui.run`, plus the `Tui.quit` Local for Kyo users (RECOMMENDED), a
  run handle only, or one JVM-wide current run (global state).
- PD-10 R2 fix: settle after the initial mount + a verify pass for young regions (RECOMMENDED), a shorter repair
  interval (more idle CPU), or "pending until the first value" (does not close the window).
- PD-21 Settle default `Settle.Quiescent(100 ms, confirm = 2)`: keep (RECOMMENDED; exact only with upstream rank 1).
- PD-22 The kyo-ui transitive jars (+15.2 MB): accept and measure (RECOMMENDED), exclude (not safe: `HttpUrl` is in the
  AST), or wait for an upstream split.

### 8.3 Before T1 and T2

- PD-25 `InputPort` element: the crossterm `Event` (RECOMMENDED: one mapping path, so the input-thread tests also test
  `EventMapping`) or a runner message (OS D4).
- PD-11 Inline viewport: defer (RECOMMENDED: new `Canvas` code and no user asked; add it later as one small PR) or
  build it in T1 (about +60 main lines and the PR 5 test cases).
- PD-13 Input bursts: one frame per event, measured by `BurstProbe` in P1 (RECOMMENDED: the prototype is measured, and
  a bracketed paste is now one message) or one frame per input batch (old design, up to 512 events).
- PD-14 Ctrl-C: the Ctrl-C KEY is a quit with exit code 0; 130 and 143 only for `kill -INT` / `kill -TERM` from outside
  (RECOMMENDED; PR 5 `TuiApp`), or 130 always.

### 8.4 Before F1-F4

- PD-18 Facade generator choices: FC-D2 (b) opaque event types with Scala accessors (RECOMMENDED); FC-D3 exclude the 9
  drag-and-drop names (RECOMMENDED); FC-D4 the `UiSignallingRef` subtype (RECOMMENDED); FC-D5 Cats explicit quit only
  (RECOMMENDED); FC-D6 all 52 HTML elements (RECOMMENDED); FC-D8 a `NotGiven` guard against a nested ZIO, if a probe
  shows clear overloads (RECOMMENDED); FC-D9 the drift check inside `check` (RECOMMENDED); FC-D10 exclude generated
  files from scalafmt (RECOMMENDED); FC-D11 drop the facade `Component.mount` (RECOMMENDED).
- PD-20 A screen-parity test of the three Counters (old-stack D12): yes, in F4 (RECOMMENDED; cheap once all three
  exist).
- PD-23 ZIO version literal: keep it in `modules/zio/build.sbt` and read it from the examples build (RECOMMENDED: no
  false "unreleased" mark on core and cats) or move it to `project/Versions.scala`.
- PD-24 Long handlers: document "a long handler freezes the UI; use `fork`" for the first release (RECOMMENDED), or let
  the runner fork long handlers (a change of kyo-ui's inline dispatch semantics).

### 8.5 Before L1, L2, D

- PD-15 First-release scope (RC D9): scroll with focus-follows (L1) yes; ellipsis, min-content, 256 colours and the
  unsupported-attribute log (L2) yes; mouse no (it stops text selection); 1 px = 1 cell; placeholders for `Img` and
  `Chart`; all PTY cases. RECOMMENDED as written. If scroll is out, L1 drops; decide before L1 opens, so that L2 is
  opened on F4 and no branch moves.
- PD-19 Docs: one docs PR at the end (RECOMMENDED: prose is reviewed as a whole) or a page per code PR (always true,
  but many partial deploys if PR 2 is merged). R can fold into D.

### 8.6 ASSUMED items (the user said "I like it, go ahead"; please confirm)

- A1 ASSUMED (refined here): the core rework replaces PRs 3-5. The inventories' (a) runner core becomes C1-C4; (b)
  terminal and app becomes T1 plus the `TuiApp` part of T2; (c) examples, ptycheck and bench becomes the rest of T2
  plus P1. PRs 3, 4 and 5 close (PD-1).
- A2 ASSUMED: the kyo-ui upstream issue is a DRAFT in `.work/`; it is never posted without the user. No PR depends on
  it.
- A3 ASSUMED: the ZIO state type is a facade-owned `SignalRef[A]` on the one kyo-ui cell (F1). The core keeps writing
  the bound ref before `onInput` (C3).
- A4 ASSUMED: the generated mirrors are checked in; a `codegen` sbt project; the drift check runs inside `sbt check`
  (F1).

---

## 9. Corrections and additions to the inventories

1. Size: the prototype has long lines. With `maxColumn = 80` (`.scalafmt.conf` on `chore/cleanup`), there are about
   25% more lines (awk count of lines over 80 columns: Layout 461 -> about 576, Dispatch 242 -> about 307, Runner 253
   -> about 315; generator 781 -> about 999). So the inventories' PR (a) is about 3000 main lines with its fixes, not
   the 2257 lines of the prototype. This is why C1-C4 split it.
2. `GuardTest.scala` (PR 5 head, 58 lines) tests the old `NotForeign` on `Source.task` and the keymap duplicate check
   (`git show`). It DROPS. inventory-runner RC-27 lists it as carried; inventory-old-stack (PR 4 row) is right.
3. Two branches are not in the inventories: `origin/chore/cleanup-7p2rll` (same head as `chore/cleanup`, 6f70179) and
   `origin/first-take` (05599dd, on `main`).
4. The repo setting `delete_branch_on_merge` is false (`gh api repos/yuri4n/tui4s`). The HANDOFF "Merge order" note
   ("GitHub retargets stacked bases when a base branch is merged and deleted") needs a delete step, and that step does
   not happen by itself today.
5. `docs/docus` (PR 2): a merge to `main` can deploy (its own AGENTS.md section and `docs/vercel.json`). It also adds
   the rule "When a public API changes, change its page in the same pull request", which binds the code PRs once PR 2
   is merged (PD-4, PD-19).
6. `Paint.scala` does not need `FrameStats` (only a comment names it, line 117), so the render PR (C1) has no public
   run type.
7. The prototype on `origin/wip/handoff` is byte-identical to the local `.work/proto` (runner `src`, facade `gen`,
   `zio/src`, `cats/src`, integration `glue`: `cmp`, 0 differences), so the PR delta guides can cite git refs.
8. `RecordingControl` and `ScriptedInput` are test doubles. This plan moves them to test sources (T1), not to the
   public `tui4s.testkit` (a smaller binary surface). The bench (P1) drives `Headless`, so it does not need them.
9. The `Counter`, `Todo` and `Dashboard` prototype apps use plain `Signal.initRef` (grep), so the regions PR (C2) can
   test them before hooks exist; only `CounterStyles` needs C4.

---

## Progress log

- 2026-10-03: read DECISIONS.md, HANDOFF.md, the three inventories, and the report sections "What carries", "Open
  items", "What does not work", "Gaps" and "Open questions".
- 2026-10-03: checked with git and `gh api` (read only): the branch list and heads, the PR list and bases, the repo
  merge settings, the base build files, the PR 5 file sizes, the prototype file sizes and long lines, `GuardTest`,
  the docs workflow and Vercel config, the generated mirror dependencies, `Mount.scala:117`, the unscoped fibers, the
  first frame without a settle, the stderr print, the prototype on `wip/handoff`.
- 2026-10-03: wrote the plan (sections 0-9). DONE.
