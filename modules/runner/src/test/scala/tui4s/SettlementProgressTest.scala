package tui4s

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.{Canvas, Knobs, Loc, Msg, RunOptions, Runner}
import tui4s.spi.Settle

import java.time.Duration as JDuration

/** Runtime progress while every notification causes another notification. */
// codex 2026-10-22: created: deterministic inbox pressure through the public raw-signal API.
class SettlementProgressTest extends Test[Any]:
    // codex 2026-10-22: created: scheduler-sensitive runner cases run separately.
    override def config: RunConfig = super.config.sequential

    // codex 2026-10-22: created: resources and observations of one test run.
    private final case class Running(
        runner: Runner,
        inbox: Channel[Msg],
        frames: Channel[FrameStats],
        canvas: Canvas,
        reads: AtomicLong,
        text: SignalRef[String],
        fiber: Fiber[Unit, Scope],
    )

    /** An Armed message re-reads the root and queues its next Armed message.
      * The inbox therefore stays active without a producer scheduling race. The
      * root is the first region, so its generation is 1.
      */
    // codex 2026-10-22: created: a stable UI with a self-sustaining notification source.
    private def start(
        strategy: Settle,
    )(using Frame): Running < (Async & Scope) =
      for
          inbox  <- Channel.initUnscoped[Msg](32)
          frames <- Channel.initUnscoped[FrameStats](32)
          reads  <- AtomicLong.init(0L)
          text   <- Signal.initRef("")
          canvas <- Sync.defer(Canvas(TestBackend(40, 4)))
          _      <- Scope.ensure(Sync.defer(canvas.close()))
          ui      = div(input.value(text).id("prompt").focusAuto(true))
          signal  = Signal.initRaw[UI](
                      [B, S] =>
                        (f: UI => B < S) =>
                          reads.incrementAndGet
                            .andThen(
                              Abort.run[Closed](
                                inbox.offer(Msg.Armed(Loc.root, 1L)),
                              ),
                            )
                            .andThen(f(ui)),
                      [B, S] => (f: UI => B < S) => Async.never[UI].map(f),
                    )
          runner <- Runner.init(
                      inbox,
                      canvas,
                      RunOptions.of(TuiOptions(settle = strategy)),
                      Present(frames),
                      Knobs(repairInterval = 1.hour),
                    )
          fiber  <- Fiber.init(runner.run(UI.Ast.Reactive[UI](signal)))
          run     = Running(runner, inbox, frames, canvas, reads, text, fiber)
          _      <- Scope.ensure(run.runner.quit.andThen(ended(run)).unit)
      yield run

    // codex 2026-10-22: created: wait for a frame while retaining its actual sequence number.
    private def nextFrame(run: Running)(using Frame): FrameStats < Async =
      Abort.run[Closed](run.frames.take).map {
        case Result.Success(frame) => frame
        case _                     => Abort.panic(IllegalStateException("frame channel closed"))
      }

    // codex 2026-10-22: created: poll completion without making interruption join the runner.
    private def ended(run: Running)(using Frame): Boolean < Async =
      Clock.let(Clock.live) {
        Abort.recover[Timeout](_ => false)(
          Async.timeout(2.seconds)(
            Loop.foreach {
              run.fiber.done.map { done =>
                if done then Loop.done(true)
                else Async.sleep(1.millis).andThen(Loop.continue)
              }
            },
          ),
        )
      }

    /** The watchdog only detects a hang. It is not a latency benchmark. */
    // codex 2026-10-22: created: the first frame and queued input both survive a permanently active inbox.
    private def framesUnderPressure(
        strategy: Settle,
    )(using Frame, AssertScope): Unit < (Async & Scope) =
      Scope.run {
        for
            run     <- start(strategy)
            first   <- nextFrame(run)
            before  <- run.reads.get
            _       <-
              Abort.run[Closed](
                run.inbox.put(
                  Msg.Key(
                    KeyboardEvent(Keyboard.Char('x'), Modifiers.none, Absent),
                    1L,
                  ),
                ),
              )
            input   <- Loop.foreach {
                         nextFrame(run).map { frame =>
                           if frame.seq == 1L then Loop.done(frame)
                           else Loop.continue
                         }
                       }
            value   <- run.text.get
            screen  <- Sync.defer(run.canvas.read())
            after   <- run.reads.get
            _       <- run.runner.quit
            stopped <- ended(run)
        yield
            assert(first.cause == FrameCause.Initial)
            assert(first.settleTimeouts == 1, s"first frame: $first")
            assert(input.cause == FrameCause.Input && input.seq == 1L)
            assert(input.settleTimeouts == 1, s"input frame: $input")
            assert(value == "x" && screen.contains("x"), screen.text)
            assert(
              before > 1 && after > before,
              s"notification reads: $before -> $after",
            )
            assert(
              stopped,
              "quit must end the run while notifications continue",
            )
      }

    // codex 2026-10-22: created: the default maximum applies after nonempty batches.
    "default settlement displays input while notifications never stop"
      .timeout(10.seconds) in {
      framesUnderPressure(TuiOptions().settle)
    }

    // codex 2026-10-22: created: the legacy scheduler policy also honors its maximum under traffic.
    "explicit quiescent settlement honors its maximum with a nonempty inbox"
      .timeout(10.seconds) in {
      framesUnderPressure(Settle.Quiescent(JDuration.ofMillis(20)))
    }

    // codex 2026-10-22: created: quit interrupts an opt-in unbounded quiet-window policy too.
    "quit ends a Hops settlement before its notification source becomes quiet"
      .timeout(10.seconds) in {
      Scope.run {
        for
            run     <- start(Settle.Hops(2, JDuration.ofSeconds(30)))
            _       <- Loop.foreach {
                         run.reads.get.map { count =>
                           if count >= 10L then Loop.done
                           else Async.sleep(1.millis).andThen(Loop.continue)
                         }
                       }
            _       <- run.runner.quit
            stopped <- ended(run)
        yield assert(stopped, "quit must not wait for the Hops quiet window")
      }
    }
end SettlementProgressTest
