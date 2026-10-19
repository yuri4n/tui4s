package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import jatatui.core.backend.{Backend, TestBackend}
import jatatui.core.buffer.{Buffer, BufferUpdate}
import jatatui.core.layout.Rect as JRect
import jatatui.core.style.{Color as JColor, Style as JStyle}
import kyo.*
import kyo.Style.TextAlign
import kyo.UI.Ast.*

import scala.jdk.CollectionConverters.*

/** The kept frame.
  *
  * THE ONE MUTABLE PLACE of the runner (documented exception to "no var /
  * while"): jatatui's `Buffer` and `Cell` are mutable, not thread-safe Java
  * objects. `back` is the kept buffer that paint writes into; `front` is a copy
  * of what the terminal shows. Only the runner fiber touches a `Canvas`. Paint
  * writes only inside damaged rectangles, and `flush` compares `back` with
  * `front` only inside those rectangles and sends only the cells that differ.
  */
private[tui4s] final class Canvas private (
    private var back: Buffer,  // scalafix:ok DisableSyntax.var; P5: the Canvas seam
    private var front: Buffer, // scalafix:ok DisableSyntax.var; P5: the Canvas seam
    val backend: Backend,
    flushSeamNanos: Long, /* claude 2026-10-12: created (the test seam
     * `Knobs.flushSeam`) */
):
    private var writes = 0 // scalafix:ok DisableSyntax.var; P5: FrameOut replaces the counter

    // claude 2026-10-13: created (T1, the bot fix of the review thread
    // CountingBackend.scala:33 of PR 5): set by `close`, read and written only
    // under the backend's monitor
    private var closed = false // scalafix:ok DisableSyntax.var; P5: the Canvas seam

    def size: Size = Size(back.area.width, back.area.height)

    /** Stops all output, for the release of the terminal: after it, `flush` and
      * `resize` do not touch the backend. It takes the backend's monitor, which
      * a flush holds while it draws: so it waits for a draw in progress, and
      * the release (also from the JVM shutdown hook, on another thread)
      * restores the terminal only after the last frame has ended. Idempotent.
      */
    // claude 2026-10-13: created (T1, the bot fix of the review thread
    // CountingBackend.scala:33: the close() guard of PR 5 CountingBackend, and
    // the wait for a draw in progress)
    def close(): Unit = backend.synchronized { closed = true } // scalafix:ok DisableSyntax.jvmSync; P5: the Canvas monitor

    /** Cell writes since the last call (the "cells repainted" measure). */
    def takeWrites(): Int =
        val w = writes
        writes = 0
        w

    /** A new size. A `TestBackend` (the headless kit) has no terminal that
      * resizes it, so the canvas resizes it here: on the runner fiber, the one
      * fiber that writes the backend (it is not thread-safe). The backend's
      * monitor is held: a reader of a `TestBackend` holds it too (see `flush`).
      */
    /* claude 2026-10-12: changed (review R2-4): the TestBackend is resized
     * here; */
    // changed (PR #11 review: Codex, Headless.scala:88): under the monitor
    // claude 2026-10-13: changed (T1, bot fix CountingBackend.scala:33): no
    // backend call after `close`
    def resize(s: Size): Unit =
      backend.synchronized { // scalafix:ok DisableSyntax.jvmSync; P5: the Canvas monitor
        if !closed then
            backend match
                case tb: TestBackend => tb.resize(s.w, s.h)
                case _               => ()
        back = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        front = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        // claude 2026-10-13: changed (T1): a failed write is TerminalIO
        if !closed then Canvas.terminalIO(backend.clear())
      }

    private def inside(x: Int, y: Int): Boolean =
      x >= 0 && y >= 0 && x < back.area.width && y < back.area.height

    def clear(r: Rect, bg: Option[JColor]): Unit =
        val c = r.intersect(Rect(0, 0, back.area.width, back.area.height))
        for
            y <- c.y until c.bottom
            x <- c.x until c.right
        do
            val cell = back.cellAt(x, y)
            cell.reset()
            bg.foreach(cell.setBg)
            writes += 1
        end for
    end clear

    def fillBg(r: Rect, bg: JColor, clip: Rect): Unit =
        val c = r
          .intersect(clip)
          .intersect(Rect(0, 0, back.area.width, back.area.height))
        for
            y <- c.y until c.bottom
            x <- c.x until c.right
        do
            back.cellAt(x, y).setBg(bg)
            writes += 1
    end fillBg

    def put(x: Int, y: Int, symbol: String, style: JStyle, clip: Rect): Unit =
      if inside(
            x,
            y,
          ) && x >= clip.x && x < clip.right && y >= clip.y && y < clip.bottom
      then
          val cell = back.cellAt(x, y)
          cell.setSymbol(symbol)
          cell.setStyle(style)
          writes += 1

    /** Writes the ops of `plan` in order (the old write path, adapted to the
      * plan of the pure painter).
      */
    // claude 2026-10-19: created (P5): the old canvas writes a plan
    def write(plan: Plan): Unit =
      plan.ops.foreach {
        case Op.Clear(r, bg)      => clear(r, bg)
        case Op.Fill(r, bg)       => fillBg(r, bg, r)
        case Op.Put(x, y, s, st)  => put(x, y, s, st, Rect(x, y, 1, 1))
        case Op.Run(x, y, gs, st) =>
          val _ = gs.foldLeft(x) { (cx, g) =>
            if inside(cx, y) && inside(cx + g.width - 1, y) then
                back.cellAt(cx, y).setSymbol(g.symbol).setStyle(st)
                (cx + 1 until cx + g.width).foreach(hx =>
                  back.cellAt(hx, y).reset(),
                )
                writes += g.width
            cx + g.width
          }
      }

    /** Sends the cells that differ between `back` and `front` inside `dirty`.
      * Returns the number of cells sent.
      */
    def flush(dirty: Vector[Rect]): Int =
        val screen  = Rect(0, 0, back.area.width, back.area.height)
        val updates = dirty.iterator.flatMap { r =>
          val c = r.intersect(screen)
          for
              y <- (c.y until c.bottom).iterator
              x <- (c.x until c.right).iterator
              if back.cellAt(x, y) != front.cellAt(x, y)
          yield
              val copy = back.cellAt(x, y).copy()
              front.content(front.indexOf(x, y)) = copy.copy()
              BufferUpdate(x, y, copy)
          end for
        }.toVector
        if updates.nonEmpty then
            /* claude 2026-10-12: changed (PR #11 review: Codex,
             * Headless.scala:88): the */
            // backend's monitor makes the flush one step for a reader of a
            // `TestBackend` (the headless kit reads under the same monitor), so
            // a screen is never a torn frame. On the terminal backend, the
            // other holder of the monitor is `close` (the release). With the
            // test seam, the draw goes one row at a time with a spin between
            // the rows.
            backend.synchronized { // scalafix:ok DisableSyntax.jvmSync; P5: the Canvas monitor
              // claude 2026-10-13: changed (T1): a write to the terminal that
              // fails is TuiError.TerminalIO (the run fails with it); after
              // `close` (the release) nothing is drawn (bot fix
              // CountingBackend.scala:33)
              if !closed then
                  Canvas.terminalIO {
                    if flushSeamNanos <= 0L then backend.draw(updates.asJava)
                    else
                        updates.grouped(back.area.width.max(1)).foreach {
                          chunk =>
                            backend.draw(chunk.asJava)
                            val t0 = java.lang.System.nanoTime() // scalafix:ok DisableSyntax.jvmGlobal; P5: no busy-spin in the flush seam
                            while java.lang.System // scalafix:ok DisableSyntax.while; P5: no busy-spin in the flush seam
                                  .nanoTime() - t0 < flushSeamNanos
                            do
                                (
                            )
                        }
                    end if
                    backend.flush()
                  }
            }
        end if
        updates.size
    end flush
end Canvas

private[tui4s] object Canvas:
    /** Runs `write`, a write to the terminal; an `IOException` becomes
      * [[tui4s.TuiError.TerminalIO]], which ends the run with that failure.
      */
    // claude 2026-10-13: created (T1)
    def terminalIO(write: => Unit): Unit =
      try write
      catch case e: java.io.IOException => throw tui4s.TuiError.TerminalIO(e) // scalafix:ok DisableSyntax.throw; P5: Abort[TuiError] in Canvas.draw

    /** @param flushSeam
      *   the test seam `Knobs.flushSeam` (zero in a real run)
      */
    /* claude 2026-10-12: changed (PR #11 review: Codex, Headless.scala:88):
     * flushSeam */
    def apply(backend: Backend, flushSeam: Duration = Duration.Zero): Canvas =
        val s = backend.size()
        new Canvas(
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          backend,
          flushSeam.toNanos,
        )
    end apply
end Canvas

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
  */
// claude 2026-10-19: changed (P5): a pure function to a Plan; the canvas
// writes the plan
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
      * each root of `damage` that is not under another root. The spans of the
      * plan are the rects of the clears and of the roots, on the screen of
      * `env`.
      */
    // claude 2026-10-19: changed (P5): the clears and the roots give ops; the
    // clip of a root is cut to the screen
    def apply(laid: Laid, env: Scene, damage: Vector[Damage]): Plan =
        val screen = Rect(0, 0, env.screen.w, env.screen.h)
        val clears = damage.collect { case c: Damage.Clear => c }.distinct
        val roots  = damage.collect { case Damage.Paint(p) => p }.distinct
        // a root under another root is painted by it (Loc.tops, PF-a, F2)
        val top    = Loc.tops(roots)
        val ops0   = clears.foldLeft(Vector.empty[Op]) { (ops, c) =>
          val r = c.rect.intersect(screen)
          if r.isEmpty then ops else ops :+ Op.Clear(r, c.bg)
        }
        val ops    = top.foldLeft(ops0)((ops, p) =>
          paintNode(ops, laid, env, p, clipOf(laid, p).intersect(screen)),
        )
        val rects  =
          clears.map(_.rect) ++ top.flatMap(p => laid.nodes.get(p).map(_.rect))
        Plan(env.screen, ops, Plan.rowSpans(rects, screen), top.size)
    end apply

    /** A node is clipped by the content boxes of its owners. */
    private def clipOf(laid: Laid, p: Loc): Rect =
        def up(q: Option[Loc], acc: Rect): Rect =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                up(
                  n.owner,
                  acc.intersect(n.kind match
                      case Kind.Screen => n.rect
                      case _           => n.content),
                )
        laid.nodes
          .get(p)
          .fold(Rect.empty)(n =>
            up(n.owner, Rect(0, 0, Int.MaxValue / 2, Int.MaxValue / 2)),
          )
    end clipOf

    /** The rows of a text at `rect` that lie inside `clip`, as line indexes
      * from 0 to `rect.h`. Paint writes only these rows.
      */
    // claude 2026-10-19: created (PF-a, F3): the rows of the paint cull
    def visibleRows(rect: Rect, clip: Rect): Range =
      (clip.y - rect.y).max(0) until (clip.bottom - rect.y).min(rect.h)

    /** `ops` and then the ops of the node at `p` and its subtree inside `clip`
      * (a clip inside the screen). Every op of a subtree lies inside `n.rect`
      * and `clip` (law I4: fill, border, decoration and text take that area,
      * and the kids take the content box inside it). So a node whose rect does
      * not meet the clip is skipped with its subtree, and a text paints only
      * its visible rows (the paint cull).
      */
    // claude 2026-10-19: changed (P5): it adds ops to `ops`; it writes nothing
    def paintNode(
        ops: Vector[Op],
        laid: Laid,
        env: Scene,
        p: Loc,
        clip: Rect,
    ): Vector[Op] =
      laid.nodes.get(p).fold(ops) { n =>
        n.kind match
            case Kind.Screen                         =>
              val inner = n.rect.intersect(clip)
              n.kids.foldLeft(ops)((o, k) => paintNode(o, laid, env, k, inner))
            case _ if n.rect.intersect(clip).isEmpty => ops
            case Kind.Txt(txt, wrapped)              =>
              val lines =
                Layout.textLines(n.props.text(txt), n.rect.w, wrapped)
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
              val fill  = n.props.bg.toVector.map(bg => Op.Fill(area, bg))
              val frame =
                n.props.border.toVector
                  .flatMap(b => border(n.rect, b, n.props.text, area))
              val inner = n.content.intersect(clip)
              n.kids.foldLeft(
                ops ++ fill ++ frame ++ decorate(laid, env, n, e, area),
              )((o, k) => paintNode(o, laid, env, k, inner))
      }

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

    /** The first glyphs of `gs` whose cells fit in `room` cells (it stops at
      * the first glyph that does not fit, as `Buffer.setStringn` does).
      */
    // claude 2026-10-19: created (P5)
    private def fitting(gs: Vector[Glyph], room: Int): Vector[Glyph] =
      gs.zip(gs.scanLeft(0)(_ + _.width).drop(1))
        .takeWhile(_._2 <= room)
        .map(_._1)

    /** The ops of `text` written at (x, y) inside `clip`: the glyphs from the
      * left edge of the clip that fit before its right edge.
      */
    // claude 2026-10-19: created (P5): the pure `Canvas.text`; it skips chars
    // at the left edge of the clip, as the old canvas did
    def text(
        x: Int,
        y: Int,
        text: String,
        style: JStyle,
        clip: Rect,
    ): Vector[Op] =
      if y < clip.y || y >= clip.bottom then Vector.empty
      else
          val skip = (clip.x - x).max(0)
          val sx   = x + skip
          val fit  = fitting(glyphs(text.drop(skip)), clip.right - sx)
          if fit.isEmpty then Vector.empty
          else Vector(Op.Run(sx, y, fit, style))

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
              at(
                r.x,
                r.y,
                (label + " " * r.w).take((r.w - 1).max(0)) + "▾",
                style,
              )
            case d: Dropdown    =>
              val v     = pickerValue(env, n.path, d.value)
              val label = d.options.toVector
                .find(_._1 == v)
                .map(_._2)
                .orElse(d.options.headOption.map(_._2))
                .getOrElse("")
              at(
                r.x,
                r.y,
                (label + " " * r.w).take((r.w - 1).max(0)) + "▾",
                style,
              )
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
              val cursor    =
                if focused then
                    put(
                      r.x + scroll._2,
                      r.y,
                      glyphs.lift(caret).getOrElse(" "),
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
