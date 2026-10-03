# SBT multi-module build + independent publishing (tui4s)

Date: 2026-09-30. Author: research subagent. Status: COMPLETE (all 6 topics answered; probe runs 1-10 done).
Legend: VERIFIED = seen in Maven Central metadata / source / compiled in probe. UNVERIFIED = memory or secondary source.

## 1. Latest versions

All rows VERIFIED with `curl https://repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml` on 2026-09-30
(release dates from the POM `Last-Modified` header where shown).

### Build tool

| Item | Latest | Notes |
|---|---|---|
| sbt 1.x | **1.13.0** (2026-08-20) | 1.12.15 is last 1.12.x. Repo currently pins 1.10.1; env.sh launcher is 1.11.7 (launcher reads `project/build.properties`, so any version works). VERIFIED |
| sbt 2.x | **2.0.9** (2026-09-14); 2.0.0 final was 2026-06-14; 2.1.0-M3 exists | sbt 2.0 is FINAL. VERIFIED |

sbt 2 viability: every plugin we need is published for sbt 2 (`_sbt2_3` suffix) - VERIFIED:
sbt-ci-release 1.12.1, sbt-dynver 5.1.1, sbt-scalafmt 2.6.2, sbt-scalafix 0.14.9, sbt-mima-plugin 1.2.1,
sbt-version-policy 3.3.0, sbt-tpolecat 0.5.7, sbt-pgp 2.3.2, sbt-git 3.0.0-RC1, sbt-debug-adapter 4.2.13, sbt-mdoc 2.9.2.
**Not** published for sbt 2: `sbt-typelevel` (no `sbt-typelevel_sbt2_3`) - VERIFIED.
Recommendation: stay on **sbt 1.13.0** for now (Metals/IntelliJ/BSP tooling and all docs/examples are 1.x-first;
sbt 2 changes build semantics - Scala 3 build definitions, built-in project matrix, cached tasks - UNVERIFIED details).
Migration later is cheap because every plugin below already has an sbt 2 build. (recommendation = opinion)

### sbt plugins (sbt 1.x artifact `_2.12_1.0`)

| Plugin | GAV | Latest |
|---|---|---|
| sbt-ci-release | `com.github.sbt:sbt-ci-release` | **1.12.1** (2026-08-20) VERIFIED |
| sbt-dynver | `com.github.sbt:sbt-dynver` | **5.1.1** (2025-06-25) VERIFIED (sbt-ci-release requires `DynVerPlugin` and `SbtPgp`, so both come transitively - VERIFIED in source) |
| sbt-scalafmt | `org.scalameta:sbt-scalafmt` | **2.6.2** VERIFIED |
| sbt-scalafix | `ch.epfl.scala:sbt-scalafix` | **0.14.9** (repo has 0.14.4) VERIFIED |
| sbt-mima-plugin | `com.typesafe:sbt-mima-plugin` | **1.2.1** (2026-09-18) VERIFIED |
| sbt-version-policy | `ch.epfl.scala:sbt-version-policy` | **3.3.0** VERIFIED |
| sbt-typelevel | `org.typelevel:sbt-typelevel` | **0.8.7** (sbt 1 only) VERIFIED |
| sbt-tpolecat | `org.typelevel:sbt-tpolecat` | **0.5.7** VERIFIED |
| sbt-pgp | `com.github.sbt:sbt-pgp` | 2.3.2 VERIFIED |
| sbt-mdoc | `org.scalameta:sbt-mdoc` | 2.9.2 VERIFIED |
| sbt-debug-adapter | `ch.epfl.scala:sbt-debug-adapter` | 4.2.13 (repo has 4.2.8) VERIFIED |

### Libraries (`_3` artifacts)

| Library | GAV | Latest | Built with Scala |
|---|---|---|---|
| Scala 3 (latest stable) | `org.scala-lang:scala3-library_3` | **3.9.0** (2026-08-26) | - |
| Scala 3 LTS | | **3.9.0 is the new LTS** (announced 2026-09-03); previous LTS 3.3.8 (2026-06-10), maintained 1 more year | - |
| Scala 3 next | | 3.10.0-RC3 | - |
| kyo-core / kyo-prelude / kyo-zio | `io.getkyo` | **1.0.0-RC7** (2026-09-28) | **3.9.0** |
| kyo-cats | `io.getkyo:kyo-cats_3` | **1.0.0-RC5 only** (no RC6/RC7!) | - |
| tui-scala | `com.olvind.tui:tui_3` | **0.0.10** (2026-03-26); deps: `cassowary_3`, `crossterm` (Java/JNI), scala-library 2.13.10 | 3.7.4 |
| zio / zio-streams / zio-test / zio-test-sbt | `dev.zio` | **2.1.26** | 3.3.7 |
| zio-interop-cats | `dev.zio` | 23.1.0.13 | - |
| cats-effect | `org.typelevel:cats-effect_3` | **3.7.1** | 3.3.4 |
| fs2-core | `co.fs2:fs2-core_3` | **3.14.0** | 3.3.8 |
| munit | `org.scalameta:munit_3` | **1.3.6** | 3.3.8 |
| munit-cats-effect | `org.typelevel:munit-cats-effect_3` | **2.2.1** | 3.3.8 |
| weaver-cats | **`org.typelevel`**`:weaver-cats_3` | **0.13.0** | 3.3.7 |
| weaver-cats (old) | `com.disneystreaming:weaver-cats_3` | 0.8.4 (last update 2024-01) - DEAD groupId | - |
| scalafmt core | `org.scalameta:scalafmt-core_2.13` / `_3` | **3.11.5** (use `version = 3.11.5` in .scalafmt.conf) | - |

Sources: Maven Central metadata (above); Scala 3.9 LTS: https://scala-lang.org/news/3.9/ (VERIFIED: "JDK 17 or newer is
required"; "artifacts built with Scala 3.9 cannot be consumed by Scala 3.3 projects"; 3.9 LTS maintained >= 3 years).

**Consequences for tui4s (VERIFIED from POMs):**
- kyo-core 1.0.0-RC7 depends on scala3-library 3.9.0, so **every tui4s module must compile with Scala 3.9.0** (a Scala 3
  library needs the consumer to be on >= the Scala version it was built with). Use `scalaVersion := "3.9.0"` in ThisBuild.
- JDK 17 minimum for Scala 3.9 (VERIFIED from announcement). CI: Temurin 21 (matches .work/env.sh), optionally 17 in matrix.
- The current repo uses `weaver` via the dead `com.disneystreaming` group - switch to `org.typelevel` 0.13.0, or use munit.
- kyo-cats lags kyo-core (RC5 vs RC7). If the cats module uses kyo-cats it must pin compatible kyo versions or avoid it
  (flag for the cats-integration researcher).

## 2. Publishing to Maven Central (Central Portal) in 2026

### Facts
- Legacy OSSRH endpoint was sunset **2025-06-30**; publish through the **Central Portal** (central.sonatype.com).
  sbt-ci-release README: "migrate to sbt 1.11.x or later and sbt-ci-release 1.11.0 or later". VERIFIED (README at v1.12.1 era, main branch).
- sbt >= 1.11 has **built-in** Central Portal support (VERIFIED in sbt v1.13.0 source, `main/src/main/scala/sbt/Defaults.scala` L272-282,
  `main/src/main/scala/sbt/internal/librarymanagement/Publishing.scala`, `Keys.scala` L570-574):
  - `stagingDirectory := (ThisBuild / baseDirectory).value / "target" / "sona-staging"` (one staging dir **per build**, not per project)
  - `localStaging := Some(Resolver.file("local-staging", stagingDirectory.value))`
  - `sonaBundle` zips the whole staging dir to `target/sona-bundle/bundle.zip` (`sonaBundle / aggregate := false`)
  - commands `sonaUpload` (PublishingType.UserManaged: upload, then click "Publish" in the portal UI) and
    `sonaRelease` (PublishingType.Automatic: upload + auto-publish after validation)
  - `sonaUploadRequestTimeout` default 10 minutes; `sonaDeploymentName` default `"$org:$name:$version:$uuid8"`
  - credentials: host `central.sonatype.com`; if no credential for that host is present, sbt adds one from env
    `SONATYPE_USERNAME` + `SONATYPE_PASSWORD` (`SysProp.sonatypeCredentalsEnv`). Locally: `~/.sbt/sonatype_central_credentials`
    with `host=central.sonatype.com / user= / password=` and `credentials += Credentials(Path.userHome / ".sbt" / "sonatype_central_credentials")`.
    The username/password are a **Portal user token** (https://central.sonatype.com/usertoken), not the login. VERIFIED (sbt source + README)
  - Snapshots go to `https://central.sonatype.com/repository/maven-snapshots/` (must be enabled for the namespace in the Portal).
- **GOTCHA (VERIFIED in Publishing.scala):** `sonaRelease`/`sonaUpload` read `version` of the **current project**
  (`Project.extract(s0).get(Keys.version)`) and fail with "SNAPSHOTs are not supported on the Central Portal" if it ends in
  `-SNAPSHOT`. With per-module versions, the root aggregate's version is not the released module's version, so run
  `; project <module>; sonaRelease` (or give root a non-snapshot version). See section 3 probe.
- sbt-ci-release 1.12.1 (VERIFIED, source `CiReleasePlugin.scala` identical on main and tag v1.12.1):
  - brings sbt-dynver + sbt-pgp; sets `ThisBuild / dynverSonatypeSnapshots := true`, `publishMavenStyle := true`,
    `Test / publishArtifact := false`, `sbtPluginPublishLegacyMavenStyle := false`, infers `scmInfo` from `git remote origin`.
  - per project: `version := (ThisBuild / version).value` (!) and `publishTo` = `ThisBuild / publishTo` if set, else
    central-snapshots for snapshots, else `localStaging`. **Do not set `publishTo`** in build.sbt; it picks Central Portal automatically.
    No setting "selects" the Central Portal any more: it is the only target (sbt-sonatype is no longer used).
  - `ci-release` command: does nothing unless `PGP_SECRET` is set; imports key (`echo $PGP_SECRET | base64 --decode | gpg --batch --import`);
    on tag push (`GITHUB_REF` starts with `refs/tags`): runs `$CI_CLEAN` (default `; clean`), then `$CI_RELEASE`
    (default `+publishSigned`, or derived from back-publish tag DSL), then `$CI_SONATYPE_RELEASE` (default **`sonaRelease`**).
    Not a tag: if `ThisBuild / version` ends with -SNAPSHOT runs `$CI_SNAPSHOT_RELEASE` (default `+publish`), else does nothing.
  - Env vars: `PGP_SECRET` (base64 of `gpg --armor --export-secret-keys $LONG_ID`), `PGP_PASSPHRASE`, `SONATYPE_USERNAME`,
    `SONATYPE_PASSWORD` (Portal token), optional `CI_RELEASE`, `CI_SNAPSHOT_RELEASE`, `CI_SONATYPE_RELEASE`, `CI_CLEAN`.
  - README: "the tag version MUST start with `v`" - only true for the default dynver prefix; with per-project
    `dynverTagPrefix` the tag is `core-v0.2.0` (section 3).
  - Tag DSL for back-publishing: `v1.2.3@3.x`, `v1.2.3@foo/publishSigned`, `#comment` (VERIFIED in source `backPubVersionToCommand`).
    It only applies to the ThisBuild version; not useful for per-module tags.
  - The GPG public key must be uploaded to a keyserver (keyserver.ubuntu.com / keys.openpgp.org) - UNVERIFIED (from memory of README GPG section).

### Namespace verification (io.github.yuri4n)
- `git remote origin` = https://github.com/yuri4n/tui4s.git (VERIFIED) so the natural groupId is **`io.github.yuri4n`**.
- Signing in to central.sonatype.com **with GitHub** auto-verifies `io.github.<user>` (sbt docs: "GitHub authentication:
  automatically associates io.github.<user_name> namespaces"). With email signup you verify by creating a temporary
  public repo named after the verification key - UNVERIFIED (memory of Sonatype docs).
- Enable "SNAPSHOTs" for the namespace in the Portal if we publish snapshots from main - UNVERIFIED (memory).

### Required POM metadata (ThisBuild)
`organization`, `homepage`, `licenses`, `developers`, `scmInfo` (auto by ci-release), `description` per module;
`pomIncludeRepository := { _ => false }`. Portal validation rejects missing sources/javadoc/signatures (sbt publishes
`-sources.jar` and `-javadoc.jar` by default; sbt-pgp adds `.asc`). VERIFIED for sbt doc text; validation rules UNVERIFIED.

## 3. Independent per-module versioning

### 3.0 Probe setup (VERIFIED, sbt 1.13.0, sbt-ci-release 1.12.1, sbt-mima-plugin 1.2.1, Scala 3.9.0)
Probe build: `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/sbt-probe/` (throwaway git repo, own `.git`).
Files: `build.sbt`, `project/ModuleVersioning.scala`, `project/plugins.sbt`, `modules/a`, `modules/b` (b `dependsOn` a).
Logs: `.work/scratch/probe-run*.log`.
Note: this Mac cannot reach repo1.maven.org over IPv6 (`curl -6` fails, `curl -4` works - VERIFIED). sbt downloads timed
out until `SBT_OPTS="$SBT_OPTS -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses=true"` was added; a few
timeouts still happened while the load average was 50-90 (other agents building), and a retry fixed them. Likely cause is
IPv6 (UNVERIFIED root cause). Consider adding the flags to .work/env.sh (local only, not the repo).

### 3.1 Probe findings
- **sbt-dynver has no per-project support out of the box**: every dynver setting is defined in `buildSettings`
  (ThisBuild) and `dynverInstance` reads the ThisBuild prefix, so `a / dynverTagPrefix := "a-v"` alone does nothing.
  README only documents `ThisBuild / dynverTagPrefix`. VERIFIED (source `DynVerPlugin.scala` v5.1.1).
- **Work-around that works:** re-apply `DynVerPlugin.buildSettings` at project scope with a project prefix
  (the README uses the same trick for `inConfig(Docker)`):
  `.settings(DynVerPlugin.buildSettings ++ Seq(dynverTagPrefix := "a-v", dynverSonatypeSnapshots := (ThisBuild / dynverSonatypeSnapshots).value))`.
  Must re-set `dynverSonatypeSnapshots` because the re-applied block resets it to false at project scope. VERIFIED by probe.
- Project `.settings(...)` win over sbt-ci-release's `version := (ThisBuild / version).value` projectSetting. VERIFIED.
- Probe run 1 (both tags on HEAD: `a-v0.1.0`, `b-v0.3.0`): `a/version = 0.1.0`, `b/version = 0.3.0`,
  root `version = 0.0.0+1-803ca607-SNAPSHOT` (no `v*` tags), `a/isSnapshot=false`, `b/isSnapshot=false`,
  `b/projectDependencies = io.github.yuri4n:probe-a:0.1.0`, `b/publishTo = local-staging (target/sona-staging)`. VERIFIED.
- `.sbt` files inside a subproject directory are still loaded by sbt 1.13.0 ("loading settings for project a from
  extra.sbt"), so `modules/<m>/version.sbt` (option b) works. VERIFIED.
- Probe run 2 (commit c2 changes only `modules/b`, tag `b-v0.3.1`; commit c3 changes only README):
  raw `a/dynver = 0.1.0+2-8132c00e-SNAPSHOT` but with the "module dir unchanged since its tag" rule `a/version = 0.1.0`;
  `b/version = 0.3.1` (raw dynver `0.3.1+1-8132c00e-SNAPSHOT`); `b/previousStableVersion = Some(0.3.1)`,
  `a/previousStableVersion = Some(0.1.0)`; `b/assertReleasable` passes with dep `probe-a:0.1.0`. VERIFIED.
- Accidental run with a dirty working tree: every module became `x.y.z+0-<sha>+<date>-SNAPSHOT` and
  `assertReleasable` failed. So a dirty tree can never be released. VERIFIED.
- Probe run 3 (commit c4 changes `a` and `b`, only `b-v0.4.0` tagged): `a/version = 0.1.0+3-917ead5e-SNAPSHOT`,
  `b/version = 0.4.0`, `b/projectDependencies = probe-a:0.1.0+3-917ead5e-SNAPSHOT`, and
  `b/assertReleasable` FAILS: "Not releasable: probe-a:0.1.0+3-917ead5e-SNAPSHOT (unreleased inter-project dependency)". VERIFIED.
  **Without this guard, `b/publishSigned` would publish a POM that depends on a snapshot of `a` that is not on Central.**
- Probe run 4 (tag `a-v0.2.0` on the same commit): `b/assertReleasable` passes ("probe-b:0.4.0; deps: probe-a:0.2.0");
  `b/publish` writes ONLY `probe-b_3/0.4.0/*` (jar, sources, javadoc, pom + md5/sha1) into `target/sona-staging`;
  the staged POM has `<dependency>io.github.yuri4n:probe-a_3:0.2.0</dependency>`. Then root `sonaRelease` FAILS:
  "SNAPSHOTs are not supported on the Central Portal" because the root version is `0.0.0+...-SNAPSHOT`. VERIFIED.
- Probe run 5: `; project b; sonaRelease` passes the snapshot check, builds `target/sona-bundle/bundle.zip` (618 kB)
  and stops at "no credentials are found for central.sonatype.com" (no network call). VERIFIED.
- Probe run 6 (**end-to-end `sbt ci-release`**, throwaway ed25519 key in an isolated `GNUPGHOME` under .work/scratch):
  env `PGP_SECRET`, `PGP_PASSPHRASE`, `GITHUB_REF=refs/tags/b-v0.4.0`,
  `CI_RELEASE="; b/assertReleasable; b/publishSigned"`, `CI_SONATYPE_RELEASE="; project b; sonaRelease"`.
  Result: key imported, "Tag push detected, publishing a stable release", `; clean` ran (a marker file in
  `target/sona-staging` was deleted, so stale artifacts of other modules cannot leak into the bundle), guard passed,
  only probe-b 0.4.0 was signed (`.asc`) and staged, `project b; sonaRelease` reached the credentials check. VERIFIED.
  The only untested step is the real HTTP upload to central.sonatype.com.
- Probe run 9: custom commands `stageModule <m>` / `releaseModule <m>` (`Command.single`, registered with
  `Global / commands ++= ...`) and `addCommandAlias("check", ...)` work. `RELEASE_VERSION=0.4.1 sbt "stageModule b"` fails with
  "probe-b: version 0.4.0 != RELEASE_VERSION 0.4.1"; with `0.4.0` it stages, and `releaseModule b` signs, stages and
  stops at the credentials check. VERIFIED.

- Probe run 10 (multi-path watch): `ModuleVersioning("a-v", Seq("modules/a", "project/Deps.scala"))`; commit c5 changes only
  `project/Deps.scala`. Result: `a/version = 0.2.0+1-fe7bf718-SNAPSHOT`, `b/version = 0.4.0` (b does not watch that file),
  `b/assertReleasable` FAILS on the unreleased `probe-a`. `b/versionPolicyPreviousVersions` for `0.4.0` is empty; for
  `a` (`0.2.0+1-...`) it is `0.2.0`. VERIFIED.

### 3.2 Options compared

| | (a) per-module tags + dynver per project | (b) `modules/<m>/version.sbt` per module | (c) lockstep single version |
|---|---|---|---|
| Independent cadence | yes | yes | no (every module released together) |
| Custom build code | ~60 lines `project/ModuleVersioning.scala` (probed) | small, plus a bump/commit/tag script (sbt-release style) | none (stock sbt-ci-release) |
| Version source of truth | git tags only | file in repo + tag | git tag `v*` |
| Release commits on main | none | 2 per release (set version, back to -SNAPSHOT) | none |
| POM of zio -> core version | core's computed version (released tag version when core is untouched) | whatever core's version.sbt says (often `-SNAPSHOT` => needs same guard) | same version, always consistent |
| User ergonomics | versions diverge: `tui4s-core 0.4.2` + `tui4s-zio 0.2.1` (need a compat table in docs) | same as (a) | one version for all artifacts (simplest for users) |
| Fits sbt-ci-release | yes, with `CI_RELEASE`/`CI_SONATYPE_RELEASE` env (probed end-to-end) | partially (ci-release wants dynver; must override `version`) | natively |
| Risk | git-describe edge cases, shared build-file changes not seen by the "unchanged" rule | forgotten bumps, merge conflicts on version files | releases of unchanged modules (harmless, noisy) |

Recommendation: **(a)**, because the user asked for independently deployable integrations and it keeps the repo free of
version bookkeeping. (c) is the fallback if the custom code is not wanted: it is what most Scala multi-module libraries
do (UNVERIFIED survey claim), and nothing in (a) blocks moving to (c) later (switch tag scheme to `v*`).

### 3.3 How zio's POM references core, and the guard
- Keep **`dependsOn(core)`** (not a binary `libraryDependencies` on published core). sbt writes the inter-project dependency
  into the POM with **core's current `version`** (VERIFIED: staged POM of b has `probe-a_3:0.2.0`).
- With plain dynver that version is a snapshot as soon as there is any commit after `core-v*` (VERIFIED run 2 raw dynver
  `0.1.0+2-...-SNAPSHOT`). Hence the **"module dir unchanged since its last tag => version = that tag" rule** in
  `ModuleVersioning` (VERIFIED run 2: `a/version = 0.1.0` while raw dynver was snapshot). So zio can be released alone
  whenever core has no unreleased changes.
- **Guard = `<m>/assertReleasable`** (VERIFIED runs 3/4/9): fails if the module itself is a snapshot, if the working tree is
  dirty, if any `projectDependencies` revision contains `-SNAPSHOT` or `+`, or if `RELEASE_VERSION` (from the tag) differs
  from the computed version. Run it first in `CI_RELEASE` and in `scripts/release.sh`.
  Fix when it fails: release core first (tag `core-vX` on the same or an earlier commit), then zio.
- Known gap: the "unchanged" rule diffs only `modules/<m>`. A change in shared build files (e.g. `project/Dependencies.scala`
  bumping Kyo for core) does not make core a snapshot. Mitigation: pass extra watched paths per module (e.g.
  `ModuleVersioning("core-v", Seq("modules/core", "project/Dependencies.scala"))`) - conservative but forces more core releases.
  Design choice for the implementer. Multi-path watching VERIFIED in probe run 10.
- Alternative considered and rejected: switching zio to a binary dependency on published core during release
  (`if (sys.env.contains("CORE_VERSION")) libraryDependencies += ... else dependsOn(core)`). Strongest guarantee (tests run
  against the real artifact) but two build shapes, IDE confusion, and it needs core to be synced to repo1 first (~minutes). UNVERIFIED.

### 3.4 Recommended build code (tested in probe, names adapted)
`project/ModuleVersioning.scala` = copy the probe file verbatim (`.work/scratch/sbt-probe/project/ModuleVersioning.scala`,
signature `apply(tagPrefix: String, paths: Seq[String])`, plus `releaseModule`/`stageModule` commands and
`assertReleasable`). Do NOT copy the probe's `versionPolicyPreviousVersions` override experiment (already removed). `build.sbt`:
```scala
import Dependencies.*

ThisBuild / organization := "io.github.yuri4n"
ThisBuild / homepage     := Some(url("https://github.com/yuri4n/tui4s"))
ThisBuild / licenses     := List(License.Apache2)          // confirm license with the user
ThisBuild / developers   := List(Developer("yuri4n", "<name>", "<public email>", url("https://github.com/yuri4n")))
ThisBuild / scalaVersion := "3.9.0"                         // kyo 1.0.0-RC7 is built with 3.9.0
ThisBuild / versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+\\+\\d+".r)

def module(id: String): Project =
  Project(id, file(s"modules/$id"))
    .settings(ModuleVersioning(tagPrefix = s"$id-v", paths = Seq(s"modules/$id")))
    .settings(name := s"tui4s-$id")

lazy val root = project.in(file("."))
  .aggregate(core, zio, cats, examples)
  .settings(name := "tui4s-root", publish / skip := true)   // also skips versionCheck/versionPolicyCheck (VERIFIED README)

lazy val core     = module("core").settings(libraryDependencies ++= coreDeps)
lazy val zio      = module("zio").dependsOn(core).settings(libraryDependencies ++= zioDeps)
lazy val cats     = module("cats").dependsOn(core).settings(libraryDependencies ++= catsDeps)
lazy val examples = project.in(file("examples")).dependsOn(core, zio, cats).settings(publish / skip := true)

Global / commands ++= Seq(ModuleVersioning.releaseModule, ModuleVersioning.stageModule)
addCommandAlias("fmt",      "; scalafmtAll; scalafmtSbt")
addCommandAlias("fmtCheck", "; scalafmtCheckAll; scalafmtSbtCheck")
addCommandAlias("check",    "; scalafmtCheckAll; scalafmtSbtCheck; Test/compile; test; versionPolicyCheck")
addCommandAlias("versions", "; show core/version; show zio/version; show cats/version")
```
`project/plugins.sbt`:
```scala
addSbtPlugin("com.github.sbt"  % "sbt-ci-release"     % "1.12.1") // + sbt-dynver 5.x + sbt-pgp
addSbtPlugin("ch.epfl.scala"   % "sbt-version-policy" % "3.3.0")  // + sbt-mima-plugin
addSbtPlugin("org.scalameta"   % "sbt-scalafmt"       % "2.6.2")
addSbtPlugin("ch.epfl.scala"   % "sbt-scalafix"       % "0.14.9")
addSbtPlugin("org.typelevel"   % "sbt-tpolecat"       % "0.5.7")  // optional; overlaps with manual -W flags
```
`project/build.properties`: `sbt.version=1.13.0`. Do not set `version` or `publishTo` anywhere.
Note: `zio`/`cats` as sbt ids are fine in build.sbt (no zio/cats code on the meta-build classpath). Artifacts:
`io.github.yuri4n::tui4s-core`, `::tui4s-zio`, `::tui4s-cats`.

## 4. GitHub Actions workflow

Drafts (YAML parsed OK with Ruby's YAML; `actionlint` not installed, so not linted):
`/Users/yuri4n/Developer/projects/tui4s/.work/scratch/ci-design/.github/workflows/ci.yml` and `release.yml`.

Action versions (VERIFIED via GitHub API `releases/latest`, 2026-09-30): actions/checkout v7.0.1, actions/setup-java v6.0.1,
sbt/setup-sbt v1.5.11, actions/setup-node v7.0.0, pnpm/action-setup v6.1.0 (has `package_json_file` input - VERIFIED
from its action.yml), coursier/cache-action v8.1.1, actions/upload-pages-artifact v5.0.0, actions/deploy-pages v5.0.1.
sbt-ci-release's own release.yml uses `actions/checkout@v7` + `fetch-depth: 0`, `actions/setup-java@v6` (temurin, `cache: sbt`),
`sbt/setup-sbt@v1`, `sbt ci-release` with the 4 secrets. VERIFIED.

Design points:
- `fetch-depth: 0` is required (dynver + version policy read tags). VERIFIED (ci-release template).
- Release trigger `on.push.tags: ["core-v*", "zio-v*", "cats-v*"]`; tag parsed with a bash regex (tested under bash:
  `core-v0.2.0 -> core 0.2.0`, `zio-v0.1.3-RC1 -> zio 0.1.3-RC1`, rejects `cats-v1.2`, `foo-v1.0.0`, `zio-v0.1.3+x`). VERIFIED.
- `CI_RELEASE="; <m>/assertReleasable; <m>/versionCheck; <m>/publishSigned"` and
  `CI_SONATYPE_RELEASE="; project <m>; sonaRelease"` - the same shape was run end-to-end in probe run 6. VERIFIED (minus upload).
- GitHub does not create push events when **more than three tags are pushed at once** (docs.github.com "Events that
  trigger workflows"; seen via search snippet - VERIFIED-secondary). Push release tags one at a time (release.sh does).
- Two tags on one commit (core + zio) give two runs and two Central deployments; order is not guaranteed, so zio may be
  visible a few minutes before core. release.sh pushes core first; wait for its run before tagging zio (recommended).
- Secrets live in a GitHub **environment** `maven-central` (optional required reviewers = manual approval gate).
- Snapshots from main are NOT in this design. If wanted later: a job on `push: branches: [main]` running `sbt ci-release`
  (default `CI_SNAPSHOT_RELEASE=+publish`) publishes modules whose version ends in -SNAPSHOT to
  central-snapshots; untouched modules have release versions and only go to the local staging dir (not uploaded).
  Requires enabling snapshots for the namespace in the Portal (UNVERIFIED).
- tui-scala's crossterm backend is JNI; tests that open a real terminal may fail on headless runners (UNVERIFIED; the
  core-design researcher should keep terminal I/O out of unit tests or use tui's test backend/buffer).
- Docs job: pnpm + Node 24 in `docs/`, `pnpm run generate` (Nuxt static output). Deployment of docs (GitHub Pages via
  upload-pages-artifact/deploy-pages) is left to the docs researcher.

### ci.yml
```yaml
name: CI

on:
  pull_request:
  push:
    branches: [main]

concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  sbt:
    name: Format, compile, test, compat
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0 # dynver + sbt-version-policy need all tags and history
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 21
          cache: sbt
      - uses: sbt/setup-sbt@v1
      # alias: scalafmtCheckAll; scalafmtSbtCheck; Test/compile; test; versionPolicyCheck
      - run: sbt -v check
      - name: Show module versions
        run: sbt -v versions

  docs:
    name: Docs (Nuxt Docus)
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: docs
    steps:
      - uses: actions/checkout@v7
      - uses: pnpm/action-setup@v6 # reads "packageManager" from docs/package.json
        with:
          package_json_file: docs/package.json
      - uses: actions/setup-node@v7
        with:
          node-version: 24
          cache: pnpm
          cache-dependency-path: docs/pnpm-lock.yaml
      - run: pnpm install --frozen-lockfile
      - run: pnpm run generate # nuxt generate -> static site in docs/.output/public
```

### release.yml
```yaml
name: Release

# One tag = one module release. Tag format: <module>-v<semver>, e.g. core-v0.2.0, zio-v0.1.3.
# Tags are created by scripts/release.sh. Note: GitHub creates no push event when more than
# three tags are pushed at once, so push release tags one by one.
on:
  push:
    tags: ["core-v*", "zio-v*", "cats-v*"]

concurrency:
  group: release-${{ github.ref_name }}
  cancel-in-progress: false

permissions:
  contents: write # gh release create

jobs:
  publish:
    runs-on: ubuntu-latest
    environment: maven-central # holds the secrets; add required reviewers for a manual gate
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0

      - name: Parse tag
        id: tag
        env:
          TAG: ${{ github.ref_name }}
        run: |
          if [[ ! "$TAG" =~ ^(core|zio|cats)-v([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?)$ ]]; then
            echo "::error::Tag '$TAG' does not match <core|zio|cats>-v<semver>"; exit 1
          fi
          echo "module=${BASH_REMATCH[1]}"  >> "$GITHUB_OUTPUT"
          echo "version=${BASH_REMATCH[2]}" >> "$GITHUB_OUTPUT"

      - name: Tag must point to a commit on main
        run: |
          git fetch --no-tags origin main
          git merge-base --is-ancestor "$GITHUB_SHA" origin/main \
            || { echo "::error::$GITHUB_REF_NAME is not on main"; exit 1; }

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 21
          cache: sbt
      - uses: sbt/setup-sbt@v1

      - name: Test the module
        env:
          MODULE: ${{ steps.tag.outputs.module }}
        run: sbt -v "$MODULE/test"

      - name: Publish to Maven Central
        env:
          MODULE: ${{ steps.tag.outputs.module }}
          RELEASE_VERSION: ${{ steps.tag.outputs.version }}
          # sbt-ci-release runs: $CI_CLEAN (default "; clean") ; $CI_RELEASE ; $CI_SONATYPE_RELEASE
          CI_RELEASE: "; ${{ steps.tag.outputs.module }}/assertReleasable; ${{ steps.tag.outputs.module }}/versionCheck; ${{ steps.tag.outputs.module }}/publishSigned"
          # sonaRelease must run with the module as current project (root version is a snapshot)
          CI_SONATYPE_RELEASE: "; project ${{ steps.tag.outputs.module }}; sonaRelease"
          PGP_PASSPHRASE: ${{ secrets.PGP_PASSPHRASE }}
          PGP_SECRET: ${{ secrets.PGP_SECRET }}
          SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
          SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
        run: sbt -v ci-release

      - name: GitHub release
        env:
          GH_TOKEN: ${{ github.token }}
          MODULE: ${{ steps.tag.outputs.module }}
          VERSION: ${{ steps.tag.outputs.version }}
        run: |
          PREV=$(git describe --tags --abbrev=0 --match "${MODULE}-v[0-9]*" "${GITHUB_SHA}^" 2>/dev/null || true)
          ARGS=(--title "tui4s-${MODULE} ${VERSION}" --generate-notes --verify-tag)
          [[ -n "$PREV" ]] && ARGS+=(--notes-start-tag "$PREV")
          [[ "$VERSION" == *-* ]] && ARGS+=(--prerelease)
          gh release create "$GITHUB_REF_NAME" "${ARGS[@]}"
```

## 5. Developer / agent ergonomics

### sbt commands (aliases VERIFIED as a mechanism in probe run 9; exact alias bodies need the plugins present)
| Command | Does |
|---|---|
| `sbt fmt` | `; scalafmtAll; scalafmtSbt` |
| `sbt check` | `; scalafmtCheckAll; scalafmtSbtCheck; Test/compile; test; versionPolicyCheck` (same as CI) |
| `sbt versions` | shows `core/version`, `zio/version`, `cats/version` (released version or snapshot) |
| `sbt "stageModule zio"` | dry run: `zio/assertReleasable` + unsigned `zio/publish` into `target/sona-staging` (VERIFIED) |
| `sbt "releaseModule zio"` | emergency manual release from a laptop: guard, `publishSigned`, `project zio`, `sonaRelease` (VERIFIED up to credentials) |
| `sbt zio/assertReleasable` | the guard alone; `RELEASE_VERSION=x.y.z` also checks the computed version equals the tag (VERIFIED) |

Aliases cannot take arguments; `stageModule`/`releaseModule` are `Command.single` commands defined in
`project/ModuleVersioning.scala` and registered with `Global / commands ++= ...` (VERIFIED). Note: `releaseModule` does not
`clean`; stale files in `target/sona-staging` would go into the bundle. In CI `ci-release` runs `; clean` first (VERIFIED run 6).
Local tip for this Mac: add `-Djava.net.preferIPv4Stack=true` to SBT_OPTS (IPv6 to Maven Central is broken here). VERIFIED.

### scripts/release.sh (tested; `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/ci-design/scripts/release.sh`)
Checks: module name, semver, on `main`, clean tree, HEAD == origin/main, tag not on remote or local, version greater than
the module's last tag (`sort -V`; note `sort -V` orders `0.2.0` before `0.2.0-RC1`, so an RC after its final is not
caught), CI green for HEAD via `gh run list` (skipped in `--dry-run`). Then it creates the annotated tag locally, runs
`RELEASE_VERSION=<v> sbt "stageModule <m>" "<m>/versionCheck" "<m>/test"`, asks for confirmation (or `--yes`), and pushes
only that tag. On any failure the local tag is deleted (trap).
Dry-run tests against the probe repo (VERIFIED): `b 0.4.1` -> fails "Not releasable: probe-a:0.2.0+2-...-SNAPSHOT",
local tag deleted; `a 0.2.1` -> "Releasable", "valid version number ... BinaryCompatible", "Dry run OK", tag deleted;
`a 0.1.9` -> "not greater than the last release 0.2.0"; `c 0.1.0` -> bad module; `a 1.2` -> bad version.
```bash
#!/usr/bin/env bash
# Release ONE module of tui4s to Maven Central by creating and pushing the tag <module>-v<version>.
# The tag push triggers .github/workflows/release.yml, which publishes only that module.
#
# Usage: scripts/release.sh <module> <version> [--dry-run] [--yes]
#   scripts/release.sh core 0.2.0 --dry-run   # validate only, delete the local tag afterwards
#   scripts/release.sh zio 0.1.3              # validate, ask, push the tag
# Env: MODULES (default "core zio cats"), REMOTE (default origin), BRANCH (default main)
set -euo pipefail

die() { echo "release: $*" >&2; exit 1; }

MODULES=${MODULES:-"core zio cats"}
REMOTE=${REMOTE:-origin}
BRANCH=${BRANCH:-main}

[[ $# -ge 2 ]] || die "usage: scripts/release.sh <module> <version> [--dry-run] [--yes]"
module=$1; version=$2; shift 2
dry_run=false; assume_yes=false
for arg in "$@"; do
  case $arg in
    --dry-run) dry_run=true ;;
    --yes|-y)  assume_yes=true ;;
    *) die "unknown option: $arg" ;;
  esac
done

[[ " $MODULES " == *" $module "* ]] || die "module must be one of: $MODULES"
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "version must look like 0.2.0 or 0.2.0-RC1"
tag="$module-v$version"

cd "$(git rev-parse --show-toplevel)"
[[ $(git rev-parse --abbrev-ref HEAD) == "$BRANCH" ]] || die "check out $BRANCH first"
[[ -z $(git status --porcelain) ]] || die "working tree is not clean"

if git remote get-url "$REMOTE" >/dev/null 2>&1; then
  git fetch --quiet --tags "$REMOTE" "$BRANCH"
  [[ $(git rev-parse HEAD) == $(git rev-parse "$REMOTE/$BRANCH") ]] || die "HEAD is not $REMOTE/$BRANCH (pull or push first)"
  if git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$tag" >/dev/null; then die "tag $tag already exists on $REMOTE"; fi
elif ! $dry_run; then
  die "remote $REMOTE not found"
fi
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then die "tag $tag already exists locally"; fi

prev=$(git describe --tags --abbrev=0 --match "$module-v[0-9]*" 2>/dev/null || true)
if [[ -n $prev ]]; then
  prev_v=${prev#"$module-v"}
  highest=$(printf '%s\n%s\n' "$prev_v" "$version" | sort -V | tail -n1)
  [[ $prev_v != "$version" && $highest == "$version" ]] || die "$version is not greater than the last release $prev_v"
  echo "==> Last release of $module: $prev_v"
else
  echo "==> First release of $module"
fi

if ! $dry_run && command -v gh >/dev/null 2>&1; then
  ci=$(gh run list --commit "$(git rev-parse HEAD)" --workflow ci.yml --json conclusion --jq '.[0].conclusion' 2>/dev/null || true)
  [[ $ci == success ]] || die "CI is not green for HEAD (conclusion: '${ci:-none}')"
fi

pushed=false
git tag -a "$tag" -m "tui4s-$module $version"
cleanup() { if [[ $pushed != true ]]; then git tag -d "$tag" >/dev/null && echo "==> Deleted local tag $tag"; fi; }
trap cleanup EXIT

echo "==> Validating $tag: guard (assertReleasable), versionCheck, tests, unsigned staging"
RELEASE_VERSION=$version sbt -batch "stageModule $module" "$module/versionCheck" "$module/test" \
  || die "validation failed. If an inter-project dependency is unreleased, release it first (e.g. scripts/release.sh core <next>)."

if $dry_run; then echo "==> Dry run OK: $tag is releasable."; exit 0; fi

if ! $assume_yes; then
  read -r -p "Push $tag to $REMOTE and publish tui4s-$module $version to Maven Central? [y/N] " answer
  [[ $answer == y || $answer == Y ]] || die "aborted"
fi
git push "$REMOTE" "refs/tags/$tag"
pushed=true
echo "==> Pushed $tag. Follow the release: gh run list --workflow release.yml --limit 1"
```

### What goes into AGENTS.md / CONTRIBUTING.md (release part)
- Module map: `modules/core` -> sbt id `core` -> artifact `io.github.yuri4n::tui4s-core` -> tag `core-v<semver>`; same for `zio`, `cats`.
  `examples/` and `docs/` are never published.
- Daily loop: `sbt fmt`, `sbt check` before every push (CI runs the same alias). `sbt versions` to see what is unreleased.
- Never set `version` or `publishTo` in build files; never move or delete a pushed release tag; never publish from a laptop
  except with `releaseModule` in an emergency.
- Compatibility: each module has `modules/<m>/compat.sbt` with `versionPolicyIntention`. A PR that breaks binary compat
  must set it to `Compatibility.None`; the next release of that module must then be `0.(y+1).0` (`versionCheck` enforces).
  After that release, open a PR that sets it back to `Compatibility.BinaryCompatible`.
- Release: `scripts/release.sh <module> <version> --dry-run`, then without `--dry-run`. If it says an inter-project
  dependency is unreleased, release that module first (core before zio/cats), wait for its Release run to finish, then retry.
  Push at most one release tag per `git push` (GitHub ignores pushes of more than three tags).
- **Agents:** pushing a release tag publishes a public, immutable artifact to Maven Central. An agent may run
  `--dry-run` freely but must get explicit human approval in chat before running release.sh without `--dry-run`, and must
  never pass `--yes` on its own.
- Secrets (GitHub environment `maven-central`): `PGP_SECRET`, `PGP_PASSPHRASE`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`
  (Central Portal user token). One-time setup: sign in to central.sonatype.com with GitHub (verifies `io.github.yuri4n`),
  generate a user token, create a dedicated GPG key, publish its public key to a keyserver.
- Troubleshooting: "SNAPSHOTs are not supported on the Central Portal" = `sonaRelease` ran with root as current project
  (use `; project <m>; sonaRelease`). "Not releasable: ..." = dirty tree, tag/version mismatch, or unreleased dependency.
  "no credentials are found for central.sonatype.com" = SONATYPE_* env missing.

## 6. Binary compatibility (MiMa / sbt-version-policy) for early-semver 0.x

### Facts
- sbt-version-policy 3.3.0 (VERIFIED on Central; README VERIFIED from GitHub main):
  sets `versionScheme := Some("early-semver")` automatically (probe: `a/versionScheme = Some(early-semver)` VERIFIED);
  uses MiMa under the hood; `versionPolicyIntention` = `Compatibility.None | BinaryCompatible | BinaryAndSourceCompatible`;
  `versionPolicyCheck` (PR gate: code + dependency changes vs intention); `versionCheck` (release gate: version number
  consistent with intention); `versionPolicyFindIssues` / `versionPolicyAssessCompatibility` (non-failing).
  Multi-project + dynver: set `ThisBuild / versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+\\+\\d+".r)`.
  Only `dynverSeparator = "+"` is supported (keep the default). First release has no previous artifact.
- Previous version is derived from each project's own `version`, so it works with per-module versions. Probe run 8
  (clean tree, HEAD tagged `a-v0.2.0` + `b-v0.4.0`): `a/version = 0.2.0` gives `versionPolicyPreviousVersions` empty and
  `mimaPreviousArtifacts = Set()` (0.y.0 is a breaking bump under early-semver, so nothing to compare). VERIFIED.
  README rule (VERIFIED text): `x.y.z+N-sha` -> previous `x.y.z`; patch `0.3.2` -> `0.3.1`.
- Overriding `versionPolicyPreviousVersions := previousStableVersion.value.toSeq` gave `b` (0.4.0) previous `0.3.1`
  and `mimaPreviousArtifacts = Set(probe-b:0.3.1)`. VERIFIED - but that is WRONG for a breaking 0.y bump, so
  **do not override; keep the sbt-version-policy default.** (The dynver README's MiMa snippet
  `mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet` has the same flaw for 0.x.)
- sbt gotcha (VERIFIED): a top-level `b / versionPolicyPreviousVersions := ...` line in build.sbt had NO effect
  (plugin projectSettings of `b` won); the same setting inside `b.settings(...)` worked. Put per-module overrides in `.settings`.
- MiMa checks JVM bytecode only. For Scala 3 inline/TASTy-level breakage there is `ch.epfl.scala:sbt-tasty-mima` 1.4.0
  (VERIFIED version on Central; usefulness for this project UNVERIFIED). Optional.

### Recommended setup
```scala
// project/plugins.sbt
addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "3.3.0") // brings sbt-mima-plugin
// build.sbt
ThisBuild / versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+\\+\\d+".r)
// modules/<m>/compat.sbt  (one per module; loaded by sbt - VERIFIED that subproject .sbt files load)
versionPolicyIntention := Compatibility.BinaryCompatible
```
- 0.x policy (early-semver): `0.y.z -> 0.y.(z+1)` must be binary compatible; `0.y -> 0.(y+1)` may break.
- Before the first release of a module: `versionPolicyIntention := Compatibility.None` (nothing to compare).
- PR CI: `sbt versionPolicyCheck` (all modules). Release CI: `sbt "<m>/versionCheck"` before `<m>/publishSigned`.
- After a breaking change lands, set that module's `compat.sbt` to `Compatibility.None` in the same PR, and
  bump 0.y at release; `scripts/release.sh` resets it to `BinaryCompatible` after tagging (sbt-version-policy README suggests
  keeping intention in a separate file and resetting it after each release - VERIFIED text).
- tui-scala is 0.0.x and Kyo is 1.0.0-RCx: their own breakage will force tui4s 0.y bumps; declare
  `libraryDependencySchemes` only if needed (UNVERIFIED whether needed).

## Recommendation summary

1. **Toolchain:** sbt **1.13.0** (sbt 2.0.9 is final and every needed plugin except sbt-typelevel has an sbt 2 build, but
   stay on 1.x for tooling maturity), Scala **3.9.0** everywhere (new LTS; forced by kyo 1.0.0-RC7), JDK 21 in CI (17 minimum).
2. **Publishing:** sbt-ci-release **1.12.1** on top of sbt's built-in Central Portal support (`localStaging` +
   `sonaRelease`). groupId `io.github.yuri4n`, artifacts `tui4s-core`, `tui4s-zio`, `tui4s-cats`. Secrets:
   `PGP_SECRET`, `PGP_PASSPHRASE`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` (Portal token).
3. **Independent versions:** per-module tags `<module>-v<semver>` + `project/ModuleVersioning.scala` (per-project dynver,
   "unchanged since tag => released version" rule, `assertReleasable` guard, `stageModule`/`releaseModule` commands).
   Proven end-to-end in the probe except the real HTTP upload.
4. **zio/cats -> core:** keep `dependsOn(core)`; POM gets core's computed version; the guard blocks a release when core has
   unreleased changes, so core is released first. Consider watching `project/Dependencies.scala` for core.
5. **CI:** `ci.yml` (sbt `check` alias + docs build) and `release.yml` (tag-triggered, one module per tag, CI_RELEASE /
   CI_SONATYPE_RELEASE env, GitHub environment `maven-central`, GitHub release with notes since the module's previous tag).
6. **Compat:** sbt-version-policy **3.3.0** (early-semver, MiMa under the hood), per-module `modules/<m>/compat.sbt`,
   `versionPolicyCheck` in PR CI, `<m>/versionCheck` at release; do not override previous-version computation.
   A breaking core release (0.y bump) will show as a dependency issue for zio/cats under `BinaryCompatible`, which
   correctly forces a 0.y bump of the integrations too (UNVERIFIED in probe; follows from `versionPolicyReportDependencyIssues` docs).
7. **Ergonomics:** `sbt fmt`, `sbt check`, `sbt versions`, `sbt "stageModule zio"`, `scripts/release.sh <m> <v> [--dry-run]`;
   agents need human approval before a non-dry-run release.

Open questions for the user: license (Apache-2.0 assumed), developer name/email for the POM, whether to publish
snapshots from main, whether Scala 3.9.0 minimum is acceptable for consumers (it is forced by Kyo RC7), and whether to
accept lockstep (option c) instead if the ~60 lines of custom build code are unwanted.

## Appendix A: project/ModuleVersioning.scala (as tested in probe runs 9-10)
```scala
import sbt._
import sbt.Keys._
import sbtdynver.DynVerPlugin
import sbtdynver.DynVerPlugin.autoImport._
import scala.sys.process._

/** Per-module versions from per-module git tags, e.g. core-v0.2.0, zio-v0.1.3.
  *
  * - HEAD exactly on `<prefix><x.y.z>` and clean            => x.y.z
  * - module dir unchanged since its last tag and tree clean => that tag's version (module is "released")
  * - otherwise                                              => dynver snapshot, e.g. 0.2.0+3-1a2b3c4d-SNAPSHOT
  */
object ModuleVersioning {
  /** `releaseModule <id>`: guard, sign + stage one module, upload/release that bundle (current project = module). */
  val releaseModule: Command = Command.single("releaseModule") { (state, m) =>
    s"$m/assertReleasable" :: s"$m/publishSigned" :: s"project $m" :: "sonaRelease" :: state
  }
  /** `stageModule <id>`: dry run; guard + unsigned publish into target/sona-staging, no upload. */
  val stageModule: Command = Command.single("stageModule") { (state, m) =>
    s"$m/assertReleasable" :: s"$m/publish" :: state
  }

  val releasedVersion   = settingKey[Option[String]]("Version of this module's last tag, if any")
  val assertReleasable  = taskKey[Unit]("Fail unless this module and its inter-project deps have release versions")

  private def unchangedSince(base: File, tag: String, paths: Seq[String]): Boolean =
    Process(Seq("git", "diff", "--quiet", tag, "HEAD", "--") ++ paths, base).! == 0

  /** @param tagPrefix e.g. "core-v"; @param paths repo-relative paths whose changes make the module "unreleased" */
  def apply(tagPrefix: String, paths: Seq[String]): Seq[Setting[_]] =
    DynVerPlugin.buildSettings ++ Seq(
      dynverTagPrefix         := tagPrefix,
      dynverSonatypeSnapshots := (ThisBuild / dynverSonatypeSnapshots).value,
      releasedVersion         := dynverGitDescribeOutput.value.filter(_.ref.isTag).map(_.ref.dropPrefix),
      version := {
        val dynamic = version.value
        val base    = (ThisBuild / baseDirectory).value
        dynverGitDescribeOutput.value match {
          case Some(out) if out.ref.isTag && !out.isDirty() && unchangedSince(base, out.ref.value, paths) =>
            out.ref.dropPrefix
          case _ => dynamic
        }
      },
      isSnapshot := version.value.endsWith("-SNAPSHOT") || version.value.contains("+"),
      assertReleasable := {
        val log  = streams.value.log
        val self = s"${name.value}:${version.value}"
        val deps = projectDependencies.value
        val bad  = (if (isSnapshot.value) List(self) else Nil) ++
          deps.filter(d => d.revision.endsWith("-SNAPSHOT") || d.revision.contains("+"))
            .map(d => s"${d.name}:${d.revision} (unreleased inter-project dependency)")
        val expected = sys.env.get("RELEASE_VERSION").filter(_.nonEmpty).toList
          .filterNot(_ == version.value).map(v => s"${name.value}: version ${version.value} != RELEASE_VERSION $v")
        val problems = bad ++ expected
        if (problems.nonEmpty) sys.error(s"Not releasable: ${problems.mkString(", ")}")
        log.info(s"Releasable: $self; deps: ${deps.map(d => s"${d.name}:${d.revision}").mkString(", ")}")
      },
    )
}
```
Notes: `releasedVersion` is informational (unused by the workflow). `isSnapshot` is redefined so that `publishTo`
(sbt-ci-release) picks central-snapshots vs local staging per module.

## Appendix B: artifacts of this research
- Report: `/Users/yuri4n/Developer/projects/tui4s/.work/reports/publishing.md`
- Probe build + throwaway git repo: `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/sbt-probe/`
- Probe logs: `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/probe-run*.log`, `probe-release-*.log`
- Workflow + script drafts: `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/ci-design/`
- Downloaded sources used as evidence: `.work/scratch/CiReleasePlugin.scala`, `DynVerPlugin.scala`, `DynVer.scala`,
  `Defaults.scala`, `Publishing.scala`, `sbt-Keys.scala`, `vp-settings.scala`, `*-readme.md`
- The throwaway GPG key used in probe run 6 was deleted after the run.
