package tui4s.oracle

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.{Div, Element, Foreach, Fragment, P, Reactive, Section, Ul}
import kyo.test.prop.Gen
import tui4s.internal.*

/** Random kyo-ui trees for the oracle properties of the frame path (PF-a). A
  * tree has elements, texts, fragments, decorated elements (inputs, a button, a
  * select, a rule, an image, lists with markers) and the two kinds of regions
  * (`Reactive` and `Foreach`). Each region has two contents: scene A shows the
  * first one, scene B the second one. A step from scene A to scene B is a
  * change of every region, as the runner sees it. In scene A the first text
  * input has the focus and the caret is at its first grapheme; in scene B no
  * element has the focus.
  *
  * All generators are explicit values (no `Gen.derive`, no given `Gen`).
  */
// claude 2026-10-19: created (PF-a): the generators of the oracle properties
object UiGen:

    /** The shape of a random UI. */
    enum Spec:
        case Txt(text: String)
        case Box(tag: Int, style: Int, kids: List[Spec])
        case Frag(kids: List[Spec])
        case Reac(a: Spec, b: Spec)
        case Each(a: List[(String, Spec)], b: List[(String, Spec)])

        /** A decorated element: `kind` is 0 to 9 (see [[decorated]]). */
        // claude 2026-10-19: created (P5): the decorations of the painter
        case Deco(kind: Int)
    end Spec

    /** A UI, the contents of its regions and inputs in scene A and in scene B,
      * and the first text input of scene A (it has the focus there).
      */
    // claude 2026-10-19: changed (P5): the focus of scene A
    final case class Built(
        ui: UI,
        a: Map[Loc, Content],
        b: Map[Loc, Content],
        focus: Option[Loc] = None,
    ):
        def sceneA(size: Size): Scene = Scene(a.get, focus, size, _ => 0)
        def sceneB(size: Size): Scene = scene(b, size)
    end Built

    def scene(contents: Map[Loc, Content], size: Size): Scene =
      Scene(contents.get, None, size, _ => Int.MaxValue)

    /** An integer from `lo` to `hi`, both included. */
    def between(lo: Int, hi: Int): Gen[Int] =
      Gen.int.map(i => lo + Math.floorMod(i, hi - lo + 1))

    /** One word of a text: ASCII, an empty word (two spaces), a long token, a
      * wide CJK character, an emoji (a surrogate pair), a combining mark, and
      * two glyphs wider than 2 cells (jatatui adds the widths of the code
      * points of a cluster): a ZWJ family (6 cells) and an emoji with a skin
      * tone (4 cells).
      */
    // claude 2026-10-19: changed (P5): the glyphs wider than 2 cells
    val word: Gen[String] = Gen.oneOf(
      "a",
      "bb",
      "hello",
      "world",
      "x",
      "",
      "a-long-token-without-spaces",
      "中文",
      "👍",
      "é",
      "👨‍👩‍👧",
      "👍🏽",
    )

    /** A text of 0 to 6 words, one space between them. */
    val text: Gen[String] = textOf(word)

    /** A text of ASCII words only: one cell for each char. */
    val asciiText: Gen[String] =
      textOf(Gen.oneOf("a", "bb", "hello", "", "a-long-token-without-spaces"))

    private def textOf(w: Gen[String]): Gen[String] =
      between(0, 6).flatMap(n => Gen.listOfN(n, w)).map(_.mkString(" "))

    private val keys: Gen[String] = Gen.oneOf("a", "b", "c", "d", "e", "f")

    private def kids(depth: Int, txt: Gen[String]): Gen[List[Spec]] =
      between(0, 4)
        .flatMap(n => Gen.listOfN(n, spec(depth - 1, txt)))
        .map(_.toList)

    private def items(depth: Int, txt: Gen[String]): Gen[List[(String, Spec)]] =
      between(0, 4)
        .flatMap(n => Gen.listOfN(n, Gen.zip(keys, spec(depth - 1, txt))))
        .map(_.toList.distinctBy(_._1))

    /** A tree at most `depth` levels deep, with texts from `txt`. */
    def spec(depth: Int, txt: Gen[String]): Gen[Spec] =
      if depth <= 0 then txt.map(Spec.Txt(_))
      else
          Gen.frequency(
            2 -> txt.map(Spec.Txt(_)),
            6 -> Gen
              .zip3(between(0, 4), between(0, 5), kids(depth, txt))
              .map(Spec.Box(_, _, _)),
            1 -> kids(depth, txt).map(Spec.Frag(_)),
            2 -> Gen
              .zip(spec(depth - 1, txt), spec(depth - 1, txt))
              .map(Spec.Reac(_, _)),
            2 -> Gen
              .zip(items(depth, txt), items(depth, txt))
              .map(Spec.Each(_, _)),
            2 -> between(0, 9).map(Spec.Deco(_)),
          )

    /** A screen size: 1 to 40 columns, 1 to 20 rows. */
    val size: Gen[Size] =
      Gen.zip(between(1, 40), between(1, 20)).map(Size(_, _))

    private def styled(tag: Int, style: Int)(using Frame): Element =
        val el: Element = tag match
            case 0 => div
            case 1 => p
            case 2 => span
            case 3 => section
            case _ => ul
        val s: Style    = style match
            case 1 => Style.border(1.px, Style.Color.slate).padding(0.px, 1.px)
            case 2 => Style.row.gap(1.px)
            case 3 => Style.bg(Style.Color.blue)
            case 4 => Style.height(2.px)
            case 5 => Style.textAlign(_.center)
            case _ => Style.empty
        el match
            case d: Div                => d.style(s)
            case d: P                  => d.style(s)
            case d: UI.Ast.SpanElement => d.style(s)
            case d: Section            => d.style(s)
            case d: Ul                 => d.style(s)
            case other                 => other
    end styled

    /** The value of a text input in scene A: wider than its field (20 cells),
      * with a wide character at the caret. In scene B the value is empty, so
      * the placeholder shows.
      */
    private val inputA = "中文 a-long-token-without-spaces"

    /** The element of a [[Spec.Deco]]: 0 a button, 1 a checked checkbox, 2 a
      * radio, 3 a text input with a placeholder (its value comes from the
      * scene), 4 a select, 5 a rule, 6 an image, 7 a list with discs, 8 a list
      * with numbers, 9 a range input.
      */
    // claude 2026-10-19: created (P5)
    def decorated(kind: Int)(using Frame): UI =
      kind match
          case 0 => button("ok")
          case 1 => checkbox.checked(true)
          case 2 => radio
          case 3 => input.placeholder("type")
          case 4 =>
            select.value("b")(option.value("a")("a"), option.value("b")("bee"))
          case 5 => hr
          case 6 => img(ImgSrc.Path("p.png"), "pic")
          case 7 => ul(li("a"), li("中")).style(_.listStyle(_.disc))
          case 8 => ol(li("x"), li("y")).style(_.listStyle(_.decimal))
          case _ => rangeInput.value(30.0)

    private def withKids(el: Element, cs: Chunk[UI]): UI = el match
        case d: Div                => d.copy(children = cs)(using d.frame)
        case d: P                  => d.copy(children = cs)(using d.frame)
        case d: UI.Ast.SpanElement => d.copy(children = cs)(using d.frame)
        case d: Section            => d.copy(children = cs)(using d.frame)
        case d: Ul                 => d.copy(children = cs)(using d.frame)
        case other                 => other

    /** Builds the UI of `s` at `path`, the contents of its regions and inputs,
      * and the focus of scene A.
      */
    // claude 2026-10-19: changed (P5): the decorated elements and the focus
    def build(s: Spec, path: Loc)(using Frame): Built =
      s match
          case Spec.Txt(t)              => Built(UI.Ast.Text(t), Map.empty, Map.empty)
          case Spec.Deco(3)             =>
            Built(
              decorated(3),
              Map(path -> Content.Value(inputA)),
              Map(path -> Content.Value("")),
              Some(path),
            )
          case Spec.Deco(k)             => Built(decorated(k), Map.empty, Map.empty)
          case Spec.Box(tag, style, ks) =>
            val built =
              ks.zipWithIndex.map((k, i) => build(k, path.child(i)))
            Built(
              withKids(styled(tag, style), Chunk.from(built.map(_.ui))),
              built.foldLeft(Map.empty[Loc, Content])(_ ++ _.a),
              built.foldLeft(Map.empty[Loc, Content])(_ ++ _.b),
              built.flatMap(_.focus).headOption,
            )
          case Spec.Frag(ks)            =>
            val built =
              ks.zipWithIndex.map((k, i) => build(k, path.child(i)))
            Built(
              Fragment[UI](Chunk.from(built.map(_.ui))),
              built.foldLeft(Map.empty[Loc, Content])(_ ++ _.a),
              built.foldLeft(Map.empty[Loc, Content])(_ ++ _.b),
              built.flatMap(_.focus).headOption,
            )
          case Spec.Reac(a, b)          =>
            val ia = build(a, path.content)
            val ib = build(b, path.content)
            Built(
              Reactive[UI](Signal.initConst[UI](UI.Ast.Text(""))),
              ia.a.updated(path, Content.Single(ia.ui)),
              ib.b.updated(path, Content.Single(ib.ui)),
              ia.focus,
            )
          case Spec.Each(as, bs)        =>
            val ia = as.map((k, s) => (k, build(s, path.item(k))))
            val ib = bs.map((k, s) => (k, build(s, path.item(k))))
            Built(
              Foreach[String, UI](
                Signal.initConst(Chunk.empty[String]),
                Absent,
                (_, _) => UI.Ast.Text(""),
              ),
              ia.foldLeft(Map.empty[Loc, Content])(_ ++ _._2.a)
                .updated(
                  path,
                  Content.Many(ia.map((k, b) => (k, b.ui)).toVector),
                ),
              ib.foldLeft(Map.empty[Loc, Content])(_ ++ _._2.b)
                .updated(
                  path,
                  Content.Many(ib.map((k, b) => (k, b.ui)).toVector),
                ),
              ia.flatMap(_._2.focus).headOption,
            )

    /** A root element (at `Loc.root`) of at most `depth` levels, with its
      * regions, and texts from `txt`.
      */
    def built(depth: Int, txt: Gen[String] = text)(using Frame): Gen[Built] =
      Gen
        .zip3(between(0, 4), between(0, 5), kids(depth, txt))
        .map((tag, style, ks) => build(Spec.Box(tag, style, ks), Loc.root))

    /** The paths whose content differs between scene A and scene B. */
    def changed(b: Built): Vector[Loc] =
      (b.a.keySet ++ b.b.keySet).toVector
        .filter(p => b.a.get(p) != b.b.get(p))
        .sortBy(_.showLoc)

    /** The laid-out screen of scene A, then the step to scene B as the runner
      * does it: the owner of each changed region (the regions under another
      * changed region left out) is laid out again.
      */
    def steppedLaid(b: Built, size: Size): (Laid, Laid) =
        val laidA   = Layout.layoutAll(b.ui, b.sceneA(size))
        val dirty   = changed(b)
        val regions = TopsOracle.runner(dirty)
        val laidB   = regions.foldLeft(laidA) { (l, r) =>
          l.regionOwner.get(r) match
              case Some(owner) =>
                Layout.relayout(l, owner, Set.empty, b.sceneB(size), b.ui)._1
              case None        => l
        }
        (laidA, laidB)
    end steppedLaid
end UiGen
