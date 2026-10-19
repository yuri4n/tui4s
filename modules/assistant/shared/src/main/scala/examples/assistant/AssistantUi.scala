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
    def of(c: Maybe[Conversation], width: Int): Chunk[Line] =
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

    /** The store of the app from the services of its environment, and its UI.
      * `quit` ends the app; `screen` is the size of the screen.
      */
    // region: run
    def run(mode: StartMode, quit: Unit < Sync, screen: Signal[ScreenSize])(
        using Frame,
    ): UI < (Env[Wiring.Services] & Async & Scope) =
      Assistant.make(mode).map(store => app(store, quit, screen))
    // endregion: run

    /** One line of text that keeps its spaces and ends with "…" at the edge. */
    private val pre: Style =
      Style.whiteSpace(_.pre).overflow(_.hidden).textOverflow(_.ellipsis)

    /** The UI of `store`. */
    // claude 2026-10-19: changed: the panels of tui4s-widgets (L3 review)
    def app(store: Assistant, quit: Unit < Sync, screen: Signal[ScreenSize])(
        using Frame,
    ): UI < (Sync & Scope) =
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
                           ChatCommand.palette,
                           quit,
                           tail,
                           confirm,
                           prompt,
                           drafts,
                           Kyo.unit,
                         ),
                         limit = ChatCommand.palette.size,
                       )
            keys     = keymap(
                         store,
                         Chunk.from(ChatCommand.values),
                         quit,
                         tail,
                         confirm,
                         prompt,
                         drafts,
                         palette.open,
                       )
            hints    = keymap(
                         store,
                         ChatCommand.hints,
                         quit,
                         tail,
                         confirm,
                         prompt,
                         drafts,
                         palette.open,
                       )
            metrics  =
              screen
                .combineLatest(store.logPane)
                .map((s, p) =>
                  Metrics.of(s, p, Metrics.hintRows(hints.help, s.width)),
                )
        // claude 2026-10-19: changed: the root carries the theme of the look
        // (L4)
        yield store.look.render(l =>
          div
            .data("theme", l.theme.name)
            .onKeyDown(keys.handler)
            .style(_.height(100.pct))(
              div(
                Panel(
                  "Chats",
                  style = Style.width(Metrics.listCells.em).flexShrink(0),
                )(
                  div.style(_.flexGrow(1).minHeight(0.px).overflowY(_.auto))(
                    list,
                  ),
                ),
                Panel.reactive(
                  store.active.map(_.fold("")(_.title)),
                  style = Style.flexGrow(1).minWidth(0.px),
                )(
                  transcript(store, metrics, follow, offset),
                ),
              ).style(
                metrics
                  .map(m => Style.row.height((m.textRows + 2).em).flexShrink(0)),
              ),
              when(store.logPane.map(_ == Pane.Shown))(
                Panel("Log", style = Style.flexShrink(0))(logView),
              ),
              Panel(
                "Message · Enter sends · Alt-Enter new line",
                style = Style.flexShrink(0),
              )(
                form.onSubmit(tail.andThen(store.send))(
                  textarea
                    .value(store.composer)
                    .placeholder("Type a message")
                    .focusAuto(true)
                    .style(_.height(Metrics.composerRows.em)),
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
              settingsLayer(store, drafts, radio, looks),
              helpLayer(store, keys),
              palette.view,
              confirm.view,
              prompt.view,
            ),
        )
        end for
    end app

    /** The keymap of `commands`: each command with its keys, its help and its
      * action.
      */
    def keymap(
        store: Assistant,
        commands: Chunk[ChatCommand],
        quit: Unit < Sync,
        tail: Unit < Sync,
        confirm: Modal[Boolean],
        prompt: Modal[Maybe[String]],
        drafts: Drafts,
        openPalette: Unit < Sync,
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
            case ChatCommand.Cancel       => store.cancel
            case ChatCommand.Help         => store.overlay.set(Overlay.Help)
            case ChatCommand.Palette      => openPalette
            case ChatCommand.Quit         => quit
        Keymap.of(commands.map(c => Binding(c.chords, c.help, action(c)))*)
    end keymap

    /** The transcript: the window of the lines of the active conversation. The
      * window follows the end (`Pager.Follow.Tail`) until a key scrolls it up;
      * `f` and End follow again, and so does a send.
      */
    def transcript(
        store: Assistant,
        metrics: Signal[Metrics],
        follow: SignalRef[Pager.Follow],
        offset: SignalRef[Int],
    )(using Frame): UI =
        val shown                               = store.active
          .combineLatest(metrics)
          .map((c, m) => (Line.of(c, m.textCells), m.textRows))
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
            .style(_.height(h.em).overflow(_.hidden))(
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
    private def field(label: String, control: UI)(using Frame): UI =
      div.style(_.row.gap(1.px).align(_.start))(
        span(label).style(_.width(13.em).flexShrink(0)),
        control,
      )

    /** The settings: the provider (only those with a key), the model, the
      * temperature and the system prompt. Enter saves (a form); Esc or Cancel
      * closes. The keys never show: the layer says where each one comes from.
      */
    // claude 2026-10-19: changed: Modal.layer of tui4s-widgets (L3 review);
    // the look (L4)
    def settingsLayer(store: Assistant, drafts: Drafts, radio: UI, looks: UI)(
        using Frame,
    ): UI =
        val keys = Chunk(ProviderChoice.Anthropic, ProviderChoice.OpenAI)
          .map(p =>
            store.providers
              .source(p)
              .fold(s"${p.label}: no key")(s =>
                s"${p.label}: a key ${s.describe}",
              ),
          )
        Modal.layer(
          store.overlay.map(_ == Overlay.Settings),
          "Settings",
          store.overlay.set(Overlay.Closed),
          Style.width(64.em),
        )(
          form.onSubmit(drafts.save)(
            field("Provider", radio),
            field(
              "Model",
              input
                .value(drafts.model)
                .focusAuto(true)
                .focusRestore(true)
                .style(_.flexGrow(1)),
            ),
            field(
              "Temperature",
              input
                .value(drafts.temperature)
                .placeholder("the default of the provider")
                .style(_.flexGrow(1)),
            ),
            // claude 2026-10-19: created (L3 review): the default model of
            // Anthropic answers HTTP 400 to a temperature
            when(drafts.model.map(_.trim == Models.anthropic))(
              field(
                "",
                span("This model takes no temperature: keep it empty.")
                  .style(pre.color(Colors.muted)),
              ),
            ),
            field("Look", looks),
            field(
              "System prompt",
              textarea.value(drafts.system).style(_.height(3.em).flexGrow(1)),
            ),
            field(
              "Keys",
              div(keys.map(k => span(k).style(pre.color(Colors.muted)))*),
            ),
            span(drafts.error).style(_.color(Colors.danger)),
            div.style(_.row.gap(2.px))(
              button("Save"),
              button("Cancel")
                .onClick(store.overlay.set(Overlay.Closed))
                .stopPropagation(true),
            ),
          ),
        )
    end settingsLayer

    /** The help: the keys of the commands and of the composer. */
    // claude 2026-10-19: changed: Modal.layer of tui4s-widgets (L3 review)
    def helpLayer(store: Assistant, keys: Keymap)(using Frame): UI =
        val rows = keys.help ++ Chunk(
          "Enter"            -> "send the message (in the composer)",
          "Alt-Enter/Ctrl-J" -> "a new line (in the composer)",
          "Tab"              -> "the next part (composer, chats, transcript)",
          "f"                -> "follow the end (in the transcript)",
        )
        Modal.layer(
          store.overlay.map(_ == Overlay.Help),
          "Help",
          store.overlay.set(Overlay.Closed),
          Style.width(64.em),
        )(
          div(
            rows.map((k, h) =>
              div.style(_.row)(
                span(k).style(
                  _.bold.color(Colors.accent).width(18.em).flexShrink(0),
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
          .andThen(error.set(""))
          .andThen(store.overlay.set(Overlay.Settings)),
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
    def make(store: Assistant)(using Frame): Drafts < Sync =
      for
          s <- store.settings.current
          p <- Signal.initRef(s.provider)
          m <- Signal.initRef(s.model)
          t <- Signal.initRef("")
          y <- Signal.initRef(s.systemPrompt)
          l <- store.look.current.map(Signal.initRef(_))
          e <- Signal.initRef("")
      yield Drafts(store, p, m, t, y, l, e)
end Drafts
