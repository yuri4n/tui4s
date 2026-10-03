//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
import jatatui.widgets.list.ListState
import java.util.Optional
object Neg2:
  val ok1 = ListState.of(0, Optional.of(3))       // compiles? expected type drives Optional.of[Integer]
  val o = Optional.of(3)                          // Optional[Int]
  val bad = ListState.of(0, o)                    // NEG: Optional[Int] vs Optional[Integer]
  val d: Int = ListState.empty().selected.get     // Integer -> Int unboxing
