package tui4s

import cats.effect.Async
import cats.syntax.functor.*
import tui.crossterm.Command
import tui.{Cell, Color, CrosstermBackend, Modifier, Rect, crossterm}

import java.util
import scala.jdk.CollectionConverters.*

final case class CrosstermBackendF[F[_]](private val buffer: CrosstermJniF[F])(
    using F: Async[F],
) extends BackendF[F]:

  /** Side-effecting - flushes commands to terminal */
  def flush: F[Unit] =
    buffer.flush

  private def draw_(content: Array[(Int, Int, Cell)]) =
    var fg: Color                    = Color.Reset
    var bg: Color                    = Color.Reset
    var modifier                     = Modifier.EMPTY
    var last_pos: Option[(Int, Int)] = None
    val commands                     = new util.ArrayList[Command]()

    content.foreach { case (x, y, cell) =>
      // Move the cursor if the previous location was not (x - 1, y)
      def shouldMove = last_pos match
        case Some((lastX, lastY)) if x == lastX + 1 && y == lastY => false
        case _                                                    => true
      if shouldMove then commands.add(new Command.MoveTo(x, y))
      last_pos = Some((x, y))

      if cell.modifier != modifier then
        val diff =
          CrosstermBackend.ModifierDiff(from = modifier, to = cell.modifier)
        diff.queue(commands)
        modifier = cell.modifier
      if cell.fg != fg then
        val color = CrosstermBackend.from(cell.fg)
        commands.add(new Command.SetForegroundColor(color))
        fg = cell.fg
      if cell.bg != bg then
        val color = CrosstermBackend.from(cell.bg)
        commands.add(new Command.SetBackgroundColor(color))
        bg = cell.bg
      commands.add(new Command.Print(cell.symbol.str))
    }
    commands.add(new Command.SetForegroundColor(new crossterm.Color.Reset()))
    commands.add(new Command.SetBackgroundColor(new crossterm.Color.Reset()))
    commands.add(new Command.SetAttribute(crossterm.Attribute.Reset))

    commands
  end draw_

  /** Side-effecting - draws content to terminal */
  def draw(content: Array[(Int, Int, Cell)]): F[Unit] =
    buffer.execute(draw_(content).asScala.toList)

  /** Side-effecting - writes cursor hide command to terminal */
  def hideCursor: F[Unit] =
    buffer.execute(new Command.Hide())

  /** Side-effecting - writes cursor show command to terminal */
  def showCursor: F[Unit] =
    buffer.execute(new Command.Show())

  /** Potentially blocking - queries terminal for cursor position */
  def getCursor: F[(Int, Int)] =
    buffer.cursorPosition.map(xy => (xy.x, xy.y))

  /** Side-effecting - writes cursor position command to terminal */
  def setCursor(x: Int, y: Int): F[Unit] =
    buffer.execute(new Command.MoveTo(x, y))

  /** Side-effecting - writes clear command to terminal */
  def clear: F[Unit] =
    buffer.execute(new Command.Clear(crossterm.ClearType.All))

  /** Potentially blocking - queries terminal for size */
  def size: F[Rect] =
    buffer.terminalSize.map(xy => Rect(0, 0, xy.x, xy.y))
end CrosstermBackendF
