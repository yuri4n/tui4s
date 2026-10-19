package tui4s.testkit

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.{Keyboard, KeyboardEvent, Modifiers}
import tui4s.internal.*
import tui4s.{FrameCause, FrameStats, TuiOptions}

/** A headless run on a jatatui `TestBackend`, for tests and measurements.
  *
  * The kit is strict: `screen` and `lines` return what the screen shows NOW,
  * and nothing waits unless its name says so (`nextFrame`, `awaitEnd`,
  * `awaitScreen`). A test that writes a `SignalRef` and wants to see the result
  * waits with `awaitScreen` (or `nextFrame`, when it knows that one frame
  * follows).
  *
  * The run ends with the enclosing `Scope`: `start` registers a finalizer that
  * posts the quit message and waits (at most 5 s) until the run fiber has
  * ended, so no watcher outlives the test.
  *
  * One test fiber drives a `Headless`. Its methods are not for two fibers at
  * the same time: the key methods, `paste`, `resize` and `nextFrame` take
  * frames from one shared channel, so a second caller can take the frame that
  * the first one waits for.
  *
  * The keys go to the app as a terminal sends them: `press` sends one
  * [[kyo.UI.KeyboardEvent]], `key` builds one from a [[kyo.UI.Keyboard]] and
  * modifiers, `char` and `typeText` type characters, `paste` pastes a text (see
  * `TuiOptions.paste`). Each key is one event and waits for its Input frame,
  * which holds all the consequences of the key (the handlers' writes and the
  * regions that derive from them).
  *
  * After the run has ended (a quit, or a failure of the run), a method that
  * waits for a frame (the key methods, `paste`, `resize`, `nextFrame`) fails at
  * once when no frame for it is left, with an `IllegalStateException` (a
  * panic). The key that ended the run still gets its frame.
  */
// claude 2026-10-12: changed (PR #11 review: Codex, Headless.scala:115): the one-driver rule
// claude 2026-10-12: changed: the final API (RC-7, D-15): the quit value, Screen,
// awaitScreen, Vector[FrameStats], no TestBackend and no jatatui Cell in the
// API; the key methods are C3
// claude 2026-10-13: changed (C3): the key methods press, key, char, typeText;
// paste; changed (review R1-8, R2-7): no wait without end after the run
final class Headless private (
    inbox: Channel[Msg],
    frames: Channel[FrameStats],
    // claude 2026-10-19: changed: the canvas, not its backend (P4)
    canvas: Canvas,
    seq: AtomicLong,
    run: Fiber[Unit, Scope],
    runner: Runner,
    // claude 2026-10-13: created (C3): `paste` reads options.paste
    options: TuiOptions,
):
    /** Ends the run. The same action as the quit value that `start` gave the
      * UI.
      */
    // claude 2026-10-12: changed (review R2-8 b): the runner's quit (a flag
    // and the message), so a full inbox cannot drop it
    def quit(using Frame): Unit < Sync = runner.quit

    /** The fibers of the run that have not ended: the R1 gauge for tests. */
    // claude 2026-10-12: created (RC-8)
    private[tui4s] def liveFibers(using Frame): Int < Sync = runner.liveFibers

    /** True when the run has ended. */
    def finished(using Frame): Boolean < Sync = run.done

    /** Waits until the run has ended, at most `timeout` of wall-clock time.
      * True when it ended.
      */
    def awaitEnd(timeout: Duration)(using Frame): Boolean < Async =
      // OS-34: never join the run fiber (`run.get`) from an interruptible
      // wait. A join makes the caller's interrupt wait for the run, and a
      // run that waits for the caller is a deadlock. This polls `done`.
      poll(timeout)(run.done)

    /** Waits until `predicate` holds for the screen, at most `timeout` of
      * wall-clock time; reads the screen every 5 ms. True when it held before
      * the timeout.
      */
    def awaitScreen(predicate: Screen => Boolean, timeout: Duration)(using
        Frame,
    ): Boolean < Async =
      poll(timeout)(screen.map(predicate))

    /** Polls `cond` every 5 ms until it is true or `timeout` of wall-clock time
      * has passed. The bound is `Async.timeout` on Kyo's live clock (a relative
      * sleep): Kyo's `Clock.deadline` reads `System.nanoTime`, and it gives 0
      * for a negative value, so a deadline could never pass.
      */
    // claude 2026-10-19: changed: the bound is Async.timeout on the live clock,
    // not System.nanoTime (P4)
    private def poll(timeout: Duration)(cond: => Boolean < Sync)(using
        Frame,
    ): Boolean < Async =
      Clock.let(Clock.live) {
        Abort.recover[Timeout](_ => false)(
          Async.timeout(timeout)(
            Loop.foreach {
              cond.map { ok =>
                if ok then Loop.done(true)
                else Async.sleep(5.millis).andThen(Loop.continue)
              }
            },
          ),
        )
      }

    /** The screen as it is now: rows, cell styles and the cursor. The read
      * holds the backend's monitor (`Canvas.read`), which the runner holds
      * while it flushes a frame or resizes the backend: a screen is never a
      * torn frame.
      */
    // claude 2026-10-19: changed: the read of the canvas, not a monitor of
    // the kit (P4)
    def screen(using Frame): Screen < Sync = Sync.defer(canvas.read())

    /** The rows of the screen as it is now, with their trailing spaces (one
      * string per row, one place per cell). Under the backend's monitor, as
      * `screen`.
      */
    // claude 2026-10-19: changed: the read of the canvas, not a monitor of
    // the kit (P4)
    def lines(using Frame): Vector[String] < Sync = Sync.defer(canvas.cells())

    /** Sends one key and waits for its frame (the Input frame of this key).
      * Background frames that come first are consumed.
      */
    // claude 2026-10-13: created (C3; the prototype's press)
    def press(ev: KeyboardEvent)(using Frame): FrameStats < Async =
      send(n => Msg.Key(ev, n))

    /** Sends the key `k` with the modifiers `mods` (kyo-ui's chord of ctrl,
      * alt, shift and meta) and waits for its frame.
      *
      * {{{
      * h.key(Keyboard.Tab, Modifiers(shift = true))     // Shift-Tab
      * h.key(Keyboard.Char('c'), Modifiers(ctrl = true)) // Ctrl-C
      * }}}
      */
    // claude 2026-10-19: changed: one Modifiers value, not three Boolean
    // flags (P1b, A5 b)
    def key(k: Keyboard, mods: Modifiers)(using Frame): FrameStats < Async =
      press(KeyboardEvent(k, mods, Absent))

    /** Sends the key `k` with no modifier and waits for its frame.
      *
      * {{{
      * h.key(Keyboard.Enter)
      * }}}
      */
    // claude 2026-10-19: created (P1b, A5 b): the key without modifiers
    def key(k: Keyboard)(using Frame): FrameStats < Async =
      key(k, Modifiers.none)

    /** Types the character `c` as one key and waits for its frame: a space is
      * `Keyboard.Space`, a line break (`\n` or `\r`) is `Keyboard.Enter`, a tab
      * is `Keyboard.Tab`, any other character is `Keyboard.Char(c)`. The event
      * is the one that a real terminal gives: an upper-case character has
      * `shift` (crossterm gives `A` with Shift), any other has no modifiers.
      * `c` is a character of the Basic Multilingual Plane: half of a surrogate
      * pair is not a key, and `char` fails with an `IllegalArgumentException`
      * (a panic). Type a character outside it (an emoji) with `typeText`.
      */
    // claude 2026-10-13: created (C3; the prototype's char, with the line
    // break and the tab); changed (review R2-8): half of a surrogate pair fails
    // claude 2026-10-13: changed (T1, review R1-1): shift for an upper-case
    // character, as the terminal sends it
    def char(c: Char)(using Frame): FrameStats < Async =
      if Character.isSurrogate(c) then
          Abort.panic(
            IllegalArgumentException(
              s"tui4s: char got half of a surrogate pair (U+${c.toInt.toHexString.toUpperCase}); type an emoji with typeText",
            ),
          )
      else press(Headless.keyOf(c.toInt))

    /** Types `s`, one key per character (as [[char]]: an upper-case character
      * has `shift`), and waits for the frame of each key. A line break `\r\n`
      * is one Enter. A character outside the Basic Multilingual Plane (an
      * emoji) is one key `Keyboard.Unknown(<the character>)`, as kyo-ui's
      * `Keyboard.fromString` gives it. The frames are in the order of the keys.
      *
      * {{{
      * for
      *     frames <- h.typeText("call mom")
      *     screen <- h.screen
      * yield assert(frames.size == 8 && screen.contains("call mom"))
      * }}}
      */
    // claude 2026-10-13: changed (T1, review R1-1): the Scaladoc names shift
    // claude 2026-10-19: changed: a Chunk of the frames, not a Seq (P1b, A5 a)
    def typeText(s: String)(using Frame): Chunk[FrameStats] < Async =
      Kyo.foreach(Headless.keysOf(s))(press).map(Chunk.from(_))

    /** Pastes `text` as a terminal sends it, and returns the frame of each
      * event. With `TuiOptions.paste` (the default: bracketed paste) the paste
      * is ONE event: one edit of the focused text input, one frame; a line
      * break in it is a space, so it never submits a form. Without it, the text
      * arrives as single keys, as `typeText` sends them: a line break is an
      * Enter (it can submit a form) and a tab is a Tab (it moves the focus).
      *
      * {{{
      * for
      *     frames <- h.paste("one\ntwo")
      *     screen <- h.screen
      * yield assert(frames.size == 1 && screen.contains("one two"))
      * }}}
      */
    // claude 2026-10-13: created (C3, RC-14 part; the lesson of the review
    // thread TuiTest.scala:166 of the old PR 3: the kit acts as a real
    // terminal)
    // claude 2026-10-19: changed: a Chunk of the frames, not a Seq (P1b, A5 a)
    def paste(text: String)(using Frame): Chunk[FrameStats] < Async =
      if options.paste then send(n => Msg.Paste(text, n)).map(Chunk(_))
      else typeText(text)

    /** Resizes the screen and waits for the frame of the resize. Background
      * frames that come first are consumed. The runner fiber resizes the
      * backend when it handles the message (one fiber touches the backend).
      */
    // claude 2026-10-12: changed (review R2-4): only the message; the runner
    // resizes the backend
    // claude 2026-10-13: changed (C3): the wait is the shared `send`
    def resize(w: Int, h: Int)(using Frame): FrameStats < Async =
      send(n => Msg.Resize(Size(w, h), n))

    /** Posts the Input message that `mk` builds with the next sequence number
      * and waits for its frame (the Input frame with that number). Background
      * frames that come first are consumed. When the run has ended before that
      * frame, the end mark wakes the wait and it fails (see the class).
      */
    // claude 2026-10-13: created (C3; the wait of the old `resize`); changed
    // (review R1-8, R2-7): the end mark
    private def send(mk: Long => Msg)(using Frame): FrameStats < Async =
      seq.getAndIncrement
        .map { n =>
          inbox
            .put(mk(n))
            .andThen {
              Loop.foreach {
                frames.take.map(s =>
                  if s eq Headless.endMark then Headless.ended(frames)
                  else if s.cause == FrameCause.Input && s.seq == n then
                      Loop.done(s)
                  else Loop.continue,
                )
              }
            }
        }
        .handle(Abort.run[Closed])
        .map(_.getOrThrow)

    /** The next frame of any cause (waits for it). After the run has ended and
      * every frame is taken, it fails (see the class).
      */
    // claude 2026-10-13: changed (review R1-8, R2-7): the end mark
    def nextFrame(using Frame): FrameStats < Async =
      Abort
        .run[Closed](
          frames.take.map(s =>
            if s eq Headless.endMark then Headless.ended(frames) else s,
          ),
        )
        .map(_.getOrThrow)

    /** The frames that arrived and were not consumed. */
    // claude 2026-10-13: changed (review R1-8, R2-7): the end mark stays in
    // the channel and is not returned
    def drainFrames(using Frame): Vector[FrameStats] < Sync =
      Abort
        .run[Closed](frames.drain.map { fs =>
          if fs.exists(_ eq Headless.endMark) then
              frames
                .offer(Headless.endMark)
                .andThen(
                  fs.filterNot(_ eq Headless.endMark),
                )
          else fs
        })
        .map(_.getOrElse(Chunk.empty).toVector)
end Headless

object Headless:
    /** The end mark: the end of the run puts it into the frame channel after
      * the last frame, so a method that waits for a frame wakes up and fails
      * instead of a wait without end. Compared by reference, never returned,
      * and put back when taken, so each later wait sees it too.
      */
    // claude 2026-10-13: created (review R1-8, R2-7)
    private val endMark: FrameStats =
      FrameStats(Long.MinValue, FrameCause.Background, 0, 0, 0L, 0)

    /** Puts the end mark back and fails: the run has ended and no frame for the
      * caller is left.
      */
    // claude 2026-10-13: created (review R1-8, R2-7)
    private def ended(frames: Channel[FrameStats])(using
        Frame,
    ): Nothing < (Abort[Closed] & Sync) =
      frames
        .offer(endMark)
        .andThen(
          Abort.panic(
            IllegalStateException(
              "tui4s: the run has ended (a quit, or a failure of the run), so no frame comes",
            ),
          ),
        )

    /** Starts a headless run of `ui` on a `width` x `height` screen and returns
      * after its first frame. `ui` gets the quit value: running it ends the run
      * (D-15). [[tui4s.Tui.quit]] does the same inside the construction of
      * `ui`, inside a handler, and inside a fiber that they start. The run ends
      * with the enclosing `Scope` (quit, then a wait for the run fiber).
      */
    // claude 2026-10-13: changed (C3, RC-2 part): Tui.quit works inside the UI
    // construction (runner.scoped)
    def start(width: Int, height: Int, options: TuiOptions = TuiOptions())(
        ui: (Unit < Sync) => UI < (Async & Scope),
    )(using Frame): Headless < (Async & Scope) =
      for
          // claude 2026-10-12: changed: the knobs (test seams) reach the runner
          knobs   <- Knobs.get
          // claude 2026-10-19: changed: the options are checked once, as in
          // the real terminal (RunOptions, amendment A10)
          runOpts  = RunOptions.of(options)
          inbox   <- Channel.initUnscoped[Msg](runOpts.inboxCapacity)
          frames  <- Channel.initUnscoped[FrameStats](1 << 16)
          backend <- Sync.defer(TestBackend(width, height))
          /* claude 2026-10-12: changed (PR #11 review: Codex,
           * Headless.scala:88): the seam */
          canvas  <- Sync.defer(Canvas(backend, knobs.flushSeam))
          // claude 2026-10-19: changed: Runner.init (P2, A7)
          runner  <- Runner.init(inbox, canvas, runOpts, Present(frames), knobs)
          seq     <- AtomicLong.init(1L)
          run     <- Fiber.init(runner.scoped(ui(runner.quit).map(runner.run)))
          // claude 2026-10-13: created (review R1-8, R2-7): the end mark after
          // the last frame
          _       <- run.onComplete(_ => Abort.run[Closed](frames.offer(endMark)))
          h        = Headless(inbox, frames, canvas, seq, run, runner, options)
          // it quits and waits before its scope ends (RC-7)
          _       <- Scope.ensure(h.quit.andThen(h.awaitEnd(5.seconds)))
          _       <- firstFrame(frames, run)
      yield h

    /** The key that types the code point `cp` (see `char` and `typeText`): the
      * event that the input thread gives for the key of a terminal
      * ([[EventMapping.typed]], then [[EventMapping.key]]), so an upper-case
      * character has `shift`. A character outside the Basic Multilingual Plane
      * is `Keyboard.Unknown(<the character>)`, and half of a surrogate pair (in
      * a broken string) is `Keyboard.Char` with no modifiers.
      */
    // claude 2026-10-13: created (C3)
    // claude 2026-10-13: changed (T1, review R1-1): the event of a terminal,
    // through EventMapping (it gave no modifiers to an upper-case character)
    private def keyOf(cp: Int): KeyboardEvent =
        def plain(k: Keyboard) = KeyboardEvent(k, Modifiers.none, Absent)
        if !Character.isBmpCodePoint(cp) then
            plain(Keyboard.Unknown(String(Character.toChars(cp))))
        else
            val c = cp.toChar
            // claude 2026-10-19: changed: EventMapping.key gives a Maybe (P1)
            EventMapping
              .key(EventMapping.typed(c))
              .getOrElse(plain(Keyboard.Char(c)))
        end if
    end keyOf

    /** The keys that type `s`: one per code point; `\r\n` is one Enter. */
    // claude 2026-10-13: created (C3)
    private def keysOf(s: String): Vector[KeyboardEvent] =
        val cps = s.replace("\r\n", "\n").codePoints().toArray.toVector
        cps.map(keyOf)

    /** Waits for the first frame. When the run ends before it (the UI or the
      * initial mount failed), the run's result surfaces here instead of a hang
      * until the test timeout. A join of a DONE fiber is not a wait (OS-34).
      */
    // claude 2026-10-12: created (review R2-8 a)
    private def firstFrame(
        frames: Channel[FrameStats],
        run: Fiber[Unit, Scope],
    )(using Frame): Unit < (Async & Scope) =
      Loop.foreach {
        Abort.run[Closed](frames.poll).map {
          // claude 2026-10-13: changed (review R1-8, R2-7): the end mark (the
          // run ended before its first frame) stays for the later waits
          case Result.Success(Present(s)) if s eq endMark =>
            Abort
              .run[Closed](frames.offer(endMark))
              .andThen(run.get)
              .andThen(Loop.done)
          case Result.Success(Present(_))                 => Loop.done
          case _                                          =>
            run.done.map { ended =>
              if ended then run.get.andThen(Loop.done)
              else
                  Clock
                    .let(Clock.live)(Async.sleep(5.millis))
                    .andThen(Loop.continue)
            }
        }
      }
end Headless
