package tui4s

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import tui4s.internal.{Canvas, Knobs, Loc, Msg, RunOptions, Runner}
import tui4s.spi.Settle
import tui4s.testkit.Headless

import java.time.Duration as JDuration

/** Input verification and delayed watcher notices observe current signal state. */
// codex 2026-10-23: created: deterministic coverage without watcher timing or repair ticks.
class SignalSettlementTest extends RunnerSuite:
    // codex 2026-10-23: created: a zero allowance makes verification carry each input's consequences.
    private val options = TuiOptions(settle = Settle.Quiescent(JDuration.ZERO))

    // codex 2026-10-23: created: the channels and screen of a directly controlled run.
    private final case class Running(
        inbox: Channel[Msg],
        frames: Channel[FrameStats],
        canvas: Canvas,
    )

    // codex 2026-10-23: created: scoped runtime resources with the repair tick outside the test interval.
    private def start(root: UI)(using Frame): Running < (Async & Scope) =
      for
          inbox  <- Channel.initUnscoped[Msg](64)
          frames <- Channel.initUnscoped[FrameStats](128)
          canvas <- Sync.defer(Canvas(TestBackend(60, 6)))
          _      <- Scope.ensure(Sync.defer(canvas.close()))
          runner <- Runner.init(
                      inbox,
                      canvas,
                      RunOptions.of(options),
                      Present(frames),
                      Knobs(repairInterval = 1.hour),
                    )
          _      <- Fiber.init(runner.run(root))
          _      <- Scope.ensure(runner.quit)
      yield Running(inbox, frames, canvas)

    // codex 2026-10-23: created: current reads work, but the watcher never supplies a change notice.
    private def silent(signal: Signal[UI], armed: Promise[Unit, Any])(using
        Frame,
    ): Signal[UI] =
      Signal.initRaw[UI](
        [B, S] => (f: UI => B < S) => signal.currentWith(f),
        [B, S] =>
          (f: UI => B < S) => armed.completeUnit.andThen(Async.never[UI].map(f)),
      )

    // codex 2026-10-23: created: a closed observation channel fails instead of hiding missing progress.
    private def next(run: Running)(using Frame): FrameStats < Async =
      Abort.run[Closed](run.frames.take).map {
        case Result.Success(frame) => frame
        case _                     => Abort.panic(IllegalStateException("frame channel closed"))
      }

    // codex 2026-10-23: created: inject a real key and await its own completed frame.
    private def key(run: Running, value: Char, seq: Long)(using
        Frame,
    ): FrameStats < Async =
      Abort
        .run[Closed](
          run.inbox.put(
            Msg.Key(
              KeyboardEvent(Keyboard.Char(value), Modifiers.none, Absent),
              seq,
            ),
          ),
        )
        .andThen(
          Loop.foreach {
            next(run).map(frame =>
              if frame.seq == seq then Loop.done(frame) else Loop.continue,
            )
          },
        )

    // codex 2026-10-23: created: an armed, old reactive region is pulled, and a delayed notice cannot restore an older value.
    "input pulls current UI before a notice, and a delayed notice cannot roll it back"
      .timeout(10.seconds) in {
      Scope.run {
        for
            value  <- Signal.initRef("old")
            armed  <- Promise.init[Unit, Any]
            signal  = silent(
                        value.map(text =>
                          div.onKeyDown {
                            case KeyboardEvent(Keyboard.Char('n'), _, _) =>
                              value.set("new")
                            case _                                       => ()
                          }(span(text)),
                        ),
                        armed,
                      )
            run    <- start(UI.Ast.Reactive[UI](signal))
            _      <- next(run)
            _      <- armed.get
            _      <- key(run, 'x', 1L)
            _      <- key(run, 'x', 2L)
            delayed = Msg.Changed(Loc.root, 1L)
            input  <- key(run, 'n', 3L)
            after  <- Sync.defer(run.canvas.read())
            _      <- Abort.run[Closed](run.inbox.put(delayed))
            notice <- next(run)
            later  <- Sync.defer(run.canvas.read())
        yield
            assert(after.contains("new") && !after.contains("old"), after.text)
            assert(input.repaired > 0, input.toString)
            assert(notice.cause == FrameCause.Background, notice.toString)
            assert(notice.repaired == 0, notice.toString)
            assert(later.contains("new") && !later.contains("old"), later.text)
      }
    }

    // codex 2026-10-23: created: notices of an obsolete generation do not read or update its replacement.
    "a notice of another generation cannot update the current region"
      .timeout(10.seconds) in {
      Scope.run {
        for
            value         <- Signal.initRef("old")
            armed         <- Promise.init[Unit, Any]
            sentinel      <- Signal.initRef[UI](span("barrier before"))
            sentinelArmed <- Promise.init[Unit, Any]
            signal         = silent(value.map(text => span(text): UI), armed)
            run           <- start(
                               div(
                                 UI.Ast.Reactive[UI](signal),
                                 UI.Ast.Reactive[UI](silent(sentinel, sentinelArmed)),
                               ),
                             )
            _             <- next(run)
            _             <- armed.get
            _             <- sentinelArmed.get
            _             <- key(run, 'x', 1L)
            _             <- key(run, 'x', 2L)
            _             <- value.set("unobserved")
            _             <- Abort.run[Closed](
                               run.inbox.put(Msg.Changed(Loc.root.child(0), 0L)),
                             )
            _             <- Abort.run[Closed](
                               run.inbox.put(
                                 Msg.Content(Loc.root.child(1), 2L, span("barrier after")),
                               ),
                             )
            // An ignored Background notice emits no frame. The next frame
            // therefore belongs to the sentinel Content behind it in FIFO
            // order; an Input barrier would pull the value under test.
            barrier       <- next(run)
            screen        <- Sync.defer(run.canvas.read())
        yield
            assert(
              barrier.cause == FrameCause.Background && barrier.updates == 1 &&
                barrier.repaired == 0,
              barrier.toString,
            )
            assert(screen.contains("barrier after"), screen.text)
            assert(
              screen.contains("old") && !screen.contains("unobserved"),
              screen.text,
            )
      }
    }

    // codex 2026-10-23: created: fresh parent renders must retain this component's state and owned effects.
    private final class Counter(
        mounts: AtomicLong,
        ends: AtomicLong,
        mounted: Promise[Unit, Any],
        ended: Promise[Unit, Any],
    ) extends Component:
        type State = Int
        // codex 2026-10-23: created: each actual mount begins with zero.
        def initial: Int                                                   = 0
        // codex 2026-10-23: created: a nested reactive view with a state-changing callback.
        def view(state: SignalRef[Int]): UI                                =
          state.render(n =>
            button(s"count=$n")
              .id("counter")
              .focusAuto(true)
              .onClick(state.updateAndGet(_ + 1)),
          )
        // codex 2026-10-23: created: observe mount and scope release without timing sleeps.
        override def onMount(state: SignalRef[Int]): Any < (Async & Scope) =
          mounts.incrementAndGet
            .andThen(
              Scope.ensure(ends.incrementAndGet.andThen(ended.completeUnit)),
            )
            .andThen(mounted.completeUnit)
            .andThen(Async.never[Unit])
    end Counter

    // codex 2026-10-23: created: parent-first snapshots skip replaced descendants while keeping component identity and focus.
    "input verification retains component state, effects and focus without pulling fresh descendants twice"
      .timeout(10.seconds) in {
      Knobs.let(Knobs(repairInterval = 1.hour)) {
        for
            mounts    <- AtomicLong.init(0L)
            ends      <- AtomicLong.init(0L)
            mounted   <- Promise.init[Unit, Any]
            ended     <- Promise.init[Unit, Any]
            parent    <- Signal.initRef(0)
            h         <-
              Headless.start(60, 6, options)(_ =>
                parent.render(n =>
                  div(span(s"parent=$n"), Counter(mounts, ends, mounted, ended)),
                ),
              )
            _         <- mounted.get
            first     <- h.key(Keyboard.Enter)
            one       <- h.screen
            idle      <- h.char('x')
            kept      <- h.screen
            second    <- h.key(Keyboard.Enter)
            two       <- h.screen
            starts    <- mounts.get
            stops     <- ends.get
            _         <- h.quit
            quit      <- h.awaitEnd(2.seconds)
            _         <- ended.get
            finalEnds <- ends.get
        yield
            assert(
              one.contains("count=1") && kept.contains("count=1"),
              kept.text,
            )
            assert(two.contains("count=2"), two.text)
            assert(
              List(first, idle, second).forall(_.focus.contains("counter")),
            )
            assert(
              List(first, idle, second).forall(_.repaired == 1),
              s"$first $idle $second",
            )
            assert(starts == 1L && stops == 0L, s"mounts=$starts ends=$stops")
            assert(quit && finalEnds == 1L, s"quit=$quit ends=$finalEnds")
      }
    }

    // codex 2026-10-23: created: text updates must not retain removed components or skip mounting their replacements.
    "a reactive region can replace a component with text, update the text, and mount a fresh component"
      .timeout(10.seconds) in {
      Knobs.let(Knobs(repairInterval = 1.hour)) {
        for
            mounts       <- AtomicLong.init(0L)
            ends         <- AtomicLong.init(0L)
            firstMounted <- Promise.init[Unit, Any]
            firstEnded   <- Promise.init[Unit, Any]
            nextMounted  <- Promise.init[Unit, Any]
            nextEnded    <- Promise.init[Unit, Any]
            content      <- Signal.initRef[UI](
                              Counter(mounts, ends, firstMounted, firstEnded).ui,
                            )
            h            <- Headless.start(60, 6)(_ =>
                              div.onKeyDown {
                                case KeyboardEvent(Keyboard.Char('t'), _, _) =>
                                  content.set(UI.Ast.Text("bare one"))
                                case KeyboardEvent(Keyboard.Char('u'), _, _) =>
                                  content.set(UI.Ast.Text("bare two"))
                                case KeyboardEvent(Keyboard.Char('c'), _, _) =>
                                  content.set(
                                    Counter(mounts, ends, nextMounted, nextEnded).ui,
                                  )
                                case _                                       => ()
                              }(UI.Ast.Reactive[UI](content)),
                            )
            _            <- firstMounted.get
            _            <- h.key(Keyboard.Enter)
            before       <- h.screen
            removed      <- h.char('t')
            plain        <- h.screen
            _            <- firstEnded.get
            endedAtText  <- ends.get
            _            <- h.char('u')
            updated      <- h.screen
            restored     <- h.char('c')
            fresh        <- h.screen
            _            <- nextMounted.get
            clicked      <- h.key(Keyboard.Enter)
            after        <- h.screen
            starts       <- mounts.get
            stops        <- ends.get
            _            <- h.quit
            quit         <- h.awaitEnd(2.seconds)
            _            <- nextEnded.get
            finalEnds    <- ends.get
        yield
            assert(before.contains("count=1"), before.text)
            assert(
              plain.contains("bare one") && !plain.contains("count="),
              plain.text,
            )
            assert(removed.focus.isEmpty, removed.toString)
            assert(endedAtText == 1L, s"ends=$endedAtText")
            assert(
              updated.contains("bare two") && !updated.contains("bare one"),
              updated.text,
            )
            assert(
              fresh.contains("count=0") && !fresh.contains("bare"),
              fresh.text,
            )
            assert(after.contains("count=1"), after.text)
            assert(List(restored, clicked).forall(_.focus.contains("counter")))
            assert(starts == 2L && stops == 1L, s"mounts=$starts ends=$stops")
            assert(quit && finalEnds == 2L, s"quit=$quit ends=$finalEnds")
      }
    }
end SignalSettlementTest
