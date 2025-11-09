package tui4s

import tui.{Cell, Rect}

trait BackendF[F[_]]:
  def draw(content: Array[(Int, Int, Cell)]): F[Unit]
  def hideCursor: F[Unit]
  def showCursor: F[Unit]
  def getCursor: F[(Int, Int)]
  def setCursor(x: Int, y: Int): F[Unit]
  def clear: F[Unit]
  def size: F[Rect]
  def flush: F[Unit]
end BackendF
