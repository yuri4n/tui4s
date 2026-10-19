package tui4s.use

import kyo.*

/** The text form of a value for a [[Storage]]: `encode`, and a `decode` that
  * can fail with a reason. The law: `decode(encode(a))` is `Success(a)` (for a
  * `Double`, the same bits). The module has instances for `String`, `Int`,
  * `Long`, `Double` and `Boolean`. The text of a `String` is the bare text,
  * with no quotes: that is why this small codec exists next to Kyo's `Json`.
  * For a structured value, give an instance over kyo-schema's `Json` and
  * `Schema` (kyo-ui brings them).
  */
// claude 2026-10-19: created (L2a); changed: why it exists next to Kyo's
// Json (L2a review)
trait TextCodec[A]:
    /** The text of `a`. */
    def encode(a: A): String

    /** The value of `text`, or the reason why it is not one. */
    def decode(text: String): Result[String, A]

/** The codecs of the plain types, and [[TextCodec.instance]] for another. */
// claude 2026-10-19: created (L2a); changed: Scaladoc (L2a review)
object TextCodec:
    /** A codec from two functions.
      *
      * {{{
      * given TextCodec[Edge] = TextCodec.instance(
      *   _.toString,
      *   t => Edge.values.find(_.toString == t).fold(Result.fail(s"not an Edge: $t"))(Result.succeed),
      * )
      * }}}
      */
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

    /** The text of a `Double` (`toString`; "NaN", "Infinity"). Negative zero is
      * "-0.0" on every platform: Scala.js gives "0" for it.
      */
    // claude 2026-10-19: changed: negative zero (the law found it on
    // Scala.js; L2a review)
    given double: TextCodec[Double] =
      instance(
        d => if d == 0.0 && 1.0 / d < 0.0 then "-0.0" else d.toString,
        parse("a Double", _.toDoubleOption),
      )

    /** "true" or "false". */
    given boolean: TextCodec[Boolean] =
      instance(_.toString, parse("a Boolean", _.toBooleanOption))
end TextCodec

/** A key-value store of texts: the capability of [[usePersisted]]. A failure of
  * the storage is a panic of the effect. An app takes [[Storage.forApp]], a
  * test takes [[Storage.inMemory]], and a custom store implements the two
  * methods.
  *
  * {{{
  * for
  *     storage <- Storage.forApp("todo")
  *     _       <- storage.set("theme", "dark")
  *     theme   <- storage.get("theme") // Present("dark")
  * yield theme
  * }}}
  */
// claude 2026-10-19: created (L2a)
trait Storage:
    /** The text under `key`, or Absent. */
    def get(key: String)(using Frame): Maybe[String] < Sync

    /** Writes `value` under `key`. */
    def set(key: String, value: String)(using Frame): Unit < Sync

/** The storages of the module. */
// claude 2026-10-19: created (L2a); changed: Scaladoc (L2a review)
object Storage:
    /** A storage in memory (for a test, or for state that a run need not keep).
      *
      * {{{
      * for
      *     storage <- Storage.inMemory
      *     ref     <- usePersisted("count", 0, storage)
      * yield ref
      * }}}
      */
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
      *
      * {{{
      * for storage <- Storage.forApp("todo")
      * yield usePersisted("filter", "all", storage)
      * }}}
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
                      last.get.map(prev =>
                        if prev == Present(text) then Kyo.unit
                        else write(text).andThen(last.set(Present(text))),
                      )
        // claude 2026-10-19: changed: the last write is registered first, so
        // it runs after the end of the fiber of the writes (Kyo runs the
        // finalizers in reverse order); `last` changes after a write (L2a
        // review)
        _      <- Scope.ensure(ref.get.map(save))
        _      <- Fiber.init(ref.observe(save))
    yield ref
    end for
end usePersisted
