package tui4s.widgets

import kyo.*
import kyo.UI.*
import tui4s.use.{Binding, Dialog, Edge, Keymap, useDialog, useNow, useSelection}

/** A status row: `left` text that grows and ends with "…" when it does not fit,
  * and a `right` slot (a clock, key hints). The background is
  * `--tui4s-surface`. A pure UI value.
  *
  * {{{
  * StatusBar(file.map(f => s"${f.path} (${f.lines} lines)"), right = span("UTF-8"))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object StatusBar:
    /** The bar of `left` and `right`. */
    def apply(left: Signal[String], right: UI = UI.empty)(using Frame): UI =
      div
        .role("status")
        .style(_.row.gap(2.px).bg(Colors.surface).padding(0.px, 1.px))(
          left.render(t =>
            span(t)
              .style(Looks.preLine.flexGrow(1).flexShrink(1).minWidth(0.px)),
          ),
          div(right).style(_.flexShrink(0)),
        )
end StatusBar

/** The key hints of a `Keymap` of tui4s-use (`Keymap.help`): each chord bold in
  * `--tui4s-accent` and its help text in `--tui4s-muted`. The hints wrap into
  * more rows when the screen is narrow. A pure UI value.
  *
  * {{{
  * KeyHints(keys) // keys = Keymap.of(Binding.of("quit", Chord.ctrl('q'))(Tui.quit))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object KeyHints:
    /** The hints of the bindings of `keymap` that have a help text. */
    def apply(keymap: Keymap)(using Frame): UI = of(keymap.help)

    /** The hints `hints` (the chords and the help text of each). A margin of
      * two cells, not a `gap`, keeps the hints apart: a `gap` also puts rows
      * between the wrapped lines, as in CSS.
      */
    def of(hints: Chunk[(String, String)])(using Frame): UI =
      div
        .role("note")
        .style(_.row.flexWrap(_.wrap))(
          UI.fragment(
            hints.map((k, h) =>
              span(
                span(k).style(_.bold.color(Colors.accent)),
                span(s" $h").style(Looks.muted),
              ).style(_.margin(0.px, 2.px, 0.px, 0.px)),
            )*,
          ),
        )
end KeyHints

/** A command palette: a layer with a search field and the bindings of a
  * `Keymap` that match it (fuzzy, on their help text). Enter runs the
  * highlighted binding and closes the layer. The layer is a modal (`position`
  * overlay, `focusTrap`); the field takes the focus when the layer opens and
  * gives it back when it closes. A key in the layer does not reach the app
  * (`stopPropagation`).
  *
  * The highlight is a `Selection` of tui4s-use; the field names the
  * highlighted command with `aria-activedescendant`.
  *
  * Keys (in the layer): typing filters; ArrowUp and ArrowDown move the
  * highlight; Enter runs; Esc closes.
  *
  * {{{
  * for palette <- CommandPalette(appKeys)
  * yield div.onKeyDown { case KeyboardEvent(Keyboard.Char('p'), m, _) if m.ctrl => palette.open; case _ => () }(
  *   body, palette.view)
  * }}}
  */
// claude 2026-10-19: created (L2b); changed (L2b review): a class with a
// private constructor, not a case class (a later field keeps the binary API)
final class CommandPalette private (
    /** The layer (empty while it is closed); put it anywhere in the app. */
    val view: UI,
    /** Opens the layer with an empty search. */
    val open: Unit < Sync,
    /** True while the layer shows. */
    val isOpen: Signal[Boolean],
)

/** Makes a [[CommandPalette]]. */
// claude 2026-10-19: created (L2b)
object CommandPalette:
    /** The palette of the bindings of `commands` that have a help text. */
    // claude 2026-10-19: changed (L2b review): the highlight is a Selection,
    // and the field names it with aria-activedescendant
    def apply(commands: Keymap, title: String = "Commands", limit: Int = 8)(
        using Frame,
    ): CommandPalette < Sync =
        val all = commands.bindings.filter(_.help.nonEmpty)
        for
            shown <- Signal.initRef(false)
            query <- Signal.initRef("")
            found  = query.map(q => filter(all, q).take(limit.max(1)))
            sel   <- useSelection(found, identity[Binding], Edge.Clamp, limit.max(1))
            id    <- Looks.freshId("commands")
        yield
            val close                               = shown.set(false)
            val run: Unit < Async                   =
              for
                  xs <- found.current
                  i  <- sel.index.current
                  _  <-
                    if i < xs.size then close.andThen(xs(i).action).unit
                    else Kyo.unit
              yield ()
            def keys(e: KeyboardEvent): Any < Async =
              e.key match
                  case Keyboard.ArrowUp   => sel.moveUp
                  case Keyboard.ArrowDown => sel.moveDown
                  case Keyboard.Enter     => run
                  case Keyboard.Escape    => close
                  case _                  => Kyo.unit
            val field: UI                           =
              found
                .map(_.nonEmpty)
                .combineLatest(sel.index)
                .render { (any, h) =>
                  val f = input
                    .value(query)
                    .role("combobox")
                    .aria("controls", id)
                    .aria("expanded", "true")
                    .placeholder("type a command")
                    .focusAuto(true)
                    .focusRestore(true)
                    .onInput(_ => sel.first)
                  if any then f.aria("activedescendant", s"$id-$h") else f
                }
            val layer                               =
              div.style(
                _.position(_.overlay).align(_.center).padding(2.px, 0.px),
              )(
                div
                  .role("dialog")
                  .aria("label", title)
                  .focusTrap(true)
                  .stopPropagation(true)
                  .onKeyDown(keys)
                  .style(Looks.layer.width(50.em))(
                    span(title).style(_.bold),
                    field,
                    found
                      .combineLatest(sel.index)
                      .render((xs, h) =>
                        div
                          .id(id)
                          .role("listbox")(
                            UI.fragment(
                              xs.zipWithIndex.map((b, i) =>
                                div
                                  .id(s"$id-$i")
                                  .role("option")
                                  .aria("selected", (i == h).toString)
                                  .style((if i == h then Looks.selected
                                          else Looks.plain).row)(
                                    span(Looks.marker(i == h)),
                                    span(b.help).style(_.flexGrow(1)),
                                    span(b.chords.map(_.label).mkString("/"))
                                      .style(Looks.muted),
                                  ),
                              )*,
                            ),
                          ),
                      ),
                  ),
              )
            new CommandPalette(
              when(shown)(layer),
              query.set("").andThen(sel.first).andThen(shown.set(true)),
              shown,
            )
        end for
    end apply

    /** The bindings whose help text has the characters of `query` in order
      * (case does not count), in their order.
      */
    def filter(bindings: Chunk[Binding], query: String): Chunk[Binding] =
      bindings.filter(b =>
        Autocomplete.matches(Autocomplete.Match.Fuzzy, query, b.help),
      )
end CommandPalette

/** A modal dialog over `useDialog` of tui4s-use: a layer in the middle of the
  * screen with a question and its answer. [[Modal.confirm]] answers yes or no;
  * [[Modal.prompt]] answers a text, or Absent. The layer is a modal (`position`
  * overlay, `focusTrap`); its first control takes the focus and gives it back
  * when the layer goes; a key in the layer does not reach the app
  * (`stopPropagation`). The requests wait in order: one layer at a time.
  *
  * A key handler calls [[ask]]: it starts the flow on the fiber of the dialog
  * and returns at once (a handler that waits for the answer would stop the keys
  * that answer it). The fiber ends with the caller's `Scope`.
  *
  * {{{
  * for confirm <- Modal.confirm()
  * yield div.onKeyDown {
  *   case KeyboardEvent(Keyboard.Delete, _, _) => confirm.ask("Delete it?")(yes => if yes then delete else ())
  *   case _ => ()
  * }(list, confirm.view)
  * }}}
  *
  * @param view
  *   the layer (empty while no request is open); put it anywhere in the app
  */
// claude 2026-10-19: created (L2b)
final class Modal[A] private (
    val view: UI,
    dialog: Dialog[String, A],
    reset: Unit < Sync,
):
    /** Opens `question` when the requests before it are closed, and gives the
      * answer to `andThen`; returns at once.
      */
    def ask(question: String)(andThen: A => Any < Async)(using
        Frame,
    ): Unit < Sync =
      dialog.launch(reset.andThen(dialog.open(question)).map(andThen))

    /** The open question; Absent when no layer shows. */
    def request: Signal[Maybe[String]] = dialog.request
end Modal

/** Makes a confirm or a prompt [[Modal]]. */
// claude 2026-10-19: created (L2b)
object Modal:
    /** A yes-or-no question. Keys: `y` or the Yes button answer true; `n`, Esc
      * or the No button answer false; Tab moves between the buttons.
      */
    def confirm(yes: String = "Yes", no: String = "No")(using
        Frame,
    ): Modal[Boolean] < (Sync & Scope) =
      useDialog[String, Boolean].map(d =>
          val keys: KeyboardEvent => Any < Async = e =>
            e.key match
                case Keyboard.Char('y')                   => d.close(true)
                case Keyboard.Char('n') | Keyboard.Escape => d.close(false)
                case _                                    => Kyo.unit
          Modal(
            layer(d, keys)(q =>
              UI.fragment(
                span(q),
                div.style(_.row.gap(2.px).justify(_.center))(
                  button(yes)
                    .focusAuto(true)
                    .focusRestore(true)
                    .onClick(d.close(true)),
                  button(no).onClick(d.close(false)),
                ),
              ),
            ),
            d,
            Kyo.unit,
          ),
      )

    /** A question with a text answer. Keys (in the field): Enter answers the
      * text; Esc answers Absent.
      */
    def prompt(placeholder: String = "")(using
        Frame,
    ): Modal[Maybe[String]] < (Sync & Scope) =
      for
          d    <- useDialog[String, Maybe[String]]
          text <- Signal.initRef("")
      yield
          val keys: KeyboardEvent => Any < Async = e =>
            e.key match
                case Keyboard.Enter  => text.get.map(t => d.close(Present(t)))
                case Keyboard.Escape => d.close(Absent)
                case _               => Kyo.unit
          Modal(
            layer(d, keys)(q =>
              UI.fragment(
                span(q),
                input
                  .value(text)
                  .placeholder(placeholder)
                  .focusAuto(true)
                  .focusRestore(true),
              ),
            ),
            d,
            text.set(""),
          )

    /** The layer of the open request of `d`. */
    private def layer[A](
        d: Dialog[String, A],
        keys: KeyboardEvent => Any < Async,
    )(
        body: String => UI,
    )(using Frame): UI =
      d.request.render(r =>
        r.fold(UI.empty: UI)(q =>
          div.style(_.position(_.overlay).justify(_.center).align(_.center))(
            div
              .role("alertdialog")
              .aria("label", q)
              .focusTrap(true)
              .stopPropagation(true)
              .onKeyDown(keys)
              .style(Looks.layer.gap(1.px).minWidth(30.em))(body(q)),
          ),
        ),
      )
end Modal

/** Short messages at the bottom right of the screen, over the rest; each one
  * goes away after `lifetime`, and at most `max` show (the newest last). The
  * time comes from the clock of tui4s-use (`useNow`), a fiber that ends with
  * the caller's `Scope`. The border of a toast is the colour of its level.
  *
  * {{{
  * for toasts <- Toasts()
  * yield div(button("Save").onClick(save.andThen(toasts.show("saved", Toasts.Level.Success))), toasts.view)
  * }}}
  *
  * @param view
  *   the layer of the toasts (empty while none shows); put it anywhere in the
  *   app
  */
// claude 2026-10-19: created (L2b)
final class Toasts private (
    val view: UI,
    queue: SignalRef[Chunk[Toasts.Toast]],
    lifetime: Duration,
    max: Int,
):
    /** Shows `text` for the lifetime of the toasts. */
    def show(text: String, level: Toasts.Level = Toasts.Level.Info)(using
        Frame,
    ): Unit < Sync =
      Clock.now.map(now =>
        queue
          .updateAndGet(q =>
            Toasts.push(q, Toasts.Toast(text, level, now + lifetime), now, max),
          )
          .unit,
      )
end Toasts

/** Makes [[Toasts]], and the rules of the queue. */
// claude 2026-10-19: created (L2b)
object Toasts:
    /** The level of a toast: its mark and its colour. */
    enum Level derives CanEqual:
        /** "i", in `--tui4s-border`. */
        case Info

        /** "✓", in `--tui4s-success`. */
        case Success

        /** "!", in `--tui4s-warning`. */
        case Warning

        /** "✗", in `--tui4s-danger`. */
        case Error
    end Level

    /** A toast: its text, its level, and the time it goes away. */
    final case class Toast(text: String, level: Level, until: Instant)
        derives CanEqual

    /** The toasts, each for `lifetime`, at most `max` at a time. */
    def apply(lifetime: Duration = 3.seconds, max: Int = 3)(using
        Frame,
    ): Toasts < (Sync & Scope) =
      for
          queue <- Signal.initRef(Chunk.empty[Toast])
          now   <- useNow(250.millis)
      yield new Toasts(
        queue
          .combineLatest(now)
          .map((q, t) => visible(q, t, max))
          .render(ts =>
            div.style(
              _.position(_.fixed)
                .bottom(1.px)
                .right(2.px)
                .zIndex(20)
                .align(_.end),
            )(
              UI.fragment(
                ts.map(t =>
                  div
                    .role("status")
                    .style(Looks.layer.borderColor(color(t.level)))(
                      s"${mark(t.level)} ${t.text}",
                    ),
                )*,
              ),
            ),
          ),
        queue,
        lifetime,
        max,
      )

    private def color(l: Level): Style.Color = l match
        case Level.Info    => Colors.border
        case Level.Success => Colors.success
        case Level.Warning => Colors.warning
        case Level.Error   => Colors.danger

    private def mark(l: Level): String = l match
        case Level.Info    => "i"
        case Level.Success => "✓"
        case Level.Warning => "!"
        case Level.Error   => "✗"

    /** The toasts of `all` that show at `now`: not gone, the last `max`. */
    def visible(all: Chunk[Toast], now: Instant, max: Int): Chunk[Toast] =
      all.filter(_.until > now).takeRight(max.max(0))

    /** The queue after `t` comes at `now`: the toasts that show, then `t`. */
    def push(
        all: Chunk[Toast],
        t: Toast,
        now: Instant,
        max: Int,
    ): Chunk[Toast] =
      (visible(all, now, max) :+ t).takeRight(max.max(0))
end Toasts
