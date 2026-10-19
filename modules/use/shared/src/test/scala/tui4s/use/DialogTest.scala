package tui4s.use

import kyo.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

/** `useDialog`: one open request at a time, the answer, and the flows that a
  * key handler starts.
  */
// claude 2026-10-19: created (L2a)
class DialogTest extends Test[Any]:
    import Controlled.eventually

    "open shows the request and waits; close answers it and hides it" in {
      for
          d      <- useDialog[String, Boolean]
          none   <- d.request.current
          ask    <- Fiber.init(d.open("Delete?"))
          shown  <- eventually(d.request)(_ == Present("Delete?"))
          _      <- d.close(true)
          answer <- ask.get
          after  <- d.request.current
      yield
          assert(none == Absent && shown && answer)
          assert(after == Absent)
    }

    "at most one request is open: a second open waits for its turn" in {
      for
          d      <- useDialog[Int, String]
          first  <- Fiber.init(d.open(1))
          _      <- eventually(d.request)(_ == Present(1))
          second <- Fiber.init(d.open(2))
          _      <- Async.sleep(20.millis)
          still  <- d.request.current
          _      <- d.close("one")
          a1     <- first.get
          shown2 <- eventually(d.request)(_ == Present(2))
          _      <- d.close("two")
          a2     <- second.get
      yield
          assert(still == Present(1))
          assert(a1 == "one" && shown2 && a2 == "two")
    }

    "launch starts a flow and returns at once; an interrupted open leaves no request" in {
      for
          d     <- useDialog[String, Int]
          got   <- Signal.initRef(0)
          _     <- d.launch(d.open("n?").map(got.set))
          shown <- eventually(d.request)(_ == Present("n?"))
          _     <- d.close(42)
          done  <- eventually(got)(_ == 42)
          ask   <- Fiber.init(d.open("gone"))
          _     <- eventually(d.request)(_ == Present("gone"))
          _     <- ask.interrupt
          gone  <- eventually(d.request)(_ == Absent)
      yield assert(shown && done && gone)
    }

    // claude 2026-10-19: created (L2a review): launch never waits
    "launch never waits: with 16 flows in the queue, it drops the next one" in {
      for
          d        <- useDialog[Int, Unit]
          _        <- d.launch(d.open(0))
          _        <- eventually(d.request)(_ == Present(0))
          _        <- Kyo.foreach(1 to 17)(i => d.launch(d.open(i)))
          answered <- Loop(0) { n =>
                        eventually(d.request, 500.millis)(_ == Present(n)).map(
                          ok =>
                            if ok then d.close(()).andThen(Loop.continue(n + 1))
                            else Loop.done(n),
                        )
                      }
      yield assert(answered == 17)
    }
end DialogTest

/** The law: a close with no open request does nothing. */
// claude 2026-10-19: created (L2a)
class DialogPropertyTest extends PropertyTest[Any]:

    // Each sample starts a dialog fiber and waits for its request, so the
    // suite keeps kyo-test-prop's 100 samples (the pure suites run 200).
    // claude 2026-10-19: created (L2a review)

    "close without open is a no-op" - {
      forAll(Gen.listOfN(5, Gen.int)) { answers =>
        for
            d   <- useDialog[String, Int]
            _   <- Kyo.foreach(answers)(d.close)
            req <- d.request.current
            ask <- Fiber.init(d.open("q"))
            _   <- Controlled.eventually(d.request)(_ == Present("q"))
            _   <- d.close(7)
            a   <- ask.get
        yield assert(req == Absent && a == 7)
      }
    }
end DialogPropertyTest
