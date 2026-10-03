package tui4s.testkit

import jatatui.core.backend.TestBackend
import jatatui.core.terminal.Terminal
import jatatui.core.text.Span as JSpan
import kyo.*
import tui4s.*
import tui4s.internal.*

/** A headless driver. The same fold, frame rules and painter as the runtime, synchronously on the caller's thread,
  * on a jatatui TestBackend. No fibers: sources and tasks are recorded, not run (use emit / fail). The same API for
  * Kyo, ZIO and Cats users.
  */
final class TuiTest[S] private (app: App[S], width: Int, height: Int):
  private val backend  = TestBackend(width, height)
  private val counting = CountingBackend(backend)
  private val terminal = Terminal.create(counting)
  private val opts     = app.options.copy(fps = 0)
  private val sup      = Supervisor[S]()
  private val engine   = Engine(app, opts, Painter(opts.theme), sup)
  private var quitFlag = false

  if !engine.start() then quitFlag = true
  else after(Step.Changed)

  private def draw(view: View): Int =
    discard(terminal.draw(frame => engine.painter.paint(view, frame)))
    counting.lastCount

  /** What the runtime does after a batch, without fibers: plan the work (recorded), draw if needed. */
  private def after(step: Step): Unit =
    step match
      case Step.Quit | Step.Failed => quitFlag = true
      case Step.Same               => ()
      case Step.WorkOnly | Step.Changed =>
        if !engine.planWork() then quitFlag = true
        else
          sup.toStart.clear()
          sup.toStop.clear()
          if step == Step.Changed then
            discard(engine.drawIfNeeded(draw))
            if engine.error != null then quitFlag = true

  private def batch(items: Seq[Item]): this.type =
    if !quitFlag then after(engine.fold(items))
    this

  def state: S                    = engine.state
  def quit: Boolean               = quitFlag              // true after Quit, Ctrl-C or an error
  def error: Option[TuiError]     = Option(engine.error)  // AppFailed / SourceFailed, recorded instead of thrown
  def frames: Long                = engine.frames         // = stats.frames (1 after construction: the first frame)
  def stats: Stats                = engine.stats
  def screen: Screen              = Screen.of(backend, engine.painter.cursor) // immutable copy of the last frame
  def view: View                  = app.view(engine.state) // the view of the current state
  def viewTree: String            = TuiTest.tree(view)     // that view as an indented tree (for agents)
  def sources: Seq[String]        = sup.declared            // labels of the standing sources declared now
  def started: Seq[String]        = sup.started.toSeq       // labels of the one-shot tasks started so far
  def cursor: Option[(Int, Int)]  = engine.painter.cursor
  def lastTrace: String           = engine.trace.lastOption.getOrElse("")
  def trace: Seq[String]          = engine.trace.toSeq

  // input
  def press(keys: (Key | Char)*): this.type =
    keys.foreach(k => batch(Seq(Bind.toKey(k))))
    this
  def send(events: Event*): this.type = batch(events)
  def typeText(text: String): this.type =
    TextField.graphemes(text).foreach { g =>
      if g.length == 1 then batch(Seq(Key.Char(g.charAt(0)))) else batch(Seq(Paste(g)))
    }
    this
  def paste(text: String): this.type = batch(Seq(Paste(text)))
  def click(x: Int, y: Int, button: Button = Button.Left): this.type =
    batch(Seq(Mouse(MouseKind.Down(button), x, y, Mods(), Nil), Mouse(MouseKind.Up(button), x, y, Mods(), Nil)))
  def resize(width: Int, height: Int): this.type =
    backend.resize(width, height)
    batch(Seq(Resize(width, height)))

  // background work
  /** A value from source/task `label`, folded by its CURRENT fold. */
  def emit(label: String, value: Any): this.type =
    val e = sup.byLabel(label)
    if e == null then
      throw AssertionError(
        s"emit($label): no running source or task with this label (known: ${(sup.declared ++ sup.started).mkString(", ")})"
      )
    try batch(Seq(Fold(e.token, value)))
    catch
      case c: ClassCastException =>
        throw AssertionError(s"emit($label): $value (${value.getClass.getSimpleName}) does not fit the fold of $label", c)
    if !e.standing || e.source.kind == Source.Kind.Task then batch(Seq(Done(e.token, Result.unit)))
    this
  end emit

  def fail(label: String, error: Throwable): this.type =
    val e = sup.byLabel(label)
    if e == null then throw AssertionError(s"fail($label): no running source or task with this label")
    batch(Seq(Done(e.token, Result.fail(error))))

  /** Any change, as a general source would send it. */
  def fold(f: S => Next[S]): this.type = batch(Seq(Fold(Fold.HandleToken, f)))
end TuiTest

object TuiTest:
  def apply[S](app: App[S], width: Int = 80, height: Int = 24): TuiTest[S] = new TuiTest(app, width, height)

  /** Feeds the input events recorded in a trace file (5.16) back into `update`. Folds are not replayed. */
  def replay[S](app: App[S], trace: java.nio.file.Path, width: Int = 80, height: Int = 24): TuiTest[S] =
    discard(trace)
    ??? // not part of the validated skeleton

  /** The real loop (fibers, supervisor; frame cap off) on a TestBackend, under Clock.withTimeControl and
    * Random.withSeed(0). Kyo-typed.
    */
  def live[S, A](app: App[S], width: Int = 80, height: Int = 24)(
      test: LiveTest[S] => A < (Async & Abort[Throwable])
  )(using Frame): A < (Async & Abort[Throwable]) =
    discard((app, width, height, test))
    ??? // not part of the validated skeleton

  /** `live` for blocking frameworks (munit, JUnit); KyoApp.Unsafe.runAndBlock underneath. */
  def liveBlocking[S, A](app: App[S], width: Int = 80, height: Int = 24, timeout: Duration = 30.seconds)(
      test: LiveTest[S] => A < (Async & Abort[Throwable])
  )(using Frame): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(timeout)(live(app, width, height)(test)).getOrThrow

  private[testkit] def tree(v: View, indent: String = ""): String =
    def children(cs: Seq[View]) = cs.map(c => tree(c, indent + "  ")).mkString
    v match
      case View.Stack(vertical, cs, _, _) => s"$indent${if vertical then "column" else "row"}\n" + children(cs)
      case View.Layers(cs)                => s"${indent}layers\n" + children(cs)
      case View.Sized(c, k)               => s"$indent$k\n" + tree(c, indent + "  ")
      case View.Bordered(c, b, f)         => s"${indent}bordered(${b.title}${if f then ", focused" else ""})\n" + tree(c, indent + "  ")
      case View.Padded(c, h, w)           => s"${indent}padded($h, $w)\n" + tree(c, indent + "  ")
      case View.Styled(c, _)              => s"${indent}styled\n" + tree(c, indent + "  ")
      case View.Popup(c, w, h)            => s"${indent}popup($w x $h)\n" + tree(c, indent + "  ")
      case View.Identified(c, n)          => s"${indent}id($n)\n" + tree(c, indent + "  ")
      case View.Tagged(c, n)              => s"${indent}tag($n)\n" + tree(c, indent + "  ")
      case other                          => s"$indent$other\n"
  end tree
end TuiTest

trait LiveTest[S]:
  def press(keys: (Key | Char)*)(using Frame): Unit < Async // one batch per key, then settle
  def send(events: Event*)(using Frame): Unit < Async
  def typeText(text: String)(using Frame): Unit < Async

  /** Returns when every item put before the call is folded and drawn, and no one-shot task is running. */
  def settle(using Frame): Unit < Async

  /** Virtual time (6.2): fires every sleeper due within `d`, in deadline order, settling after each step. */
  def advance(d: Duration, sleepers: Int = -1)(using Frame): Unit < Async
  def state(using Frame): S < Sync
  def screen(using Frame): Screen < Sync
  def stats(using Frame): Stats < Sync
  def sources(using Frame): Seq[String] < Sync
  def quit(using Frame): Boolean < Sync
end LiveTest

/** One frame as text plus styles. Rows have trailing spaces trimmed. */
final case class Screen(rows: Vector[String], styles: Vector[Vector[Style]], cursor: Option[(Int, Int)])
    derives CanEqual:
  def text: String                  = rows.mkString("\n") // rows joined with '\n'
  def row(y: Int): String           = rows(y)
  def contains(s: String): Boolean  = rows.exists(_.contains(s))
  def find(s: String): Option[(Int, Int)] =
    rows.iterator.zipWithIndex.collectFirst { case (r, y) if r.contains(s) => (r.indexOf(s), y) }
  def region(x: Int, y: Int, width: Int, height: Int): Vector[String] =
    rows.slice(y, y + height).map(r => r.padTo(x + width, ' ').substring(x, x + width).nn)
  def styleAt(x: Int, y: Int): Style = styles(y)(x)
  override def toString: String      = text
end Screen

object Screen:
  private[tui4s] def of(backend: TestBackend, cursor: Option[(Int, Int)]): Screen =
    val buf  = backend.buffer()
    val area = buf.area()
    // (validated) The text as a terminal shows it: the trailing cells of a wide grapheme hold " " in the buffer and
    // are skipped, as jatatui's Buffer.diff skips them. Without this "✔ call bob" reads "✔  call bob".
    val rows = Vector.tabulate(area.height) { y =>
      val sb = StringBuilder()
      var x  = 0
      while x < area.width do
        val sym = buf.cellAt(area.x + x, area.y + y).symbol()
        discard(sb.append(sym))
        x += JSpan.raw(sym).width().max(1)
      sb.toString.replaceAll("\\s+$", "").nn
    }
    // (validated) Styles normalized: a cell reports Reset for colors nobody set; read them as unset, so that
    // `styleAt(x, y) == Style.bold.fg(Color.Yellow)` holds for a bold yellow cell.
    def norm(o: java.util.Optional[jatatui.core.style.Color]) = o.filter(_ != jatatui.core.style.Color.RESET).nn
    val styles = Vector.tabulate(area.height, area.width) { (y, x) =>
      val st = buf.cellAt(area.x + x, area.y + y).style()
      Style.fromJ(jatatui.core.style.Style(norm(st.fg), norm(st.bg), norm(st.underlineColor), st.addModifier, st.subModifier))
    }
    Screen(rows, styles, cursor)
  end of
end Screen

/** None when equal; otherwise a row-by-row diff. Framework-neutral. */
def diffScreen(actual: Screen, expected: String): Option[String] =
  val exp = expected.linesIterator.map(_.replaceAll("\\s+$", "").nn).toVector
  val act = actual.rows.reverse.dropWhile(_.isEmpty).reverse
  val e2  = exp.reverse.dropWhile(_.isEmpty).reverse
  if act == e2 then None
  else
    val n = act.size.max(e2.size)
    Some(
      (0 until n)
        .collect {
          case i if act.lift(i) != e2.lift(i) =>
            s"row $i\n  actual:   |${act.lift(i).getOrElse("")}|\n  expected: |${e2.lift(i).getOrElse("")}|"
        }
        .mkString("\n")
    )
end diffScreen

/** Throws java.lang.AssertionError with the diff (munit, zio-test, weaver). */
def assertScreen(actual: Screen, expected: String): Unit =
  diffScreen(actual, expected).foreach(d => throw AssertionError(s"screen differs:\n$d\n--- actual:\n${actual.text}"))

/** A scripted InputPort for headless runs (tests, benchmarks). `poll` really waits, like the JNI call. Not in SPEC
  * section 2: added by the validator for the facade tests of 6.3.
  */
final class ScriptedInput(events: Seq[Event]) extends tui4s.spi.InputPort:
  private val queue = java.util.concurrent.LinkedBlockingQueue[Event]()
  events.foreach(e => queue.put(e))
  @volatile private var peeked: Event | Null = null
  def push(e: Event): Unit = queue.put(e)
  def poll(timeoutMillis: Int): Boolean =
    if peeked != null then true
    else
      val e = queue.poll(timeoutMillis.toLong, java.util.concurrent.TimeUnit.MILLISECONDS)
      if e == null then false
      else
        peeked = e
        true
  def read(): Event | Null =
    val p = peeked
    peeked = null
    if p != null then p else queue.poll()
end ScriptedInput

object ScriptedInput:
  def apply(events: (Event | Char)*): ScriptedInput =
    new ScriptedInput(events.map {
      case c: Char  => Key.Char(c)
      case e: Event => e
    })
  def empty: ScriptedInput = new ScriptedInput(Nil)
