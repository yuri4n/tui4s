package tui4s.widgets

import kyo.*
import kyo.UI.*
import tui4s.use.{Edge, debounced, useSelection}

/** A search field and its debounced query: [[SearchBox.query]] follows the text
  * after it is quiet for `quiet` (`debounced` of tui4s-use), so a parent
  * filters a list once per pause, not once per key. The text is a
  * [[Model.Writable]]. The watch of the text is a fiber that ends with the
  * caller's `Scope`.
  *
  * Keys (in the field): the keys of a text input; Esc clears the text.
  *
  * {{{
  * for box <- SearchBox(quiet = 200.millis)
  * yield div(box.view, ListView(items.combineLatest(box.query).map(filter), _.id)(row))
  * }}}
  *
  * @param view
  *   the field
  * @param text
  *   the text of the field, at each key
  * @param query
  *   the text after a quiet time
  */
// claude 2026-10-19: created (L2b)
final case class SearchBox(
    view: UI,
    text: Signal[String],
    query: Signal[String],
)

/** Makes a [[SearchBox]]. */
// claude 2026-10-19: created (L2b)
object SearchBox:
    /** A search field with a query that is quiet for `quiet`. */
    def apply(
        quiet: Duration = 300.millis,
        text: Model.Writable[String] = Model.Local(""),
        placeholder: String = "search",
    )(using Frame): SearchBox < (Sync & Scope) =
      for
          ref   <- Model.ref(text)
          query <- debounced(ref, quiet)
      yield SearchBox(
        input
          .value(ref)
          .role("searchbox")
          .placeholder(placeholder)
          .onKeyDown {
            case KeyboardEvent(Keyboard.Escape, _, _) => ref.set("")
            case _                                    => Kyo.unit
          },
        ref,
        query,
      )
end SearchBox

/** A text field with suggestions in a dropdown layer under it (WAI-ARIA
  * `combobox`). The suggestions are the `options` that match the text
  * ([[Autocomplete.Match]]), at most `limit`. The layer opens when the text
  * changes and closes at a pick, at Esc and when the field loses the focus. The
  * highlighted suggestion is `--tui4s-selection`.
  *
  * Keys (in the field): the keys of a text input; ArrowDown opens the list or
  * goes to the next suggestion; ArrowUp goes to the one before; Enter picks the
  * highlighted one (it becomes the text, and `onPick` gets it); Esc closes the
  * list.
  *
  * {{{
  * Autocomplete(languages, matcher = Autocomplete.Match.Fuzzy, onPick = choose)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Autocomplete:
    /** How an option matches the text (case does not count). */
    enum Match derives CanEqual:
        /** The option starts with the text. */
        case Prefix

        /** The option has the text. */
        case Contains

        /** The option has the characters of the text, in order. */
        case Fuzzy
    end Match

    /** The field and its dropdown layer. */
    def apply(
        options: Signal[Chunk[String]],
        text: Model.Writable[String] = Model.Local(""),
        matcher: Match = Match.Contains,
        limit: Int = 6,
        placeholder: String = "",
        onPick: String => Any < Async = (_: String) => (),
    )(using Frame): UI < Sync =
      for
          ref  <- Model.ref(text)
          open <- Signal.initRef(false)
          hi   <- Signal.initRef(0)
      yield
          val shown                               =
            options
              .combineLatest(ref)
              .map((xs, t) => suggest(xs, t, matcher, limit))
          def move(d: Int): Unit < Sync           =
            shown.current.map(xs =>
              hi.updateAndGet(i => (i + d).max(0).min((xs.size - 1).max(0)))
                .unit,
            )
          val pick: Unit < Async                  =
            for
                o  <- open.get
                xs <- shown.current
                i  <- hi.get
                _  <-
                  if o && i < xs.size then
                      ref
                        .set(xs(i))
                        .andThen(open.set(false))
                        .andThen(onPick(xs(i)))
                  else Kyo.unit
            yield ()
          def keys(e: KeyboardEvent): Any < Async =
            e.key match
                case Keyboard.ArrowDown =>
                  open.get.map(o =>
                    if o then move(1) else open.set(true).andThen(hi.set(0)),
                  )
                case Keyboard.ArrowUp   => move(-1)
                case Keyboard.Enter     => pick
                case Keyboard.Escape    => open.set(false)
                case _                  => Kyo.unit
          div.style(_.position(_.relative))(
            input
              .value(ref)
              .role("combobox")
              .aria("autocomplete", "list")
              .placeholder(placeholder)
              .onInput(_ => open.set(true).andThen(hi.set(0)))
              .onBlur(open.set(false))
              .onKeyDown(keys),
            open.combineLatest(shown).combineLatest(hi).render[UI] {
              case ((o, xs), h) =>
                if !o || xs.isEmpty then UI.empty
                else
                    div
                      .role("listbox")
                      .style(
                        Looks.layer
                          .position(_.absolute)
                          .top(100.pct)
                          .left(0.px)
                          .zIndex(10)
                          .minWidth(20.px),
                      )(
                        UI.fragment(
                          xs.zipWithIndex.map((s, i) =>
                            div
                              .role("option")
                              .style((if i == h then Looks.selected
                                      else Looks.plain).row)(
                                span(Looks.marker(i == h)),
                                span(s),
                              ),
                          )*,
                        ),
                      )
            },
          )

    /** The options that match `text`, at most `limit`, in their order. An empty
      * text matches no option.
      */
    def suggest(
        options: Chunk[String],
        text: String,
        m: Match,
        limit: Int,
    ): Chunk[String] =
      if text.isEmpty then Chunk.empty
      else options.filter(matches(m, text, _)).take(limit.max(0))

    /** True when `option` matches `text` by `m` (case does not count). */
    def matches(m: Match, text: String, option: String): Boolean =
        val (t, o) = (text.toLowerCase, option.toLowerCase)
        m match
            case Match.Prefix   => o.startsWith(t)
            case Match.Contains => o.contains(t)
            case Match.Fuzzy    =>
              t.foldLeft(0)((at, c) =>
                if at < 0 then -1
                else
                    val i = o.indexOf(c.toInt, at)
                    if i < 0 then -1 else i + 1,
              ) >= 0
        end match
    end matches
end Autocomplete

/** A list of items with a check box each ("[x]" or "[ ]"): a [[ListView]] whose
  * Space checks or unchecks the selected item. The checked items are a
  * [[Model]] of their keys.
  *
  * Keys (on the list): the moves of a [[ListView]] (ArrowUp, ArrowDown, `k`,
  * `j`, PageUp, PageDown, Home, End); Space and Enter check or uncheck.
  *
  * {{{
  * Checklist(tasks, _.id, checked = Model.Bound(done))(t => span(t.title))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Checklist:
    /** The checklist of `items`. */
    def apply[A, K](
        items: Signal[Chunk[A]],
        key: A => K,
        checked: Model[Set[K]] = Model.Local(Set.empty[K]),
        height: Int = 10,
        onChange: Set[K] => Any < Async = (_: Set[K]) => (),
    )(label: A => UI)(using Frame): UI < Sync =
      for
          v   <- Model.open(checked, onChange)
          sel <- useSelection(items, key, Edge.Clamp, height)
          id  <- Looks.freshId("checklist")
      yield
          val toggle: Unit < Async                =
            for
                k   <- sel.key.current
                now <- v.value.current
                _   <- k.fold(Kyo.unit)(k =>
                         v.change(if now(k) then now - k else now + k),
                       )
            yield ()
          val list                                = Listbox.keys(sel, m => m, toggle)
          def keys(e: KeyboardEvent): Any < Async =
            if e.key == Keyboard.Space then toggle else list(e)
          Listbox(
            id,
            items
              .combineLatest(v.value)
              .map((xs, c) => (0, xs.map(a => (a, c(key(a)))))),
            sel.index,
            height,
            Style.Overflow.auto,
            keys,
          )((p, _) =>
            div.style(_.row)(span(if p._2 then "[x] " else "[ ] "), label(p._1)),
          )
end Checklist

/** One choice of several (WAI-ARIA `radiogroup`): "(•)" marks the chosen one.
  * The group is one tab stop, and the arrows choose, as in a browser. The
  * choice is a [[Model]] (default: the first option).
  *
  * Keys (on the group): ArrowUp and ArrowLeft choose the option before;
  * ArrowDown and ArrowRight the one after (both wrap); Home and End the first
  * and the last.
  *
  * {{{
  * RadioGroup("Small", "Medium", "Large")(onChange = resize)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object RadioGroup:
    /** The group of the options `first` and `rest`. */
    def apply[A](first: A, rest: A*)(
        value: Model[A] = Model.Local(first),
        onChange: A => Any < Async = (_: A) => (),
        label: A => String = (a: A) => a.toString,
    )(using Frame): UI < Sync =
        val all = Chunk.from(first +: rest)
        Model.open(value, onChange).map { v =>
          def to(f: Int => Int): Unit < Async     =
            v.value.current.map(a => v.change(all(f(all.indexOf(a).max(0)))))
          def keys(e: KeyboardEvent): Any < Async =
            e.key match
                case Keyboard.ArrowUp | Keyboard.ArrowLeft    =>
                  to(step(_, -1, all.size))
                case Keyboard.ArrowDown | Keyboard.ArrowRight =>
                  to(step(_, 1, all.size))
                case Keyboard.Home                            => to(_ => 0)
                case Keyboard.End                             => to(_ => all.size - 1)
                case _                                        => Kyo.unit
          v.value.render(chosen =>
            div
              .role("radiogroup")
              .tabIndex(0)
              .preventScrollKeys
              .onKeyDown(keys)(
                UI.fragment(all.map(a =>
                    val on = a == chosen
                    span(s"${if on then "(•)" else "( )"} ${label(a)}")
                      .role("radio")
                      .aria("checked", on.toString)
                      .style(if on then Style.bold.color(Colors.accent)
                      else Style.empty),
                )*),
              ),
          )
        }
    end apply

    /** The index `delta` options from `i` of `n`, wrapping at the ends. */
    def step(i: Int, delta: Int, n: Int): Int =
      if n <= 0 then 0 else Math.floorMod(i + delta, n)
end RadioGroup

/** A number that the keys change by `step`, in `min` to `max` (WAI-ARIA
  * `spinbutton`): "◂ 5 ▸". The number is a [[Model]].
  *
  * Keys (on the stepper, a tab stop): ArrowUp, ArrowRight and `+` add one step;
  * ArrowDown, ArrowLeft and `-` take one away; PageUp and PageDown ten steps;
  * Home and End go to `min` and `max`.
  *
  * {{{
  * NumberStepper(value = Model.Bound(copies), min = 1, max = 99)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object NumberStepper:
    /** The stepper of `value`. */
    def apply(
        value: Model[Int] = Model.Local(0),
        min: Int = Int.MinValue,
        max: Int = Int.MaxValue,
        step: Int = 1,
        onChange: Int => Any < Async = (_: Int) => (),
    )(using Frame): UI < Sync =
      Model.open(value, onChange).map { v =>
        def by(steps: Int): Unit < Async        =
          v.value.current.map(n => v.change(stepBy(n, steps, step, min, max)))
        def keys(e: KeyboardEvent): Any < Async =
          e.key match
              case Keyboard.ArrowUp | Keyboard.ArrowRight |
                  Keyboard.Char('+') =>
                by(1)
              case Keyboard.ArrowDown | Keyboard.ArrowLeft |
                  Keyboard.Char('-') =>
                by(-1)
              case Keyboard.PageUp   => by(10)
              case Keyboard.PageDown => by(-10)
              case Keyboard.Home     => v.change(min.min(max))
              case Keyboard.End      => v.change(max.max(min))
              case _                 => Kyo.unit
        v.value.render(n =>
          div
            .role("spinbutton")
            .aria("valuenow", n.toString)
            .tabIndex(0)
            .preventScrollKeys
            .onKeyDown(keys)
            .style(_.row)(
              span("◂ ").style(Looks.muted),
              span(n.toString).style(_.bold),
              span(" ▸").style(Looks.muted),
            ),
        )
      }

    /** `n` moved by `steps` steps of `step`, in `min` to `max`. */
    def stepBy(n: Int, steps: Int, step: Int, min: Int, max: Int): Int =
      (n.toLong + steps.toLong * step.toLong)
        .max(min.toLong)
        .min(max.toLong.max(min.toLong))
        .toInt
end NumberStepper

/** A date typed as text (`YYYY-MM-DD`) and checked as the user types: under the
  * field (when it is not empty), "✓" and the date (`--tui4s-success`), or "✗"
  * and the problem (`--tui4s-danger`). `onDate` gets each valid date. The text
  * is a [[Model.Writable]].
  *
  * {{{
  * DateField(onDate = d => due.set(d))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object DateField:
    /** A date of the Gregorian calendar. Make it with [[DateField.parse]]. */
    final case class Date private[widgets] (year: Int, month: Int, day: Int)
        derives CanEqual:
        /** The text `YYYY-MM-DD`. */
        def text: String =
          f"$year%04d-$month%02d-$day%02d"

    /** Why a text is not a date. */
    enum Problem derives CanEqual:
        /** The text is not `YYYY-MM-DD`. */
        case Format

        /** The month is not 1 to 12. */
        case Month(month: Int)

        /** The day is not 1 to the days of its month. */
        case Day(day: Int, last: Int)
    end Problem

    /** The field, and the check under it. */
    def apply(
        text: Model.Writable[String] = Model.Local(""),
        onDate: Date => Any < Async = (_: Date) => (),
    )(using Frame): UI < Sync =
      Model
        .ref(text)
        .map(ref =>
          div(
            input
              .value(ref)
              .placeholder("YYYY-MM-DD")
              .onInput(t => parse(t).fold(_ => Kyo.unit, d => onDate(d))),
            ref.render[UI](t =>
              if t.isEmpty then UI.empty
              else
                  parse(t) match
                      case Right(d) =>
                        span(s"✓ ${d.text}").style(_.color(Colors.success))
                      case Left(p)  =>
                        span(s"✗ ${english(p)}").style(_.color(Colors.danger)),
            ),
          ),
        )

    /** The date of `text` (`YYYY-MM-DD`), or the problem. */
    def parse(text: String): Either[Problem, Date] =
      text.trim.split('-').toList match
          case List(y, m, d)
              if y.length == 4 && m.length == 2 && d.length == 2 &&
                (y + m + d).forall(_.isDigit) =>
            val (year, month, day) = (y.toInt, m.toInt, d.toInt)
            if month < 1 || month > 12 then Left(Problem.Month(month))
            else
                val last = days(year, month)
                if day < 1 || day > last then Left(Problem.Day(day, last))
                else Right(Date(year, month, day))
          case _ => Left(Problem.Format)

    /** The days of `month` in `year` (February has 29 in a leap year). */
    def days(year: Int, month: Int): Int =
      month match
          case 2              =>
            if (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 then 29
            else 28
          case 4 | 6 | 9 | 11 => 30
          case _              => 31

    /** English text for a problem. */
    def english(p: Problem): String = p match
        case Problem.Format       => "type the date as YYYY-MM-DD"
        case Problem.Month(m)     => s"month $m is not 1 to 12"
        case Problem.Day(d, last) => s"day $d is not 1 to $last"
end DateField
