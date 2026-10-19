package tui4s

import kyo.*
import kyo.test.*
import tui4s.internal.{CssVars, Props, Warnings}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The table of the style properties cannot drift. The ignored set is "all the
  * `Style.Prop` cases of `kyo-ui-surface.txt` minus the honored set of
  * [[internal.Props.honored]]". The package Scaladoc of `tui4s` lists the two
  * sets, and this test reads that list from the source file. The warning set is
  * the set of the cases whose sample (one style of each case, with a value that
  * the runner honors when it honors the case) gives a warning
  * ([[internal.Warnings]]): it is the ignored set too.
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

    "the warning set is the ignored set: a sample of each case warns when the case is ignored" in {
      val names   = samples.map(_.props.head.productPrefix)
      val warns   = samples
        .filter(st => Warnings.of(st, CssVars.empty).nonEmpty)
        .map(_.props.head.productPrefix)
        .toSet
      val ignored = props -- Props.honored
      assert(names.toSet == props, s"no sample: ${props -- names.toSet}")
      assert(names.size == props.size, "two samples of one case")
      assert(
        warns == ignored,
        s"warns: ${warns -- ignored}; silent: ${ignored -- warns}",
      )
    }
end StyleTableTest

object StyleTableTest:
    /** One style of each case of `Style.Prop`, with a value that the runner
      * honors when it honors the case.
      */
    def samples(using Frame): Vector[Style] =
        val c = Style.Color.red
        Vector(
          Style.accentColor(c),
          Style.active(Style.bold),
          Style.align(_.center),
          Style.alignSelf(_.center),
          Style.animationDelay(10),
          Style.animation("a", 10, _.linear),
          Style.backgroundClip(_.borderBox),
          Style.bg(c),
          Style.bgGradient(_.toRight, (c, 0.pct), (c, 100.pct)),
          Style.blur(1.px),
          Style.borderCollapse(_.collapse),
          Style.borderColor(c),
          Style.rounded(1.px),
          Style.borderStyle(_.solid),
          Style.borderWidth(1.px),
          Style.bottom(1.px),
          Style.brightness(0.5),
          Style.contrast(0.5),
          Style.cursor(_.pointer),
          Style.disabled(Style.bold),
          Style.display(_.flex),
          Style.flexBasis(1.px),
          Style.row,
          Style.flexGrow(1),
          Style.flexShrink(1),
          Style.flexWrap(_.wrap),
          Style.focus(Style.bold),
          Style.fontFamily(_.Monospace),
          Style.fontSize(12.px),
          Style.fontStyle(_.italic),
          Style.fontVariantLigatures(_.none),
          Style.fontWeight(_.bold),
          Style.gap(1.px),
          Style.grayscale(0.5),
          Style.height(1.px),
          Style.displayNone,
          Style.hover(Style.bold),
          Style.hueRotate(0.5),
          Style.invert(0.5),
          Style.justify(_.center),
          Style.left(1.px),
          Style.letterSpacing(1.px),
          Style.lineHeight(1.5),
          Style.listStyle(_.disc),
          Style.margin(1.px),
          Style.maxHeight(1.px),
          Style.maxWidth(1.px),
          Style.minHeight(1.px),
          Style.minWidth(1.px),
          Style.opacity(0.5),
          Style.overflow(_.hidden),
          Style.overflowX(_.hidden),
          Style.overflowY(_.hidden),
          Style.padding(1.px),
          Style.position(_.absolute),
          Style.right(1.px),
          Style.saturate(0.5),
          Style.scrollMarginTop(1.px),
          Style.scrollbarColor(c, c),
          Style.scrollbarGutter(_.stable),
          Style.scrollbarWidth(_.thin),
          Style.sepia(0.5),
          Style.shadow(1.px, 1.px, 1.px, 1.px, c),
          Style.strokeDashoffset(0.5),
          Style.textAlign(_.center),
          Style.color(c),
          Style.textDecoration(_.underline),
          Style.textOverflow(_.ellipsis),
          Style.textTransform(_.uppercase),
          Style.textWrap(_.wrap),
          Style.top(1.px),
          Style.transition(_.all, 10, _.linear),
          Style.translate(1.px, 1.px),
          Style.whiteSpace(_.pre),
          Style.width(1.px),
          Style.zIndex(1),
        )
    end samples

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
