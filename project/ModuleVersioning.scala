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
