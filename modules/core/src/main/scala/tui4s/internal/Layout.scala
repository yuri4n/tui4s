package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import jatatui.core.style.Color as JColor
import jatatui.core.text.Span
import kyo.*
import kyo.Style.{
  Alignment,
  Justification,
  ListStyle,
  Overflow,
  ScrollbarWidth,
  TextOverflow,
  WhiteSpace,
}
import kyo.UI.Ast.*

/** What a region shows now. */
private[tui4s] enum Content:
    /** A `Reactive` region: the last UI its signal produced. */
    case Single(ui: UI)

    /** A `Foreach` region: its rendered items, keyed. */
    case Many(items: Vector[(String, UI)])

    /** An element bound to a `SignalRef` (`.value(ref)`, `.checked(ref)`): the
      * ref's value.
      */
    case Value(v: Any)

    /** A component whose content has not arrived yet. */
    case Pending
end Content

/** What layout and paint need from the runner. All lookups are pure reads of
  * the runner's immutable world. `caret` gives the caret of the text input at a
  * path as a grapheme index (`World.carets`; `Int.MaxValue` is the end of the
  * text). `scroll` gives the scroll offset of the viewport at a path
  * (`World.scroll`); the layout clamps it. `vars` holds the custom properties
  * of the stylesheet of the run ([[CssVars]]): the screen has them.
  */
// claude 2026-10-19: changed: the scroll offset of a viewport (L1a); the
// custom properties of the run (L1b)
private[tui4s] final case class Scene(
    content: Loc => Option[Content],
    focus: Option[Loc],
    screen: Size,
    caret: Loc => Int,
    scroll: Loc => Offset = _ => Offset.zero,
    vars: CssVars = CssVars.empty,
)

private[tui4s] enum Kind:
    case Screen
    case El(el: Element)
    // claude 2026-10-19: changed: the text flow, not a Boolean (L1a)
    case Txt(text: String, flow: TextFlow)

/** What a text does with its white space (CSS `white-space`): `Keep` keeps the
  * spaces and the line breaks; `Collapse` makes each run of white space (line
  * breaks too) one space and drops it at the two ends; `KeepBreaks` collapses
  * the white space of each line and keeps the line breaks.
  */
// claude 2026-10-19: created (L1a)
private[tui4s] enum Space derives CanEqual:
    case Keep, Collapse, KeepBreaks

/** How a text breaks into lines: `Off` keeps its line breaks and never wraps;
  * `Split` wraps at the spaces and splits a word wider than the line by cells
  * (CSS `overflow-wrap: break-word`); `Whole` wraps at the spaces and keeps a
  * long word whole (`overflow-wrap: normal`).
  */
// claude 2026-10-19: created (L1a)
private[tui4s] enum Wrapping derives CanEqual:
    case Off, Split, Whole

/** Where a line that is wider than its text box ends: `Clip` cuts it by cells;
  * `Ellipsis` paints "…" in the last cell (CSS `text-overflow: ellipsis`).
  */
// claude 2026-10-19: created (L1a)
private[tui4s] enum Cut derives CanEqual:
    case Clip, Ellipsis

/** The flow of a text node: its white space, its wrap and its cut, from the
  * props of the element that holds it ([[TextFlow.of]]).
  *
  * For example, in `div.style(_.overflow(_.hidden).textOverflow(_.ellipsis)
  * .whiteSpace(_.noWrap))("a long title")` the text has `TextFlow(Collapse,
  * Off, Ellipsis)`: one line, "…" at the cut. A text in a plain column has
  * `TextFlow.column`, in a plain row `TextFlow.row` (the old rule).
  */
// claude 2026-10-19: created (L1a)
private[tui4s] final case class TextFlow(space: Space, wrap: Wrapping, cut: Cut)

private[tui4s] object TextFlow:
    /** A text in a column with no text-flow property: it wraps. */
    val column: TextFlow = TextFlow(Space.Keep, Wrapping.Split, Cut.Clip)

    /** A text in a row with no text-flow property: it does not wrap. */
    val row: TextFlow = TextFlow(Space.Keep, Wrapping.Off, Cut.Clip)

    /** The flow of a text whose element has the props `p`. White space:
      * `normal` and `noWrap` collapse, `preLine` keeps the line breaks, `pre`,
      * `preWrap` and no property keep all. Wrap: never with `noWrap` or `pre`;
      * else in a column, or in a row of a definite `width` (CSS wraps a text in
      * a box of a definite width), with the word break of `textWrap`. Cut: the
      * ellipsis of `textOverflow` on an element with `overflowX: hidden`.
      */
    def of(p: Props): TextFlow =
        val ws    = p.text.whiteSpace
        val space = ws match
            case Some(WhiteSpace.normal) | Some(WhiteSpace.noWrap) =>
              Space.Collapse
            case Some(WhiteSpace.preLine)                          => Space.KeepBreaks
            case _                                                 => Space.Keep
        val wraps = ws match
            case Some(WhiteSpace.noWrap) | Some(WhiteSpace.pre) => false
            case _                                              =>
              p.dir == Dir.Column || p.width.exists {
                case Length.Auto | Length.Calc(_) => false
                case _                            => true
              }
        val wrap  =
          if !wraps then Wrapping.Off
          else
              p.text.breaks match
                  case WordBreak.Split => Wrapping.Split
                  case WordBreak.Keep  => Wrapping.Whole
        val cut   =
          if p.textOverflow == TextOverflow.ellipsis &&
              p.overflowX == Overflow.hidden
          then Cut.Ellipsis
          else Cut.Clip
        TextFlow(space, wrap, cut)
    end of
end TextFlow

/** One laid-out box. `rect` is the border box; margins are outside. `view` is
  * the scroll state of a viewport (an element with an axis that is not
  * `overflow: visible`), and none for any other node.
  */
/** The paint layer of a positioned element (CSS `position` other than
  * `static`). `z` is its `zIndex` (0 when none; kyo-ui's `dropdown` gives 50).
  * `cb` is the path of its containing block, the node that places it: its owner
  * for `relative` and `sticky`, the nearest positioned ancestor for `absolute`
  * (the screen when none), the screen for `fixed`.
  */
// claude 2026-10-19: created (L1b)
private[tui4s] final case class Layer(pos: Pos, z: Int, cb: Loc)

// claude 2026-10-19: changed: the view of a viewport, and its port (L1a)
private[tui4s] final case class Node(
    path: Loc,
    kind: Kind,
    rect: Rect,
    props: Props,
    bgUnder: Option[JColor],
    natural: Size,
    availW: Int,
    kids: Vector[Loc],
    owner: Option[Loc],
    index: Int,
    cols: Vector[Int],
    view: Option[View] = None,
    // claude 2026-10-19: created (L1b): the layer of a positioned element
    layer: Option[Layer] = None,
):
    def content: Rect             = rect.inset(props.insets)
    def bgForKids: Option[JColor] = props.bg.orElse(bgUnder)

    /** The cells that show the kids: the port of a viewport, else the content
      * box. The painter clips the kids to it.
      */
    def port: Rect = view.fold(content)(_.port)
end Node

/** The laid-out screen: a flat, immutable index of nodes, plus the container
  * that owns the items of each region.
  *
  * Invariant (I1): the nodes at or under the path of a node are the nodes that
  * its `kids` reach. A kid path extends the path of its owner, and the items of
  * one container are prefix-free (`flatten` stops at an element or a text).
  * `layoutAll` and `relayout` keep the invariant.
  */
// claude 2026-10-19: changed: the paths of the layers (L1b)
private[tui4s] final case class Laid(
    nodes: Map[Loc, Node],
    regionOwner: Map[Loc, Loc],
    layers: Set[Loc] = Set.empty,
):
    /** The screen with `n` at its path: the node, and the path in `layers` when
      * `n` has a layer.
      */
    // claude 2026-10-19: created (L1b)
    def put(n: Node): Laid =
      copy(
        nodes = nodes.updated(n.path, n),
        layers =
          if n.layer.isDefined then layers + n.path else layers - n.path,
      )

    /** The screen without the node at `prefix`, its subtree and the regions
      * under it. The walk follows `kids`, so it visits only the subtree, not
      * the whole node map (I1 makes it equal to a filter of the paths). The
      * region map is still filtered as a whole: it is usually much smaller.
      */
    // claude 2026-10-19: changed: a walk of the subtree through `kids`, not a
    // filter of the whole node map (PF-a, F1)
    def without(prefix: Loc): Laid =
        def subtree(p: Loc): Iterator[Loc] =
          nodes
            .get(p)
            .iterator
            .flatMap(n =>
              Iterator.single(p) ++ n.kids.iterator.flatMap(subtree),
            )
        // claude 2026-10-19: changed: the layers under the prefix go too (L1b)
        Laid(
          nodes -- subtree(prefix),
          regionOwner.filterNot((r, o) =>
            r.isAtOrUnder(prefix) || o.isAtOrUnder(prefix),
          ),
          layers.filterNot(_.isAtOrUnder(prefix)),
        )
    end without

    /** The screen with the node at `prefix` and its subtree moved by `dx`
      * columns and `dy` rows: their rects, and the ports and the indicators of
      * the viewports among them. A scroll moves the kids of a viewport so: a
      * layout does not depend on the position of a box. An absolute or fixed
      * element whose containing block is not at or under `prefix` does not
      * move, with its subtree: its containing block did not move.
      */
    // claude 2026-10-19: changed: an element out of the flow stays with its
    // containing block (L1b)
    def shift(prefix: Loc, dx: Int, dy: Int): Laid =
        def move(r: Rect): Rect                             = Rect(r.x + dx, r.y + dy, r.w, r.h)
        def go(acc: Map[Loc, Node], p: Loc): Map[Loc, Node] =
          nodes.get(p).fold(acc) { n =>
            val stays = p != prefix && n.layer
              .exists(l => l.pos.outOfFlow && !l.cb.isAtOrUnder(prefix))
            if stays then acc
            else
                val moved = n.copy(
                  rect = move(n.rect),
                  view = n.view.map(v =>
                    v.copy(port = move(v.port), bar = v.bar.map(move)),
                  ),
                )
                n.kids.foldLeft(acc.updated(p, moved))(go)
            end if
          }
        copy(nodes = go(nodes, prefix))
    end shift
end Laid

private[tui4s] object Laid:
    val empty: Laid = Laid(Map.empty, Map.empty)

/** A change of the screen that paint must apply: clear a rectangle to a
  * background, or paint a node and its subtree.
  */
// claude 2026-10-19: changed: the region of a layer (L1b)
private[tui4s] enum Damage:
    case Clear(rect: Rect, bg: Option[JColor])
    case Paint(path: Loc)

    /** The cells of `rect` are painted again from the flow and from the layers,
      * in paint order: the old place or the new place of a layer that moved,
      * came or went ([[Layout.layerDamage]]).
      */
    case Region(rect: Rect)
end Damage

/** A flex item of a container: an element or a text, with the regions it is
  * inside (innermost first).
  */
private[tui4s] final case class Item(path: Loc, ui: UI, regions: List[Loc])

private[tui4s] final case class Flat(items: Vector[Item], regions: Vector[Loc]):
    def ++(o: Flat): Flat = Flat(items ++ o.items, regions ++ o.regions)

private[tui4s] object Flat:
    val empty: Flat = Flat(Vector.empty, Vector.empty)

/** The offsets of a positioned element in cells (`top`, `right`, `bottom`,
  * `left`), against a box of `w` x `h` cells: a percentage of `left` and
  * `right` is of `w`, of `top` and `bottom` of `h`. None is `auto`.
  */
// claude 2026-10-19: created (L1b)
private[tui4s] final case class Offsets(
    t: Option[Int],
    r: Option[Int],
    b: Option[Int],
    l: Option[Int],
):
    /** The width between the two horizontal offsets in a box `w` cells wide,
      * when both are given.
      */
    def stretchW(w: Int): Option[Int] =
      for a <- l; c <- r yield (w - a - c).max(0)

    /** The height between the two vertical offsets in a box `h` cells high,
      * when both are given.
      */
    def stretchH(h: Int): Option[Int] =
      for a <- t; c <- b yield (h - a - c).max(0)
end Offsets

private[tui4s] object Offsets:
    val none: Offsets = Offsets(None, None, None, None)

    /** The offsets of `p` in a box of `w` x `h` cells. */
    def cells(p: Props, w: Int, h: Int, env: Scene): Offsets =
        def hor(l: Length): Option[Int] = Props.cells(l, w, env.screen.h)
        def ver(l: Length): Option[Int] = l match
            case Length.Pct(v) => Some((v * h / 100).toInt)
            case _             => Props.cells(l, 0, env.screen.h)
        Offsets(
          p.inset.top.flatMap(ver),
          p.inset.right.flatMap(hor),
          p.inset.bottom.flatMap(ver),
          p.inset.left.flatMap(hor),
        )
    end cells

    /** The offsets that size an element: those of an element out of the flow
      * (absolute, fixed), none for any other element.
      */
    def of(p: Props, w: Int, h: Int, env: Scene): Offsets =
      if p.position.outOfFlow then cells(p, w, h, env) else none
end Offsets

/** Flexbox in terminal cells, following kyo-ui's base CSS (everything is flex,
  * reactive boundaries are transparent).
  */
private[tui4s] object Layout:

    // ---- text ----

    def textWidth(s: String): Int = Span.raw(s).width()

    /** The open line of a wrap: its words and its width in cells. */
    private final case class Line(words: Vector[String], w: Int):
        def add(word: String, ww: Int): Line = Line(words :+ word, w + 1 + ww)
        def text: String                     = words.mkString(" ")

    /** The state of a wrap: the closed lines and the open line. */
    private final case class Wrap(done: Vector[String], open: Option[Line]):
        def close: Wrap = Wrap(done ++ open.map(_.text), None)

    /** The lines of `text` in `width` cells (at least 1). Greedy: a word goes
      * on the open line if it fits, else it opens a new line; a word wider than
      * the width is split by [[chunksByWidth]]. One fold carries the width of
      * the open line. The width of `a + " " + b` is `textWidth(a) + 1 +
      * textWidth(b)` (jatatui adds the widths of the code points), so each word
      * is measured once.
      */
    // claude 2026-10-19: changed: one fold that carries the width of the open
    // line (PF-a, F4); the split of a long word is a parameter (L1a)
    def wrap(text: String, width: Int): Vector[String] =
      wrapWords(text, width, Wrapping.Split)

    /** The lines of `text` in `width` cells, as [[wrap]] gives them, but a word
      * wider than the width stays whole on a line of its own (CSS
      * `overflow-wrap: normal`).
      */
    // claude 2026-10-19: created (L1a)
    def wrapWhole(text: String, width: Int): Vector[String] =
      wrapWords(text, width, Wrapping.Whole)

    /** The greedy wrap of [[wrap]]; a word wider than the width is split by
      * [[chunksByWidth]] when `long` is `Wrapping.Split`, and stays whole when
      * it is `Wrapping.Whole`.
      */
    // claude 2026-10-19: created (L1a): the old body of wrap
    private def wrapWords(
        text: String,
        width: Int,
        long: Wrapping,
    ): Vector[String] =
        val w = width.max(1)
        text.split("\n", -1).toVector.flatMap { para =>
          val words = para.split(" ", -1)
          val lines = words
            .foldLeft(Wrap(Vector.empty, None)) { (acc, word) =>
              val ww = textWidth(word)
              acc.open match
                  case Some(open) if open.w + 1 + ww <= w     =>
                    acc.copy(open = Some(open.add(word, ww)))
                  case _ if ww <= w || long == Wrapping.Whole =>
                    acc.close.copy(open = Some(Line(Vector(word), ww)))
                  case _                                      =>
                    val (chunks, lastW) = chunksByWidth(word, w)
                    Wrap(
                      acc.close.done ++ chunks.dropRight(1),
                      chunks.lastOption.map(c => Line(Vector(c), lastW)),
                    )
              end match
            }
            .close
            .done
          if lines.isEmpty then Vector("") else lines
        }
    end wrapWords

    /** Splits a word into pieces of at most `w` cells, and gives the width of
      * the last piece. The split is at a code point border: a wide character or
      * a surrogate pair does not split, and a code point wider than `w` is a
      * piece alone. One walk over the code points carries the start and the
      * width of the open piece.
      */
    // claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:114)
    // claude 2026-10-19: changed: one walk that carries the width of the open
    // piece (PF-a, F4)
    private def chunksByWidth(word: String, w: Int): (Vector[String], Int) =
        @annotation.tailrec
        def go(
            i: Int,
            start: Int,
            cw: Int,
            done: Vector[String],
        ): (Vector[String], Int) =
          if i >= word.length then
              if i > start then (done :+ word.substring(start, i), cw)
              else (done, 0)
          else
              val cp = word.codePointAt(i)
              val n  = Character.charCount(cp)
              val gw = textWidth(String(Character.toChars(cp)))
              if i == start then go(i + n, start, gw, done)
              else if cw + gw <= w then go(i + n, start, cw + gw, done)
              else go(i + n, i, gw, done :+ word.substring(start, i))
        go(0, 0, 0, Vector.empty)
    end chunksByWidth

    /** The lines of `text` in `width` cells with the flow `flow`: its white
      * space first ([[Space]]), then its wrap ([[Wrapping]]).
      */
    // claude 2026-10-19: changed: the text flow, not a Boolean (L1a)
    def textLines(text: String, width: Int, flow: TextFlow): Vector[String] =
        val t = flow.space match
            case Space.Keep       => text
            case Space.Collapse   => collapse(text)
            case Space.KeepBreaks =>
              text.split("\n", -1).map(collapse).mkString("\n")
        flow.wrap match
            case Wrapping.Off   => t.split("\n", -1).toVector
            case Wrapping.Split => wrap(t, width)
            case Wrapping.Whole => wrapWhole(t, width)
    end textLines

    /** A run of white space (spaces, tabs, line breaks). */
    // claude 2026-10-19: created (L1a)
    private val whiteRun: java.util.regex.Pattern =
      java.util.regex.Pattern.compile("[ \t\n\r\f]+")

    /** `s` with each run of white space as one space, and none at the two ends
      * (the white space of a flex item collapses, as in CSS).
      */
    // claude 2026-10-19: created (L1a)
    def collapse(s: String): String =
      whiteRun.matcher(s).replaceAll(" ").trim

    // ---- flattening: what are the flex items of a container ----

    /** A tag of a `RawHtml` value; the runner shows the text without tags. */
    // claude 2026-10-19: created (PF-a, F4): compiled once, not per flatten
    private val htmlTag: java.util.regex.Pattern =
      java.util.regex.Pattern.compile("<[^>]*>")

    // claude 2026-10-19: changed: the steps of Loc (P2, A6)
    def flatten(ui: UI, path: Loc, regions: List[Loc], env: Scene): Flat =
      ui match
          case t: Text          => Flat(Vector(Item(path, t, regions)), Vector.empty)
          // claude 2026-10-19: changed: the tag pattern is compiled once
          // (PF-a, F4)
          case r: RawHtml       =>
            Flat(
              Vector(
                Item(
                  path,
                  Text(htmlTag.matcher(r.value).replaceAll("")),
                  regions,
                ),
              ),
              Vector.empty,
            )
          case e: Element       =>
            if Props.isHidden(e) then Flat.empty
            else Flat(Vector(Item(path, e, regions)), Vector.empty)
          case f: Fragment[?]   =>
            f.children.toVector.zipWithIndex.foldLeft(Flat.empty) {
              case (acc, (c, i)) =>
                acc ++ flatten(c, path.child(i), regions, env)
            }
          case _: Reactive[?]   =>
            val here = Flat(Vector.empty, Vector(path))
            env.content(path) match
                case Some(Content.Single(c)) =>
                  here ++ flatten(c, path.content, path :: regions, env)
                case _                       => here
          case _: Foreach[?, ?] =>
            val here = Flat(Vector.empty, Vector(path))
            env.content(path) match
                case Some(Content.Many(items)) =>
                  items.foldLeft(here) { case (acc, (key, c)) =>
                    acc ++ flatten(c, path.item(key), path :: regions, env)
                  }
                case _                         => here
          case _                => Flat.empty

    /** The flex items of an element's children. A table's rows are its `tr`
      * children, also inside `tbody`.
      */
    // claude 2026-10-19: changed: the steps of Loc (P2, A6)
    def itemsOf(el: Element, path: Loc, env: Scene): Flat =
      el match
          case _: Select | _: Dropdown => Flat.empty /* painted as a picker,
             * options are not items */
          case _                       =>
            el.children.toVector.zipWithIndex.foldLeft(Flat.empty) {
              case (acc, (c, i)) =>
                acc ++ flatten(c, path.child(i), Nil, env)
            }

    // ---- intrinsic sizes of the void and picker elements ----

    private def optionLabels(el: Element): Vector[String] = el match
        case s: Select   =>
          s.children.toVector.collect { case o: Opt =>
            o.children.toVector.collect { case t: Text => t.value }.mkString
          }
        case d: Dropdown => d.options.toVector.map(_._2)
        case _           => Vector.empty

    def intrinsic(el: Element): Option[Size] = el match
        case _: Checkbox | _: Radio                      => Some(Size(3, 1))
        case _: Textarea                                 => Some(Size(30, 3))
        case _: RangeInput                               => Some(Size(12, 1))
        case _: DateInput | _: TimeInput | _: ColorInput => Some(Size(12, 1))
        case _: TextInput                                => Some(Size(20, 1))
        case _: FileInput                                => Some(Size(8, 1))
        case _: Select | _: Dropdown                     =>
          Some(
            Size(optionLabels(el).map(textWidth).maxOption.getOrElse(0) + 2, 1),
          )
        case _: Hr                                       => Some(Size(1, 1))
        case _: Br                                       => Some(Size(0, 1))
        case i: Img                                      => Some(Size(textWidth(s"[${i.alt.getOrElse("img")}]"), 1))
        case _: Iframe                                   => Some(Size(8, 1))
        case _: SvgNode                                  => Some(Size(7, 1))
        case _                                           => None

    // ---- measure ----

    final case class Measured(item: Item, props: Props, size: Size)

    private def clamp(v: Int, min: Option[Int], max: Option[Int]): Int =
      max.fold(v)(v.min).max(min.getOrElse(0))

    def measureItem(
        item: Item,
        availW: Int,
        availH: Option[Int],
        parent: Props,
        env: Scene,
        listStyle: Option[ListStyle],
        index: Int,
    ): Measured =
      item.ui match
          // claude 2026-10-19: changed: the text flow of the parent; a text
          // that does not wrap keeps its width in a viewport that scrolls
          // horizontally (L1a)
          case t: Text    =>
            val flow  = TextFlow.of(parent)
            val lines = textLines(parent.text(t.value), availW, flow)
            val w     = lines.map(textWidth).maxOption.getOrElse(0)
            val keep  =
              flow.wrap == Wrapping.Off && Props.scrolls(parent.overflowX)
            Measured(
              item,
              Props.neutral(parent.text),
              Size(if keep then w else w.min(availW.max(0)), lines.size),
            )
          case e: Element =>
            val p = Props.resolve(
              e,
              parent.text,
              env.focus.contains(item.path),
              listStyle,
              index,
            )
            Measured(
              item,
              p,
              measureElement(
                e,
                item.path,
                p,
                availW - p.margin.h,
                availH.map(h => (h - p.margin.v).max(0)),
                env,
              ),
            )
          case _          => Measured(item, Props.neutral(parent.text), Size.zero)

    /** @param availH
      *   the height of the containing block when it is definite: a percentage
      *   height is relative to it. `None` makes a percentage height `auto`, as
      *   in CSS.
      */
    def measureElement(
        e: Element,
        path: Loc,
        p: Props,
        availW: Int,
        availH: Option[Int],
        env: Scene,
    ): Size =
        val ins                            = p.insets
        val fixedW                         = p.width.flatMap(Props.cells(_, availW, env.screen.h))
        /* claude 2026-10-12: changed (PR #10 review: Codex, Layout.scala:264):
         * a */
        // percentage height was relative to the screen
        def cellsH(l: Length): Option[Int] = l match
            case Length.Pct(v) => availH.map(h => (v * h / 100).toInt)
            case _             => Props.cells(l, 0, env.screen.h)
        val fixedH0                        = p.height.flatMap(cellsH)
        // claude 2026-10-19: created (L1b): an element out of the flow gets
        // `availW` and `availH` from its containing block; its offsets take
        // their cells from them, and two opposite offsets give its size
        val off                            = Offsets.of(p, availW, availH.getOrElse(0), env)
        val fixedW1                        = fixedW.orElse(off.stretchW(availW))
        val fixedH                         = fixedH0.orElse(availH.flatMap(off.stretchH))
        val inner                          =
          (fixedW1.getOrElse(availW - off.l.getOrElse(0) - off.r.getOrElse(0)) -
            ins.h).max(0)
        val innerH                         = fixedH.map(h => (h - ins.v).max(0))
        val content: Size                  = intrinsic(e).getOrElse {
          e match
              case _: Table => tableSize(e, path, p, inner, env)._2
              case _        =>
                val flat     = itemsOf(e, path, env)
                val measured = flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, innerH, p, env, p.listStyle, i),
                )
                // claude 2026-10-19: changed: the heights of `settle` (L1a
                // review); the items in the flow only (L1b)
                combine(
                  p,
                  settle(p, inner, measured).filterNot(outOfFlow),
                  p.dir match
                      case Dir.Row    => Some(inner)
                      case Dir.Column => innerH,
                )
        }
        val w                              = fixedW1.getOrElse(content.w + ins.h)
        val h                              = fixedH.getOrElse(content.h + ins.v)
        Size(
          clamp(
            w,
            p.minW.flatMap(Props.cells(_, availW, env.screen.h)),
            p.maxW.flatMap(Props.cells(_, availW, env.screen.h)),
          ),
          clamp(h, p.minH.flatMap(cellsH), p.maxH.flatMap(cellsH)),
        )
    end measureElement

    /** The natural content size of a container from its measured items (margins
      * and gaps included). A container that wraps (`flexWrap(wrap)`) and has a
      * main size `main` (its content width for a row, its definite content
      * height for a column) is as long as its longest line on the main axis,
      * and its lines with the gap between them on the cross axis.
      */
    // claude 2026-10-19: changed: the lines of a container that wraps (L1b)
    def combine(
        p: Props,
        items: Vector[Measured],
        main: Option[Int] = None,
    ): Size =
      if items.isEmpty then Size.zero
      else
          main.filter(_ => p.wraps) match
              case Some(m) =>
                val ls    = lines(p.dir, m, p.gap, items)
                val mains = ls.map(l => lineMain(p.dir, p.gap, l))
                val cross = ls.map(l => lineCross(p.dir, l)).sum +
                  p.gap * (ls.size - 1)
                p.dir match
                    case Dir.Row    => Size(mains.max, cross)
                    case Dir.Column => Size(cross, mains.max)
              case None    =>
                val outer = items.map(m =>
                  Size(m.size.w + m.props.margin.h, m.size.h + m.props.margin.v),
                )
                val gaps  = p.gap * (items.size - 1)
                p.dir match
                    case Dir.Column =>
                      Size(outer.map(_.w).max, outer.map(_.h).sum + gaps)
                    case Dir.Row    =>
                      Size(outer.map(_.w).sum + gaps, outer.map(_.h).max)

    /** The cells of an item on the main axis of `dir`, its margins too. */
    // claude 2026-10-19: created (L1b)
    private def outerMain(dir: Dir, m: Measured): Int = dir match
        case Dir.Row    => m.size.w + m.props.margin.h
        case Dir.Column => m.size.h + m.props.margin.v

    /** The cells of an item on the cross axis of `dir`, its margins too. */
    // claude 2026-10-19: created (L1b)
    private def outerCross(dir: Dir, m: Measured): Int = dir match
        case Dir.Row    => m.size.h + m.props.margin.v
        case Dir.Column => m.size.w + m.props.margin.h

    /** The main cells of a line: its items and the gaps between them. */
    // claude 2026-10-19: created (L1b)
    def lineMain(dir: Dir, gap: Int, line: Vector[Measured]): Int =
      line.map(outerMain(dir, _)).sum + gap * (line.size - 1).max(0)

    /** The cross cells of a line: the cross size of its tallest item. */
    // claude 2026-10-19: created (L1b)
    def lineCross(dir: Dir, line: Vector[Measured]): Int =
      line.map(outerCross(dir, _)).maxOption.getOrElse(0)

    /** The flex lines of `items` in a main size of `main` cells (CSS
      * `flex-wrap: wrap`): greedy, in the order of the items, an item goes on
      * the open line when it fits there with the gap, else it opens the next
      * line. A line has at least one item, so an item longer than `main` is a
      * line of its own. Law: a line of two or more items is not longer than
      * `main`.
      */
    // claude 2026-10-19: created (L1b)
    def lines(
        dir: Dir,
        main: Int,
        gap: Int,
        items: Vector[Measured],
    ): Vector[Vector[Measured]] =
      items
        .foldLeft(Vector.empty[(Vector[Measured], Int)]) { (acc, m) =>
          val s = outerMain(dir, m)
          acc.lastOption match
              case Some((line, used)) if used + gap + s <= main =>
                acc.updated(acc.size - 1, (line :+ m, used + gap + s))
              case _                                            => acc :+ (Vector(m), s)
        }
        .map(_._1)

    // ---- tables ----

    /** The rows of `table` (also those inside a `Tbody`), each with its `Tr`. */
    // claude 2026-10-19: changed: each row with its Tr, no cast (purity plan,
    // P1)
    private def rowsOf(
        table: Element,
        path: Loc,
        env: Scene,
    ): Vector[(Item, Tr)] =
        def row(it: Item): Vector[(Item, Tr)] = it.ui match
            case tr: Tr => Vector((it, tr))
            case _      => Vector.empty
        itemsOf(table, path, env).items.flatMap { it =>
          it.ui match
              case tb: Tbody => itemsOf(tb, it.path, env).items.flatMap(row)
              case _         => row(it)
        }
    end rowsOf

    /** Column widths and the table's content size. Cells are one row high;
      * columns are one cell apart.
      */
    def tableSize(
        table: Element,
        path: Loc,
        p: Props,
        inner: Int,
        env: Scene,
    ): (Vector[Int], Size) =
        val rows  = rowsOf(table, path, env)
        // claude 2026-10-19: changed: the Tr of rowsOf, no cast (P1)
        val cells = rows.map { (row, tr) =>
          val rp = Props.resolve(tr, p.text, false, None, 0)
          itemsOf(tr, row.path, env).items.zipWithIndex.map((c, i) =>
            measureItem(c, inner, None, rp, env, None, i).size.w,
          )
        }
        val ncols = cells.map(_.size).maxOption.getOrElse(0)
        val cols  = Vector.tabulate(ncols)(j =>
          cells.flatMap(_.lift(j)).maxOption.getOrElse(0),
        )
        (cols, Size(cols.sum + (ncols - 1).max(0), rows.size))
    end tableSize

    // ---- arrange: flex positions of measured items inside a content rect ----

    /** The main size of each flex item of a line of `mainSize` cells, in the
      * order of `items`: the free cells go to the items that grow, and a
      * deficit comes from the items that shrink, by their weights.
      */
    // claude 2026-10-19: created (L1a review): moved out of `arrange`, so
    // that `settle` gives a text the width that `arrange` gives it
    private def flexSizes(
        dir: Dir,
        mainSize: Int,
        gap: Int,
        items: Vector[Measured],
    ): Vector[Int] =
        val column                  = dir == Dir.Column
        def mainOf(s: Size): Int    = if column then s.h else s.w
        def mMain(m: Measured): Int =
          if column then m.props.margin.v else m.props.margin.h
        val gaps                    = gap * (items.size - 1)
        val base                    = items.map(m => mainOf(m.size))
        val used                    = base.sum + items.map(mMain).sum + gaps
        val free                    = mainSize - used
        val grows                   = items.map(_.props.grow)
        if free > 0 && grows.sum > 0 then
            val shares   = grows.map(g => (free * g / grows.sum).toInt)
            val rest     = free - shares.sum
            val lastGrow = grows.lastIndexWhere(_ > 0)
            base.zip(shares).zipWithIndex.map { case ((b, s), i) =>
              b + s + (if i == lastGrow then rest else 0)
            }
        else if free < 0 then
            // In a column, an item without an explicit height does not
            // shrink below its content height (CSS `min-height: auto` of
            // flex items); the owner clips the overflow. Before, a list
            // taller than the screen shrank EVERY item (header, input,
            // rows) to 0 rows.
            // claude 2026-10-19: changed: a viewport (an item whose
            // vertical overflow is not visible) has no floor, as CSS
            // gives a scroll container `min-height: 0` (L1a)
            val floor   = items
              .zip(base)
              .map((m, b) =>
                if column && m.props.height.isEmpty &&
                    m.props.overflowY == Overflow.visible
                then b
                else 0,
              )
            val weights = items.zip(base).zip(floor).map { case ((m, b), f) =>
              if b > f then m.props.shrink * b else 0.0
            }
            val total   = weights.sum
            if total <= 0 then base
            else
                /* claude 2026-10-12: changed (PR #10 review: Codex,
                 * Layout.scala:399): */
                // the cuts add up to the deficit, not more. Each item
                // loses the whole part of its share; the rest goes one
                // cell at a time to the items with the largest fraction
                // (the last item first when equal), never below the floor.
                val deficit = -free
                val exact   = weights.map(w => deficit * w / total)
                val whole   = exact.map(_.toInt)
                val room    = base.zip(whole).zip(floor).map { case ((b, c), f) =>
                  (b - c - f).max(0)
                }
                val order   = exact
                  .zip(whole)
                  .zipWithIndex
                  .map { case ((e, c), i) => (e - c, i) }
                  .sortBy { case (frac, i) => (-frac, -i) }
                  .map(_._2)
                val extra   = order
                  .foldLeft(
                    (Vector.fill(items.size)(0), deficit - whole.sum),
                  ) { case ((acc, left), i) =>
                    if left > 0 && room(i) > 0 then
                        (acc.updated(i, 1), left - 1)
                    else (acc, left)
                  }
                  ._1
                base.zip(whole.zip(extra)).zip(floor).map {
                  case ((b, (c, x)), f) => (b - c - x).max(f).max(0)
                }
            end if
        else base
        end if
    end flexSizes

    /** The items of a row after its flex sizes. A text that wraps (a row of a
      * definite `width`, see [[TextFlow.of]]) and that the row shrinks gets the
      * height of its lines at its new width, as CSS lays out a flex item at its
      * resolved main size. So the row is as high as its lines, and no line is
      * lost. The width of the item stays: `arrange` gives it the same size
      * again. A row that scrolls horizontally does not shrink its items.
      *
      * For example, in `div.style(_.row.width(10.px))("aaaa bbbb cccc",
      * span("XYZ"))` the text is 9 cells wide and 2 lines high at 10 cells; the
      * row shrinks it to 8 cells, so it is 3 lines high.
      */
    // claude 2026-10-19: created (L1a review)
    def settle(
        p: Props,
        inner: Int,
        items: Vector[Measured],
    ): Vector[Measured] =
        val flow = TextFlow.of(p)
        // claude 2026-10-19: changed: a row that wraps breaks its items into
        // lines and does not shrink them (L1b)
        if p.dir != Dir.Row || flow.wrap == Wrapping.Off ||
            p.overflowX != Overflow.visible || p.wraps
        then items
        else
            // claude 2026-10-19: changed: only the items in the flow share the
            // row (L1b)
            val inFlow = items.filterNot(outOfFlow)
            val order  = if p.reverse then inFlow.reverse else inFlow
            val sizes  = flexSizes(Dir.Row, inner, p.gap, order)
            val size   = inFlow
              .map(_.item.path)
              .zip(if p.reverse then sizes.reverse else sizes)
              .toMap
            items.map { m =>
              (m.item.ui, size.get(m.item.path)) match
                  case (t: Text, Some(s)) if s < m.size.w =>
                    m.copy(size =
                      m.size.copy(h = textLines(p.text(t.value), s, flow).size),
                    )
                  case _                                  => m
            }
        end if
    end settle

    /** True when the item is out of the flex flow: an element with `position`
      * absolute or fixed (also kyo-ui's `overlay` and `dropdown`).
      */
    // claude 2026-10-19: created (L1b)
    def outOfFlow(m: Measured): Boolean = m.props.position.outOfFlow

    // claude 2026-10-19: changed: the flex lines of a container that wraps
    // (L1b)
    def arrange(
        p0: Props,
        box: Rect,
        items0: Vector[Measured],
        cols: Vector[Int],
    ): Vector[Rect] =
      if items0.isEmpty then Vector.empty
      else if p0.wraps && cols.isEmpty then wrapped(p0, box, items0)
      else if cols.nonEmpty then
          // a table row: fixed column widths
          items0.zipWithIndex.map { (m, j) =>
            val x = box.x + cols.take(j).sum + j
            Rect(
              x,
              box.y,
              cols.lift(j).getOrElse(m.size.w),
              1.max(m.size.h).min(box.h),
            )
          }
      else
          /* claude 2026-10-12: changed (PR #10 review: Codex, Props.scala:303):
           * a */
          // reversed container (`rowReverse`, `columnReverse`) places its
          // items in the opposite order, with the main start at the far end
          // (so `justify: start` packs them there, as in CSS). The rects
          // come back in the items' own order.
          val items                    = if p0.reverse then items0.reverse else items0
          val p                        =
            if !p0.reverse then p0
            else
                p0.copy(justify = p0.justify match
                    case Justification.start => Justification.end
                    case Justification.end   => Justification.start
                    case j                   => j)
          val column                   = p.dir == Dir.Column
          def crossOf(s: Size): Int    = if column then s.w else s.h
          def mMain(m: Measured): Int  =
            if column then m.props.margin.v else m.props.margin.h
          def mCross(m: Measured): Int =
            if column then m.props.margin.h else m.props.margin.v
          val mainSize                 = if column then box.h else box.w
          val crossSize                = if column then box.w else box.h
          val gaps                     = p.gap * (items.size - 1)
          val grows                    = items.map(_.props.grow)
          // claude 2026-10-19: changed: the main sizes of flexSizes (L1a review)
          val sizes                    = flexSizes(p.dir, mainSize, p.gap, items)
          val leftover                 =
            (mainSize - sizes.sum - items.map(mMain).sum - gaps).max(0)
          val (start, between)         =
            if grows.sum > 0 then (0, 0)
            else
                p.justify match
                    case Justification.center       => (leftover / 2, 0)
                    case Justification.end          => (leftover, 0)
                    case Justification.spaceBetween =>
                      (
                        0,
                        if items.size > 1 then leftover / (items.size - 1)
                        else 0,
                      )
                    case Justification.spaceAround  =>
                      (leftover / (items.size * 2), leftover / items.size)
                    case Justification.spaceEvenly  =>
                      (leftover / (items.size + 1), leftover / (items.size + 1))
                    case Justification.start        => (0, 0)
          val offsets                  = sizes.zip(items).scanLeft(start) { case (acc, (s, m)) =>
            acc + s + mMain(m) + p.gap + between
          }
          val rects                    = items.zip(sizes).zip(offsets).map { case ((m, s), off) =>
            val mainPos       =
              off + (if column then m.props.margin.t else m.props.margin.l)
            val crossRoom     = (crossSize - mCross(m)).max(0)
            /* a left-aligned text paints only its glyphs: it does not stretch
             * (smaller damage when it changes) */
            // claude 2026-10-19: changed: a match on Text (purity plan, P1)
            val isText        = m.item.ui match
                case _: Text => true
                case _       => false
            val textNoStretch =
              isText && p.text.align == kyo.Style.TextAlign.left
            val fixedCross    =
              textNoStretch || (if column then m.props.width.isDefined
                                else m.props.height.isDefined)
            val align         = m.props.alignSelf.getOrElse(p.align)
            val crossLen      =
              if align == Alignment.stretch && !fixedCross then crossRoom
              else crossOf(m.size).min(crossRoom)
            val crossLead     =
              if column then m.props.margin.l else m.props.margin.t
            val crossPos      = crossLead + (align match
                case Alignment.center => (crossRoom - crossLen) / 2
                case Alignment.end    => crossRoom - crossLen
                case _                => 0)
            if column then Rect(box.x + crossPos, box.y + mainPos, crossLen, s)
            else Rect(box.x + mainPos, box.y + crossPos, s, crossLen)
          }
          if p0.reverse then rects.reverse else rects
    end arrange

    /** The rects of the items of a container that wraps: its [[lines]] in its
      * main size, each line arranged as a flex line (grow, shrink, justify,
      * align) in a box of the cross size of its tallest item. The lines stack
      * from the cross start, the gap between them: kyo-ui has no
      * `alignContent`, so they do not stretch or spread (CSS `align-content:
      * flex-start`). The rects come back in the items' own order.
      */
    // claude 2026-10-19: created (L1b)
    private def wrapped(
        p: Props,
        box: Rect,
        items: Vector[Measured],
    ): Vector[Rect] =
        val main = p.dir match
            case Dir.Row    => box.w
            case Dir.Column => box.h
        val line = p.copy(wrap = kyo.Style.FlexWrap.noWrap)
        lines(p.dir, main, p.gap, items)
          .foldLeft((Vector.empty[Rect], 0)) { case ((acc, at), ls) =>
            val cross = lineCross(p.dir, ls)
            val area  = p.dir match
                case Dir.Row    => Rect(box.x, box.y + at, box.w, cross)
                case Dir.Column => Rect(box.x + at, box.y, cross, box.h)
            (acc ++ arrange(line, area, ls, Vector.empty), at + cross + p.gap)
          }
          ._1
    end wrapped

    // ---- layout: build the nodes of an item and its subtree ----

    final case class Ctx(
        owner: Loc,
        parent: Props,
        bgUnder: Option[JColor],
        availW: Int,
        listStyle: Option[ListStyle],
        cols: Vector[Int],
    )

    /** The items of a node, measured in its content width, plus the regions met
      * while flattening.
      */
    // claude 2026-10-19: changed: the width of the kids is a parameter (the
    // port of a viewport can be narrower than the content box, L1a); the
    // heights of `settle` (L1a review)
    def measuredKids(
        node: Node,
        env: Scene,
        root: UI,
        inner: Int,
    ): (Vector[Measured], Vector[Loc], Vector[Int]) =
        /* claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:264):
         * the height */
        // that the kids' percentages resolve against: the content height when
        // this node's height is definite (the screen, an explicit height, or
        // a size that the flex parent imposed), else none
        val availH: Option[Int] = node.kind match
            case Kind.Screen => Some(node.content.h)
            case _
                if node.props.height.isDefined || node.rect.h != node.natural.h =>
              Some(node.content.h)
            case _           => None
        node.kind match
            case Kind.Screen       =>
              val flat = flatten(root, Loc.root, Nil, env)
              (
                flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, availH, node.props, env, None, i),
                ),
                flat.regions,
                Vector.empty,
              )
            case Kind.El(e: Table) =>
              val flat = itemsOf(e, node.path, env)
              val rows = flat.items.flatMap { it =>
                it.ui match
                    case _: Tr     => Vector(it)
                    case tb: Tbody => itemsOf(tb, it.path, env).items
                    case _         => Vector.empty
              }
              (
                rows.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, availH, node.props, env, None, i),
                ),
                flat.regions,
                Vector.empty,
              )
            case Kind.El(e)        =>
              val flat = itemsOf(e, node.path, env)
              (
                settle(
                  node.props,
                  inner,
                  flat.items.zipWithIndex.map((it, i) =>
                    measureItem(
                      it,
                      inner,
                      availH,
                      node.props,
                      env,
                      node.props.listStyle,
                      i,
                    ),
                  ),
                ),
                flat.regions,
                Vector.empty,
              )
            case Kind.Txt(_, _)    => (Vector.empty, Vector.empty, Vector.empty)
        end match
    end measuredKids

    /** The kids of a node, measured and arranged: the measured items, the
      * regions met while flattening, their rects, the column widths of a table,
      * the width the kids are measured in, and the view of a viewport.
      */
    // claude 2026-10-19: created (L1a)
    final case class Arranged(
        kids: Vector[Measured],
        regions: Vector[Loc],
        rects: Vector[Rect],
        tableCols: Vector[Int],
        inner: Int,
        view: Option[View],
        // claude 2026-10-19: created the two fields below (L1b): the layer
        // and the width that each kid is measured in
        layers: Vector[Option[Layer]] = Vector.empty,
        avail: Vector[Int] = Vector.empty,
    ):
        /** The layer of the kid `i`. */
        def layerOf(i: Int): Option[Layer] = layers.lift(i).flatten

        /** The width that the kid `i` is measured in. */
        def availOf(i: Int): Int = avail.lift(i).getOrElse(inner)
    end Arranged

    /** Measures and arranges the kids of `node` (which is already positioned).
      * The kids of a box are arranged in its content box. The kids of a
      * viewport (an element with an axis that is not `overflow: visible`) are
      * arranged in the box of its content: on each axis that is not `visible`,
      * the natural size of the kids (at least the size of the port), moved by
      * the scroll offset of `env` (clamped to the content; 0 on an axis that is
      * `hidden`). The painter clips them to the port.
      *
      * The scroll indicator: when the vertical axis scrolls, the content is
      * taller than the port, the port has at least 3 rows and 2 columns, and
      * `scrollbarWidth` is not `none`, the indicator takes the right column of
      * the padding box (as the classic scrollbar of a browser). Only then the
      * content box loses that column, and the kids are measured again in the
      * narrower width.
      */
    // claude 2026-10-19: created (L1a): the one arrange of layoutChildren and
    // relayout, with the indicator column; changed: the kids out of the flow
    // and the positioned kids (L1b, see `positioned`)
    def arrangeKids(
        node: Node,
        env: Scene,
        root: UI,
        find: Loc => Option[Node],
    ): Arranged =
      positioned(node, arrangeFlow(node, env, root), env, find)

    /** The flow arrange of [[arrangeKids]]: the kids out of the flow get an
      * empty rect here.
      */
    // claude 2026-10-19: created (L1b): the old body of arrangeKids
    private def arrangeFlow(node: Node, env: Scene, root: UI): Arranged =
        val box                         = node.content
        // the items in the flow are arranged; a kid out of the flow keeps an
        // empty rect until `positioned` places it (not in a table)
        def arrange(
            p: Props,
            area: Rect,
            ks: Vector[Measured],
            cols: Vector[Int],
        ): Vector[Rect]                 = node.kind match
            case Kind.El(_: Table) => Layout.arrange(p, area, ks, cols)
            case _                 =>
              val flow  = ks.filterNot(outOfFlow)
              val rects = Layout.arrange(p, area, flow, cols).iterator
              ks.map(m => if outOfFlow(m) then Rect.empty else rects.next())
        def colsIn(w: Int): Vector[Int] = node.kind match
            case Kind.El(e: Table) =>
              tableSize(e, node.path, node.props, w, env)._1
            case _                 => Vector.empty
        val viewport                    = node.kind match
            case Kind.El(_) => node.props.clips
            case _          => false
        val (kids0, regions, _)         = measuredKids(node, env, root, box.w)
        if !viewport then
            Arranged(
              kids0,
              regions,
              arrange(node.props, box, kids0, node.cols),
              colsIn(box.w),
              box.w,
              None,
            )
        else
            val p                                           = node.props
            def natural(ks: Vector[Measured], w: Int): Size = node.kind match
                case Kind.El(e: Table) => tableSize(e, node.path, p, w, env)._2
                // claude 2026-10-19: changed: the items in the flow (L1b)
                case _                 =>
                  combine(
                    p,
                    ks.filterNot(outOfFlow),
                    Option.when(p.dir == Dir.Row)(w),
                  )
            val showBar                                     =
              Props.scrolls(p.overflowY) &&
                p.scrollbarWidth != ScrollbarWidth.none && box.h >= 3 &&
                box.w >= 2 && natural(kids0, box.w).h > box.h
            val pad                                         =
              node.rect.inset(p.border.fold(Sides.zero)(_.sides))
            val bar                                         =
              Option.when(showBar)(Rect(pad.right - 1, pad.y, 1, pad.h))
            val port                                        =
              if showBar then box.copy(w = box.w - 1) else box
            val kids                                        =
              if showBar then measuredKids(node, env, root, port.w)._1
              else kids0
            val size                                        = natural(kids, port.w)
            val content                                     = Size(
              if p.overflowX == Overflow.visible then port.w
              else size.w.max(port.w),
              if p.overflowY == Overflow.visible then port.h
              else size.h.max(port.h),
            )
            val view0                                       =
              View(Offset.zero, content, port, bar, p.overflowX, p.overflowY)
            val view                                        =
              view0.copy(offset = view0.clamp(env.scroll(node.path)))
            val o                                           = view.offset
            val inBox                                       =
              Rect(port.x - o.x, port.y - o.y, content.w, content.h)
            Arranged(
              kids,
              regions,
              arrange(p, inBox, kids, node.cols),
              colsIn(port.w),
              port.w,
              Some(view),
            )
        end if
    end arrangeFlow

    /** The kids of `node` after the flow arrange `a`: each positioned kid gets
      * its layer ([[Layer]]), each `relative` kid moves by its offsets, and
      * each absolute or fixed kid is measured and placed against its containing
      * block ([[outOfFlowRect]]). A table places its rows in the flow: their
      * position is ignored. `find` gives the laid-out ancestors (the containing
      * block of an absolute kid can be one of them).
      */
    // claude 2026-10-19: created (L1b)
    private def positioned(
        node: Node,
        a: Arranged,
        env: Scene,
        find: Loc => Option[Node],
    ): Arranged =
      node.kind match
          case Kind.El(_: Table)                                => a
          case _ if !a.kids.exists(_.props.position.positioned) => a
          case _                                                =>
            val area   = a.view.fold(node.content)(_.port)
            val screen = Rect(0, 0, env.screen.w, env.screen.h)
            val placed =
              a.kids.zip(a.rects).zipWithIndex.map { case ((m, r), i) =>
                val p = m.props
                val z = p.z.getOrElse(0)
                p.position match
                    case Pos.Static   => (m, r, None, a.inner)
                    case Pos.Sticky   =>
                      (m, r, Some(Layer(Pos.Sticky, z, node.path)), a.inner)
                    case Pos.Relative =>
                      val o  = Offsets.cells(p, area.w, area.h, env)
                      val dx = o.l.orElse(o.r.map(-_)).getOrElse(0)
                      val dy = o.t.orElse(o.b.map(-_)).getOrElse(0)
                      (
                        m,
                        r.copy(x = r.x + dx, y = r.y + dy),
                        Some(Layer(Pos.Relative, z, node.path)),
                        a.inner,
                      )
                    case pos          =>
                      val cb       =
                        if pos == Pos.Fixed then Loc.screen
                        else containingBlock(node, find)
                      val cbRect   =
                        if cb == node.path then padding(node)
                        else find(cb).fold(screen)(padding)
                      val box      =
                        if cb == Loc.screen then screen else cbRect
                      val (m2, r2) = outOfFlowRect(m, i, node, area, box, env)
                      (m2, r2, Some(Layer(pos, z, cb)), box.w)
                end match
              }
            a.copy(
              kids = placed.map(_._1),
              rects = placed.map(_._2),
              layers = placed.map(_._3),
              avail = placed.map(_._4),
            )
    end positioned

    /** The padding box of a node: its border box inside its border. The
      * containing block of an absolute element is the padding box of its
      * positioned ancestor, as in CSS.
      */
    // claude 2026-10-19: created (L1b)
    def padding(n: Node): Rect =
      n.kind match
          case Kind.Screen => n.rect
          case _           => n.rect.inset(n.props.border.fold(Sides.zero)(_.sides))

    /** The containing block of an absolute kid of `node`: `node` when it is
      * positioned (or the screen), else the nearest positioned ancestor of
      * `node`, else the screen.
      */
    // claude 2026-10-19: created (L1b)
    @annotation.tailrec
    def containingBlock(node: Node, find: Loc => Option[Node]): Loc =
      node.kind match
          case Kind.Screen                         => Loc.screen
          case _ if node.props.position.positioned => node.path
          case _                                   =>
            node.owner.flatMap(find) match
                case Some(o) => containingBlock(o, find)
                case None    => Loc.screen

    /** The measured item and the box of an absolute or fixed kid (the kid `i`
      * of `node`) against its containing block `cb` (a padding box). It is
      * measured in the width of `cb` ([[measureElement]] takes its offsets and
      * its percentages from that width and that height). On each axis: the
      * start offset places it from the start edge of `cb` (`left`, `top`), else
      * the end offset from the end edge (`right`, `bottom`), else it is at the
      * start of the content box `area` of `node` (CSS puts it at its place in
      * the flow; the runner puts it at the start of the box of its parent). Two
      * opposite offsets give its size, else `width` and `height`, else its
      * natural size.
      */
    // claude 2026-10-19: created (L1b)
    private def outOfFlowRect(
        m: Measured,
        i: Int,
        node: Node,
        area: Rect,
        cb: Rect,
        env: Scene,
    ): (Measured, Rect) =
        val m2 = measureItem(
          m.item,
          cb.w,
          Some(cb.h),
          node.props,
          env,
          node.props.listStyle,
          i,
        )
        val mg = m2.props.margin
        val o  = Offsets.cells(m2.props, cb.w - mg.h, cb.h - mg.v, env)
        val sz = m2.size
        val x  = o.l
          .map(cb.x + mg.l + _)
          .orElse(o.r.map(c => cb.right - mg.r - c - sz.w))
          .getOrElse(area.x + mg.l)
        val y  = o.t
          .map(cb.y + mg.t + _)
          .orElse(o.b.map(c => cb.bottom - mg.b - c - sz.h))
          .getOrElse(area.y + mg.t)
        (m2, Rect(x, y, sz.w, sz.h))
    end outOfFlowRect

    /** The cells that the damage inside the node at `from` can name: the ports
      * of the viewports at `from` and above it, up to the first layer, whose
      * box ([[layerBox]]) ends the walk. None when no viewport and no layer
      * holds it (the damage stays as it is).
      */
    // claude 2026-10-19: created (L1a): a viewport clips the damage of its
    // subtree, so a clear of a kid that scrolled out does not touch the cells
    // outside the viewport; changed: a layer clips the damage of its subtree
    // to its box (L1b)
    def damageClip(laid: Laid, from: Option[Loc]): Option[Rect] =
        @annotation.tailrec
        def up(q: Option[Loc], acc: Option[Rect]): Option[Rect] =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                val here = n.view.fold(acc)(v =>
                  Some(acc.fold(v.port)(_.intersect(v.port))),
                )
                if n.layer.isDefined then
                    val box = layerBox(laid, n)
                    Some(here.fold(box)(_.intersect(box)))
                else up(n.owner, here)
        up(from, None)
    end damageClip

    /** The cells that the damage of the node `n` itself can name: the box of a
      * layer, else the [[damageClip]] of its owner.
      */
    // claude 2026-10-19: created (L1b)
    def selfClip(laid: Laid, n: Node): Option[Rect] =
      if n.layer.isDefined then Some(layerBox(laid, n))
      else damageClip(laid, n.owner)

    /** The cells that the layer `n` can paint: its border box inside the clip
      * of its containing block ([[overlayClip]]). The border box of any other
      * node.
      */
    // claude 2026-10-19: created (L1b)
    def layerBox(laid: Laid, n: Node): Rect =
      n.layer.fold(n.rect)(l => n.rect.intersect(overlayClip(laid, l.cb)))

    /** The clip of a layer whose containing block is `cb`: the ports of the
      * viewports at `cb` and above it, and the screen. From a layer the walk
      * goes on at its containing block, so a fixed element escapes every
      * viewport, and an absolute element escapes the viewports above its
      * containing block, as CSS clips an element by the overflow of its chain
      * of containing blocks. A box that is not a viewport does not clip a layer
      * (CSS `overflow: visible`).
      */
    // claude 2026-10-19: created (L1b)
    def overlayClip(laid: Laid, cb: Loc): Rect =
        @annotation.tailrec
        def up(q: Option[Loc], acc: Rect): Rect =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                n.kind match
                    case Kind.Screen => acc.intersect(n.rect)
                    case _           =>
                      up(
                        n.layer.fold(n.owner)(l => Some(l.cb)),
                        n.view.fold(acc)(v => acc.intersect(v.port)),
                      )
        up(Some(cb), Rect(0, 0, Int.MaxValue / 2, Int.MaxValue / 2))
    end overlayClip

    /** The box of the containing block `cb` (its padding box, the screen for
      * the screen), or none when `laid` does not have it.
      */
    // claude 2026-10-19: created (L1b)
    def cbBox(laid: Laid, cb: Loc): Option[Rect] =
      laid.nodes.get(cb).map(padding)

    /** The damage of the layers of a step from `before` to `after`: for each
      * layer whose box or z-index changed, or that came or went, a
      * `Damage.Region` of its old box and of its new box. So the cells that a
      * layer leaves show the flow and the other layers again.
      */
    // claude 2026-10-19: created (L1b)
    def layerDamage(before: Laid, after: Laid): Vector[Damage] =
        def boxes(l: Laid): Map[Loc, (Rect, Int)] =
          l.layers.iterator
            .flatMap(p =>
              l.nodes
                .get(p)
                .map(n => p -> (layerBox(l, n), n.layer.fold(0)(_.z))),
            )
            .toMap
        val b0                                    = boxes(before)
        val b1                                    = boxes(after)
        (b0.keySet ++ b1.keySet).toVector
          .sortBy(_.showLoc)
          .filter(p => b0.get(p) != b1.get(p))
          .flatMap(p => b0.get(p).map(_._1).toVector ++ b1.get(p).map(_._1))
          .filterNot(_.isEmpty)
          .distinct
          .map(Damage.Region(_))
    end layerDamage

    /** Lays out a measured item at `rect` and its whole subtree into `acc`;
      * `layer` is its layer when it is positioned.
      */
    // claude 2026-10-19: changed: the layer of a positioned element (L1b)
    def place(
        m: Measured,
        rect: Rect,
        ctx: Ctx,
        index: Int,
        env: Scene,
        root: UI,
        acc: Laid,
        layer: Option[Layer] = None,
    ): Laid =
      m.item.ui match
          case t: Text    =>
            val node = Node(
              m.item.path,
              Kind.Txt(t.value, TextFlow.of(ctx.parent)),
              rect,
              m.props,
              ctx.bgUnder,
              m.size,
              ctx.availW,
              Vector.empty,
              Some(ctx.owner),
              index,
              Vector.empty,
            )
            acc.put(node)
          case e: Element =>
            val node0 = Node(
              m.item.path,
              Kind.El(e),
              rect,
              m.props,
              ctx.bgUnder,
              m.size,
              ctx.availW,
              Vector.empty,
              Some(ctx.owner),
              index,
              ctx.cols,
              layer = layer,
            )
            layoutChildren(node0, env, root, acc)
          case _          => acc

    /** Measures, arranges and places the children of `node` (which is already
      * positioned).
      */
    // claude 2026-10-19: changed: the kids of `arrangeKids`; the view of a
    // viewport (L1a)
    // claude 2026-10-19: changed: the layer and the width of each kid (L1b)
    def layoutChildren(node: Node, env: Scene, root: UI, acc: Laid): Laid =
        val a        = arrangeKids(node, env, root, acc.nodes.get)
        val kids     = a.kids
        val regions  = a.regions
        val rects    = a.rects
        val ctx      = Ctx(
          node.path,
          node.props,
          node.bgForKids,
          a.inner,
          node.props.listStyle,
          a.tableCols,
        )
        val withKids = node.copy(kids = kids.map(_.item.path), view = a.view)
        val acc1     = acc
          .put(withKids)
          .copy(
            regionOwner = regions.foldLeft(acc.regionOwner)((ro, r) =>
              ro.updated(r, node.path),
            ),
          )
        kids.zip(rects).zipWithIndex.foldLeft(acc1) { case (l, ((m, r), i)) =>
          place(
            m,
            r,
            ctx.copy(availW = a.availOf(i)),
            i,
            env,
            root,
            l,
            a.layerOf(i),
          )
        }
    end layoutChildren

    // claude 2026-10-19: changed: the custom properties of the run (L1b)
    def screenNode(size: Size, vars: CssVars = CssVars.empty): Node =
      Node(
        Loc.screen,
        Kind.Screen,
        Rect(0, 0, size.w, size.h),
        Props.neutral(TextAttrs.default.copy(vars = vars)),
        None,
        size,
        size.w,
        Vector.empty,
        None,
        0,
        Vector.empty,
      )

    /** The whole screen. */
    def layoutAll(root: UI, env: Scene): Laid =
      layoutChildren(screenNode(env.screen, env.vars), env, root, Laid.empty)

    // ---- incremental re-layout of one container ----

    private def sameItem(old: Node, item: Item): Boolean =
      (old.kind, item.ui) match
          case (Kind.El(a), b: Element)  => a eq b
          case (Kind.Txt(a, _), b: Text) => a == b.value
          case _                         => false

    // claude 2026-10-12: changed: the unused parameter `kids` is removed;
    // changed (PR #10 review: Codex, Layout.scala:264): `availH` added
    private def naturalOf(node: Node, availH: Option[Int], env: Scene): Size =
      node.kind match
          case Kind.Screen    => node.natural
          case Kind.El(e)     =>
            measureElement(
              e,
              node.path,
              node.props,
              node.availW - node.props.margin.h,
              availH.map(h => (h - node.props.margin.v).max(0)),
              env,
            )
          case Kind.Txt(_, _) => node.natural

    /** Re-arranges the items of `container`. Items that keep their element,
      * size and rect keep their subtree; the others are laid out again. When
      * the container's own natural size changes, its owner is re-arranged too
      * (and so on up).
      *
      * @param force
      *   direct items to lay out again even if they look unchanged (focus
      *   moved, a bound value changed)
      */
    def relayout(
        laid: Laid,
        container: Loc,
        force: Set[Loc],
        env: Scene,
        root: UI,
    ): (Laid, Vector[Damage]) =
        laid.nodes.get(container) match
            case None        => (laid, Vector.empty)
            case Some(node0) =>
              /* the node's own props can change with focus; re-resolve them
               * from its owner */
              val node                     = node0.kind match
                  case Kind.El(e) =>
                    val parentText = node0.owner
                      .flatMap(laid.nodes.get)
                      .map(_.props.text)
                      .getOrElse(TextAttrs.default)
                    val listStyle  = node0.owner
                      .flatMap(laid.nodes.get)
                      .flatMap(_.props.listStyle)
                    node0.copy(props =
                      Props.resolve(
                        e,
                        parentText,
                        env.focus.contains(container),
                        listStyle,
                        node0.index,
                      ),
                    )
                  case _          => node0
              // claude 2026-10-19: changed: the kids of `arrangeKids`, the
              // view of a viewport, and its clip of the damage (L1a)
              // claude 2026-10-19: changed: the positioned kids, their layers
              // and their widths; a layer clips the damage of its kids to its
              // box (L1b)
              val a                        = arrangeKids(node, env, root, laid.nodes.get)
              val kids                     = a.kids
              val regions                  = a.regions
              val rects                    = a.rects
              val tableCols                = a.tableCols
              val ctx                      = Ctx(
                node.path,
                node.props,
                node.bgForKids,
                a.inner,
                node.props.listStyle,
                tableCols,
              )
              val clip                     =
                selfClip(laid, node).fold(a.view.map(_.port))(c =>
                  Some(a.view.fold(c)(v => c.intersect(v.port))),
                )
              def clear(r: Rect): Damage   =
                Damage.Clear(clip.fold(r)(r.intersect), node.bgForKids)
              val newPaths                 = kids.map(_.item.path).toSet
              val removed                  = node.kids.filterNot(newPaths.contains)
              val afterRemove              = removed.foldLeft(laid)((l, p) => l.without(p))
              val removedDamage            =
                removed.flatMap(p => laid.nodes.get(p).map(n => clear(n.rect)))
              // claude 2026-10-19: changed: a kid of a viewport that only
              // moved (a scroll) moves with its subtree and is not laid out
              // again; the count of the kids laid out again (L1a review)
              val (laid2, damage, laidOut) = kids
                .zip(rects)
                .zipWithIndex
                .foldLeft((afterRemove, removedDamage, 0)) {
                  case ((l, d, n), ((m, r), i)) =>
                    val old  = laid.nodes.get(m.item.path)
                    val same = old.filter(o =>
                      sameItem(
                        o,
                        m.item,
                      ) && o.natural == m.size && o.index == i && !force
                        .contains(m.item.path) &&
                        // claude 2026-10-12: changed (PR #10 review: Codex,
                        // Layout.scala:703): the OLD node's columns against
                        // the new ones (`ctx.cols` was built from `tableCols`)
                        o.props == m.props && o.cols == tableCols &&
                        // claude 2026-10-19: created (L1b): the same layer;
                        // a kid out of the flow, the same width
                        o.layer == a.layerOf(i) && a
                          .layerOf(i)
                          .forall(l =>
                            !l.pos.outOfFlow || o.availW == a.availOf(i),
                          ),
                    )
                    same match
                        case Some(o) if o.rect == r => (l, d, n)
                        case Some(o)
                            if a.view.isDefined && o.rect.w == r.w &&
                              o.rect.h == r.h && o.availW == a.availOf(i) &&
                              o.layer == a.layerOf(i) =>
                          (
                            l.shift(o.path, r.x - o.rect.x, r.y - o.rect.y),
                            d ++ Vector(
                              clear(o.rect),
                              clear(r),
                              Damage.Paint(m.item.path),
                            ),
                            n,
                          )
                        case _                      =>
                          val cleared = old.fold(l)(o => l.without(o.path))
                          val placed  = place(
                            m,
                            r,
                            ctx.copy(availW = a.availOf(i)),
                            i,
                            env,
                            root,
                            cleared,
                            a.layerOf(i),
                          )
                          val dmg     = old
                            .map(o => clear(o.rect))
                            .toVector ++
                            Vector(clear(r), Damage.Paint(m.item.path))
                          (placed, d ++ dmg, n + 1)
                    end match
                }
              val updatedNode              =
                node.copy(kids = kids.map(_.item.path), view = a.view)
              val laid3                    = laid2
                .put(updatedNode)
                .copy(
                  regionOwner = regions.foldLeft(laid2.regionOwner)((ro, r) =>
                    ro.updated(r, container),
                  ),
                )
              val propsChanged             = node.props != node0.props
              // claude 2026-10-19: changed: a new view (a scroll, a new
              // content size) paints the viewport again; the owner is laid
              // out again only when the natural size or the props change (L1a,
              // L1a review)
              val viewChanged              = a.view != node0.view
              /* claude 2026-10-12: changed (PR #10 review: Codex,
               * Layout.scala:264): the */
              // owner's content height, as `measuredKids` gives it
              // claude 2026-10-19: changed: the height of the containing
              // block of an element out of the flow (L1b)
              val ownerH                   = node.layer match
                  case Some(l) if l.pos.outOfFlow =>
                    cbBox(laid3, l.cb).map(_.h)
                  case _                          =>
                    node.owner.flatMap(laid3.nodes.get).map(_.content.h)
              // claude 2026-10-19: changed: no measure when no kid changed
              // (a scroll): the natural size stays (L1a review)
              val newNatural               =
                if laidOut == 0 && removed.isEmpty && !propsChanged then
                    node0.natural
                else naturalOf(updatedNode, ownerH, env)
              (node.owner, newNatural != node0.natural || propsChanged) match
                  case (Some(owner), true) =>
                    relayout(laid3, owner, Set(container), env, root)
                  case _                   =>
                    val above      = selfClip(laid3, node)
                    val selfDamage =
                      if propsChanged || viewChanged then
                          Vector(
                            Damage.Clear(
                              above.fold(node.rect)(node.rect.intersect),
                              node.bgUnder,
                            ),
                            Damage.Paint(container),
                          )
                      else Vector.empty
                    (laid3, damage ++ selfDamage)
              end match
        end match
    end relayout

    /** The focusable nodes at `p` and under it, in document order: the elements
      * that [[Props.isFocusable]] accepts, and the viewport stops. A viewport
      * stop is a viewport with no `tabIndex` that can scroll now (its content
      * is larger than its port on an axis that scrolls, as Firefox makes a
      * scrollable region focusable) and that holds no focusable node (as Chrome
      * skips such a scroller: the keys on the focused node inside scroll it).
      * So a viewport whose content fits is not a tab stop, and a viewport
      * around a list of buttons needs no `tabIndex(-1)`.
      */
    // claude 2026-10-19: created (L1a review)
    private def stops(laid: Laid, p: Loc): Vector[Loc] =
      laid.nodes.get(p) match
          case None    => Vector.empty
          case Some(n) =>
            val below = n.kids.flatMap(stops(laid, _))
            val self  = n.kind match
                case Kind.El(e) =>
                  Props.isFocusable(e) || (below.isEmpty &&
                    e.attrs.tabIndex.isEmpty && !Props.isDisabled(e) &&
                    n.view.exists(_.max != Offset.zero))
                case _          => false
            if self then p +: below else below

    /** True when the node at `p` takes the focus (see [[stops]]). */
    // claude 2026-10-19: created (L1a review)
    def isFocusable(laid: Laid, p: Loc): Boolean =
      stops(laid, p).headOption.contains(p)

    /** The focusable nodes in tab order (see [[stops]]): positive `tabIndex`
      * ascending, then document order.
      */
    // claude 2026-10-19: changed: the viewport stops of the layout (L1a
    // review)
    def focusOrder(laid: Laid): Vector[Loc] =
        val all                   = stops(laid, Loc.screen)
        def tabIndex(p: Loc): Int = laid.nodes.get(p).map(_.kind) match
            case Some(Kind.El(e)) => e.attrs.tabIndex.getOrElse(0)
            case _                => 0
        val positive              = all.filter(tabIndex(_) > 0).sortBy(tabIndex)
        positive ++ all.filter(tabIndex(_) <= 0)
end Layout
