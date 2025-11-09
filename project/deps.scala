import sbt._

object deps {
  val scala3Version = "3.7.3"

  val fs2ver   = "3.11.0"
  lazy val fs2 = Seq("co.fs2" %% "fs2-core" % fs2ver, "co.fs2" %% "fs2-io" % fs2ver)

  val tuiScalaVer   = "0.0.7"
  lazy val tuiScala = Seq("com.olvind.tui" % "crossterm" % tuiScalaVer, "com.olvind.tui" %% "tui" % tuiScalaVer)

  val weaverVer   = "0.8.3"
  lazy val weaver = "com.disneystreaming" %% "weaver-cats" % weaverVer
}
