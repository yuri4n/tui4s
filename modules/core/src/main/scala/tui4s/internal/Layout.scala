package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import jatatui.core.style.Color as JColor
import jatatui.core.text.Span
import kyo.*
import kyo.Style.{Alignment, Justification, ListStyle, Overflow, ScrollbarWidth}
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
  * (`World.scroll`); the layout clamps it.
  */
// claude 2026-10-19: changed: the scroll offset of a viewport (L1a)
private[tui4s] final case class Scene(
    content: Loc => Option[Content],
    focus: Option[Loc],
    screen: Size,
    caret: Loc => Int,
    scroll: Loc => Offset = _ => Offset.zero,
)

private[tui4s] enum Kind:
    case Screen
    case El(el: Element)
    case Txt(text: String, wrap: Boolean)

/** One laid-out box. `rect` is the border box; margins are outside. `view` is
  * the scroll state of a viewport (an element with an axis that is not
  * `overflow: visible`), and none for any other node.
  */
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
private[tui4s] final case class Laid(
    nodes: Map[Loc, Node],
    regionOwner: Map[Loc, Loc],
):
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
        Laid(
          nodes -- subtree(prefix),
          regionOwner.filterNot((r, o) =>
            r.isAtOrUnder(prefix) || o.isAtOrUnder(prefix),
          ),
        )
    end without
end Laid

private[tui4s] object Laid:
    val empty: Laid = Laid(Map.empty, Map.empty)

/** A change of the screen that paint must apply: clear a rectangle to a
  * background, or paint a node and its subtree.
  */
private[tui4s] enum Damage:
    case Clear(rect: Rect, bg: Option[JColor])
    case Paint(path: Loc)

/** A flex item of a container: an element or a text, with the regions it is
  * inside (innermost first).
  */
private[tui4s] final case class Item(path: Loc, ui: UI, regions: List[Loc])

private[tui4s] final case class Flat(items: Vector[Item], regions: Vector[Loc]):
    def ++(o: Flat): Flat = Flat(items ++ o.items, regions ++ o.regions)

private[tui4s] object Flat:
    val empty: Flat = Flat(Vector.empty, Vector.empty)

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
    // line (PF-a, F4)
    def wrap(text: String, width: Int): Vector[String] =
        val w = width.max(1)
        text.split("\n", -1).toVector.flatMap { para =>
          val words = para.split(" ", -1)
          val lines = words
            .foldLeft(Wrap(Vector.empty, None)) { (acc, word) =>
              val ww = textWidth(word)
              acc.open match
                  case Some(open) if open.w + 1 + ww <= w =>
                    acc.copy(open = Some(open.add(word, ww)))
                  case _ if ww <= w                       =>
                    acc.close.copy(open = Some(Line(Vector(word), ww)))
                  case _                                  =>
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
    end wrap

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

    def textLines(text: String, width: Int, wrapped: Boolean): Vector[String] =
      if wrapped then wrap(text, width) else text.split("\n", -1).toVector

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
          case t: Text    =>
            val lines =
              textLines(parent.text(t.value), availW, parent.dir == Dir.Column)
            Measured(
              item,
              Props.neutral(parent.text),
              Size(
                lines.map(textWidth).maxOption.getOrElse(0).min(availW.max(0)),
                lines.size,
              ),
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
        val fixedH                         = p.height.flatMap(cellsH)
        val inner                          = (fixedW.getOrElse(availW) - ins.h).max(0)
        val innerH                         = fixedH.map(h => (h - ins.v).max(0))
        val content: Size                  = intrinsic(e).getOrElse {
          e match
              case _: Table => tableSize(e, path, p, inner, env)._2
              case _        =>
                val flat     = itemsOf(e, path, env)
                val measured = flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, innerH, p, env, p.listStyle, i),
                )
                combine(p, measured)
        }
        val w                              = fixedW.getOrElse(content.w + ins.h)
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
      * and gaps included).
      */
    def combine(p: Props, items: Vector[Measured]): Size =
      if items.isEmpty then Size.zero
      else
          val outer = items.map(m =>
            Size(m.size.w + m.props.margin.h, m.size.h + m.props.margin.v),
          )
          val gaps  = p.gap * (items.size - 1)
          p.dir match
              case Dir.Column =>
                Size(outer.map(_.w).max, outer.map(_.h).sum + gaps)
              case Dir.Row    =>
                Size(outer.map(_.w).sum + gaps, outer.map(_.h).max)

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

    def arrange(
        p0: Props,
        box: Rect,
        items0: Vector[Measured],
        cols: Vector[Int],
    ): Vector[Rect] =
      if items0.isEmpty then Vector.empty
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
          def mainOf(s: Size): Int     = if column then s.h else s.w
          def crossOf(s: Size): Int    = if column then s.w else s.h
          def mMain(m: Measured): Int  =
            if column then m.props.margin.v else m.props.margin.h
          def mCross(m: Measured): Int =
            if column then m.props.margin.h else m.props.margin.v
          val mainSize                 = if column then box.h else box.w
          val crossSize                = if column then box.w else box.h
          val gaps                     = p.gap * (items.size - 1)
          val base                     = items.map(m => mainOf(m.size))
          val used                     = base.sum + items.map(mMain).sum + gaps
          val free                     = mainSize - used
          val grows                    = items.map(_.props.grow)
          val sizes: Vector[Int]       =
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
                val weights = items.zip(base).zip(floor).map {
                  case ((m, b), f) => if b > f then m.props.shrink * b else 0.0
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
                    val room    = base.zip(whole).zip(floor).map {
                      case ((b, c), f) => (b - c - f).max(0)
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
    // port of a viewport can be narrower than the content box, L1a)
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
    )

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
    // relayout, with the indicator column
    def arrangeKids(node: Node, env: Scene, root: UI): Arranged =
        val box                         = node.content
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
                case _                 => combine(p, ks)
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
    end arrangeKids

    /** The cells that the damage inside the node at `from` can name: the ports
      * of the viewports at `from` and above it. None when no viewport holds it
      * (the damage stays as it is).
      */
    // claude 2026-10-19: created (L1a): a viewport clips the damage of its
    // subtree, so a clear of a kid that scrolled out does not touch the cells
    // outside the viewport
    def damageClip(laid: Laid, from: Option[Loc]): Option[Rect] =
        @annotation.tailrec
        def up(q: Option[Loc], acc: Option[Rect]): Option[Rect] =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                up(
                  n.owner,
                  n.view.fold(acc)(v =>
                    Some(acc.fold(v.port)(_.intersect(v.port))),
                  ),
                )
        up(from, None)
    end damageClip

    /** Lays out a measured item at `rect` and its whole subtree into `acc`. */
    def place(
        m: Measured,
        rect: Rect,
        ctx: Ctx,
        index: Int,
        env: Scene,
        root: UI,
        acc: Laid,
    ): Laid =
      m.item.ui match
          case t: Text    =>
            val node = Node(
              m.item.path,
              Kind.Txt(t.value, ctx.parent.dir == Dir.Column),
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
            acc.copy(nodes = acc.nodes.updated(node.path, node))
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
            )
            layoutChildren(node0, env, root, acc)
          case _          => acc

    /** Measures, arranges and places the children of `node` (which is already
      * positioned).
      */
    // claude 2026-10-19: changed: the kids of `arrangeKids`; the view of a
    // viewport (L1a)
    def layoutChildren(node: Node, env: Scene, root: UI, acc: Laid): Laid =
        val a        = arrangeKids(node, env, root)
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
        val acc1     = acc.copy(
          nodes = acc.nodes.updated(node.path, withKids),
          regionOwner = regions.foldLeft(acc.regionOwner)((ro, r) =>
            ro.updated(r, node.path),
          ),
        )
        kids.zip(rects).zipWithIndex.foldLeft(acc1) { case (a, ((m, r), i)) =>
          place(m, r, ctx, i, env, root, a)
        }
    end layoutChildren

    def screenNode(size: Size): Node =
      Node(
        Loc.screen,
        Kind.Screen,
        Rect(0, 0, size.w, size.h),
        Props.neutral(TextAttrs.default),
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
      layoutChildren(screenNode(env.screen), env, root, Laid.empty)

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
              val node                   = node0.kind match
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
              val a                      = arrangeKids(node, env, root)
              val kids                   = a.kids
              val regions                = a.regions
              val rects                  = a.rects
              val tableCols              = a.tableCols
              val ctx                    = Ctx(
                node.path,
                node.props,
                node.bgForKids,
                a.inner,
                node.props.listStyle,
                tableCols,
              )
              val clip                   =
                damageClip(laid, node.owner).fold(a.view.map(_.port))(c =>
                  Some(a.view.fold(c)(v => c.intersect(v.port))),
                )
              def clear(r: Rect): Damage =
                Damage.Clear(clip.fold(r)(r.intersect), node.bgForKids)
              val newPaths               = kids.map(_.item.path).toSet
              val removed                = node.kids.filterNot(newPaths.contains)
              val afterRemove            = removed.foldLeft(laid)((l, p) => l.without(p))
              val removedDamage          =
                removed.flatMap(p => laid.nodes.get(p).map(n => clear(n.rect)))
              val (laid2, damage)        = kids
                .zip(rects)
                .zipWithIndex
                .foldLeft((afterRemove, removedDamage)) {
                  case ((l, d), ((m, r), i)) =>
                    val old  = laid.nodes.get(m.item.path)
                    val keep = old.exists(o =>
                      sameItem(
                        o,
                        m.item,
                      ) && o.rect == r && o.natural == m.size && o.index == i && !force
                        .contains(m.item.path) &&
                        // claude 2026-10-12: changed (PR #10 review: Codex,
                        // Layout.scala:703): the OLD node's columns against
                        // the new ones (`ctx.cols` was built from `tableCols`)
                        o.props == m.props && o.cols == tableCols,
                    )
                    if keep then (l, d)
                    else
                        val cleared = old.fold(l)(o => l.without(o.path))
                        val placed  = place(m, r, ctx, i, env, root, cleared)
                        val dmg     = old
                          .map(o => clear(o.rect))
                          .toVector ++
                          Vector(clear(r), Damage.Paint(m.item.path))
                        (placed, d ++ dmg)
                    end if
                }
              val updatedNode            =
                node.copy(kids = kids.map(_.item.path), view = a.view)
              val laid3                  = laid2.copy(
                nodes = laid2.nodes.updated(container, updatedNode),
                regionOwner = regions.foldLeft(laid2.regionOwner)((ro, r) =>
                  ro.updated(r, container),
                ),
              )
              // claude 2026-10-19: changed: a new view (a scroll, a new
              // content size) paints the viewport again (L1a)
              val propsChanged           =
                node.props != node0.props || a.view != node0.view
              /* claude 2026-10-12: changed (PR #10 review: Codex,
               * Layout.scala:264): the */
              // owner's content height, as `measuredKids` gives it
              val ownerH                 =
                node.owner.flatMap(laid3.nodes.get).map(_.content.h)
              val newNatural             = naturalOf(updatedNode, ownerH, env)
              (node.owner, newNatural != node0.natural || propsChanged) match
                  case (Some(owner), true) =>
                    relayout(laid3, owner, Set(container), env, root)
                  case _                   =>
                    val above      = damageClip(laid3, node.owner)
                    val selfDamage =
                      if propsChanged then
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

    /** The focusable elements in tab order: positive `tabIndex` ascending, then
      * document order.
      */
    def focusOrder(laid: Laid): Vector[Loc] =
        def walk(p: Loc): Vector[Loc] =
          laid.nodes.get(p) match
              case None    => Vector.empty
              case Some(n) =>
                val self = n.kind match
                    case Kind.El(e) if Props.isFocusable(e) => Vector(p)
                    case _                                  => Vector.empty
                self ++ n.kids.flatMap(walk)
        val all                       = walk(Loc.screen)
        def tabIndex(p: Loc): Int     = laid.nodes.get(p).map(_.kind) match
            case Some(Kind.El(e)) => e.attrs.tabIndex.getOrElse(0)
            case _                => 0
        val positive                  = all.filter(tabIndex(_) > 0).sortBy(tabIndex)
        positive ++ all.filter(tabIndex(_) <= 0)
    end focusOrder
end Layout
