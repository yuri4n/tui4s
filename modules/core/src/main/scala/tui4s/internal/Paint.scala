package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import jatatui.core.style.Style as JStyle
import kyo.*
import kyo.Style.TextAlign
import kyo.UI.Ast.*

/** The pure painter: the laid-out screen, the scene and the damage of a frame
  * give the [[Plan]] of the frame (its ops, its damaged cells and the number of
  * subtrees painted).
  *
  * For example, a full frame of `ui` on `w` x `h` cells:
  * {{{
  * val laid = Layout.layoutAll(ui, scene)
  * val plan = Paint(laid, scene, Vector(
  *   Damage.Clear(Rect(0, 0, w, h), None), Damage.Paint(Loc.screen)))
  * canvas.write(plan)
  * }}}
  *
  * Laws:
  *   - `Paint(laid, scene, damage)` is total and deterministic: it reads only
  *     its arguments and writes nothing.
  *   - The ops of a subtree lie inside the rect of its root and inside the clip
  *     of the root (the content boxes of its owners and the screen), so every
  *     op lies inside the spans of the plan.
  *   - A text is clipped by cells (see [[text]]).
  *
  * The paint order (CSS stacking, for the common cases): first the flow (the
  * nodes that are not positioned, in document order), then the positioned
  * elements (the layers of [[Layout]]: `position` relative, sticky, absolute,
  * fixed) by `zIndex` (0 when none), the equal ones in document order. Each
  * positioned element is a stacking context of its own (as CSS makes one for a
  * positioned element with a `zIndex`): its positioned descendants paint after
  * it and inside its place in the order, never under it. The runner does not
  * follow the other rules of CSS stacking: an `auto` z-index makes no context
  * of its own there, and a negative `zIndex` paints the element under the flow
  * there; here it paints after the flow, before the layers of `zIndex` 0.
  * `opacity`, `transform` and `filter` make no context here.
  *
  * The damage rule: the cells of the damage of a frame (its clears, the rects
  * of its roots, the boxes of the layers under its roots) that lie under the
  * box of a layer are painted again in paint order after the roots, in whole
  * rows: the rows are cleared, the flow paints them, then each layer paints
  * them. A `Damage.Region` is painted again so too. So that layer and every
  * layer above it paint again over the damaged roots, and nothing under a layer
  * ever shows through it. A layer that moves, comes or goes gives the regions
  * of its old box and of its new box ([[Layout.layerDamage]]).
  *
  * The cost of whole rows: a damaged root under a layer makes the plan repaint
  * and diff each row that it touches, across the full width of the screen. A
  * full-screen modal (`position(_.overlay)`) is over every root, so a tick of a
  * region under it, or a repair tick, repaints and diffs every row of that
  * region. The terminal gets only the cells that changed. Whole rows are the
  * simple safe choice: a clip inside a row could cut a wide glyph, and the flow
  * would not give it back. A narrower band (the x range of the damage and of
  * the layer boxes in those rows, out to the nearest glyph boundary) is a task
  * for the benchmarks (L5).
  */
// claude 2026-10-19: changed (P5): a pure function to a Plan; the canvas
// writes the plan; changed: the layers and the damage rule (L1b)
private[tui4s] object Paint:

    /** The horizontal scroll of a text field `width` cells wide whose graphemes
      * have the cell widths `widths`: the first grapheme it shows, and the
      * cells from that grapheme to the caret. The first grapheme is the
      * smallest index, not after the caret, for which the cells from it to the
      * caret plus the cell of the cursor (`cursorW`) fit in the field; it is
      * the caret when nothing before the caret fits. One walk back from the
      * caret: linear in the graphemes.
      */
    // claude 2026-10-13: created (review R2-2: the old search summed the cells
    // again for each start, quadratic in the graphemes before the caret)
    def scrollStart(
        widths: IndexedSeq[Int],
        caret: Int,
        cursorW: Int,
        width: Int,
    ): (Int, Int) =
        @annotation.tailrec
        def back(i: Int, used: Int): (Int, Int) =
          if i > 0 && used + widths(i - 1) + cursorW <= width then
              back(i - 1, used + widths(i - 1))
          else (i, used)
        back(caret, 0)
    end scrollStart

    /** The plan of one frame: the clears of `damage` first, then the paint of
      * each root of `damage` that is not under another root and not in a layer,
      * then the damage rule (see the object): the cells of the damage under the
      * box of a layer, and the regions, painted again in paint order. The spans
      * of the plan are the rects of the clears, of the roots (each cut to its
      * clip), of the layers under the roots, of the regions and of the cells
      * painted again, on the screen of `env`.
      */
    // claude 2026-10-19: changed (P5): the clears and the roots give ops; the
    // clip of a root is cut to the screen; changed: the layers (L1b)
    def apply(laid: Laid, env: Scene, damage: Vector[Damage]): Plan =
        val screen  = Rect(0, 0, env.screen.w, env.screen.h)
        val clears  = damage.collect { case c: Damage.Clear => c }.distinct
        val roots   = damage.collect { case Damage.Paint(p) => p }.distinct
        val regions = damage
          .collect { case Damage.Region(r) => r.intersect(screen) }
          .filterNot(_.isEmpty)
          .distinct
        // a root under another root is painted by it (Loc.tops, PF-a, F2)
        val top     = Loc.tops(roots)
        val ops0    = clears.foldLeft(Vector.empty[Op]) { (ops, c) =>
          val r = c.rect.intersect(screen)
          if r.isEmpty then ops else ops :+ Op.Clear(r, c.bg)
        }
        val layers  = paintOrder(laid)
        // a root in a layer is painted by the damage rule below
        val inFlow  = top.filterNot(inLayer(laid, _))
        val ops1    = inFlow.foldLeft(ops0)((ops, p) =>
          paintNode(ops, laid, env, p, clipOf(laid, p).intersect(screen)),
        )
        // claude 2026-10-19: changed: the span of a root is cut to its clip,
        // so a root inside a viewport names only cells of the port (L1a
        // review)
        val rects   = clears.map(_.rect) ++ top.flatMap(p =>
          laid.nodes.get(p).map(_.rect.intersect(clipOf(laid, p))),
        ) ++ layers.collect {
          case (p, box) if top.exists(r => p.isAtOrUnder(r)) => box
        }
        // the damage rule: the rows of the cells of the damage under the box
        // of a layer, and of the regions, painted again in whole rows (a clip
        // in a row could cut a wide glyph, and the flow would not give it
        // back)
        val under   = layers.flatMap { (_, box) =>
          rects.map(_.intersect(box).intersect(screen)).filterNot(_.isEmpty)
        } ++ regions
        val again   = rows(under, screen)
        val ops     = again.foldLeft(ops1) { (ops, u) =>
          val flow = paintNode(
            ops :+ Op.Clear(u, None),
            laid,
            env,
            Loc.screen,
            u,
          )
          layers.foldLeft(flow) { case (o, (p, _)) =>
            paintNode(
              o,
              laid,
              env,
              p,
              clipOf(laid, p).intersect(screen).intersect(u),
              Cover.Opaque,
            )
          }
        }
        Plan(
          env.screen,
          ops,
          Plan.rowSpans(rects ++ regions ++ again, screen),
          top.size,
        )
    end apply

    /** The whole rows of `screen` that the rects of `rs` touch, as disjoint
      * rects in row order (the rows that touch merge).
      */
    // claude 2026-10-19: created (L1b)
    def rows(rs: Vector[Rect], screen: Rect): Vector[Rect] =
      rs.map(r => (r.y, r.bottom))
        .sorted
        .foldLeft(Vector.empty[(Int, Int)]) { case (acc, (y0, y1)) =>
          acc.lastOption match
              case Some((a, b)) if y0 <= b =>
                acc.updated(acc.size - 1, (a, b.max(y1)))
              case _                       => acc :+ (y0, y1)
        }
        .map((y0, y1) => Rect(screen.x, y0, screen.w, y1 - y0))

    /** True when the node at `p` is a layer or is inside one (on its chain of
      * owners).
      */
    // claude 2026-10-19: created (L1b)
    def inLayer(laid: Laid, p: Loc): Boolean =
        @annotation.tailrec
        def up(q: Option[Loc]): Boolean = q.flatMap(laid.nodes.get) match
            case None                        => false
            case Some(n) if n.layer.nonEmpty => true
            case Some(n)                     => up(n.owner)
        up(Some(p))

    /** The layers of `laid` in paint order, each with its box
      * ([[Layout.layerBox]] inside the screen): by `zIndex`, the equal ones in
      * document order, and each layer before the layers inside it, which keep
      * that order among them (see the object).
      */
    // claude 2026-10-19: created (L1b)
    def paintOrder(laid: Laid): Vector[(Loc, Rect)] =
      if laid.layers.isEmpty then Vector.empty
      else
          val screen                              = laid.nodes.get(Loc.screen).fold(Rect.empty)(_.rect)
          // the key of a node: the (zIndex, document place) of each layer on
          // its chain of owners, the outermost first
          def key(p: Loc): List[(Int, List[Int])] =
            chain(laid, p).map((z, n) => (z, docPlace(laid, n)))
          laid.layers.toVector
            .flatMap(p => laid.nodes.get(p).map(n => (p, n)))
            .map((p, n) =>
              (p, key(p), Layout.layerBox(laid, n).intersect(screen)),
            )
            .sortWith((a, b) => before(a._2, b._2))
            .map((p, _, box) => (p, box))
    end paintOrder

    /** The (zIndex, node) of each layer on the chain of owners of `p` (`p`
      * itself too), the outermost first.
      */
    // claude 2026-10-19: created (L1b)
    private def chain(laid: Laid, p: Loc): List[(Int, Node)] =
        @annotation.tailrec
        def up(q: Option[Loc], acc: List[(Int, Node)]): List[(Int, Node)] =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                up(n.owner, n.layer.fold(acc)(l => (l.z, n) :: acc))
        up(Some(p), Nil)
    end chain

    /** The document place of `n`: the index of each node on its chain of owners
      * in the kids of its owner, the outermost first. One place is before
      * another in document order when it is less (a prefix first).
      */
    // claude 2026-10-19: created (L1b)
    private def docPlace(laid: Laid, n: Node): List[Int] =
        @annotation.tailrec
        def up(q: Option[Node], acc: List[Int]): List[Int] = q match
            case None    => acc
            case Some(m) =>
              m.kind match
                  case Kind.Screen => acc
                  case _           => up(m.owner.flatMap(laid.nodes.get), m.index :: acc)
        up(Some(n), Nil)
    end docPlace

    /** True when the paint key `a` is before `b`: the first pair that differs
      * decides (a lower zIndex, then an earlier document place); a key that is
      * a prefix of the other is first (a layer before the layers inside it).
      */
    // claude 2026-10-19: created (L1b)
    @annotation.tailrec
    private def before(
        a: List[(Int, List[Int])],
        b: List[(Int, List[Int])],
    ): Boolean =
      (a, b) match
          case (Nil, Nil)                               => false
          case (Nil, _)                                 => true
          case (_, Nil)                                 => false
          case ((za, _) :: _, (zb, _) :: _) if za != zb => za < zb
          case ((_, pa) :: _, (_, pb) :: _) if pa != pb => less(pa, pb)
          case (_ :: ra, _ :: rb)                       => before(ra, rb)

    /** The order of document places: the first index that differs decides; a
      * prefix is first.
      */
    @annotation.tailrec
    private def less(a: List[Int], b: List[Int]): Boolean = (a, b) match
        case (Nil, Nil)                 => false
        case (Nil, _)                   => true
        case (_, Nil)                   => false
        case (x :: _, y :: _) if x != y => x < y
        case (_ :: xs, _ :: ys)         => less(xs, ys)

    /** A node is clipped by the ports of its owners: the content box of a box,
      * the port of a viewport. A layer is clipped by the clip of its containing
      * block ([[Layout.overlayClip]]), not by the boxes of its owners; the
      * nodes inside a layer by the ports of their owners up to the layer, and
      * by the clip of the layer.
      */
    // claude 2026-10-19: changed: the port of a viewport (L1a); the layers
    // (L1b)
    def clipOf(laid: Laid, p: Loc): Rect =
        @annotation.tailrec
        def up(q: Option[Loc], acc: Rect): Rect =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                val here = acc.intersect(n.kind match
                    case Kind.Screen => n.rect
                    case _           => n.port)
                n.layer match
                    case Some(l) =>
                      here.intersect(Layout.overlayClip(laid, l.cb))
                    case None    => up(n.owner, here)
        val all                                 = Rect(0, 0, Int.MaxValue / 2, Int.MaxValue / 2)
        laid.nodes
          .get(p)
          .fold(Rect.empty)(n =>
            n.layer match
                case Some(l) => Layout.overlayClip(laid, l.cb)
                case None    => up(n.owner, all),
          )
    end clipOf

    /** The rows of a text at `rect` that lie inside `clip`, as line indexes
      * from 0 to `rect.h`. Paint writes only these rows.
      */
    // claude 2026-10-19: created (PF-a, F3): the rows of the paint cull
    def visibleRows(rect: Rect, clip: Rect): Range =
      (clip.y - rect.y).max(0) until (clip.bottom - rect.y).min(rect.h)

    /** What a background does to the cells under it: `Fill` gives them the
      * colour and keeps their symbols (in the flow nothing is under a box);
      * `Opaque` clears them first (in a layer, the flow and the lower layers
      * are under it, and a box with a background hides them, as in CSS).
      */
    // claude 2026-10-19: created (L1b)
    enum Cover derives CanEqual:
        case Fill, Opaque

    /** The kids of `n` in the flow: not layers. A layer paints in the layer
      * pass of [[apply]].
      */
    // claude 2026-10-19: created (L1b)
    private def flowKids(laid: Laid, n: Node): Vector[Loc] =
      if laid.layers.isEmpty then n.kids
      else n.kids.filterNot(laid.layers.contains)

    /** `ops` and then the ops of the node at `p` and its subtree inside `clip`
      * (a clip inside the screen). Every op of a subtree lies inside `n.rect`
      * and `clip` (law I4: fill, border, decoration and text take that area,
      * and the kids take the content box inside it). So a node whose rect does
      * not meet the clip is skipped with its subtree, and a text paints only
      * its visible rows (the paint cull).
      */
    // claude 2026-10-19: changed (P5): it adds ops to `ops`; it writes
    // nothing; changed: the layers paint in their own pass (L1b)
    def paintNode(
        ops: Vector[Op],
        laid: Laid,
        env: Scene,
        p: Loc,
        clip: Rect,
        cover: Cover = Cover.Fill,
    ): Vector[Op] =
      laid.nodes.get(p).fold(ops) { n =>
        n.kind match
            case Kind.Screen                         =>
              val inner = n.rect.intersect(clip)
              flowKids(laid, n).foldLeft(ops)((o, k) =>
                paintNode(o, laid, env, k, inner, cover),
              )
            case _ if n.rect.intersect(clip).isEmpty => ops
            // claude 2026-10-19: changed: the text flow; the ellipsis (L1a)
            case Kind.Txt(txt, flow)                 =>
              val lines =
                Layout
                  .textLines(n.props.text(txt), n.rect.w, flow)
                  .map(l =>
                    if flow.cut == Cut.Ellipsis && Layout
                          .textWidth(l) > n.rect.w
                    then ellipsis(l, n.rect.w)
                    else l,
                  )
              val style = n.props.text.style
              val area  = n.rect.intersect(clip)
              visibleRows(n.rect, area).foldLeft(ops) { (o, i) =>
                lines.lift(i).fold(o) { line =>
                  val w = Layout.textWidth(line)
                  val x = n.props.text.align match
                      case TextAlign.center =>
                        n.rect.x + (n.rect.w - w).max(0) / 2
                      case TextAlign.right  => n.rect.x + (n.rect.w - w).max(0)
                      case _                => n.rect.x
                  o ++ text(x, n.rect.y + i, line, style, area)
                }
              }
            case Kind.El(e)                          =>
              val area  = n.rect.intersect(clip)
              // claude 2026-10-19: changed: in a layer, a background covers
              // what is under it (a clear); a layer with no background shows
              // it (L1b)
              val fill  = n.props.bg.toVector.map(bg =>
                cover match
                    case Cover.Opaque => Op.Clear(area, Some(bg))
                    case Cover.Fill   => Op.Fill(area, bg),
              )
              val frame =
                n.props.border.toVector
                  .flatMap(b => border(n.rect, b, n.props.text, area))
              // claude 2026-10-19: changed: the kids of a viewport are clipped
              // to its port (L1a)
              val inner = n.port.intersect(clip)
              // claude 2026-10-19: created (L1a): the scroll indicator; a
              // focused viewport has a heavy track (L1a review)
              val look  = BarLook.of(n.props)
              val bar   = n.view.toVector.flatMap(v =>
                indicator(
                  if env.focus.contains(n.path) then look.focused else look,
                  v,
                  area,
                ),
              )
              flowKids(laid, n).foldLeft(
                ops ++ fill ++ frame ++ decorate(laid, env, n, e, area),
              )((o, k) => paintNode(o, laid, env, k, inner, cover)) ++ bar
      }

    /** The thumb of a scroll indicator of `len` cells: its first cell and its
      * number of cells, for a port of `port` rows over `content` rows at the
      * offset `offset`. The thumb has the share of the track that the port has
      * of the content (at least 1 cell); it is at the top at offset 0 and at
      * the bottom at the last offset.
      */
    // claude 2026-10-19: created (L1a)
    def thumb(len: Int, port: Int, content: Int, offset: Int): (Int, Int) =
        val size = ((len * port + content / 2) / content.max(1)).max(1).min(len)
        val room = len - size
        val last = content - port
        val at   =
          if last <= 0 then 0 else ((offset * room + last / 2) / last).min(room)
        (at.max(0), size)

    /** The ops of the scroll indicator of the view `v` inside `clip`. `look`
      * gives its glyphs and styles, so a theme can give another look.
      */
    // claude 2026-10-19: created (L1a)
    def indicator(look: BarLook, v: View, clip: Rect): Vector[Op] =
      v.bar.toVector.flatMap { bar =>
        val (at, size) = thumb(bar.h, v.port.h, v.content.h, v.offset.y)
        (0 until bar.h).toVector.flatMap { i =>
          val (sym, st) =
            if i >= at && i < at + size then (look.thumb, look.thumbStyle)
            else (look.track, look.trackStyle)
          put(bar.x, bar.y + i, sym, st, clip)
        }
      }

    /** The look of a scroll indicator: the glyph and the style of its track and
      * of its thumb.
      */
    // claude 2026-10-19: created (L1a)
    final case class BarLook(
        track: String,
        trackStyle: JStyle,
        thumb: String,
        thumbStyle: JStyle,
    ):
        /** The look of the indicator of a focused viewport: a heavy track
          * ("┃"), as a focused border becomes heavy. It is the focus mark of a
          * viewport with no border.
          */
        // claude 2026-10-19: created (L1a review)
        def focused: BarLook = copy(track = "┃")
    end BarLook

    object BarLook:
        /** The default look of the indicator of an element with the props `p`:
          * a thin line for the track (dim, or the track colour of
          * `scrollbarColor`) and a full block for the thumb (the text colour,
          * or the thumb colour of `scrollbarColor`).
          */
        def of(p: Props): BarLook =
            val base       = p.text.copy(underline = false, reverse = false)
            val (th, tr)   = p.scrollbarColor.getOrElse((None, None))
            val thumbStyle = th.fold(base.style)(c => JStyle.empty().withFg(c))
            val trackStyle =
              tr.fold(base.copy(dim = true).style)(c =>
                JStyle.empty().withFg(c),
              )
            BarLook("│", trackStyle, "█", thumbStyle)
        end of
    end BarLook

    /** The op that writes `symbol` at (x, y), when the cell is inside `clip`. */
    // claude 2026-10-19: created (P5): the pure `Canvas.put`
    private def put(
        x: Int,
        y: Int,
        symbol: String,
        style: JStyle,
        clip: Rect,
    ): Option[Op] =
      Option.when(
        x >= clip.x && x < clip.right && y >= clip.y && y < clip.bottom,
      )(Op.Put(x, y, symbol, style))

    /** The glyphs of `s` as jatatui's `Buffer.setStringn` finds them: grapheme
      * clusters, without the ones that hold a control character or take no
      * cell.
      */
    // claude 2026-10-19: created (P5)
    def glyphs(s: String): Vector[Glyph] =
      TextEdit.graphemes(s).flatMap { g =>
        val control =
          g.codePoints.nn
            .anyMatch(cp => Character.getType(cp) == Character.CONTROL.toInt)
        val w       = if control then 0 else Layout.textWidth(g)
        Option.when(w > 0)(Glyph(g, w))
      }

    /** `s` in exactly `cells` cells: its whole glyphs that fit, then spaces. A
      * wide glyph that does not fit is dropped, not split.
      */
    // claude 2026-10-19: created (L1a): the cut by chars of the pickers moved
    // their arrow out of the box when the label had wide characters
    def fit(s: String, cells: Int): String =
        val gs   = glyphs(s)
        val kept = gs
          .zip(gs.scanLeft(0)(_ + _.width).drop(1))
          .takeWhile(_._2 <= cells)
        kept.map(_._1.symbol).mkString +
          " " * (cells - kept.lastOption.fold(0)(_._2)).max(0)

    /** `line` cut to `width` cells with "…" (1 cell) in its last cell: the
      * glyphs that fit in `width - 1` cells, then "…". The cut is by cells: a
      * wide glyph that does not fit next to the ellipsis is dropped whole.
      */
    // claude 2026-10-19: created (L1a)
    def ellipsis(line: String, width: Int): String =
      if width <= 0 then ""
      else
          val gs = glyphs(line)
          gs.zip(gs.scanLeft(0)(_ + _.width).drop(1))
            .takeWhile(_._2 <= width - 1)
            .map(_._1.symbol)
            .mkString + "…"

    /** The ops of `text` written at (x, y) inside `clip`. The clip cuts the
      * text by cells, not by chars: each glyph keeps the cells that it has in a
      * paint without the clip, and the text stops at the first glyph that does
      * not end before the right edge of the clip (that cell keeps its value, as
      * `Buffer.setStringn` does).
      *
      * Law: inside the clip, the cells are the cells of the same text painted
      * on the whole screen, except the cells of a wide glyph that the right
      * edge cuts. A wide glyph that the left edge cuts shows its hidden half in
      * the clip: an empty cell (a space with no style).
      */
    // claude 2026-10-19: created (P5): the pure `Canvas.text`, with the clip
    // by cells (the old canvas skipped chars, so a wide character, an emoji or
    // a combining mark before the left edge moved the text)
    def text(
        x: Int,
        y: Int,
        text: String,
        style: JStyle,
        clip: Rect,
    ): Vector[Op] =
      if y < clip.y || y >= clip.bottom then Vector.empty
      else
          val gs            = glyphs(text)
          // each glyph at its cell, up to the first that ends right of the clip
          val placed        = gs
            .zip(gs.scanLeft(x)(_ + _.width))
            .takeWhile((g, gx) => gx + g.width <= clip.right)
          val (left, shown) = placed.span((_, gx) => gx < clip.x)
          // the hidden half of a glyph that starts left of the clip
          val hidden        = left.lastOption.collect {
            case (g, gx) if gx + g.width > clip.x =>
              Op.Clear(Rect(clip.x, y, gx + g.width - clip.x, 1), None)
          }
          val run           = shown.headOption.map((_, sx) =>
            Op.Run(sx, y, shown.map(_._1), style),
          )
          hidden.toVector ++ run.toVector

    // claude 2026-10-19: changed (P5): it gives the ops of the border
    private def border(
        r: Rect,
        b: Border,
        text: TextAttrs,
        clip: Rect,
    ): Vector[Op] =
        val style =
          b.fg.fold(text.copy(underline = false, reverse = false).style)(c =>
            JStyle.empty().withFg(c),
          )
        val g     = b.glyphs
        val s     = b.sides
        val cells =
          if r.w <= 0 || r.h <= 0 then Vector.empty
          else
              Vector(
                if s.t > 0 then (r.x until r.right).map(x => (x, r.y, g.h))
                else Vector.empty,
                if s.b > 0 then
                    (r.x until r.right).map(x => (x, r.bottom - 1, g.h))
                else Vector.empty,
                if s.l > 0 then (r.y until r.bottom).map(y => (r.x, y, g.v))
                else Vector.empty,
                if s.r > 0 then
                    (r.y until r.bottom).map(y => (r.right - 1, y, g.v))
                else Vector.empty,
                Option.when(s.t > 0 && s.l > 0)((r.x, r.y, g.tl)).toVector,
                Option
                  .when(s.t > 0 && s.r > 0)((r.right - 1, r.y, g.tr))
                  .toVector,
                Option
                  .when(s.b > 0 && s.l > 0)((r.x, r.bottom - 1, g.bl))
                  .toVector,
                Option
                  .when(s.b > 0 && s.r > 0)((r.right - 1, r.bottom - 1, g.br))
                  .toVector,
              ).flatten
        cells.flatMap((x, y, sym) => put(x, y, sym, style, clip))
    end border

    private def boundValue(env: Scene, p: Loc): Option[Any] =
      env.content(p) match
          case Some(Content.Value(v)) => Some(v)
          case _                      => None

    def textValue(env: Scene, p: Loc, ti: TextInput): String =
      ti.value match
          case Present(UI.Bound.Ref(_))   =>
            boundValue(env, p).map(_.toString).getOrElse("")
          case Present(UI.Bound.Const(v)) => v
          case _                          => boundValue(env, p).map(_.toString).getOrElse("")

    def checkedValue(env: Scene, p: Loc, bi: BooleanInput): Boolean =
      bi.checked match
          case Present(UI.Bound.Ref(_))   => boundValue(env, p).contains(true)
          case Present(UI.Bound.Const(v)) => v
          case _                          => false

    def pickerValue(
        env: Scene,
        p: Loc,
        value: Maybe[UI.Bound[String]],
    ): String =
      value match
          case Present(UI.Bound.Ref(_))   =>
            boundValue(env, p).map(_.toString).getOrElse("")
          case Present(UI.Bound.Const(v)) => v
          case _                          => ""

    /** The ops of a textarea with the view `v` ([[Layout.areaView]]): each row
      * of the port is an underlined field, and the visual lines of the text
      * ([[AreaText]]) from the row of the offset fill it, clipped to the port.
      * An empty textarea with no focus shows its placeholder, dim and italic.
      * The focused textarea shows its caret: a reversed cell at the row and the
      * column of the caret (the glyph at the caret when it fits, else a space;
      * at the right edge when the caret is after the last cell).
      */
    // claude 2026-10-19: created (L1c)
    private def area(
        env: Scene,
        n: Node,
        ta: Textarea,
        v: View,
        style: JStyle,
        focused: Boolean,
        clip: Rect,
    ): Vector[Op] =
        val value     = textValue(env, n.path, ta)
        val field     =
          style.withAddModifier(jatatui.core.style.Modifier.UNDERLINED)
        val hint      = value.isEmpty && !focused
        val shown     = if hint then ta.placeholder.getOrElse("") else value
        val textStyle =
          if hint then
              field
                .withAddModifier(jatatui.core.style.Modifier.DIM)
                .withAddModifier(jatatui.core.style.Modifier.ITALIC)
          else field
        val gs        = TextEdit.graphemes(shown)
        val lines     = AreaText.linesOf(gs, v.port.w)
        val port      = v.port.intersect(clip)
        val rows      = (0 until v.port.h).toVector.flatMap { i =>
          val y     = v.port.y + i
          val blank = (v.port.x until v.port.right).toVector.flatMap(x =>
            put(x, y, " ", field, port),
          )
          val line  = lines
            .lift(v.offset.y + i)
            .fold("")(l => gs.slice(l.start, l.end).mkString)
          blank ++ text(v.port.x, y, line, textStyle, port)
        }
        val cursor    =
          if !focused then Vector.empty
          else
              val c   = env.caret(n.path).max(0).min(gs.size)
              val r   = AreaText.row(lines, c)
              val col = lines.lift(r).fold(0)(AreaText.column(gs, _, c))
              val x   = v.port.x + col.min((v.port.w - 1).max(0))
              val g   = gs
                .lift(c)
                .filter(g =>
                  !AreaText.isBreak(g) && col + Layout.textWidth(g) <= v.port.w,
                )
                .getOrElse(" ")
              put(
                x,
                v.port.y + r - v.offset.y,
                g,
                field.withAddModifier(jatatui.core.style.Modifier.REVERSED),
                port,
              ).toVector
        rows ++ cursor
    end area

    /** The ops of what the runner draws for an element besides its children:
      * brackets, fields, boxes, markers.
      */
    // claude 2026-10-19: changed (P5): it gives ops
    private def decorate(
        laid: Laid,
        env: Scene,
        n: Node,
        e: Element,
        clip: Rect,
    ): Vector[Op] =
        val r                                         = n.rect.inset(n.props.border.fold(Sides.zero)(_.sides))
        val style                                     = n.props.text.style
        val focused                                   = env.focus.contains(n.path)
        def at(x: Int, y: Int, s: String, st: JStyle) = text(x, y, s, st, clip)
        e match
            case _: Button      =>
              val fill =
                if n.props.text.reverse then
                    (r.x until r.right).toVector.flatMap(x =>
                      put(x, r.y + r.h / 2, " ", style, clip),
                    )
                else Vector.empty
              fill ++ at(r.x, r.y + r.h / 2, "[", style) ++
                at(r.right - 1, r.y + r.h / 2, "]", style)
            case cb: Checkbox   =>
              at(
                r.x,
                r.y,
                if checkedValue(env, n.path, cb) then "[x]" else "[ ]",
                style,
              )
            case rb: Radio      =>
              at(
                r.x,
                r.y,
                if checkedValue(env, n.path, rb) then "(•)" else "( )",
                style,
              )
            case _: Hr          => at(r.x, r.y, "─" * r.w, style)
            case i: Img         =>
              at(r.x, r.y, s"[${i.alt.getOrElse("img")}]", style)
            case _: SvgNode     =>
              at(
                r.x,
                r.y,
                "[chart]",
                style.withAddModifier(jatatui.core.style.Modifier.DIM),
              )
            case s: Select      =>
              val v      = pickerValue(env, n.path, s.value)
              val labels = s.children.toVector.collect { case o: Opt =>
                (
                  o.value.getOrElse(""),
                  o.children.toVector.collect { case t: Text =>
                    t.value
                  }.mkString,
                )
              }
              val label  = labels
                .find(_._1 == v)
                .map(_._2)
                .orElse(labels.headOption.map(_._2))
                .getOrElse("")
              // claude 2026-10-19: changed: the label fits by cells, so the
              // arrow stays in the last cell with a wide label (L1a)
              at(r.x, r.y, fit(label, r.w - 1) + "▾", style)
            case d: Dropdown    =>
              val v     = pickerValue(env, n.path, d.value)
              val label = d.options.toVector
                .find(_._1 == v)
                .map(_._2)
                .orElse(d.options.headOption.map(_._2))
                .getOrElse("")
              // claude 2026-10-19: changed: the label fits by cells (L1a)
              at(r.x, r.y, fit(label, r.w - 1) + "▾", style)
            case ri: RangeInput =>
              // a constant value (`.value(50.0)`) comes from the element, as in
              // the text and picker paths; only a bound ref comes from the
              // scene
              val v   = (ri.value match
                  case Present(UI.Bound.Const(c)) => Some(c)
                  case _                          =>
                    boundValue(env, n.path).collect { case d: Double => d }
              )
                .getOrElse(0.0)
              val lo  = ri.min.getOrElse(0.0)
              val hi  = ri.max.getOrElse(100.0)
              val pos = (((v - lo) / (hi - lo).max(1e-9)) * (r.w - 1)).toInt
                .max(0)
                .min((r.w - 1).max(0))
              at(
                r.x,
                r.y,
                (0 until r.w)
                  .map(i => if i == pos then "●" else "─")
                  .mkString,
                style,
              )
            // claude 2026-10-19: created (L1c): the lines of a textarea
            case ta: Textarea   =>
              n.view.fold(Vector.empty[Op])(v =>
                area(env, n, ta, v, style, focused, clip),
              )
            case ti: TextInput  =>
              // the caret is a grapheme index (World.carets); the text scrolls
              // and the cursor lands by cell widths (a wide character takes
              // two cells), not by UTF-16 chars
              val value     = textValue(env, n.path, ti)
              val glyphs    = e match
                  case _: PasswordInput =>
                    TextEdit.graphemes(value).map(_ => "*")
                  case _                => TextEdit.graphemes(value)
              val widths    = glyphs.map(Layout.textWidth)
              val caret     = env.caret(n.path).max(0).min(glyphs.size)
              // the first grapheme shown: the cells from it to the caret, plus
              // the cell of the cursor, fit in the field (`scrollStart`, one
              // walk back from the caret)
              val cursorW   = widths.lift(caret).getOrElse(1).max(1)
              val scroll    = scrollStart(widths, caret, cursorW, r.w)
              val first     = scroll._1
              val visible   = glyphs
                .drop(first)
                .zip(widths.drop(first))
                .scanLeft(("", 0)) { case ((_, used), (g, w)) => (g, used + w) }
                .drop(1)
                .takeWhile(_._2 <= r.w)
                .map(_._1)
                .mkString
              val field     =
                style.withAddModifier(jatatui.core.style.Modifier.UNDERLINED)
              val shown     =
                if glyphs.isEmpty && !focused then ti.placeholder.getOrElse("")
                else visible
              val textStyle =
                if glyphs.isEmpty && !focused then
                    field
                      .withAddModifier(jatatui.core.style.Modifier.DIM)
                      .withAddModifier(jatatui.core.style.Modifier.ITALIC)
                else field
              val blank     =
                (r.x until r.right).toVector.flatMap(x =>
                  put(x, r.y, " ", field, clip),
                )
              // claude 2026-10-19: changed: the glyph at the caret shows only
              // when it fits in the field; a wide glyph in the last cell (a
              // field of 1 cell) named a cell outside the field (L1c: the
              // painter bug of L1b)
              val atCaret   = glyphs
                .lift(caret)
                .filter(g => scroll._2 + Layout.textWidth(g) <= r.w)
                .getOrElse(" ")
              val cursor    =
                if focused then
                    put(
                      r.x + scroll._2,
                      r.y,
                      atCaret,
                      field.withAddModifier(
                        jatatui.core.style.Modifier.REVERSED,
                      ),
                      clip,
                    ).toVector
                else Vector.empty
              blank ++ at(r.x, r.y, shown, textStyle) ++ cursor
            case _: Li          =>
              n.owner.flatMap(laid.nodes.get).flatMap(_.props.listStyle) match
                  case Some(kyo.Style.ListStyle.disc)    =>
                    at(r.x, r.y, "• ", style)
                  case Some(kyo.Style.ListStyle.decimal) =>
                    at(r.x, r.y, s"${n.index + 1}. ", style)
                  case _                                 => Vector.empty
            case _              => Vector.empty
        end match
    end decorate
end Paint
