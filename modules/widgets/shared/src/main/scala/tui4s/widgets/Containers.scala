package tui4s.widgets

import kyo.*
import kyo.UI.*

/** A box around content: a border of one style and a title on its top border.
  * The title is a layer (`position` absolute, one row up), so it does not take
  * a row of the content; with [[Panel.Border.None]] it is the first row. The
  * border is `--tui4s-border`, the title bold `--tui4s-accent`. A pure UI
  * value.
  *
  * {{{
  * Panel(title = "CPU", border = Panel.Border.Solid)(span("42 %"), gauge)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Panel:
    /** The style of the border. */
    enum Border derives CanEqual:
        case Solid, Rounded, Dashed, Dotted, None

    /** The panel of `content` with `title` (none when empty). */
    def apply(title: String = "", border: Border = Border.Rounded)(
        content: UI*,
    )(using Frame): UI =
        val box   = border match
            case Border.None    => Style.padding(0.px, 1.px)
            case Border.Rounded => line(Style.BorderStyle.solid).rounded(1.px)
            case Border.Solid   => line(Style.BorderStyle.solid)
            case Border.Dashed  => line(Style.BorderStyle.dashed)
            case Border.Dotted  => line(Style.BorderStyle.dotted)
        val label = Style.bold.color(Colors.accent)
        val head  =
          if title.isEmpty then UI.empty
          else if border == Border.None then span(title).style(label)
          else
              span(s" $title ").style(
                label.position(_.absolute).top((-1).px).left(1.px),
              )
        section
          .role("group")
          .aria("label", title)
          .style(box.position(_.relative))(head, UI.fragment(content*))
    end apply

    private def line(s: Style.BorderStyle): Style =
      Style.border(1.px, s, Colors.border).padding(0.px, 1.px)
end Panel

/** Two panes side by side ([[SplitPane.Direction.Row]]) or one above the other
  * ([[SplitPane.Direction.Column]]), and a divider between them. The size of
  * the first pane is a percentage of the widget ([[Model]], default 50); the
  * second pane takes the rest. In a column, give the widget a definite height
  * (a parent with a `height`), as a percentage height needs one.
  *
  * Keys on the divider (a tab stop; its border is heavy while it has the
  * focus): ArrowLeft and ArrowRight (ArrowUp and ArrowDown in a column) change
  * the size by `step`; Home and End go to `min` and `max`. The size stays in
  * `min` to `max`.
  *
  * {{{
  * SplitPane(fileList, preview)(size = Model.Local(30))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object SplitPane:
    /** Where the second pane is: right of the first one, or under it. */
    enum Direction derives CanEqual:
        case Row, Column

    /** The split of `first` and `second`. */
    def apply(first: UI, second: UI)(
        direction: Direction = Direction.Row,
        size: Model[Int] = Model.Local(50),
        step: Int = 5,
        min: Int = 10,
        max: Int = 90,
        onChange: Int => Any < Async = (_: Int) => (),
    )(using Frame): UI < Sync =
      Model.open(size, onChange).map { v =>
        val row                                 = direction == Direction.Row
        def keys(e: KeyboardEvent): Any < Async =
          v.value.current.map(now =>
            next(direction, e.key, now, step, min, max)
              .fold(Kyo.unit)(v.change),
          )
        div.style(
          (if row then Style.row else Style.column).flexGrow(1),
        )(
          div(first).style(
            v.value.map(p =>
              (if row then Style.width(p.pct) else Style.height(p.pct))
                .flexShrink(0),
            ),
          ),
          div
            .role("separator")
            .aria("orientation", if row then "vertical" else "horizontal")
            .tabIndex(0)
            .preventScrollKeys
            .onKeyDown(keys)
            .style(
              if row then Style.borderLeft(1.px, Colors.border)
              else Style.borderTop(1.px, Colors.border),
            ),
          div(second).style(_.flexGrow(1)),
        )
      }

    /** The size after the key `k` on the divider, or Absent for another key. */
    private[widgets] def next(
        d: Direction,
        k: Keyboard,
        now: Int,
        step: Int,
        min: Int,
        max: Int,
    ): Maybe[Int] =
        val delta = (d, k) match
            case (Direction.Row, Keyboard.ArrowLeft)    => Present(-step)
            case (Direction.Row, Keyboard.ArrowRight)   => Present(step)
            case (Direction.Column, Keyboard.ArrowUp)   => Present(-step)
            case (Direction.Column, Keyboard.ArrowDown) => Present(step)
            case (_, Keyboard.Home)                     => Present(min - now)
            case (_, Keyboard.End)                      => Present(max - now)
            case _                                      => Absent
        delta.map(resize(now, _, min, max))
    end next

    /** `size + delta`, in `min` to `max` (`min` when `max` < `min`). */
    def resize(size: Int, delta: Int, min: Int, max: Int): Int =
      (size + delta).min(max).max(min)
end SplitPane

/** Sections that open and close. Each section has a header button ("▸" when it
  * is closed, "▾" when it is open, and `aria-expanded`) and a body that shows
  * while it is open. [[Accordion.Mode.One]] keeps at most one section open
  * (opening one closes the other); [[Accordion.Mode.Many]] opens each one on
  * its own. The open sections are a [[Model]] of their indices.
  *
  * Keys: Tab moves between the headers; Enter and Space open or close the
  * section of the focused header.
  *
  * {{{
  * Accordion(
  *   Accordion.Section("General", generalForm),
  *   Accordion.Section("Advanced", advancedForm),
  * )(mode = Accordion.Mode.Many)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Accordion:
    /** A section: the text of its header and its body. */
    final case class Section(title: String, body: UI)

    /** How many sections can be open. */
    enum Mode derives CanEqual:
        case One, Many

    /** The accordion of the sections `first` and `rest`. */
    def apply(first: Section, rest: Section*)(
        mode: Mode = Mode.One,
        open: Model[Set[Int]] = Model.Local(Set.empty[Int]),
        onChange: Set[Int] => Any < Async = (_: Set[Int]) => (),
    )(using Frame): UI < Sync =
      Model.open(open, onChange).map { v =>
        div(
          UI.fragment(Chunk.from(first +: rest).zipWithIndex.map { (s, i) =>
            val shown = v.value.map(_.contains(i))
            div(
              shown.render(on =>
                button(s"${if on then "▾" else "▸"} ${s.title}")
                  .aria("expanded", on.toString)
                  .onClick(
                    v.value.current.map(o => v.change(toggle(o, i, mode))),
                  )
                  .style(
                    (if on then Style.bold else Style.empty).alignSelf(_.start),
                  ),
              ),
              when(shown)(div(s.body).style(_.padding(0.px, 0.px, 0.px, 2.px))),
            )
          }*),
        )
      }

    /** The open sections after a toggle of the section `i`. */
    def toggle(open: Set[Int], i: Int, mode: Mode): Set[Int] =
      if open.contains(i) then open - i
      else
          mode match
              case Mode.One  => Set(i)
              case Mode.Many => open + i
end Accordion
