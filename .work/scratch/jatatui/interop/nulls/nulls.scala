//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using dep com.olvind.jatatui:crossterm:0.31.0
//> using options -Yexplicit-nulls -Wsafe-init

// How painful is -Yexplicit-nulls against jatatui? Typical wrapper code, no `.nn` anywhere.
import jatatui.core.backend.{Backend, ClearType, WindowSize}
import jatatui.core.buffer.{Buffer, BufferUpdate, Cell}
import jatatui.core.layout.{Constraint, Layout, Position, Rect, Size}
import jatatui.core.style.{Color, Modifier, Style}
import jatatui.core.terminal.{Frame, Terminal}
import jatatui.core.text.{Line, Span, Text}
import jatatui.core.widgets.Widget
import jatatui.widgets.block.Block
import jatatui.widgets.list.{ListItem, ListState, List as JList}
import jatatui.widgets.paragraph.Paragraph
import tui.crossterm.{Event, KeyCode}
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

final class RecordingBackend(w: Int, h: Int) extends Backend:
  val buf: Buffer = Buffer.empty(Rect(0, 0, w, h))
  def draw(content: java.lang.Iterable[BufferUpdate]): Unit =
    content.forEach(u => buf.cellAt(u.x, u.y).setSymbol(u.cell.symbol))
  def hideCursor(): Unit = ()
  def showCursor(): Unit = ()
  def getCursorPosition(): Position = Position.ORIGIN
  def setCursorPosition(position: Position): Unit = ()
  def clear(): Unit = buf.reset()
  def clearRegion(clearType: ClearType): Unit = ()
  def size(): Size = Size(w, h)
  def windowSize(): WindowSize = WindowSize(Size(w, h), Size(0, 0))
  def flush(): Unit = ()
  def scrollRegionUp(regionStart: Int, regionEnd: Int, lineCount: Int): Unit = ()
  def scrollRegionDown(regionStart: Int, regionEnd: Int, lineCount: Int): Unit = ()

object Nulls:
  def keyChar(e: Event): Option[Char] = e match
    case k: Event.Key => k.keyEvent.code match
      case c: KeyCode.Char => Some(c.c)
      case _               => None
    case _ => None

  def rowText(buf: Buffer, y: Int): String =
    val a: Rect = buf.area                                       // flexible type Rect? -> Rect
    (0 until a.width).map(x => buf.cellAt(x, y).symbol).mkString // String? used as String

  def main(args: Array[String]): Unit =
    val be = RecordingBackend(20, 4)
    val term: Terminal[RecordingBackend] = Terminal.create(be)
    val st = ListState.empty()
    st.select(Optional.of[Integer](1))
    term.draw { (f: Frame) =>
      val Array(a, b) = Layout.vertical(Constraint.Length(1), Constraint.Fill(1)).areas(f.area, 2): @unchecked
      f.renderWidget(Paragraph.of(Line.from(Span.raw("hi"), Span.styled("!", Style.empty().withFg(Color.RED)))), a)
      val items: java.util.List[ListItem] = List("x", "y").map(ListItem.of).asJava
      f.renderStatefulWidget(JList.of(items).withHighlightSymbol(">"), b, st)
      val w: Widget = (r, bb) => bb.setString(r.x, r.y, "*", Style.empty())
      f.renderWidget(w, f.area)
    }
    val lines: java.util.List[Line] = Text.from("a\nb").lines
    val first: Line = lines.get(0)                               // Line? -> Line
    val content: String = first.spans.get(0).content            // public final field, String?
    val sel: Option[Int] = st.selected.toScala.map(_.intValue)
    val cell: Cell = be.buf.cellAt(0, 0)
    val maybe: Optional[Cell] = be.buf.cell(99, 99)
    println(s"${rowText(be.buf, 0)}|${rowText(be.buf, 2)}|$content|$sel|${cell.symbol}|${maybe.isPresent}|${keyChar(Event.Paste("x"))}")
    val fg: Color = cell.fg                                       // public mutable field
    val m: Modifier = cell.modifier
    val title: String | Null = System.getenv("NOPE_NOT_SET")      // genuinely nullable Java return
    println(s"$fg $m ${title == null}")
