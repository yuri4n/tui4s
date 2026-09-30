package tui4s

import cats.data.OptionT
import cats.effect.Ref
import cats.syntax.all.given
import cats.{Applicative, Functor, Monad}
import tui.{Rect, crossterm}
import tui4s.interfaces.Widget

final case class FrameF[F[_]](
    buffer: BufferF[F],
    size: Rect,
    cursorPositionRef: Ref[F, Option[(Int, Int)]],
):
  def renderWidget(
      widget: Widget[F],
      area: Rect,
      lastEvent: crossterm.Event,
  )(using Monad[F]): F[Unit] =
    widget.createElement.flatMap(_.render(buffer, area,   lastEvent))

  def setCursorPosition(x: Int, y: Int)(using
      Applicative[F],
  ): F[Unit] =
    cursorPositionRef.set(Some((x, y))).void

  def getCursorPostion: OptionT[F, (Int, Int)] =
    OptionT(cursorPositionRef.get)
end FrameF

object FrameF:
  def of[F[_]: {Ref.Make, Functor}](
      buffer: BufferF[F],
      size: Rect,
      cursorPosition: Option[(Int, Int)] = None,
  ): F[FrameF[F]] =
    Ref
      .of[F, Option[(Int, Int)]](cursorPosition)
      .map: cursorPositionRef =>
        FrameF(
          buffer = buffer,
          size = size,
          cursorPositionRef = cursorPositionRef,
        )
end FrameF
