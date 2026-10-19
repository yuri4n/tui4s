package examples.assistant

import kyo.*
import kyo.UI.*
import tui4s.use.{Binding, Keymap}
import tui4s.widgets.*

/** The size of the screen in cells: the terminal, or the window on the web. The
  * main of a platform gives it as a signal.
  */
// claude 2026-10-19: created (L3)
final case class ScreenSize(width: Int, height: Int) derives CanEqual

/** The cells and the rows of the parts of the screen: a pure function of its
  * size, so the transcript knows its width (to wrap) and its height (to follow
  * the end).
  */
// claude 2026-10-19: created (L3)
final case class Metrics(textCells: Int, textRows: Int) derives CanEqual

/** The layout rules of the screen. */
// claude 2026-10-19: created (L3)
object Metrics:
    /** The width of the list of conversations: the marker "› " (2 cells), a
      * title, the border and the padding (2 cells at each side).
      */
    val listCells: Int = Title.cells + 6

    /** The rows of the text of the composer. */
    val composerRows: Int = 3

    /** The rows of the text of the log pane. */
    val logRows: Int = 4

    /** The metrics of a screen of `s`, with the log pane `log` and `hintRows`
      * rows of key hints. Fixed rows: the composer (its rows and its border),
      * the status bar, the hints, the log pane when it shows (its rows, its
      * status line, its search field and its border), and the border of the
      * transcript.
      */
    def of(s: ScreenSize, log: Pane, hintRows: Int): Metrics =
        val logPane = log match
            case Pane.Shown  => logRows + 2 + 2
            case Pane.Hidden => 0
        val fixed   = (composerRows + 2) + 1 + hintRows + logPane + 2
        Metrics((s.width - listCells - 4).max(10), (s.height - fixed).max(3))

    /** The rows of the key hints `hints` in `width` cells, as `KeyHints` wraps
      * them: each hint is its chord, a space, its text, and a margin of 2.
      */
    def hintRows(hints: Chunk[(String, String)], width: Int): Int =
      hints
        .map((k, h) => Cells.width(k) + 1 + Cells.width(h) + 2)
        .foldLeft((1, 0)) { case ((rows, used), w) =>
          if used == 0 || used + w <= width then (rows, used + w)
          else (rows + 1, w)
        }
        ._1
end Metrics

/** One line of the transcript: what it is, and its text. */
// claude 2026-10-19: created (L3)
final case class Line(kind: Line.Kind, text: String) derives CanEqual

/** The kinds of the lines, and the pure view of a conversation as lines. */
// claude 2026-10-19: created (L3)
object Line:
    /** What a line is: it gives its style. */
    enum Kind derives CanEqual:
        /** The name of the writer of a message. */
        case Header(role: Role)

        /** A line of the text of a message. */
        case Text

        /** An error or a stop under a reply. */
        case Note

        /** The hint of an empty conversation. */
        case Hint

        /** The empty line between two messages. */
        case Blank
    end Kind

    /** The hint of an empty conversation. */
    val hint: String =
      "Type a message below. Enter sends it; Alt-Enter or Ctrl-J starts a new line. Ctrl-P lists the commands."

    /** The lines of `c` in `width` cells: for each message, its writer, its
      * text (wrapped) and its note; an empty line between two messages.
      */
    // claude 2026-10-19: changed: the hint is a parameter (L4: the web has its own)
    def of(
        c: Maybe[Conversation],
        width: Int,
        hint: String = Line.hint,
    ): Chunk[Line] =
      c.filter(_.messages.nonEmpty)
        .fold(wrap(hint, width).map(Line(Kind.Hint, _)))(c =>
          Chunk
            .from(
              c.messages.zipWithIndex.map((m, i) =>
                (if i == 0 then Chunk.empty else Chunk(Line(Kind.Blank, ""))) ++
                  message(m, width),
              ),
            )
            .flatten,
        )

    // claude 2026-10-19: changed: a note for a stop that is not an end (L3 review)
    private def message(m: Message, width: Int): Chunk[Line] =
        val header = m.role match
            case Role.User      => "You"
            case Role.Assistant => "Assistant"
        val note   = m.status match
            case MessageStatus.Failed(ChatError.Cancelled)    => Chunk("■ stopped")
            case MessageStatus.Failed(e)                      => wrap(s"✗ ${e.describe}", width)
            case MessageStatus.Streaming if m.content.isEmpty => Chunk("…")
            case MessageStatus.Done(StopReason.MaxTokens)     =>
              Chunk("■ the reply stopped at the token limit")
            case MessageStatus.Done(StopReason.Refusal)       =>
              Chunk("✗ the model declined to answer")
            case MessageStatus.Done(StopReason.Other(name))   =>
              wrap(s"■ the reply stopped: $name", width)
            case _                                            => Chunk.empty
        Line(Kind.Header(m.role), header) +:
          ((if m.content.isEmpty then Chunk.empty else wrap(m.content, width))
            .map(Line(Kind.Text, _)) ++
            note.map(Line(Kind.Note, _)))
    end message

    /** The lines of `text` in `width` cells: its own line breaks, then a wrap
      * at the spaces; a word wider than the line is cut by cells. A tab is 4
      * spaces.
      */
    def wrap(text: String, width: Int): Chunk[String] =
        val w = width.max(1)
        Chunk
          .from(
            text
              .replace("\t", "    ")
              .replace("\r", "")
              .split("\n", -1)
              .nn
              .map(_.nn),
          )
          .flatMap(line =>
            if Cells.width(line) <= w then Chunk(line) else words(line, w),
          )
    end wrap

    /** The greedy wrap of one line with no line break. */
    private def words(line: String, w: Int): Chunk[String] =
        val (done, last) =
          Chunk
            .from(line.split(" ", -1).nn.map(_.nn))
            .foldLeft((Chunk.empty[String], "")) { case ((acc, cur), word) =>
              if cur.isEmpty && acc.isEmpty && word.isEmpty then (acc, cur)
              else if Cells.width(cur) + (if cur.isEmpty then 0 else 1) + Cells
                    .width(word) <= w
              then (acc, if cur.isEmpty then word else s"$cur $word")
              else
                  val pieces = cut(word, w).filter(_.nonEmpty)
                  (
                    (if cur.isEmpty then acc else acc :+ cur) ++ pieces
                      .dropRight(1),
                    pieces.lastOption.getOrElse(""),
                  )
            }
        if last.isEmpty then done else done :+ last
    end words

    /** `word` in pieces of at most `w` cells (a wide character is never cut). */
    private def cut(word: String, w: Int): Chunk[String] =
        @scala.annotation.tailrec
        def go(i: Int, acc: Chunk[String]): Chunk[String] =
          if i >= word.length then acc
          else
              val cp   = word.codePointAt(i)
              val s    = word.substring(i, i + Character.charCount(cp))
              val next =
                if Cells.width(acc.last) + Cells.of(cp) <= w then
                    acc.dropRight(1) :+ (acc.last + s)
                else acc :+ s
              go(i + Character.charCount(cp), next)
        go(0, Chunk(""))
    end cut
end Line

/** The UI of the assistant: plain kyo-ui with the widgets of tui4s-widgets and
  * the composables of tui4s-use (no API of tui4s-core, so the web target can
  * take it). The screen:
  *
  *   - at the left, the list of the conversations (`ListView`): the arrows
  *     select one, and the selected one is the active one;
  *   - at the right, the transcript of the active conversation: its lines,
  *     wrapped, follow the end while a reply streams (`f` stops and starts the
  *     follow; the arrows, PageUp, PageDown, Home and End scroll it);
  *   - the log pane (Ctrl-L), a `Pager` over the log of the app;
  *   - the composer: a textarea in a form, so Enter sends and Alt-Enter or
  *     Ctrl-J starts a new line;
  *   - the status bar (the provider, the model, the tokens, a spinner while a
  *     reply streams) and the key hints;
  *   - the layers: the command palette (Ctrl-P), the settings (Ctrl-S; the keys
  *     never show, only where each one comes from), the help (F1), and the
  *     questions of delete (Ctrl-D) and rename (Ctrl-R).
  *
  * The colours are the variables of tui4s-widgets (`Colors`). The root carries
  * the `data-theme` of the look ([[Look]]): the run gives the stylesheet of all
  * the looks (`Look.palette` in the terminal, `Look.stylesheet` on the web),
  * and the settings switch the look.
  */
// claude 2026-10-19: created (L3)
object AssistantUi:

    /** The store of the app from the services of its environment, and its UI on
      * the [[Surface]] of the environment. `quit` ends the app; `screen` is the
      * size of the screen in cells.
      */
    // claude 2026-10-19: changed: the surface (L4)
    // region: run
    def run(mode: StartMode, quit: Unit < Sync, screen: Signal[ScreenSize])(
        using Frame,
    ): UI < (Env[Wiring.Services & Surface] & Async & Scope) =
      Env
        .get[Surface]
        .map(surface =>
          Assistant
            .make(mode, surface.look)
            .map(store => app(store, surface, quit, screen)),
        )
    // endregion: run

    /** One line of text that keeps its spaces and ends with "…" at the edge. */
    private val pre: Style =
      Style.whiteSpace(_.pre).overflow(_.hidden).textOverflow(_.ellipsis)

    /** The UI of `store` on `surface`. */
    // claude 2026-10-19: changed: the panels of tui4s-widgets (L3 review); the
    // surface: its units, its keys, its composer (L4)
    def app(
        store: Assistant,
        surface: Surface,
        quit: Unit < Sync,
        screen: Signal[ScreenSize],
    )(using Frame): UI < (Sync & Scope) =
        for
            follow  <- Signal.initRef(Pager.Follow.Tail)
            offset  <- Signal.initRef(0)
            confirm <- Modal.confirm()
            prompt  <- Modal.prompt("a title")
            spinner <- Spinner(label = "streaming")
            list    <- ListView.of(store.chats, store.selection, height = 500)(c =>
                         span(c.title).style(pre),
                       )
            logView <- Pager(
                         store.log.lines,
                         height = Metrics.logRows,
                         follow = Model.Local(Pager.Follow.Tail),
                       )
            drafts  <- Drafts.make(store)
            echo    <- AtomicRef.init[Surface.Echo](Surface.Echo.Off)
            radio   <- RadioGroup(
                         store.providers.available.head,
                         store.providers.available.drop(1)*,
                       )(value = Model.Bound(drafts.provider), label = _.label)
            // claude 2026-10-19: created (L4): the switch of the look
            looks   <- RadioGroup(Look.Terminal, Look.Web)(
                         value = Model.Bound(drafts.look),
                         label = _.label,
                       )
            tail     = follow.set(Pager.Follow.Tail)
            palette <- CommandPalette(
                         keymap(
                           store,
                           surface,
                           ChatCommand.palette,
                           quit,
                           tail,
                           confirm,
                           prompt,
                           drafts,
                           Kyo.unit,
                           store.cancel,
                         ),
                         limit = ChatCommand.palette.size,
                       )
            // Esc at the root. In a terminal an open layer takes Esc first.
            // On the web (kyo-ui 1.0.0-RC7) a key in a field of a layer
            // skips the handlers of the layer and comes to the root: so Esc
            // here also closes an open layer, and else stops the reply.
            // claude 2026-10-19: created (L4)
            escape   = store.overlay.get.map {
                         case Overlay.Closed =>
                           palette.isOpen.current.map(open =>
                             if open then palette.close else store.cancel,
                           )
                         case _              => store.overlay.set(Overlay.Closed)
                       }
            keys     = keymap(
                         store,
                         surface,
                         Chunk.from(ChatCommand.values),
                         quit,
                         tail,
                         confirm,
                         prompt,
                         drafts,
                         palette.open,
                         escape,
                       )
            hints    = keymap(
                         store,
                         surface,
                         ChatCommand.hints,
                         quit,
                         tail,
                         confirm,
                         prompt,
                         drafts,
                         palette.open,
                         escape,
                       )
            metrics  =
              screen
                .combineLatest(store.logPane)
                .map((s, p) =>
                  Metrics.of(s, p, Metrics.hintRows(hints.help, s.width)),
                )
            // The body is made once. The region of the look gives a thin root
            // around it: the same body and the same handler at each render,
            // so the repair tick finds the region equal and does nothing.
            // claude 2026-10-19: changed: the body is made once (L4 review)
            inner    = div.style(_.height(100.pct))(
                         div(
                           Panel(
                             "Chats",
                             style = Style
                               .width(surface.cols(Metrics.listCells))
                               .flexShrink(0) ++ surface.panel,
                           )(
                             div.style(
                               _.flexGrow(1).minHeight(0.px).overflowY(_.auto),
                             )(
                               list,
                             ),
                           ),
                           Panel.reactive(
                             store.active.map(_.fold("")(_.title)),
                             style =
                               Style.flexGrow(1).minWidth(0.px) ++ surface.panel,
                           )(
                             transcript(store, surface, metrics, follow, offset),
                           ),
                         ).style(
                           metrics
                             .map(m =>
                               Style.row
                                 .height(surface.rows(m.textRows + 2))
                                 .flexShrink(0),
                             ),
                         ),
                         when(store.logPane.map(_ == Pane.Shown))(
                           Panel(
                             "Log",
                             style = Style.flexShrink(
                               0,
                             ) ++ surface.lines ++ surface.panel,
                           )(
                             logView,
                           ),
                         ),
                         Panel(
                           surface.composerTitle,
                           style = Style.flexShrink(0) ++ surface.panel,
                         )(
                           composer(
                             surface,
                             textarea
                               .value(store.composer)
                               .placeholder("Type a message")
                               .focusAuto(true)
                               .style(
                                 _.height(surface.rows(Metrics.composerRows)),
                               ),
                             store.composer,
                             tail.andThen(store.send),
                             echo,
                           ),
                         ),
                         StatusBar(
                           store.settings
                             .combineLatest(store.tokens)
                             .map((s, t) =>
                               s"${s.provider.label} · ${s.model} · ${t.input} in, ${t.output} out",
                             ),
                           right = store.streaming.render {
                             case Streaming.Running(_) => spinner
                             case Streaming.Idle       =>
                               span("ready").style(_.color(Colors.muted))
                           },
                         ),
                         KeyHints(hints),
                         settingsLayer(store, surface, drafts, radio, looks),
                         helpLayer(store, surface, keys),
                         palette.view,
                         confirm.view,
                         prompt.view,
                       )
        // claude 2026-10-19: changed: the root carries the theme of the look
        // (L4); only the root is in the region of the look (L4 review)
        yield store.look.render(l =>
          div
            .data("theme", l.theme.name)
            .onKeyDown(keys.handler)
            .style(_.height(100.pct))(inner),
        )
        end for
    end app

    /** The composer `area` on `surface`. In a terminal the text area is in a
      * form: Enter sends it, and Alt-Enter and Ctrl-J insert a line break. On
      * the web the text area is not in a form (kyo-ui submits a form on each
      * Enter in it, also Shift-Enter), and a browser inserts a line break for
      * Enter and Shift-Enter. So on the web Enter runs `send`, and the input
      * event of the break that follows gives back what the send left
      * ([[Surface.echo]]); Shift-Enter keeps the break of the browser, at the
      * caret; Alt-Enter (and Ctrl-J on macOS: on another system the browser
      * keeps Ctrl-J) adds a line break at the end (a handler does not know the
      * caret).
      */
    // claude 2026-10-19: created (L4)
    private def composer(
        surface: Surface,
        area: UI.Ast.Textarea,
        text: SignalRef[String],
        send: Unit < Async,
        echo: AtomicRef[Surface.Echo],
    )(using Frame): UI =
      surface match
          case Surface.Terminal       => form.onSubmit(send)(area)
          // claude 2026-10-19: changed: Ctrl-J only on macOS (L4 review)
          case Surface.Browser(_, os) =>
            def plain(m: Modifiers) = !m.ctrl && !m.alt && !m.shift && !m.meta
            area
              .onKeyDown(e =>
                e.key match
                    case Keyboard.Enter if plain(e.modifiers) =>
                      for
                          before <- text.get
                          _      <- send
                          after  <- text.get
                          _      <- echo.set(Surface.Echo.Sent(before, after))
                      yield ()
                    case Keyboard.Enter if e.modifiers.alt    =>
                      text.updateAndGet(_ + "\n").unit
                    case Keyboard.Char('j')
                        if e.modifiers.ctrl && os == Surface.Os.Mac =>
                      text.updateAndGet(_ + "\n").unit
                    case _                                    => Kyo.unit,
              )
              .onInput(t =>
                echo
                  .getAndSet(Surface.Echo.Off)
                  .map(e =>
                    if e == Surface.Echo.Off then Kyo.unit
                    else text.set(Surface.echo(e, t)),
                  ),
              )

    /** The keymap of `commands`: each command with its keys, its help and its
      * action.
      */
    // claude 2026-10-19: changed: the chords of the surface; a command with no
    // chord there is not in the keymap; the action of Esc (L4)
    def keymap(
        store: Assistant,
        surface: Surface,
        commands: Chunk[ChatCommand],
        quit: Unit < Sync,
        tail: Unit < Sync,
        confirm: Modal[Boolean],
        prompt: Modal[Maybe[String]],
        drafts: Drafts,
        openPalette: Unit < Sync,
        escape: Any < Async,
    )(using Frame): Keymap =
        def action(c: ChatCommand): Any < Async = c match
            case ChatCommand.NewChat      => tail.andThen(store.newChat)
            case ChatCommand.NextChat     => tail.andThen(store.nextChat)
            case ChatCommand.PreviousChat => tail.andThen(store.previousChat)
            case ChatCommand.DeleteChat   =>
              store.active.current.map(
                _.fold(Kyo.unit)(c =>
                  confirm.ask(s"Delete the chat \"${c.title}\"?")(yes =>
                    if yes then store.delete(c.id) else Kyo.unit,
                  ),
                ),
              )
            case ChatCommand.RenameChat   =>
              prompt.ask("A new title for the chat")(t =>
                t.fold(Kyo.unit)(store.renameActive),
              )
            case ChatCommand.OpenSettings => drafts.open
            case ChatCommand.ToggleLog    => store.toggleLog
            case ChatCommand.Cancel       => escape
            case ChatCommand.Help         => store.overlay.set(Overlay.Help)
            case ChatCommand.Palette      => openPalette
            case ChatCommand.Quit         => quit
        Keymap.of(
          commands
            .filter(_.chords(surface).nonEmpty)
            .map(c => Binding(c.chords(surface), c.help, action(c)))*,
        )
    end keymap

    /** The transcript: the window of the lines of the active conversation. The
      * window follows the end (`Pager.Follow.Tail`) until a key scrolls it up;
      * `f` and End follow again, and so does a send.
      */
    // claude 2026-10-19: changed: the rows of the surface; its hint (L4)
    def transcript(
        store: Assistant,
        surface: Surface,
        metrics: Signal[Metrics],
        follow: SignalRef[Pager.Follow],
        offset: SignalRef[Int],
    )(using Frame): UI =
        val shown                               = store.active
          .combineLatest(metrics)
          .map((c, m) => (Line.of(c, m.textCells, surface.hint), m.textRows))
          .combineLatest(offset)
          .combineLatest(follow)
          .map { case (((ls, h), o), f) =>
            (ls, h, Pager.top(ls.size, h, o, f))
          }
        def scrollTo(to: Int): Unit < Async     =
          shown.current.map { (ls, h, _) =>
            val end = (ls.size - h).max(0)
            val at  = to.min(end).max(0)
            offset
              .set(at)
              .andThen(
                follow.set(if at < end then Pager.Follow.Off
                else Pager.Follow.Tail),
              )
          }
        def by(delta: Int): Unit < Async        =
          shown.current.map((_, _, top) => scrollTo(top + delta))
        def keys(e: KeyboardEvent): Any < Async =
          e.key match
              case Keyboard.ArrowUp | Keyboard.Char('k')   => by(-1)
              case Keyboard.ArrowDown | Keyboard.Char('j') => by(1)
              case Keyboard.PageUp if !e.modifiers.ctrl    =>
                shown.current.map((_, h, _) => by(-h))
              case Keyboard.PageDown if !e.modifiers.ctrl  =>
                shown.current.map((_, h, _) => by(h))
              case Keyboard.Home                           => scrollTo(0)
              case Keyboard.End | Keyboard.Char('f')       =>
                follow.set(Pager.Follow.Tail)
              case _                                       => Kyo.unit
        shown.render { (ls, h, top) =>
          div
            .role("log")
            .tabIndex(0)
            .preventScrollKeys
            .onKeyDown(keys)
            .style(_.height(surface.rows(h)).overflow(_.hidden))(
              UI.fragment(ls.slice(top, top + h).map(line)*),
            )
        }
    end transcript

    /** The look of a line. */
    private def line(l: Line)(using Frame): UI =
        val text = if l.text.isEmpty then " " else l.text
        l.kind match
            case Line.Kind.Header(Role.User)      =>
              span(text).style(pre.bold.color(Colors.accent))
            case Line.Kind.Header(Role.Assistant) =>
              span(text).style(pre.bold.color(Colors.success))
            case Line.Kind.Text                   => span(text).style(pre)
            case Line.Kind.Note                   =>
              span(text).style(pre.color(Colors.danger))
            case Line.Kind.Hint                   =>
              span(text).style(pre.color(Colors.muted))
            case Line.Kind.Blank                  => span(" ")
        end match
    end line

    /** A row of a layer: a label of 13 cells and its control. */
    // claude 2026-10-19: changed: the units of the surface (L4)
    private def field(surface: Surface, label: String, control: UI)(using
        Frame,
    ): UI =
      div.style(_.row.gap(surface.cols(1)).align(_.start))(
        span(label).style(_.width(surface.cols(13)).flexShrink(0)),
        control,
      )

    /** The settings: the provider (only those with a key), the model, the
      * temperature, the look and the system prompt. Enter saves (a form); Esc
      * or Cancel closes. The keys never show: in a terminal the layer says
      * where each one comes from; on the web ([[keyFields]]) the person types a
      * key in a masked field, and the layer says only that one is kept.
      */
    // claude 2026-10-19: changed: Modal.layer of tui4s-widgets (L3 review);
    // the look, the surface, the keys of the web (L4)
    def settingsLayer(
        store: Assistant,
        surface: Surface,
        drafts: Drafts,
        radio: UI,
        looks: UI,
    )(using Frame): UI =
        val keys = Chunk(ProviderChoice.Anthropic, ProviderChoice.OpenAI)
          .map(p =>
            store.providers
              .source(p)
              .fold(s"${p.label}: no key")(s =>
                s"${p.label}: a key ${s.describe}",
              ),
          )
        val f    = field(surface, _, _)
        Modal.layer(
          store.overlay.map(_ == Overlay.Settings),
          "Settings",
          store.overlay.set(Overlay.Closed),
          Style.width(surface.cols(64)),
        )(
          form.onSubmit(drafts.save)(
            f("Provider", radio),
            f(
              "Model",
              input
                .value(drafts.model)
                .focusAuto(true)
                .focusRestore(true)
                .style(_.flexGrow(1)),
            ),
            f(
              "Temperature",
              input
                .value(drafts.temperature)
                .placeholder("the default of the provider")
                .style(_.flexGrow(1)),
            ),
            // claude 2026-10-19: created (L3 review): the default model of
            // Anthropic answers HTTP 400 to a temperature
            when(drafts.model.map(_.trim == Models.anthropic))(
              f(
                "",
                span("This model takes no temperature: keep it empty.")
                  .style(pre.color(Colors.muted)),
              ),
            ),
            f("Look", looks),
            f(
              "System prompt",
              textarea
                .value(drafts.system)
                .style(_.height(surface.rows(3)).flexGrow(1)),
            ),
            surface match
                case Surface.Terminal      =>
                  f(
                    "Keys",
                    div(keys.map(k => span(k).style(pre.color(Colors.muted)))*),
                  )
                case Surface.Browser(_, _) => UI.empty
            ,
            span(drafts.error).style(_.color(Colors.danger)),
            div.style(_.row.gap(surface.cols(2)))(
              button("Save"),
              button("Cancel")
                .onClick(store.overlay.set(Overlay.Closed))
                .stopPropagation(true),
            ),
          ),
          surface match
              case Surface.Terminal          => UI.empty
              case Surface.Browser(vault, _) =>
                keyFields(store, surface, drafts, vault),
        )
    end settingsLayer

    /** The keys of the web: for each real provider a masked field and Keep (a
      * form: Enter keeps too), and Forget; the field never shows a kept key,
      * only that one is kept. A change starts the app again ([[KeyVault]]).
      * Outside the form of the settings, so Keep does not save them. The field
      * is not bound (`.value(ref)`): kyo-ui writes a bound value into the
      * `value` attribute of the DOM, in clear text. Its input events write the
      * draft, and the layer makes a new, empty field each time it opens.
      */
    // claude 2026-10-19: created (L4); changed: the field is not bound, the
    // warning names extensions (L4 review)
    private def keyFields(
        store: Assistant,
        surface: Surface,
        drafts: Drafts,
        vault: KeyVault,
    )(using Frame): UI =
      div.style(_.gap(surface.cols(1)))(
        span(
          "Your key stays in this browser. Extensions and anyone with this profile can read it.",
        ).style(_.color(Colors.warning)),
        UI.fragment(
          drafts.keys.map((p, text) =>
            div.style(_.row.gap(surface.cols(1)).align(_.center))(
              form
                .onSubmit(drafts.keep(p, vault))(
                  div.style(_.row.gap(surface.cols(1)).align(_.center))(
                    span(s"${p.label} key").style(
                      _.width(surface.cols(13)).flexShrink(0),
                    ),
                    passwordInput
                      .onInput(t => text.set(t))
                      .placeholder(
                        store.providers
                          .source(p)
                          .fold("no key: paste one here")(s =>
                            s"a key is ${s.describe}",
                          ),
                      )
                      .style(_.flexGrow(1)),
                    button("Keep"),
                  ),
                )
                .style(_.flexGrow(1)),
              button("Forget").onClick(vault.forget(p)),
            ),
          )*,
        ),
      )

    /** The help: the keys of the commands and of the composer. */
    // claude 2026-10-19: changed: Modal.layer of tui4s-widgets (L3 review);
    // the keys of the composer and the units of the surface (L4)
    def helpLayer(store: Assistant, surface: Surface, keys: Keymap)(using
        Frame,
    ): UI =
        val rows = keys.help ++ surface.composerHelp ++ Chunk(
          "Tab" -> "the next part (composer, chats, transcript)",
          "f"   -> "follow the end (in the transcript)",
        )
        Modal.layer(
          store.overlay.map(_ == Overlay.Help),
          "Help",
          store.overlay.set(Overlay.Closed),
          Style.width(surface.cols(64)),
        )(
          div(
            rows.map((k, h) =>
              div.style(_.row)(
                span(k).style(
                  _.bold
                    .color(Colors.accent)
                    .width(surface.cols(18))
                    .flexShrink(0),
                ),
                span(h),
              ),
            )*,
          ),
          button("Close")
            .focusAuto(true)
            .focusRestore(true)
            .onClick(store.overlay.set(Overlay.Closed))
            .style(_.alignSelf(_.center)),
        )
    end helpLayer
end AssistantUi

/** The fields of the settings layer, as drafts: [[open]] copies the settings
  * into them, [[save]] checks them and gives them to the store.
  */
// claude 2026-10-19: created (L3)
final class Drafts private (
    store: Assistant,
    /** The provider of the draft. */
    val provider: SignalRef[ProviderChoice],
    /** The model of the draft. */
    val model: SignalRef[String],
    /** The temperature of the draft, as text. */
    val temperature: SignalRef[String],
    /** The system prompt of the draft. */
    val system: SignalRef[String],
    /** The look of the draft. */
    // claude 2026-10-19: created (L4)
    val look: SignalRef[Look],
    /** The text of a new key of each real provider (the web). */
    // claude 2026-10-19: created (L4)
    val keys: Chunk[(ProviderChoice, SignalRef[String])],
    /** The error of the last save; empty when there is none. */
    val error: SignalRef[String],
)(using Frame):
    /** Copies the settings into the drafts and opens the layer. */
    // claude 2026-10-19: changed: the look (L4)
    def open: Unit < Sync =
      store.settings.current.map(s =>
        provider
          .set(s.provider)
          .andThen(model.set(s.model))
          .andThen(temperature.set(s.temperature.fold("")(_.toString)))
          .andThen(system.set(s.systemPrompt))
          .andThen(store.look.current.map(look.set))
          .andThen(Kyo.foreachDiscard(keys)((_, t) => t.set("")))
          .andThen(error.set(""))
          .andThen(store.overlay.set(Overlay.Settings)),
      )

    /** Keeps the key of the draft of `p` in `vault` (the app starts again), or
      * shows why the text is not a key.
      */
    // claude 2026-10-19: created (L4)
    def keep(p: ProviderChoice, vault: KeyVault): Unit < Sync =
      Maybe
        .fromOption(keys.find(_._1 == p))
        .fold(Kyo.unit)((_, text) =>
          text.get.map(t =>
            ApiKey.parse(t, KeySource.Browser) match
                case Absent     =>
                  error.set(
                    s"not a key of ${p.label}: 1 to 512 visible characters, no space",
                  )
                case Present(k) => text.set("").andThen(vault.keep(p, k)),
          ),
        )

    /** Checks the drafts; gives them to the store and closes the layer, or
      * shows why they are not valid. A new provider takes its default model
      * when the model was not changed.
      */
    // claude 2026-10-19: changed: the look (L4)
    def save: Unit < Sync =
      for
          before <- store.settings.current
          p      <- provider.get
          m      <- model.get
          t      <- temperature.get
          sys    <- system.get
          l      <- look.get
          _      <- Settings.temperature(t) match
                        case Left(why)   => error.set(why)
                        case Right(temp) =>
                          val keep =
                            if p != before.provider && m.trim == before.model then
                                p.defaultModel
                            else m
                          store
                            .updateSettings(Settings(p, keep, temp, sys))
                            .andThen(store.setLook(l))
                            .andThen(store.overlay.set(Overlay.Closed))
      yield ()
end Drafts

/** Makes the [[Drafts]] of a store. */
// claude 2026-10-19: created (L3)
object Drafts:
    /** Empty drafts; [[Drafts.open]] fills them. */
    // claude 2026-10-19: changed: the look and the keys (L4)
    def make(store: Assistant)(using Frame): Drafts < Sync =
      for
          s <- store.settings.current
          p <- Signal.initRef(s.provider)
          m <- Signal.initRef(s.model)
          t <- Signal.initRef("")
          y <- Signal.initRef(s.systemPrompt)
          l <- store.look.current.map(Signal.initRef(_))
          k <- Kyo.foreach(
                 Chunk(ProviderChoice.Anthropic, ProviderChoice.OpenAI),
               )(p => Signal.initRef("").map(r => (p, r)))
          e <- Signal.initRef("")
      yield Drafts(store, p, m, t, y, l, k, e)
end Drafts
