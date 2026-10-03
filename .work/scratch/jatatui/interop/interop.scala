//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using dep com.olvind.jatatui:crossterm:0.31.0
//> using options -deprecation -feature

// Scala 3.9 <-> jatatui interop checks. Compile + run (no native lib is loaded: CrosstermJni is never instantiated).
import jatatui.core.buffer.Buffer
import jatatui.core.layout.{Constraint, Layout, Rect}
import jatatui.core.style.{Color, Style}
import jatatui.core.terminal.Viewport
import jatatui.core.text.{Line, Span, Text}
import jatatui.widgets.list.{ListState, List as JList}
import jatatui.widgets.paragraph.Paragraph
import tui.crossterm.{Command, Event, KeyCode, KeyEvent, KeyEventKind, KeyEventState, KeyModifiers, MouseEventKind}

import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object Interop:
  // Exhaustive match over a Java sealed interface (type patterns; records have no unapply)
  def describe(e: Event): String = e match
    case k: Event.Key         => s"key ${k.keyEvent.code}"
    case m: Event.Mouse       => s"mouse ${m.mouseEvent.kind} at ${m.mouseEvent.column},${m.mouseEvent.row}"
    case p: Event.Paste       => s"paste ${p.string.length}"
    case r: Event.Resize      => s"resize ${r.columns}x${r.rows}"
    case _: Event.FocusGained => "focus"
    case _: Event.FocusLost   => "blur"

  // EXPECTED WARNING: not exhaustive (Java sealed interface)
  def incompleteSealed(e: Event): String = e match
    case _: Event.Key   => "k"
    case _: Event.Mouse => "m"

  // EXPECTED WARNING: not exhaustive (Java enum)
  def incompleteEnum(k: KeyEventKind): Int = k match
    case KeyEventKind.Press  => 1
    case KeyEventKind.Repeat => 2

  // EXPECTED WARNING: nested sealed interface (MouseEventKind) not exhaustive
  def incompleteNested(k: MouseEventKind): Int = k match
    case _: MouseEventKind.Down => 1
    case _: MouseEventKind.Up   => 2

  def isCtrl(m: KeyModifiers): Boolean = (m.bits & KeyModifiers.CONTROL) != 0

  def main(args: Array[String]): Unit =
    // records: universal apply (no `new`) and parameterless accessor calls (Java methods may omit `()`)
    val ev: Event = Event.Key(KeyEvent(KeyCode.Char('q'), KeyModifiers(KeyModifiers.CONTROL), KeyEventKind.Press, KeyEventState(0)))
    println(describe(ev) + " ctrl=" + (ev match { case k: Event.Key => isCtrl(k.keyEvent.modifiers); case _ => false }))
    println(describe(Event.Resize(120, 40)))
    // record equality is structural
    println(s"record equality: ${KeyCode.Char('q') == KeyCode.Char('q')} ${Color.Red() == Color.RED}")

    // Java varargs from a Scala Seq
    val cs: Seq[Constraint] = Seq(Constraint.Length(1), Constraint.Fill(1))
    val rects = Layout.vertical(cs*).split(Rect(0, 0, 10, 5))
    println(s"varargs split: ${rects.toList}")
    // overload: List<Constraint> vs Constraint...
    println(s"java.util.List overload: ${Layout.vertical(cs.asJava).split(Rect(0, 0, 10, 5)).length}")
    val cmds: Seq[Command] = Seq(Command.Hide(), Command.MoveTo(1, 2))
    val asJavaList: java.util.List[Command] = cmds.asJava
    println(s"commands: ${asJavaList}")
    val b = Buffer.withLines(Seq("ab", "cd")*)
    println(s"withLines from Seq: ${b.area}")

    // Optional<Integer>
    val st = ListState.of(0, Optional.of[Integer](3))
    val selected: Option[Int] = st.selected.toScala.map(_.intValue)
    st.select(Some(Integer.valueOf(4)).toJava)
    println(s"selected=$selected then ${st.selected}")
    val opt: Optional[Integer] = Option(5).map(Integer.valueOf).toJava
    println(s"opt=$opt")

    // Soft keyword `inline` used as a Java method name
    println(s"Viewport.inline(5) = ${Viewport.inline(5)}")

    // Name clashes handled with rename imports
    val list = JList.ofStrings("a", "b")
    println(s"JList len=${list.len}")

    // Overloads Paragraph.of(Text|String|Line|List<Line>)
    val p1 = Paragraph.of("x"); val p2 = Paragraph.of(Line.from("x")); val p3 = Paragraph.of(Text.from("x"))
    val p4 = Paragraph.of(java.util.List.of(Line.from("x")))
    println(s"Paragraph overloads equal: ${p1 == p2} ${p2 == p3} ${p3 == p4}")

    // Stylize helpers on Span/Line vs Style (record accessor clash)
    val sp = Span.raw("x").fg(Color.RED).bold()
    val style: Style = Style.empty().withFg(Color.RED).bold()
    println(s"span style == style: ${sp.style == style}; Style.fg accessor = ${style.fg}")
