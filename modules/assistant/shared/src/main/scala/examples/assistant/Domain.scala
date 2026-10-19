package examples.assistant

import kyo.*

/** The id of a conversation: 1 to 64 letters, digits or "-". It is also the
  * storage key of the conversation, so it never holds a "/" or a space.
  */
// claude 2026-10-19: created (L3)
opaque type ConversationId = String

/** Makes and reads a [[ConversationId]]. */
// claude 2026-10-19: created (L3)
object ConversationId:
    /** The id of `text`, when it is one: 1 to 64 letters, digits or "-". */
    def parse(text: String): Maybe[ConversationId] =
      Maybe.when(
        text.nonEmpty && text.length <= 64 &&
          text.forall(c => c.isLetterOrDigit && c < 128 || c == '-'),
      )(text)

    /** A new random id of 12 letters and digits (Kyo's `Random`). */
    def fresh(using Frame): ConversationId < Sync =
      Random.nextStringAlphanumeric(12)

    extension (id: ConversationId)
        /** The text of the id. */
        def text: String = id

    given CanEqual[ConversationId, ConversationId] = CanEqual.derived
end ConversationId

/** Who wrote a message. */
// claude 2026-10-19: created (L3)
enum Role derives CanEqual:
    /** The person at the keyboard. */
    case User

    /** The model. */
    case Assistant

/** The tokens of one reply: the tokens of the request (`input`) and of the
  * reply (`output`), as the provider counts them.
  */
// claude 2026-10-19: created (L3)
final case class Usage(input: Int, output: Int) derives CanEqual:
    /** The tokens of both. */
    def total: Int = input + output

    /** The sum of two counts. */
    def +(other: Usage): Usage =
      Usage(input + other.input, output + other.output)

/** The count of no tokens. */
// claude 2026-10-19: created (L3)
object Usage:
    /** No tokens. */
    val zero: Usage = Usage(0, 0)

/** Why a chat with a provider failed: an error is a value (Kyo
  * `Abort[ChatError]`), never an exception. The HTTP status of a reply gives
  * the first four ([[HttpFailure.toChatError]]).
  */
// claude 2026-10-19: created (L3)
enum ChatError derives CanEqual:
    /** The provider refused the key (HTTP 401 or 403), or there is no key. */
    case Auth

    /** Too many requests (HTTP 429): try again after `retryAfter`, when the
      * provider gave it.
      */
    case RateLimited(retryAfter: Maybe[Duration])

    /** The network or the provider failed (no connection, a cut stream, HTTP
      * 5xx, an overloaded provider): a retry can work.
      */
    case Network(message: String)

    /** The provider did not accept the request, or its reply was not what the
      * protocol says (HTTP 400, a bad event): a retry does not help.
      */
    case Protocol(message: String)

    /** The person stopped the reply. */
    case Cancelled

    /** The text of the error for the person. */
    def describe: String = this match
        case Auth            =>
          "the provider refused the API key, or there is no key (see the README)"
        case RateLimited(at) =>
          "too many requests" + at.fold("")(d =>
            s": try again in ${d.toSeconds} s",
          )
        case Network(m)      => s"network: $m"
        case Protocol(m)     => s"protocol: $m"
        case Cancelled       => "stopped"
end ChatError

/** Where a message is: it streams, it is done, or it failed. */
// claude 2026-10-19: created (L3)
enum MessageStatus derives CanEqual:
    /** The reply comes in pieces: more text can come. */
    case Streaming

    /** The message is complete. */
    case Done

    /** The reply failed with `error`; the message keeps the text before it. */
    case Failed(error: ChatError)
end MessageStatus

/** One message of a conversation: who wrote it, its text, when it started, its
  * status and the tokens of its reply (zero for a message of the person).
  */
// claude 2026-10-19: created (L3)
final case class Message(
    role: Role,
    content: String,
    at: Instant,
    status: MessageStatus,
    usage: Usage = Usage.zero,
) derives CanEqual

/** A conversation: its id, its title, its messages (the oldest first) and the
  * time it was made.
  */
// claude 2026-10-19: created (L3)
final case class Conversation(
    id: ConversationId,
    title: String,
    messages: Chunk[Message],
    created: Instant,
) derives CanEqual:
    /** The tokens of all its replies. */
    def usage: Usage = messages.foldLeft(Usage.zero)(_ + _.usage)

    /** True when a reply of this conversation streams. */
    def streaming: Boolean =
      messages.lastOption.exists(_.status == MessageStatus.Streaming)
end Conversation

/** Makes a [[Conversation]]. */
// claude 2026-10-19: created (L3)
object Conversation:
    /** An empty conversation with the title of [[Title.empty]]. */
    def empty(id: ConversationId, at: Instant): Conversation =
      Conversation(id, Title.empty, Chunk.empty, at)

/** Why a reply stopped, in the words of the app. */
// claude 2026-10-19: created (L3)
enum StopReason derives CanEqual:
    /** The model ended its turn. */
    case EndTurn

    /** The reply reached the token limit. */
    case MaxTokens

    /** The model declined to answer (a safety rule). */
    case Refusal

    /** Another reason, with the name that the provider gave. */
    case Other(name: String)
end StopReason

/** Gives the [[StopReason]] of a name of a provider. */
// claude 2026-10-19: created (L3)
object StopReason:
    /** The reason of `name`: Anthropic's `end_turn`, `stop_sequence`,
      * `max_tokens`, `refusal`, and OpenAI's `stop`, `length`,
      * `content_filter`.
      */
    def of(name: String): StopReason = name match
        case "end_turn" | "stop_sequence" | "stop" => EndTurn
        case "max_tokens" | "length"               => MaxTokens
        case "refusal" | "content_filter"          => Refusal
        case other                                 => Other(other)
end StopReason

/** One event of a streamed reply: a piece of text, the stop, or an error. A
  * provider gives them in order, and [[Transcript]] folds them into the
  * message.
  */
// claude 2026-10-19: created (L3)
enum ChatEvent derives CanEqual:
    /** The next piece of the text. */
    case TextDelta(text: String)

    /** The reply is complete: why, and its tokens. */
    case Stop(reason: StopReason, usage: Usage)

    /** The reply failed after the pieces before it. */
    case Error(error: ChatError)
end ChatEvent
