package tui4s.use

import kyo.*
import kyo.test.*

/** `useAsync`, `useStreamFold` and `useStreamText` on the live clock: each test
  * waits for a state with a bound.
  */
// claude 2026-10-19: created (L2a)
class TasksTest extends Test[Any]:
    import Controlled.eventually

    "useAsync: no input is Idle; an input gives Loading, then Done" in {
      for
          input <- Signal.initRef(Maybe.empty[Int])
          gate  <- Promise.init[Unit, Any]
          state <- useAsync(input)(i => gate.get.andThen(i * 10))
          idle  <- state.current
          _     <- input.set(Present(4))
          load  <- eventually(state)(_ == AsyncState.Loading)
          _     <- gate.completeDiscard(Result.succeed(()))
          done  <- eventually(state)(_ == AsyncState.Done(40))
          _     <- input.set(Absent)
          back  <- eventually(state)(_ == AsyncState.Idle)
      yield
          assert(idle == AsyncState.Idle)
          assert(load && done && back)
    }

    "useAsync: a new input interrupts the work for the old one; the newest wins" in {
      for
          input   <- Signal.initRef(Maybe.empty[String])
          stopped <- Signal.initRef(false)
          state   <- useAsync(input)(q =>
                       if q == "slow" then
                           Sync.ensure(stopped.set(true))(
                             Async.sleep(1.hour).andThen(q),
                           )
                       else q.toUpperCase,
                     )
          _       <- input.set(Present("slow"))
          _       <- eventually(state)(_ == AsyncState.Loading)
          _       <- input.set(Present("fast"))
          fast    <- eventually(state)(_ == AsyncState.Done("FAST"))
          stop    <- eventually(stopped)(identity)
      yield assert(fast && stop)
    }

    "useAsync: a typed failure and a panic are Failed" in {
      for
          input  <- Signal.initRef(Maybe.empty[Int])
          state  <- useAsync(input)(i =>
                      if i < 0 then Abort.fail("negative")
                      else if i == 0 then
                          Abort.panic(new ArithmeticException("zero"))
                      else i,
                    )
          _      <- input.set(Present(-1))
          failed <- eventually(state) {
                      case AsyncState.Failed(Result.Failure("negative")) => true
                      case _                                             => false
                    }
          _      <- input.set(Present(0))
          panic  <- eventually(state) {
                      case AsyncState.Failed(Result.Panic(_)) => true
                      case _                                  => false
                    }
      yield assert(failed && panic)
    }

    "AsyncState.of maps a result" in {
      assert(
        AsyncState.of(Result.succeed[String, Int](1)) == AsyncState.Done(1),
      )
      assert(
        AsyncState.of(Result.fail[String, Int]("e")) ==
          AsyncState.Failed(Result.Failure("e")),
      )
    }

    // claude 2026-10-19: changed: the status Done (L2a review)
    "useStreamFold folds the stream; useStreamText joins its pieces; the end is Done" in {
      for
          sum  <- useStreamFold(Stream.init(Seq(1, 2, 3)), 0)(_ + _)
          text <- useStreamText(Stream.init(Seq("he", "ll", "o")))
          s    <- eventually(sum.value)(_ == 6)
          t    <- eventually(text.value)(_ == "hello")
          done <- eventually(text.status)(_ == StreamStatus.Done)
      yield assert(s && t && done)
    }

    // claude 2026-10-19: created (L2a review)
    "useStreamFold: a stream that fails keeps its value and is Failed; one that runs is Running" in {
      val boom = new IllegalStateException("boom")
      for
          gate    <- Promise.init[Unit, Any]
          failing <-
            useStreamFold(
              Stream
                .init(Seq(1, 2))
                .concat(Stream.init(Sync.defer[Seq[Int], Any](throw boom))),
              0,
            )(_ + _)
          open    <- useStreamFold(Stream.init(gate.get.andThen(Seq(1))), 0)(
                       _ + _,
                     )
          failed  <- eventually(failing.status)(_ == StreamStatus.Failed(boom))
          value   <- failing.value.current
          running <- open.status.current
      yield assert(failed && value == 3 && running == StreamStatus.Running)
      end for
    }
end TasksTest
