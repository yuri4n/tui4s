package spike

import kyo.*
import kyo.test.*
import java.util.concurrent.atomic.AtomicInteger
import scala.compiletime.testing.typeCheckErrors

/** Native Kyo alternatives only; no proposed library implementation. */
class VarStoreTest extends Test[Any]:
    private def increment: Unit < Var[Int] =
      Var.updateDiscard[Int](_ + 1)

    private def composed: Int < Var[Int] =
      increment.andThen(increment).andThen(Var.get[Int])

    "Var handlers own computational state and can return its final value" in {
      for
          first  <- Var.runTuple(0)(composed)
          second <- Var.runTuple(10)(composed)
      yield
          assert(first == (2, 2))
          assert(second == (12, 12))
    }

    "feature actions compose and replay deterministically from explicit state" in {
      val program = CounterTransitions.incrementTwice.andThen(CounterTransitions.doubled)
      for
          first  <- Var.runTuple(CounterState(3))(program)
          replay <- Var.runTuple(CounterState(3))(program)
          other  <- Var.runTuple(CounterState(20))(program)
      yield
          assert(first == (CounterState(5), 10))
          assert(replay == first)
          assert(other == (CounterState(22), 44))
    }

    "Var transitions can be discarded on domain failure without publishing state" in {
      def failed: Unit < (Var[CounterState] & Abort[String]) =
        CounterTransitions.increment.andThen(Abort.fail("rejected"))

      for
          result <- Abort.run[String](Var.runTuple(CounterState(3))(failed))
          fresh  <- Var.runTuple(CounterState(3))(CounterTransitions.doubled)
      yield
          assert(result == Result.Failure("rejected"))
          assert(fresh == (CounterState(3), 6))
    }

    "Scope cleanup is independent of the state effect" in {
      for
          releases <- Sync.defer(AtomicInteger(0))
          result   <- Scope.run {
                        Var.runTuple(CounterState(0)) {
                          Scope.ensure(Sync.defer { val _ = releases.incrementAndGet() })
                            .andThen(CounterTransitions.increment)
                            .andThen(CounterTransitions.doubled)
                        }
                      }
          released <- Sync.defer(releases.get())
      yield
          assert(result == (CounterState(1), 2))
          assert(released == 1)
    }

    "a fresh Var handler for every UI callback resets its state" in {
      val callback: () => Int < Async = () => Var.run(0)(composed)
      for
          first  <- callback()
          second <- callback()
      yield
          assert(first == 2)
          assert(second == 2)
    }

    "pure Var logic can be an atomic reducer on a reactive SignalRef" in {
      for
          ref    <- Signal.initRef(0)
          _      <- ref.updateAndGet(current => Var.runTuple(current)(composed).eval._1)
          _      <- ref.updateAndGet(current => Var.runTuple(current)(composed).eval._1)
          result <- ref.get
      yield assert(result == 4)
    }

    "a naive async Var to SignalRef bridge loses concurrent updates" in {
      def action(
          ref: SignalRef[Int],
          ready: Promise[Unit, Any],
          go: Promise[Unit, Any],
      ): Unit < Async =
        ref.get.map { current =>
          ready.completeUnitDiscard
            .andThen(go.get)
            .andThen(Var.runTuple(current)(increment))
            .map { (next, _) => ref.set(next) }
        }

      for
          ref       <- Signal.initRef(0)
          readyOne  <- Promise.init[Unit, Any]
          readyTwo  <- Promise.init[Unit, Any]
          go        <- Promise.init[Unit, Any]
          one       <- Fiber.init(action(ref, readyOne, go))
          two       <- Fiber.init(action(ref, readyTwo, go))
          _         <- readyOne.get
          _         <- readyTwo.get
          _         <- go.completeUnitDiscard
          _         <- one.get
          _         <- two.get
          result    <- ref.get
      yield assert(result == 1)
    }

    "atomic native signal updates retain concurrent changes" in {
      for
          ref    <- Signal.initRef(0)
          go     <- Promise.init[Unit, Any]
          one    <- Fiber.init(go.get.andThen(ref.updateAndGet(_ + 1)))
          two    <- Fiber.init(go.get.andThen(ref.updateAndGet(_ + 1)))
          _      <- go.completeUnitDiscard
          _      <- one.get
          _      <- two.get
          result <- ref.get
      yield assert(result == 2)
    }

    "combined pure transactions share native reactive state under concurrent actions" in {
      for
          store  <- ReactiveTransitions.init(0)
          go     <- Promise.init[Unit, Any]
          fibers <- Kyo.foreach(1 to 32)(_ =>
                      Fiber.init(go.get.andThen(store.transact(CounterTransitions.incrementTwice))),
                    )
          _      <- go.completeUnitDiscard
          _      <- Kyo.foreach(fibers)(_.get)
          state  <- store.snapshot
          count  <- store.count.current
          twice  <- store.doubled.current
      yield
          assert(state == CounterState(64))
          assert(count == 64)
          assert(twice == 128)
    }

    "a scoped Layer shares a whole feature group and owns its setup resource" in {
      for
          releases <- Sync.defer(AtomicInteger(0))
          setups   <- Sync.defer(AtomicInteger(0))
          layer = Layer {
                    Sync.defer { val _ = setups.incrementAndGet() }.andThen {
                      Scope.ensure(Sync.defer { val _ = releases.incrementAndGet() })
                        .andThen(ReactiveTransitions.init(0))
                    }
                  }
          group <- Scope.run {
                     Memo.run {
                       Env.runLayer(layer) {
                         for
                             first   <- Env.get[ReactiveTransitions]
                             second  <- Scope.run(Env.get[ReactiveTransitions])
                             callback = (first.transact(CounterTransitions.incrementTwice): Unit < Async)
                             button   = kyo.UI.button("Increment").onClick(callback)
                             fiber   <- Fiber.init(button.attrs.onClick.get.unit)
                             _       <- fiber.get
                             value   <- second.doubled.current
                             closed  <- Sync.defer(releases.get())
                         yield
                             assert(first eq second)
                             assert(value == 4)
                             assert(closed == 0)
                             first
                       }
                     }
                   }
          created  <- Sync.defer(setups.get())
          closed   <- Sync.defer(releases.get())
          retained <- group.snapshot
      yield
          assert(created == 1)
          assert(closed == 1)
          assert(retained == CounterState(2))
    }

    "combined transactions reject async actions rather than pretend to be atomic" in {
      val errors = typeCheckErrors("""
        import kyo.*
        import spike.*
        def action: Unit < (Var[CounterState] & Async) =
          Async.sleep(1.millis).andThen(CounterTransitions.increment)
        def applyAction(store: ReactiveTransitions): Unit < Sync = store.transact(action)
      """)
      assert(errors.nonEmpty)
    }

    "the same feature definition makes independent groups in independent providers" in {
      val layer = Layer(ReactiveTransitions.init(0))
      for
          first  <- Memo.run(Env.runLayer(layer)(Env.get[ReactiveTransitions]))
          second <- Memo.run(Env.runLayer(layer)(Env.get[ReactiveTransitions]))
          _      <- first.increment
          one    <- first.snapshot
          two    <- second.snapshot
      yield
          assert(!(first eq second))
          assert(one == CounterState(1))
          assert(two == CounterState(0))
    }

    "Var must be handled before a native UI callback can execute" in {
      val errors = typeCheckErrors("""
        import kyo.*
        val callback: Unit < Var[Int] = Var.updateDiscard[Int](_ + 1)
        val button = kyo.UI.button("Increment").onClick(callback)
      """)
      assert(errors.nonEmpty)
    }
end VarStoreTest
