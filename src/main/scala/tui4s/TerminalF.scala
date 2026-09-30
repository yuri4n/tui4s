package tui4s

import cats.effect.std.Console
import cats.effect.{Async, Ref, Sync}
import cats.syntax.all.given
import cats.{Functor, NonEmptyParallel}
import tui.{Rect, ResizeBehavior, TerminalOptions, Viewport, crossterm}
import tui4s.interfaces.Widget

import scala.util.control.NonFatal

final case class TerminalF[F[_]](
    private val backend: BackendF[F],
    private val buffers: Array[BufferF[F]],
    private val currentRef: Ref[F, Int],
    private val isCursorHiddenRef: Ref[F, Boolean],
    private val viewportRef: Ref[F, Viewport],
):

  def drop(using F: Sync[F], console: Console[F]): F[Unit] =
    (isCursorHiddenRef.get).flatMap(F.whenA(_)(showCursor.recoverWith:
      case NonFatal(e) =>
        console.errorln(s"Failed to show the cursor: ${e.getMessage}"),
    ))

  def getFrame(using
      Functor[F],
      NonEmptyParallel[F],
      Ref.Make[F],
  ): F[FrameF[F]] =
    (currentBufferMut, viewportRef.get).parFlatMapN:
      (currentBuffer, viewport) =>
        FrameF.of(
          buffer = currentBuffer,
          size = viewport.area,
          cursorPosition = None,
        )

  def currentBufferMut(using Functor[F]): F[BufferF[F]] =
    currentRef.get.map(buffers(_))

  def flush(using F: Sync[F]): F[Unit] =
    currentRef.get
      .map: current =>
        val previous_buffer = buffers(1 - current)
        val current_buffer  = buffers(current)
        previous_buffer.diff(current_buffer)
      .flatMap(backend.draw)
      .void

  /** Side-effecting - modifies buffers and calls clear() */
  def resize(area: Rect)(using F: Sync[F]): F[Unit] =
    for
      current <- currentRef.get
      _       <- buffers(current).resize(area)
      _       <- buffers(1 - current).resize(area)
      _       <- viewportRef.update(_.copy(area = area))
      _       <- clear
    yield ()

  /** Side-effecting - may call resize if needed */
  def autoresize(using F: Sync[F]): F[Unit] =
    viewportRef.get.flatMap: viewport =>
      F.whenA(viewport.resizeBehavior == ResizeBehavior.Auto):
        for
          size <- getSize
          _    <- F.whenA(size != viewport.area)(resize(size))
        yield ()

  /** Side-effecting - complex drawing operation with multiple terminal writes
    */

  def draw(
      widget: Widget[F],
      lastEvent: crossterm.Event,
  )(using
      F: Sync[F],
      N: NonEmptyParallel[F],
  ): F[CompletedFrameF[F]] =
    for
      _              <- autoresize
      frame          <- getFrame
      viewport       <- viewportRef.get
      _              <- frame.renderWidget(widget, viewport.area, lastEvent)
      cursorPosition <- frame.getCursorPostion.value
      _              <- flush
      _              <- cursorPosition match
                          case Some((x, y)) => showCursor >> setCursor(x, y)
                          case None         => hideCursor
      current        <- currentRef.get
      _              <- buffers(1 - current).reset
      _              <- currentRef.set(1 - current)
      _              <- backend.flush
    yield CompletedFrameF(
      buffer = buffers(1 - current),
      area = viewport.area,
    )
  //   F.delay(terminal.draw(f))

  /** Side-effecting - writes cursor hide command to terminal */
  def hideCursor(using F: Sync[F]): F[Unit] =
    backend.hideCursor >> isCursorHiddenRef.set(true)
  //   F.delay(terminal.hideCursor())

  /** Side-effecting - writes cursor show command to terminal */
  def showCursor(using F: Sync[F]): F[Unit] =
    backend.showCursor >> isCursorHiddenRef.set(false)

  /** Potentially blocking - queries terminal for cursor position */
  def getCursor: F[(Int, Int)] =
    backend.getCursor

  /** Side-effecting - writes cursor position command to terminal */
  def setCursor(x: Int, y: Int): F[Unit] =
    backend.setCursor(x, y)
  //   F.delay(terminal.setCursor(x, y))

  /** Side-effecting - writes clear command and resets buffers */
  def clear(using F: Sync[F]): F[Unit] =
    backend.clear *> currentRef.get.flatMap(current =>
      buffers(1 - current).reset,
    )
  //   F.delay(terminal.clear())

  /** Potentially blocking - queries terminal backend for size */
  def getSize: F[Rect] =
    backend.size
  //   F.blocking(terminal.size())
end TerminalF

object TerminalF:

  /** Create a TerminalF from a backend - potentially blocking during
    * initialization
    */
  def init[F[_]: Async: Ref.Make: NonEmptyParallel](
      backend: BackendF[F],
  ): F[TerminalF[F]] =
    backend.size.flatMap: size =>
      withOptions(
        backend,
        TerminalOptions(
          viewport = Viewport(
            area = size,
            resizeBehavior = ResizeBehavior.Auto,
          ),
        ),
      )
  end init

  def withOptions[F[_]: Async: Ref.Make: NonEmptyParallel](
      backend: BackendF[F],
      options: TerminalOptions,
  ): F[TerminalF[F]] =
    val getEmptyBuffer = BufferF.empty(options.viewport.area)

    (
      getEmptyBuffer,
      getEmptyBuffer,
      Ref.of(0),
      Ref.of(false),
      Ref.of(options.viewport),
    ).parMapN: (buf1, buf2, currentRef, isCursorHiddenRef, viewportRef) =>
      TerminalF(
        backend = backend,
        buffers = Array(buf1, buf2),
        currentRef = currentRef,
        isCursorHiddenRef = isCursorHiddenRef,
        viewportRef = viewportRef,
      )
  end withOptions

//   Async[F].blocking(Terminal.init(backend)).map(TerminalF(_))

/** Create a TerminalF from a BackendF - uses the underlying backend */
// def fromBackendF[F[_]: Async](
//     backendF: CrosstermBackendF[F],
// ): F[TerminalF[F]] =
//   init(backendF.backend)

/** Create a TerminalF with options - potentially blocking during initialization
  */
// def withOptions[F[_]: Async](
//     backend: tui.Backend,
//     options: TerminalOptions,
// ): F[TerminalF[F]] =
//   Async[F].blocking(Terminal.withOptions(backend, options)).map(TerminalF(_))
end TerminalF
