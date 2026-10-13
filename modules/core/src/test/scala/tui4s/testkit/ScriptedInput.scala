package tui4s.testkit

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import tui.crossterm.Event as CEvent
import tui.crossterm.KeyCode
import tui.crossterm.KeyEvent
import tui.crossterm.KeyEventKind
import tui.crossterm.KeyEventState
import tui.crossterm.KeyModifiers
import tui4s.internal.InputPort

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

    /** The next event; `null` when none is queued. */
    def read(): CEvent | Null =
        val p = peeked
        peeked = null
        if p != null then p else queue.poll()
end ScriptedInput

/** Builds scripted input and crossterm key events. */
// claude 2026-10-13: changed (T1): crossterm events; `key` builds one
private[tui4s] object ScriptedInput:
    /** Input that sends `events` first; a `Char` is the key a terminal sends
      * for it ('\n' and '\r' are Enter, '\t' is Tab).
      */
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

    /** The key event of a terminal for the character `c`. */
    def typed(c: Char): CEvent =
      if c == '\n' || c == '\r' then key(KeyCode.Enter())
      else if c == '\t' then key(KeyCode.Tab())
      else key(KeyCode.Char(c))
end ScriptedInput
