import sbt._
import sbt.Keys._
import sbtdynver.DynVerPlugin
import sbtdynver.DynVerPlugin.autoImport._
import scala.sys.process._

/** Per-module versions from per-module git tags, e.g. core-v0.2.0.
  *
  * The version of a module is:
  *   - `x.y.z` if HEAD is on the tag `<prefix>x.y.z` and the tree is clean.
  *   - the version of its last tag if the tree is clean and the module paths
  *     did not change since that tag (the module is "released").
  *   - a dynver snapshot (e.g. 0.2.0+3-1a2b3c4d-SNAPSHOT) in all other cases.
  */
object ModuleVersioning {

  /** `releaseModule <id>`: check, sign and stage one module, then upload and
    * release that bundle. One call is one Central deployment. The release
    * workflow batches the modules of a run into one `sonaRelease`.
    * `sonaRelease` runs in the module project, because the root version is a
    * snapshot.
    */
  // cursor 2026-10-29: changed: one call is one Central deployment (ADR 0024)
  val releaseModule: Command = Command.single("releaseModule") { (state, m) =>
    s"$m/assertReleasable" :: s"$m/publishSigned" :: s"project $m" ::
      "sonaRelease" :: state
  }

  /** `stageModule <id>`: dry run. Check, then publish unsigned into
    * target/sona-staging. Nothing is uploaded.
    */
  val stageModule: Command = Command.single("stageModule") { (state, m) =>
    s"$m/assertReleasable" :: s"$m/publish" :: state
  }

  val releasedVersion  = settingKey[Option[String]](
    "Version of the last tag of this module, if any",
  )
  val assertReleasable = taskKey[Unit](
    "Fail unless this module and its inter-project deps have release versions",
  )

  private def isUnreleased(v: String): Boolean =
    v.endsWith("-SNAPSHOT") || v.contains("+")

  private def unchangedSince(
      base: File,
      tag: String,
      paths: Seq[String],
  ): Boolean = {
    val diff = Seq("git", "diff", "--quiet", tag, "HEAD", "--") ++ paths
    Process(diff, base).! == 0
  }

  /** @param tagPrefix
    *   the tag prefix of the module, e.g. "core-v".
    * @param paths
    *   repo-relative paths. A change in one of them makes the module
    *   unreleased.
    */
  def apply(tagPrefix: String, paths: Seq[String]): Seq[Setting[_]] =
    DynVerPlugin.buildSettings ++ Seq(
      dynverTagPrefix         := tagPrefix,
      dynverSonatypeSnapshots := (ThisBuild / dynverSonatypeSnapshots).value,
      releasedVersion         := {
        val out = dynverGitDescribeOutput.value
        out.filter(_.ref.isTag).map(_.ref.dropPrefix)
      },
      version                 := {
        val dynamic = version.value
        val base    = (ThisBuild / baseDirectory).value

        def released(out: sbtdynver.GitDescribeOutput): Boolean =
          out.ref.isTag && !out.isDirty() &&
            unchangedSince(base, out.ref.value, paths)
        dynverGitDescribeOutput.value match {
          case Some(out) if released(out) => out.ref.dropPrefix
          case _                          => dynamic
        }
      },
      isSnapshot              := isUnreleased(version.value),
      assertReleasable        := {
        val log      = streams.value.log
        val self     = s"${name.value}:${version.value}"
        val deps     = projectDependencies.value
        val badSelf  = if (isSnapshot.value) List(self) else Nil
        val badDeps  = deps.filter(d => isUnreleased(d.revision)).map { d =>
          s"${d.name}:${d.revision} (unreleased inter-project dependency)"
        }
        val expected = sys.env.get("RELEASE_VERSION").filter(_.nonEmpty)
        val badTag   = expected.toList.filterNot(_ == version.value).map { v =>
          s"${name.value}: version ${version.value} != RELEASE_VERSION $v"
        }
        val problems = badSelf ++ badDeps ++ badTag
        if (problems.nonEmpty)
          sys.error(s"Not releasable: ${problems.mkString(", ")}")
        val depList  = deps.map(d => s"${d.name}:${d.revision}").mkString(", ")
        log.info(s"Releasable: $self; deps: $depList")
      },
    )
}
