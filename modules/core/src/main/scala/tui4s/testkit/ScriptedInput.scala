package tui4s.testkit

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import tui4s.*
import tui4s.spi.InputPort

/** Input for headless runs (`spi.Headless`, the facades' run seams,
  * benchmarks): the given events, then the events that [[push]] adds, in order.
  * Like the terminal, `poll` really waits for its timeout when no event is
  * queued. A key is sent as a terminal sends it ('\n' is `Key.Enter`).
  *
  * @param events
  *   the events that are queued at the start
  */
final class ScriptedInput(events: Seq[Event]) extends InputPort:
    private val queue                          = new LinkedBlockingQueue[Event]()
    events.foreach(push)
    @volatile private var peeked: Event | Null = null

    /** Queues `event` after the others. Thread-safe. */
    def push(event: Event): Unit = queue.put(event match
        case k: Key => Key.sent(k)
        case other  => other)

    /** Waits at most `timeoutMillis` for an event; true when one is ready. */
    def poll(timeoutMillis: Int): Boolean =
      peeked != null || {
        val e = queue.poll(timeoutMillis.max(0).toLong, TimeUnit.MILLISECONDS)
        if e == null then false
        else
            peeked = e
            true
      }

    /** The next event; `null` when none is queued. */
    def read(): Event | Null =
        val p = peeked
        peeked = null
        if p != null then p else queue.poll()
end ScriptedInput

/** Builds scripted input. */
object ScriptedInput:
    /** Input that sends `events` first; a `Char` is `Key.Char(c)`. */
    def apply(events: (Event | Char)*): ScriptedInput =
      new ScriptedInput(events.map {
        case c: Char  => Key.Char(c)
        case e: Event => e
      })

    /** Input with no events (until [[ScriptedInput.push]]). */
    def empty: ScriptedInput = new ScriptedInput(Nil)

    /** Input that never sends an event unless the test pushes one; the same as
      * [[empty]].
      */
    def never: ScriptedInput = new ScriptedInput(Nil)
end ScriptedInput
