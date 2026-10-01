package tui4s.internal

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal
import tui4s.*

/** The result of folding one batch. */
private[tui4s] enum Step derives CanEqual:
    /** The app stops: no later item is folded and no frame is drawn. */
    case Quit

    /** The final state is the same object: no `view` call, no frame. */
    case Same

    /** The same state, but new one-shot tasks to start. */
    case WorkOnly

    /** A new state, or a resize: build the view, maybe draw. */
    case Changed

    /** User code threw; `Engine.error` says where. */
    case Failed
end Step

/** The fold of a batch and the draw decision (SPEC 5.5, 5.6). Plain mutable
  * state, owned by the render fiber (runtime) or by the test thread
  * (`TuiTest`). No Kyo step per event.
  *
  * This version folds input events. Values from sources and tasks, and the
  * source diff, come with the source runtime; until then the one-shot tasks of
  * a transition wait in [[takePending]].
  */
private[tui4s] final class Engine[S](
    val app: App[S],
    val opts: TuiOptions,
    val painter: Painter,
):
    /** The current state. Valid after [[start]] returned true. */
    var state: S = null.asInstanceOf[S]

    /** The first failure of user code, recorded instead of thrown. */
    var error: TuiError | Null = null

    private val pending                                        = ArrayBuffer.empty[Source[S]]
    private var lastView: View | Null                          = null
    private var resized                                        = false
    private var frames, viewBuilds                             = 0L
    private var skippedSame, skippedEqualView, batches, events = 0L
    private var lastFrameMicros                                = 0L
    private var lastChangedCells                               = 0

    /** A snapshot of the counters. */
    def stats: Stats =
      Stats(
        frames = frames,
        viewBuilds = viewBuilds,
        skippedSame = skippedSame,
        skippedEqualView = skippedEqualView,
        batches = batches,
        events = events,
        folds = 0L,
        droppedFolds = 0L,
        sources = 0,
        tasks = 0,
        lastFrameMicros = lastFrameMicros,
        lastChangedCells = lastChangedCells,
      )

    /** Calls `init`. False when it threw ([[error]] says so). */
    def start(): Boolean =
      try
          state = app.init
          true
      catch
          case NonFatal(t) =>
            fail("init", t)
            false

    /** The one-shot tasks of the transitions since the last call, in order. */
    def takePending(): Seq[Source[S]] =
        val tasks = pending.toList
        pending.clear()
        tasks

    /** Folds one batch of events, in order, until a quit or a failure. */
    def fold(batch: Iterable[Event]): Step =
        batches += 1
        val before = state
        var quit   = false
        var failed = false
        var resize = false
        val it     = batch.iterator
        while !quit && !failed && it.hasNext do
            val event = it.next()
            events += 1
            event match
                case Key.Ctrl('c') if opts.ctrlCQuits => quit = true
                case e                                =>
                  val ev = e match
                      case Resize(_, _) =>
                        resize = true
                        e
                      case m: Mouse     => m.copy(tags = painter.tagsAt(m.x, m.y))
                      case other        => other
                  try quit = applyNext(app.update(state, ev))
                  catch
                      case NonFatal(t) =>
                        fail("update on " + Engine.code(ev), t)
                        failed = true
            end match
        end while
        if resize then resized = true
        if failed then Step.Failed
        else if quit then Step.Quit
        else if resize || !Engine.same(before, state) then Step.Changed
        else if pending.nonEmpty then Step.WorkOnly
        else
            skippedSame += 1
            Step.Same
    end fold

    /** Builds the view and draws it unless it equals the last drawn view (and
      * no resize happened since). `draw` paints a view and returns the number
      * of changed cells. True when a frame was drawn; false also when user code
      * threw ([[error]] says so).
      */
    def drawIfNeeded(draw: View => Int): Boolean =
        viewBuilds += 1
        val view =
          try app.view(state)
          catch
              case NonFatal(t) =>
                fail("view", t)
                null
        if view == null then false
        else if !resized && lastView != null && view == lastView then
            skippedEqualView += 1
            false
        else
            val t0 = java.lang.System.nanoTime()
            try
                lastChangedCells = draw(view)
                lastFrameMicros = (java.lang.System.nanoTime() - t0) / 1000
                frames += 1
                lastView = view
                resized = false
                true
            catch
                case NonFatal(t) =>
                  fail("paint", t)
                  false
            end try
        end if
    end drawIfNeeded

    /** Applies one [[Next]]; true when the app stops. */
    private def applyNext(next: Next[S]): Boolean =
      if next.asInstanceOf[AnyRef] eq Quit then true
      else
          next match
              case t: Next.Then[?] =>
                val step = t.asInstanceOf[Next.Then[S]]
                state = step.state
                if step.quit then true
                else
                    pending ++= step.tasks
                    false
              case s               =>
                state = s.asInstanceOf[S]
                false

    private def fail(where: String, t: Throwable): Unit =
      if error == null then error = TuiError.AppFailed(where, t)
end Engine

private[tui4s] object Engine:
    /** Level 1: the same object. Boxed primitives and strings compare with `==`
      * (an `Int` above 127 is boxed again by each `+ 1`).
      */
    def same(a: Any, b: Any): Boolean = a match
        case _: (Int | Long | Short | Byte | Char | Boolean | Double | Float |
              String) =>
          a == b
        case _ => a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]

    /** The code of an event in messages and traces: "key:ctrl+s",
      * "resize:80x24", "mouse:down:left:3,4", "paste:<base64>".
      */
    def code(e: Event): String = e match
        case k: Key                  => "key:" + k.show
        case Paste(t)                =>
          "paste:" + java.util.Base64.getEncoder
            .encodeToString(t.getBytes("UTF-8"))
        case Resize(w, h)            => s"resize:${w}x$h"
        case Mouse(kind, x, y, _, _) => s"mouse:${mouseCode(kind)}:$x,$y"
        case FocusGained             => "focus:gained"
        case FocusLost               => "focus:lost"

    private def mouseCode(kind: MouseKind): String = kind match
        case MouseKind.Down(b)     => "down:" + b.toString.toLowerCase
        case MouseKind.Up(b)       => "up:" + b.toString.toLowerCase
        case MouseKind.Drag(b)     => "drag:" + b.toString.toLowerCase
        case MouseKind.Moved       => "moved"
        case MouseKind.ScrollUp    => "scroll-up"
        case MouseKind.ScrollDown  => "scroll-down"
        case MouseKind.ScrollLeft  => "scroll-left"
        case MouseKind.ScrollRight => "scroll-right"
end Engine
