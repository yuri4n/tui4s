package tui4s

import cats.effect.Sync
import tui.{Buffer, Spans, Style}
import tui.Rect

// TODO: this should have a mutex for secure read write.
final case class BufferF[F[_]] private (private val buffer: Buffer):

  def set(x: Int, y: Int, cell: tui.Cell)(using Sync[F]): F[Unit] =
    Sync[F].delay:
      buffer.set(x, y, cell)

  def setString(x: Int, y: Int, string: String, style: Style)(using
      Sync[F],
  ): F[(Int, Int)] =
    Sync[F].delay:
      buffer.setString(x, y, string, style)

  def setString(
      x: Int,
      y: Int,
      string: String,
      width: Int,
      style: Style,
  )(using Sync[F]): F[(Int, Int)] =
    Sync[F].delay:
      buffer.setStringn(x, y, string, width, style)

  def setCell(x: Int, y: Int, cell: tui.Cell)(using Sync[F]): F[Unit] =
    Sync[F].delay:
      buffer.set(x, y, cell)

  def setStyle(area: tui.Rect, style: Style)(using Sync[F]): F[Unit] =
    Sync[F].delay:
      buffer.setStyle(area, style)

  def setSpans(x: Int, y: Int, spans: Spans, maxWidth: Int)(using
      Sync[F],
  ): F[(Int, Int)] =
    Sync[F].delay:
      buffer.setSpans(x, y, spans, maxWidth)

  def diff(other: BufferF[F]): Array[(Int, Int, tui.Cell)] =
    buffer.diff(other.buffer)

  def resize(area: tui.Rect)(using Sync[F]): F[Unit] =
    Sync[F].delay:
      buffer.resize(area)

  def reset(using Sync[F]): F[Unit] =
    Sync[F].delay:
      buffer.reset()
end BufferF

object BufferF:

  def empty[F[_]](area: Rect)(using Sync[F]): F[BufferF[F]] =
    Sync[F].delay:
      BufferF(Buffer.empty(area))
