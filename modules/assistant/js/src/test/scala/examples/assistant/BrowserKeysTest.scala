package examples.assistant

import kyo.*
import kyo.test.*
import tui4s.use.Storage

/** The keys of the web ([[BrowserKeys]]) and the store of the conversations,
  * over a storage in memory (Node.js has no `localStorage`): a kept key is one
  * entry and gives its provider; a forgotten key is none; each change starts
  * the app again; a key never shows in a text.
  */
// claude 2026-10-19: created (L4)
class BrowserKeysTest extends Test[Any]:

    /** The keys on `storage`; the restarts are counted in `restarts`. */
    private def keys(storage: Storage, restarts: AtomicInt)(using Frame) =
      BrowserKeys(storage, restarts.incrementAndGet.unit)

    "no key: no provider but the fake" in {
      for
          storage  <- Storage.inMemory
          restarts <- AtomicInt.init(0)
          k         = keys(storage, restarts)
          found    <- Kyo.foreach(Chunk.from(ProviderChoice.values))(p =>
                        Abort.run[SecretsError](k.apiKey(p)),
                      )
          log      <- AppLog.make()
          ps       <- Providers.make(
                        k,
                        (_, _) => FakeProvider.make(),
                        FakeProvider.make(),
                        log,
                      )
      yield assert(
        found.forall(_ == Result.Success(Absent)) &&
          ps.available == Chunk(ProviderChoice.Fake),
      )
    }

    "keep: one entry, the provider is usable, the app starts again; the key never shows" in {
      for
          storage  <- Storage.inMemory
          restarts <- AtomicInt.init(0)
          k         = keys(storage, restarts)
          key       = ApiKey.parse("sk-ant-SECRET", KeySource.Browser).get
          _        <- k.keep(ProviderChoice.Anthropic, key)
          entry    <- storage.get("key-anthropic")
          other    <- storage.get("key-openai")
          found    <- Abort.run[SecretsError](k.apiKey(ProviderChoice.Anthropic))
          n        <- restarts.get
          log      <- AppLog.make()
          ps       <- Providers.make(
                        k,
                        (_, _) => FakeProvider.make(),
                        FakeProvider.make(),
                        log,
                      )
          lines    <- log.lines.current
      yield
          val got = found.getOrElse(Absent)
          assert(
            entry == Present("sk-ant-SECRET") && other == Absent && n == 1 &&
              got.map(_.reveal) == Present("sk-ant-SECRET") &&
              got.map(_.source) == Present(KeySource.Browser) &&
              ps.available == Chunk(
                ProviderChoice.Anthropic,
                ProviderChoice.Fake,
              ) &&
              !got.toString.contains("SECRET") &&
              lines.exists(_.contains("kept in this browser")) &&
              !lines.exists(_.contains("SECRET")),
            lines.toString,
          )
      end for
    }

    "forget: the entry is empty, no key; a text that is not a key is none" in {
      for
          storage  <- Storage.inMemory
          restarts <- AtomicInt.init(0)
          k         = keys(storage, restarts)
          _        <- k.keep(
                        ProviderChoice.OpenAI,
                        ApiKey.parse("sk-o", KeySource.Browser).get,
                      )
          _        <- k.forget(ProviderChoice.OpenAI)
          entry    <- storage.get("key-openai")
          gone     <- Abort.run[SecretsError](k.apiKey(ProviderChoice.OpenAI))
          _        <- storage.set("key-anthropic", "not a key")
          bad      <- Abort.run[SecretsError](k.apiKey(ProviderChoice.Anthropic))
          _        <- k.keep(
                        ProviderChoice.Fake,
                        ApiKey.parse("x", KeySource.Browser).get,
                      )
          n        <- restarts.get
      yield assert(
        entry == Present("") && gone == Result.Success(Absent) &&
          bad == Result.Success(Absent) && n == 2,
      )
    }

    "the store of the conversations over the same storage keeps a conversation" in {
      for
          storage <- Storage.inMemory
          store    = ConversationStore.make(storage)
          id      <- ConversationId.fresh
          now     <- Clock.now
          c        = Conversation.empty(id, now).copy(title = Title.of("on the web"))
          _       <- Abort.run[StoreError](store.save(c))
          ids     <- store.list
          back    <- Abort.run[StoreError](store.load(id))
      yield assert(ids == Chunk(id) && back == Result.Success(Present(c)))
    }
end BrowserKeysTest
