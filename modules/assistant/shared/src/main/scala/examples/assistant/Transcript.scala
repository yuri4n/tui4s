package examples.assistant

import kyo.*
import tui4s.widgets.Cells

/** The fold of a streamed reply: each [[ChatEvent]] changes the message of the
  * reply. A pure state machine, with these laws (`DomainPropertyTest`):
  *
  *   - Before a stop, the text is the deltas joined in order, and the status
  *     stays [[MessageStatus.Streaming]].
  *   - [[ChatEvent.Stop]] ends the message: the status is
  *     [[MessageStatus.Done]], the usage is the usage of the stop, and a later
  *     event changes nothing.
  *   - [[ChatEvent.Error]] ends the message as [[MessageStatus.Failed]] and
  *     keeps the text before it; a later event changes nothing.
  *
  * {{{
  * events.foldLeft(Transcript.start(now))(Transcript.apply)
  * }}}
  */
// claude 2026-10-19: created (L3)
object Transcript:
    /** The message of a reply that starts at `at`: empty, streaming. */
    def start(at: Instant): Message =
      Message(Role.Assistant, "", at, MessageStatus.Streaming)

    // region: transcript
    /** The message after `event`. */
    def apply(message: Message, event: ChatEvent): Message =
      message.status match
          case MessageStatus.Streaming =>
            event match
                case ChatEvent.TextDelta(t) =>
                  message.copy(content = message.content + t)
                case ChatEvent.Stop(_, u)   =>
                  message.copy(status = MessageStatus.Done, usage = u)
                case ChatEvent.Error(e)     =>
                  message.copy(status = MessageStatus.Failed(e))
          case _                       => message
    // endregion: transcript

    /** The conversation after `event`: the fold of its last message, when that
      * message is a streaming reply; else the conversation as it is.
      */
    def reply(c: Conversation, event: ChatEvent): Conversation =
      c.messages.lastOption match
          case Some(m)
              if m.role == Role.Assistant && m.status == MessageStatus.Streaming =>
            c.copy(messages = c.messages.dropRight(1) :+ apply(m, event))
          case _ => c
end Transcript

/** The title rule: the title of a conversation is its first message of the
  * person, on one line (each run of white space is one space), cut to
  * [[Title.cells]] terminal cells with the "…" of the widgets
  * (`tui4s.widgets.Cells.cut`). A conversation with no such text is
  * [[Title.empty]]. The laws (`DomainPropertyTest`): the title fits in the
  * cells; it has no line break; a text that fits is its own title; else the
  * title is a start of the text and "…".
  */
// claude 2026-10-19: created (L3)
object Title:
    /** The title of a conversation with no message. */
    val empty: String = "New chat"

    /** The cells of a title: the width of the list of conversations. */
    val cells: Int = 22

    /** The title of the text `text`, in `max` cells. */
    def of(text: String, max: Int = cells): String =
        val line = text.split("\\s+").nn.filter(_.nn.nonEmpty).mkString(" ")
        if line.isEmpty then empty else Cells.cut(line, max)

    /** The title of `c` by the rule: from its first message of the person. */
    def of(c: Conversation): String =
      c.messages.find(_.role == Role.User).fold(empty)(m => of(m.content))
end Title
