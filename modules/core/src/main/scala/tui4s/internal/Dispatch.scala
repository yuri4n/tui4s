package tui4s.internal

import kyo.*
import kyo.UI.Keyboard
import kyo.UI.KeyboardEvent
import kyo.UI.MouseEvent
import kyo.UI.Ast.*

/** Key routing. Mirrors kyo-ui's dispatcher (`ReactiveUI.dispatchToElement`)
  * and its client-side focus rules:
  *
  *   1. `onKeyDown` handlers run on the focused element, then on each ancestor
  *      (bubbling), until an element that handled the key has
  *      `stopPropagation(true)`.
  *   2. Then the default action of the focused element: Tab / Shift-Tab move
  *      the focus (inside a `focusTrap` if any), ArrowLeft / ArrowRight move it
  *      inside a `focusGroup`, a text input edits its value (the bound
  *      `SignalRef` is written BEFORE `onInput`), Enter / Space activate a
  *      button or link (`onClick`), toggle a checkbox or radio (`onChange`),
  *      cycle a select; Enter inside a `form` runs `onSubmit`.
  *
  * Handlers run on the runner fiber, one event at a time. A handler that fails
  * is logged and the event goes on.
  *
  * Every write of a bound `SignalRef` goes through one function, [[writeBound]]
  * (plan 0.3).
  */
// claude 2026-10-13: changed (C3, plan 0.3): the knobs (the hook of the write
// seam)
final class Dispatch(quit: Unit < Sync, knobs: Knobs = Knobs.default):

    /** The write seam (plan 0.3): the one place where the dispatcher writes a
      * bound `SignalRef` (the text of a text input or a textarea, the checked
      * flag of a checkbox or a radio, the value of a select). It calls the test
      * hook `knobs.onBoundWrite`, then writes the Kyo ref. Not public API. The
      * runner writes the ref BEFORE it runs `onInput` or `onChange`, so a
      * handler that reads the ref sees the new value. A later version can send
      * the write to another primary (a ZIO or Cats Effect ref) or keep the
      * runner's own text for the focused field here.
      */
    // claude 2026-10-13: created (C3, plan 0.3)
    private[tui4s] def writeBound[A](path: Loc, ref: SignalRef[A], value: A)(
        using Frame,
    ): Unit < Sync =
      knobs.onBoundWrite(path, value).andThen(ref.set(value))

    private def safe(label: String, v: => Any < Async)(using
        Frame,
    ): Unit < Async =
      Abort.run[Throwable](Tui4sQuit.let(quit)(v)).map {
        case Result.Success(_) => ()
        case Result.Failure(e) => Log.error(s"tui4s: $label handler failed", e)
        case Result.Panic(e)   => Log.error(s"tui4s: $label handler panicked", e)
      }

    private def run(m: Maybe[Any < Async]): Any < Async                   =
      if m.isEmpty then () else m.get
    private def runWith[A](m: Maybe[A => Any < Async], a: A): Any < Async =
      if m.isEmpty then () else m.get(a)

    /** The element at `p` and its element ancestors, innermost first. */
    def chain(laid: Laid, p: Loc): List[(Loc, Element)] =
        def up(
            q: Option[Loc],
            acc: List[(Loc, Element)],
        ): List[(Loc, Element)] =
          q.flatMap(laid.nodes.get) match
              case None    => acc.reverse
              case Some(n) =>
                n.kind match
                    case Kind.El(e) => up(n.owner, (n.path, e) :: acc)
                    case _          => up(n.owner, acc)
        up(Some(p), Nil)
    end chain

    private def targetOf(w: World): Option[Loc] =
      w.focus
        .filter(w.laid.nodes.contains)
        .orElse(
          w.laid.nodes
            .get(Loc.screen)
            .flatMap(
              _.kids.find(k =>
                w.laid.nodes
                  .get(k)
                  .exists(_.kind match
                      case Kind.El(_) => true
                      case _          => false),
              ),
            ),
        )

    def onKey(w: World, key: KeyboardEvent)(using Frame): World < Async =
      targetOf(w) match
          case None         => w
          case Some(target) =>
            val ch       = chain(w.laid, target)
            val targetEl = ch.headOption.map(_._2)
            val ev       = key.copy(targetId =
              targetEl.flatMap(_.attrs.identifier.toOption) match
                  case Some(id) => Present(id)
                  case None     => Absent,
            )
            bubble(ch, ev, target).andThen(
              defaults(w, target, targetEl, ch, ev),
            )

    private def bubble(
        ch: List[(Loc, Element)],
        ev: KeyboardEvent,
        target: Loc,
    )(using Frame): Unit < Async =
      ch match
          case Nil            => ()
          case (p, e) :: rest =>
            val handler  = e.attrs.onKeyDown
            val declared = handler.nonEmpty
            val skip     = p == target && Props.isDisabled(e)
            val run      =
              if declared && !skip then safe("onKeyDown", handler.get(ev))
              else Kyo.unit
            run.andThen(
              if declared && e.attrs.stopPropagation.getOrElse(false) then
                  Kyo.unit
              else bubble(rest, ev, target),
            )

    private def isKey(ev: KeyboardEvent, k: Keyboard): Boolean = ev.key == k
    private def activate(ev: KeyboardEvent): Boolean           =
      isKey(ev, Keyboard.Enter) || isKey(ev, Keyboard.Space)

    private def defaults(
        w: World,
        target: Loc,
        el: Option[Element],
        ch: List[(Loc, Element)],
        ev: KeyboardEvent,
    )(using
        Frame,
    ): World < Async =
        val focused = w.focus.contains(target)
        if isKey(ev, Keyboard.Tab) then
            moveFocus(w, if ev.modifiers.shift then -1 else 1)
        else
            el match
                case Some(e) if focused && !Props.isDisabled(e) =>
                  val form                  = ch.collectFirst { case (_, f: Form) => f }
                  val submit                = form.filter(_ =>
                    isKey(ev, Keyboard.Enter) && !e.isInstanceOf[Select],
                  )
                  val action: World < Async = e match
                      case ti: TextInput                        => editText(w, target, ti, ev)
                      case b: (Button | Anchor) if activate(ev) =>
                        click(b, ev).andThen(w)
                      case bi: BooleanInput if activate(ev)     =>
                        toggle(w, target, bi)
                      case s: Select
                          if activate(ev) || isKey(
                            ev,
                            Keyboard.ArrowDown,
                          ) || isKey(ev, Keyboard.ArrowUp) =>
                        cycle(w, target, s, !isKey(ev, Keyboard.ArrowUp))
                      case _
                          if (isKey(ev, Keyboard.ArrowLeft) || isKey(
                            ev,
                            Keyboard.ArrowRight,
                          )) && e.attrs.focusGroup.nonEmpty =>
                        moveInGroup(
                          w,
                          target,
                          e.attrs.focusGroup.get,
                          if isKey(ev, Keyboard.ArrowRight) then 1 else -1,
                        )
                      case _                                    => w
                  action.map(w1 =>
                    submit.fold(Kyo.lift(w1))(f =>
                      safe("onSubmit", run(f.onSubmit)).andThen(w1),
                    ),
                  )
                case _                                          => w
        end if
    end defaults

    private def click(e: Element, ev: KeyboardEvent)(using
        Frame,
    ): Unit < Async =
        val mouse = MouseEvent(ev.targetId, ev.modifiers)
        val a     = e.attrs
        safe("onClickSelf", run(a.onClickSelf))
          .andThen(safe("onClickSelf", runWith(a.onClickSelfEvt, mouse)))
          .andThen(safe("onClick", runWith(a.onClickEvt, mouse)))
          .andThen(safe("onClick", run(a.onClick)))
    end click

    // ---- text inputs ----

    private def currentText(w: World, p: Loc, ti: TextInput): String =
      w.regions.get(p).map(_.content) match
          case Some(Content.Value(v)) => v.toString
          case _                      =>
            ti.value match
                case Present(UI.Bound.Const(v)) => v
                case _                          => ""

    private def editText(w: World, p: Loc, ti: TextInput, ev: KeyboardEvent)(
        using Frame,
    ): World < Async =
        val readOnly          = ti.readOnly.getOrElse(false)
        val value             = currentText(w, p, ti)
        val caret             = w.carets.getOrElse(p, Int.MaxValue).min(value.length).max(0)
        val ctrlAlt           = ev.modifiers.ctrl || ev.modifiers.alt || ev.modifiers.meta
        val (next, nextCaret) = ev.key match
            case Keyboard.Char(c) if !ctrlAlt && !readOnly    =>
              (value.patch(caret, c.toString, 0), caret + 1)
            case Keyboard.Space if !ctrlAlt && !readOnly      =>
              (value.patch(caret, " ", 0), caret + 1)
            case Keyboard.Backspace if !readOnly && caret > 0 =>
              (value.patch(caret - 1, "", 1), caret - 1)
            case Keyboard.Delete if !readOnly                 =>
              (value.patch(caret, "", 1), caret)
            case Keyboard.ArrowLeft                           => (value, (caret - 1).max(0))
            case Keyboard.ArrowRight                          => (value, (caret + 1).min(value.length))
            case Keyboard.Home                                => (value, 0)
            case Keyboard.End                                 => (value, value.length)
            case _                                            => (value, caret)
        val w1                =
          (if nextCaret != caret then
               w.copy(carets = w.carets.updated(p, nextCaret)).markNode(p)
           else w)
        if next == value then
            if isKey(ev, Keyboard.Enter) then
                safe("onChange", runWith(ti.onChange, value)).andThen(w1)
            else w1
        else
            /* the runner's own write is applied at once (no wait for the
             * watcher); the watcher's echo is then a no-op */
            val stored             = w1.regions.get(p) match
                case Some(r) => r.copy(content = Content.Value(next))
                case None    =>
                  RegionState(
                    p,
                    Source.Local,
                    0,
                    Content.Value(next),
                    Vector.empty,
                    None,
                  )
            val w2                 =
              w1.copy(regions = w1.regions.updated(p, stored)).markNode(p)
            // claude 2026-10-13: changed (C3, plan 0.3): the write seam
            val write: Unit < Sync = ti.value match
                case Present(UI.Bound.Ref(ref)) => writeBound(p, ref, next)
                case _                          => Kyo.unit
            write
              .andThen(safe("onInput", runWith(ti.onInput, next)))
              .andThen(w2)
        end if
    end editText

    // ---- checkbox, radio, select ----

    private def toggle(w: World, p: Loc, bi: BooleanInput)(using
        Frame,
    ): World < Async =
        val now                = w.regions.get(p).map(_.content) match
            case Some(Content.Value(v: Boolean)) => v
            case _                               =>
              bi.checked match
                  case Present(UI.Bound.Const(b)) => b
                  case _                          => false
        val next               = bi match
            case _: Radio => true
            case _        => !now
        // claude 2026-10-13: changed (C3, plan 0.3): the write seam
        val write: Unit < Sync = bi.checked match
            case Present(UI.Bound.Ref(ref)) => writeBound(p, ref, next)
            case _                          => Kyo.unit
        val stored             = w.regions.get(p) match
            case Some(r) => r.copy(content = Content.Value(next))
            case None    =>
              RegionState(
                p,
                Source.Local,
                0,
                Content.Value(next),
                Vector.empty,
                None,
              )
        write
          .andThen(safe("onChange", runWith(bi.onChange, next)))
          .andThen(w.copy(regions = w.regions.updated(p, stored)).markNode(p))
    end toggle

    private def cycle(w: World, p: Loc, s: Select, forward: Boolean)(using
        Frame,
    ): World < Async =
        val values = s.children.toVector.collect { case o: Opt =>
          o.value.getOrElse("")
        }
        if values.isEmpty then w
        else
            val now                = w.regions.get(p).map(_.content) match
                case Some(Content.Value(v)) => v.toString
                case _                      =>
                  s.value match
                      case Present(UI.Bound.Const(v)) => v
                      case _                          => values.head
            val i                  = values.indexOf(now).max(0)
            val next               = values(
              (i + (if forward then 1 else -1) + values.size) % values.size,
            )
            // claude 2026-10-13: changed (C3, plan 0.3): the write seam
            val write: Unit < Sync = s.value match
                case Present(UI.Bound.Ref(ref)) => writeBound(p, ref, next)
                case _                          => Kyo.unit
            val stored             = w.regions.get(p) match
                case Some(r) => r.copy(content = Content.Value(next))
                case None    =>
                  RegionState(
                    p,
                    Source.Local,
                    0,
                    Content.Value(next),
                    Vector.empty,
                    None,
                  )
            write
              .andThen(safe("onChange", runWith(s.onChange, next)))
              .andThen(
                w.copy(regions = w.regions.updated(p, stored)).markNode(p),
              )
        end if
    end cycle

    // ---- focus ----

    def setFocus(w: World, next: Option[Loc], runHandlers: Boolean)(using
        Frame,
    ): World < Async =
      if w.focus == next then w
      else
          val old                                 = w.focus
          def el(p: Option[Loc]): Option[Element] =
            p.flatMap(w.laid.nodes.get).map(_.kind).collect { case Kind.El(e) =>
              e
            }
          val blur                                = if runHandlers then
              el(old).fold(Kyo.unit)(e => safe("onBlur", run(e.attrs.onBlur)))
          else Kyo.unit
          val focus                               = if runHandlers then
              el(next).fold(Kyo.unit)(e =>
                safe("onFocus", run(e.attrs.onFocus)),
              )
          else Kyo.unit
          val w1                                  = w.copy(focus = next)
          val w2                                  = (old.toList ++ next.toList).foldLeft(w1)(_.markNode(_))
          blur.andThen(focus).andThen(w2)

    private def moveFocus(w: World, dir: Int)(using Frame): World < Async =
        val all   = Layout.focusOrder(w.laid)
        // inside a focus trap, Tab wraps among the trap's focusables
        val trap  = w.focus.flatMap(f =>
          chain(w.laid, f).collectFirst {
            case (p, e) if e.attrs.focusTrap.getOrElse(false) => p
          },
        )
        val order = trap.fold(all)(t => all.filter(_.isAtOrUnder(t)))
        if order.isEmpty then w
        else
            val i    = w.focus.map(order.indexOf).getOrElse(-1)
            val next = if i < 0 then (if dir > 0 then 0 else order.size - 1)
            else (i + dir + order.size) % order.size
            setFocus(w, Some(order(next)), runHandlers = true)
    end moveFocus

    private def moveInGroup(w: World, p: Loc, group: String, dir: Int)(using
        Frame,
    ): World < Async =
        val peers = Layout
          .focusOrder(w.laid)
          .filter(q =>
            w.laid.nodes.get(q).map(_.kind).exists {
              case Kind.El(e) => e.attrs.focusGroup.contains(group)
              case _          => false
            },
          )
        if peers.size < 2 then w
        else
            val i = peers.indexOf(p).max(0)
            setFocus(
              w,
              Some(peers((i + dir + peers.size) % peers.size)),
              runHandlers = true,
            )
        end if
    end moveInGroup
end Dispatch

/** The quit action of the running app, for [[tui4s.Tui.quit]]. A `Local`, so it
  * also reaches fibers forked by a handler or by the UI's construction.
  */
object Tui4sQuit:
    private val local: Local[Unit < Sync] = Local.init(Kyo.unit)

    def let[A, S](quit: Unit < Sync)(v: A < S)(using Frame): A < S =
      local.let(quit)(v)
    def apply()(using Frame): Unit < Sync                          = local.use(identity)
