package examples.assistant

import kyo.*
import kyo.test.*
import tui4s.use.Storage

/** The store of the app on the interpreters in memory, wired with Kyo layers as
  * the main wires the real ones: send and the reply that streams, a failure, a
  * cancel, new, switch, rename, delete, the settings (also after a run with
  * `--fake`), a save that fails, and the conversations that a second start
  * loads. On the JVM and on Node.js.
  */
// claude 2026-10-19: created (L3); changed: the tests of the review (L3 review)
class AssistantTest extends Test[Any]:
    import AssistantTest.*

    "send: the reply streams into the conversation; the title comes from the message; the store keeps it" in {
      for
          storage <- Storage.inMemory
          seen    <- Scope.run(
                       withStoreOn(
                         storage,
                         FakeProvider.make(delay = Duration.Zero),
                       ) { a =>
                         for
                             _ <- a.composer.set("hi there")
                             _ <- a.send
                             _ <- idle(a)
                             c <- a.active.current
                             t <- a.tokens.current
                             s <- a.composer.get
                         yield (c, t, s)
                       },
                     )
          ids     <- ConversationStore.make(storage).list
      yield
          val (c, tokens, composer) = seen
          val ms                    = c.get.messages
          assert(
            composer == "" && c.get.title == "hi there" &&
              ms.map(_.role) == Chunk(Role.User, Role.Assistant) &&
              ms(1).status == MessageStatus.Done(StopReason.EndTurn) &&
              ms(1).content.startsWith("You said: \"hi there\".") &&
              tokens.output > 0 && ids == Chunk(c.get.id),
          )
    }

    "a failure of the provider marks the reply Failed and keeps its text" in {
      Scope.run(withStore(FakeProvider.make(delay = Duration.Zero)) { a =>
        for
            _ <- a.composer.set("/fail")
            _ <- a.send
            _ <- idle(a)
            c <- a.active.current
        yield
            val m = c.get.messages.last
            assert(
              m.status == MessageStatus.Failed(
                ChatError.Network("the fake provider failed on purpose"),
              ) && m.content.startsWith("This reply fails"),
            )
      })
    }

    // claude 2026-10-19: changed: a gate, not the clock (L3 review)
    "cancel stops the stream: the reply keeps its text and is marked stopped" in {
      PieceGate.init.map(gate =>
        Scope.run(withStore(gate.provider(PieceGate.lines(5))) { a =>
          for
              _ <- a.composer.set("hi")
              _ <- a.send
              _ <- gate.release(3)
              _ <- Memory.await(a.chats)(
                     _.exists(
                       _.messages.lastOption.exists(
                         _.content == "line 1\nline 2\nline 3\n",
                       ),
                     ),
                   )
              _ <- a.cancel
              c <- a.active.current
              s <- a.streaming.current
              // the negative check: the pieces that come free after the
              // cancel reach no conversation (the fiber of the reply ended)
              _ <- gate.release(2)
              _ <- Async.sleep(100.millis)
              d <- a.active.current
          yield
              val m = c.get.messages.last
              assert(
                m.status == MessageStatus.Failed(ChatError.Cancelled) &&
                  m.content == "line 1\nline 2\nline 3\n" &&
                  s == Streaming.Idle && d == c,
              )
        }),
      )
    }

    "new, switch, rename and delete" in {
      Scope.run(withStore(FakeProvider.make(delay = Duration.Zero)) { a =>
        for
            first  <- a.selection.key.current
            _      <- a.newChat
            same   <- a.selection.key.current
            _      <- a.composer.set("one")
            _      <- a.send
            _      <- idle(a)
            _      <- a.newChat
            second <- a.selection.key.current
            n      <- a.chats.current.map(_.size)
            _      <- a.nextChat
            back   <- a.selection.key.current
            _      <- a.renameActive("  a better\ntitle ")
            title  <- a.active.current.map(_.map(_.title))
            _      <- a.deleteActive
            left   <- a.chats.current
            _      <- a.deleteActive
            last   <- a.chats.current
        yield assert(
          same == first && second != first && n == 2 && back == first &&
            title == Present("a better title") && left.map(
              _.id,
            ) == second.toChunk &&
            last.size == 1 && last.head.messages.isEmpty,
        )
      })
    }

    "the settings: a provider with no key stays out; with a key it takes its default model" in {
      Scope.run(
        withStore(
          FakeProvider.make(delay = Duration.Zero),
          keys = Map(ProviderChoice.OpenAI -> "sk-o"),
        ) { a =>
          for
              start <- a.settings.current
              _     <- a.setProvider(ProviderChoice.Anthropic)
              same  <- a.settings.current
              _     <- a.setProvider(ProviderChoice.Fake)
              _     <- a.setModel("my-model")
              fake  <- a.settings.current
              lines <- a.log.lines.current
          yield assert(
            start.provider == ProviderChoice.OpenAI && start.model == Models.openai &&
              same == start && fake.provider == ProviderChoice.Fake &&
              fake.model == "my-model" &&
              lines.exists(_.contains("Anthropic has no key")) &&
              !lines.exists(_.contains("sk-o")),
          )
        },
      )
    }

    "a second start loads the stored conversations and the settings" in {
      for
          storage <- Storage.inMemory
          fake     = FakeProvider.make(delay = Duration.Zero)
          _       <- Scope.run(withStoreOn(storage, fake) { a =>
                       a.composer
                         .set("remember me")
                         .andThen(a.send)
                         .andThen(idle(a))
                         .andThen(a.setModel("kept-model"))
                     })
          seen    <- Scope.run(withStoreOn(storage, fake) { a =>
                       Kyo.zip(a.active.current, a.settings.current)
                     })
      yield
          val (c, s) = seen
          assert(
            c.map(_.title) == Present("remember me") && s.model == "kept-model",
          )
    }

    // claude 2026-10-19: created (L3 review)
    "a run with --fake keeps the stored settings: the next start takes them again" in {
      val anthropic = Map(ProviderChoice.Anthropic -> "sk-a")
      for
          storage <- Storage.inMemory
          fake     = FakeProvider.make(delay = Duration.Zero)
          _       <- Scope.run(withStoreOn(storage, fake, anthropic) { a =>
                       a.updateSettings(
                         Settings(
                           ProviderChoice.Anthropic,
                           "my-model",
                           Present(0.5),
                           "Be brief.",
                         ),
                       )
                     })
          forced  <- Scope.run(
                       withStoreOn(storage, fake, anthropic, StartMode.FakeOnly)(
                         _.settings.current,
                       ),
                     )
          next    <- Scope.run(
                       withStoreOn(storage, fake, anthropic)(_.settings.current),
                     )
      yield assert(
        forced.provider == ProviderChoice.Fake &&
          forced.systemPrompt == "Be brief." &&
          next == Settings(
            ProviderChoice.Anthropic,
            "my-model",
            Present(0.5),
            "Be brief.",
          ),
        s"$forced $next",
      )
      end for
    }

    // claude 2026-10-19: created (L3 review)
    "a save that fails is a warning in the log; the reply still ends" in {
      for
          storage <- Storage.inMemory
          seen    <- Scope.run(
                       withStoreOn(
                         Memory.unwritable(storage),
                         FakeProvider.make(delay = Duration.Zero),
                       ) { a =>
                         for
                             _     <- a.composer.set("hi")
                             _     <- a.send
                             _     <- idle(a)
                             c     <- a.active.current
                             lines <- a.log.lines.current
                         yield (c, lines)
                       },
                     )
      yield
          val (c, lines) = seen
          assert(
            c.exists(
              _.messages.last.status == MessageStatus.Done(
                StopReason.EndTurn,
              ),
            ) && lines.exists(l =>
              l.contains("was not saved") && l.contains("the disk is full"),
            ),
            lines.toString,
          )
      end for
    }

    "--fake forces the fake provider, also with a key" in {
      Scope.run(
        withStore(
          FakeProvider.make(delay = Duration.Zero),
          keys = Map(ProviderChoice.Anthropic -> "sk-a"),
          mode = StartMode.FakeOnly,
        ) { a =>
          a.settings.current.map(s =>
            assert(
              s.provider == ProviderChoice.Fake &&
                a.providers.available == Chunk(ProviderChoice.Fake),
            ),
          )
        },
      )
    }

    // claude 2026-10-19: created (L4)
    "the look: a change persists and a second start loads it; with --fake it is not stored" in {
      val fake = FakeProvider.make(delay = Duration.Zero)
      for
          storage <- Storage.inMemory
          first   <- Scope.run(
                       withStoreOn(storage, fake)(a =>
                         a.setLook(Look.Web).andThen(a.look.current),
                       ),
                     )
          second  <- Scope.run(withStoreOn(storage, fake)(_.look.current))
          other   <- Storage.inMemory
          forced  <- Scope.run(
                       withStoreOn(other, fake, mode = StartMode.FakeOnly)(a =>
                         a.setLook(Look.Web).andThen(a.look.current),
                       ),
                     )
          stored  <- other.get("look")
      yield assert(
        first == Look.Web && second == Look.Web && forced == Look.Web &&
          stored == Absent,
      )
      end for
    }
end AssistantTest

/** The wiring of a store in memory, and the wait of the tests. */
// claude 2026-10-19: created (L3); changed: Memory.wiring, the wait (L3 review)
object AssistantTest:
    /** The effects of a test of the store: a wait can time out. */
    // claude 2026-10-19: created (L3 review)
    type Effects = Async & Scope & Abort[Timeout]

    /** `f` with a store on `storage`, the provider `fake`, and `keys`; the
      * layers as the main wires them, with the interpreters in memory.
      */
    // claude 2026-10-19: changed: a wait can time out (L3 review)
    def withStoreOn[A](
        storage: Storage,
        fake: ChatProvider,
        keys: Map[ProviderChoice, String] = Map.empty,
        mode: StartMode = StartMode.Detect,
    )(f: Assistant => A < Effects)(using Frame): A < Effects =
        val (files, secrets) = Memory.wiring(storage, keys)
        Memo.run(
          Env.runLayer(
            files,
            secrets,
            Wiring.conversations,
            Wiring.log,
            Wiring.providers((_, _) => fake, fake, mode),
          )(Assistant.make(mode).map(f)),
        )
    end withStoreOn

    /** `f` with a store on a new storage in memory. */
    // claude 2026-10-19: changed: a wait can time out (L3 review)
    def withStore[A](
        fake: ChatProvider,
        keys: Map[ProviderChoice, String] = Map.empty,
        mode: StartMode = StartMode.Detect,
    )(f: Assistant => A < Effects)(using Frame): A < Effects =
      Storage.inMemory.map(withStoreOn(_, fake, keys, mode)(f))

    /** Waits until no reply streams; fails after 5 s. */
    // claude 2026-10-19: changed: a wait on the signal that fails (L3 review)
    def idle(a: Assistant)(using Frame): Unit < (Async & Abort[Timeout]) =
      Memory.await(a.streaming)(_ == Streaming.Idle).unit
end AssistantTest
