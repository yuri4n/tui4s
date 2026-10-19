package tui4s.testkit

import kyo.Maybe
import tui.crossterm.{
  Event as CEvent,
  KeyCode,
  KeyEvent,
  KeyEventKind,
  KeyEventState,
  KeyModifiers,
}
import tui4s.internal.{EventMapping, InputPort}

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** Scripted crossterm input for the tests of the input thread and of a run on a
  * fake terminal: the given events, then the events that [[push]] adds, in
  * order. Like the terminal, `poll` really waits for its timeout when no event
  * is queued.
  *
  * @param events
  *   the events that are queued at the start
  */
// claude 2026-10-13: changed (T1, D-21 a): crossterm events (the input thread
// maps them); a test double in the test sources (RC-36)
private[tui4s] final class ScriptedInput(events: Seq[CEvent]) extends InputPort:
    private val queue                           = new LinkedBlockingQueue[CEvent]()
    events.foreach(push)
    @volatile private var peeked: CEvent | Null = null

    /** Queues `event` after the others. Thread-safe. */
    def push(event: CEvent): Unit = queue.put(event)

    /** Waits at most `timeoutMillis` for an event; true when one is ready. */
    def poll(timeoutMillis: Int): Boolean =
      peeked != null || {
        val e = queue.poll(timeoutMillis.max(0).toLong, TimeUnit.MILLISECONDS)
        if e == null then false
        else
            peeked = e
            true
      }

    /** The next event; `Absent` when none is queued. */
    // claude 2026-10-19: changed: Maybe[CEvent] (InputPort, P1)
    def read(): Maybe[CEvent] =
        val p                = peeked
        peeked = null
        val e: CEvent | Null = if p != null then p else queue.poll()
        Maybe(e).map(_.nn)
end ScriptedInput

/** Builds scripted input and crossterm key events. */
// claude 2026-10-13: changed (T1): crossterm events; `key` builds one
private[tui4s] object ScriptedInput:
    /** Input that sends `events` first; a `Char` is the key a terminal sends
      * for it ([[typed]]).
      */
    // claude 2026-10-13: changed (T1, review R1-1): the Scaladoc names typed
    def apply(events: (CEvent | Char)*): ScriptedInput =
      new ScriptedInput(events.map {
        case c: Char   => typed(c)
        case e: CEvent => e
      })

    /** Input with no events (until [[ScriptedInput.push]]). */
    def never: ScriptedInput = new ScriptedInput(Nil)

    /** A crossterm key event. */
    def key(
        code: KeyCode,
        mods: Int = 0,
        kind: KeyEventKind = KeyEventKind.Press,
        state: Int = 0,
    ): CEvent =
      CEvent.Key(KeyEvent(code, KeyModifiers(mods), kind, KeyEventState(state)))

    /** The key event of a terminal for the character `c`: '\n' and '\r' are
      * Enter, '\t' is Tab, and an upper-case character has Shift, as crossterm
      * gives it ([[tui4s.internal.EventMapping.typed]]).
      */
    // claude 2026-10-13: changed (T1, review R1-1): EventMapping.typed, the one
    // place for the event of a typed character
    def typed(c: Char): CEvent = CEvent.Key(EventMapping.typed(c))
end ScriptedInput
