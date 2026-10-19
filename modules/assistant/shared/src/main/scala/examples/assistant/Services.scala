package examples.assistant

import kyo.*
import tui4s.use.{Storage, TextCodec}

/** A request to a provider: the messages of the conversation (the oldest
  * first), the model, the temperature (Absent: the default of the provider) and
  * the system prompt.
  */
// claude 2026-10-19: created (L3)
final case class ChatRequest(
    messages: Chunk[Message],
    model: String,
    temperature: Maybe[Double],
    system: String,
) derives CanEqual

/** Makes the request of a conversation. */
// claude 2026-10-19: created (L3)
object ChatRequest:
    /** The request of `c` with the settings `s`: the messages that have text
      * and do not stream (a failed reply keeps its text; an empty one goes).
      */
    def of(c: Conversation, s: Settings): ChatRequest =
      ChatRequest(
        c.messages.filter(m =>
          m.status != MessageStatus.Streaming && m.content.trim.nonEmpty,
        ),
        s.model,
        s.temperature,
        s.systemPrompt,
      )
end ChatRequest

// region: provider
/** A chat provider: the capability to stream the reply to a request. The stream
  * gives the pieces of the text, then one [[ChatEvent.Stop]] or one
  * [[ChatEvent.Error]]; or it fails with a [[ChatError]] (an HTTP status, no
  * connection). An interrupt of the fiber that runs it stops it, and its
  * connection closes with it.
  *
  * The interpreters: [[FakeProvider]] (shared, scripted), and on the JVM the
  * Anthropic and OpenAI providers ([[SseProvider]] over the HTTP client of
  * kyo-http).
  */
// claude 2026-10-19: created (L3)
trait ChatProvider:
    /** The events of the reply to `request`. */
    def stream(request: ChatRequest)(using
        Frame,
    ): Stream[ChatEvent, Async & Abort[ChatError]]
// endregion: provider

/** Where a key came from: the app shows the source, never the key. */
// claude 2026-10-19: created (L3)
enum KeySource derives CanEqual:
    /** The environment variable `name`. */
    case Environment(name: String)

    /** The key file at `path`. */
    case File(path: String)

    /** The text of the source for the person. */
    def describe: String = this match
        case Environment(n) => s"from $$$n"
        case File(p)        => s"from the file $p"
end KeySource

/** An API key and its source. Its `toString` never shows the key, so a log
  * line, an error or a screen that prints it shows `ApiKey(<redacted>)`. Only
  * the HTTP interpreter reads the key ([[ApiKey.reveal]]), to write the header
  * of a request.
  *
  * A class, not an opaque type: an opaque type over `String` would have the
  * `toString` of the key itself.
  */
// claude 2026-10-19: created (L3)
final class ApiKey private (secret: String, val source: KeySource):
    /** The key itself: only for the header of a request. */
    def reveal: String = secret

    override def toString: String = "ApiKey(<redacted>)"

/** Makes an [[ApiKey]]. */
// claude 2026-10-19: created (L3)
object ApiKey:
    /** The key of `text` (with no white space at its ends), when it is one: a
      * text of 1 to 512 visible ASCII characters.
      */
    def parse(text: String, source: KeySource): Maybe[ApiKey] =
        val t = text.trim
        Maybe.when(
          t.nonEmpty && t.length <= 512 && t.forall(c => c > ' ' && c < 127),
        )(ApiKey(t, source))
end ApiKey

/** Why the keys cannot be read: an error that names the fix. */
// claude 2026-10-19: created (L3)
enum SecretsError derives CanEqual:
    /** The key file can be read by others than its owner. */
    case OpenToOthers(path: String, mode: String)

    /** The key file exists, but it cannot be read. */
    case Unreadable(path: String, reason: String)

    /** The text for the person, with the fix. */
    def describe: String = this match
        case OpenToOthers(p, m) =>
          s"the key file $p has the mode $m: others can read it, so the app does not. Run: chmod 600 $p"
        case Unreadable(p, r)   => s"the key file $p cannot be read: $r"
end SecretsError

/** The capability to find the API key of a provider. The JVM interpreter
  * (`SecretsJvm`) reads the environment variable of the provider, else the key
  * file of the app, only when the file is for its owner only (mode 0600). No
  * interpreter reads any other file: never the credentials of another tool.
  */
// claude 2026-10-19: created (L3)
trait Secrets:
    /** The key of `provider`; Absent when there is none (the fake needs none). */
    def apiKey(provider: ProviderChoice)(using
        Frame,
    ): Maybe[ApiKey] < (Sync & Abort[SecretsError])

/** The environment variables of the keys. The interpreters are on each platform
  * (`SecretsJvm`), and in memory in the test sources.
  */
// claude 2026-10-19: created (L3); changed: the interpreter in memory went to the tests (L3 review)
object Secrets:
    /** The environment variable of the key of `p`; Absent for the fake. */
    def variable(p: ProviderChoice): Maybe[String] = p match
        case ProviderChoice.Anthropic => Present("ANTHROPIC_API_KEY")
        case ProviderChoice.OpenAI    => Present("OPENAI_API_KEY")
        case ProviderChoice.Fake      => Absent

/** Why a stored conversation cannot be read or written. */
// claude 2026-10-19: created (L3); changed: Unwritable (L3 review)
enum StoreError derives CanEqual:
    /** The text under `id` is not a conversation: `reason`. */
    case Unreadable(id: String, reason: String)

    /** The storage did not write the text under `id`: `reason` (a full or
      * read-only disk).
      */
    case Unwritable(id: String, reason: String)

    /** The text for the person. */
    def describe: String = this match
        case Unreadable(id, r) => s"the chat $id cannot be read: $r"
        case Unwritable(id, r) => s"the chat $id was not saved: $r"
end StoreError

/** The capability to keep the conversations: list, load, save and delete. The
  * interpreter of [[ConversationStore.make]] keeps them in a `Storage` of
  * tui4s-use (on the JVM: `FileStorage`, one file per key; in a test:
  * `Storage.inMemory`), in the text form of [[ConversationCodec]].
  */
// claude 2026-10-19: created (L3)
trait ConversationStore:
    /** The ids of the stored conversations, the newest first. */
    def list(using Frame): Chunk[ConversationId] < Sync

    /** The conversation `id`; Absent when it is not stored. */
    def load(id: ConversationId)(using
        Frame,
    ): Maybe[Conversation] < (Sync & Abort[StoreError])

    /** Stores `c` (a new one goes first in the list). */
    // claude 2026-10-19: changed: a failed write is a StoreError (L3 review)
    def save(c: Conversation)(using Frame): Unit < (Sync & Abort[StoreError])

    /** Removes the conversation `id`. */
    // claude 2026-10-19: changed: a failed write is a StoreError (L3 review)
    def delete(id: ConversationId)(using
        Frame,
    ): Unit < (Sync & Abort[StoreError])
end ConversationStore

/** The interpreter of [[ConversationStore]] over a `Storage`. */
// claude 2026-10-19: created (L3)
object ConversationStore:
    /** The key of the list of the ids. */
    val indexKey: String = "chats"

    /** The key of the conversation `id`. */
    def key(id: ConversationId): String = s"chat-${id.text}"

    /** The store over `storage`. A `Storage` has no delete: a deleted
      * conversation leaves the list and its text becomes empty. A write that
      * fails (the storage throws) is a [[StoreError.Unwritable]].
      */
    // claude 2026-10-19: changed: the writes give StoreError (L3 review)
    def make(storage: Storage): ConversationStore = new ConversationStore:
        import ConversationCodec.codec
        import IndexCodec.codec as index

        private def ids(using Frame): Chunk[ConversationId] < Sync =
          storage
            .get(indexKey)
            .map(
              _.fold(Chunk.empty[ConversationId])(t =>
                index.decode(t).getOrElse(Chunk.empty),
              ),
            )

        /** Writes `text` under `key`: a throw of the storage is a
          * [[StoreError.Unwritable]] of `id`.
          */
        private def set(id: ConversationId, key: String, text: String)(using
            Frame,
        ): Unit < (Sync & Abort[StoreError]) =
          Abort.run[Throwable](storage.set(key, text)).map {
            case Result.Success(_) => Kyo.unit
            case Result.Failure(t) =>
              Abort.fail(StoreError.Unwritable(id.text, t.toString))
            case Result.Panic(t)   =>
              Abort.fail(StoreError.Unwritable(id.text, t.toString))
          }

        private def write(id: ConversationId, ids: Chunk[ConversationId])(using
            Frame,
        ) =
          set(id, indexKey, index.encode(ids))

        def list(using Frame): Chunk[ConversationId] < Sync = ids

        def load(id: ConversationId)(using
            Frame,
        ): Maybe[Conversation] < (Sync & Abort[StoreError]) =
          storage.get(key(id)).map {
            case Present(t) if t.nonEmpty =>
              summon[TextCodec[Conversation]].decode(t) match
                  case Result.Success(c) => Present(c)
                  case other             =>
                    Abort.fail(StoreError.Unreadable(id.text, other.toString))
            case _                        => Absent
          }

        def save(c: Conversation)(using
            Frame,
        ): Unit < (Sync & Abort[StoreError]) =
          set(c.id, key(c.id), summon[TextCodec[Conversation]].encode(c))
            .andThen(
              ids.map(xs =>
                if xs.contains(c.id) then Kyo.unit else write(c.id, c.id +: xs),
              ),
            )

        def delete(id: ConversationId)(using
            Frame,
        ): Unit < (Sync & Abort[StoreError]) =
          ids
            .map(xs => write(id, xs.filterNot(_ == id)))
            .andThen(set(id, key(id), ""))
end ConversationStore

/** The log of the app: its last lines as a signal (the log pane shows them),
  * and each line also goes to Kyo's `Log` (in tui4s: the logger of the run,
  * never the screen).
  */
// claude 2026-10-19: created (L3)
trait AppLog:
    /** The last lines, the oldest first. */
    def lines: Signal[Chunk[String]]

    /** Adds an information line. */
    def info(message: String)(using Frame): Unit < Sync

    /** Adds a warning line. */
    def warn(message: String)(using Frame): Unit < Sync
end AppLog

/** The interpreter of [[AppLog]]. */
// claude 2026-10-19: created (L3)
object AppLog:
    // region: applog
    /** A log that keeps the last `limit` lines; each line starts with its time
      * (UTC, `HH:MM:SS`) and its level.
      */
    def make(limit: Int = 500)(using Frame): AppLog < Sync =
      Signal
        .initRef(Chunk.empty[String])
        .map(ref =>
          new AppLog:
              val lines: Signal[Chunk[String]] = ref

              private def add(level: String, message: String): Unit < Sync =
                Clock.now.map(now =>
                  ref
                    .updateAndGet(ls =>
                      (ls :+ s"${now.show.slice(11, 19)} $level $message")
                        .takeRight(limit.max(1)),
                    )
                    .unit,
                )

              def info(message: String)(using Frame): Unit < Sync =
                add("info", message).andThen(Log.info(message))

              def warn(message: String)(using Frame): Unit < Sync =
                add("warn", message).andThen(Log.warn(message)),
        )
    // endregion: applog
end AppLog

/** The providers that the app can use: the fake always, and each real provider
  * whose key was found, with the source of that key.
  */
// claude 2026-10-19: created (L3)
final class Providers private (
    entries: Chunk[(ProviderChoice, ChatProvider, Maybe[KeySource])],
):
    /** The usable providers, in the order Anthropic, OpenAI, Fake. */
    def available: Chunk[ProviderChoice] = entries.map(_._1)

    /** The provider `p`, when it is usable. */
    def get(p: ProviderChoice): Maybe[ChatProvider] =
      Maybe.fromOption(entries.find(_._1 == p).map(_._2))

    /** The source of the key of `p`, when it has one. */
    def source(p: ProviderChoice): Maybe[KeySource] =
      Maybe.fromOption(entries.find(_._1 == p)).flatMap(_._3)
end Providers

/** Makes [[Providers]]. */
// claude 2026-10-19: created (L3)
object Providers:
    /** Only the fake provider `fake`. */
    def fakeOnly(fake: ChatProvider): Providers =
      Providers(Chunk((ProviderChoice.Fake, fake, Absent)))

    /** The providers whose key `secrets` finds (`live` makes each one from its
      * key), and the fake. A [[SecretsError]] goes to `log` as a warning, and
      * that provider is not usable. (With the flag `--fake`, the main takes
      * [[fakeOnly]] and reads no key.)
      */
    def make(
        secrets: Secrets,
        live: (ProviderChoice, ApiKey) => ChatProvider,
        fake: ChatProvider,
        log: AppLog,
    )(using Frame): Providers < Sync =
      Kyo
        .foreach(Chunk(ProviderChoice.Anthropic, ProviderChoice.OpenAI))(p =>
          Abort.run[SecretsError](secrets.apiKey(p)).map {
            case Result.Success(Present(k)) =>
              log
                .info(s"${p.label}: a key ${k.source.describe}")
                .andThen(Chunk((p, live(p, k), Present(k.source))))
            case Result.Success(Absent)     => Chunk.empty
            case Result.Failure(e)          =>
              log.warn(s"${p.label}: ${e.describe}").andThen(Chunk.empty)
            case Result.Panic(t)            =>
              log.warn(s"${p.label}: the key: $t").andThen(Chunk.empty)
          },
        )
        .map(found =>
          Providers(found.flatten :+ (ProviderChoice.Fake, fake, Absent)),
        )
    end make
end Providers
