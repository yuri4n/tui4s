package examples

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** README.md shows code of the examples, and this suite checks that it is the
  * same text as the example files (OS-48).
  *
  * The marking: in README.md, a line `<!-- from: <path> -->` or `<!-- from:
  * <path>#<region> -->` directly above a fenced code block marks that block.
  * `<path>` is relative to the root of the repository. Without `#<region>`, the
  * block must be the whole file. With it, the block must be the lines of the
  * region: in the file, the lines between `// region: <region>` and
  * `// endregion: <region>` (the two region lines are not part of it). A block
  * without a marker is not checked.
  */
// claude 2026-10-13: created (T2, OS-48 part)
class ReadmeSuite extends FunSuite:
    import ReadmeSuite.*

    /** The root of the repository: the first directory, from the working
      * directory up, that has `build.sbt` and `README.md`.
      */
    private val root: Path =
      Iterator
        .iterate(Paths.get("").nn.toAbsolutePath.nn)(p => p.getParent)
        .takeWhile(_ != null)
        .map(_.nn)
        .find(p =>
          Files.exists(p.resolve("build.sbt")) &&
            Files.exists(p.resolve("README.md")),
        )
        .getOrElse(fail("no directory with build.sbt and README.md"))

    private def lines(p: Path): Vector[String] =
      Files.readAllLines(p, StandardCharsets.UTF_8).nn.asScala.toVector

    test("README.md: each marked block is the same text as its source") {
      val found = blocks(lines(root.resolve("README.md")))
      assert(found.size >= 2, s"expected the two quick-start blocks: $found")
      found.foreach {
        case Left(problem) => fail(s"README.md: $problem")
        case Right(b)      =>
          val src      = root.resolve(b.path)
          assert(
            Files.exists(src),
            s"README.md line ${b.line}: no file ${b.path}",
          )
          val expected = b.region match
              case None    => Right(lines(src).mkString("\n"))
              case Some(r) => region(lines(src), r)
          expected match
              case Left(problem) => fail(s"${b.path}: $problem")
              case Right(text)   =>
                assertEquals(
                  b.text,
                  text,
                  s"README.md line ${b.line}: the block is not the text of ${b.path}${b.region.fold("")("#" + _)}",
                )
          end match
      }
    }

    test(
      "the check finds a block that differs, a marker without a block, a region without an end",
    ) {
      val readme = Vector(
        "<!-- from: A.scala#r -->",
        "```scala",
        "val x = 1",
        "```",
        "<!-- from: B.scala -->",
        "no block here",
      )
      val found  = blocks(readme)
      assertEquals(found.size, 2)
      assertEquals(found(0).map(_.text), Right("val x = 1"))
      assert(found(1).isLeft, found(1).toString)
      val file   = Vector("// region: r", "val x = 2", "// endregion: r")
      assertEquals(region(file, "r"), Right("val x = 2"))
      assertNotEquals(found(0).map(_.text), region(file, "r"))
      assert(region(Vector("// region: r", "val x = 2"), "r").isLeft)
      assert(region(file, "other").isLeft)
    }
end ReadmeSuite

object ReadmeSuite:
    /** A marked block: its marker line (1-based), the source and its region,
      * and the text between the fences.
      */
    final case class Block(
        line: Int,
        path: String,
        region: Option[String],
        text: String,
    )

    private val Marker = """<!-- from: ([^#\s]+)(?:#(\S+))? -->""".r

    /** The marked blocks of `readme`, or a problem for a marker that has no
      * block under it.
      */
    def blocks(readme: Vector[String]): Vector[Either[String, Block]] =
      readme.zipWithIndex.collect { case (Marker(path, region), i) =>
        val fence = i + 1
        if fence >= readme.size || !readme(fence).startsWith("```") then
            Left(
              s"line ${i + 1}: the marker of $path has no code block under it",
            )
        else
            val end = readme.indexWhere(_.startsWith("```"), fence + 1)
            if end < 0 then Left(s"line ${i + 1}: the code block has no end")
            else
                Right(
                  Block(
                    i + 1,
                    String.valueOf(path),
                    Option(region).map(String.valueOf),
                    readme.slice(fence + 1, end).mkString("\n"),
                  ),
                )
            end if
        end if
      }

    /** The lines between `// region: <name>` and `// endregion: <name>`. */
    def region(file: Vector[String], name: String): Either[String, String] =
        val start = file.indexWhere(_.trim == s"// region: $name")
        val end   = file.indexWhere(_.trim == s"// endregion: $name", start + 1)
        if start < 0 then Left(s"no line '// region: $name'")
        else if end < 0 then Left(s"no line '// endregion: $name'")
        else Right(file.slice(start + 1, end).mkString("\n"))
end ReadmeSuite
