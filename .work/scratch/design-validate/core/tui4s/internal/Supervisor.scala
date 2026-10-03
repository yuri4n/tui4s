package tui4s.internal

import kyo.*
import scala.collection.mutable.ArrayBuffer
import tui4s.*

/** One running (or done) source or task. Owned by the render fiber. */
private[tui4s] final class Entry[S](val token: Long, var source: Source[S], val standing: Boolean):
  var fiber: Fiber[Unit, Abort[Throwable]] | Null = null
  var done: Boolean                               = false

/** Bookkeeping of sources and tasks (5.8): generation tokens, the source diff, keyed tasks. Plain mutable state on
  * the render fiber; the runtime starts and interrupts fibers from `toStart` / `toStop` (see `Launcher`), the
  * synchronous test kit only records them.
  */
private[tui4s] final class Supervisor[S]:
  private val standing  = new java.util.LinkedHashMap[String, Entry[S]]() // label -> standing source
  private val keyed     = new java.util.HashMap[String, Entry[S]]()       // task id -> latest task
  private val liveMap   = new java.util.HashMap[Long, Entry[S]]()         // token -> entry that may still deliver
  private var nextToken = 1L

  val toStart: ArrayBuffer[Entry[S]] = ArrayBuffer.empty
  val toStop: ArrayBuffer[Entry[S]]  = ArrayBuffer.empty
  val started: ArrayBuffer[String]   = ArrayBuffer.empty // one-shot task labels, in start order

  private def fresh(source: Source[S], isStanding: Boolean): Entry[S] =
    val e = new Entry[S](nextToken, source, isStanding)
    nextToken += 1
    discard(liveMap.put(e.token, e))
    toStart += e
    e

  /** After a changed batch: diff the declared sources by label. */
  def sync(wanted: Seq[Source[S]]): Unit =
    val counts = scala.collection.mutable.HashMap.empty[String, Int]
    val labelled = wanted.map { s =>
      val n = counts.getOrElse(s.label, 0)
      counts(s.label) = n + 1
      if n == 0 then s else s.id(s"${s.label}#$n")
    }
    val wantedLabels = labelled.iterator.map(_.label).toSet
    val it           = standing.entrySet().iterator()
    while it.hasNext do
      val e = it.next().getValue
      if !wantedLabels.contains(e.source.label) then
        it.remove()
        if liveMap.remove(e.token) != null then toStop += e
    labelled.foreach { s =>
      val e = standing.get(s.label)
      if e != null then e.source = s // newest fold and recover (2.13 rule 6)
      else discard(standing.put(s.label, fresh(s, isStanding = true)))
    }
  end sync

  /** One-shot tasks returned with a transition. */
  def start(tasks: Seq[Source[S]]): Unit =
    tasks.foreach { t =>
      if t.keyed then
        val old = keyed.get(t.label)
        if old != null && liveMap.remove(old.token) != null then toStop += old
      val e = fresh(t, isStanding = false)
      if t.keyed then discard(keyed.put(t.label, e))
      started += t.label
    }

  def live(token: Long): Entry[S] | Null = liveMap.get(token)

  def finished(token: Long): Unit =
    val e = liveMap.remove(token)
    if e != null then
      e.done = true
      if !e.standing && e.source.keyed && (keyed.get(e.source.label) eq e) then discard(keyed.remove(e.source.label))

  /** Labels of the standing sources declared now, in order. */
  def declared: Seq[String] =
    val b  = Seq.newBuilder[String]
    val it = standing.keySet().iterator()
    while it.hasNext do b += it.next()
    b.result()

  /** A live entry by label (test kit `emit` / `fail`): a running task first, then a standing source. */
  def byLabel(label: String): Entry[S] | Null =
    var found: Entry[S] | Null = null
    val it                     = liveMap.values().iterator()
    while it.hasNext do
      val e = it.next()
      if !e.standing && e.source.label == label then found = e
    if found != null then found
    else
      val s = standing.get(label)
      if s != null && !s.done then s else null
  end byLabel

  def liveCount: Int = liveMap.size
  def taskCount: Int =
    var n  = 0
    val it = liveMap.values().iterator()
    while it.hasNext do if !it.next().standing then n += 1
    n
  def runningPeriodic: Int =
    var n  = 0
    val it = liveMap.values().iterator()
    while it.hasNext do
      val k = it.next().source.kind
      if k == Source.Kind.Periodic || k == Source.Kind.Chunked then n += 1
    n

  /** Every live entry (stopAll). */
  def stopEverything(): Unit =
    val it = liveMap.values().iterator()
    while it.hasNext do toStop += it.next()
    liveMap.clear()
    standing.clear()
    keyed.clear()
end Supervisor

/** Starts and interrupts the fibers that the Supervisor planned (runtime only). */
private[tui4s] final class Launcher[S](sup: Supervisor[S], inbox: Channel[Item]):

  /** On the render fiber, after a batch: interrupt what stopped, start what is new. */
  def launch(using Frame): Unit < Sync =
    Sync.Unsafe.defer {
      sup.toStop.foreach { e =>
        val f = e.fiber
        if f != null then discard(f.unsafe.interrupt())
      }
      sup.toStop.clear()
      val starts = sup.toStart.toList
      sup.toStart.clear()
      starts
    }.map(starts => Kyo.foreachDiscard(starts)(startOne))

  private def startOne(e: Entry[S])(using Frame): Unit < Sync =
    Fiber.initUnscoped(e.source.body(TokenEmitter(inbox, e.token))).map { fiber =>
      Sync.Unsafe.defer {
        e.fiber = fiber
        fiber.unsafe.onComplete { r =>
          discard(inbox.unsafe.putFiber(Done(e.token, r.map(_ => ()))))
        }
      }
    }

  def stopAll(using Frame): Unit < Sync =
    Sync.Unsafe.defer {
      sup.stopEverything()
      sup.toStop.foreach { e =>
        val f = e.fiber
        if f != null then discard(f.unsafe.interrupt())
      }
      sup.toStop.clear()
      sup.toStart.clear()
    }
end Launcher

/** The emitter of one source generation: values carry the token, so a stopped source's late value is dropped. */
private[tui4s] final class TokenEmitter(inbox: Channel[Item], token: Long) extends tui4s.spi.Emitter[Any]:
  def apply(value: Any)(using Frame): Unit < Async = Abort.run[Closed](inbox.put(Fold(token, value))).unit
  val unsafe: tui4s.spi.Emitter.Unsafe[Any] = new tui4s.spi.Emitter.Unsafe[Any]:
    def put(value: Any, accepted: () => Unit)(using AllowUnsafe): Unit =
      inbox.unsafe.putFiber(Fold(token, value)).onComplete(_ => accepted())
end TokenEmitter
