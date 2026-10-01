# tui4s: rules for agents and contributors

This file is for AI agents and for humans who work on tui4s. It uses ASD-STE100
(Simplified Technical English). Keep that style when you edit this file.

## What tui4s is

tui4s is a library for declarative terminal user interfaces in Scala 3. It wraps
[jatatui](https://github.com/oyvindberg/jatatui), a Java port of ratatui that
replaces tui-scala.

| Directory       | sbt id     | Artifact                         | Release tag     | Published |
| --------------- | ---------- | -------------------------------- | --------------- | --------- |
| `modules/core`  | `core`     | `io.github.yuri4n::tui4s-core`   | `core-v<semver>` | yes      |
| `modules/zio`   | `zio`      | `io.github.yuri4n::tui4s-zio`    | `zio-v<semver>`  | yes      |
| `modules/cats`  | `cats`     | `io.github.yuri4n::tui4s-cats`   | `cats-v<semver>` | yes      |
| `examples`      | `examples` | none                             | none             | no       |
| `docs`          | none       | none (Nuxt Docus site)           | none             | no       |

The core uses [Kyo](https://getkyo.io). The `zio` and `cats` modules let ZIO and
Cats Effect users write tui4s apps without Kyo types in their code.

## Toolchain

- Use **JDK 25**. Kyo 1.0.0-RC7 needs JDK 25 to compile and to run, because its
  macros load Kyo classes. Before you run sbt, make sure that `java -version`
  shows 25.
- Scala **3.9.0** and sbt **1.13.0**. Do not change them in only one module.
- Run only one sbt process in this repo at a time. Two processes share
  `target/` and fight for the sbt server lock.

## Daily commands

| Command                        | What it does                                               |
| ------------------------------ | ---------------------------------------------------------- |
| `sbt fmt`                      | Formats all Scala and sbt files.                           |
| `sbt check`                    | Does the same checks as CI: format, compile, test, compat. |
| `sbt versions`                 | Shows the version of each module (released or snapshot).   |
| `sbt core/test`                | Runs the tests of one module.                              |
| `sbt "examples/runMain <main>"` | Runs an example in the real terminal (forked JVM).         |

Run `sbt fmt` and `sbt check` before each push.

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

## Dependencies and versions

- `project/Versions.scala` holds the versions that more than one module uses
  (Scala, Kyo, jatatui, munit). All published modules watch this file. A change
  in it marks all modules as unreleased.
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
