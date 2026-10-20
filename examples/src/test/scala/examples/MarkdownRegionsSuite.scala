package examples

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The Markdown files show code of the examples, and this suite checks that it
  * is the same text as the source files (OS-48). The files: `README.md`,
  * `examples/README.md` and each `.md` file under `docs/content` (the pages of
  * the docs site).
  *
  * The marking: a line `<!-- from: <path> -->` or `<!-- from: <path>#<region>
  * -->` directly above a fenced code block marks that block. `<path>` is
  * relative to the root of the repository. Without `#<region>`, the block must
  * be the whole file. With it, the block must be the lines of the region: in
  * the file, the lines between `// region: <region>` and `// endregion:
  * <region>` (the two region lines are not part of it). In the two READMEs, a
  * block without a marker is not checked. In the docs pages, each Scala block
  * (a fence that starts with ```` ```scala ````) must have a marker; a shell,
  * YAML or `sbt` block has none.
  */
// claude 2026-10-13: created (T2, OS-48 part)
// claude 2026-10-20: changed (L5): ReadmeSuite becomes MarkdownRegionsSuite;
// it also checks examples/README.md and the pages of the docs site
class MarkdownRegionsSuite extends FunSuite:
    import MarkdownRegionsSuite.*

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

    /** The pages of the docs site, in a fixed order. */
    private val pages: Vector[Path] =
        val dir = root.resolve("docs/content").nn
        if !Files.isDirectory(dir) then Vector.empty
        else
            val walk = Files.walk(dir).nn
            try
              walk.iterator.nn.asScala
                .filter(p => p.toString.endsWith(".md"))
                .toVector
                .sortBy(_.toString)
            finally walk.close()
        end if
    end pages

    /** Checks each marked block of the file `name` against its source. */
    private def check(
        name: String,
        found: Vector[Either[String, Block]],
    ): Unit =
      found.foreach {
        case Left(problem) => fail(s"$name: $problem")
        case Right(b)      =>
          val src      = root.resolve(b.path)
          assert(
            Files.exists(src),
            s"$name line ${b.line}: no file ${b.path}",
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
                  s"$name line ${b.line}: the block is not the text of ${b.path}${b.region.fold("")("#" + _)}",
                )
          end match
      }

    test("README.md: each marked block is the same text as its source") {
      val found = blocks(lines(root.resolve("README.md")))
      assert(found.size >= 2, s"expected the two quick-start blocks: $found")
      check("README.md", found)
    }

    test(
      "examples/README.md: each marked block is the same text as its source",
    ) {
      check(
        "examples/README.md",
        blocks(lines(root.resolve("examples/README.md"))),
      )
    }

    test(
      "docs/content: each Scala block is marked, and each marked block is the same text as its source",
    ) {
      assert(pages.nonEmpty, s"no page under ${root.resolve("docs/content")}")
      val counts = pages.map { p =>
        val name  = root.relativize(p).toString
        val text  = lines(p)
        unmarkedScala(text).foreach(n =>
          fail(
            s"$name line $n: a Scala block with no '<!-- from: ... -->' line",
          ),
        )
        val found = blocks(text)
        check(name, found)
        found.size
      }
      assert(counts.sum >= 20, s"expected many checked blocks: $counts")
    }

    test(
      "the check finds a block that differs, a marker without a block, a region without an end, a Scala block with no marker",
    ) {
      val readme = Vector(
        "<!-- from: A.scala#r -->",
        "```scala",
        "val x = 1",
        "```",
        "<!-- from: B.scala -->",
        "no block here",
        "```scala [C.scala]",
        "val y = 3",
        "```",
        "```sh",
        "sbt test",
        "```",
      )
      val found  = blocks(readme)
      assertEquals(found.size, 2)
      assertEquals(found(0).map(_.text), Right("val x = 1"))
      assert(found(1).isLeft, found(1).toString)
      assertEquals(unmarkedScala(readme), Vector(7))
      val file   = Vector("// region: r", "val x = 2", "// endregion: r")
      assertEquals(region(file, "r"), Right("val x = 2"))
      assertNotEquals(found(0).map(_.text), region(file, "r"))
      assert(region(Vector("// region: r", "val x = 2"), "r").isLeft)
      assert(region(file, "other").isLeft)
    }
end MarkdownRegionsSuite

object MarkdownRegionsSuite:
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

    /** The lines (1-based) of the opening fences of the Scala blocks of `md`
      * that have no marker line directly above them.
      */
    // claude 2026-10-20: created (L5): every Scala block of the docs is marked
    def unmarkedScala(md: Vector[String]): Vector[Int] =
        // the indexes of the opening fences: a fence line opens a block when no
        // block is open
        val opening = md.indices
          .filter(i => md(i).startsWith("```"))
          .zipWithIndex
          .collect { case (i, n) if n % 2 == 0 => i }
          .toVector
        opening.collect {
          case i
              if md(i).startsWith("```scala") &&
                (i == 0 || Marker.unapplySeq(md(i - 1)).isEmpty) =>
            i + 1
        }
    end unmarkedScala

    /** The lines between `// region: <name>` and `// endregion: <name>`. */
    def region(file: Vector[String], name: String): Either[String, String] =
        val start = file.indexWhere(_.trim == s"// region: $name")
        val end   = file.indexWhere(_.trim == s"// endregion: $name", start + 1)
        if start < 0 then Left(s"no line '// region: $name'")
        else if end < 0 then Left(s"no line '// endregion: $name'")
        else Right(file.slice(start + 1, end).mkString("\n"))
end MarkdownRegionsSuite
