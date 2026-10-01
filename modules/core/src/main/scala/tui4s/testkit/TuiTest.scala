package tui4s.testkit

import jatatui.core.backend.TestBackend
import jatatui.core.terminal.Terminal
import tui4s.*
import tui4s.internal.CountingBackend
import tui4s.internal.Engine
import tui4s.internal.Painter
import tui4s.internal.Step
import tui4s.internal.discard

/** A headless driver for an app: the same fold, frame rules and painter as the
  * runtime, synchronously on the caller's thread, on a jatatui `TestBackend`.
  * It needs no terminal and no effect runtime, so Kyo, ZIO and Cats Effect
  * users test the same way.
  *
  * {{{
  * val t = TuiTest(CounterApp, 40, 6)
  * t.press('+', '+')
  * assert(t.state == 2)
  * assert(t.screen.contains("Count: 2"))
  * }}}
  *
  * There is no frame cap: each batch that changes the view draws a frame. User
  * code that throws does not throw into the test: the run ends, `quit` is true
  * and `error` says where.
  *
  * Input arrives as a terminal sends it. A key goes through the mapping of a
  * terminal: '\n' and '\r' are `Key.Enter`, '\t' is `Key.Tab`, ESC is
  * `Key.Esc`, DEL and '\b' are `Key.Backspace`, and `Key.Ctrl('S')` is
  * `Key.Ctrl('s')`. A [[Mouse]] event needs `TuiOptions(mouse = true)`, and
  * [[FocusGained]] / [[FocusLost]] need `TuiOptions(focusEvents = true)`: the
  * runtime reports them only then, so without the option the input method
  * throws an `AssertionError`.
  *
  * The input methods return this driver; write one call per statement (a
  * chained call used as a statement warns under `-Wnonunit-statement`).
  */
final class TuiTest[S] private (app: App[S], width: Int, height: Int):
    private val backend  = TestBackend(width, height)
    private val counting = CountingBackend(backend)
    private val terminal = Terminal.create(counting)
    private val options  = app.options
    private val engine   = Engine(app, options, Painter(options.theme))
    private var ended    = false
    // The size of the last Resize folded; the backend takes it when the next
    // frame is drawn, so a batch that draws nothing keeps the last frame.
    private var newSize  = Option.empty[Resize]
    // A copy of the last drawn frame while the backend does not hold it (a
    // resized frame whose paint failed: jatatui clears before the paint).
    private var kept     = Option.empty[Screen]

    if engine.start() then after(Step.Changed) else ended = true

    /** The current state. */
    def state: S = engine.state

    /** True after `Quit`, Ctrl-C (with `TuiOptions.ctrlCQuits`) or a failure of
      * user code. Later input is ignored, and the screen stays the last drawn
      * frame.
      */
    def quit: Boolean = ended

    /** The failure of user code that ended the run: a [[TuiError.AppFailed]]
      * that names where ("init", "view", "update on key:x", "paint").
      */
    def error: Option[TuiError] = Option(engine.error)

    /** The frames drawn: 1 after construction (the first frame). */
    def frames: Long = engine.stats.frames

    /** The counters of the run. */
    def stats: Stats = engine.stats

    /** An immutable copy of the last drawn frame. */
    def screen: Screen =
      kept.getOrElse(Screen.of(backend.buffer(), engine.painter.cursor))

    /** The view of the current state (calls `view`). */
    def view: View = app.view(engine.state)

    /** The terminal cursor (column, row) of the last drawn frame. */
    def cursor: Option[(Int, Int)] = engine.painter.cursor

    /** Presses each key in its own batch, like a person typing. A `Char` is
      * `Key.Char(c)`, a control character the named key ('\n' is Enter).
      */
    def press(keys: (Key | Char)*): this.type =
        keys.foreach(k => batch(Seq(Bind.toKey(k))))
        this

    /** Sends all `events` in one batch: at most one frame for all of them. A
      * [[Resize]] resizes the test terminal for that frame.
      */
    def send(events: Event*): this.type =
        batch(events)
        this

    /** Types `text`, one batch per grapheme: a one-char grapheme is a
      * `Key.Char` (a control character the named key: "\n" and "\r\n" are
      * Enter, "\t" is Tab), a longer one (an emoji, a letter with a combining
      * mark) is a `Paste`, because a terminal cannot send it as one key.
      */
    def typeText(text: String): this.type =
        TextField.graphemes(text).foreach { g =>
          if g.length == 1 then batch(Seq(Key.Char(g.charAt(0))))
          else if g == "\r\n" then batch(Seq(Key.Enter))
          else batch(Seq(Paste(g)))
        }
        this

    /** Pastes `text` (one [[Paste]] event). */
    def paste(text: String): this.type =
        batch(Seq(Paste(text)))
        this

    /** A click at the cell (x, y): button down and up in one batch. Their
      * [[Mouse.tags]] come from the last drawn frame. Needs
      * `TuiOptions(mouse = true)`.
      */
    def click(x: Int, y: Int, button: Button = Button.Left): this.type =
        batch(
          Seq(
            Mouse(MouseKind.Down(button), x, y, Mods(), Nil),
            Mouse(MouseKind.Up(button), x, y, Mods(), Nil),
          ),
        )
        this
    end click

    /** Sends [[Resize]] (as `send(Resize(width, height))` does). The next frame
      * is drawn even when the view is equal, on a terminal of the new size.
      */
    def resize(width: Int, height: Int): this.type =
      send(Resize(width, height))

    private def batch(events: Seq[Event]): Unit =
        events.foreach(reported)
        if !ended then
            val sent = events.map {
              case k: Key => Key.sent(k)
              case other  => other
            }
            sent.foreach {
              case r: Resize => newSize = Some(r)
              case _         => ()
            }
            after(engine.fold(sent))
        end if
    end batch

    /** Fails the test for an event that the runtime does not report with the
      * app's options: such a test would pass, and the app would not work.
      */
    private def reported(event: Event): Unit = event match
        case _: Mouse if !options.mouse                      =>
          throw AssertionError(
            "a Mouse event needs TuiOptions(mouse = true): " +
              "the runtime reports no mouse events without it",
          )
        case FocusGained | FocusLost if !options.focusEvents =>
          throw AssertionError(
            s"$event needs TuiOptions(focusEvents = true): " +
              "the runtime reports no focus events without it",
          )
        case _                                               => ()

    /** What the runtime does after a batch, without fibers: draw when needed.
      * One-shot tasks are not run here.
      */
    private def after(step: Step): Unit =
        discard(engine.takePending())
        step match
            case Step.Quit | Step.Failed   => ended = true
            case Step.Same | Step.WorkOnly => ()
            case Step.Changed              =>
              discard(engine.drawIfNeeded(draw))
              if engine.error != null then ended = true
    end after

    private def draw(view: View): Int =
        newSize.foreach { r =>
          kept = Some(screen)
          backend.resize(r.width, r.height)
          newSize = None
        }
        discard(terminal.draw(frame => engine.painter.paint(view, frame)))
        kept = None
        counting.lastCount
    end draw
end TuiTest

/** Builds test drivers. */
object TuiTest:
    /** A driver for `app` on a `width` x `height` test terminal. It runs `init`
      * and draws the first frame.
      */
    def apply[S](app: App[S], width: Int = 80, height: Int = 24): TuiTest[S] =
      new TuiTest(app, width, height)
