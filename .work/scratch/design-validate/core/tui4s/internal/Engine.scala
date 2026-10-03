package tui4s.internal

import kyo.*
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal
import tui4s.*

/** The fold of a batch, the source diff and the draw decision (5.5, 5.6). Plain mutable state, owned by the render
  * fiber (runtime) or by the test thread (TuiTest). No Kyo step per event.
  */
private[tui4s] final class Engine[S](val app: App[S], val opts: TuiOptions, val painter: Painter, val sup: Supervisor[S]):
  var state: S                    = null.asInstanceOf[S]
  var error: TuiError | Null      = null
  private var stateChanged        = true // sources(state) must be diffed
  private val pending             = ArrayBuffer.empty[Source[S]]
  private var lastView: View | Null = null
  private var resized             = false
  val probes: ArrayBuffer[Probe]  = ArrayBuffer.empty

  // Stats
  var frames, viewBuilds, skippedSame, skippedEqualView, batches, events, folds, droppedFolds = 0L
  var lastFrameMicros                                                                        = 0L
  var lastChangedCells                                                                       = 0

  // trace (5.16), kept in memory; the file writer is not part of the skeleton
  val trace: ArrayBuffer[String]        = ArrayBuffer.empty
  private val traceIn: ArrayBuffer[String] = ArrayBuffer.empty
  private var traceState                = ""

  def stats: Stats =
    Stats(frames, viewBuilds, skippedSame, skippedEqualView, batches, events, folds, droppedFolds,
      sup.declared.size, sup.taskCount, lastFrameMicros, lastChangedCells)

  private def fail(where: String, t: Throwable): Unit =
    if error == null then error = TuiError.AppFailed(where, t)

  /** `init`; true when it did not throw. */
  def start(): Boolean =
    try
      state = app.init
      trace += s"""{"kind":"start"}"""
      true
    catch
      case NonFatal(t) =>
        fail("init", t)
        false

  private def same(a: Any, b: Any): Boolean = a match
    case _: (Int | Long | Short | Byte | Char | Boolean | Double | Float | String) => a == b
    case _ => a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]

  /** Applies one `Next`; returns true when the app quits. */
  private def applyNext(next: Next[S]): Boolean =
    if next.asInstanceOf[AnyRef] eq Quit then true
    else
      next match
        case t: Next.Then[?] =>
          val th = t.asInstanceOf[Next.Then[S]]
          state = th.state
          if th.quit then true
          else
            pending ++= th.tasks
            false
        case s =>
          state = s.asInstanceOf[S]
          false

  /** Folds one batch, in order, until a quit (5.5). */
  def fold(batch: Iterable[Item]): Step =
    batches += 1
    traceIn.clear()
    val before  = state
    var quit    = false
    var failed  = false
    var resize0 = false
    val it      = batch.iterator
    while !quit && !failed && it.hasNext do
      it.next() match
        case Key.Ctrl('c') if opts.ctrlCQuits =>
          events += 1
          traceIn += "key:ctrl+c"
          quit = true
        case e: Event =>
          events += 1
          val ev = e match
            case Resize(_, _) =>
              resize0 = true
              e
            case m: Mouse => m.copy(tags = painter.tagsAt(m.x, m.y))
            case other    => other
          traceIn += Engine.code(ev)
          try quit = applyNext(app.update(state, ev))
          catch
            case NonFatal(t) =>
              fail("update on " + Engine.code(ev), t)
              failed = true
        case f: Fold =>
          if f.token == Fold.HandleToken then
            folds += 1
            try quit = applyNext(f.value.asInstanceOf[S => Next[S]](state))
            catch
              case NonFatal(t) =>
                fail("handle fold", t)
                failed = true
          else
            val e = sup.live(f.token)
            if e == null then droppedFolds += 1
            else
              folds += 1
              try quit = foldValue(e, f.value)
              catch
                case NonFatal(t) =>
                  fail("fold of " + e.source.label, t)
                  failed = true
        case d: Done =>
          val e = sup.live(d.token)
          if e != null then
            d.result match
              case Result.Success(_) => sup.finished(d.token)
              case r: Result.Error[?] =>
                sup.finished(d.token)
                val t = r match
                  case Result.Failure(t: Throwable) => t
                  case Result.Panic(t)              => t
                  case other                        => new RuntimeException(other.toString)
                val rec = e.source.recoverWith
                if rec == null then
                  if error == null then error = TuiError.SourceFailed(e.source.label, t)
                  failed = true
                else
                  try quit = applyNext(rec(state, t))
                  catch
                    case NonFatal(t2) =>
                      fail("recover of " + e.source.label, t2)
                      failed = true
            end match
        case p: Probe =>
          probes += p
        case f: Failed =>
          if error == null then error = f.error
          failed = true
        case Stop =>
          quit = true
    end while
    if resize0 then
      resized = true
      painter.markResized()
    val step =
      if failed then Step.Failed
      else if quit then Step.Quit
      else if !same(before, state) || resize0 then
        stateChanged = true
        Step.Changed
      else if pending.nonEmpty then Step.WorkOnly
      else
        skippedSame += 1
        Step.Same
    traceState = step match
      case Step.Changed => "changed"
      case Step.Same    => "same"
      case Step.WorkOnly => "same"
      case Step.Quit    => "quit"
      case Step.Failed  => "failed"
    if step == Step.Same then writeTrace("skip-same")
    step
  end fold

  private def foldValue(e: Entry[S], value: Any): Boolean =
    if e.source.kind == Source.Kind.Chunked then
      value match
        case chunk: Seq[?] =>
          val it = chunk.iterator
          var q  = false
          while !q && it.hasNext do q = applyNext(e.source.fold(state, it.next()))
          q
        case single => applyNext(e.source.fold(state, single))
    else applyNext(e.source.fold(state, value))

  /** After a changed batch: diff sources (if the state changed) and plan the pending tasks. False on error. */
  def planWork(): Boolean =
    try
      if stateChanged then
        sup.sync(app.sources(state))
        stateChanged = false
      if pending.nonEmpty then
        sup.start(pending.toSeq)
        pending.clear()
      true
    catch
      case NonFatal(t) =>
        fail("sources", t)
        false

  /** Builds the view (level 2) and draws at most once. Returns true when a frame was drawn. */
  def drawIfNeeded(draw: View => Int): Boolean =
    val v =
      try
        viewBuilds += 1
        app.view(state)
      catch
        case NonFatal(t) =>
          fail("view", t)
          null
    if v == null then false
    else if !resized && lastView != null && v == lastView then
      skippedEqualView += 1
      writeTrace("skip-equal-view")
      false
    else
      val t0 = java.lang.System.nanoTime()
      lastChangedCells = draw(v)
      lastFrameMicros = (java.lang.System.nanoTime() - t0) / 1000
      frames += 1
      lastView = v
      resized = false
      writeTrace("drawn")
      true
    end if
  end drawIfNeeded

  def lastDrawnView: View | Null = lastView

  private def writeTrace(frame: String): Unit =
    val in = traceIn.map(s => "\"" + s.replace("\"", "\\\"") + "\"").mkString("[", ",", "]")
    trace += s"""{"kind":"batch","in":$in,"state":"$traceState","frame":"$frame"}"""
    if trace.size > 1000 then trace.remove(0, 500)

  /** Completes the probes of the last batch (after its draw decision). */
  def completeProbes()(using AllowUnsafe): Unit =
    if probes.nonEmpty then
      val st = stats
      probes.foreach(_.promise.completeDiscard(Result.succeed(st)))
      probes.clear()
end Engine

private[tui4s] object Engine:
  def code(e: Event): String = e match
    case k: Key                 => "key:" + k.show
    case Paste(t)               => "paste:" + java.util.Base64.getEncoder.nn.encodeToString(t.getBytes("UTF-8"))
    case Resize(w, h)           => s"resize:${w}x$h"
    case Mouse(kind, x, y, _, _) => s"mouse:$kind:$x,$y"
    case FocusGained            => "focus:gained"
    case FocusLost              => "focus:lost"
end Engine
