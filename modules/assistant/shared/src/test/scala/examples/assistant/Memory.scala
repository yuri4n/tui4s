package examples.assistant

import kyo.*
import tui4s.use.Storage

import java.io.IOException

/** The interpreters in memory of the tests: the keys, the layers of the storage
  * and of the keys, a storage that cannot write, and the wait on a signal. The
  * main sources have no code that only the tests use.
  */
// claude 2026-10-19: created (L3 review): Secrets.inMemory and Wiring.inMemory
// came from the main sources
object Memory:
    /** The keys `keys`: each as from its environment variable. */
    def secrets(keys: Map[ProviderChoice, String]): Secrets = new Secrets:
        def apiKey(provider: ProviderChoice)(using
            Frame,
        ): Maybe[ApiKey] < (Sync & Abort[SecretsError]) =
          (for
              text <- Maybe.fromOption(keys.get(provider))
              name <- Secrets.variable(provider)
              key  <- ApiKey.parse(text, KeySource.Environment(name))
          yield key): Maybe[ApiKey]

    /** The layers of the storage `storage` (the files) and of the keys `keys`
      * (the environment).
      */
    def wiring(
        storage: Storage,
        keys: Map[ProviderChoice, String] = Map.empty,
    )(using Frame): (Layer[Storage, Any], Layer[Secrets, Any]) =
      (Layer(storage), Layer(secrets(keys)))

    /** A storage that reads from `base` and fails each write, as a full disk
      * does: the write throws.
      */
    def unwritable(base: Storage): Storage = new Storage:
        def get(key: String)(using Frame): Maybe[String] < Sync       = base.get(key)
        def set(key: String, value: String)(using Frame): Unit < Sync =
          Sync.defer(throw IOException("the disk is full"))

    /** The first value of `s` for which `p` holds. It waits for each change of
      * `s`; it also reads `s` again every 50 ms, because Kyo can miss the
      * wakeup of a change that lands between the read and the wait (as
      * `Signal.observe` repairs it). After `max`, it fails with `Timeout`: the
      * test fails there, not at a later assertion.
      */
    def await[A](s: Signal[A], max: Duration = 5.seconds)(p: A => Boolean)(using
        Frame,
    ): A < (Async & Abort[Timeout]) =
      Async.timeout(max)(
        Loop(()) { _ =>
          s.current.map(a =>
            if p(a) then Loop.done(a)
            else
                Abort
                  .run[Timeout](Async.timeout(50.millis)(s.next))
                  .andThen(Loop.continue(())),
          )
        },
      )
end Memory

/** A scripted provider that the test drives: each piece of a reply waits for
  * one release of the test ([[release]]). A test takes a frame between two
  * pieces, or stops a reply before its end, with no clock and no race.
  *
  * {{{
  * gate     <- PieceGate.init
  * provider  = gate.provider()
  * _        <- gate.release(3) // three pieces go
  * }}}
  */
// claude 2026-10-19: created (L3 review)
final class PieceGate private (permits: Channel[Unit]):
    /** Lets `n` more pieces go. */
    def release(n: Int)(using Frame): Unit < Async =
      Kyo.foreachDiscard(1 to n)(_ => Abort.run[Closed](permits.put(())).unit)

    /** The provider of `script`: a piece waits for a release. */
    def provider(script: FakeProvider.Script = FakeProvider.demo)(using
        Frame,
    ): ChatProvider =
      FakeProvider.paced(script, Abort.run[Closed](permits.take).unit)
end PieceGate

/** Makes a [[PieceGate]]. */
// claude 2026-10-19: created (L3 review)
object PieceGate:
    /** A gate with no release yet. */
    def init(using Frame): PieceGate < Sync =
      Channel.initUnscoped[Unit](4096).map(PieceGate(_))

    /** A script of `n` lines: `line 1` to `line n`, one piece each. */
    def lines(n: Int): FakeProvider.Script = _ =>
      FakeProvider.Reply(
        Chunk.from((1 to n).map(i => s"line $i\n")),
        FakeProvider.Ending.Stop(StopReason.EndTurn),
      )
end PieceGate
