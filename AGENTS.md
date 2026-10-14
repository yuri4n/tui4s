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
in their code. Today the core renders a static kyo-ui value to cells
(`tui4s.internal.Layout`, `tui4s.internal.Paint`); the runner that follows
signals, the keys and the real terminal come in the next pull requests.

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
  into a `Buffer`.
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
