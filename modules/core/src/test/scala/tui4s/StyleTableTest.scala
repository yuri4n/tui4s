package tui4s

import kyo.*
import kyo.test.*
import tui4s.internal.Props

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The table of the style properties cannot drift. The ignored set is "all the
  * `Style.Prop` cases of `kyo-ui-surface.txt` minus the honored set of
  * [[internal.Props.honored]]". The package Scaladoc of `tui4s` lists the two
  * sets, and this test reads that list from the source file.
  */
// claude 2026-10-19: created (L1b)
class StyleTableTest extends Test[Any]:
    import StyleTableTest.*

    "the surface list, Props.honored and the package Scaladoc agree" in {
      val surface          = props
      val ignored          = surface -- Props.honored
      val (honored, table) = StyleTableTest.table(scaladoc)
      assert(surface.size == 76, s"${surface.size} cases")
      assert(
        Props.honored.subsetOf(surface),
        s"not in kyo-ui: ${Props.honored -- surface}",
      )
      assert(
        honored == Props.honored,
        s"honored: the Scaladoc has ${honored -- Props.honored}, Props has ${Props.honored -- honored}",
      )
      assert(
        table == ignored,
        s"ignored: the Scaladoc has ${table -- ignored}, the surface has ${ignored -- table}",
      )
    }
end StyleTableTest

object StyleTableTest:
    /** The case names of `Style.Prop` in the checked-in surface list. */
    def props: Set[String] =
      KyoUiSurface.checkedIn
        .filter(i => i.kind == "enum.case" && i.name.startsWith("Style.Prop."))
        .map(_.name.stripPrefix("Style.Prop."))
        .toSet

    private val source = "modules/core/src/main/scala/tui4s/package.scala"

    /** The lines of the package Scaladoc source: the first directory, from the
      * working directory up, that has it.
      */
    def scaladoc: Vector[String] =
        val root = Iterator
          .iterate(Paths.get("").nn.toAbsolutePath.nn)(p => p.getParent)
          .takeWhile(_ != null)
          .map(_.nn)
          .find(p => Files.exists(p.resolve(source)))
          .getOrElse(throw IllegalStateException(s"no $source"))
        Files
          .readAllLines(root.resolve(source), StandardCharsets.UTF_8)
          .nn
          .asScala
          .toVector
    end scaladoc

    private val Name = """`(\w+)`""".r

    /** The names of the list items `- honored:` and `- ignored:`: the
      * back-quoted words from the item line to the next item or to the end of
      * the list (a blank Scaladoc line or the end of the comment).
      */
    def table(lines: Vector[String]): (Set[String], Set[String]) =
        def item(tag: String): Set[String] =
            val start = lines.indexWhere(_.trim.startsWith(s"*   - $tag:"))
            if start < 0 then Set.empty
            else
                val rest = lines.drop(start + 1).takeWhile { l =>
                  val t = l.trim
                  !t.startsWith("*   - ") && t != "*" && !t.startsWith("*/")
                }
                (lines(start) +: rest)
                  .flatMap(Name.findAllMatchIn(_))
                  .map(_.group(1).nn)
                  .toSet
            end if
        end item
        (item("honored"), item("ignored"))
    end table
end StyleTableTest
