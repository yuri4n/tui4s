package examples.assistant

import kyo.*
import kyo.test.*
import tui4s.use.Storage

/** The services on their interpreters in memory: the fake provider, the store
  * of the conversations over `Storage.inMemory` (and over a storage that cannot
  * write), the log, the keys in memory and the set of the providers. On the JVM
  * and on Node.js.
  */
// claude 2026-10-19: created (L3); changed: a write that fails (L3 review)
class ServicesTest extends Test[Any]:

    private val at = Instant.Epoch

    private def user(text: String) =
      Message(Role.User, text, at, MessageStatus.Done(StopReason.EndTurn))

    private def request(text: String) =
      ChatRequest(Chunk(user(text)), Models.fake, Absent, "")

    private def chat(id: String, text: String) =
      Conversation(
        ConversationId.parse(id).get,
        Title.of(text),
        Chunk(user(text)),
        at,
      )

    "the fake provider streams the pieces of its script, then a stop with the usage" in {
      val fake = FakeProvider.make(delay = Duration.Zero)
      for events <- fake.stream(request("hi")).run
      yield
          val text = events.collect { case ChatEvent.TextDelta(t) =>
            t
          }.mkString
          assert(
            text.startsWith("You said: \"hi\".") &&
              (events.last match
                  case ChatEvent.Stop(StopReason.EndTurn, u) => u.output > 0
                  case _                                     => false),
          )
      end for
    }

    "the fake provider: /fail gives its pieces, then fails with the network error" in {
      val fake = FakeProvider.make(delay = Duration.Zero)
      for
          seen <- AtomicRef.init(Chunk.empty[ChatEvent])
          r    <- Abort.run[ChatError](
                    fake
                      .stream(request("/fail"))
                      .foreach(e => seen.updateAndGet(_ :+ e)),
                  )
          es   <- seen.get
      yield assert(
        r == Result.fail(
          ChatError.Network("the fake provider failed on purpose"),
        ) &&
          es.nonEmpty && es.forall(_.isInstanceOf[ChatEvent.TextDelta]),
      )
      end for
    }

    "the fake provider pauses on Kyo's Clock: time control moves it on" in {
      val fake = FakeProvider.make(delay = 1.second)
      Clock.withTimeControl { control =>
        for
            fiber <- Fiber.init(fake.stream(request("hi")).run)
            _     <- Kyo.foreachDiscard(1 to 200)(_ =>
                       control.advance(1.second, 10.millis),
                     )
            es    <- fiber.get
        yield assert(es.lastOption.exists(_.isInstanceOf[ChatEvent.Stop]))
      }
    }

    "the store keeps the conversations: the newest first, load, delete" in {
      for
          storage <- Storage.inMemory
          store    = ConversationStore.make(storage)
          a        = chat("a1", "first")
          b        = chat("b2", "second")
          _       <- store.save(a)
          _       <- store.save(b)
          _       <- store.save(a.copy(title = "renamed"))
          ids     <- store.list
          loaded  <- Abort.run[StoreError](store.load(a.id))
          _       <- store.delete(b.id)
          after   <- store.list
          gone    <- Abort.run[StoreError](store.load(b.id))
      yield assert(
        ids == Chunk(b.id, a.id) &&
          loaded == Result.succeed(Present(a.copy(title = "renamed"))) &&
          after == Chunk(a.id) && gone == Result.succeed(Absent),
      )
    }

    "the store: a text that is not a conversation is a StoreError" in {
      for
          storage <- Storage.inMemory
          id       = ConversationId.parse("x1").get
          _       <- storage.set(ConversationStore.key(id), "not a chat")
          r       <- Abort.run[StoreError](ConversationStore.make(storage).load(id))
      yield assert(r match
          case Result.Failure(StoreError.Unreadable("x1", _)) => true
          case _                                              => false)
    }

    // claude 2026-10-19: created (L3 review)
    "the store: a write that fails is a StoreError, not a panic" in {
      for
          storage <- Storage.inMemory
          store    = ConversationStore.make(Memory.unwritable(storage))
          a        = chat("a1", "first")
          saved   <- Abort.run[StoreError](store.save(a))
          deleted <- Abort.run[StoreError](store.delete(a.id))
      yield
          def unwritable(r: Result[StoreError, Unit]) = r match
              case Result.Failure(StoreError.Unwritable("a1", why)) =>
                why.contains("the disk is full")
              case _                                                => false
          assert(unwritable(saved) && unwritable(deleted), s"$saved $deleted")
    }

    "the log keeps its last lines, each with its level" in {
      for
          log   <- AppLog.make(limit = 2)
          _     <- log.info("one")
          _     <- log.warn("two")
          _     <- log.info("three")
          lines <- log.lines.current
      yield assert(
        lines.size == 2 && lines(0).endsWith("warn two") &&
          lines(1).endsWith("info three"),
      )
    }

    "a key never shows: its toString is redacted" in {
      val key = ApiKey.parse(" sk-test-123 ", KeySource.Environment("X")).get
      assert(
        key.toString == "ApiKey(<redacted>)" && key.reveal == "sk-test-123" &&
          !s"$key".contains("sk-test"),
      )
    }

    "the providers: a found key makes its provider usable; an error is a warning that names the fix" in {
      val fake    = FakeProvider.make(delay = Duration.Zero)
      val failing = new Secrets:
          def apiKey(p: ProviderChoice)(using
              Frame,
          ): Maybe[ApiKey] < (Sync & Abort[SecretsError]) =
            p match
                case ProviderChoice.OpenAI =>
                  Abort.fail(SecretsError.OpenToOthers("/k", "rw-r--r--"))
                case other                 =>
                  Memory.secrets(Map(other -> "sk-ant-SECRET")).apiKey(other)
      for
          log   <- AppLog.make()
          ps    <- Providers.make(failing, (_, _) => fake, fake, log)
          lines <- log.lines.current
      yield assert(
        ps.available == Chunk(ProviderChoice.Anthropic, ProviderChoice.Fake) &&
          ps.source(ProviderChoice.Anthropic) ==
          Present(KeySource.Environment("ANTHROPIC_API_KEY")) &&
          lines.exists(_.contains("chmod 600 /k")) &&
          !lines.exists(_.contains("SECRET")),
      )
      end for
    }

    "the request of a conversation leaves out the streaming reply and an empty one" in {
      val c = Conversation(
        ConversationId.parse("r1").get,
        "t",
        Chunk(
          user("hi"),
          Message(
            Role.Assistant,
            "",
            at,
            MessageStatus.Failed(ChatError.Cancelled),
          ),
          user("again"),
          Transcript.start(at),
        ),
        at,
      )
      val r = ChatRequest.of(c, Settings.of(ProviderChoice.Fake))
      assert(r.messages.map(_.content) == Chunk("hi", "again"))
    }
end ServicesTest
