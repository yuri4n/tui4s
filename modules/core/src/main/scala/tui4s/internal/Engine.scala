package tui4s.internal

import kyo.*
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

    /** A new state, or a resize: diff the sources, build the view, maybe draw. */
    case Changed

    /** User code threw, a source failed or the input failed; `Engine.error`
      * says why.
      */
    case Failed
end Step

/** The fold of a batch, the source diff and the draw decision (SPEC 5.5, 5.6,
  * 2.13). Plain mutable state, owned by the render fiber (runtime) or by the
  * test thread (`TuiTest`). A batch is one plain `while` loop: no Kyo step per
  * item.
  *
  * A caller drives it like this:
  *
  *   1. [[start]] (`init`), [[syncWork]] (`sources(init)`), [[firstFrame]].
  *   1. For each batch: [[fold]]; then by the step: `Same` or `WorkOnly`:
  *      [[syncWork]] and [[skipSame]]; `Changed`: [[syncWork]] and
  *      [[drawIfNeeded]] (a paced runtime may [[fold]] once more in between);
  *      `Quit` / `Failed`: [[finish]].
  *   1. After each decision: [[completeProbes]] (runtime).
  *
  * @param tracer
  *   where trace lines go; `null`: no trace (and no trace cost)
  */
private[tui4s] final class Engine[S](
    val app: App[S],
    val opts: TuiOptions,
    val painter: Painter,
    val sup: Supervisor[S],
    tracer: Tracer | Null,
):
    /** The current state. Valid after [[start]] returned true. */
    var state: S = null.asInstanceOf[S]

    /** Why the run failed, recorded instead of thrown. */
    var error: TuiError | Null = null

    /** Why the app stopped ("update", "fold", "ctrl-c", "stop"); `null` while
      * it runs or when it failed.
      */
    var quitReason: String | Null = null

    /** The class of a value that `TuiTest.emit` folds now; `null` otherwise. A
      * `ClassCastException` from a fold that names this class (the value does
      * not fit the fold) leaves [[fold]] instead of ending the run: `emit`
      * turns it into an assertion error. Any other one is an `AppFailed`.
      */
    var emittedClass: Class[?] | Null = null

    /** The last folded resize; the runtime resizes a test backend before the
      * next draw.
      */
    var pendingResize: Resize | Null = null

    private val pending               = ArrayBuffer.empty[Source[S]]
    private val probes                = ArrayBuffer.empty[Probe]
    private var sourcesDirty          = true
    private var lastView: View | Null = null
    private var resized               = false
    private var ended                 = false
    private var probeOnly             = false

    private var frames, viewBuilds, skippedSame, skippedEqualView = 0L
    private var batches, events, folds, droppedFolds              = 0L
    private var lastFrameMicros                                   = 0L
    private var lastChangedCells                                  = 0

    // The trace record of the batches before one draw decision.
    private val tracing     = tracer != null
    private val t0          = java.lang.System.nanoTime()
    private val budgetNanos =
      if opts.fps > 0 then 1_000_000_000L / opts.fps else 1_000_000_000L / 60
    private val recIn       = ArrayBuffer.empty[String]
    private val recFolds    = ArrayBuffer.empty[String]
    private val recStart    = ArrayBuffer.empty[String]
    private val recStop     = ArrayBuffer.empty[String]
    private val recTasks    = ArrayBuffer.empty[String]
    private var recDropped  = 0
    private var recChanged  = false
    private var recOpen     = false
    private var recNanos    = 0L

    /** A snapshot of the counters. */
    def stats: Stats =
      Stats(
        frames = frames,
        viewBuilds = viewBuilds,
        skippedSame = skippedSame,
        skippedEqualView = skippedEqualView,
        batches = batches,
        events = events,
        folds = folds,
        droppedFolds = droppedFolds,
        sources = sup.sourceCount,
        tasks = sup.taskCount,
        lastFrameMicros = lastFrameMicros,
        lastChangedCells = lastChangedCells,
      )

    /** True after [[finish]]. */
    def isEnded: Boolean = ended

    /** Calls `init`. False when it threw ([[error]] says so). */
    def start(): Boolean =
      try
          state = app.init
          sourcesDirty = true
          true
      catch
          case NonFatal(t) =>
            fail("init", t)
            false

    /** Folds one batch, in order, until a quit or a failure (SPEC 5.5). */
    def fold(batch: Iterable[Item]): Step =
        probeOnly = true
        val before    = state
        var quit      = false
        var failed    = false
        var resize    = false
        var slowWhere = ""
        var slowItem  = 0L
        val foldStart = if tracing then java.lang.System.nanoTime() else 0L
        val it        = batch.iterator
        while !quit && !failed && it.hasNext do
            val itemStart     = if tracing then java.lang.System.nanoTime() else 0L
            var where: String = ""
            val item          = it.next()
            if probeOnly && !item.isInstanceOf[Probe] then
                // A batch of probes only (a settle) is not a batch of the app.
                probeOnly = false
                batches += 1
                if tracing && !recOpen then openRecord()
            item match
                case e: Event  =>
                  events += 1
                  e match
                      case Key.Ctrl('c') if opts.ctrlCQuits =>
                        if tracing then recIn += "key:ctrl+c"
                        quit = true
                        quitReason = "ctrl-c"
                      case _                                =>
                        val ev = e match
                            case r: Resize =>
                              resize = true
                              pendingResize = r
                              r
                            case m: Mouse  =>
                              m.copy(tags = painter.tagsAt(m.x, m.y))
                            case other     => other
                        if tracing then
                            val code = EventCode(ev)
                            recIn += code
                            where = "update on " + code
                        try
                            if applyNext(app.update(state, ev)) then
                                quit = true
                                quitReason = "update"
                        catch
                            case NonFatal(t) =>
                              fail("update on " + EventCode(ev), t)
                              failed = true
                        end try
                  end match
                case f: Fold   =>
                  if f.token == Fold.Direct then
                      folds += 1
                      if tracing then
                          recFolds += "fold"
                          where = "fold"
                      try
                          if applyNext(
                                f.value.asInstanceOf[S => Next[S]](state),
                              )
                          then
                              quit = true
                              quitReason = "fold"
                      catch
                          case NonFatal(t) =>
                            fail("fold", t)
                            failed = true
                      end try
                  else
                      val r = sup.live(f.token)
                      if r == null then
                          droppedFolds += 1
                          recDropped += 1
                      else
                          folds += 1
                          val label = r.source.label
                          if tracing then
                              recFolds += label
                              where = "fold of " + label
                          try
                              if foldValue(r, f.value) then
                                  quit = true
                                  quitReason = "fold"
                          catch
                              case c: ClassCastException
                                  if Engine.castOf(c, emittedClass) =>
                                // The value never folded: undo its counts.
                                folds -= 1
                                batches -= 1
                                clearRecord()
                                throw c
                              case NonFatal(t) =>
                                fail("fold of " + label, t)
                                failed = true
                          end try
                      end if
                  end if
                case d: Done   =>
                  val r = sup.live(d.token)
                  if r != null then
                      sup.finished(d.token)
                      val problem: Throwable | Null = d.result match
                          case Result.Failure(t: Throwable) => t
                          case Result.Panic(t)              => t
                          case _                            => null
                      if problem != null then
                          val label = r.source.label
                          val rec   = r.source.recoverWith
                          if rec == null then
                              if error == null then
                                  error = TuiError.SourceFailed(label, problem)
                              failed = true
                          else
                              if tracing then where = "recover of " + label
                              try
                                  if applyNext(rec(state, problem)) then
                                      quit = true
                                      quitReason = "fold"
                              catch
                                  case NonFatal(t) =>
                                    fail("recover of " + label, t)
                                    failed = true
                              end try
                          end if
                      end if
                  end if
                case p: Probe  => probes += p
                case f: Failed =>
                  if error == null then error = f.error
                  failed = true
                case Stop      =>
                  quit = true
                  quitReason = "stop"
            end match
            if tracing then
                val took = java.lang.System.nanoTime() - itemStart
                if took > slowItem && where.nonEmpty then
                    slowItem = took
                    slowWhere = where
        end while
        // Not folded after a quit or a failure, but a waiting settle is
        // still released (when the run ends).
        while it.hasNext do
            it.next() match
                case p: Probe => probes += p
                case _        => ()
        if resize then resized = true
        val step      =
          if failed then Step.Failed
          else if quit then Step.Quit
          else if resize || !Engine.same(before, state) then
              sourcesDirty = true
              Step.Changed
          else if pending.nonEmpty then Step.WorkOnly
          else Step.Same
        if tracing then
            if step == Step.Changed then recChanged = true
            val took = java.lang.System.nanoTime() - foldStart
            if took > budgetNanos then
                slow(if slowWhere.nonEmpty then slowWhere else "batch", took)
        step
    end fold

    /** After a batch: diff `sources(state)` when the state changed, then plan
      * the one-shot tasks of the batch. False when `sources` threw ([[error]]
      * says so).
      */
    def syncWork(): Boolean =
      try
          if sourcesDirty then
              sourcesDirty = false
              val before = if tracing then java.lang.System.nanoTime() else 0L
              val wanted = app.sources(state)
              if tracing then
                  val took = java.lang.System.nanoTime() - before
                  if took > budgetNanos then slow("sources", took)
              sup.sync(wanted)
          end if
          if pending.nonEmpty then
              sup.start(pending)
              pending.clear()
          if tracing then recordWork()
          true
      catch
          case NonFatal(t) =>
            fail("sources", t)
            false

    /** The first frame, after [[start]] and [[syncWork]]. False when user code
      * threw.
      */
    def firstFrame(width: Int, height: Int, draw: View => Int): Boolean =
        if tracing then
            tracer.nn.line(
              s"""{"t":${Json.seconds(java.lang.System.nanoTime() - t0)},""" +
                s""""kind":"start","size":"${width}x$height",""" +
                s""""sources":${Json.arr(sup.declared)}}""",
            )
            clearRecord()
        discard(decide(draw, record = false))
        error == null
    end firstFrame

    /** The decision for a batch whose state is the same object. */
    def skipSame(): Unit =
      if !probeOnly then
          skippedSame += 1
          if tracing then writeBatch("same", "skip-same", 0L, 0)

    /** Builds the view and draws it unless it equals the last drawn view (and
      * no resize happened since). `draw` paints a view and returns the number
      * of changed cells; it may throw a [[TuiError]] for terminal I/O. True
      * when a frame was drawn; false also when the run failed ([[error]] says
      * why).
      */
    def drawIfNeeded(draw: View => Int): Boolean = decide(draw, record = true)

    private def decide(draw: View => Int, record: Boolean): Boolean =
        viewBuilds += 1
        val before = if tracing then java.lang.System.nanoTime() else 0L
        val view   =
          try app.view(state)
          catch
              case NonFatal(t) =>
                fail("view", t)
                null
        if tracing then
            val took = java.lang.System.nanoTime() - before
            if took > budgetNanos then slow("view", took)
        if view == null then
            if record && tracing then writeBatch("failed", "skip-failed", 0, 0)
            false
        else if !resized && lastView != null && view == lastView then
            skippedEqualView += 1
            if record && tracing then
                writeBatch("changed", "skip-equal-view", 0L, 0)
            false
        else
            val start = java.lang.System.nanoTime()
            try
                lastChangedCells = draw(view)
                lastFrameMicros = (java.lang.System.nanoTime() - start) / 1000
                frames += 1
                lastView = view
                resized = false
                if record && tracing then
                    writeBatch(
                      "changed",
                      "drawn",
                      lastFrameMicros,
                      lastChangedCells,
                    )
                true
            catch
                case e: TuiError =>
                  if error == null then error = e
                  if record && tracing then
                      writeBatch("failed", "skip-failed", 0, 0)
                  false
                case NonFatal(t) =>
                  fail("paint", t)
                  if record && tracing then
                      writeBatch("failed", "skip-failed", 0, 0)
                  false
            end try
        end if
    end decide

    /** The app ended: the trace gets the last batch and a quit line.
      * Idempotent.
      *
      * @param reason
      *   used when the engine knows no reason (an interrupted run)
      */
    def finish(reason: String): Unit =
      if !ended then
          ended = true
          if tracing then
              val t   = tracer.nn
              if recOpen then
                  if error != null then
                      writeBatch("failed", "skip-failed", 0, 0)
                  else writeBatch("quit", "skip-quit", 0, 0)
              val why =
                if error != null then "error"
                else if quitReason != null then quitReason.nn
                else reason
              val err =
                if error == null then ""
                else
                    s""","error":${Json.str(
                        String.valueOf(error.nn.getMessage),
                      )}"""
              t.line(
                s"""{"t":${Json.seconds(java.lang.System.nanoTime() - t0)},""" +
                  s""""kind":"quit","reason":${Json.str(why)}$err}""",
              )
              t.flush()
          end if

    /** Writes out buffered trace lines (the render fiber goes idle). */
    def flushTrace(): Unit = if tracing then tracer.nn.flush()

    /** After a draw decision: completes each probe whose batch count is reached
      * (a plain probe at once, a news probe once the app folded a new batch),
      * and forgets the probes that their caller completed.
      */
    def completeProbes()(using AllowUnsafe): Unit =
      if probes.nonEmpty then
          val st = stats
          probes.filterInPlace { p =>
            if p.promise.done() then false
            else if batches > p.after then
                p.promise.completeDiscard(Result.succeed(st))
                false
            else true
          }

    /** Completes every probe (the app ended). */
    def completeAllProbes()(using AllowUnsafe): Unit =
      if probes.nonEmpty then
          val st = stats
          probes.foreach(_.promise.completeDiscard(Result.succeed(st)))
          probes.clear()

    /** The one-shot tasks of the transitions not yet given to the supervisor. */
    def pendingTasks: Seq[Source[S]] = pending.toList

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

    /** Folds a value with the generation's CURRENT fold. A chunk folds element
      * by element and stops at a quit.
      */
    private def foldValue(r: Running[S], value: Any): Boolean =
        val f = r.source.fold
        if r.source.kind == Source.Kind.Chunked then
            val it = value.asInstanceOf[Iterable[Any]].iterator
            var q  = false
            while !q && it.hasNext do q = applyNext(f(state, it.next()))
            q
        else applyNext(f(state, value))
    end foldValue

    private def fail(where: String, t: Throwable): Unit =
      if error == null then error = TuiError.AppFailed(where, t)

    private def openRecord(): Unit =
        recOpen = true
        recNanos = java.lang.System.nanoTime()

    private def clearRecord(): Unit =
        recIn.clear()
        recFolds.clear()
        recStart.clear()
        recStop.clear()
        recTasks.clear()
        recDropped = 0
        recChanged = false
        recOpen = false
    end clearRecord

    private def recordWork(): Unit =
        sup.toStart.foreach { r =>
          if !r.stopped then
              if r.standing then recStart += r.source.label
              else recTasks += r.source.label
        }
        sup.toStop.foreach(r => recStop += r.source.label)

    private def writeBatch(
        state0: String,
        frame: String,
        frameMicros: Long,
        cells: Int,
    ): Unit =
        val now         = java.lang.System.nanoTime()
        val b           = new java.lang.StringBuilder(160)
        val st          = if state0 == "same" && recChanged then "changed" else state0
        discard(
          b.append("{\"t\":")
            .append(Json.seconds(now - t0))
            .append(",\"kind\":\"batch\",\"in\":")
            .append(Json.arr(recIn)),
        )
        if recFolds.nonEmpty then
            discard(b.append(",\"folds\":").append(Json.arr(recFolds)))
        if recDropped > 0 then
            discard(b.append(",\"dropped\":").append(recDropped))
        discard(b.append(",\"state\":").append(Json.str(st)))
        if recStart.nonEmpty then
            discard(b.append(",\"start\":").append(Json.arr(recStart)))
        if recStop.nonEmpty then
            discard(b.append(",\"stop\":").append(Json.arr(recStop)))
        if recTasks.nonEmpty then
            discard(b.append(",\"tasks\":").append(Json.arr(recTasks)))
        discard(b.append(",\"frame\":").append(Json.str(frame)))
        if frame == "drawn" then
            discard(
              b.append(",\"frameMicros\":")
                .append(frameMicros)
                .append(",\"cells\":")
                .append(cells),
            )
        val batchMicros = if recOpen then (now - recNanos) / 1000 else 0L
        discard(b.append(",\"batchMicros\":").append(batchMicros).append('}'))
        tracer.nn.line(b.toString)
        clearRecord()
    end writeBatch

    private def slow(where: String, nanos: Long): Unit =
      tracer.nn.line(
        s"""{"t":${Json.seconds(java.lang.System.nanoTime() - t0)},""" +
          s""""kind":"slow","where":${Json.str(where)},""" +
          s""""micros":${nanos / 1000}}""",
      )
end Engine

private[tui4s] object Engine:
    /** True when `c` is the cast of a value of class `cls` (the JVM message
      * starts with "class <name> cannot be cast").
      */
    def castOf(c: ClassCastException, cls: Class[?] | Null): Boolean =
        val m = c.getMessage
        cls != null && m != null &&
        m.startsWith("class " + cls.getName + " cannot be cast")

    /** Level 1: the same object. Boxed primitives and strings compare with `==`
      * (an `Int` above 127 is boxed again by each `+ 1`).
      */
    def same(a: Any, b: Any): Boolean = a match
        case _: (Int | Long | Short | Byte | Char | Boolean | Double | Float |
              String) =>
          a == b
        case _ => a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]
end Engine
