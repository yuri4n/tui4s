package tui4s.use

import kyo.*

/** The text form of a value for a [[Storage]]: `encode`, and a `decode` that
  * can fail with a reason. The law: `decode(encode(a))` is `Success(a)`. The
  * module has instances for `String`, `Int`, `Long`, `Double` and `Boolean`;
  * give one for another type, for example with a JSON library.
  */
// claude 2026-10-19: created (L2a)
trait TextCodec[A]:
    /** The text of `a`. */
    def encode(a: A): String

    /** The value of `text`, or the reason why it is not one. */
    def decode(text: String): Result[String, A]

// claude 2026-10-19: created (L2a)
object TextCodec:
    /** A codec from two functions. */
    def instance[A](
        enc: A => String,
        dec: String => Result[String, A],
    ): TextCodec[A] =
      new TextCodec[A]:
          def encode(a: A): String                    = enc(a)
          def decode(text: String): Result[String, A] = dec(text)

    private def parse[A](name: String, f: String => Option[A]) =
      (text: String) =>
        f(text).fold(Result.fail[String, A](s"not $name: $text"))(
          Result.succeed,
        )

    /** A text is its own text. */
    given string: TextCodec[String] = instance(identity, Result.succeed)

    /** The decimal text of an `Int`. */
    given int: TextCodec[Int] =
      instance(_.toString, parse("an Int", _.toIntOption))

    /** The decimal text of a `Long`. */
    given long: TextCodec[Long] =
      instance(_.toString, parse("a Long", _.toLongOption))

    /** The text of a `Double` (`toString`). */
    given double: TextCodec[Double] =
      instance(_.toString, parse("a Double", _.toDoubleOption))

    /** "true" or "false". */
    given boolean: TextCodec[Boolean] =
      instance(_.toString, parse("a Boolean", _.toBooleanOption))
end TextCodec

/** A key-value store of texts: the capability of [[usePersisted]]. A failure of
  * the storage is a panic of the effect.
  */
// claude 2026-10-19: created (L2a)
trait Storage:
    /** The text under `key`, or Absent. */
    def get(key: String)(using Frame): Maybe[String] < Sync

    /** Writes `value` under `key`. */
    def set(key: String, value: String)(using Frame): Unit < Sync

// claude 2026-10-19: created (L2a)
object Storage:
    /** A storage in memory (for a test, or for state that a run need not keep). */
    def inMemory(using Frame): Storage < Sync =
      AtomicRef
        .init(Map.empty[String, String])
        .map(texts =>
          new Storage:
              def get(key: String)(using Frame): Maybe[String] < Sync       =
                texts.get.map(m => Maybe.fromOption(m.get(key)))
              def set(key: String, value: String)(using Frame): Unit < Sync =
                texts.updateAndGet(_.updated(key, value)).unit,
        )

    /** The storage of this platform for the app `app`. On the JVM: one file for
      * each key in the data directory of the app ([[FileStorage]]). On
      * Scala.js: the `localStorage` of the browser, with the prefix `app/`
      * ([[LocalStorage]]).
      */
    def forApp(app: String)(using Frame): Storage < Sync =
      StoragePlatform.forApp(app)
end Storage

/** A `SignalRef` whose value lives in `storage` under `key`. It starts with the
  * stored value, or with `initial` when there is none or when its text does not
  * decode (a warning goes to Kyo's `Log`). Each change of the ref is written,
  * and the value at the end is written when the caller's `Scope` closes. A
  * write that fails goes to Kyo's `Log`, and the ref keeps working. The writes
  * run in a fiber that ends when the caller's `Scope` closes.
  *
  * {{{
  * for
  *     storage <- Storage.forApp("todo")
  *     theme   <- usePersisted("theme", "dark", storage)
  * yield select.value(theme)(option("dark"), option("light"))
  * }}}
  */
// claude 2026-10-19: created (L2a)
def usePersisted[A](key: String, initial: A, storage: Storage)(using
    codec: TextCodec[A],
    eq: CanEqual[A, A],
    frame: Frame,
): SignalRef[A] < (Sync & Scope) =
    def warn(what: String, why: Any): Unit < Sync =
      Log.warn(s"usePersisted: $what '$key': $why")
    def write(text: String): Unit < Sync          =
      Abort.run[Throwable](storage.set(key, text)).map {
        case Result.Success(_) => Kyo.unit
        case failed            => warn("cannot write", failed)
      }
    for
        stored <- Abort.run[Throwable](storage.get(key)).map {
                    case Result.Success(text) => text
                    case failed               =>
                      warn("cannot read", failed).andThen(Maybe.empty[String])
                  }
        start  <- (stored match
                      case Present(text) =>
                        codec.decode(text) match
                            case Result.Success(a) => a
                            case failed            =>
                              warn("cannot decode", failed).andThen(initial)
                      case Absent        => initial
                  ): A < Sync
        ref    <- Signal.initRef(start)
        last   <- AtomicRef.init(stored)
        save    = (a: A) =>
                      val text = codec.encode(a)
                      last
                        .getAndSet(Present(text))
                        .map(prev =>
                          if prev == Present(text) then Kyo.unit else write(text),
                        )
        _      <- Fiber.init(ref.observe(save))
        _      <- Scope.ensure(ref.get.map(save))
    yield ref
    end for
end usePersisted
