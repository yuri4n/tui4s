package tui4s.internal

import kyo.*
import kyo.Style.Overflow
import kyo.UI.Ast.Element
import kyo.UI.{Keyboard, KeyboardEvent}

/** The scroll offset of a viewport: the columns and the rows of its content
  * that lie left of and above its port. `Offset(0, 0)` shows the start.
  */
// claude 2026-10-19: created (L1a)
private[tui4s] final case class Offset(x: Int, y: Int)

private[tui4s] object Offset:
    val zero: Offset = Offset(0, 0)

/** The scroll state of a viewport after its layout.
  *
  * For example, a column viewport 5 rows high with 12 rows of content, moved 3
  * rows down: `View(Offset(0, 3), Size(w, 12), port, None, visible, scroll)`.
  * Its kids are laid out in the content box `Rect(port.x, port.y - 3, w, 12)`;
  * the painter clips them to `port`.
  *
  * @param offset
  *   the offset of the layout, clamped to [0, `max`] on an axis that scrolls,
  *   and 0 on the other axes
  * @param content
  *   the size of the content box: the natural size of the content on an axis
  *   that is not `visible`, at least the size of the port
  * @param port
  *   the cells that show the content: the content box of the node without the
  *   indicator column
  * @param bar
  *   the column of the scroll indicator, when it shows
  * @param x
  *   the overflow of the horizontal axis
  * @param y
  *   the overflow of the vertical axis
  */
// claude 2026-10-19: created (L1a)
private[tui4s] final case class View(
    offset: Offset,
    content: Size,
    port: Rect,
    bar: Option[Rect],
    x: Overflow,
    y: Overflow,
):
    /** The largest offset of each axis that scrolls (0 on the other axes). */
    def max: Offset =
      Offset(
        if Props.scrolls(x) then (content.w - port.w).max(0) else 0,
        if Props.scrolls(y) then (content.h - port.h).max(0) else 0,
      )

    /** `o` clamped to [0, `max`] on each axis. */
    def clamp(o: Offset): Offset =
        val m = max
        Offset(o.x.max(0).min(m.x), o.y.max(0).min(m.y))
end View

/** The navigation keys that scroll a viewport (the scroll keys of a browser):
  * the arrows, PageUp, PageDown, Home and End, with no modifier.
  */
// claude 2026-10-19: created (L1a)
private[tui4s] enum ScrollKey derives CanEqual:
    case Up, Down, Left, Right, PageUp, PageDown, Home, End

private[tui4s] object ScrollKey:
    /** The scroll key of `ev`, or none. */
    def of(ev: KeyboardEvent): Option[ScrollKey] =
        val m = ev.modifiers
        if m.ctrl || m.alt || m.meta || m.shift then None
        else
            ev.key match
                case Keyboard.ArrowUp    => Some(Up)
                case Keyboard.ArrowDown  => Some(Down)
                case Keyboard.ArrowLeft  => Some(Left)
                case Keyboard.ArrowRight => Some(Right)
                case Keyboard.PageUp     => Some(PageUp)
                case Keyboard.PageDown   => Some(PageDown)
                case Keyboard.Home       => Some(Home)
                case Keyboard.End        => Some(End)
                case _                   => None
        end if
    end of
end ScrollKey

/** When the runner scrolls the focused element into view: `OnMove`, only when
  * the target (see [[Scroll.target]]) is not the one of the last frame; `Now`,
  * in this frame (a key or a paste reached the focused element and did not
  * scroll).
  */
// claude 2026-10-19: created (L1a): an ADT, not a Boolean
private[tui4s] enum Reveal derives CanEqual:
    case OnMove, Now

/** The pure scroll rules of the viewports: the key step, the follow-focus rule,
  * and the state that a frame keeps.
  */
// claude 2026-10-19: created (L1a)
private[tui4s] object Scroll:

    /** The offset of one axis after the follow rule (the CSSOM `scrollIntoView`
      * with `block: nearest`): the least change of `offset` that shows the box
      * `start` to `start + size` (in content cells) in a port of `port` cells,
      * clamped to [0, `max`]. A box that fits shows whole; a box larger than
      * the port covers it. A box that shows already keeps the offset.
      */
    def nearest(offset: Int, start: Int, size: Int, port: Int, max: Int): Int =
        val a = start
        val b = start + size - port
        offset.max(a.min(b)).min(a.max(b)).max(0).min(max.max(0))

    /** The node of the scroll target of `focus`: the focused element, or the
      * element inside it that its `aria-activedescendant` names by id (the
      * WAI-ARIA pattern of a list that keeps the focus and moves a selection).
      */
    def target(laid: Laid, focus: Option[Loc]): Option[Loc] =
      focus.flatMap { f =>
        laid.nodes.get(f).map(_.kind) match
            case Some(Kind.El(e)) =>
              e.attrs.ariaAttrs.get("activedescendant") match
                  case Some(id) => Some(withId(laid, f, id).getOrElse(f))
                  case None     => Some(f)
            case Some(_)          => Some(f)
            case None             => None
      }

    /** The first node under `p` (in document order) whose element has the id
      * `id`.
      */
    private def withId(laid: Laid, p: Loc, id: String): Option[Loc] =
      laid.nodes.get(p).flatMap { n =>
        n.kids.iterator
          .map(k =>
            laid.nodes.get(k).map(_.kind) match
                case Some(Kind.El(e)) if e.attrs.identifier.contains(id) =>
                  Some(k)
                case _                                                   =>
                  withId(laid, k, id),
          )
          .collectFirst { case Some(q) => q }
      }

    /** The follow-focus rule: each viewport that holds `target` scrolls the
      * least distance that shows the border box of `target` (the [[nearest]]
      * rule on each axis that scrolls), the innermost first; the box moves with
      * each scroll before the next viewport. `hidden` does not scroll. Gives
      * the new offsets and the viewports that moved, innermost first. The walk
      * stops at an absolute or fixed element: a scroll of a viewport around it
      * does not move it.
      */
    // claude 2026-10-19: changed: the walk stops at an element out of the
    // flow (L1b)
    def follow(
        laid: Laid,
        target: Loc,
        scroll: Map[Loc, Offset],
    ): (Map[Loc, Offset], Vector[Loc]) =
        @annotation.tailrec
        def up(
            q: Option[Loc],
            box: Rect,
            acc: Map[Loc, Offset],
            moved: Vector[Loc],
        ): (Map[Loc, Offset], Vector[Loc]) =
          q.flatMap(laid.nodes.get) match
              case None    => (acc, moved)
              case Some(n) =>
                // the viewports outside an element out of the flow do not
                // move it
                val onward =
                  if n.layer.exists(_.pos.outOfFlow) then None else n.owner
                n.view match
                    case None    => up(onward, box, acc, moved)
                    case Some(v) =>
                      val o    = v.offset
                      val m    = v.max
                      val nx   = nearest(
                        o.x,
                        box.x - v.port.x + o.x,
                        box.w,
                        v.port.w,
                        m.x,
                      )
                      val ny   = nearest(
                        o.y,
                        box.y - v.port.y + o.y,
                        box.h,
                        v.port.h,
                        m.y,
                      )
                      val next = Offset(nx, ny)
                      if next == o then up(onward, box, acc, moved)
                      else
                          up(
                            onward,
                            box.copy(
                              x = box.x - (nx - o.x),
                              y = box.y - (ny - o.y),
                            ),
                            acc.updated(n.path, next),
                            moved :+ n.path,
                          )
                      end if
                end match
        laid.nodes.get(target) match
            case None    => (scroll, Vector.empty)
            case Some(t) =>
              val first =
                if t.layer.exists(_.pos.outOfFlow) then None else t.owner
              up(first, t.rect, scroll, Vector.empty)
    end follow

    /** The offset of `v` after the key `k`, or none when `v` does not scroll
      * that way (the axis does not scroll, or the offset is at its end). Up and
      * Down move one row, PageUp and PageDown one port height, Home and End to
      * the ends (of the vertical axis, else of the horizontal axis), Left and
      * Right one column.
      */
    def keyStep(k: ScrollKey, v: View): Option[Offset] =
        val o            = v.offset
        val m            = v.max
        val page         = v.port.h.max(1)
        val next: Offset = k match
            case ScrollKey.Up       => o.copy(y = o.y - 1)
            case ScrollKey.Down     => o.copy(y = o.y + 1)
            case ScrollKey.PageUp   => o.copy(y = o.y - page)
            case ScrollKey.PageDown => o.copy(y = o.y + page)
            case ScrollKey.Left     => o.copy(x = o.x - 1)
            case ScrollKey.Right    => o.copy(x = o.x + 1)
            case ScrollKey.Home     =>
              if m.y > 0 then o.copy(y = 0) else o.copy(x = 0)
            case ScrollKey.End      =>
              if m.y > 0 then o.copy(y = m.y) else o.copy(x = m.x)
        val clamped      = v.clamp(next)
        Option.when(clamped != o)(clamped)
    end keyStep

    /** The viewport of `chain` (innermost first) that the key `k` scrolls, and
      * its new offset: the first one that can move that way (a viewport at its
      * end passes the key to the next one, as a browser chains a scroll).
      */
    def byKey(
        laid: Laid,
        chain: List[Loc],
        k: ScrollKey,
    ): Option[(Loc, Offset)] =
      chain.iterator
        .flatMap(p =>
          laid.nodes.get(p).flatMap(_.view).flatMap(keyStep(k, _)).map((p, _)),
        )
        .nextOption()

    /** True when the element `e` uses the key `k` itself, so the key does not
      * scroll: a textarea moves its caret (all the keys), a text input moves
      * its caret (the arrows, Home and End), a select cycles (Up and Down), a
      * member of a focus group moves the focus (Left and Right).
      */
    // claude 2026-10-19: changed: a textarea uses every navigation key (L1c)
    def consumes(e: Element, k: ScrollKey): Boolean =
        val group = e.attrs.focusGroup.nonEmpty
        e match
            case _: UI.Ast.Textarea  => true
            case _: UI.Ast.TextInput =>
              k match
                  case ScrollKey.PageUp | ScrollKey.PageDown => false
                  case _                                     => true
            case _: UI.Ast.Select    =>
              k == ScrollKey.Up || k == ScrollKey.Down
            case _                   =>
              group && (k == ScrollKey.Left || k == ScrollKey.Right)
        end match
    end consumes

    /** True when `e` has kyo-ui's `preventScrollKeys`: the navigation keys in
      * its subtree do not scroll (its handlers still get them).
      */
    def preventsScroll(e: Element): Boolean =
      e.attrs.dataAttrs.get("kyo-scroll-keys").contains("1")

    /** The offsets that a frame keeps: the offset of each viewport of `scroll`
      * that `laid` still has, as its layout clamped it. A viewport that
      * unmounted loses its entry (it starts at 0 when it comes back); an offset
      * of 0 has no entry. The view of the node `focus` is kept too: a textarea
      * moves its own view to show its caret ([[Layout.areaView]]).
      */
    // claude 2026-10-19: changed: the view of the focused node too (the
    // caret of a textarea moves its view, L1c)
    def kept(
        laid: Laid,
        scroll: Map[Loc, Offset],
        focus: Option[Loc] = None,
    ): Map[Loc, Offset] =
      (scroll.keySet ++ focus).iterator
        .flatMap(p =>
          laid.nodes
            .get(p)
            .flatMap(_.view)
            .map(v => p -> v.offset)
            .filter(_._2 != Offset.zero),
        )
        .toMap
end Scroll
