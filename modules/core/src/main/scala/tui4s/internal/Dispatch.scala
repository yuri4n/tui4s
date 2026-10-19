package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import kyo.*
import kyo.UI.Ast.*
import kyo.UI.{Keyboard, KeyboardEvent, Modifiers, MouseEvent}
import tui4s.TerminalSize

/** Key routing. Mirrors kyo-ui's dispatcher (`ReactiveUI.dispatchToElement`)
  * and its client-side focus rules:
  *
  *   1. `onKeyDown` handlers run on the focused element, then on each ancestor
  *      (bubbling), until an element that handled the key has
  *      `stopPropagation(true)`.
  *   2. Then the default action of the focused element: Tab / Shift-Tab move
  *      the focus (inside a `focusTrap` if any), ArrowLeft / ArrowRight move it
  *      inside a `focusGroup`, a text input edits its value (the bound
  *      `SignalRef` is written BEFORE `onInput`), a textarea edits its lines
  *      ([[AreaText]]; its newline keys are in [[editArea]]), Enter / Space
  *      activate a button or link (a click that bubbles: kyo-ui's Click rule,
  *      see [[activateClick]]), toggle a checkbox or radio (`onChange`), cycle
  *      a select; Enter inside a `form` runs `onSubmit`.
  *   3. Then the scroll of a navigation key (the scroll keys of a browser, see
  *      [[scrollOrReveal]]): when the element did not use the key itself, the
  *      innermost viewport of the chain that can move that way scrolls; else
  *      the frame scrolls the focused element into view (`Reveal.Now`).
  *
  * A handler that kyo-ui offers in two forms (an action, or a function of the
  * event: `onClick`, `onClickSelf`, `onFocus`, `onBlur`, `onSubmit`) runs in
  * both forms, the action first, as kyo-ui's Click, Focus and Blur rules do.
  * The event is a `MouseEvent` with the key's modifiers (a focus move: none)
  * and the target's `id`.
  *
  * Handlers run on the runner fiber, one event at a time. A handler that fails
  * is logged and the event goes on.
  *
  * Every write of a bound `SignalRef` goes through one function, [[writeBound]]
  * (plan 0.3).
  */
// claude 2026-10-19: changed: the Scaladoc names the textarea (L1c)
private[tui4s] final class Dispatch(
    quit: Unit < Sync,
    knobs: Knobs = Knobs.default,
):

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

    /** A bracketed paste: ONE edit of the focused text input or textarea: the
      * text goes in at the caret; in a text input, line breaks and tabs become
      * spaces ([[TextEdit.paste]]); a textarea keeps the line breaks
      * ([[AreaText.paste]]); other control characters are dropped. Then the
      * bound write and `onInput` once. It is never a submit and never runs a
      * key handler. A paste on any other element does nothing (kyo-ui has no
      * paste event).
      */
    // claude 2026-10-19: changed: the textarea (L1c)
    def onPaste(w: World, text: String)(using Frame): World < Async =
      w.focus.flatMap(p => w.laid.nodes.get(p).map(n => (p, n.kind))) match
          case Some((p, Kind.El(ti: TextInput))) if !Props.isDisabled(ti) =>
            val value             = currentText(w, p, ti)
            val caret             = caretOf(w, p, value)
            // claude 2026-10-19: changed: the rules of the field; a textarea
            // keeps the line breaks (L1c)
            val (next, nextCaret) = ti match
                case _: Textarea =>
                  AreaText.paste(value, caret, text, rulesOf(ti))
                case _           =>
                  TextEdit.paste(value, caret, text, rulesOf(ti))
            // claude 2026-10-19: changed: the field scrolls into view (L1a)
            commitText(
              w.copy(reveal = Reveal.Now),
              p,
              ti,
              value,
              caret,
              next,
              nextCaret,
            )
          case _                                                          => w

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
            // claude 2026-10-13: changed (review R2-3): a disabled target
            // whose handler was skipped does not stop the bubbling (kyo-ui's
            // dispatchToElement keeps bubbling for a disabled target)
            run.andThen(
              if declared && !skip && e.attrs.stopPropagation.getOrElse(false)
              then Kyo.unit
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
                  // claude 2026-10-13: changed (PR #13 review: Codex,
                  // Dispatch.scala:180, :204): a button's Enter submits
                  // through its click (the Click rule), not here too
                  // claude 2026-10-19: changed: a match, no isInstanceOf (P1)
                  // claude 2026-10-19: changed: a newline key of a
                  // textarea does not submit (L1c)
                  val submit                = form.filter(_ =>
                    isKey(ev, Keyboard.Enter) && (e match
                        case _: Select | _: Button => false
                        // claude 2026-10-19: created (L1c): the DOM keeps
                        // Enter on a dropdown from the form
                        case _: Dropdown           => false
                        case _: Textarea           => !AreaText.isNewline(ev)
                        case _                     => true),
                  )
                  val action: World < Async = e match
                      // claude 2026-10-19: created (L1c): the number steps,
                      // the range keys, the keys of a dropdown (below)
                      case ni: NumberInput if NumberSteps.of(ev).nonEmpty =>
                        stepNumber(
                          w,
                          target,
                          ni,
                          NumberSteps.of(ev).getOrElse(0),
                        )
                      case ri: RangeInput if RangeKey.of(ev).nonEmpty     =>
                        moveRange(w, target, ri, RangeKey.of(ev))
                      case ta: Textarea                                   =>
                        editArea(w, target, ta, ev, form.nonEmpty)
                      case ti: TextInput                                  => editText(w, target, ti, ev)
                      // claude 2026-10-19: changed: the Click of the element,
                      // not a Boolean (P1)
                      case b: (Button | Anchor) if activate(ev)           =>
                        activateClick(ch, target, Click.of(b), ev).andThen(w)
                      case bi: BooleanInput if activate(ev)               =>
                        toggle(w, target, bi)
                      case s: Select
                          if activate(ev) || isKey(
                            ev,
                            Keyboard.ArrowDown,
                          ) || isKey(ev, Keyboard.ArrowUp) =>
                        cycle(w, target, s, !isKey(ev, Keyboard.ArrowUp))
                      case d: Dropdown
                          if activate(ev) || isKey(ev, Keyboard.ArrowDown) ||
                            isKey(ev, Keyboard.ArrowUp) =>
                        cycleValues(
                          w,
                          target,
                          d.options.toVector.map(_._1),
                          d.value,
                          d.onChange,
                          !isKey(ev, Keyboard.ArrowUp),
                        )
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
                      case _                                              => w
                  // claude 2026-10-19: changed: the scroll of a navigation key,
                  // or the reveal of the focused element (L1a)
                  action.map { w0 =>
                    val w1 = scrollOrReveal(w0, ch, e, ev)
                    submit.fold(Kyo.lift(w1))(f =>
                      submitForm(f, MouseEvent(ev.targetId, ev.modifiers))
                        .andThen(w1),
                    )
                  }
                case _                                          => w
        end if
    end defaults

    /** The scroll default of the key `ev` on the focused element `e` (its chain
      * is `ch`, innermost first). A navigation key ([[ScrollKey]]: the arrows,
      * PageUp, PageDown, Home, End, with no modifier) scrolls the innermost
      * viewport of the chain that can move that way ([[Scroll.byKey]]), when
      * `e` does not use the key itself ([[Scroll.consumes]]) and no element of
      * the chain has kyo-ui's `preventScrollKeys`. Any other key (and a
      * navigation key that `e` used or that was prevented) makes the frame
      * scroll the focused element into view (`Reveal.Now`), as a browser shows
      * a field while the user types in it.
      */
    // claude 2026-10-19: created (L1a)
    private def scrollOrReveal(
        w: World,
        ch: List[(Loc, Element)],
        e: Element,
        ev: KeyboardEvent,
    ): World =
      ScrollKey.of(ev) match
          case Some(k)
              if !Scroll.consumes(e, k) &&
                !ch.exists((_, a) => Scroll.preventsScroll(a)) =>
            Scroll.byKey(w.laid, ch.map(_._1), k) match
                case Some((p, o)) => w.scrollTo(p, o)
                case None         => w
          case _ => w.copy(reveal = Reveal.Now)

    /** A keyboard activation (Enter or Space) of a button or an anchor is a
      * click: it runs kyo-ui's Click rule over the chain, innermost first. On
      * the target: `onClickSelf` and its event form. On each element: the event
      * form of `onClick`, then `onClick`. A click of a Button that reaches a
      * Form submits it ([[Click.Submits]]; kyo-ui: "Only Button clicks submit
      * forms", so an Anchor's Enter submits through the key rule instead). The
      * bubbling stops after an element that declared a click handler (the self
      * forms count on the target only) and has `stopPropagation`. kyo-ui's
      * keydown rule runs only the target's `onClick`, but in a browser the
      * Enter or Space on a button IS a click, and a terminal has no other
      * click.
      */
    // claude 2026-10-13: changed (PR #13 review: Codex, Dispatch.scala:180):
    // the click bubbles, as kyo-ui's Click rule does; it replaces `click`
    // claude 2026-10-19: changed: a Click, not a Boolean (purity plan, P1)
    private def activateClick(
        ch: List[(Loc, Element)],
        target: Loc,
        click: Click,
        ev: KeyboardEvent,
    )(using Frame): Unit < Async =
        val mouse                                        = MouseEvent(ev.targetId, ev.modifiers)
        def go(rest: List[(Loc, Element)]): Unit < Async =
          rest match
              case Nil            => Kyo.unit
              case (p, e) :: tail =>
                val a        = e.attrs
                val isTarget = p == target
                val self     =
                  if isTarget then
                      safe("onClickSelf", run(a.onClickSelf)).andThen(
                        safe("onClickSelf", runWith(a.onClickSelfEvt, mouse)),
                      )
                  else Kyo.unit
                val submit   = e match
                    case f: Form if click == Click.Submits && !isTarget =>
                      submitForm(f, mouse)
                    case _                                              => Kyo.unit
                val declared = a.onClick.nonEmpty || a.onClickEvt.nonEmpty ||
                  (isTarget && (a.onClickSelf.nonEmpty || a.onClickSelfEvt.nonEmpty))
                self
                  .andThen(safe("onClick", runWith(a.onClickEvt, mouse)))
                  .andThen(safe("onClick", run(a.onClick)))
                  .andThen(submit)
                  .andThen(
                    if declared && a.stopPropagation.getOrElse(false) then
                        Kyo.unit
                    else go(tail),
                  )
        go(ch)
    end activateClick

    /** A submit of the form `f`: `onSubmit`, then its event form. kyo-ui's
      * Click rule runs both; its keydown rule runs only the action, but in a
      * browser an Enter in a form is a submit event, which has both.
      */
    // claude 2026-10-13: created (PR #13 review: Codex, Dispatch.scala:204)
    private def submitForm(f: Form, mouse: MouseEvent)(using
        Frame,
    ): Unit < Async =
      safe("onSubmit", run(f.onSubmit))
        .andThen(safe("onSubmit", runWith(f.onSubmitEvt, mouse)))

    // ---- text inputs ----

    private def currentText(w: World, p: Loc, ti: TextInput): String =
      w.regions.get(p).map(_.content) match
          case Some(Content.Value(v)) => v.toString
          case _                      =>
            ti.value match
                case Present(UI.Bound.Const(v)) => v
                case _                          => ""

    /** The key `ev` on the text input at `p`: the grapheme editing of
      * [[TextEdit]]. Enter runs `onChange` with the text (the commit), except
      * on a read-only input.
      */
    // claude 2026-10-13: changed (C3, OS-11): the grapheme editing of TextEdit
    // replaces the UTF-16 editing of the prototype; the caret is a grapheme
    // index; the write and onInput are in `commitText`
    private def editText(w: World, p: Loc, ti: TextInput, ev: KeyboardEvent)(
        using Frame,
    ): World < Async =
        val value             = currentText(w, p, ti)
        val caret             = caretOf(w, p, value)
        val rules             = rulesOf(ti)
        // claude 2026-10-19: changed: the rules of the field (L1c)
        val (next, nextCaret) = TextEdit.key(value, caret, ev, rules)
        // claude 2026-10-13: changed (review R2-5): no onChange on a
        // read-only input (kyo-ui ignores a Change on a read-only target)
        // claude 2026-10-19: changed: the commit of a number field (L1c)
        if next == value && isKey(ev, Keyboard.Enter) &&
            rules.access == FieldAccess.Edit
        then
            safe("onChange", runWith(ti.onChange, value))
              .andThen(commitNumber(w, p, ti, value))
              .map(moveCaret(_, p, caret, nextCaret))
        else commitText(w, p, ti, value, caret, next, nextCaret)
    end editText

    /** The commit of a number field (Enter): when its text is a number, the
      * text becomes the number as kyo-ui formats it ("1." becomes "1"; the
      * bound write), then `onChangeNumeric` with the number, as the DOM
      * runner's `ChangeNumeric`. Any other field: no change.
      */
    // claude 2026-10-19: created (L1c)
    private def commitNumber(w: World, p: Loc, ti: TextInput, value: String)(
        using Frame,
    ): World < Async =
      ti match
          case ni: NumberInput =>
            NumberText.parse(value) match
                case Some(v) =>
                  val text = NumberText.format(v)
                  val c    = caretOf(w, p, value)
                  commitText(w, p, ni, value, c, text, TextEdit.count(text))
                    .map(w1 =>
                      safe(
                        "onChangeNumeric",
                        runWith(ni.onChangeNumeric, v.toDouble),
                      ).andThen(w1),
                    )
                case None    => w
          case _               => w

    /** The key `ev` on the textarea at `p` ([[AreaText]]). A newline key
      * (Alt-Enter, Shift-Enter, Ctrl-J: [[AreaText.isNewline]]) inserts a line
      * break. A plain Enter (no modifier) inserts a line break when no form
      * holds the textarea, as in a browser; in a form it is the commit
      * (`onChange` with the text, not on a read-only textarea) and the form
      * submits (kyo-ui's keydown rule: "Enter key bubbling to Form triggers
      * onSubmit"; the chat composer of an app relies on it). Enter with Ctrl or
      * Meta inserts nothing. The other keys move the caret by visual lines in
      * the port of the view of the textarea, or edit as in a text input.
      */
    // claude 2026-10-19: created (L1c)
    private def editArea(
        w: World,
        p: Loc,
        ta: Textarea,
        ev: KeyboardEvent,
        inForm: Boolean,
    )(using Frame): World < Async =
        val value             = currentText(w, p, ta)
        val caret             = caretOf(w, p, value)
        val rules             = rulesOf(ta)
        val port              = w.laid.nodes.get(p).flatMap(_.view).map(_.port)
        val enter             = isKey(ev, Keyboard.Enter) && !AreaText.isNewline(ev)
        val breaks            = AreaText.isNewline(ev) ||
          (enter && !inForm && ev.modifiers == Modifiers.none)
        val (next, nextCaret) =
          if breaks then AreaText.newline(value, caret, rules)
          else
              AreaText.key(
                value,
                caret,
                ev,
                port.fold(1)(_.w),
                port.fold(1)(_.h),
                rules,
              )
        if enter && inForm then
            if rules.access == FieldAccess.Edit then
                safe("onChange", runWith(ta.onChange, value)).andThen(w)
            else w
        else commitText(w, p, ta, value, caret, next, nextCaret)
    end editArea

    /** The rules of the edits of `ti` ([[Rules]]): read-only or not; the limit
      * of [[tui4s.maxLength]] (none when the attribute is absent or not a
      * number); a number field holds a [[NumberText.partial]] text.
      */
    // claude 2026-10-19: created (L1c)
    private def rulesOf(ti: TextInput): Rules =
      Rules(
        if ti.readOnly.getOrElse(false) then FieldAccess.ReadOnly
        else FieldAccess.Edit,
        ti.attrs.dataAttrs
          .get(tui4s.MaxLengthAttr)
          .flatMap(_.toIntOption)
          .fold(Int.MaxValue)(_.max(0)),
        ti match
            case _: NumberInput => NumberText.partial
            case _              => _ => true,
      )

    /** ArrowUp or ArrowDown on the number field at `p` (`steps` 1 or -1): the
      * browser's `stepUp` and `stepDown` ([[NumberText.step]]: by `step`,
      * clamped to `min` and `max`). It is an edit (the bound write and
      * `onInput`) and a commit (`onChangeNumeric`), as the DOM runner
      * dispatches both. Nothing on a read-only field.
      */
    // claude 2026-10-19: created (L1c)
    private def stepNumber(w: World, p: Loc, ni: NumberInput, steps: Int)(using
        Frame,
    ): World < Async =
      if ni.readOnly.getOrElse(false) then w
      else
          val value     = currentText(w, p, ni)
          val (next, v) = NumberText.step(
            value,
            steps,
            ni.step.toOption,
            ni.min.toOption,
            ni.max.toOption,
          )
          val caret     = caretOf(w, p, value)
          commitText(w, p, ni, value, caret, next, TextEdit.count(next))
            .map(w1 =>
              safe("onChangeNumeric", runWith(ni.onChangeNumeric, v.toDouble))
                .andThen(w1),
            )

    /** A key of [[RangeKey]] on the range at `p`: its value (the bound ref, the
      * constant, else `min`) moves by its steps or to an end, clamped to `min`
      * and `max` (0 and 100 when none, as in HTML), with `step` (1 when none).
      * When the value changes: the bound write, then `onChange`.
      */
    // claude 2026-10-19: created (L1c)
    private def moveRange(
        w: World,
        p: Loc,
        ri: RangeInput,
        k: Option[RangeKey],
    )(using Frame): World < Async =
        val lo                 = ri.min.getOrElse(0.0)
        val hi                 = ri.max.getOrElse(100.0)
        val now                = w.regions.get(p).map(_.content) match
            case Some(Content.Value(v: Double)) => v
            case _                              =>
              ri.value match
                  case Present(UI.Bound.Const(c)) => c
                  case _                          => lo
        val at                 = BigDecimal.decimal(now)
        val next               = k match
            case Some(RangeKey.Steps(n)) =>
              NumberText.stepBy(at, n, ri.step.toOption, Some(lo), Some(hi))
            case Some(RangeKey.Low)      => BigDecimal.decimal(lo)
            case Some(RangeKey.High)     => BigDecimal.decimal(hi)
            case None                    => at
        val v                  = next.toDouble
        val write: Unit < Sync = ri.value match
            case Present(UI.Bound.Ref(ref)) => writeBound(p, ref, v)
            case _                          => Kyo.unit
        if v == now then w
        else
            write
              .andThen(safe("onChange", runWith(ri.onChange, v)))
              .andThen(
                w.copy(regions = w.regions.updated(p, stored(w, p, v)))
                  .markNode(p),
              )
        end if
    end moveRange

    /** The region state of `p` with the value `v` (the runner's own copy of a
      * bound value).
      */
    // claude 2026-10-19: created (L1c)
    private def stored(w: World, p: Loc, v: Any): RegionState =
      w.regions.get(p) match
          case Some(r) => r.copy(content = Content.Value(v))
          case None    =>
            RegionState(
              p,
              Source.Local,
              0,
              Content.Value(v),
              Vector.empty,
              None,
            )

    /** The caret of the text input at `p` with the text `value`: a grapheme
      * index; no entry is the end of the text.
      */
    // claude 2026-10-13: created (C3)
    private def caretOf(w: World, p: Loc, value: String): Int =
      w.carets.getOrElse(p, Int.MaxValue).min(TextEdit.count(value)).max(0)

    // claude 2026-10-13: created (C3)
    private def moveCaret(w: World, p: Loc, caret: Int, next: Int): World =
      if next != caret then
          w.copy(carets = w.carets.updated(p, next)).markNode(p)
      else w

    /** One edit of the text input at `p`: the new caret, then (when the text
      * changed) the runner's own copy of the text, the bound write through
      * [[writeBound]], and `onInput` once, in this order.
      */
    // claude 2026-10-13: created (C3; the second half of the prototype's
    // editText, shared with the paste)
    private def commitText(
        w: World,
        p: Loc,
        ti: TextInput,
        value: String,
        caret: Int,
        next: String,
        nextCaret: Int,
    )(using Frame): World < Async =
        val w1 = moveCaret(w, p, caret, nextCaret)
        if next == value then w1
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
    end commitText

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
      cycleValues(
        w,
        p,
        s.children.toVector.collect { case o: Opt => o.value.getOrElse("") },
        s.value,
        s.onChange,
        forward,
      )

    /** The next value of a picker (a select, a dropdown) with the values
      * `values` and its value `value`: the bound write, then `onChange`.
      */
    // claude 2026-10-19: changed: the values of any picker, not only a select
    // (L1c: the dropdown)
    private def cycleValues(
        w: World,
        p: Loc,
        values: Vector[String],
        value: Maybe[UI.Bound[String]],
        onChange: Maybe[String => Any < Async],
        forward: Boolean,
    )(using Frame): World < Async =
      if values.isEmpty then w
      else
          val now                = w.regions.get(p).map(_.content) match
              case Some(Content.Value(v)) => v.toString
              case _                      =>
                value match
                    case Present(UI.Bound.Const(v)) => v
                    case _                          => values.head
          val i                  = values.indexOf(now).max(0)
          val next               = values(
            (i + (if forward then 1 else -1) + values.size) % values.size,
          )
          // claude 2026-10-13: changed (C3, plan 0.3): the write seam
          val write: Unit < Sync = value match
              case Present(UI.Bound.Ref(ref)) => writeBound(p, ref, next)
              case _                          => Kyo.unit
          write
            .andThen(safe("onChange", runWith(onChange, next)))
            .andThen(
              w.copy(regions = w.regions.updated(p, stored(w, p, next)))
                .markNode(p),
            )
    end cycleValues

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
          // claude 2026-10-13: changed (PR #13 review: Codex,
          // Dispatch.scala:401): both forms of onBlur and onFocus, as kyo-ui's
          // Focus and Blur rules; the event has no modifiers (a focus move)
          def mouseOf(e: Element): MouseEvent     =
            MouseEvent(e.attrs.identifier, Modifiers.none)
          val blur                                = if runHandlers then
              el(old).fold(Kyo.unit)(e =>
                safe("onBlur", run(e.attrs.onBlur)).andThen(
                  safe("onBlur", runWith(e.attrs.onBlurEvt, mouseOf(e))),
                ),
              )
          else Kyo.unit
          val focus                               = if runHandlers then
              el(next).fold(Kyo.unit)(e =>
                safe("onFocus", run(e.attrs.onFocus)).andThen(
                  safe("onFocus", runWith(e.attrs.onFocusEvt, mouseOf(e))),
                ),
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

/** The steps of a number field for a key: ArrowUp 1, ArrowDown -1 (no
  * modifier); none for another key.
  */
// claude 2026-10-19: created (L1c)
private[tui4s] object NumberSteps:
    def of(ev: KeyboardEvent): Option[Int] =
      if ev.modifiers != Modifiers.none then None
      else
          ev.key match
              case Keyboard.ArrowUp   => Some(1)
              case Keyboard.ArrowDown => Some(-1)
              case _                  => None
end NumberSteps

/** A key of a range (kyo-ui's `RangeInput`, as the browser moves a slider):
  * `Steps(n)` moves it by `n` steps (ArrowRight and ArrowUp 1, ArrowLeft and
  * ArrowDown -1, PageUp 10, PageDown -10); `Low` and `High` move it to `min`
  * (Home) and to `max` (End). No modifier.
  */
// claude 2026-10-19: created (L1c)
private[tui4s] enum RangeKey derives CanEqual:
    case Steps(n: Int)
    case Low, High

private[tui4s] object RangeKey:
    def of(ev: KeyboardEvent): Option[RangeKey] =
      if ev.modifiers != Modifiers.none then None
      else
          ev.key match
              case Keyboard.ArrowRight | Keyboard.ArrowUp  => Some(Steps(1))
              case Keyboard.ArrowLeft | Keyboard.ArrowDown => Some(Steps(-1))
              case Keyboard.PageUp                         => Some(Steps(10))
              case Keyboard.PageDown                       => Some(Steps(-10))
              case Keyboard.Home                           => Some(Low)
              case Keyboard.End                            => Some(High)
              case _                                       => None
end RangeKey

/** What a keyboard click of a button or a link does besides its handlers: the
  * click of a `Button` submits the form that holds it; the click of an `Anchor`
  * does not (kyo-ui: "Only Button clicks submit forms").
  */
// claude 2026-10-19: created: an ADT, not a Boolean (purity plan, P1)
private[tui4s] enum Click derives CanEqual:
    case Submits, Plain

private[tui4s] object Click:
    /** The click of `e`. */
    def of(e: Button | Anchor): Click = e match
        case _: Button => Submits
        case _: Anchor => Plain

/** The quit action of the running app, for [[tui4s.Tui.quit]]. A `Local`, so it
  * also reaches fibers forked by a handler or by the UI's construction.
  */
private[tui4s] object Tui4sQuit:
    private val local: Local[Unit < Sync] = Local.init(Kyo.unit)

    def let[A, S](quit: Unit < Sync)(v: A < S)(using Frame): A < S =
      local.let(quit)(v)
    def apply()(using Frame): Unit < Sync                          = local.use(identity)

/** The size of the terminal of the running app, for [[tui4s.Tui.size]]. A
  * `Local`, as [[Tui4sQuit]]: the runner sets it around the run, so it also
  * reaches the fibers that the UI forks. Outside a run it is a signal that
  * stays at 0 x 0.
  */
// claude 2026-10-19: created (L1b)
private[tui4s] object Tui4sSize:
    private val local: Local[Signal[TerminalSize]] =
      Local.init(Signal.initConst(TerminalSize(0, 0)))

    def let[A, S](size: Signal[TerminalSize])(v: A < S)(using Frame): A < S =
      local.let(size)(v)

    def get(using Frame): Signal[TerminalSize] < Sync = local.use(identity)
end Tui4sSize
