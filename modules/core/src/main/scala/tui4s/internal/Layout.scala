package tui4s.internal

import jatatui.core.style.Color as JColor
import jatatui.core.text.Span
import kyo.*
import kyo.Style.Alignment
import kyo.Style.Justification
import kyo.Style.ListStyle
import kyo.UI.Ast.*

/** What a region shows now. */
enum Content:
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
  * the runner's immutable world.
  */
final case class Scene(
    content: Loc => Option[Content],
    focus: Option[Loc],
    screen: Size,
    caret: Loc => Int,
)

enum Kind:
    case Screen
    case El(el: Element)
    case Txt(text: String, wrap: Boolean)

/** One laid-out box. `rect` is the border box; margins are outside. */
final case class Node(
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
):
    def content: Rect             = rect.inset(props.insets)
    def bgForKids: Option[JColor] = props.bg.orElse(bgUnder)
end Node

/** The laid-out screen: a flat, immutable index of nodes, plus the container
  * that owns the items of each region.
  */
final case class Laid(nodes: Map[Loc, Node], regionOwner: Map[Loc, Loc]):
    def without(prefix: Loc): Laid =
      Laid(
        nodes.filterNot((p, _) => p.isAtOrUnder(prefix)),
        regionOwner.filterNot((r, o) =>
          r.isAtOrUnder(prefix) || o.isAtOrUnder(prefix),
        ),
      )
end Laid

object Laid:
    val empty: Laid = Laid(Map.empty, Map.empty)

/** A change of the screen that paint must apply: clear a rectangle to a
  * background, or paint a node and its subtree.
  */
enum Damage:
    case Clear(rect: Rect, bg: Option[JColor])
    case Paint(path: Loc)

/** A flex item of a container: an element or a text, with the regions it is
  * inside (innermost first).
  */
final case class Item(path: Loc, ui: UI, regions: List[Loc])

final case class Flat(items: Vector[Item], regions: Vector[Loc]):
    def ++(o: Flat): Flat = Flat(items ++ o.items, regions ++ o.regions)

object Flat:
    val empty: Flat = Flat(Vector.empty, Vector.empty)

/** Flexbox in terminal cells, following kyo-ui's base CSS (everything is flex,
  * reactive boundaries are transparent).
  */
object Layout:

    // ---- text ----

    def textWidth(s: String): Int = Span.raw(s).width()

    def wrap(text: String, width: Int): Vector[String] =
        val w = width.max(1)
        text.split("\n", -1).toVector.flatMap { para =>
          val words = para.split(" ", -1).toVector
          val lines = words.foldLeft(Vector.empty[String]) { (acc, word) =>
            acc.lastOption match
                case Some(last) if textWidth(last) + 1 + textWidth(word) <= w =>
                  acc.init :+ s"$last $word"
                case _ if acc.isEmpty && textWidth(word) <= w                 => Vector(word)
                case _ if textWidth(word) <= w                                => acc :+ word
                case _                                                        => acc ++ chunksByWidth(word, w)
          }
          if lines.isEmpty then Vector("") else lines
        }
    end wrap

    /** Splits a word into pieces of at most `w` cells. The split is at a code
      * point border: a wide character or a surrogate pair does not split.
      */
    // claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:114)
    private def chunksByWidth(word: String, w: Int): Vector[String] =
        val glyphs = Vector.unfold(0) { i =>
          if i >= word.length then None
          else
              val cp = word.codePointAt(i)
              Some((String(Character.toChars(cp)), i + Character.charCount(cp)))
        }
        glyphs.foldLeft(Vector.empty[String]) { (acc, g) =>
          acc.lastOption match
              case Some(last) if textWidth(last) + textWidth(g) <= w =>
                acc.init :+ (last + g)
              case _                                                 => acc :+ g
        }
    end chunksByWidth

    def textLines(text: String, width: Int, wrapped: Boolean): Vector[String] =
      if wrapped then wrap(text, width) else text.split("\n", -1).toVector

    // ---- flattening: what are the flex items of a container ----

    def flatten(ui: UI, path: Loc, regions: List[Loc], env: Scene): Flat =
      ui match
          case t: Text          => Flat(Vector(Item(path, t, regions)), Vector.empty)
          case r: RawHtml       =>
            Flat(
              Vector(
                Item(path, Text(r.value.replaceAll("<[^>]*>", "")), regions),
              ),
              Vector.empty,
            )
          case e: Element       =>
            if Props.isHidden(e) then Flat.empty
            else Flat(Vector(Item(path, e, regions)), Vector.empty)
          case f: Fragment[?]   =>
            f.children.toVector.zipWithIndex.foldLeft(Flat.empty) {
              case (acc, (c, i)) =>
                acc ++ flatten(c, path :+ i.toString, regions, env)
            }
          case _: Reactive[?]   =>
            val here = Flat(Vector.empty, Vector(path))
            env.content(path) match
                case Some(Content.Single(c)) =>
                  here ++ flatten(c, path :+ "~", path :: regions, env)
                case _                       => here
          case _: Foreach[?, ?] =>
            val here = Flat(Vector.empty, Vector(path))
            env.content(path) match
                case Some(Content.Many(items)) =>
                  items.foldLeft(here) { case (acc, (key, c)) =>
                    acc ++ flatten(c, path :+ s"#$key", path :: regions, env)
                  }
                case _                         => here
          case _                => Flat.empty

    /** The flex items of an element's children. A table's rows are its `tr`
      * children, also inside `tbody`.
      */
    def itemsOf(el: Element, path: Loc, env: Scene): Flat =
      el match
          case _: Select | _: Dropdown => Flat.empty /* painted as a picker,
             * options are not items */
          case _                       =>
            el.children.toVector.zipWithIndex.foldLeft(Flat.empty) {
              case (acc, (c, i)) =>
                acc ++ flatten(c, path :+ i.toString, Nil, env)
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
              measureElement(e, item.path, p, availW - p.margin.h, env),
            )
          case _          => Measured(item, Props.neutral(parent.text), Size.zero)

    def measureElement(
        e: Element,
        path: Loc,
        p: Props,
        availW: Int,
        env: Scene,
    ): Size =
        val ins           = p.insets
        val fixedW        = p.width.flatMap(Props.cells(_, availW, env.screen.h))
        val inner         = (fixedW.getOrElse(availW) - ins.h).max(0)
        val content: Size = intrinsic(e).getOrElse {
          e match
              case _: Table => tableSize(e, path, p, inner, env)._2
              case _        =>
                val flat     = itemsOf(e, path, env)
                val measured = flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, p, env, p.listStyle, i),
                )
                combine(p, measured)
        }
        val w             = fixedW.getOrElse(content.w + ins.h)
        val h             = p.height
          .flatMap(Props.cells(_, env.screen.h, env.screen.h))
          .getOrElse(content.h + ins.v)
        Size(
          clamp(
            w,
            p.minW.flatMap(Props.cells(_, availW, env.screen.h)),
            p.maxW.flatMap(Props.cells(_, availW, env.screen.h)),
          ),
          clamp(
            h,
            p.minH.flatMap(Props.cells(_, env.screen.h, env.screen.h)),
            p.maxH.flatMap(Props.cells(_, env.screen.h, env.screen.h)),
          ),
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

    private def rowsOf(table: Element, path: Loc, env: Scene): Vector[Item] =
      itemsOf(table, path, env).items.flatMap { it =>
        it.ui match
            case _: Tr     => Vector(it)
            case tb: Tbody =>
              itemsOf(tb, it.path, env).items.filter(_.ui.isInstanceOf[Tr])
            case _         => Vector.empty
      }

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
        val cells = rows.map { row =>
          val rp =
            Props.resolve(row.ui.asInstanceOf[Element], p.text, false, None, 0)
          itemsOf(
            row.ui.asInstanceOf[Element],
            row.path,
            env,
          ).items.zipWithIndex.map((c, i) =>
            measureItem(c, inner, rp, env, None, i).size.w,
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
        p: Props,
        box: Rect,
        items: Vector[Measured],
        cols: Vector[Int],
    ): Vector[Rect] =
      if items.isEmpty then Vector.empty
      else if cols.nonEmpty then
          // a table row: fixed column widths
          items.zipWithIndex.map { (m, j) =>
            val x = box.x + cols.take(j).sum + j
            Rect(
              x,
              box.y,
              cols.lift(j).getOrElse(m.size.w),
              1.max(m.size.h).min(box.h),
            )
          }
      else
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
                val floor   = items
                  .zip(base)
                  .map((m, b) =>
                    if column && m.props.height.isEmpty then b else 0,
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
          items.zip(sizes).zip(offsets).map { case ((m, s), off) =>
            val mainPos       =
              off + (if column then m.props.margin.t else m.props.margin.l)
            val crossRoom     = (crossSize - mCross(m)).max(0)
            /* a left-aligned text paints only its glyphs: it does not stretch
             * (smaller damage when it changes) */
            val textNoStretch = m.item.ui
              .isInstanceOf[Text] && p.text.align == kyo.Style.TextAlign.left
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
    def measuredKids(
        node: Node,
        env: Scene,
        root: UI,
    ): (Vector[Measured], Vector[Loc], Vector[Int]) =
        val inner = node.content.w
        node.kind match
            case Kind.Screen       =>
              val flat = flatten(root, Loc.root, Nil, env)
              (
                flat.items.zipWithIndex.map((it, i) =>
                  measureItem(it, inner, node.props, env, None, i),
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
                  measureItem(it, inner, node.props, env, None, i),
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
    def layoutChildren(node: Node, env: Scene, root: UI, acc: Laid): Laid =
        val (kids, regions, _) = measuredKids(node, env, root)
        val box                = node.content
        val tableCols          = node.kind match
            case Kind.El(e: Table) =>
              tableSize(e, node.path, node.props, box.w, env)._1
            case _                 => Vector.empty
        val rects              = arrange(node.props, box, kids, node.cols)
        val ctx                = Ctx(
          node.path,
          node.props,
          node.bgForKids,
          box.w,
          node.props.listStyle,
          tableCols,
        )
        val withKids           = node.copy(kids = kids.map(_.item.path))
        val acc1               = acc.copy(
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

    // claude 2026-10-12: changed: the unused parameter `kids` is removed
    private def naturalOf(node: Node, env: Scene): Size =
      node.kind match
          case Kind.Screen    => node.natural
          case Kind.El(e)     =>
            measureElement(
              e,
              node.path,
              node.props,
              node.availW - node.props.margin.h,
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
              val node               = node0.kind match
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
              val (kids, regions, _) = measuredKids(node, env, root)
              val box                = node.content
              val tableCols          = node.kind match
                  case Kind.El(e: Table) =>
                    tableSize(e, node.path, node.props, box.w, env)._1
                  case _                 => Vector.empty
              val rects              = arrange(node.props, box, kids, node.cols)
              val ctx                = Ctx(
                node.path,
                node.props,
                node.bgForKids,
                box.w,
                node.props.listStyle,
                tableCols,
              )
              val newPaths           = kids.map(_.item.path).toSet
              val removed            = node.kids.filterNot(newPaths.contains)
              val afterRemove        = removed.foldLeft(laid)((l, p) => l.without(p))
              val removedDamage      = removed.flatMap(p =>
                laid.nodes.get(p).map(n => Damage.Clear(n.rect, node.bgForKids)),
              )
              val (laid2, damage)    = kids
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
                        o.props == m.props && tableCols == ctx.cols,
                    )
                    if keep then (l, d)
                    else
                        val cleared = old.fold(l)(o => l.without(o.path))
                        val placed  = place(m, r, ctx, i, env, root, cleared)
                        val dmg     = old
                          .map(o => Damage.Clear(o.rect, node.bgForKids))
                          .toVector ++
                          Vector(
                            Damage.Clear(r, node.bgForKids),
                            Damage.Paint(m.item.path),
                          )
                        (placed, d ++ dmg)
                    end if
                }
              val updatedNode        = node.copy(kids = kids.map(_.item.path))
              val laid3              = laid2.copy(
                nodes = laid2.nodes.updated(container, updatedNode),
                regionOwner = regions.foldLeft(laid2.regionOwner)((ro, r) =>
                  ro.updated(r, container),
                ),
              )
              val propsChanged       = node.props != node0.props
              val newNatural         = naturalOf(updatedNode, env)
              (node.owner, newNatural != node0.natural || propsChanged) match
                  case (Some(owner), true) =>
                    relayout(laid3, owner, Set(container), env, root)
                  case _                   =>
                    val selfDamage =
                      if propsChanged then
                          Vector(
                            Damage.Clear(node.rect, node.bgUnder),
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
