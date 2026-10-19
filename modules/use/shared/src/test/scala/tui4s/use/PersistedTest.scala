package tui4s.use

import kyo.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

/** `usePersisted` on an in-memory storage: the stored value, a text that does
  * not decode, the writes and the write at the end.
  */
// claude 2026-10-19: created (L2a)
class PersistedTest extends Test[Any]:

    "the ref starts with the stored value and writes each change" in {
      for
          storage <- Storage.inMemory
          _       <- storage.set("n", "41")
          seen    <- Scope.run {
                       for
                           n     <- usePersisted("n", 0, storage)
                           start <- n.get
                           _     <- n.set(42)
                           // The write runs in the fiber of the ref: a read
                           // every 5 ms, at most 2 s.
                           wrote <- Loop(0) { i =>
                                      storage
                                        .get("n")
                                        .map(t =>
                                          if t == Present("42") || i > 400 then
                                              Loop.done(t)
                                          else
                                              Async
                                                .sleep(5.millis)
                                                .andThen(Loop.continue(i + 1)),
                                        )
                                    }
                       yield (start, wrote)
                     }
      yield assert(seen == (41, Present("42")))
    }

    "a text that does not decode gives the initial value; the end of the Scope writes the last value" in {
      for
          storage <- Storage.inMemory
          _       <- storage.set("n", "not a number")
          start   <- Scope.run {
                       for
                           n <- usePersisted("n", 7, storage)
                           s <- n.get
                           _ <- n.set(8)
                       yield s
                     }
          last    <- storage.get("n")
      yield assert(start == 7 && last == Present("8"))
    }

    "no stored text gives the initial value" in {
      for
          storage <- Storage.inMemory
          flag    <- Scope.run(usePersisted("on", true, storage).map(_.get))
      yield assert(flag)
    }
end PersistedTest

/** The law of the codecs: `decode(encode(a))` is `Success(a)`. */
// claude 2026-10-19: created (L2a)
class TextCodecPropertyTest extends PropertyTest[Any]:

    private def roundTrip[A](a: A)(using c: TextCodec[A]): Boolean =
      c.decode(c.encode(a)) == Result.succeed(a)

    "decode(encode(a)) is a" - {
      forAll(Gen.int, Gen.long, Gen.string, Gen.boolean) { (i, l, s, b) =>
        assert(roundTrip(i) && roundTrip(l) && roundTrip(s) && roundTrip(b))
        assert(roundTrip(i.toDouble / 3))
      }
    }
end TextCodecPropertyTest
