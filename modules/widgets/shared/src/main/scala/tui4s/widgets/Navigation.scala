package tui4s.widgets

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.Interactive
import tui4s.use.Keymap

/** A menu bar: a button for each [[Menu.Group]], and a dropdown layer under the
  * open one with the bindings of its `Keymap` (the help text, and the chords at
  * the right). The same keymap can serve the shortcuts of the app, so a menu
  * item and its key run one action. The dropdown takes the focus when it opens
  * and gives it back to its button when it closes; a key in it does not reach
  * the app (`stopPropagation`).
  *
  * Keys: on the bar (one focus group), ArrowLeft and ArrowRight move between
  * the buttons, and Enter, Space or ArrowDown open the menu; in a menu, ArrowUp
  * and ArrowDown move the highlight, Enter runs the item and closes the menu,
  * ArrowLeft and ArrowRight open the menu before or after, Esc closes.
  *
  * {{{
  * Menu.bar(Menu.Group("File", fileKeys), Menu.Group("Edit", editKeys))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Menu:
    /** A menu: its title and its items (the bindings with a help text). */
    final case class Group(title: String, keys: Keymap)

    /** The bar of the menus `first` and `rest`. */
    def bar(first: Group, rest: Group*)(using Frame): UI < Sync =
        val groups = Chunk.from(first +: rest)
        val n      = groups.size
        for
            open  <- Signal.initRef(Absent: Maybe[Int])
            hi    <- Signal.initRef(0)
            group <- Looks.freshId("menu")
        yield
            def at(i: Int): Unit < Sync                     =
              hi.set(0).andThen(open.set(Present(Math.floorMod(i, n))))
            def items(i: Int)                               = groups(i).keys.bindings.filter(_.help.nonEmpty)
            def keys(i: Int)(e: KeyboardEvent): Any < Async =
              e.key match
                  case Keyboard.ArrowUp    =>
                    hi.updateAndGet(h => (h - 1).max(0)).unit
                  case Keyboard.ArrowDown  =>
                    hi.updateAndGet(h =>
                      (h + 1).min((items(i).size - 1).max(0)),
                    ).unit
                  case Keyboard.ArrowLeft  => at(i - 1)
                  case Keyboard.ArrowRight => at(i + 1)
                  case Keyboard.Escape     => open.set(Absent)
                  case Keyboard.Enter      =>
                    hi.get.map(h =>
                      if h < items(i).size then
                          open.set(Absent).andThen(items(i)(h).action).unit
                      else Kyo.unit,
                    )
                  case _                   => Kyo.unit
            nav
              .role("menubar")
              .style(_.row.gap(1.px))(
                UI.fragment(
                  groups.zipWithIndex.map((g, i) =>
                    div.style(_.position(_.relative))(
                      button(g.title)
                        .focusGroup(group)
                        .aria("haspopup", "menu")
                        .onClick(at(i))
                        .onKeyDown {
                          case KeyboardEvent(Keyboard.ArrowDown, _, _) => at(i)
                          case _                                       => Kyo.unit
                        },
                      open
                        .combineLatest(hi)
                        .render[UI]((o, h) =>
                          if !o.contains(i) then UI.empty
                          else
                              div
                                .role("menu")
                                .tabIndex(0)
                                .focusAuto(true)
                                .focusRestore(true)
                                .focusTrap(true)
                                .stopPropagation(true)
                                .preventScrollKeys
                                .onKeyDown(keys(i))
                                .style(
                                  Looks.layer
                                    .position(_.absolute)
                                    .top(100.pct)
                                    .left(0.px)
                                    .zIndex(30)
                                    .minWidth(20.px),
                                )(
                                  UI.fragment(
                                    items(i).zipWithIndex.map((b, j) =>
                                      div
                                        .role("menuitem")
                                        .style((if j == h then Looks.selected
                                                else
                                                    Looks.plain
                                        ).row.gap(2.px))(
                                          span(Looks.marker(j == h) + b.help)
                                            .style(_.flexGrow(1)),
                                          span(
                                            b.chords.map(_.label).mkString("/"),
                                          ).style(Looks.muted),
                                        ),
                                    )*,
                                  ),
                                ),
                        ),
                    ),
                  )*,
                ),
              )
        end for
    end bar
end Menu

/** A text under an element while that element has the focus (WAI-ARIA
  * `tooltip`): a layer (`position` absolute, `top: 100%`) over what is under
  * it. The widget sets the `onFocus` and `onBlur` of `target` (they replace
  * handlers of the same name on it).
  *
  * {{{
  * Tooltip("Saves the file (Ctrl-S)")(button("Save").onClick(save))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Tooltip:
    /** `target`, with `text` under it while it has the focus. */
    def apply[E <: Interactive { type Self = E }](text: String)(target: E)(using
        Frame,
    ): UI < Sync =
      Signal.initRef(false).map { shown =>
        val watched: UI =
          target.onFocus(shown.set(true)).onBlur(shown.set(false))
        div.style(_.position(_.relative).align(_.start))(
          watched,
          when(shown)(
            span(text)
              .role("tooltip")
              .style(
                Looks.layer
                  .position(_.absolute)
                  .top(100.pct)
                  .left(0.px)
                  .zIndex(40)
                  .whiteSpace(_.noWrap),
              ),
          ),
        )
      }
end Tooltip

/** The path to the current place: a button for each part before the last one
  * (`onPick` gets its index), " › " between the parts, and the last part bold
  * (`aria-current`). The buttons are one focus group.
  *
  * Keys: ArrowLeft and ArrowRight move between the buttons; Enter and Space
  * pick the focused one.
  *
  * {{{
  * Breadcrumbs(dir.map(_.parts), onPick = i => dir.updateAndGet(_.take(i + 1)))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Breadcrumbs:
    /** The breadcrumbs of `path`. */
    def apply(
        path: Signal[Chunk[String]],
        onPick: Int => Any < Async = (_: Int) => (),
    )(using
        Frame,
    ): UI < Sync =
      Looks
        .freshId("crumbs")
        .map(group =>
          path.render(ps =>
            nav
              .aria("label", "breadcrumbs")
              .style(_.row)(
                UI.fragment(ps.zipWithIndex.map((p, i) =>
                    val last = i == ps.size - 1
                    val sep  =
                      if last then UI.empty else span(" › ").style(Looks.muted)
                    val part =
                      if last then span(p).aria("current", "page").style(_.bold)
                      else button(p).focusGroup(group).onClick(onPick(i))
                    span(part, sep),
                )*),
              ),
          ),
        )
end Breadcrumbs

/** Page numbers with gaps: "‹ 1 … 4 [5] 6 … 20 ›" (the current page bold in
  * `--tui4s-accent`), `around` pages on each side of the current one. The page
  * is a [[Model]] of its index from 0; the text counts from 1.
  *
  * Keys (on the widget, a tab stop): ArrowLeft and PageUp go to the page
  * before; ArrowRight and PageDown to the page after; Home and End to the first
  * and the last.
  *
  * {{{
  * Pagination(rows.map(r => (r.size + 9) / 10), page = Model.Bound(page))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Pagination:
    /** A cell of the widget: a page (from 0) or a gap ("…"). */
    enum Cell derives CanEqual:
        case Page(index: Int)
        case Gap

    /** The pages of `pages` pages. */
    def apply(
        pages: Signal[Int],
        page: Model[Int] = Model.Local(0),
        around: Int = 1,
        onChange: Int => Any < Async = (_: Int) => (),
    )(using Frame): UI < Sync =
      Model.open(page, onChange).map { v =>
        def to(f: (Int, Int) => Int): Unit < Async =
          for
              n <- pages.current
              p <- v.value.current
              _ <- v.change(f(p, n).min(n - 1).max(0))
          yield ()
        def keys(e: KeyboardEvent): Any < Async    =
          e.key match
              case Keyboard.ArrowLeft | Keyboard.PageUp    => to((p, _) => p - 1)
              case Keyboard.ArrowRight | Keyboard.PageDown =>
                to((p, _) => p + 1)
              case Keyboard.Home                           => to((_, _) => 0)
              case Keyboard.End                            => to((_, n) => n - 1)
              case _                                       => Kyo.unit
        pages
          .combineLatest(v.value)
          .render((n, p) =>
            nav
              .aria("label", "pages")
              .tabIndex(0)
              .preventScrollKeys
              .onKeyDown(keys)
              .style(_.row.gap(1.px))(
                span("‹").style(Looks.muted),
                UI.fragment(cells(p, n, around).map {
                  case Cell.Page(i) if i == p =>
                    span(s"[${i + 1}]")
                      .aria("current", "page")
                      .style(_.bold.color(Colors.accent))
                  case Cell.Page(i)           => span(s"${i + 1}")
                  case Cell.Gap               => span("…").style(Looks.muted)
                }*),
                span("›").style(Looks.muted),
              ),
          )
      }

    /** The cells for the page `page` of `pages`: the first and the last page,
      * `around` pages on each side of `page`, and a gap for two or more pages
      * left out (one page left out shows).
      */
    def cells(page: Int, pages: Int, around: Int): Chunk[Cell] =
      if pages <= 0 then Chunk.empty
      else
          val p     = page.max(0).min(pages - 1)
          val a     = around.max(0)
          val shown = (Set(0, pages - 1) ++ ((p - a).max(0) to (p + a).min(
            pages - 1,
          ))).toList.sorted
          val out   = shown.zip(shown.drop(1)).flatMap { (x, y) =>
            if y - x == 2 then List(Cell.Page(x + 1), Cell.Page(y))
            else if y - x > 2 then List(Cell.Gap, Cell.Page(y))
            else List(Cell.Page(y))
          }
          Chunk.from(Cell.Page(shown.head) :: out)
end Pagination

/** Chips ("scala ×") and a field that adds one. A tag is trimmed, and an empty
  * tag or a tag that is there already is not added. The tags are a [[Model]].
  *
  * Keys: on a chip (the chips are one focus group), ArrowLeft and ArrowRight
  * move, Delete and Backspace remove it; in the field, Enter adds its text, and
  * Backspace in an empty field removes the last tag.
  *
  * {{{
  * Tags(tags = Model.Bound(labels))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Tags:
    /** The chips of `tags` and the field. */
    def apply(
        tags: Model[Chunk[String]] = Model.Local(Chunk.empty[String]),
        placeholder: String = "add a tag",
        onChange: Chunk[String] => Any < Async = (_: Chunk[String]) => (),
    )(using Frame): UI < Sync =
      for
          v     <- Model.open(tags, onChange)
          draft <- Signal.initRef("")
          group <- Looks.freshId("tags")
      yield
          def edit(f: Chunk[String] => Chunk[String]): Unit < Async =
            v.value.current.map(ts => v.change(f(ts)))
          div
            .role("list")
            .style(_.row.flexWrap(_.wrap))(
              v.value.render(ts =>
                UI.fragment(
                  ts.zipWithIndex.map((t, i) =>
                    span(s"$t ×")
                      .role("listitem")
                      .tabIndex(0)
                      .focusGroup(group)
                      .onKeyDown {
                        case KeyboardEvent(
                              Keyboard.Delete | Keyboard.Backspace,
                              _,
                              _,
                            ) =>
                          edit(remove(_, i))
                        case _ => Kyo.unit
                      }
                      .style(
                        Style
                          .bg(Colors.selection)
                          .padding(0.px, 1.px)
                          .margin(0.px, 1.px, 0.px, 0.px)
                          .focus(_.bold.color(Colors.accent)),
                      ),
                  )*,
                ),
              ),
              input
                .value(draft)
                .placeholder(placeholder)
                .onKeyDown {
                  case KeyboardEvent(Keyboard.Enter, _, _)     =>
                    draft.get.map(t => edit(add(_, t)).andThen(draft.set("")))
                  case KeyboardEvent(Keyboard.Backspace, _, _) =>
                    draft.get.map(t =>
                      if t.isEmpty then edit(_.dropRight(1)) else Kyo.unit,
                    )
                  case _                                       => Kyo.unit
                },
            )

    /** `tags` and `tag` (trimmed) at the end, when it is not empty and not
      * there already.
      */
    def add(tags: Chunk[String], tag: String): Chunk[String] =
        val t = tag.trim
        if t.isEmpty || tags.contains(t) then tags else tags :+ t

    /** `tags` without the tag at `i`. */
    def remove(tags: Chunk[String], i: Int): Chunk[String] =
      tags.take(i) ++ tags.drop(i + 1)
end Tags

/** Steps of a task, one at a time: a header with each step ("✓" done, "●" the
  * current one, "○" to do), the body of the current step, and Back and Next
  * buttons (Next is Finish on the last step: it calls `onFinish`). The current
  * step is a [[Model]] of its index.
  *
  * Keys: Tab moves between the buttons; Enter and Space press them.
  *
  * {{{
  * Wizard(Wizard.Step("Account", account), Wizard.Step("Profile", profile))(onFinish = create)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Wizard:
    /** A step: its title and its body. */
    final case class Step(title: String, body: UI)

    /** Where a step is, from the current one. */
    enum Status derives CanEqual:
        case Done, Current, Todo

    /** The wizard of the steps `first` and `rest`. */
    def apply(first: Step, rest: Step*)(
        current: Model[Int] = Model.Local(0),
        onChange: Int => Any < Async = (_: Int) => (),
        onFinish: Any < Async = (),
    )(using Frame): UI < Sync =
        val steps = Chunk.from(first +: rest)
        val n     = steps.size
        Model.open(current, onChange).map { v =>
          def by(d: Int): Unit < Async =
            v.value.current.map(c => v.change(move(c, d, n)))
          v.value.render(c0 =>
              val c = c0.max(0).min(n - 1)
              div.style(_.gap(1.px))(
                div.style(_.row.flexWrap(_.wrap))(
                  UI.fragment(steps.zipWithIndex.map((s, i) =>
                      val (mark, look) = status(i, c) match
                          case Status.Done    => ("✓", Style.color(Colors.success))
                          case Status.Current =>
                            ("●", Style.bold.color(Colors.accent))
                          case Status.Todo    => ("○", Looks.muted)
                      span(
                        s"$mark ${s.title}${if i < n - 1 then "  ›  " else ""}",
                      ).style(look),
                  )*),
                ),
                div(steps(c).body),
                div.style(_.row.gap(2.px))(
                  button("Back").disabled(c == 0).onClick(by(-1)),
                  if c == n - 1 then button("Finish").onClick(onFinish)
                  else button("Next").onClick(by(1)),
                ),
              ),
          )
        }
    end apply

    /** The status of the step `i` when `current` is the current one. */
    def status(i: Int, current: Int): Status =
      if i < current then Status.Done
      else if i == current then Status.Current
      else Status.Todo

    /** The step `delta` steps from `current` of `n`, in 0 to `n - 1`. */
    def move(current: Int, delta: Int, n: Int): Int =
      (current + delta).min(n - 1).max(0)
end Wizard
