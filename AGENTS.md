# tui4s: rules for agents and contributors

This file is for AI agents and for humans who work on tui4s. It uses ASD-STE100
(Simplified Technical English). Keep that style when you edit this file.

## What tui4s is

<!-- claude 2026-10-12: changed this section: tui4s is a terminal runner for kyo-ui (C1) -->
tui4s is a terminal runner for [kyo-ui](https://github.com/getkyo/kyo), the UI
library of [Kyo](https://getkyo.io), in Scala 3. A kyo-ui value describes the
screen. tui4s lays it out in terminal cells and paints it with
[jatatui](https://github.com/oyvindberg/jatatui), a Java port of ratatui that
replaces tui-scala. tui4s has no UI DSL of its own: the DSL is kyo-ui.

| Directory       | sbt id     | Artifact                         | UI DSL                                  | Release tag      | Published |
| --------------- | ---------- | -------------------------------- | --------------------------------------- | ---------------- | --------- |
| `modules/core`  | `core`     | `io.github.yuri4n::tui4s-core`   | kyo-ui                                  | `core-v<semver>` | yes       |
| `modules/zio`   | `zio`      | `io.github.yuri4n::tui4s-zio`    | a ZIO DSL over kyo-ui (planned)         | `zio-v<semver>`  | yes       |
| `modules/cats`  | `cats`     | `io.github.yuri4n::tui4s-cats`   | a Cats Effect DSL over kyo-ui (planned) | `cats-v<semver>` | yes       |
| `examples`      | `examples` | none                             | -                                       | none             | no        |
| `docs`          | none       | none (Nuxt Docus site)           | -                                       | none             | no        |

The core uses Kyo and kyo-ui (both at `Versions.kyo`). The `zio` and `cats`
modules will let ZIO and Cats Effect users write tui4s apps without Kyo types
in their code. Today the core renders a kyo-ui value to cells
(`tui4s.internal.Layout`, `tui4s.internal.Paint`) and follows its signals: a
`Signal`, a `Foreach` or a bound `SignalRef` drives a region of the screen,
and each change gives one frame (`tui4s.internal.Runner`). The headless test
kit `tui4s.testkit.Headless` runs an app without a terminal; a test writes the
app's `SignalRef`s and reads `testkit.Screen`. The keys, the components and
the real terminal come in the next pull requests.
<!-- claude 2026-10-12: changed these lines: signals drive the screen; the headless kit (C2) -->

## Toolchain

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

| Command                              | What it does                                                  |
| ------------------------------------ | ------------------------------------------------------------- |
| `sbt fmt`                            | Formats all Scala and sbt files.                              |
| `sbt check`                          | Does the sbt checks of CI: format, compile, test, compat.     |
| `bash scripts/version-order-test.sh` | Tests the version order of the release script (CI runs it).   |
| `sbt versions`                       | Shows the version of each module (released or snapshot).      |
| `sbt core/test`                      | Runs the tests of one module. The core tests use kyo-test.    |
| `sbt "examples/runMain <main>"`      | Runs an example in the real terminal (forked JVM).            |

<!-- claude 2026-10-09: changed: also run the version order test before each push -->
Run `sbt fmt`, `sbt check` and `bash scripts/version-order-test.sh` before each
push.

The stress probes (`tui4s.stress.*`) are ignored in `sbt check`. To run one,
set `-Dtui4s.stress=<rounds>` in the test JVM and run it alone on the machine:
`sbt 'set core / Test / javaOptions += "-Dtui4s.stress=150"' 'core/testOnly
tui4s.stress.LeakProbe'`. A test run under CPU contention sets
`-Dtui4s.load=1` the same way (the glitch test then allows 1 split frame).
<!-- claude 2026-10-12: created this paragraph (review R3-8, R3-2) -->

## Rules for code

- Do not write to stdout or stderr while a terminal UI runs. The output
  corrupts the screen. Send logs to a file.
- Do not put `com.olvind.tui` (tui-scala) on the classpath. Its `crossterm`
  jar has the same `tui.crossterm` package as the jatatui `crossterm` jar.
- Do all drawing on one fiber. jatatui buffers, cells and widget states are
  mutable and are not thread-safe.
- The native `read()` of crossterm blocks and an interrupt cannot stop it. To
  read input, call `poll(<short timeout>)` in a loop, then call `read()`.
- Tests must not need a real terminal. Render into a jatatui `TestBackend` or
  into a `Buffer`, or run the app on `tui4s.testkit.Headless`.
- The frame contract is SOFT (decision D-17 c): one frame per event with all
  of its consequences; 0 split frames with no load, and at most 1 per 10 000
  under load (Kyo 1.0.0-RC7 can lose a wakeup in `Signal.combineLatest`, and
  a region on `combineLatest`, `zip`, `switchMap` or `combineLatestAll` still
  runs one `Async.race` per change inside Kyo; the watcher loop of the runner
  removes only its own race: issue #8). The glitch test (AppsTest) asserts 0
  split frames in 500 events and prints the count; a run under CPU contention
  sets `-Dtui4s.load=1` in the test JVM, which allows 1. One event is one
  write. The runner cannot tell a consequence of the active event from an
  independent write that lands while it settles (a Kyo signal carries no
  cause), so the writes that land inside one settle window share one frame:
  a frame is a batch, and `FrameStats.updates` counts its region values. The
  glitch test writes one ref at a time and waits for its frame; two writes
  from a test fiber with no wait between them give one frame or two.
  <!-- claude 2026-10-12: changed this sentence (PR #11 review: Codex, Runner.scala:394) -->
  The runner settles on the JVM-wide Kyo scheduler, so core test suites run one
  at a time: a suite that runs the runner extends `RunnerSuite` (sequential
  leaves), and sbt runs one test JVM at a time. A test that writes a
  `SignalRef` and reads the screen waits with `Headless.awaitScreen`; the kit
  itself never waits unless a method's name says so. The runner posts the
  stats of a frame after its flush: a test that counts frames after
  `awaitScreen` waits for them with `RunnerSuite.framesUntil`.
  <!-- claude 2026-10-12: created this item (D-17 c, RC-19); changed: the races inside Kyo, the glitch assertion and the load flag, one event is one write, framesUntil (review R2-6, R3-2, K2-2, R2-3) -->
- A `Reactive` region whose UI holds a closure (a handler) or a nested signal
  never compares equal to its last render. The verify pass and the repair
  tick (every 1 s) render it again: the frame has `repaired > 0` and, when
  the cells did not change, `flushed == 0`; the terminal gets no bytes. Kyo's
  `Signal.observe` repair timer does the same. A test that counts frames
  turns the tick off (`Knobs.let(Knobs(repairInterval = 1.hour))`) or drains
  these frames first.
  <!-- claude 2026-10-12: created this item (review R2-5) -->
- The test JVM sets the tui4s scheduler values (`modules/core/build.sbt`,
  `Test / javaOptions`): `kyo.scheduler.cycleIntervalNs=10000000`,
  `kyo.scheduler.blockingMonitorIntervalNs=20000000` and
  `kyo.scheduler.regulator.concurrencyCollectIntervalMs=100`. Without them the
  runner gives more split frames under load (spike S1). `TuiApp`, in a later
  pull request, sets the same values for a real app
  (`tui4s.spi.SchedulerDefaults`). The settle canary (SettleTest) needs a
  second Kyo worker: on a 2-CPU JVM under load the scheduler keeps one, so
  the canary cancels itself there (the report says `cancelled`, not passed).
  <!-- claude 2026-10-12: created this item (D-17 c); changed: the canary on 2 CPUs -->
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
  <!-- claude 2026-10-13: created this item (C3, RR N9) -->
- The strict flags do not see everything at the Java boundary (spike S6):
  `-Wnonunit-statement` does not see a Java call in statement position;
  `-Wvalue-discard` sees a Java result only in a `Unit` position;
  `-Yexplicit-nulls` gives flexible types to Java results, so the compiler does
  not force a null check. Check for null where the Java source, the Javadoc or
  the JNI contract allows null, mainly for the crossterm records.
  <!-- claude 2026-10-12: created this item (S6 "Proposed AGENTS.md rule for C1") -->

## Dependencies and versions

- `project/Versions.scala` holds the versions that more than one module uses
  (Scala, Kyo, jatatui, munit, tasty-query). tasty-query is there because the
  core tests use it now and the zio and cats coverage tests will use it later.
  <!-- claude 2026-10-12: changed: tasty-query named, with the reason (review R1-3, R2-5) -->
- Each published module watches its own `modules/<m>` directory, the root
  `build.sbt` and the `project` directory. A change in the root `build.sbt` or
  in `project` marks all modules as unreleased.
  <!-- claude 2026-10-09: changed the watched files: build.sbt and project/ too -->
- A version that only one module uses goes in `modules/<m>/build.sbt`.
- Never set `version` or `publishTo` in a build file. `project/ModuleVersioning.scala`
  and sbt-ci-release set them.

## Compatibility

Each module has `modules/<m>/compat.sbt` with `versionPolicyIntention`. The
rules are early-semver:

- `Compatibility.BinaryCompatible`: the next release can be a patch bump
  (`0.y.z` to `0.y.z+1`).
- `Compatibility.None`: the next release must bump the minor version (`0.y` to
  `0.y+1`).

The package `tui4s.internal` is sealed: each of its top-level definitions is
`private[tui4s]`, so users and Scaladoc do not see it (the test kit and the
`zio` and `cats` modules are inside `tui4s` and keep the access).
`PublicApiTest` checks the seal, and also that no public signature of `tui4s`,
`tui4s.spi` or `tui4s.testkit` names an internal type; the compiler does not
check this. `private[tui4s]` is public in the bytecode: MiMa and
`versionPolicyCheck` see the internal types and the `private[tui4s]` seams, so
a change there can break binary compatibility as a change of the public API
does.
<!-- claude 2026-10-13: created this paragraph (C3, gate 1 item 1, RC-36) -->

If a pull request breaks binary compatibility, set the intention of that module
to `Compatibility.None` in the same pull request. After the release, set it back
to `Compatibility.BinaryCompatible`. CI runs `versionPolicyCheck` on each pull
request, and the release runs `<m>/versionCheck`.

## Releasing

Each module has its own version and its own tag. A tag push publishes only that
module to Maven Central and creates its GitHub release (see
`.github/workflows/release.yml`). There is no other manual step.

1. Merge to `main` and wait until CI is green.
2. Do a dry run: `scripts/release.sh <module> <version> --dry-run`.
3. Release: `scripts/release.sh <module> <version>`. The script checks the
   module, asks for confirmation, creates the tag `<module>-v<version>`, and
   pushes only that tag.

<!-- claude 2026-10-09: added the accepted version forms and their order -->
A version is `x.y.z` or `x.y.z-<q><n>`: `<q>` is `alpha`, `beta`, `M` or `RC`,
and `<n>` is 1 or more (for example `0.2.0-RC1`). The numbers have no leading
zeros. The script orders versions as Maven does: it compares `x`, `y` and `z`
as numbers. For the same `x.y.z`, `alpha` < `beta` < `M` < `RC` < no `<q>`, and
for the same `<q>`, the larger `<n>` is greater (`RC10` > `RC2`).
<!-- claude 2026-10-09: added the coursier difference for M -->
coursier (and thus sbt) puts `M` below `alpha` and `beta`. Thus, do not release
an `M` version after an `alpha` or `beta` of the same `x.y.z`.

If the script says that an inter-project dependency is unreleased, release that
module first (`core` before `zio` and `cats`). Wait until its release run is
complete, then try again. Push only one release tag at a time: GitHub ignores a push of more than
three tags.

**Agents:** a release tag publishes a public artifact to Maven Central, and you
cannot remove it. You can run `--dry-run` at any time. You must get approval
from a human in the chat before you run `scripts/release.sh` without
`--dry-run`. Never use `--yes` on your own decision.

One-time setup (human): sign in to central.sonatype.com with GitHub (this
verifies `io.github.yuri4n`), make a user token, make a GPG key, and put its
public key on a key server. Then add these secrets to the GitHub environment
`maven-central`: `PGP_SECRET`, `PGP_PASSPHRASE`, `SONATYPE_USERNAME`,
`SONATYPE_PASSWORD`. The environment accepts only the tags `core-v*`, `zio-v*`
and `cats-v*`.

| Error message                                          | Cause and fix                                                       |
| ------------------------------------------------------ | ------------------------------------------------------------------- |
| `SNAPSHOTs are not supported on the Central Portal`    | `sonaRelease` ran on the root project. Use `; project <m>; sonaRelease`. |
| `Not releasable: ...`                                  | The tree is dirty, the tag and version are different, or a dependency is unreleased. |
| `no credentials are found for central.sonatype.com`    | The `SONATYPE_*` variables are missing.                             |
