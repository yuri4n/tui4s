package tui4s.internal

import jatatui.core.backend.Backend
import jatatui.core.buffer.Buffer
import jatatui.core.buffer.BufferUpdate
import jatatui.core.layout.Rect as JRect
import jatatui.core.style.Color as JColor
import jatatui.core.style.Style as JStyle
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
final class Canvas private (
    private var back: Buffer,
    private var front: Buffer,
    val backend: Backend,
):
    private var writes = 0

    def size: Size = Size(back.area.width, back.area.height)

    /** Cell writes since the last call (the "cells repainted" measure). */
    def takeWrites(): Int =
        val w = writes
        writes = 0
        w

    def resize(s: Size): Unit =
        back = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        front = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        backend.clear()

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

    /** Writes `text` at (x, y), clipped to `clip`. Returns the number of cells
      * used.
      */
    def text(x: Int, y: Int, text: String, style: JStyle, clip: Rect): Int =
        if y < clip.y || y >= clip.bottom || y < 0 || y >= back.area.height then
            0
        else
            val skip    = (clip.x - x).max(0)
            val visible = text.drop(skip)
            val sx      = x + skip
            val maxW    = (clip.right.min(back.area.width) - sx).max(0)
            if maxW == 0 || visible.isEmpty then 0
            else
                val end  = back.setStringn(sx, y, visible, maxW, style)
                val used = end.x - sx
                writes += used
                used
        end if
    end text

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
            backend.draw(updates.asJava)
            backend.flush()
        updates.size
    end flush
end Canvas

object Canvas:
    def apply(backend: Backend): Canvas =
        val s = backend.size()
        new Canvas(
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          backend,
        )
end Canvas

/** Paints laid-out nodes into the kept buffer. */
object Paint:

    /** Applies the damage of one frame. Returns the rectangles to flush and the
      * number of subtrees painted.
      */
    def apply(
        canvas: Canvas,
        laid: Laid,
        env: Scene,
        damage: Vector[Damage],
    ): (Vector[Rect], Int) =
        val clears = damage.collect { case c: Damage.Clear => c }.distinct
        val roots  = damage.collect { case Damage.Paint(p) => p }.distinct
        // a root under another root is painted by it
        val top    =
          roots.filterNot(p => roots.exists(q => q != p && p.isUnder(q)))
        clears.foreach(c => canvas.clear(c.rect, c.bg))
        top.foreach(p => paintNode(canvas, laid, env, p, clipOf(laid, p)))
        (
          clears.map(_.rect) ++ top.flatMap(p => laid.nodes.get(p).map(_.rect)),
          top.size,
        )
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

    def paintNode(
        canvas: Canvas,
        laid: Laid,
        env: Scene,
        p: Loc,
        clip: Rect,
    ): Unit =
      laid.nodes.get(p).foreach { n =>
        n.kind match
            case Kind.Screen             =>
              n.kids.foreach(k => paintNode(canvas, laid, env, k, n.rect))
            case Kind.Txt(text, wrapped) =>
              val lines =
                Layout.textLines(n.props.text(text), n.rect.w, wrapped)
              val style = n.props.text.style
              val area  = n.rect.intersect(clip)
              lines.take(n.rect.h).zipWithIndex.foreach { (line, i) =>
                val w = Layout.textWidth(line)
                val x = n.props.text.align match
                    case TextAlign.center =>
                      n.rect.x + (n.rect.w - w).max(0) / 2
                    case TextAlign.right  => n.rect.x + (n.rect.w - w).max(0)
                    case _                => n.rect.x
                discard(canvas.text(x, n.rect.y + i, line, style, area))
              }
            case Kind.El(e)              =>
              val area  = n.rect.intersect(clip)
              n.props.bg.foreach(bg => canvas.fillBg(n.rect, bg, clip))
              n.props.border
                .foreach(b => border(canvas, n.rect, b, n.props.text, area))
              decorate(canvas, laid, env, n, e, area)
              val inner = n.content.intersect(clip)
              n.kids.foreach(k => paintNode(canvas, laid, env, k, inner))
      }

    private def discard(a: Any): Unit = ()

    private def border(
        canvas: Canvas,
        r: Rect,
        b: Border,
        text: TextAttrs,
        clip: Rect,
    ): Unit =
        val style =
          b.fg.fold(text.copy(underline = false, reverse = false).style)(c =>
            JStyle.empty().withFg(c),
          )
        val g     = b.glyphs
        val s     = b.sides
        if r.w > 0 && r.h > 0 then
            if s.t > 0 then
                (r.x until r.right).foreach(x =>
                  canvas.put(x, r.y, g.h, style, clip),
                )
            if s.b > 0 then
                (r.x until r.right).foreach(x =>
                  canvas.put(x, r.bottom - 1, g.h, style, clip),
                )
            if s.l > 0 then
                (r.y until r.bottom).foreach(y =>
                  canvas.put(r.x, y, g.v, style, clip),
                )
            if s.r > 0 then
                (r.y until r.bottom).foreach(y =>
                  canvas.put(r.right - 1, y, g.v, style, clip),
                )
            if s.t > 0 && s.l > 0 then canvas.put(r.x, r.y, g.tl, style, clip)
            if s.t > 0 && s.r > 0 then
                canvas.put(r.right - 1, r.y, g.tr, style, clip)
            if s.b > 0 && s.l > 0 then
                canvas.put(r.x, r.bottom - 1, g.bl, style, clip)
            if s.b > 0 && s.r > 0 then
                canvas.put(r.right - 1, r.bottom - 1, g.br, style, clip)
        end if
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

    /** What the runner draws for an element besides its children: brackets,
      * fields, boxes, markers.
      */
    private def decorate(
        canvas: Canvas,
        laid: Laid,
        env: Scene,
        n: Node,
        e: Element,
        clip: Rect,
    ): Unit =
        val r       = n.rect.inset(n.props.border.fold(Sides.zero)(_.sides))
        val style   = n.props.text.style
        val focused = env.focus.contains(n.path)
        e match
            case _: Button      =>
              if n.props.text.reverse then
                  (r.x until r.right).foreach(x =>
                    canvas.put(x, r.y + r.h / 2, " ", style, clip),
                  )
              discard(canvas.text(r.x, r.y + r.h / 2, "[", style, clip))
              discard(canvas.text(r.right - 1, r.y + r.h / 2, "]", style, clip))
            case cb: Checkbox   =>
              discard(
                canvas.text(
                  r.x,
                  r.y,
                  if checkedValue(env, n.path, cb) then "[x]" else "[ ]",
                  style,
                  clip,
                ),
              )
            case rb: Radio      =>
              discard(
                canvas.text(
                  r.x,
                  r.y,
                  if checkedValue(env, n.path, rb) then "(•)" else "( )",
                  style,
                  clip,
                ),
              )
            case _: Hr          =>
              discard(canvas.text(r.x, r.y, "─" * r.w, style, clip))
            case i: Img         =>
              discard(
                canvas.text(
                  r.x,
                  r.y,
                  s"[${i.alt.getOrElse("img")}]",
                  style,
                  clip,
                ),
              )
            case _: SvgNode     =>
              discard(
                canvas.text(
                  r.x,
                  r.y,
                  "[chart]",
                  style.withAddModifier(jatatui.core.style.Modifier.DIM),
                  clip,
                ),
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
              discard(
                canvas.text(
                  r.x,
                  r.y,
                  (label + " " * r.w).take((r.w - 1).max(0)) + "▾",
                  style,
                  clip,
                ),
              )
            case d: Dropdown    =>
              val v     = pickerValue(env, n.path, d.value)
              val label = d.options.toVector
                .find(_._1 == v)
                .map(_._2)
                .orElse(d.options.headOption.map(_._2))
                .getOrElse("")
              discard(
                canvas.text(
                  r.x,
                  r.y,
                  (label + " " * r.w).take((r.w - 1).max(0)) + "▾",
                  style,
                  clip,
                ),
              )
            case ri: RangeInput =>
              val v   = boundValue(env, n.path)
                .collect { case d: Double => d }
                .getOrElse(0.0)
              val lo  = ri.min.getOrElse(0.0)
              val hi  = ri.max.getOrElse(100.0)
              val pos = (((v - lo) / (hi - lo).max(1e-9)) * (r.w - 1)).toInt
                .max(0)
                .min((r.w - 1).max(0))
              discard(
                canvas.text(
                  r.x,
                  r.y,
                  (0 until r.w)
                    .map(i => if i == pos then "●" else "─")
                    .mkString,
                  style,
                  clip,
                ),
              )
            case ti: TextInput  =>
              val value     = textValue(env, n.path, ti)
              val shown     = e match
                  case _: PasswordInput => "*" * value.length
                  case _                => value
              val caret     = env.caret(n.path).max(0).min(shown.length)
              val offset    = (caret - r.w + 1).max(0)
              val field     =
                style.withAddModifier(jatatui.core.style.Modifier.UNDERLINED)
              val text      =
                if shown.isEmpty && !focused then ti.placeholder.getOrElse("")
                else shown.slice(offset, offset + r.w)
              val textStyle =
                if shown.isEmpty && !focused then
                    field
                      .withAddModifier(jatatui.core.style.Modifier.DIM)
                      .withAddModifier(jatatui.core.style.Modifier.ITALIC)
                else field
              (r.x until r.right).foreach(x =>
                canvas.put(x, r.y, " ", field, clip),
              )
              discard(canvas.text(r.x, r.y, text, textStyle, clip))
              if focused then
                  val cx = r.x + caret - offset
                  val ch = shown.lift(caret).map(_.toString).getOrElse(" ")
                  canvas.put(
                    cx,
                    r.y,
                    ch,
                    field.withAddModifier(jatatui.core.style.Modifier.REVERSED),
                    clip,
                  )
              end if
            case li: Li         =>
              n.owner.flatMap(laid.nodes.get).flatMap(_.props.listStyle) match
                  case Some(kyo.Style.ListStyle.disc)    =>
                    discard(canvas.text(r.x, r.y, "• ", style, clip))
                  case Some(kyo.Style.ListStyle.decimal) =>
                    discard(
                      canvas.text(r.x, r.y, s"${n.index + 1}. ", style, clip),
                    )
                  case _                                 => ()
              end match
              discard(li)
            case _              => ()
        end match
    end decorate
end Paint
