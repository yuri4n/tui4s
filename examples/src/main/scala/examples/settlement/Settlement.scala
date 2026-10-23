package examples.settlement

import kyo.*
import kyo.UI.*
import tui4s.TuiApp

import scala.annotation.tailrec

/** A small input beside independent scheduler work and status updates. */
// codex 2026-10-22: created: the settlement reproduction app.
object Settlement:

    // codex 2026-10-22: created: distinguish unrelated work from the quiet baseline.
    enum Workload derives CanEqual:
        case Quiet, Busy

    // codex 2026-10-22: created: the visible lifetime of the load.
    enum Load derives CanEqual:
        case Running, Done

    /** CLI values are validated before workers start. */
    // codex 2026-10-22: created: bounded workload configuration.
    final class Settings private (
        val workload: Workload,
        val updatesPerSecond: Int,
        val seconds: Int,
    ):
        // codex 2026-10-22: created: identify whether a background worker is needed.
        def active: Boolean = workload == Workload.Busy || updatesPerSecond > 0

    // codex 2026-10-22: created: parse the reproducible workload flags.
    object Settings:
        // codex 2026-10-22: created: a quiet default with a ten-second load duration.
        val default: Settings = new Settings(Workload.Quiet, 0, 10)

        // codex 2026-10-22: created: reject invalid rates, durations and unknown flags.
        def parse(args: Vector[String]): Either[String, Settings] =
          args
            .foldLeft[Either[String, Settings]](Right(default)) { (out, arg) =>
              out.flatMap { previous =>
                if arg == "--busy" then
                    Right(
                      new Settings(
                        Workload.Busy,
                        previous.updatesPerSecond,
                        previous.seconds,
                      ),
                    )
                else if arg.startsWith("--updates=") then
                    number(
                      arg.drop("--updates=".length),
                      0,
                      100000,
                      "--updates",
                    )
                      .map(n =>
                        new Settings(previous.workload, n, previous.seconds),
                      )
                else if arg.startsWith("--duration=") then
                    number(
                      arg.drop("--duration=".length),
                      1,
                      3600,
                      "--duration",
                    )
                      .map(n =>
                        new Settings(
                          previous.workload,
                          previous.updatesPerSecond,
                          n,
                        ),
                      )
                else Left(s"unknown settlement option: $arg")
              }
            }

        // codex 2026-10-22: created: the CLI integer boundary.
        private def number(
            value: String,
            min: Int,
            max: Int,
            flag: String,
        ): Either[String, Int] =
          value.toIntOption
            .filter(n => n >= min && n <= max)
            .toRight(s"$flag must be an integer from $min to $max")
    end Settings

    // codex 2026-10-22: created: input and status state; work is not a UI signal.
    final case class State(
        text: SignalRef[String],
        updates: SignalRef[Long],
        load: SignalRef[Load],
        work: AtomicLong,
    )

    // codex 2026-10-22: created: state belongs to this app instance.
    def init(settings: Settings)(using Frame): State < Sync =
      for
          text    <- Signal.initRef("")
          updates <- Signal.initRef(0L)
          load    <-
            Signal.initRef(if settings.active then Load.Running else Load.Done)
          work    <- AtomicLong.init(0L)
      yield State(text, updates, load, work)

    /** The PTY observer reads the actual input, not a separate echo field. */
    // codex 2026-10-22: created: stable screen rows for the external observer.
    def view(state: State): UI =
      div(
        span("Settlement probe"),
        div.style(_.row)(
          span("Input: "),
          input
            .value(state.text)
            .id("prompt")
            .focusAuto(true)
            .style(_.width(100.px)),
        ),
        span(state.updates.map(n => s"Updates: $n")),
        span(state.load.map {
          case Load.Running => "Load: running"
          case Load.Done    => "Load: done"
        }),
        span(
          "Ctrl-C quits. The load stops at its deadline; the input stays open.",
        ),
      )

    /** Finite CPU work. Its next seed is retained outside the UI. */
    // codex 2026-10-22: created: bounded work between cooperative boundaries.
    private def calculate(seed: Long): Long =
        // codex 2026-10-22: created: avoid allocating a collection or boxed value per operation.
        @tailrec def loop(remaining: Int, value: Long): Long =
          if remaining == 0 then value
          else
              loop(
                remaining - 1,
                (value ^ remaining.toLong) * 6364136223846793005L + 1442695040888963407L,
              )
        loop(10000, seed)
    end calculate

    // codex 2026-10-22: created: fix the effect types of the bracketed scheduler hop.
    private def hop(using Frame): Unit < Async =
      Fiber.use[Nothing, Unit, Any, Any](Kyo.unit) { fiber =>
        fiber.get
      }

    /** A scheduled round trip lets a single-worker runtime serve other work. */
    // codex 2026-10-22: created: busy load with a stop path and no unowned fiber.
    private def busy(stop: AtomicBoolean, work: AtomicLong)(using
        Frame,
    ): Unit < Async =
      Loop(1L) { seed =>
        stop.get.map { done =>
          if done then Loop.done(())
          else
              Sync.defer(calculate(seed)).map { next =>
                work
                  .set(next)
                  .andThen(hop)
                  .map(_ => Loop.continue(next))
              }
        }
      }

    /** The rate is a requested ceiling; scheduling can reduce the actual rate. */
    // codex 2026-10-22: created: finite status traffic with a measured update count.
    private def notifications(
        stop: AtomicBoolean,
        updates: SignalRef[Long],
        perSecond: Int,
    )(using Frame): Unit < Async =
      Loop.foreach {
        stop.get.map { done =>
          if done then Loop.done
          else
              updates
                .updateAndGet(_ + 1)
                .andThen(
                  Async.sleep(Duration.fromNanos(1000000000L / perSecond)),
                )
                .andThen(Loop.continue)
        }
      }

    /** Both workers start before the UI is returned for its first mount. */
    // codex 2026-10-22: created: scoped workers, a relative stop timer, startup acknowledgement and a package test seam.
    private[settlement] def start(state: State, settings: Settings)(using
        Frame,
    ): Unit < (Async & Scope) =
      for
          stop           <- AtomicBoolean.init(false)
          busyStarted    <- Promise.init[Unit, Any]
          updatesStarted <- Promise.init[Unit, Any]
          _              <- Fiber.init {
                              for
                                  timer <- Fiber.init(
                                             Clock
                                               .let(Clock.live)(
                                                 Async.sleep(settings.seconds.seconds),
                                               )
                                               .andThen(stop.set(true)),
                                           )
                                  b     <- Fiber.init(
                                             busyStarted.completeUnit.andThen(
                                               if settings.workload == Workload.Busy then
                                                   busy(stop, state.work)
                                               else Kyo.unit,
                                             ),
                                           )
                                  u     <- Fiber.init(
                                             updatesStarted.completeUnit.andThen(
                                               if settings.updatesPerSecond > 0 then
                                                   notifications(
                                                     stop,
                                                     state.updates,
                                                     settings.updatesPerSecond,
                                                   )
                                               else Kyo.unit,
                                             ),
                                           )
                                  _     <- b.get
                                  _     <- u.get
                                  _     <- timer.get
                                  _     <- state.load.set(Load.Done)
                              yield ()
                            }
          _              <- busyStarted.get
          _              <- updatesStarted.get
      yield ()

    // codex 2026-10-22: created: no worker or timer in the quiet baseline.
    def app(settings: Settings)(using Frame): UI < (Async & Scope) =
      for
          state <- init(settings)
          _     <- if settings.active then start(state, settings) else Kyo.unit
      yield view(state)
end Settlement

/** Start through the generated `settlement` launcher in the PTY kit. */
// codex 2026-10-22: created: command-line entry point for the reproduction.
object SettlementApp extends TuiApp:
    // codex 2026-10-22: created: invalid configuration fails before starting load.
    def ui: UI < (Async & Scope) =
      Settlement.Settings.parse(args.toVector) match
          case Right(settings) => Settlement.app(settings)
          case Left(error)     => Abort.panic(IllegalArgumentException(error))
