package tui4s.internal

import jatatui.core.backend.Backend
import jatatui.core.backend.ClearType
import jatatui.core.backend.WindowSize
import jatatui.core.buffer.BufferUpdate
import jatatui.core.layout.Position
import jatatui.core.layout.Size
import jatatui.core.terminal.Terminal
import kyo.*
import tui4s.*
import tui4s.spi.InputPort

/** Counts the BufferUpdates it forwards (Stats.lastChangedCells). */
private[tui4s] final class CountingBackend(underlying: Backend) extends Backend:
  var lastCount: Int = 0
  def draw(content: java.lang.Iterable[BufferUpdate]): Unit =
    var n  = 0
    val it = content.iterator()
    val b  = java.util.ArrayList[BufferUpdate]()
    while it.hasNext do
      discard(b.add(it.next()))
      n += 1
    lastCount = n
    underlying.draw(b)
  override def appendLines(n: Int): Unit                    = underlying.appendLines(n)
  def hideCursor(): Unit                                    = underlying.hideCursor()
  def showCursor(): Unit                                    = underlying.showCursor()
  def getCursorPosition(): Position                         = underlying.getCursorPosition()
  def setCursorPosition(position: Position): Unit           = underlying.setCursorPosition(position)
  def clear(): Unit                                         = underlying.clear()
  def clearRegion(clearType: ClearType): Unit               = underlying.clearRegion(clearType)
  def size(): Size                                          = underlying.size()
  def windowSize(): WindowSize                              = underlying.windowSize()
  def flush(): Unit                                         = underlying.flush()
  def scrollRegionUp(start: Int, end: Int, count: Int): Unit   = underlying.scrollRegionUp(start, end, count)
  def scrollRegionDown(start: Int, end: Int, count: Int): Unit = underlying.scrollRegionDown(start, end, count)
end CountingBackend

/** An open terminal (or a headless stand-in). `close()` is synchronous, idempotent and never throws (5.9). */
private[tui4s] trait Session:
  def terminal: Terminal[CountingBackend]
  def close(): Unit

/** How a run acquires its terminal. */
private[tui4s] trait TerminalControl:
  def open(opts: TuiOptions, inbox: Channel[Item])(using Frame): Session < (Sync & Abort[TuiError])

/** The dedicated input thread (5.3). */
private[tui4s] final class InputThread(port: InputPort, inbox: Channel[Item], pollMillis: Int)
    extends java.lang.Thread("tui4s-input"):
  setDaemon(true)
  @volatile private var running = true

  override def run(): Unit =
    import AllowUnsafe.embrace.danger
    try
      while running do
        if port.poll(pollMillis) then
          val batch = scala.collection.mutable.ArrayBuffer.empty[Item]
          var n     = 0
          while running && n < 512 && (n == 0 || port.poll(0)) do // drain what is already queued
            val e = port.read()
            if e != null then batch += e                          // key Release etc. map to null
            n += 1
          if batch.nonEmpty then
            val accepted = java.util.concurrent.CountDownLatch(1) // backpressure, as in loop.scala
            inbox.unsafe.putBatchFiber(batch.toSeq).onComplete(_ => accepted.countDown())
            accepted.await() // also released when the release closes the inbox
          end if
        end if
      end while
    catch
      case scala.util.control.NonFatal(e) =>
        discard(inbox.unsafe.offer(Failed(TuiError.TerminalIO(e))))
    end try
  end run

  def stopAndJoin(timeoutMillis: Long): Boolean =
    running = false
    join(timeoutMillis)
    !isAlive
end InputThread

/** Headless: any jatatui Backend and any InputPort; no raw mode, no alternate screen. `log` records the release
  * order for tests (enter, input-started, input-stopped, input-joined, leave-raw-mode).
  */
private[tui4s] final class HeadlessControl(
    backend: Backend,
    input: InputPort,
    val log: java.util.concurrent.ConcurrentLinkedQueue[String] = java.util.concurrent.ConcurrentLinkedQueue()
) extends TerminalControl:
  def open(opts: TuiOptions, inbox: Channel[Item])(using Frame): Session < (Sync & Abort[TuiError]) =
    Sync.Unsafe.defer {
      discard(log.add("enter"))
      val term   = Terminal.create(CountingBackend(backend))
      val thread = InputThread(input, inbox, opts.pollMillis)
      thread.start()
      discard(log.add("input-started"))
      new Session:
        private val closed                       = java.util.concurrent.atomic.AtomicBoolean(false)
        def terminal: Terminal[CountingBackend] = term
        def close(): Unit =
          if closed.compareAndSet(false, true) then
            discard(inbox.unsafe.close())
            discard(log.add("input-stopped"))
            discard(thread.stopAndJoin(2L * opts.pollMillis + 50))
            discard(log.add("input-joined"))
            discard(log.add("leave-raw-mode"))
    }
end HeadlessControl

/** The real terminal through crossterm (5.9). Compiled, not run by the validator (no TTY). */
private[tui4s] final class CrosstermControl extends TerminalControl:
  import tui.crossterm.Command
  import tui.crossterm.CrosstermJni

  def open(opts: TuiOptions, inbox: Channel[Item])(using Frame): Session < (Sync & Abort[TuiError]) =
    Sync.Unsafe.defer {
      try Right(openUnsafe(opts, inbox))
      catch
        case e: TuiError                       => Left(e)
        case scala.util.control.NonFatal(e) => Left(TuiError.TerminalIO(e))
    }.map(r => Abort.get(r))

  private def openUnsafe(opts: TuiOptions, inbox: Channel[Item])(using AllowUnsafe): Session =
      val jni =
        try new CrosstermJni()
        catch
          case e: (IllegalCallerException | UnsatisfiedLinkError) => throw TuiError.NativeAccessDenied(e)
      try jni.enableRawMode()
      catch case e: RuntimeException => throw TuiError.NotATerminal(e)
      val enter = Seq.newBuilder[Command]
      if opts.viewport == Viewport.Fullscreen then enter += Command.EnterAlternateScreen()
      enter += Command.Hide()
      if opts.paste then enter += Command.EnableBracketedPaste()
      if opts.mouse then enter += Command.EnableMouseCapture()
      if opts.focusEvents then enter += Command.EnableFocusChange()
      jni.execute(enter.result()*)
      val backend = CountingBackend(jatatui.crossterm.CrosstermBackend(jni))
      val term = opts.viewport match
        case Viewport.Fullscreen => Terminal.create(backend)
        case Viewport.Inline(h) =>
          Terminal.withOptions(
            backend,
            jatatui.core.terminal.TerminalOptions(jatatui.core.terminal.Viewport.inline(h))
          )
      val thread = InputThread(CrosstermInput(jni), inbox, opts.pollMillis)
      thread.start()
      new Session:
        private val closed                       = java.util.concurrent.atomic.AtomicBoolean(false)
        def terminal: Terminal[CountingBackend] = term
        def close(): Unit =
          if closed.compareAndSet(false, true) then
            discard(inbox.unsafe.close())
            discard(thread.stopAndJoin(2L * opts.pollMillis + 50))
            val leave = Seq.newBuilder[Command]
            if opts.focusEvents then leave += Command.DisableFocusChange()
            if opts.mouse then leave += Command.DisableMouseCapture()
            if opts.paste then leave += Command.DisableBracketedPaste()
            leave += Command.Show()
            if opts.viewport == Viewport.Fullscreen then leave += Command.LeaveAlternateScreen()
            try jni.execute(leave.result()*)
            catch case scala.util.control.NonFatal(_) => ()
            try
              jni.disableRawMode()
              jni.flush()
            catch case scala.util.control.NonFatal(_) => ()
      end new
  end openUnsafe
end CrosstermControl

/** crossterm as an InputPort (input thread only). */
private[tui4s] final class CrosstermInput(jni: tui.crossterm.CrosstermJni) extends InputPort:
  def poll(timeoutMillis: Int): Boolean =
    jni.poll(tui.crossterm.Duration(timeoutMillis / 1000L, (timeoutMillis % 1000) * 1000000))
  def read(): Event | Null = EventMapping(jni.read())

/** crossterm events -> tui4s events (2.4). Type patterns: Java records are not extractors. */
private[tui4s] object EventMapping:
  import tui.crossterm.{Event as CEvent, KeyCode, KeyEventKind, KeyModifiers, MouseButton, MouseEventKind}

  private def mods(m: KeyModifiers): Mods =
    Mods(
      shift = (m.bits & KeyModifiers.SHIFT) != 0,
      ctrl = (m.bits & KeyModifiers.CONTROL) != 0,
      alt = (m.bits & KeyModifiers.ALT) != 0
    )

  private def button(b: MouseButton): Button = b match
    case MouseButton.Left   => Button.Left
    case MouseButton.Right  => Button.Right
    case MouseButton.Middle => Button.Middle

  def apply(e: CEvent): Event | Null = e match
    case k: CEvent.Key =>
      val ke = k.keyEvent
      if ke.kind == KeyEventKind.Release then null
      else
        val m = mods(ke.modifiers)
        def named(key: Key): Key = if m.ctrl || m.alt || m.shift then Key.Modified(key, m) else key
        ke.code match
          case c: KeyCode.Char =>
            if m.ctrl then Key.Ctrl(c.c.toLower) else if m.alt then Key.Alt(c.c) else Key.Char(c.c)
          case _: KeyCode.BackTab   => Key.BackTab
          case _: KeyCode.Enter     => named(Key.Enter)
          case _: KeyCode.Esc       => named(Key.Esc)
          case _: KeyCode.Tab       => named(Key.Tab)
          case _: KeyCode.Backspace => named(Key.Backspace)
          case _: KeyCode.Delete    => named(Key.Delete)
          case _: KeyCode.Insert    => named(Key.Insert)
          case _: KeyCode.Up        => named(Key.Up)
          case _: KeyCode.Down      => named(Key.Down)
          case _: KeyCode.Left      => named(Key.Left)
          case _: KeyCode.Right     => named(Key.Right)
          case _: KeyCode.Home      => named(Key.Home)
          case _: KeyCode.End       => named(Key.End)
          case _: KeyCode.PageUp    => named(Key.PageUp)
          case _: KeyCode.PageDown  => named(Key.PageDown)
          case f: KeyCode.F         => named(Key.F(f.num))
          case _                    => null
        end match
    case m: CEvent.Mouse =>
      val me = m.mouseEvent
      val kind = me.kind match
        case d: MouseEventKind.Down  => MouseKind.Down(button(d.mouseButton))
        case u: MouseEventKind.Up    => MouseKind.Up(button(u.mouseButton))
        case d: MouseEventKind.Drag  => MouseKind.Drag(button(d.mouseButton))
        case _: MouseEventKind.Moved => MouseKind.Moved
        case _: MouseEventKind.ScrollUp    => MouseKind.ScrollUp
        case _: MouseEventKind.ScrollDown  => MouseKind.ScrollDown
        case _: MouseEventKind.ScrollLeft  => MouseKind.ScrollLeft
        case _: MouseEventKind.ScrollRight => MouseKind.ScrollRight
      Mouse(kind, me.column, me.row, mods(me.modifiers), Nil)
    case p: CEvent.Paste       => Paste(p.string)
    case r: CEvent.Resize      => Resize(r.columns, r.rows)
    case _: CEvent.FocusGained => FocusGained
    case _: CEvent.FocusLost   => FocusLost
end EventMapping
