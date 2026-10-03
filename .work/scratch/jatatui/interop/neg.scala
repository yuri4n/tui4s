//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using dep com.olvind.jatatui:crossterm:0.31.0

// Each line marked NEG is expected NOT to compile.
import jatatui.core.style.{Color, Style}
import jatatui.widgets.list.ListState
import tui.crossterm.Event
import java.util.Optional

object Neg:
  def a(e: Event): Int = e match
    case Event.Resize(c, r) => c + r                        // NEG 1: Java record has no unapply
    case _ => 0
  val b = ListState.of(0, Optional.of(3))                     // NEG 2: Optional[Int] is not Optional[Integer]
  val c = Style.empty().fg(Color.RED)                         // NEG 3: Style.fg() is the record accessor
  val d: Int = ListState.empty().selected.get                 // NEG 4?: Integer -> Int (may compile via unboxing conversion)
