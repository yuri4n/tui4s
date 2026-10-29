import sbt._
import sbt.io.CopyOptions
import scala.xml.{Elem, Node}

/** The published jar of tui4s-core holds the JVM classes of tui4s-app,
  * tui4s-use and tui4s-widgets. Those projects are not published. The POM and
  * the Ivy file from publishLocal do not depend on them.
  */
// cursor 2026-10-29: created: merge app, use and widgets into the core jar (ADR 0024); cursor 2026-10-29: changed: publishLocal omits the bundled projects
object CorePackaging {

  /** Artifact ids (without a Scala suffix) that the published metadata must not
    * list.
    */
  val internalArtifacts: Set[String] = Set(
    "tui4s-app",
    "tui4s-use",
    "tui4s-widgets",
  )

  /** True when `name` is a bundled artifact. The name may already carry a Scala
    * suffix (`tui4s-use_3`).
    */
  // cursor 2026-10-29: created: one check for the POM and the Ivy file
  def isBundled(name: String): Boolean =
    internalArtifacts.exists(id => name == id || name.startsWith(id + "_"))

  /** Class files and resources from the class directories of the bundled
    * projects. A manifest in those directories is left out: the jar of core has
    * its own.
    */
  def productMappings(dirs: Seq[File]): Seq[(File, String)] = {
    val pairs = dirs.flatMap { dir =>
      (dir ** -DirectoryFilter)
        .pair(Path.relativeTo(dir))
        .filter { case (_, path) => path != "META-INF/MANIFEST.MF" }
    }
    unique(pairs, "class file")
  }

  /** Sources of the bundled projects, with a path relative to a source root. */
  def sourceMappings(
      files: Seq[File],
      dirs: Seq[File],
  ): Seq[(File, String)] = {
    val pairs = files.flatMap { file =>
      dirs.iterator
        .map(dir =>
          IO.relativize(dir, file).map(rel => file -> rel.replace('\\', '/')),
        )
        .collectFirst { case Some(pair) => pair }
    }
    unique(pairs, "source file")
  }

  /** Copy class directories into `dest`. MiMa reads one directory, so the check
    * of core sees TuiApp, the composables and the widgets.
    */
  def stageClasses(dest: File, dirs: Seq[File]): File = {
    IO.delete(dest)
    IO.createDirectory(dest)
    val options = CopyOptions().withOverwrite(true)
    dirs.foreach { dir =>
      if (dir.isDirectory) IO.copyDirectory(dir, dest, options)
    }
    dest
  }

  /** Remove dependencies on the bundled projects. Keep tui4s-runner and
    * tui4s-testkit.
    */
  def dropInternalDependencies(node: Node): Node = {
    def drop(n: Node): Node = n match {
      case e: Elem if e.label == "dependencies" =>
        e.copy(child = e.child.flatMap {
          case d: Elem if d.label == "dependency" && internal(d) =>
            Seq.empty[Node]
          case other                                             => Seq(drop(other))
        })
      case e: Elem                              => e.copy(child = e.child.map(drop))
      case other                                => other
    }
    drop(node)
  }

  // cursor 2026-10-29: changed: the POM uses isBundled
  private def internal(dep: Elem): Boolean =
    isBundled((dep \ "artifactId").text)

  private def unique(
      pairs: Seq[(File, String)],
      kind: String,
  ): Seq[(File, String)] = {
    val grouped = pairs.groupBy(_._2)
    val clashes = grouped.collect {
      case (path, items)
          if items.map(_._1.getCanonicalPath).distinct.size > 1 =>
        path
    }
    if (clashes.nonEmpty)
      sys.error(
        s"tui4s-core: two $kind entries share a jar path: ${clashes.mkString(", ")}",
      )
    grouped.values.map(_.head).toSeq.sortBy(_._2)
  }
}
