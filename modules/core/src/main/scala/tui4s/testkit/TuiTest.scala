package tui4s.testkit

import jatatui.core.backend.TestBackend
import jatatui.core.terminal.Terminal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kyo.*
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import tui4s.*
import tui4s.internal.CountingBackend
import tui4s.internal.Done
import tui4s.internal.Engine
import tui4s.internal.EventCode
import tui4s.internal.Fold
import tui4s.internal.Item
import tui4s.internal.Json
import tui4s.internal.MemoryTrace
import tui4s.internal.Painter
import tui4s.internal.Running
import tui4s.internal.Step
import tui4s.internal.Supervisor
import tui4s.internal.discard

/** A headless driver for an app: the same fold, frame rules, source diff and
  * painter as the runtime, synchronously on the caller's thread, on a jatatui
  * `TestBackend`. It needs no terminal and starts no fiber, so Kyo, ZIO and
  * Cats Effect users test the same way.
  *
  * {{{
  * val t = TuiTest(CounterApp, 40, 6)
  * t.press('+', '+')
  * assert(t.state == 2)
  * assert(t.screen.contains("Count: 2"))
  * }}}
  *
  * Sources and tasks are recorded, not run: [[sources]] lists the declared
  * sources, [[started]] the one-shot tasks, and [[emit]] / [[fail]] stand in
  * for their values and failures.
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
    private val traceLog = MemoryTrace(TuiTest.TraceLines)
    private val sup      = Supervisor[S]()
    private val engine   =
      Engine(app, options, Painter(options.theme), sup, traceLog)
    private val tasks    = ArrayBuffer.empty[String]
    private var ended    = false
    // The size of the last Resize folded; the backend takes it when the next
    // frame is drawn, so a batch that draws nothing keeps the last frame.
    private var newSize  = Option.empty[Resize]
    // A copy of the last drawn frame while the backend does not hold it (a
    // resized frame whose paint failed: jatatui clears before the paint).
    private var kept     = Option.empty[Screen]

    if engine.start() && engine.syncWork() then
        recordWork()
        if !engine.firstFrame(width, height, draw) then end()
    else end()

    /** The current state. */
    def state: S = engine.state

    /** True after `Quit`, Ctrl-C (with `TuiOptions.ctrlCQuits`) or a failure.
      * Later input is ignored, and the screen stays the last drawn frame.
      */
    def quit: Boolean = ended

    /** The failure that ended the run: a [[TuiError.AppFailed]] that names
      * where user code threw ("init", "view", "sources", "update on key:x",
      * "fold of cpu", "paint"), or a [[TuiError.SourceFailed]] from [[fail]].
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

    /** The view of the current state as an indented tree, one node per line. */
    def viewTree: String = ViewTree(view)

    /** The labels of the standing sources that the current state declares,
      * after the diff, in order; a repeated label has a `#1`, `#2` suffix.
      */
    def sources: Seq[String] = sup.declared

    /** The labels of the one-shot tasks started so far, in start order. */
    def started: Seq[String] = tasks.toVector

    /** The terminal cursor (column, row) of the last drawn frame. */
    def cursor: Option[(Int, Int)] = engine.painter.cursor

    /** The trace line (SPEC 5.16) of the last batch: what came in, whether the
      * state changed, and whether a frame was drawn. A key that did nothing
      * shows `"state":"same","frame":"skip-same"`.
      */
    def lastTrace: String =
      traceLog.lines.findLast(_.contains("\"kind\":\"batch\"")).getOrElse("")

    /** Every trace line so far (the newest 10 000): one "start" line, one
      * "batch" line per batch, "slow" lines for user code slower than one frame
      * budget, and a "quit" line at the end.
      */
    def trace: Seq[String] = traceLog.lines

    /** Presses each key in its own batch, like a person typing. A `Char` is
      * `Key.Char(c)`, a control character the named key ('\n' is Enter).
      */
    def press(keys: (Key | Char)*): this.type =
        keys.foreach(k => input(Seq(Bind.toKey(k))))
        this

    /** Sends all `events` in one batch: at most one frame for all of them. A
      * [[Resize]] resizes the test terminal for that frame.
      */
    def send(events: Event*): this.type =
        input(events)
        this

    /** Types `text`, one batch per grapheme: a one-char grapheme is a
      * `Key.Char` (a control character the named key: "\n" and "\r\n" are
      * Enter, "\t" is Tab), a longer one (an emoji, a letter with a combining
      * mark) is a `Paste`, because a terminal cannot send it as one key.
      */
    def typeText(text: String): this.type =
        TuiTest.typed(text).foreach(e => input(Seq(e)))
        this

    /** Pastes `text` (one [[Paste]] event). */
    def paste(text: String): this.type =
        input(Seq(Paste(text)))
        this

    /** A click at the cell (x, y): button down and up in one batch. Their
      * [[Mouse.tags]] come from the last drawn frame. Needs
      * `TuiOptions(mouse = true)`.
      */
    def click(x: Int, y: Int, button: Button = Button.Left): this.type =
        input(TuiTest.clicked(x, y, button))
        this

    /** Sends [[Resize]] (as `send(Resize(width, height))` does). The next frame
      * is drawn even when the view is equal, on a terminal of the new size.
      */
    def resize(width: Int, height: Int): this.type =
      send(Resize(width, height))

    /** A value from the source or task `label`, folded with its CURRENT fold
      * (the fold of the newest declaration). A task is then done. A value for a
      * stream source is one element.
      *
      * Throws an `AssertionError` when no source or task `label` runs (the
      * message lists the known labels) or when `value` does not fit the fold
      * (its cast fails). A fold that throws for a value that fits ends the run
      * with an [[TuiError.AppFailed]], as any user code does.
      */
    def emit(label: String, value: Any): this.type =
        if !ended then
            val r                = running("emit", label)
            val v                =
              if r.source.kind == Source.Kind.Chunked then Seq(value) else value
            val items: Seq[Item] =
              if r.source.kind == Source.Kind.Task then
                  Seq(Fold(r.token, v), Done(r.token, Result.succeed(())))
              else Seq(Fold(r.token, v))
            engine.emittedClass = if value == null then null else value.getClass
            try batch(items)
            catch
                case c: ClassCastException =>
                  val kind =
                    if value == null then "null"
                    else value.getClass.getSimpleName
                  throw AssertionError(
                    s"emit($label): $value ($kind) does not fit the fold of " +
                      s"source $label",
                    c,
                  )
            finally engine.emittedClass = null
            end try
        end if
        this
    end emit

    /** The source or task `label` fails with `error`: its `recover` folds it,
      * or the run ends with [[TuiError.SourceFailed]]. Throws an
      * `AssertionError` when no source or task `label` runs.
      */
    def fail(label: String, error: Throwable): this.type =
        if !ended then
            val r = running("fail", label)
            batch(Seq(Done(r.token, Result.fail(error))))
        this

    /** Folds `f` into the state, as a general source or a handle would. */
    def fold(f: S => Next[S]): this.type =
        if !ended then batch(Seq(Fold(Fold.Direct, f)))
        this

    private def running(what: String, label: String): Running[S] =
        val found = sup.byLabel(label)
        if found == null then
            val known = sup.runningLabels.distinct
            throw AssertionError(
              s"$what($label): no running source or task with this label " +
                s"(known: ${
                    if known.isEmpty then "none" else known.mkString(", ")
                  })",
            )
        end if
        found
    end running

    private def input(events: Seq[Event]): Unit =
        events.foreach(TuiTest.reported(options, _))
        if !ended then
            val sent = events.map {
              case k: Key => Key.sent(k)
              case other  => other
            }
            sent.foreach {
              case r: Resize => newSize = Some(r)
              case _         => ()
            }
            batch(sent)
        end if
    end input

    /** What the runtime does with a batch, without fibers: fold, plan the work
      * (recorded), draw when needed.
      */
    private def batch(items: Seq[Item]): Unit =
      engine.fold(items) match
          case Step.Quit | Step.Failed   => end()
          case Step.Same | Step.WorkOnly =>
            if engine.syncWork() then
                recordWork()
                engine.skipSame()
            else end()
          case Step.Changed              =>
            if engine.syncWork() then
                recordWork()
                discard(engine.drawIfNeeded(draw))
                if engine.error != null then end()
            else end()

    /** Records the planned starts; the stops need nothing (no fibers). */
    private def recordWork(): Unit =
        sup.toStart.foreach(r =>
          if !r.standing && !r.stopped then tasks += r.source.label,
        )
        sup.toStart.clear()
        sup.toStop.clear()

    private def end(): Unit =
        ended = true
        engine.finish("quit")
        sup.stopEverything()
        sup.toStop.clear()

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
    private val TraceLines = 10_000

    /** A driver for `app` on a `width` x `height` test terminal. It runs
      * `init`, records `sources(init)` and draws the first frame.
      */
    def apply[S](app: App[S], width: Int = 80, height: Int = 24): TuiTest[S] =
      new TuiTest(app, width, height)

    /** A driver that replays the input of a trace file (`TuiOptions.trace`,
      * SPEC 5.16): the events of each traced batch go into `update` as one
      * batch. Values of sources and tasks are not replayed (folds are
      * functions); use [[TuiTest.emit]] for them.
      */
    def replay[S](
        app: App[S],
        trace: Path,
        width: Int = 80,
        height: Int = 24,
    ): TuiTest[S] =
        val t = TuiTest(app, width, height)
        Files.readAllLines(trace, StandardCharsets.UTF_8).asScala.foreach {
          line =>
            if Json.string(line, "kind").contains("batch") then
                val events =
                  Json
                    .strings(line, "in")
                    .getOrElse(Vector.empty)
                    .flatMap(
                      EventCode.decode,
                    )
                if events.nonEmpty then discard(t.send(events*))
        }
        t
    end replay

    /** The real loop (fibers, supervisor, frame rules) on a test terminal,
      * under `Clock.withTimeControl` and `Random.withSeed(0)`, with no frame
      * cap (`fps = 0`, whatever the app's options say). The app stops when
      * `test` ends. When the app failed ([[TuiError.AppFailed]],
      * [[TuiError.SourceFailed]]), `live` fails with that error after `test`
      * (`LiveTest.error` reads it during the test). Kyo-typed; see
      * [[liveBlocking]] for other frameworks.
      *
      * {{{
      * TuiTest.live(DashboardApp, 72, 16) { t =>
      *   t.advance(1.second).andThen(t.state).map(d => assert(d.history.size == 5))
      * }
      * }}}
      */
    def live[S, A](app: App[S], width: Int = 80, height: Int = 24)(
        test: LiveTest[S] => A < (Async & Abort[Throwable]),
    )(using Frame): A < (Async & Abort[Throwable]) =
      LiveRun(app, width, height)(test)

    /** [[live]] for blocking test frameworks (munit, JUnit): blocks the calling
      * thread until `test` ends, at most `timeout`.
      */
    def liveBlocking[S, A](
        app: App[S],
        width: Int = 80,
        height: Int = 24,
        timeout: Duration = 30.seconds,
    )(test: LiveTest[S] => A < (Async & Abort[Throwable]))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe
          .runAndBlock(timeout)(live(app, width, height)(test))
          .getOrThrow
    end liveBlocking

    /** The events of typing `text`, one per grapheme. */
    private[testkit] def typed(text: String): Vector[Event] =
      TextField.graphemes(text).map { g =>
        if g.length == 1 then Key.Char(g.charAt(0))
        else if g == "\r\n" then Key.Enter
        else Paste(g)
      }

    /** Button down and up at (x, y). */
    private[testkit] def clicked(x: Int, y: Int, button: Button): Seq[Event] =
      Seq(
        Mouse(MouseKind.Down(button), x, y, Mods(), Nil),
        Mouse(MouseKind.Up(button), x, y, Mods(), Nil),
      )

    /** Fails the test for an event that the runtime does not report with the
      * app's options: such a test would pass, and the app would not work.
      */
    private[testkit] def reported(options: TuiOptions, event: Event): Unit =
      event match
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
end TuiTest
