//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using options -Yexplicit-nulls
import jatatui.core.layout.{Constraint, Layout, Rect}
import jatatui.core.text.Line
object Nulls2:
  val proof: String = null                                   // must FAIL under explicit nulls
  val arr: Array[Rect] = Layout.vertical(Constraint.Fill(1)).split(Rect(0, 0, 1, 1))   // Java Rect[]
  val l: Line = Line.from("x")
  val sym: String = l.spans.get(0).content
  def f(x: String | Null): Int = x.length                    // must FAIL: no member on String | Null
  val ok: Int = Option(System.getenv("X")).map(_.length).getOrElse(0)
  val strict: String = System.getenv("X")                    // flexible type: compiles, may be null at runtime
