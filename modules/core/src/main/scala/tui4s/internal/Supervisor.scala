package tui4s.internal

import kyo.*
import scala.collection.mutable.ArrayBuffer
import tui4s.*

/** One generation of a source or task: a token, the declaration it runs, and
  * (in the runtime) its fiber. Owned by the render fiber.
  *
  * @param token
  *   the generation: values carry it, so a value from a stopped generation is
  *   dropped
  * @param source
  *   the declaration; for a standing source the newest one (newest fold and
  *   recover)
  * @param standing
  *   true for a source declared by `sources(state)`, false for a one-shot task
  */
private[tui4s] final class Running[S](
    val token: Long,
    var source: Source[S],
    val standing: Boolean,
):
    /** The fiber, once the launcher started it. */
    var fiber: Fiber[Unit, Abort[Throwable]] | Null = null

    /** The body ended (its `Done` was folded). */
    var done: Boolean = false

    /** Stopped by the supervisor (also before its start). */
    var stopped: Boolean = false
end Running

/** The bookkeeping of sources and tasks (SPEC 5.8): generation tokens, the diff
  * of the declared sources, keyed tasks. Plain mutable state, owned by the
  * render fiber (or the test thread). It starts and stops nothing itself: it
  * plans starts and stops in [[toStart]] and [[toStop]]. The runtime's
  * [[Launcher]] starts and interrupts fibers from them; the synchronous test
  * kit only records them.
  */
private[tui4s] final class Supervisor[S]:
    /** Label -> standing source (running or done), in declaration order. */
    private val standing = new java.util.LinkedHashMap[String, Running[S]]()

    /** Task id -> the latest task with that id. */
    private val keyed = new java.util.HashMap[String, Running[S]]()

    /** Token -> the generation that may still deliver values. */
    private val liveMap = new java.util.HashMap[Long, Running[S]]()

    private var nextToken    = 1L
    private var liveStanding = 0
    private var liveTasks    = 0

    /** Generations to start, in order. */
    val toStart: ArrayBuffer[Running[S]] = ArrayBuffer.empty

    /** Generations to stop. */
    val toStop: ArrayBuffer[Running[S]] = ArrayBuffer.empty

    private def fresh(source: Source[S], isStanding: Boolean): Running[S] =
        val r = Running[S](nextToken, source, isStanding)
        nextToken += 1
        discard(liveMap.put(r.token, r))
        if isStanding then liveStanding += 1 else liveTasks += 1
        toStart += r
        r

    private def stop(r: Running[S]): Unit =
      if liveMap.remove(r.token) != null then
          if r.standing then liveStanding -= 1 else liveTasks -= 1
          r.stopped = true
          toStop += r

    /** After a change: diff the declared sources by label (SPEC 2.13 rule 6).
      * Duplicate labels get `#1`, `#2` suffixes. Missing labels stop, new
      * labels start, kept labels take the newest declaration. The declared
      * order is the order of `wanted`.
      */
    def sync(wanted: Seq[Source[S]]): Unit =
        val labelled = Supervisor.suffixed(wanted)
        if standing.size > 0 then
            val keep = new java.util.HashSet[String](labelled.size * 2)
            labelled.foreach(s => discard(keep.add(s.label)))
            val it   = standing.values().iterator()
            while it.hasNext do
                val r = it.next()
                if !keep.contains(r.source.label) then
                    it.remove()
                    stop(r)
        end if
        labelled.foreach { s =>
          val r = standing.get(s.label)
          if r != null then r.source = s
          else discard(standing.put(s.label, fresh(s, isStanding = true)))
        }
        // A kept label stays where it was and a new one comes last: put them
        // in the declared order again when that order changed.
        val keys     = standing.keySet().iterator()
        val inOrder  =
          labelled.forall(s => keys.hasNext && keys.next() == s.label)
        if !inOrder then
            val ordered = new java.util.LinkedHashMap[String, Running[S]]()
            labelled.foreach(s =>
              discard(ordered.put(s.label, standing.get(s.label))),
            )
            standing.clear()
            standing.putAll(ordered)
    end sync

    /** One-shot tasks returned with a transition, in order (SPEC 2.13 rule 9).
      * A task with an id stops the running task with the same id.
      */
    def start(tasks: Iterable[Source[S]]): Unit =
      tasks.foreach { t =>
        if t.keyed then
            val old = keyed.get(t.label)
            if old != null then stop(old)
        val r = fresh(t, isStanding = false)
        if t.keyed then discard(keyed.put(t.label, r))
      }

    /** The generation `token`, if it may still deliver values. */
    def live(token: Long): Running[S] | Null = liveMap.get(token)

    /** The generation `token` ended. A standing source stays declared (and
      * done) until its label disappears.
      */
    def finished(token: Long): Unit =
        val r = liveMap.remove(token)
        if r != null then
            r.done = true
            if r.standing then liveStanding -= 1
            else
                liveTasks -= 1
                if r.source.keyed && (keyed.get(r.source.label) eq r) then
                    discard(keyed.remove(r.source.label))
    end finished

    /** The labels of the declared standing sources, in order. */
    def declared: Seq[String] =
        val b  = Vector.newBuilder[String]
        val it = standing.keySet().iterator()
        while it.hasNext do b += it.next()
        b.result()

    /** A generation by label, for `TuiTest.emit` and `fail`: the latest running
      * task first, then a running standing source.
      */
    def byLabel(label: String): Running[S] | Null =
        var found: Running[S] | Null = null
        val it                       = liveMap.values().iterator()
        while it.hasNext do
            val r = it.next()
            if !r.standing && r.source.label == label &&
                (found == null || r.token > found.token)
            then found = r
        if found != null then found
        else
            val s = standing.get(label)
            if s != null && !s.done then s else null
    end byLabel

    /** The labels of the generations that run now: standing sources in
      * declaration order, then tasks in start order.
      */
    def runningLabels: Seq[String] =
        val b     = Vector.newBuilder[String]
        val it    = standing.values().iterator()
        while it.hasNext do
            val r = it.next()
            if !r.done then b += r.source.label
        val tasks = ArrayBuffer.empty[Running[S]]
        liveMap.values().forEach(r => if !r.standing then tasks += r)
        tasks.sortInPlaceBy(_.token).foreach(r => b += r.source.label)
        b.result()
    end runningLabels

    /** Standing sources that run now. */
    def sourceCount: Int = liveStanding

    /** One-shot tasks that run now. */
    def taskCount: Int = liveTasks

    /** `task` bodies (standing or one-shot) that run now. */
    def busyTasks: Int =
        var n  = 0
        val it = liveMap.values().iterator()
        while it.hasNext do
            if it.next().source.kind == Source.Kind.Task then n += 1
        n

    /** Periodic and stream bodies that run now: the sleepers of the live kit. */
    def sleepers: Int =
        var n  = 0
        val it = liveMap.values().iterator()
        while it.hasNext do
            val k = it.next().source.kind
            if k == Source.Kind.Periodic || k == Source.Kind.Chunked then n += 1
        n

    /** True when no generation runs and no id is held (declared standing
      * sources that are done may stay).
      */
    def idle: Boolean = liveMap.isEmpty && keyed.isEmpty

    /** Stops every generation and forgets every declaration (the app ends). */
    def stopEverything(): Unit =
        val it = liveMap.values().iterator()
        while it.hasNext do
            val r = it.next()
            r.stopped = true
            toStop += r
        liveMap.clear()
        standing.clear()
        keyed.clear()
        liveStanding = 0
        liveTasks = 0
    end stopEverything
end Supervisor

private[tui4s] object Supervisor:
    /** `wanted` with `#1`, `#2`, ... added to the second, third, ... source
      * with the same label. A list without duplicates is returned as it is.
      */
    def suffixed[S](wanted: Seq[Source[S]]): Seq[Source[S]] =
        val seen = new java.util.HashMap[String, Integer]()
        var dup  = false
        wanted.foreach { s =>
          val n = seen.get(s.label)
          if n == null then discard(seen.put(s.label, 1))
          else
              dup = true
              discard(seen.put(s.label, n + 1))
        }
        if !dup then wanted
        else
            seen.clear()
            wanted.map { s =>
              val n = seen.get(s.label)
              if n == null then
                  discard(seen.put(s.label, 1))
                  s
              else
                  discard(seen.put(s.label, n + 1))
                  s.id(s"${s.label}#$n")
            }
        end if
    end suffixed
end Supervisor

/** Starts and interrupts the fibers that the [[Supervisor]] planned (runtime
  * only), on the render fiber, after the step that planned them.
  */
private[tui4s] final class Launcher[S](
    sup: Supervisor[S],
    inbox: Channel[Item],
):

    /** Interrupts what stopped, then starts what is new, in order. */
    def launch(using Frame): Unit < Sync =
      Sync.Unsafe
        .defer {
          interruptPlanned()
          if sup.toStart.isEmpty then Chunk.empty[Running[S]]
          else
              val starts = Chunk.from(sup.toStart)
              sup.toStart.clear()
              starts
        }
        .map(starts => Kyo.foreachDiscard(starts)(startOne))

    /** Interrupts the planned stops (signalled, not awaited). */
    private def interruptPlanned()(using AllowUnsafe, Frame): Unit =
      if sup.toStop.nonEmpty then
          sup.toStop.foreach { r =>
            val f = r.fiber
            if f != null then discard(f.unsafe.interrupt())
          }
          sup.toStop.clear()

    /** `Fiber.initUnscoped` keeps the Kyo locals of the render fiber (a time
      * control in tests, the facades' ambient runtimes). A scoped `Fiber.init`
      * would add one finalizer per start to a scope that lives as long as the
      * app.
      *
      * The start is the acquire of a bracket: its release is owed as the fiber
      * exists. An interrupt of the render fiber before the fiber is recorded in
      * `r` interrupts it there (nothing else could: [[stopAll]] finds no fiber
      * in `r`).
      */
    private def startOne(r: Running[S])(using Frame): Unit < Sync =
      if r.stopped then Kyo.unit
      else
          Sync.acquireReleaseWith(
            Fiber.initUnscoped(r.source.body(TokenEmitter(inbox, r.token))),
          )(fiber =>
            Sync.Unsafe.defer(
              if r.fiber == null then discard(fiber.unsafe.interrupt()),
            ),
          ) { fiber =>
            Sync.Unsafe.defer {
              r.fiber = fiber
              fiber.unsafe.onComplete { result =>
                discard(inbox.unsafe.putFiber(Done(r.token, result)))
              }
            }
          }

    /** Interrupts every fiber and forgets every declaration. */
    def stopAll(using Frame): Unit < Sync =
      Sync.Unsafe.defer {
        sup.stopEverything()
        interruptPlanned()
        sup.toStart.clear()
      }
end Launcher

/** The emitter of one generation: each value carries the token, so a value of a
  * stopped generation is dropped by the fold. After the run ended (the inbox is
  * closed) an emit ends the body with a panic, so a body that escaped its
  * interrupt stops at its next value.
  */
private[tui4s] final class TokenEmitter(inbox: Channel[Item], token: Long)
    extends tui4s.spi.Emitter[Any]:
    def apply(value: Any)(using Frame): Unit < Async =
      Abort.run[Closed](inbox.put(Fold(token, value))).map {
        case Result.Success(_) => Kyo.unit
        case Result.Failure(c) => Abort.panic(c)
        case Result.Panic(t)   => Abort.panic(t)
      }

    val unsafe: tui4s.spi.Emitter.Unsafe[Any] =
      new tui4s.spi.Emitter.Unsafe[Any]:
          def put(value: Any, accepted: () => Unit)(using AllowUnsafe): Unit =
            inbox.unsafe
              .putFiber(Fold(token, value))
              .onComplete(_ => accepted())
end TokenEmitter
