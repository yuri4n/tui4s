<!-- claude 2026-10-03: created this file (planning agent, facades part) -->
# Inventory: the facades as the real tui4s-zio and tui4s-cats modules

Status: DONE (2026-10-03). Planning only. I did no build, no checkout, no commit, no push and no GitHub write.
Scope: the ZIO and Cats facades as the published modules `tui4s-zio` and `tui4s-cats`, and the codegen project
that writes their mirrors. The runner (core) is in `inventory-runner.md` (items RC-*). The old stack is in
`inventory-old-stack.md` (items OS-*). This file uses those ids and does not repeat their content.

Short names for the evidence:

- FR = `.work/proto/kyoui-facades/REPORT.md` (findings F1-F8, open items O1-O8, questions Q1-Q3, skeptic section
  "Independent verification", probes P1-P4).
- IR = `.work/proto/kyoui-integration/REPORT.md` (runner fixes R1-R5, facade fixes F1-F3, "Gaps for the real
  modules", questions Q1-Q4).
- RR = `.work/proto/kyoui-runner/REPORT.md`.
- `proto/` = `.work/proto/`. Git refs are `origin/<branch>`.
- ASSUMED = a working assumption that the user accepted with "I like it, go ahead". The user must still confirm it:
  A1 (core rework as PRs (a) runner core, (b) terminal and app, (c) examples, ptycheck, bench; PRs 3 and 4 closed or
  replaced), A2 (kyo-ui upstream issue drafted only), A3 (ZIO state type is a facade-owned `SignalRef[A]`), A4
  (generated mirrors checked in, codegen sbt project, CI drift check).

## Progress log

- 2026-10-03: read AGENTS.md, DECISIONS.md, HANDOFF.md, FR. Started this file.
- 2026-10-03: read the facade sources (`gen/`, `zio/src`, `cats/src`, tests), the IR glue and tests, the branches
  `feat/zio` and `feat/cats` (build, `Tui`, `TuiApp`, two tests), the build files of `chore/cleanup` (`build.sbt`,
  `project/ModuleVersioning.scala`, `project/Versions.scala`, `ci.yml`, `.scalafmt.conf`), and the two other plan
  inventories.
- 2026-10-03: checked how the runner keys `Component` state (`Mount.scala:117`). Wrote all sections. DONE.

---

## 0. Summary (facts)

1. The prototype facades work, on the HTML stand-in (FR) and on the terminal runner (IR): ZIO and Cats tests green
   in 3 of 3 runs (FR "Re-runs"); 12 of 12 terminal tests in 3 of 3 runs, PTY 10 of 10 and 22 of 22 (IR "Result").
2. The generator is a scala-cli project of 777 lines (`proto/kyoui-facades/gen/`: Generate 591, KyoUiApi 127,
   Inventory 59). It reads kyo-ui TASTy with tasty-query 1.9.0 and gets the class path from `java.class.path`
   (`KyoUiApi.scala`). Its output is byte-identical on re-runs (FR results 1, skeptic step 3a).
3. Only a ZIO FULL mirror exists (52 elements, 1116 lines, `full-zio/generated/ZioUiFull.scala`).
   `writeFullMirror` is ZIO only (`Generate.scala:585-591`). A Cats full mirror was never generated or compiled.
4. The generator header hard-codes "kyo-ui:1.0.0-RC7" (`Generate.scala:522`). It does not read `Versions.kyo`.
5. The sbt meta-build (`project/*.scala`) is Scala 2.12 (`.scalafmt.conf`, fileOverride "sbt compiles the
   meta-build ... with Scala 2.12"). tasty-query is a Scala 3 library (only `tasty-query_3` is in the coursier
   cache of this machine). Thus the generator cannot live in `project/`. It must be an sbt sub-project.
6. The generated code does not pass `scalafmtCheckAll` as it is: 4-space indent and lines longer than 80 columns
   (`zio/generated/ZioUi.scala`; `.scalafmt.conf` `maxColumn = 80`). `.scalafmt.conf` has no exclude path today.
7. The prototypes compile with `-Wunused:imports` only (`zio/project.scala`, `cats/project.scala`). The build uses
   `-Wunused:all -Wvalue-discard -Wnonunit-statement -Yexplicit-nulls` (`build.sbt` @ `chore/cleanup`). The
   generated catch-all overloads have an unused parameter `other`. No fatal-warning flag exists, so this is noise,
   not a failure (unverified under the strict flags: OS-39).
8. Top-level definitions compile into a class named after the FILE (`ZioUi.scala` -> `ZioUi$package`). Opaque types
   erase to the kyo-ui classes. Thus kyo-ui classes are in the facade BINARY API, and a rename or split of the
   generated file is a binary break (Scala 3 top-level encoding; MiMa runs through `versionPolicyCheck`).
9. The hand-written runtime is 768 lines (FR "Measurements"): `zio/src` 332, `cats/src` 436. Of these, 80 lines are
   the HTML test stand-ins (`zio/src/Headless.scala` 36, `cats/src/Headless.scala` 44) that IR F1 drops, and 344 are
   vendored (`AmbientZIOs` 136, `CatsBridge` 208). The IR glue is 233 lines (`glue/zio/Tui.scala` 122,
   `glue/cats/Tui.scala` 111).
10. `AmbientZIOs`, `CatsBridge` and `AmbientDispatcher` exist on `feat/zio` / `feat/cats` with test suites
    (`AmbientZIOsSuite` 229 lines; `CatsBridgeSuite` 264, `DispatcherSuite` 75). OS-22 and OS-23 found them
    identical to the prototype files except the package.
11. GAP (not prototyped): facade components are NOT runner-mounted. `Component.mount` and `useSignal` run when the
    app's `ZIO[Scope, ...]` / `Resource` is built (`zio/src/Hooks.scala`; SIDE-BY-SIDE.md row "Where state lives").
    The core has runner-mounted components: `Component.ui`, `Hooks.component(key)`, `onMount`, `useHold`, `useFold`,
    `useEffect`, `useInterval` (`proto/kyoui-runner/src/tui4s/kyoui/{Component,Hooks}.scala`). The facades mirror
    none of them. So a facade component cannot live inside `render`, `foreach` or `when`.
12. The core keys `Component` state by tree path and `c.getClass.getName` (`internal/Mount.scala:117`). A facade
    adapter class would give ONE class name for all facade components, so the core needs a key hook (FC-17).
13. The facade glue loses typed errors: the ZIO `Tui.run` returns `UIO[Unit]` (`glue/zio/Tui.scala`), because the
    prototype core `Tui.run` has no error channel (`Unit < Async`, `kyoui-runner/src/tui4s/kyoui/Tui.scala`).
14. The runner `Headless` returns a jatatui `Cell` (`cell(x, y)`) and a `kyo.Chunk` (`typeText`) (`Tui.scala:160,
    172` of the runner prototype). A facade kit must not pass these on.
15. The full mirror has top-level factories with short names: `a`, `main`, `option`, `select`, `label`, `code`, `p`
    (`ZioUiFull.scala:223-274`), and `when`, `empty`. It has no top-level `text`, so the `import fs2.*` clash of the
    old Cats facade (`.work/impl/cats-impl.md`, OS-old-stack section 2) is gone. The mirror type `Signal` clashes with
    `fs2.concurrent.Signal` under two wildcard imports.
16. `chore/cleanup` already has `modules/zio/build.sbt` (kyo-zio, zio 2.1.26) and `modules/cats/build.sbt`
    (cats-effect 3.7.1, fs2 3.14.0). The prototypes compiled `KyoSignallingRef extends SignallingRef` against fs2
    3.12.2 only (`cats/project.scala`; IR `project.scala`).
17. Zero facade work can be green before the core has: a Kyo-free `TuiOptions` (R4), quit as a value (R5), a typed
    error channel, the runner `Headless`, and the R1 leak fix (section 5).

---

## 1. The generator as an sbt codegen project (ASSUMED A4)

### 1.1 Where it lives

| Option | What | For | Against |
| --- | --- | --- | --- |
| A. Sub-project `codegen/` (RECOMMENDED) | Scala 3.9.0 project, `publish / skip := true`, in the root aggregate. Deps: `tasty-query 1.9.0`, `"io.getkyo" %% "kyo-ui" % Versions.kyo`. No `dependsOn` to core, zio or cats. Mains: `write`, `check`, `inventory`. | Same Scala as the modules. Runs when a mirror does not compile (a kyo-ui bump). OS-42 proposes it. | One more project to compile in `sbt check`. |
| B. `project/` meta-build | Generator as sbt build code. | No extra project. | Not possible: meta-build is Scala 2.12; tasty-query and the generator are Scala 3 (summary 5). |
| C. `sourceGenerators` in zio and cats (no checked-in output) | Generate at each compile. | No drift possible. | Against A4. Slower compile. Metals sees the mirror only after a compile. Review cannot see the API in the diff. |

Rule: `codegen` must never depend on `zio` or `cats`. Reason: after a kyo-ui bump the old mirror can fail to compile,
and the generator must still run.

Effect on versions: the output files are in `modules/zio/...` and `modules/cats/...`. `ModuleVersioning` watches
`modules/<m>` and `project/Versions.scala` (`build.sbt` `module(...)`). A change in the output marks the module
unreleased. A change only in `codegen/` does not. This is correct: only the output ships (OS-42).

### 1.2 How it reads the kyo-ui TASTy

- Today: `KyoUiApi` builds a tasty-query `Context` from `jrt:/modules/java.base` plus every entry of
  `java.class.path`, and finds the kyo-ui jar by its file name (`kyoUiEntry`). tasty-query needs the whole class path
  (kyo-core, kyo-data, scala-library) to resolve `kyo.Maybe`, `kyo.Signal` and the others.
- Change (FC-2): the sbt task gives the class path as an argument: `(codegen / Compile / dependencyClasspath)`. The
  generator does not read `java.class.path`. Reason: a forked sbt run can use a class-path jar, and an explicit list
  is easy to test. Keep the file-name match for the kyo-ui entry. Fork the run with `-Xss16m` (`.jvmopts` precedent).
- The task also passes `Versions.kyo`, the target paths and the package names. The header then names the real
  version (summary 4).

### 1.3 Outputs, format and compiler flags

| Output (checked in) | Content |
| --- | --- |
| `modules/zio/src/main/generated/ZioUi.scala` | ZIO mirror, all HTML elements (decision FC-D6). |
| `modules/cats/src/main/generated/CatsUi.scala` | Cats mirror, same elements. NEW: never generated before (summary 3). |
| `modules/<m>/src/test/generated/GuardSpec.scala` | NEW: one `compileErrors` check per (receiver, handler name) (FC-30). |

- A separate source root `src/main/generated` (added with `Compile / unmanagedSourceDirectories`) makes a clear
  "do not edit" border and one simple glob for the format exclude and the drift check. Alternative: a `generated/`
  folder inside `src/main/scala`. Both work; I recommend the separate root.
- Keep the file names stable for ever (summary 8). Never split a published mirror file without a minor bump.
- Format: add `project.excludePaths = ["glob:**/src/*/generated/**"]` to `.scalafmt.conf`. The generator owns the
  layout, and the drift check pins it. Alternative: run scalafmt-dynamic inside the generator (one more dependency,
  and the output then changes with each scalafmt version). Recommended: exclude.
- Flags: the generator writes `@scala.annotation.nowarn("msg=unused")` on each catch-all, or the build adds
  `-Wconf:src=.*/generated/.*&msg=unused:s`. Check `-Yexplicit-nulls` and `-Wnonunit-statement` at the first
  compile (risk, OS-39).

### 1.4 The drift check

- `sbt mirrors` (alias of `codegen/run write ...`) writes all outputs. A developer runs it and commits the result.
- `codegen/mirrorsCheck` (a task) generates into `codegen/target/mirrors-check/`, compares each file byte by byte
  with the checked-in file, and fails with: "the mirrors are stale: run `sbt mirrors` and commit". It prints the
  first different line of each file.
- Put `codegen/mirrorsCheck` in the `check` alias (before `Test/compile`). Reason: AGENTS.md says that `sbt check`
  does the same checks as CI. CI (`ci.yml`, job `sbt`) then needs no change.
- Alternative: a CI step `sbt mirrors && git diff --exit-code`. Simpler task code, but a local `sbt check` then
  does not find drift. Not recommended.
- Cost: one more compile (about 800 lines plus tasty-query) and one TASTy load per `sbt check`. Not measured.
  Measure it in the codegen PR.

### 1.5 Fail closed

Today the generator only PRINTS a setter with no rule ("skipped ... no rule") and a "leak through alias"
(`logs/fixer-generate-full.txt`). A new effect point in a new kyo-ui would then be missing with no error.

Change (FC-4): the generator FAILS (exit code 1) when:

- a setter or factory has no rule and is not in the checked-in exclusion list (today: the 9 drag-and-drop setters,
  decision FC-D3);
- an aliased data type has a member that names a Kyo type that is not in the checked-in leak list (today: 17
  "leak through alias" lines, decision FC-D2);
- a kyo-ui data type is used that has no alias.

### 1.6 What happens on a kyo-ui version bump

1. Change `Versions.kyo` (kyo-zio uses the same constant). All three modules become unreleased
   (`project/Versions.scala` is in every module's paths).
2. If kyo-ui is built with a newer Scala, also bump `Versions.scala` and, if needed, tasty-query (a tasty-query
   version reads TASTy only up to the version it knows). AGENTS.md: change Scala in all modules at once.
3. Run `sbt mirrors`. The generator fails on each new setter with no rule, each new leak, each new data type
   (section 1.5). Add a rule, an alias or an exclusion. Commit the new mirrors.
4. Run `sbt check`: the guard tests, the surface tests, the AST test, the vendored bridges (`AmbientZIOs` uses
   kyo-zio internals such as `ZIOs.toResult`; `CatsBridge` uses Kyo fiber APIs), and the core guards (RC-11
   settle canary, RC-12 kyo-ui surface guard).
5. Compatibility: the facade binary API names kyo-ui classes (summary 8). After the first release, a kyo-ui bump is
   a binary break unless MiMa says otherwise: set `versionPolicyIntention := Compatibility.None` in core, zio and
   cats in the same PR (AGENTS.md "Compatibility").
6. Release order: core first, then zio and cats (AGENTS.md "Releasing").

---

## 2. Per module

### 2.1 Package names (decision FC-D1)

| Option | User import | For | Against |
| --- | --- | --- | --- |
| A. Flat `tui4s.interop.zio` / `tui4s.interop.cats` (RECOMMENDED) | `import tui4s.interop.zio.*` gives the DSL, `Tui`, `TuiApp`, `useSignal`, `Component`. Test kit: `tui4s.interop.zio.testkit`. Internals: `tui4s.interop.zio.internal`. | DECISIONS round 4. One import. Same internal package as `feat/zio` / `feat/cats` (`...interop.zio.internal`). | Many short names in one package (`a`, `p`, `main`, `when`). The generator's access qualifier changes from `private[ui]` to `private[zio]` / `private[cats]` (one parameter, `Generate.lastSegment`). |
| B. Prototype `tui4s.interop.zio.ui` (DSL and glue) | `import tui4s.interop.zio.ui.*` | Matches the prototypes, IR, SIDE-BY-SIDE.md. Leaves `tui4s.interop.zio` free for later non-UI API. | Against DECISIONS round 4 (asked before the direction change). A longer import. |
| C. DSL in `.ui`, run API in the parent | two imports | Clear split. | Two imports for every app (against OS-32 "one import"). |

All options keep `_root_.zio` / `_root_.cats` in generated code (inside package `...interop.zio`, the name `zio`
means the facade package). The generator already does this.

### 2.2 tui4s-zio: file map

| Target (package per option A) | Source | Action | Lines (est.) |
| --- | --- | --- | --- |
| `src/main/generated/ZioUi.scala` | generator, ZIO, all HTML elements | generate | ~1120 (generated) |
| `SignalRef.scala` | `proto/kyoui-facades/zio/src/SignalRef.scala` | keep (A3); Scaladoc of `changes` semantics (FR O7) | 72 |
| `internal/Bridge.scala` | `zio/src/Bridge.scala` | keep | 55 |
| `Hooks.scala` (`useSignal`, `useStream`, `component`, `Component`) | `zio/src/Hooks.scala` | adapt: runner-mounted `Component` and `component(key)` (FC-17) | 33 -> ~140 |
| `internal/AmbientZIOs.scala` + `AmbientZIOsSuite` | `feat/zio` (OS-22) | keep; fix stale texts (FR O5) | 131 + 229 |
| `Tui.scala` (`run`, `quit`, options, errors, loggers) | IR `glue/zio/Tui.scala` + `feat/zio` `Tui.scala` (`quietly`) | adapt (FC-12) | ~180 |
| `TuiApp.scala` | `feat/zio` `TuiApp.scala` | adapt (FC-13) | ~70 |
| `testkit/Headless.scala` | IR `TuiHeadless` | adapt (FC-14) | ~110 |
| `exports.scala` | new | `export` of core Kyo-free types (FC-15) | ~20 |
| `zio/src/Headless.scala`, `shared/kyo/tui4sproto/Headless.scala` | FR | DROP (IR F1) | -154 |
| tests | FR suites, IR suites, `feat/zio` tests | adapt (FC-19) | ~1000 |

### 2.3 tui4s-cats: file map

| Target | Source | Action | Lines (est.) |
| --- | --- | --- | --- |
| `src/main/generated/CatsUi.scala` | generator, Cats, all HTML elements | generate (first time for the full set) | ~1120 (generated) |
| `internal/Bridge.scala` (`KyoSignallingRef`) | `cats/src/Bridge.scala` | adapt: ref type per FC-D4; compile against fs2 3.14.0 | 111 |
| `Hooks.scala` | `cats/src/Hooks.scala` | adapt (FC-17) | 34 -> ~140 |
| `internal/CatsBridge.scala`, `internal/AmbientDispatcher.scala` + `CatsBridgeSuite`, `DispatcherSuite` | `feat/cats` (OS-23) | keep; fix messages (FR O5, IR F3); keep the Apache-2.0 header and the "Changes from the original" list | 247 + 339 |
| `Tui.scala` (`run`, `runWithQuit`, `resource`, errors) | IR `glue/cats/Tui.scala` + `feat/cats` `Tui.scala` (`resource`, `end` fiber) | adapt (FC-12) | ~200 |
| `TuiApp.scala` | `feat/cats` `TuiApp.scala` | adapt (FC-13) | ~70 |
| `testkit/Headless.scala` | IR `TuiHeadless[F]` | adapt (FC-14) | ~110 |
| `exports.scala` | new | as ZIO | ~20 |
| `cats/src/Headless.scala` | FR | DROP (IR F1) | -44 |
| tests | FR, IR, `feat/cats` | adapt (FC-20) | ~1100 |

### 2.4 The glue: run, quit, options, errors, output

- Run: ZIO `Tui.run(options)(app)`; Cats `Tui.run[F](options)(app)`. Both build the app in the user's effect
  system (ZIO `Scope`, Cats `Resource`), then run the kyo-ui value on the core runner inside the bridge
  (`AmbientZIOs.run`, or `CatsBridge.runF` with `AmbientDispatcher.let(d)`) (IR "How it works" steps 1-3).
- Quit (decision FC-D5): with core R5 (`Tui.run(options)(quit => ui)`, RC-2 / core D4) the glue needs no extra Kyo
  fiber (IR `withQuit` goes away). ZIO keeps `Tui.quit` through a `FiberRef` (IR glue, test "glue: Tui.quit in a
  ZIO handler"). Cats keeps the explicit `quit: F[Unit]` value.
- Options (IR Q4): with core R4 (RC-3, all options are Scala or JDK types), the facades RE-EXPORT the core
  `TuiOptions`: no mirror, no mapping. `java.time.Duration` (core D3) is `zio.Duration`; Cats users write
  `.toJava` (`scala.jdk.DurationConverters`).
- Errors: ZIO `IO[TuiError, Unit]`; Cats `F[Unit]` that fails with `TuiError` (an `Exception`, PR 5 `Options.scala`).
  `AmbientZIOs.run` already maps `Abort[E]` to the ZIO error channel. Needs the core error channel (RC-2).
- Output: ZIO removes the default loggers in its own `ZIO.scoped` around the run only (old `quietly`; fixes
  spec-3, OS-33). NEW: the ZIO run also replaces the `zio.Console` service for the run (`ZIO.withConsole`) with a
  console that writes to the run log. Reason: IR probe `ZioOutputProbeEarlyMain` shows `zio.Console.printLine` on
  the screen when `scala.Console` loaded early (IR "What does not work" 3). `AmbientZIOs.run` captures the runtime
  inside that region, so handlers see the replaced service. Not prototyped: verify with the IR output probe. Cats
  `IO.println` already goes to the log (IR progress 15:23 UTC). Scala `println` keeps the core limit (RC-10:
  "use `TuiApp`").

### 2.5 Main classes

| | ZIO `TuiApp` | Cats `TuiApp` |
| --- | --- | --- |
| Base | `ZIOAppDefault` (`feat/zio`) | `IOApp` (not `IOApp.Simple`), so the app gets `args` |
| Constructor | `SchedulerDefaults.applyIfUnset()`; `OutputCapture.install()` (`feat/zio`, `feat/cats`; DECISIONS round 4) | same |
| User writes | `def ui: ZIO[Scope & ZIOAppArgs, Nothing, UI]` (gives `args`: ZIO finding api-2, OS-24); `def options`; `def onExit: Task[Unit]` | `def ui(args: List[String], quit: IO[Unit]): Resource[IO, UI]`; `def options`; `def onExit: IO[Unit]` |
| Runtime settings | default loggers off during the run (2.4) | CPU starvation checker off (`runtimeConfig`, `feat/cats` `TuiApp.scala`) |
| Exit codes | Follow core D10 (Ctrl-C key = quit, exit 0; 130 / 143 only for real signals). The effect runtimes set their own code on a signal; check with PTY rows `kill -INT`, `kill -TERM` (RC-31). Not verified for the new glue. | same |
| Old state result | Dropped: a kyo-ui app has no single state (`onExit` takes no state). | same |

### 2.6 Test kits

- Each facade has `testkit.Headless` over the core `Headless` (RC-7): `start(app, width, height, options)`, `key`,
  `char`, `typeText`, `screen`, `lines`, `focus(id)`, `awaitEnd`, `resize`. It returns `FrameStats` (a core type
  with no Kyo type) and the core `Screen` (RC-28, core D11: no jatatui `Cell`). `typeText` returns `Seq`, not
  `kyo.Chunk` (summary 14).
- Quit-and-wait before the scope ends moves INTO the core `Headless` (RC-7). Then the facade kits need no workaround
  for the R1 leak (IR progress 15:49 UTC).
- `focus(id)`: Tab until the element has the focus, at most N presses, then a failure with the focused id (IR
  `focusOn`, `ZioOnTerminal.test.scala:30-38`). It replaces "click by id" of the HTML stand-in, and it closes the
  lesson of FR O4 (no silent drop of a wrong id).
- Rules (OS-34, OS-35): no unbounded Kyo wait inside a ZIO acquire or a Cats `Resource` acquire; never join the Kyo
  run fiber from an interruptible wait; never race or time out a wait on a long-lived Kyo promise.
- ZIO TestClock: a handler runs inline in the runner dispatch (FR F7), so a handler that sleeps on the caller's
  `TestClock` stops `key` from returning (OS-31). Document it in the kit and test it (`zio-test` in Test scope).

### 2.7 Components at runner mount (the gap of summary 11)

The user asked for both styles, and for the `Component` class "the runner allocates state at mount"
(DECISIONS round 5). The facades do not do this today. Proposal (FC-17), the same for ZIO and Cats:

- `component(key)(body: ZIO[Scope, Nothing, HtmlNode]): HtmlNode` over core `Hooks.component(key)`. The ZIO `Scope`
  of `body` closes when the core's Kyo `Scope` of the mount closes (a ZIO `Scope.make`, closed in `Scope.ensure`).
  The `Here` of the call site goes to the core as the `Frame` (the core keys hook components by call site).
- `abstract class Component { type State; def initial; def view(s: SignalRef[State]): HtmlNode; def onMount(s):
  ZIO[Scope, Nothing, Any] }`, made a child by an implicit conversion. Inside, a core `Component` adapter wraps the
  kyo cell as the facade ref (the facade ref IS a wrapper of the kyo cell, so no copy).
- Core dependency: the core keys `Component` state by `c.getClass.getName` (`Mount.scala:117`). One adapter class
  for all facade components would share state between different facade classes at one position. The core needs an
  overridable key (for example `protected def stateKey: String = getClass.getName`). This is a small change to
  RC-5.
- `useEffect` / `useInterval`: ZIO users have `forkScoped` and `ZStream`; Cats users have `background` and fs2.
  Recommendation: no mirror; `useStream` covers `useHold`. Keep `component` and `Component`.
- Keep `Component.mount` (build-time state) or drop it? Recommendation: drop it; one meaning for "Component".

### 2.8 Integration fixes F1-F3

| Id | What | Plan |
| --- | --- | --- |
| IR F1 | Drop the HTML stand-in in package `kyo` (`shared/kyo/tui4sproto/Headless.scala`, `zio/src/Headless.scala`, `cats/src/Headless.scala`). | DROP in the module PRs. Port the FR suites from `Headless.mount` + `click(id)` / `input(id, v)` to the terminal kit (keys and focus). After this, no tui4s code is in package `kyo` (no `private[kyo]` use: RR N9). |
| IR F2 | Move the glue into the module sources. | DO, in `Tui.scala` and `testkit/Headless.scala` of each module. With option A of FC-D1 the access qualifier is `private[zio]` / `private[cats]`. |
| IR F3 | Stale message in `AmbientDispatcher` (names `tui4s.interop.cats.Tui.run`, `Tui.resource`, `TuiApp`). | FIX: name the real entry points (`Tui.run`, `Tui.resource`, `TuiApp`, `testkit.Headless.start`). |

---

## 3. Open items: fix in the module PR, defer, or decide

| Item | Evidence | Plan | FC |
| --- | --- | --- | --- |
| FR F1 lift trap is real | FR F1, C1, P4a/P4b | FIX (keep as test): the canary test "the lift trap is real WITHOUT the mirror". If kyo-ui changes handler types upstream, it flips and tells us. | FC-30 |
| FR F2 extension cannot shadow a member | FR F2, P1a-P1c | KEEP the opaque-type design; keep the guards "raw kyo type does not compile" and "raw named setter does not compile". | FC-30 |
| FR F3 trait-level forwarders | FR F3 | KEEP (generator design). | FC-1 |
| FR F4 ZIO seals `Ref` | FR F4, P3 | KEEP `SignalRef` (ASSUMED A3, confirm). | FC-9 |
| FR F5 two cells race | FR F5, C7 | KEEP the stress suites (about 2 s each) in the normal test run, as a guard against a second cell. | FC-19, FC-20 |
| FR F6 CAS by reference | FR F6, C5 | KEEP; add a small contention test of `modify` (N fibers, N updates, exact sum). | FC-19 |
| FR F7 handler inline in dispatch | FR F7, C9 | DOCUMENT: a long handler freezes the UI; use `fork` / `background`. Ask the core (RC decision) whether the runner should fork long handlers. TestClock rule (2.6). | FC-34 |
| FR F8 `Dispatcher.parallel(await = true)` loses a cancel | FR F8, `logs/skeptic-rerun-pure-dispatcher.txt` | KEEP `await = false`; keep `DispatcherSuite` from `feat/cats`. | FC-11 |
| FR O1 / Q2 drag and drop | FR O1; skeptic 3a | DECIDE (FC-D3). Recommendation: exclude all 9 names, also the action-only forms, until the runner has a mouse (RR N8, core D9 "no mouse"). | FC-4, FC-32 |
| FR O2 / Q1 Kyo data types through aliases (`Maybe`, `Chunk`, `ConcreteTag`, `Schema`, `HttpUrl`) | FR O2; skeptic P1, R1; `logs/fixer-generate-full.txt` (17 leak lines) | DECIDE (FC-D2). | FC-31 |
| FR O3 / Q3 Cats binding takes any `SignallingRef` (run-time failure) | FR O3; skeptic P2 | DECIDE (FC-D4). Recommendation: a tui4s subtype in the setter signatures (compile-time check). | FC-10 |
| FR O4 headless drops a missing id | FR O4; skeptic P4, R3 | OBSOLETE with IR F1 (the HTML stand-in goes). Lesson: bounded `focus(id)` that fails loudly (2.6). | FC-14 |
| FR O5 stale texts in the vendored bridges | FR O5; skeptic 3e | FIX in the module PRs. Carry `CatsBridgeSuite` from `feat/cats`; its stress test re-verifies the "VERIFIED by a hanging test" claim. Run it 3 times. | FC-11 |
| FR O6 folders not self-contained | FR O6 | OBSOLETE in sbt (the build has the deps). | - |
| FR O7 `SignalRef.changes` merges fast changes; interrupted wait keeps its callback | FR O7; OS-35 | FIX as docs + test: Scaladoc says "latest value"; a stress test interrupts `changes` 1000 times and checks the heap stays flat. Do not add a pump (FR F5). | FC-9, FC-36 |
| FR O8 headless only | FR O8 | CLOSED by IR. The module PRs add PTY rows (FC-33). | FC-33 |
| Skeptic: Cats foreign `SignallingRef` fails at run time | skeptic P2 | Same as FR O3. | FC-10 |
| Skeptic: `Maybe` / `Chunk` aliases leak data types | skeptic P1 | Same as FR O2. | FC-31 |
| Skeptic: drag and drop not generated | skeptic 3a | Same as FR O1. | FC-4 |
| Skeptic: headless drops events on a missing id | skeptic P4 | Same as FR O4. | FC-14 |
| IR R1 leak of an interrupted run | IR "What does not work" 1 | CORE (RC-8). Needed before the facade `Tui.run` can be cancelled safely (it has no workaround). | dep |
| IR R2 first-key race | IR "What does not work" 2 | CORE (RC-9). Facades hit it more often (ZIO 13 of 300 under load). IR Q3: keep the kit strict and require RC-9. | dep |
| IR R3 `scala.Console` capture | IR "What does not work" 3 | CORE (RC-10) + facade `TuiApp` install + ZIO console override (2.4). | FC-13, FC-27 |
| IR R4 Kyo-free options | IR R4 | CORE (RC-3); facades re-export. | FC-15 |
| IR R5 quit as a value | IR R5 | CORE (RC-2, core D4); facades drop `withQuit`. | FC-12 |
| IR Q1 Cats quit | IR Q1 | DECIDE (FC-D5). | FC-12 |
| IR Q2 Ctrl-C exit code | IR Q2 | Follow core D10. | FC-13 |
| IR Q3 strict kit | IR Q3 | Strict; needs RC-9. | FC-14 |
| IR Q4 facade options | IR Q4 | Re-export the core type (needs R4). | FC-15 |
| IR gap: no facade Dashboard | IR "What does not work" 6 | NEW examples with `useStream` (old `ziodashboard`, `catsdashboard` as the model, OS-29). | FC-33 |

---

## 4. Surface checks and lift-trap guards

### 4.1 SurfaceLeaks as a CI check (FC-29)

- The check: `proto/kyoui-facades/shared-test/SurfaceLeaks.test.scala` (54 lines, tasty-query). It lists each public
  or protected signature of a package that names a `kyo.*` type, and a test allows only the aliased data types.
- Where the helper lives (OS-17):

| Option | For | Against |
| --- | --- | --- |
| A. One copy in a neutral folder (for example `codegen/src/surface/scala`), added to both facades with `Test / unmanagedSourceDirectories` (RECOMMENDED) | One copy. Precedent: PR 5 added `modules/core/src/surface/scala` to the facade tests this way (`modules/zio/build.sbt` @ `feat/zio`). All TASTy code lives under `codegen/`. | A path outside the module (test only; does not change the module version). |
| B. A copy in each module | Each module stands alone. | Two copies drift. |
| C. An unpublished `checks` project that depends on zio and cats | Can also hold a cross-facade screen-parity test (old-stack D12). | One more project; the check is far from the module. |

- Packages to scan: the facade package, `testkit`, `internal` (visible members only), and the re-exported core
  types (FC-15).
- The allow-list: from decision FC-D2. The generator also writes the alias list into `src/test/generated/`, so the
  test and the generator cannot disagree.
- It runs in `sbt test`, so in `sbt check` and in CI.

### 4.2 Lift-trap guards (FC-30)

1. GENERATED guard spec: one `compileErrors` line per (receiver, handler name), for a plain value, a ZIO that can
   fail (ZIO), an `F[A]` that is not `F[Unit]` (Cats), and a Kyo effect. It checks the exact tui4s message, not only
   "error". Today the guard test covers only `onClick` (FR "User-facing API"); the full mirror has 56 effect points
   (`logs/fixer-generate-full.txt`).
2. Canary: "the lift trap is real WITHOUT the mirror" (raw kyo-ui `onClick(zio)` never runs). Port it to the
   terminal kit (press Enter on the raw button).
3. Raw access guards: `val b: kyo.UI.Ast.Button = button("x")` and the raw named setter do not compile.
4. Nested effect (decision FC-D8): `onClick(ZIO.succeed(count.update(_ + 1)))` compiles today (result type `Any`),
   and the inner ZIO never runs. Options: keep `Any`; or add a `NotGiven[A <:< ZIO[?, ?, ?]]` evidence to the ZIO
   handler overloads; or use `ZIO[Any, Nothing, Unit]` (Cats already uses `F[Unit]`). Not prototyped.
5. Name-clash compile tests (FC-35, OS-32): one file per facade with the facade import and `zio.*`,
   `zio.stream.*` (ZIO) or `cats.*`, `cats.effect.*`, `cats.syntax.all.*`, `fs2.*`, `fs2.concurrent.*` (Cats). The
   known clash is `Signal` with `fs2.concurrent.Signal` (summary 15): document it, or rename the mirror type.

---

## 5. Order and dependency on core

### 5.1 What the facades need from the core first

| Need | Core item | Why | Blocks |
| --- | --- | --- | --- |
| Package layout (`tui4s.Tui`, `tui4s.testkit.Headless`, ...) | RC-1 (core D2) | The glue imports them. | all facade code |
| `Tui.run(options)(quit => ui)` with `Abort[TuiError]` | RC-2 (core D4), R5 | No extra Kyo fiber for quit; typed errors (summary 13). | FC-12 |
| Kyo-free `TuiOptions`, `TuiError` | RC-3 (core D3), R4 | Re-export with no mirror. | FC-15 |
| `FrameStats`, `FrameCause` public, Kyo-free | RC-6 | Test kit result. | FC-14 |
| `Headless` with quit-and-wait, `awaitEnd`, `resize`, `Screen` (no jatatui `Cell`), Kyo-free results | RC-7, RC-28 (core D11) | Facade kits wrap it. | FC-14 |
| `Hooks.component(key)`, `Component` with an overridable state key | RC-4, RC-5 (+ the key hook) | Runner-mounted facade components. | FC-17 |
| R1 leak fix | RC-8 | `Tui.run` cancel; kit scope end. | FC-12, FC-14 |
| R2 first-key race fix | RC-9 | Facade tests are strict (IR Q3). | FC-19, FC-20 |
| `OutputCapture.install`, `SchedulerDefaults` | RC-10, RC-23, RC-25 | Facade `TuiApp` constructors call them. | FC-13 |
| Real terminal (`TerminalIO`, `Session`), core `TuiApp` behaviour (exit codes, shutdown hook) | RC-20, RC-21, RC-24 | Facade `Tui.run` and `TuiApp` on a real terminal. | FC-12, FC-13 |
| Write the bound ref BEFORE `onInput` (`Dispatch.scala:143-146`) | A3 note in `inventory-runner.md` section 9 | One cell stays the source of truth. | FC-9 |
| kyo-ui as a dependency of core | RC-16 | zio and cats get kyo-ui through core. | all |
| ptycheck kit and launchers | RC-31, OS-20 | Facade PTY rows. | FC-33 |

`private[tui4s]` seams of the core are public in the bytecode, so MiMa checks them (RC-36).

### 5.2 PR split and order (decision FC-D7)

Non-destructive: new branches only. `feat/zio` and `feat/cats` stay unchanged as reference (they have no PR). No
force push. The files of `feat/zio` / `feat/cats` that carry come with `git checkout origin/feat/zio -- <path>` on
the new branch, and the commit message names the source commit (old-stack option A).

| Option | Shape | For | Against |
| --- | --- | --- | --- |
| A. Three PRs, one stack (RECOMMENDED) | (gen) `feat/codegen` on top of (c); (zio) `feat/zio-kyoui` on (gen); (cats) `feat/cats-kyoui` on (zio). | Linear: no conflict in shared files (`.scalafmt.conf`, the `check` alias, `examples/ptycheck/checklist.exp`, `codegen` targets). Each diff is small to review. | Cats review waits for the ZIO PR. |
| B. (gen) from `chore/cleanup`, in parallel with core (a) | (gen) has no core dependency. | Earlier review of the generator. | (zio) then needs two bases: a merge commit of (gen) and (c) into its branch. |
| C. zio and cats as siblings on (gen) | Parallel reviews. | Both add PTY rows and drift targets: a conflict at merge (as `feat/zio` / `feat/cats`, OS-20). |
| D. Two PRs per facade (mirror + runtime + kit after (a); `Tui` + `TuiApp` + examples after (c)) | Earlier start. | Five or six facade PRs; the first half has no real terminal. |

Gates: each PR is green with `sbt check` before review (user rule); (zio) and (cats) also pass the CI `terminal`
job with their PTY rows.

### 5.3 Work order and agents (user limits: at most 4 agents, at most 2 that build)

1. While core (a) is in review: implement (gen) (1 building agent). It needs only the build files of PR 1.
2. After (c) is green: (zio) implement (1 building agent), then 3 reviewers + 3 skeptics in two waves (no build,
   at most 4 at once), then 1 fixer (builds). The reusable one-PR workflow exists on the desktop machine (HANDOFF "Facts the next session
   must keep": `tui4s-impl-pr` script).
3. (cats) the same, after (zio) is green. It can start its implementation while (zio) is in review (2 building
   agents in total).
4. Announce a run of more than 10 agents first. One workflow at a time.

---

## 6. Items

Size: S < 150 lines, M 150-500, L > 500 (hand-written main + test lines; generated lines in brackets).

| Id | What | Action | Target | Size |
| --- | --- | --- | --- | --- |
| FC-1 | Codegen sub-project (port `gen/`) | adapt | `codegen/` | M |
| FC-2 | Class path and version as task inputs | adapt | `codegen/.../KyoUiApi.scala` | S |
| FC-3 | Both ecosystems, full set, stable names, qualifier | adapt | `codegen/.../Generate.scala` | S |
| FC-4 | Fail-closed generator | new | `codegen/` | S |
| FC-5 | Format exclude and warning rules | new | `.scalafmt.conf`, `build.sbt` | S |
| FC-6 | `mirrors` alias, `mirrorsCheck` in `check` | new | `build.sbt` | S |
| FC-7 | Generator tests | new | `codegen/src/test` | S-M |
| FC-8 | kyo-ui bump procedure | new (docs) | `AGENTS.md`, `codegen/README.md` | S |
| FC-9 | ZIO state and runtime (`SignalRef`, `Bridge`, `useSignal`, `useStream`) | keep | `modules/zio` | S |
| FC-10 | Cats state and runtime (`KyoSignallingRef`, ref type) | adapt | `modules/cats` | S |
| FC-11 | Vendored bridges and their suites | keep | `modules/{zio,cats}/.../internal` | M |
| FC-12 | `Tui` entry points | adapt | `modules/{zio,cats}/.../Tui.scala` | M |
| FC-13 | `TuiApp` main classes | adapt | `modules/{zio,cats}/.../TuiApp.scala` | S |
| FC-14 | Facade test kits | adapt | `modules/{zio,cats}/.../testkit/` | M |
| FC-15 | Re-export core Kyo-free types | new | `modules/{zio,cats}/.../exports.scala` | S |
| FC-16 | Package layout | decide | both modules, generator | S |
| FC-17 | Runner-mounted facade components | new | `Hooks.scala` (both) + core key hook | M |
| FC-18 | Drop the HTML stand-in | drop | prototype only | S |
| FC-19 | ZIO tests | adapt | `modules/zio/src/test` | L |
| FC-20 | Cats tests | adapt | `modules/cats/src/test` | L |
| FC-21 | ZIO build file | adapt | `modules/zio/build.sbt` | S |
| FC-22 | Cats build file | adapt | `modules/cats/build.sbt` | S |
| FC-23 | Generated ZIO mirror | generate | `modules/zio/src/main/generated` | S (+1120) |
| FC-24 | Generated Cats mirror | generate | `modules/cats/src/main/generated` | S (+1120) |
| FC-25 | Mirror scope (elements) | decide | generator | S |
| FC-26 | Reactive template completeness | adapt | generator template | S |
| FC-27 | ZIO output during the run | new | `modules/zio/.../Tui.scala` | S |
| FC-28 | Compatibility notes | keep | `compat.sbt`, docs | S |
| FC-29 | SurfaceLeaks CI check | adapt | neutral folder + both builds | S |
| FC-30 | Lift-trap guards (generated spec, canary, raw guards) | new | generator + tests | S-M |
| FC-31 | Data-type leaks (Q1) | decide | generator | S-M |
| FC-32 | Drag and drop (Q2) | decide | generator exclusion list | S |
| FC-33 | Facade examples and PTY rows | adapt | `examples/`, `examples/ptycheck` | M |
| FC-34 | Long handlers and TestClock | decide / docs | docs, kit, core | S |
| FC-35 | Name-clash tests | new | both test folders | S |
| FC-36 | `changes` interrupt stress test | new | `modules/zio/src/test` | S |
| FC-37 | AGENTS.md and module docs | adapt | `AGENTS.md`, Scaladoc | S |
| FC-38 | PR objects and order | decide | GitHub | S |

The structured result of this task holds the full rows (source, risk, dependencies).

---

## 7. Decisions needed (options, tradeoffs, recommendation)

- FC-D1 Package names: A flat `tui4s.interop.zio` / `.cats` (DECISIONS round 4; one import) (RECOMMENDED),
  B `....ui` (prototype), C split (two imports). See 2.1.
- FC-D2 Kyo data types through the aliases (FR Q1): (a) allow and document, with the checked-in leak list (S);
  (b) opaque EVENT types (`MouseEvent`, `KeyboardEvent`, `WheelEvent`) with generated accessors that return Scala
  types (`targetId: Option[String]`), keep the other aliases with a short allow-list (S-M; an extension cannot
  shadow a member, FR F2, so the event types must be opaque); (c) mirror every data type (L; a conversion per event).
  RECOMMENDED: (b). It removes the leak that handlers meet most (`targetId`), at a small cost. `Keyboard.charValue`,
  `Style.props`/`find`/`without`, `Style.Color.hex`, `Length.resolveOrAuto`, `Href`/`ImgSrc.Absolute.url`,
  `Modifiers.derived$Schema` stay allowed and documented.
- FC-D3 Drag and drop (FR Q2): exclude all 9 names (RECOMMENDED: the runner has no mouse, core D9) or generate the
  full forms (aliases for `kyo.Drag.*`, a handler rule with a non-`Any` result, mirrors of `Drag.Source/Target`; M).
- FC-D4 Cats ref type (FR Q3): fs2 `SignallingRef` in the setters (known type, run-time failure) or a tui4s subtype
  `UiSignallingRef[F, A] extends SignallingRef[F, A]` that `useSignal` returns (compile-time check; it is still a
  `SignallingRef` for all fs2 code). RECOMMENDED: the subtype.
- FC-D5 Quit (IR Q1): ZIO `Tui.quit` (FiberRef) plus the explicit form; Cats explicit `quit: F[Unit]` only
  (RECOMMENDED: no global state; works for any `F`). Alternatives: an `IOLocal` quit for `IO` only (IR showed that
  Dispatcher fibers see the `IOLocal` of the start), or one JVM-wide current run (global state; breaks parallel
  headless tests).
- FC-D6 Mirror scope: all 52 HTML elements (RECOMMENDED: same as kyo-ui for Kyo users; the core logs unsupported
  elements, RC-14) or only the elements that the runner supports (smaller API; additive later). SVG stays out.
- FC-D7 PR split: option A of 5.2 (RECOMMENDED), B, C or D.
- FC-D8 ZIO handler result: `Any` (today), `Any` + a `NotGiven` guard for a nested ZIO (RECOMMENDED if a probe
  shows that overload resolution stays clear), or `Unit` (more `.unit` calls, for example `ZIO.when(...)`).
- FC-D9 Drift check in the `check` alias (RECOMMENDED: local = CI) or a separate CI step.
- FC-D10 Generated code format: exclude from scalafmt (RECOMMENDED) or format in the generator.
- FC-D11 Facade `Component.mount` (build-time state): drop (RECOMMENDED, one meaning) or keep next to the
  runner-mounted `Component`.
- Confirm ASSUMED A3 (ZIO `SignalRef`) and A4 (checked-in mirrors, codegen project, drift check).

## 8. Working assumptions (ASSUMED until the user confirms)

- A1 ASSUMED: the facade PRs come after the core PRs (a), (b), (c) and build on (c).
- A2 ASSUMED: the upstream draft (RC-34) may add one facade point: kyo-ui handler types that reject a plain `Any`
  (FR F1) would make the guard overloads simpler. Drafted only, never posted without the user.
- A3 ASSUMED: ZIO state = facade-owned `SignalRef[A]` on the one kyo cell (FR F4, F5).
- A4 ASSUMED: mirrors checked in, `codegen` sbt project, drift check in `sbt check` (section 1).
