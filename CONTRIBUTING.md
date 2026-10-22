<!-- claude 2026-10-20: created (L6); changed: the marked blocks of README.md (review fix) -->
# Contributing to tui4s

<!-- claude 2026-10-20: changed: the intro: this file is the rulebook (ADR 0021) -->
This file is the rulebook of tui4s, for humans and for AI agents. Start with
the [quick path](#quick-path). The sections after it are the full rules.
[ARCHITECTURE.md](ARCHITECTURE.md) tells how the runner works.
[AGENTS.md](AGENTS.md) adds the rules for AI agents only. The three files use
ASD-STE100 (Simplified Technical English). Keep that style when you edit
them.

## Quick path

<!-- claude 2026-10-20: changed: the short form of this file is the quick path: its sections are subsections, with links to the full rules (ADR 0021) -->

### Set up

- JDK 25 (Kyo 1.0.0-RC7 needs it), sbt 1.13, Node.js 22 (the Scala.js tests
  and the docs site).
- Clone with the submodule: `git clone --recurse-submodules`, or run
  `git submodule update --init --recursive` in a checkout.
- With Nix: `nix develop` gives the tools (JDK 25, sbt, Node.js, `expect`,
  `python3`).

The full rules: [Toolchain](#toolchain).

### Check a change

<!-- claude 2026-10-20: changed: the release rules line; created: the changelog line (ADR 0020); changed: the link to the full rules (ADR 0021) -->
```sh
sbt fmt                                # format
sbt check                              # format, compile, lint, test, compatibility
bash scripts/version-order-test.sh     # the release rules: version order, modules, last tag
bash scripts/changelog-section-test.sh # the release notes from CHANGELOG.md
bash scripts/edge-check.sh             # the edge files and the scalafix suppressions
examples/ptycheck/run.sh               # the real-terminal checklist, in a pseudo-terminal
```

CI compiles with `-Werror`: run `tpolecatCiMode` in sbt, or set
`SBT_TPOLECAT_CI=1`, to see the same errors. Run the PTY kit after a change
to the terminal code or to the examples. Never start a terminal UI with
`sbt run`: use the start scripts (`sbt examples/launchers`, then
`examples/target/bin/<app>`). All the commands: [Daily commands](#daily-commands).

### Write docs

- Write the docs, the comments and the Scaladoc in ASD-STE100 (Simplified
  Technical English): short sentences, one instruction in each.
- A Scala block of a docs page (`docs/content`) or of `examples/README.md`,
  and a Scala block of `README.md` with the line
  `<!-- from: <path>#<region> -->` above it, is the text of a region of a
  compiled source: `MarkdownRegionsSuite` checks it. Add the region to the
  source first, then copy its text.
- The docs site: `pnpm install --frozen-lockfile && pnpm run build` in `docs`.

The full rules: [Docs](#docs).

### Record a change for the release notes

<!-- claude 2026-10-20: created: this section (ADR 0020); changed: the links to Releasing and to the versioning (ADR 0021) -->
`CHANGELOG.md` is the one source of the release notes. A pull request that
changes a published module adds a line under `## [Unreleased]`, in the
subsection `### tui4s-<module>` of that module. The maintainer releases from
the Actions form ([Releasing](#releasing)). The version of a module:
[Compatibility and versioning](#compatibility-and-versioning).

### Marks

A change by an AI agent carries a mark at the smallest unit that it changed:
`// claude <date>: created|changed: <what>` (an HTML comment in Markdown).
[AGENTS.md](AGENTS.md) has the full rule.

### Run the bench

`bench/run.sh` publishes the tui4s artifacts locally, builds the eight
stacks and measures them (`bench/README.md`). Run it alone on the machine.
The full rules: [Benchmarks](#benchmarks).

### Report a problem

Use the issue forms. A security problem goes to [SECURITY.md](SECURITY.md).
The planned features are issues: <https://github.com/yuri4n/tui4s/issues>.

### The full rules

[Toolchain](#toolchain), [Daily commands](#daily-commands),
[UI architecture and API design](#ui-architecture-and-api-design),
[Rules for code](#rules-for-code), [Purity](#purity), [Docs](#docs),
[Benchmarks](#benchmarks), [Dependencies and versions](#dependencies-and-versions),
[Branch and tag rules](#branch-and-tag-rules),
[Compatibility and versioning](#compatibility-and-versioning),
[Releasing](#releasing).

<!-- claude 2026-10-20: changed: the sections from "Toolchain" to "Releasing" moved here from AGENTS.md as they were; their marks stay (ADR 0021) -->

## Toolchain

- Keep quick onboarding available in every maintained repository. Provide a
  locked Nix development shell with the required JDK, sbt, and test tools.
  Use sbt-native-packager for runnable distributions where useful. A library
  remains a library; put its runnable demonstration in a separate project.
  This is a user preference, not a requirement to make every artifact an app.
- Start a new checkout with `git clone --recurse-submodules`, then
  `nix develop`. An existing checkout needs
  `git submodule update --init --recursive`. Keep the manual JDK and sbt
  setup documented too. Do not claim a shell or platform was tested without
  running the relevant check.
- Use **JDK 25**. Kyo 1.0.0-RC7 needs JDK 25 to compile and to run, because its
  macros load Kyo classes. Before you run sbt, make sure that `java -version`
  shows 25.
- Scala **3.9.0** and sbt **1.13.0**. Do not change them in only one module.
- Run only one sbt process in this repo at a time. Two processes share
  `target/` and fight for the sbt server lock.
- The compiler options come from sbt-tpolecat (`build.sbt`, "Compiler
  options"). On a developer machine the warnings stay warnings (`DevMode`). In
  CI the environment variable `SBT_TPOLECAT_CI` adds `-Werror`: a warning
  fails the build. To get the CI behaviour in a local sbt session, run
  `tpolecatCiMode` first, or set `SBT_TPOLECAT_CI=1`. Keep the code free of
  warnings.
  <!-- claude 2026-10-12: created this item (D-8: sbt-tpolecat replaces the hand-written flags) -->

## Daily commands

<!-- claude 2026-10-09: changed the sbt check row and added the version order test row -->
<!-- claude 2026-10-17: changed the sbt check row; added the scalafixAll and edge-check rows -->
<!-- claude 2026-10-20: changed: the release rules row; created: the changelog row (ADR 0020) -->

| Command                              | What it does                                                  |
| ------------------------------------ | ------------------------------------------------------------- |
| `sbt fmt`                            | Formats all Scala and sbt files.                              |
| `sbt check`                          | Does the sbt CI checks: format, compile, lint, test, compat.  |
| `sbt scalafixAll`                    | Organizes the imports and shows the DisableSyntax errors.     |
| `bash scripts/version-order-test.sh` | Tests the release rules (`scripts/release-lib.sh`): the version form and order, the modules, the last tag (CI runs it). |
| `bash scripts/changelog-section-test.sh` | Tests the release notes from `CHANGELOG.md` (`scripts/changelog-section.sh`; CI runs it). |
| `bash scripts/edge-check.sh`         | Checks the edge headers and the suppressions (CI runs it).    |
| `sbt versions`                       | Shows the version of each module (released or snapshot).      |
| `sbt runner/test`                    | Runs the suites that need only the runner (layout, paint, the oracles, the terminal path on a fake crossterm). They use kyo-test. |
| `sbt core/test`                      | Runs the integration suites (`modules/core`): the suites that run `Headless` or `TuiApp`. They use kyo-test and the test sources of the runner. |
| `sbt use/test`                       | Runs the tests of a cross-built module (`use`, `widgets`) on the JVM and on Scala.js (Node.js runs the JS tests). |
| `sbt useJVM/test useJS/compile`      | Runs the JVM tests of tui4s-use and compiles its Scala.js side. |
| `nix develop`                       | Enters the development shell with JDK 25, sbt, Node.js, and terminal test tools. |
| `sbt examples/Universal/stage`       | Stages native-packager launchers and runtime JARs in `examples/target/universal/stage`. |
| `sbt examples/Universal/packageBin`  | Creates the examples ZIP distribution under `examples/target/universal`. |
| `sbt examples/launchers`             | Stages the distribution and writes compatible `examples/target/bin/<package>` launchers plus the PTY classpath. |
| `examples/target/bin/<app>`          | Runs an example in the real terminal (its own JVM): `counter`, `counterkyo`, `twocounters`, `counterstyles`, `todo`, `dashboard`, `printing`, `forms`, `gallery`, `assistant` (`--fake`: no network). |
| `examples/ptycheck/run.sh`           | Runs the real-terminal checklist in a pseudo-terminal (CI job `terminal`). |
| `examples/target/bin/aot-train`      | Trains the JDK 25 AOT cache of each example in a pseudo-terminal (`bin/aot-train` of the distribution; about 9 s per app). The start scripts then use the caches (ADR 0015). |
| `scripts/native-image.sh <app>`      | Builds a GraalVM native binary of one example into `examples/target/native/<app>` (GraalVM for JDK 25; CI job `native`). `python3 scripts/native-smoke.py <binary>` checks the native Counter in a pseudo-terminal. |
| `bash scripts/web-build.sh`          | Links the web assistant (`assistantWeb/fullLinkJS`) and writes `dist/assistant` (index.html, main.js). With an sbt server, set `SBT="sbt --client"`. |
| `node scripts/web-smoke.mjs`         | Runs the web assistant from `dist/assistant` in headless Chromium (Playwright): a send, the streamed reply, Esc, the look switch. |
| `pnpm install --frozen-lockfile && pnpm run build` (in `docs`) | Builds the docs site into `docs/.output`, with `llms.txt`, `llms-full.txt` and `raw/<page>.md`. Set `NUXT_TELEMETRY_DISABLED=1`. The build fetches nothing from the network. `pnpm run preview` serves the result. |
| `bash scripts/docs-casts.sh`         | Builds the launchers and records the terminal casts of the docs site (`docs/public/casts/<app>.cast`), each app in a pseudo-terminal of its own (`examples/ptycheck/cast.py`). |
| `bash scripts/docs-demo.sh`          | Copies `dist/assistant` (run `scripts/web-build.sh` first) to `docs/public/demo/assistant`, the web demo of the docs site. The page opens it with `?demo`: only the fake provider, and a storage in memory. |
| `bench/run.sh`                       | Runs the bench (`bench/README.md`): publishes the tui4s artifacts locally, builds the eight stacks, measures them in a pseudo-terminal and writes the tables. `bench/run.sh ci` is the smoke of the CI job `bench`. |
<!-- claude 2026-10-19: created the rows of use/test and useJS/compile; changed: Node.js in the shell (L2a); gallery in the app list (L2b) -->
<!-- claude 2026-10-19: changed: the rows runner/test and core/test (D25) -->
<!-- claude 2026-10-19: changed: assistant in the app list (L3) -->
<!-- claude 2026-10-19: created the rows of the web build and the web smoke test (L4) -->
<!-- claude 2026-10-20: created the rows of the docs build, docs-casts.sh and docs-demo.sh (L5) -->
<!-- claude 2026-10-20: created the row of bench/run.sh (L5); created the rows of aot-train and native-image.sh (L5b) -->

A test suite lives in the lowest module whose main classpath it needs: a
suite that needs only the runner is in `modules/runner/src/test`; a suite
that runs `Headless` or `TuiApp` is in `modules/core/src/test` (the
composite depends on the three, and its tests use the test sources of the
runner). The runner and the core get the same test settings
(`runnerTestSettings` in `build.sbt`).
<!-- claude 2026-10-19: created this paragraph (D25) -->

<!-- claude 2026-10-13: changed (T1): the launchers and the PTY kit replace `sbt "examples/runMain <main>"` -->

<!-- claude 2026-10-09: changed: also run the version order test before each push -->
<!-- claude 2026-10-17: changed: also run the edge check before each push; `sbt scalafixAll` -->
<!-- claude 2026-10-20: changed: also run the changelog test before each push (ADR 0020) -->
Run `sbt fmt`, `sbt check`, `bash scripts/version-order-test.sh`,
`bash scripts/changelog-section-test.sh` and `bash scripts/edge-check.sh`
before each push. If the scalafix check of
`sbt check` fails on an import, run `sbt scalafixAll`. After a change to the
terminal code (`TerminalIO`, `Crossterm`, `Session`, `EventMapping`,
`OutputCapture`, `Tui.run`, `TuiApp`, the examples), also run
`examples/ptycheck/run.sh` (it needs `expect`, `python3` and JDK 25 in
`JAVA_HOME`, or `java` on `PATH` when `JAVA_HOME` is not set).
<!-- claude 2026-10-13: changed (T1): run the PTY kit after a terminal change -->
<!-- claude 2026-10-13: changed (T2): TuiApp; the JDK of the kit -->

Never start a terminal UI with `sbt run` or `sbt runMain`: sbt also reads the
keyboard, so keys are lost, and the forked JVM gets no terminal on stdin (the
app stops with `NotATerminal`). Use the start scripts. An agent never starts
a terminal UI in its own shell: only the PTY kit runs one. The start scripts
use `$JAVA_HOME/bin/java` (or `java` on `PATH`): it must be JDK 25. When the
environment sets `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` (a proxy setup),
the JVM prints a "Picked up ..." line into the terminal before the UI starts;
`run.sh` unsets them for the app.
<!-- claude 2026-10-13: created this paragraph (T1, OS-47 part) -->

The examples use sbt-native-packager's `JavaAppPackaging`. Keep the JVM flags,
all example launchers, program arguments, and the PTY `target/bin/classpath`
contract when changing packaging. `Universal/packageBin` distributes JARs and
scripts; it does not bundle a JDK. Validate both
the staged distribution and the existing launcher paths. Keep library modules
free of application packaging plugins.
The start scripts use the JDK 25 AOT cache of the app (`aot/<app>.aot`) only
when `bin/aot-train` made it with the same JDK (`aot/<app>.jdk` keeps the text
of `java -version`); else they start as before (`ExamplePackaging.aotDefines`,
ADR 0015). The JVM flags of the examples include `-XX:TieredStopAtLevel=1`
and `-XX:+UseSerialGC`. Run the PTY kit with and without the caches (CI does
both). tui4s-runner carries the native-image metadata
(`META-INF/native-image/io.github.yuri4n/tui4s-runner`: the crossterm
libraries and the isatty downcall). It covers the workloads of the examples;
keep it in step with a jatatui or Kyo upgrade, and run the CI job `native`.
<!-- claude 2026-10-20: changed (L5b): the AOT cache, the start flags, the native-image metadata -->

The stress probes (`tui4s.stress.*`) are ignored in `sbt check`. To run one,
set `-Dtui4s.stress=<rounds>` in the test JVM and run it alone on the machine:
`sbt 'set core / Test / javaOptions += "-Dtui4s.stress=150"' 'core/testOnly
tui4s.stress.LeakProbe'`. A test run under CPU contention sets
`-Dtui4s.load=1` the same way, or runs `TUI4S_LOAD=1 sbt ...` (the glitch
tests then allow 1 split frame). The frame-path probe `tui4s.stress.PerfProbe`
measures the pure layout and paint of a frame (no runner, no terminal) on a
dashboard, a list of 2,000 rows and a log, and prints the median and the 90th
percentile of each step in ms: `sbt 'set core / Test / javaOptions +=
"-Dtui4s.stress=30"' 'core/testOnly tui4s.stress.PerfProbe'`. Run it before
and after a change of the frame path, and give both numbers.
<!-- claude 2026-10-19: changed: the frame-path probe PerfProbe (PF-b) -->

## UI architecture and API design

- Audit the pinned Kyo and kyo-ui public APIs, documentation, and tests before
  adding an abstraction. Prefer their existing signals, bindings, callbacks,
  effects, and resource scopes. State the missing behavior that new code adds.
- Follow Vue component conventions when they improve the Scala API: typed
  props, owned or controlled state, explicit callbacks, and function slots.
  Keep Kyo's execution model. Do not reproduce JavaScript conventions that
  add no useful behavior to the typed Scala API.
- Use `SignalRef` for mutable reactive state and `Signal` for derived or
  read-only state. Use native `.value(ref)` and `.checked(ref)` bindings for
  inputs. They write the reference before calling the user event handler.
- Use typed Scala callbacks for component events. Use Kyo `Emit` when logic
  needs a separate event effect and handler. Handle that effect inside the
  callback execution before returning to the UI's `Async` event boundary.
  Do not add model or event wrappers only to rename existing Kyo APIs.
- A component owns local state by default. A controlled component reads the
  signal supplied by its parent and calls a typed change callback; the parent
  can accept or reject the request. Keep that supplied signal authoritative.
  Prefer typed props and function parameters for slots.
- Put reusable composables in `tui4s.use` (`modules/use`) and reusable
  widgets in `tui4s.widgets` (`modules/widgets`). These modules are plain
  kyo-ui code for the JVM and Scala.js, with no dependency on tui4s-core. A
  composable takes its lifetime from the `Scope` of its caller, never from a
  hidden global. A widget may use the composables; tui4s-widgets depends on
  tui4s-use. `tui4s.widgets.Tabs` uses public Kyo APIs only;
  `examples.widgets.Tabs` adapts it to tui4s component mounting. There is no
  separate UI core repository. tui4s owns terminal rendering, input, and its
  retained mount identity. Ordinary Kyo UI construction and terminal mounting
  have different state lifetimes; document the difference.
  <!-- claude 2026-10-19: changed: composables and widgets go in the modules use and widgets (L2a); Tabs moved to tui4s.widgets, widgets depends on use (L2b) -->
- A widget of tui4s-widgets takes typed props (named parameters with
  defaults), a `Model` for its value (`Local`: the widget owns it; `Bound`:
  the parent's `SignalRef`, written before `onChange`; `Controlled`: the
  parent's `Signal`, and `onChange` asks), and function slots. It takes its
  colours from the `--tui4s-*` custom properties (`Colors`), and it also
  shows its state with a mark (`›`, `[x]`, bold text). Its pure rules are in
  its companion, with module tests (kyo-test, on the JVM and on Node.js). The
  gallery example (`examples.gallery`) shows each widget: a new widget gets a
  place in a family of the gallery, a leaf in `GallerySuite`, and its family
  region in the README (`MarkdownRegionsSuite` checks it).
  <!-- claude 2026-10-19: created this item (L2b) -->
  <!-- claude 2026-10-20: changed: ReadmeSuite is now MarkdownRegionsSuite (L5) -->
- Use only Kyo's public APIs. Do not use a package name or reflection to reach
  private Kyo implementation members.
- An ordinary Scala class can group signals, derived values, and actions.
  Use Kyo `Env` and `Layer` for dependency provision when useful. Resolve or
  handle dependencies before a deferred UI callback runs; handling UI
  construction alone does not handle effects in a returned callback.
- A store groups related hooks and composables into one typed feature, not
  only state fields. Include its actions, derived values, subscriptions, and
  effects where useful. Review shared instance identity and the lifetime of
  the whole group. Compare native `Scope`, `Var`, and signal composition
  before choosing a store API; do not add a second effect runtime.
- The assistant (`modules/assistant`) is the blueprint of a real app: a pure
  domain with laws, services as traits with their interpreters in a `make`
  (one for each platform, and one in memory for the tests), one store for the
  app, the wiring with Kyo layers in the main, and errors as values (`Abort`
  with a sealed error type). Follow it for a new app. Its `js` part has the
  interpreters of the browser (`fetch`, the keys in `localStorage`), and the
  service `Surface` gives the platform differences of the UI (units, chords).
  <!-- claude 2026-10-19: created this item (L3) -->
  <!-- claude 2026-10-19: changed: the interpreters of the browser, Surface (L4) -->
- A shared store belongs to one app or provider. The same definition must
  return the same whole instance within that owner and run setup once.
  Component unmount must not destroy it; owner shutdown must clean up its
  effects. Separate apps, tests, and server requests must get separate groups.
- `vendor/pini4s` is an optional shared-state library. Its keyed references
  add explicit, application-owned sharing. Do not put component events,
  bindings, widgets, or renderer code in it. Local state and controlled
  components need no store library.
- Compare API alternatives with small executable spikes and independent
  reviews before making new abstractions. Check both component code and app
  code, including deferred callbacks. Do not choose contextual parameters
  or effects only because the existing code uses them.
- pini4s is a Git submodule. Run `git submodule update --init --recursive`
  after cloning. Run `sbt check` in `vendor/pini4s` when it changes, as well as
  the relevant tui4s checks.

## Rules for code

- Do not write to stdout or stderr while a terminal UI runs. The output
  corrupts the screen. Send logs to a file. `Tui.run` binds its own Kyo
  logger (`internal.TuiLog`, to `TuiOptions.logFile` or to memory) and
  captures `System.out` and `System.err` (`internal.OutputCapture`); what it
  kept is printed after the restore. A `TuiApp` installs the capture in its
  constructor, before the first use of `scala.Console`, so Scala's `println`
  is captured too. The main sources of the runner, the app and the test kit
  have no `System.err` and no `println` except the log sink, the output
  forwarders and the prints after the restore, each with a
  `// tui4s: allowed` comment:
  `grep -rnE 'System\.err|System\.out|println' modules/runner/src/main modules/app/src/main modules/testkit/src/main`
  shows only comment lines and lines with that marker.
  <!-- claude 2026-10-13: changed (T1): the run's logger, the capture, the marker rule -->
  <!-- claude 2026-10-13: changed (T2): TuiApp installs the capture -->
  <!-- claude 2026-10-19: changed: the main sources of the three modules (D25) -->
- Do not put `com.olvind.tui` (tui-scala) on the classpath. Its `crossterm`
  jar has the same `tui.crossterm` package as the jatatui `crossterm` jar.
- Do all drawing on one fiber. jatatui buffers, cells and widget states are
  mutable and are not thread-safe.
- The native `read()` of crossterm blocks and an interrupt cannot stop it. To
  read input, call `poll(<short timeout>)` in a loop, then call `read()`.
  Only the input thread of the session (`internal.InputThread`, a platform
  thread) reads the terminal; it posts messages to the runner's inbox and
  never draws.
- A run in the terminal needs the JVM flag
  `--enable-native-access=ALL-UNNAMED` (crossterm is a JNI library). Without
  it JDK 25 prints a four-line warning into the terminal before the UI starts
  (it stays above the prompt after the run); with
  `--illegal-native-access=deny` the run fails with
  `TuiError.NativeAccessDenied` before the terminal changes. A library that
  does not load for another reason is `TuiError.NativeLoadFailed`.
- The release of a run is one synchronous stack (`internal.ReleaseStack`, an
  edge file). Each acquire pushes its release in the same synchronous step,
  after the acquire: a push to a closed stack runs the release at once. `runAll`
  (also `Session.close`) runs the releases once, the last push first: the canvas
  closes first (it waits for a draw in progress, on the backend's monitor, and
  nothing is drawn after it), then the input thread stops (the inbox closes, the
  join waits at most two poll intervals plus 50 ms), then the terminal leaves
  its modes, the output is flushed, raw mode ends, the output capture stops
  (with the R3 warning), the log sink finishes, and last the shutdown hook is
  removed. A failing step does not stop the next steps; the first failure is
  printed after the restore. It runs from the end of the run or from the JVM
  shutdown hook, whichever comes first; a second call does nothing. The stack
  holds its monitor only while its state changes, never while a release runs:
  keep this lock order. It uses no Kyo scheduler: a JVM hook cannot run the
  finalizers of a Kyo `Scope` (Kyo 1.0.0-RC7 only starts a fiber for them).
  After a quit, `Tui.run` closes the `Scope` of the UI before the release: it
  interrupts the fibers that the UI started with `Fiber.init` and waits for
  them. A fiber that does not stop on an interrupt (a blocking call in
  `Sync.defer`) keeps the terminal in raw mode until it ends.
  <!-- claude 2026-10-13: changed (T1, review R2-2): the scope closes before the release -->
  <!-- claude 2026-10-19: changed (P3, A1): the release stack and its order -->
- The fibers that a component or a hook starts end with the run: the body of
  a hook component and `Component.onMount` (started by `internal.Mount`), and
  the fibers of `useEffect`, `useInterval`, `useHold` and `useFold` (they
  call `internal.Tui4sTrack`) join the live-fiber gauge of the run
  (`internal.FiberGauge`, one Kyo `AtomicRef`), and the end of the run
  interrupts them (`FiberGauge.close`), also a fiber of a hook at the top of
  the app, whose `Scope` closes later. A fiber that joins a closed gauge is
  interrupted at once. A new hook or a new mount that starts a fiber starts
  it with `FiberGauge.fork` or `Tui4sTrack.init`: the start and the add are
  one bracket, so an interrupt cannot land between them. A test checks the
  gauge after an interrupted run and after a quit (`ComponentTest`). The
  unmount of a component interrupts its fiber, so its `Scope` closes and its
  effects stop. A failure of the body of a hook component or of `onMount` is
  logged once through the run's Kyo logger (one line at error with the
  position: `Mount.logFailure`), and the run goes on.
  <!-- claude 2026-10-13: created this item (C4, RC-8) -->
  <!-- claude 2026-10-19: changed: the failure of a hook body or of onMount is logged -->
  <!-- claude 2026-10-19: changed (P2, A7): FiberGauge, the one bracket -->
- jatatui 0.31.0's JNI cuts a typed character outside the Basic Multilingual
  Plane (an emoji) to 16 bits: such a key arrives wrong. A bracketed paste
  keeps it (`internal.EventMapping` Scaladoc).
  <!-- claude 2026-10-13: created these three items and the input-thread sentence (T1) -->
- Tests must not need a real terminal. Render into a jatatui `TestBackend` or
  into a `Buffer`, or run the app on `tui4s.testkit.Headless`. A test of the
  terminal path uses a fake crossterm (`FakeCrossterm` and the run seam
  `Tui.runWith`); `NativeTerminalTest` loads the real library only when stdin
  is not a terminal. The real terminal is the job of the PTY kit
  (`examples/ptycheck`).
  <!-- claude 2026-10-13: changed (T1): the fake crossterm and the PTY kit -->
- The frame contract is SOFT (decision D-17 c): one frame per event with all
  of its consequences; 0 split frames with no load, and at most 1 per 10 000
  under load (Kyo 1.0.0-RC7 can lose a wakeup in `Signal.combineLatest`, and
  a region on `combineLatest`, `zip`, `switchMap` or `combineLatestAll` still
  runs one `Async.race` per change inside Kyo; the watcher loop of the runner
  removes only its own race: issue #8). The glitch tests (AppsTest: 500
  writes, and 500 keys) assert 0 split frames and print the count; a run
  under CPU contention sets `-Dtui4s.load=1` in the test JVM, which allows 1.
  CI sets the environment variable `TUI4S_LOAD=1` (`ci.yml`), and
  `runnerTestSettings` (`build.sbt`) gives the test JVM `-Dtui4s.load=1`: on a loaded
  2-CPU runner, 500 events give about a 5 percent chance of 1 split under the
  SOFT contract, and a real regression gives many. One event is one write
  (or one key). A key is one event: its handlers run inside the runner's step,
  before the settle, so its Input frame holds their writes and every region that
  derives from them; a key step also re-reads the regions whose watcher has not
  armed yet, as a resize does. The runner cannot tell a consequence of the
  active event from an independent write that lands while it settles (a Kyo
  signal carries no cause), so the writes that land inside one settle window
  share one frame: a frame is a batch, and `FrameStats.updates` counts its
  region values. The glitch test writes one ref at a time and waits for its
  frame; two writes from a test fiber with no wait between them give one frame
  or two.
  <!-- claude 2026-10-12: changed this sentence (PR #11 review: Codex, Runner.scala:394) -->
  <!-- claude 2026-10-19: changed: runnerTestSettings gives the load flag (D25) -->
  The runner settles on the JVM-wide Kyo scheduler, so core test suites run one
  at a time: a suite that runs the runner extends `RunnerSuite` (sequential
  leaves), and sbt runs one test JVM at a time. A test that writes a
  `SignalRef` and reads the screen waits with `Headless.awaitScreen`; the kit
  itself never waits unless a method's name says so. After the run has ended, a
  kit method that waits for a frame (a key method, `paste`, `resize`,
  `nextFrame`) fails at once; the key that ended the run still gets its frame.
  The runner posts the stats of a frame after its flush: a test that counts
  frames after `awaitScreen` waits for them with `RunnerSuite.framesUntil`.
  <!-- claude 2026-10-12: created this item (D-17 c, RC-19); changed: the races inside Kyo, the glitch assertion and the load flag, one event is one write, framesUntil (review R2-6, R3-2, K2-2, R2-3) -->
  <!-- claude 2026-10-13: changed: the glitch test by keys, the CI load flag TUI4S_LOAD (C3, gate 1 item 2) -->
  <!-- claude 2026-10-13: changed: rewrapped at 80 columns (review R1-7); the kit fails after the end of the run (review R1-8, R2-7) -->
- A `Reactive` region whose UI holds a closure (a handler) or a nested signal
  never compares equal to its last render. The verify pass and the repair
  tick (every 1 s) render it again: the frame has `repaired > 0` and, when
  the cells did not change, `flushed == 0`; the terminal gets no bytes. Kyo's
  `Signal.observe` repair timer does the same. A test that counts frames
  turns the tick off (`Knobs.let(Knobs(repairInterval = 1.hour))`) or drains
  these frames first. Such a render builds new component nodes: the mount of
  a component of the same identity (a hook component: the call site and the
  key; a `Component`: the state key) at the same position goes on, so its
  state stays and its body or `onMount` does not run again.
  <!-- claude 2026-10-12: created this item (review R2-5) -->
  <!-- claude 2026-10-13: changed (C4): the component mounts go on -->
- The test JVM sets the tui4s scheduler values (`runnerTestSettings` in
  `build.sbt`, `Test / javaOptions`): `kyo.scheduler.cycleIntervalNs=10000000`,
  `kyo.scheduler.blockingMonitorIntervalNs=20000000`,
  `kyo.scheduler.blockingMonitorMinIntervalNs=20000000` and
  `kyo.scheduler.regulator.concurrencyCollectIntervalMs=100`. Without them the
  runner gives more split frames under load (spike S1). Without
  `blockingMonitorMinIntervalNs`, Kyo measures its CPU-time counter for about
  90 ms when its scheduler starts (the reason for 20 ms is in the Scaladoc of
  `SchedulerDefaults`). The start scripts of the examples give a real app the
  same values as JVM flags (`examples / run / javaOptions` in `build.sbt`);
  `TuiApp` sets them in code (`tui4s.spi.SchedulerDefaults.applyIfUnset`,
  first in its constructor: Kyo reads them once, when its scheduler starts).
  An app with a main of its own gives them as JVM flags.
  <!-- claude 2026-10-13: changed (T1, D-17 c): the start scripts -->
  <!-- claude 2026-10-13: changed (T2, RC-25): TuiApp sets them in code -->
  <!-- claude 2026-10-19: changed (C2): blockingMonitorMinIntervalNs -->
  <!-- claude 2026-10-19: changed: runnerTestSettings (D25) -->
  The settle canary (SettleTest) needs a
  second Kyo worker: on a 2-CPU JVM under load the scheduler keeps one, so
  the canary cancels itself there (the report says `cancelled`, not passed).
  <!-- claude 2026-10-12: created this item (D-17 c); changed: the canary on 2 CPUs -->
- A run has one JVM shutdown hook, `tui4s-stop` (`internal.ReleaseStack.run`).
  It is added in the step that makes the stack, and its removal is the first
  step of the stack (it runs last). `Tui.run` runs the UI on a fiber of its own
  (the run fiber); the caller waits on a copy of its result, not on the run
  fiber, so an interrupt of the caller restores the terminal at once. When the
  JVM stops (SIGTERM, SIGINT, SIGHUP, `System.exit`), the hook records the
  stop (`internal.Hooks.stopping`), interrupts the run fiber with no wait,
  runs the stack, and then waits at most 2 s until the release is complete or
  the run fiber ended. When the end of the run and the hook race, both run the stack:
  the second call does nothing, and the wait of the hook covers the release by
  the other side. A closed inbox is a quit. A `TuiApp` then prints nothing in
  `main` and does not run `onExit` (`internal.Hooks.shuttingDown`), so no output
  races with the exit of the JVM. A JVM stop that starts after the removal of
  the hook and before `main` reads `shuttingDown` is not seen; the terminal is
  restored by then. `TuiAppTest` models the hook with `FakeHooks`; the PTY kit
  checks the real signals.
  <!-- claude 2026-10-13: created this item (T2, RC-24, D-24 a) -->
  <!-- claude 2026-10-19: changed (P3, A2): one hook per run, the hook steps -->
  <!-- claude 2026-10-19: changed: SIGHUP (the PTY row hup) -->
- Never join the run fiber from an interruptible wait. A join makes the
  caller's interrupt wait for the run, and a run that waits for the caller is
  a deadlock. Poll `done` with a bounded wait instead (`Headless.awaitEnd`).
  <!-- claude 2026-10-12: created this item (OS-34) -->
- Give a Kyo handler value the type `Any < Async` (a function:
  `KeyboardEvent => Any < Async`), and put a side effect inside `Sync.defer`.
  Kyo lifts a plain value into `A < S`, so a handler with no type compiles,
  but its side effect is not in the effect: an untyped `val h = { n += 1 }`
  given to `onClick` runs once, when the UI is built, and never on the key;
  an untyped `e => e.key match { ... }` whose cases return effects runs none
  of them. The compiler gives no warning. `LiftTrapTest` shows the trap (the
  lift trap, RR O5); when Kyo closes it, that test fails.
  <!-- claude 2026-10-13: created this item (C3, RR O5) -->
- The runner calls no `private[kyo]` member of Kyo or kyo-ui (RR N9). The
  compiler rejects a direct call. Do not get around it with a source file in
  package `kyo` or with reflection. Read the attributes of an element through
  the public member `Element.attrs` (its type `Attrs` is `private[kyo]`, so
  the runner never names it). Where kyo-ui keeps a rule private (the key
  dispatch, the region walk), the runner copies its semantics (`Dispatch`,
  `Mount`), and the surface guard (`kyo-ui-surface.txt`) pins what it reads.
  For L1b it also pins `Attrs.focusRestore`, `Attrs.cssClasses`, the cases
  of `Position`, `Display`, `FlexWrap` and `Stylesheet.Entry`, and the CSS
  of `position` and `display` in kyo-ui's `CssStyleRenderer`.
  <!-- claude 2026-10-19: changed: this item of C3 (RR N9), with the surface of L1b -->
- The strict flags do not see everything at the Java boundary (spike S6):
  `-Wnonunit-statement` does not see a Java call in statement position;
  `-Wvalue-discard` sees a Java result only in a `Unit` position;
  `-Yexplicit-nulls` gives flexible types to Java results, so the compiler does
  not force a null check. Check for null where the Java source, the Javadoc or
  the JNI contract allows null, mainly for the crossterm records.
  <!-- claude 2026-10-12: created this item (S6 "Proposed AGENTS.md rule for C1") -->

## Purity

<!-- claude 2026-10-17: created this section (purity plan, phase P0) -->

- Write impure code only in an edge file, or on a line that has a
  `scalafix:ok` comment (see below). Impure code is a `var`, a
  `while` loop, `null`, a `| Null` type, `return`, `throw`, a cast, a JVM
  lock, atomic or thread, a `System` or `Runtime` call, and the unsafe Kyo
  APIs (`AllowUnsafe`, `Sync.Unsafe`, `.unsafe`).
- The painter is pure: `internal.Paint` gives the `internal.Plan` of a frame
  (its ops and its damaged row spans) and writes nothing. Only the edge file
  `internal/Canvas.scala` touches the jatatui buffers and the backend. Its two
  kernels are the only loops over cells: `Canvas.write` (the ops of a plan
  into the kept cells) and `Canvas.diff` (the damaged cells that differ from
  the shown cells). Each Scaladoc states its law, and `CanvasPropertyTest`
  checks it. `Canvas.diff`, `flush`, `resize`, `read`, `cells` and `close`
  take the backend's monitor; `write` does not. `Headless` reads the screen
  only through `Canvas.read` and `Canvas.cells`. Do not add a third kernel:
  put a new write into a new `Op`. The old painter is the permanent oracle in
  the test sources (`oracle/PaintOracle.scala`); do not change it.
  <!-- claude 2026-10-19: changed (P5): the kernels are in the edge file Canvas.scala; the plan, the monitor rule, the oracle -->
  <!-- claude 2026-10-19: changed (P4): Canvas.cells and the reads of Headless -->
- An edge file wraps a foreign API. Its line 1 is
  `// scalafix:off DisableSyntax` and its line 2 is
  `// EDGE: <the foreign API>`. `scripts/edge-check.sh` has the list of the
  edge files. All of them are in `modules/runner/src/main/scala/tui4s/internal`.
  <!-- claude 2026-10-19: changed: the edge files are in modules/runner (D25) -->
- The scalafix rule DisableSyntax (`.scalafix.conf`) finds impure code in the
  main sources. `sbt check` runs `scalafixAll --check`. The test sources use
  `.scalafix-test.conf`, which has only OrganizeImports.
- Outside the edge files, an impure site has a comment at the end of its
  line: `// scalafix:ok DisableSyntax.<id>; <phase>: <reason>`. `<phase>` is
  the phase that removes the site (see the phase key below), or `KEEP` for a
  site that stays (for example `System.exit` in `TuiApp`). For an expression
  on more than one line, put the comment on its last line: scalafix applies
  it to the full expression. When you remove the site, remove its comment. An
  impure site in an edge file has no comment. Do not write `scalafix:off`
  outside an edge file. `bash scripts/edge-check.sh` checks these rules.
  scalafmt does not wrap a trailing comment (`.scalafmt.conf`), so the
  comment stays on its line.
- The phases P1 to P6 landed on 2026-10-19: only `KEEP` sites remain, and
  `grep -rn 'scalafix:ok DisableSyntax' modules examples --include=*.scala | grep -v '; KEEP:'`
  prints nothing. A new impure site outside an edge file is a `KEEP` site
  with its reason. The phase key stays as the history of the comments (the
  purity plan of 2026-10-17; a tag such as `(A1)` in a reason names an
  amendment of the plan):
  - `P1`: delete dead code, change `null` to `Maybe` (to `Option` where no
    Kyo class can load), and change casts to matches.
  - `P1b`: a non-null `TuiError` cause (A4) and the final shapes of the
    public API (A5).
  - `P2`: the state of `Runner` and `Mount` on Kyo primitives, with the fiber
    gauge in one Kyo `AtomicRef` (A7).
  - `P3`: one synchronous release stack for the terminal (A1) and one stop
    hook for each run (A2).
  - `P4`: the testkit (`Headless`) and the examples. `Headless` reads the
    screen through `Canvas.read`, not through its own monitor.
  - `P5`: a pure painter and one `Canvas` seam, with the two kernels.
  - `P6`: the output sinks (`OutputCapture`, `LogSink`).
  - `KEEP`: the site stays.
  <!-- claude 2026-10-19: changed: P1 to P6 landed; only KEEP sites remain (L0) -->
- Use an ADT (an enum), not a Boolean, when a Boolean selects runtime
  behavior. A Boolean is correct for an option toggle and for a predicate.
- Property tests use kyo-test-prop (`PropertyTest[S]`, `Gen`, `Shrink`) under
  the kyo-test framework, with explicit `Gen` values. Do not use `Gen.derive`
  or a given `Gen` for a domain type.
- The skills `functional-design` and `scala-functional-design` are the rules
  of the rewrite. They are not in this repo. Install them from
  <https://github.com/yuri4n/functional-design-skills> (a Claude Code plugin
  marketplace), or copy its `skills/` directory into `~/.claude/skills`.
  <!-- claude 2026-10-19: created this item (D19: the skills leave the repo) -->

## Docs

<!-- claude 2026-10-20: created this section (L5); changed: no zio and cats modules (ADR 0014); examples/README.md has the rule of the pages -->
- The docs site is `docs/` (Nuxt Docus). Each page leads with a usage
  example (D17). Write the pages in ASD-STE100, short. Do not claim more than
  the code does: there is no ZIO or Cats Effect integration (issues #18 and
  #19), there is no mouse input, and Windows is experimental.
  Vercel builds the site (Root Directory `docs`) on a push to `main` that
  changes `docs/` since the last successful deployment (`docs/vercel.json`,
  `VERCEL_GIT_PREVIOUS_SHA`); the first deployment always builds.
  <!-- claude 2026-10-20: changed: the Vercel build rule -->
- Each Scala block of a page in `docs/content` is the text of a region of a
  source file. The line `<!-- from: <path>#<region> -->` is directly above
  the block, one for each block, as in `README.md`. `MarkdownRegionsSuite`
  (in `examples/test`) checks each block, and it fails on a Scala block with
  no such line. The same rule holds for `examples/README.md` (its blocks are
  regions of `examples.state`). A shell, text, YAML or `sbt` block has no line. To show new
  code, add a region to the source file (the two comment lines; no change of
  behaviour), then copy its text into the page.
- The casts (`docs/public/casts`) and the web demo
  (`docs/public/demo/assistant`) are generated files. After a change to an
  example that a cast shows, run `scripts/docs-casts.sh`. After a change to
  the assistant, run `scripts/web-build.sh` and `scripts/docs-demo.sh`.
  Commit the results.
- The logo is text: `assets/logo/tui4s.txt` is the logotype, and its "4"
  is the isomark (ADR 0022). Do not edit the logo files of `docs/public`
  and `assets/logo/isomark.txt` by hand: they are generated files, as the
  casts are. `node scripts/logo/build.mjs` writes them from the text, and
  also the logotype block of `README.md`. It needs Playwright with Chromium,
  as `scripts/web-smoke.mjs` does. After a change of the text, run it and
  commit the results. `node scripts/logo/build.mjs --check` (the CI step
  "Logo assets") fails when an output is not the output of the text. It
  needs only Node.js.
  <!-- claude 2026-10-20: created this item (ADR 0022) -->
- The build fetches nothing from the network: no font provider is on, and
  each icon is in the client bundle. Put a new icon in
  `icon.clientBundle.icons` of `docs/nuxt.config.ts`.

## Benchmarks

<!-- claude 2026-10-20: created this section (L5) -->
- The bench (`bench/`, decision D10) compares tui4s with seven other stacks on
  tasks with an exact specification (`bench/tasks/`). It has its own sbt build
  and its own CI job (`.github/workflows/bench.yml`). It does not change a
  published module. Its main results are compactness, expressiveness,
  correctness and resource management. The performance numbers are on a
  separate page (`bench/PERFORMANCE.md`). The CI job fails when a step fails
  that is not a known failure (`EXPECTED` in `bench/harness/bench.py`).
  <!-- claude 2026-10-20: changed: this item in short sentences, and the check of the CI job (review fixes) -->
- Every number states its machine (`bench/results/<machine>/machine.json`). An
  implementation in another stack gets a review by a person of that ecosystem
  before the numbers are published. Pending: ratatui, bubbletea, Textual, ink,
  layoutz, TamboUI and jatatui-plain.
- Run the bench only with `bench/run.sh`. The AWS run (`bench/infra/`) reads
  the credentials only from the AWS SDK default chain, and the operator always
  destroys it after the fetch.

## Dependencies and versions

- `project/Versions.scala` holds the versions that more than one module uses
  (Scala, Kyo, jatatui, tasty-query), and the munit version of the example
  tests (only the examples use munit; they have no build file of their own).
  tasty-query is there because the runner and core tests use it
  (`KyoUiSurfaceTest`, `PublicApiTest`).
  <!-- claude 2026-10-12: changed: tasty-query named, with the reason (review R1-3, R2-5) -->
  <!-- claude 2026-10-19: changed: the runner and core tests (D25); 2026-10-20: changed: no zio and cats coverage tests (ADR 0014); munit only for the examples (review fix) -->
- Each published module watches its own `modules/<m>` directory, the root
  `build.sbt` and the `project` directory. A change in the root `build.sbt` or
  in `project` marks all modules as unreleased.
  <!-- claude 2026-10-09: changed the watched files: build.sbt and project/ too -->
- A version that only one module uses goes in `modules/<m>/build.sbt`.
- Never set `version` or `publishTo` in a build file. `project/ModuleVersioning.scala`
  and sbt-ci-release set them.

## Branch and tag rules

<!-- claude 2026-10-09: created this section; 2026-10-20: changed (L6): the rules of the files of today, no approval -->
The files of `.github/rulesets/` are the GitHub rulesets of `main` and of the
release tags (see its `README.md`). They are active only after a human admin
imports them. Then:

- A change to `main` goes through a pull request, with a linear history
  (squash or rebase merge) and resolved threads. A pull request needs no
  approval. Force pushes to `main` and its deletion are blocked.
- The CI job `Format, compile, test, compatibility, packaging` must pass.
  Nobody can bypass this rule.
- You cannot delete or move a release tag (`runner-v*`, `app-v*`,
  `testkit-v*`, `core-v*`, `use-v*`, `widgets-v*`).
- The release workflow creates the release tags (see "Releasing"). A tag
  that a person pushes starts no release.
  <!-- claude 2026-10-20: created: this item (ADR 0020) -->

If you change the `name` of the CI job, change
`.github/rulesets/main-invariants.json` in the same pull request.

## Compatibility and versioning

<!-- claude 2026-10-20: changed: the title (it was "Compatibility") (ADR 0021) -->

Each module has `modules/<m>/compat.sbt` with `versionPolicyIntention`. The
rules are early-semver:

- `Compatibility.BinaryCompatible`: the next release can be a patch bump
  (`0.y.z` to `0.y.z+1`).
- `Compatibility.None`: the next release must bump the minor version (`0.y` to
  `0.y+1`).

The package `tui4s.internal` is sealed: each of its top-level definitions is
`private[tui4s]`, so users and Scaladoc do not see it (tui4s-app and
tui4s-testkit are other jars in the package `tui4s`, and they keep the
access: `private[tui4s]` works across jars).
`PublicApiTest` (in `modules/core`) checks the seal in the classes of the four
jars of the split (runner, app, testkit, core), and also that no public
signature of `tui4s`, `tui4s.spi` or `tui4s.testkit` names an internal type;
the compiler does not check this. `private[tui4s]` is public in the bytecode: MiMa and
`versionPolicyCheck` see the internal types and the `private[tui4s]` seams, so
a change there can break binary compatibility as a change of the public API
does.
<!-- claude 2026-10-13: created this paragraph (C3, gate 1 item 1, RC-36) -->
<!-- claude 2026-10-19: changed: the seal across the jars of the split (D25); 2026-10-20: changed: no zio and cats (ADR 0014) -->

If a pull request breaks binary compatibility, set the intention of that module
to `Compatibility.None` in the same pull request. After the release, set it back
to `Compatibility.BinaryCompatible`. CI runs `versionPolicyCheck` on each pull
request, and the release runs `<m>/versionCheck`.

<!-- claude 2026-10-20: created: this paragraph, the checks compare nothing before 0.1.1 (after the release of 0.1.0-RC1) -->
The checks compare a version only with the stable releases of its series.
sbt-version-policy drops the qualifier and the metadata of the version
(`0.1.0-RC1+1-<sha>-SNAPSHOT` becomes `0.1.0`), and a release candidate on
Maven Central sorts below `0.1.0`. Thus, while the versions are `0.1.0-RC<n>`,
`versionPolicyPreviousVersions` is empty: MiMa compares nothing, and
`versionPolicyCheck` and `versionCheck` pass with no comparison. The first
real comparison is `0.1.1` against `0.1.0`. Set the intention anyway, so the
record is right when the checks start. To see the binary changes of a module
since the last candidate, give MiMa that candidate by hand:
`sbt 'set runner / mimaPreviousArtifacts := Set("io.github.yuri4n" %% "tui4s-runner" % "0.1.0-RC1")' runner/mimaReportBinaryIssues`.

### How to choose the next version of a module

<!-- claude 2026-10-20: created: this procedure (ADR 0021); changed: step 4, what the checks stop and what they do not (review fix); 2026-10-20: changed: step 3, the next candidate (after the release of 0.1.0-RC1) -->
1. Read the section `## [Unreleased]` of `CHANGELOG.md`: the subsection
   `### tui4s-<module>` tells what changed in the module since its last
   release.
2. Read `versionPolicyIntention` in `modules/<m>/compat.sbt`.
   `Compatibility.None`: the next release bumps the minor version (`0.y.z`
   to `0.y+1.0`). `Compatibility.BinaryCompatible`: the next release can be
   a patch bump (`0.y.z` to `0.y.z+1`). Under early-semver at `0.x`, a patch
   can add features, and a minor can break the API.
3. Keep a qualifier (`RC<n>`, for example `0.1.0-RC1`) while Kyo and kyo-ui
   are release candidates. The next candidate keeps the number and bumps
   `<n>` (`0.1.0-RC2`), also after a change of the API: a candidate has no
   compatibility promise, and the checks compare it with nothing (see
   above).
4. The job `prepare` checks the number before a tag exists ("Releasing",
   step 3). The run stops when the version is not greater than the last tag
   of a module, or when it is a smaller bump than `versionPolicyIntention`
   allows (`<m>/versionCheck`). A module with no tag has no last release, so
   its first release has no version order check. Nothing stops a number that
   is too large or that has no qualifier, and you cannot remove a published
   version. Do a dry run, and read its table (the module, the tag, the last
   release) in the summary of the run before the real run.
5. A coordinated release of all the modules uses one version for all of
   them: select `all` in the form. A module that moves at its own pace
   releases alone, with its own version.

### After a release

<!-- claude 2026-10-20: created: this procedure (ADR 0021); 2026-10-20: changed: step 4, the version in the docs -->
Open one pull request after a release:

1. In `CHANGELOG.md`, check the heading of the released version,
   `## [<version>] - <YYYY-MM-DD>`. The release pull request renamed
   `## [Unreleased]` before the run ("Releasing", step 1). If the release
   ran on another day, change the date to the day of the release.
2. Start a new `## [Unreleased]` above that heading, if it is not there.
   It can stay empty: the next pull request that changes a module adds the
   subsection of that module.
3. Set `versionPolicyIntention` of each released module back to
   `Compatibility.BinaryCompatible` in `modules/<m>/compat.sbt`.
4. Put the released version in the dependency lines of the docs, where
   `grep -rn 'io.github.yuri4n" %%' README.md docs/content` finds them: the
   status line and the dependency lines of `README.md`, the installation
   page, and the composables and widgets pages of the docs site.

## Releasing

<!-- claude 2026-10-20: changed (ADR 0020): this section: the release starts from the Actions form on main, the checks run before the tag, one approval for all the modules; changed (review): the notes of a first release, the limit of a re-run, self-review off -->
Each module has its own version and its own tag `<module>-v<version>`. A
release starts from the form of the workflow Release
(`.github/workflows/release.yml`) on `main`. One run releases one module or
all the modules, with one approval.

1. In a pull request, rename `## [Unreleased]` of `CHANGELOG.md` to
   `## [<version>] - <YYYY-MM-DD>`. Keep the subsection
   `### tui4s-<module>` of each module of the release. Merge the pull
   request, and wait until CI is green on `main`.
2. Open **Actions > Release > Run workflow**. Select the branch `main`, the
   module (or `all`) and the version. Alternatively, run
   `scripts/release.sh <module|all> <version>`: it fills the same form with
   `gh`, and it shows the run.
3. The job `prepare` checks the release before a tag exists. The branch is
   `main`. The version has an accepted form and is greater than the last tag
   of each module. The new tags do not exist. `CHANGELOG.md` has the notes of
   each module. The CI job `Format, compile, test, compatibility, packaging`
   passed on the commit. For each module, `sbt "stageModule <m>"`
   (`assertReleasable`, then an unsigned publish into `target/sona-staging`)
   and `<m>/versionCheck` pass. The job tags the commit only in its own
   checkout, so that the dependency checks see the new versions.
4. Approve the deployment: GitHub asks the required reviewer of the
   environment `maven-central`. One approval covers all the modules of the
   run.
5. The job `publish` releases the modules in the order of the dependencies.
   For each module, it pushes the tag, publishes the module to Maven Central
   (sbt-ci-release, `sonaRelease`), and creates the GitHub release. The notes
   of the release are the subsection of the module in `CHANGELOG.md`, then,
   after a first release, the notes that GitHub makes since the previous
   release of the module.

A dry run (the box "Dry run" of the form, or `--dry-run`) does the job
`prepare` and stops: no tag, and no publish. Do a dry run before a release.

If the job `publish` fails, remove the cause, then click **Re-run failed
jobs**. Do not start a new run: its job `prepare` stops on the tags that
exist. The re-run keeps the tags on the commit. It skips a module that has
its GitHub release, and it does not publish again a version that is on
Maven Central. A tag with no GitHub release means: the publish of that module
is not complete. Central can show a new version on `repo1.maven.org` some
minutes after it accepts the upload. Wait for it before a re-run. A re-run
uses the workflow and the scripts of the commit of the run: a fix of
`release.yml` or of a script cannot help a re-run. Merge the fix, and release
the next version.

<!-- claude 2026-10-09: added the accepted version forms and their order -->
<!-- claude 2026-10-20: changed: the release checks order the versions (ADR 0020) -->
A version is `x.y.z` or `x.y.z-<q><n>`: `<q>` is `alpha`, `beta`, `M` or `RC`,
and `<n>` is 1 or more (for example `0.2.0-RC1`). The numbers have no leading
zeros. The release checks order versions as Maven does: they compare `x`, `y`
and `z` as numbers. For the same `x.y.z`, `alpha` < `beta` < `M` < `RC` < no
`<q>`, and for the same `<q>`, the larger `<n>` is greater (`RC10` > `RC2`).
<!-- claude 2026-10-09: added the coursier difference for M -->
coursier (and thus sbt) puts `M` below `alpha` and `beta`. Thus, do not release
an `M` version after an `alpha` or `beta` of the same `x.y.z`.

<!-- claude 2026-10-19: changed: `use` before `widgets` (L2b review) -->
<!-- claude 2026-10-19: changed: the order runner, app, testkit, core (D25); 2026-10-20: changed: no zio and cats (ADR 0014); changed: release all, the order in release-lib.sh (ADR 0020) -->
If `assertReleasable` says that an inter-project dependency is unreleased,
release that module first, or release `all`. The order is `runner`, then
`app` and `testkit`, then `core` (the composite of the three); `use` before
`widgets` (`release_all_modules` in `scripts/release-lib.sh`). A change in
`build.sbt` or in `project` makes all the modules unreleased: then release
`all`.

<!-- claude 2026-10-20: changed (ADR 0020): the gate is the required reviewer of the environment; no --yes -->
**Agents:** a release publishes a public artifact to Maven Central, and you
cannot remove it. You can start a dry run at any time:
`scripts/release.sh <module|all> <version> --dry-run`. A real release needs a
human: start it only when a human asks for it in the chat. The human gate is
the required reviewer of the environment `maven-central`: GitHub asks the
maintainer to approve the deployment, not a prompt of a script. An agent
never approves a deployment and never pushes a release tag.

<!-- claude 2026-10-20: changed (ADR 0020): the deployment policy is the branch main and a required reviewer; no tag patterns -->
One-time setup (human): sign in to central.sonatype.com with GitHub (this
verifies `io.github.yuri4n`), make a user token, make a GPG key, and put its
public key on a key server. Then, in **Settings > Environments >
`maven-central`**:

- Deployment branches and tags: `main` (the job `publish` runs on `main`).
  The tag patterns of the old release are not necessary.
- Required reviewers: the maintainer. Leave "Prevent self-review" off: the
  maintainer starts the run and approves it.
- Environment secrets: `PGP_SECRET`, `PGP_PASSPHRASE`, `SONATYPE_USERNAME`,
  `SONATYPE_PASSWORD`.

<!-- claude 2026-10-20: changed (ADR 0020): the messages of the form and of the two jobs -->

| Error message                                          | Cause and fix                                                       |
| ------------------------------------------------------ | ------------------------------------------------------------------- |
| `Release only from main`                               | The form ran on another branch. Select `main`.                      |
| `... is not greater than the last release ...`         | The version is not greater than the last tag of the module (Maven order). Use a greater version. |
| `The tag <tag> exists`                                 | That version is released, or a run stopped after its tag. Use the next version, or re-run the failed job of that run. |
| `changelog-section: ... has no heading` (or `no subsection`, `no text`) | `CHANGELOG.md` has no notes for the module and the version. Rename `## [Unreleased]` in a pull request. |
| `No successful check run ...`                          | CI did not pass on the commit, or it still runs. Wait for CI on `main`, or run it again. |
| `Not releasable: ...`                                  | The tag and the version are different, the tree is dirty, or a dependency is unreleased. Release the dependency first, or release `all`. |
| `The tag <tag> points at ...`                          | A tag of this version is on another commit. Do not move a release tag: release the next version. |
| `... is not allowed to deploy to maven-central ...`    | The deployment policy of the environment does not allow `main`. Add `main`. |
| `SNAPSHOTs are not supported on the Central Portal`    | `sonaRelease` ran on the root project. Use `; project <m>; sonaRelease`. |
| `no credentials are found for central.sonatype.com`    | The `SONATYPE_*` secrets are missing in the environment.           |
