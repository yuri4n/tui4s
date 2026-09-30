package tui4s

import cats.NonEmptyParallel
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.concurrent.Signal
import tui.crossterm
import tui.crossterm.{Command, CrosstermJni}
import tui4s.FramesPerUnit.fps
import tui4s.interfaces.Widget

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

// // list widget -> uses a user-defined state and a buffer to draw something to the buffer.
// // each frame should be resized before printing it
// // how to specify where and what?

type TerminalResources[F[_]] = (
    jniF: CrosstermJniF[F],
    backend: CrosstermBackendF[F],
    terminal: TerminalF[F],
)

type StartedTerminalResources[F[_]] = (
    terminalResources: TerminalResources[F],
    eventSignal: Signal[F, Option[crossterm.Event]],
)

type FramesPerUnit = (frames: Int, per: TimeUnit)

object FramesPerUnit:

    def fromFps(fps: Int): FramesPerUnit =
      (fps, TimeUnit.SECONDS)

    extension (fpu: FramesPerUnit)
      def toDuration: FiniteDuration =
          val (frames, per) = fpu
          FiniteDuration(1, per) / frames

    extension (fps: Int) def fps: FramesPerUnit = FramesPerUnit.fromFps(fps)
end FramesPerUnit

/** Resource for building and working with the [[TerminalF]]. */
object withTerminal:

    // private def renderUi[F[_]: Async: NonEmptyParallel: Temporal](
    //     app: Widget[F],
    //     terminal: TerminalF[F],
    //     eventSignal: Signal[F, Option[crossterm.Event]],
    //     frameRate: FramesPerUnit,
    // ): Resource[F, Unit] =
    //   Stream
    //     .repeatEval(terminal.draw(app, eventSignal))
    //     .meteredStartImmediately(frameRate.toDuration)
    //     .compile
    //     .drain
    //     .background
    //     .void

    def apply[F[_]: NonEmptyParallel](
        app: Widget[F],
        frameRate: FramesPerUnit = 60.fps,
    )(using
        F: Async[F],
    ): Resource[
      F,
      (TerminalResources[F], Signal[F, Option[CompletedFrameF[F]]]),
    ] =
      Resource
        .make(
          for
              jni      <- F.delay(new CrosstermJni())
              jniF      = CrosstermJniF[F](jni)
              _        <- jniF.enableRawMode
              _        <- jniF.execute(
                            new Command.EnterAlternateScreen(),
                            new Command.EnableMouseCapture(),
                          )
              backend   = CrosstermBackendF[F](jniF)
              terminal <- TerminalF.init[F](backend)
          yield (jniF, backend, terminal),
        ) { case (jniF, backend, _) =>
          for
              _ <- jniF.disableRawMode
              _ <- jniF.execute(
                     new Command.LeaveAlternateScreen(),
                     new Command.DisableMouseCapture(),
                   )
              _ <- backend.showCursor
          yield ()
        }
        .flatMap { case t @ (jniF, _, terminal) =>
          for completedFrameSignal <-
                jniF
                  .events()
                  .evalMap(e => terminal.draw(app, e))
                  .holdOptionResource
              /* _ <- renderUi(app, terminal, eventSignal, frameRate) /* Start
               * background */
          //  * rendering */
          yield (t, completedFrameSignal)
        }

    /** Simplified version that only returns the terminal for basic usage */
    def terminal[F[_]: Async: NonEmptyParallel](
        app: Widget[F],
        frameRate: FramesPerUnit = 60.fps,
    ): Resource[F, TerminalF[F]] =
      apply[F](app, frameRate).map(_._1.terminal)
end withTerminal
