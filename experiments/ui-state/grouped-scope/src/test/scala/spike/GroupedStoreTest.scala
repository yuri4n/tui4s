package spike

import kyo.*
import kyo.test.*

class GroupedStoreTest extends Test[Any]:
    "one provider initializes and returns the exact same group once" in {
      for
          setups <- Signal.initRef(0)
          closes <- Signal.initRef(0)
          layer   = Layer(CounterGroup.setup(
                      setups.updateAndGet(_ + 1).unit,
                      closes.updateAndGet(_ + 1).unit,
                    ))
          result <- Scope.run(Memo.run {
                      for
                          first  <- Env.runLayer(layer)(Env.get[CounterGroup])
                          second <- Env.runLayer(layer)(Env.get[CounterGroup])
                          _      <- first.increment
                          value  <- second.count.get
                          twice  <- second.doubled.current
                          _      <- second.rename("Shared hooks")
                          title  <- first.title.get
                          runs   <- setups.get
                          stops  <- closes.get
                      yield (first, second, value, twice, title, runs, stops)
                    })
          closed <- closes.get
          done   <- result._1.watcher.done
      yield
          assert(result._1 eq result._2)
          assert(result._3 == 1 && result._4 == 2)
          assert(result._5 == "Shared hooks")
          assert(result._6 == 1 && result._7 == 0)
          assert(closed == 1 && done)
    }

    "independent app providers isolate the same group definition" in {
      for
          setups <- Signal.initRef(0)
          closes <- Signal.initRef(0)
          layer   = Layer(CounterGroup.setup(
                      setups.updateAndGet(_ + 1).unit,
                      closes.updateAndGet(_ + 1).unit,
                    ))
          first  <- Scope.run(Memo.run(
                      Env.runLayer(layer)(Env.use[CounterGroup] { group =>
                        group.increment.andThen(group)
                      }),
                    ))
          second <- Scope.run(Memo.run(
                      Env.runLayer(layer)(Env.get[CounterGroup]),
                    ))
          a      <- first.count.get
          b      <- second.count.get
          runs   <- setups.get
          stops  <- closes.get
          doneA  <- first.watcher.done
          doneB  <- second.watcher.done
      yield
          assert(!(first eq second))
          assert(a == 1 && b == 0)
          assert(runs == 2 && stops == 2)
          assert(doneA && doneB)
    }

    "component scope closure leaves the eagerly provided group alive" in {
      for
          closes  <- Signal.initRef(0)
          changed <- Promise.init[Unit, Any]
          group   <- Scope.run {
                       for
                           shared <- CounterGroup.setup(
                                       (),
                                       closes.updateAndGet(_ + 1).unit,
                                       n => if n == 2 then changed.completeUnitDiscard else (),
                                     )
                           child  <- Scope.run(
                                       Env.run(shared)(Env.use[CounterGroup] { g =>
                                         g.increment.andThen(g)
                                       }),
                                     )
                           stops  <- closes.get
                           ended  <- shared.watcher.done
                           _      <- Sync.defer {
                                       assert(child eq shared)
                                       assert(stops == 0 && !ended)
                                     }
                           _      <- shared.increment
                           _      <- changed.get
                       yield shared
                     }
          stops   <- closes.get
          ended   <- group.watcher.done
          waiters <- group.count.waiters
          seen    <- group.observations.get
          _       <- group.count.set(99)
          after   <- group.observations.get
          drained <- group.count.waiters
          _       <- Sync.defer(println(s"normal cleanup: closed=$stops done=$ended queued=$waiters afterWrite=$drained"))
      yield
          assert(stops == 1 && ended)
          assert(drained == 0)
          assert(seen.contains(2))
          assert(after == seen)
    }

    "concurrent consumers update one group atomically and derive the result" in {
      Scope.run {
        for
            group <- CounterGroup.setup((), ())
            work  <- Kyo.foreach(1 to 16)(_ =>
                       Fiber.init(Kyo.foreach(1 to 32)(_ => group.increment).unit),
                     )
            _     <- Kyo.foreach(work)(_.get)
            count <- group.count.get
            twice <- group.doubled.current
        yield
            assert(count == 512)
            assert(twice == 1024)
      }
    }

    "provider failure closes the group's resources" in {
      for
          closes <- Signal.initRef(0)
          saved  <- Signal.initRef(Option.empty[CounterGroup])
          result <- Abort.run[String](Scope.run {
                      for
                          group <- CounterGroup.setup((), closes.updateAndGet(_ + 1).unit)
                          _     <- saved.set(Some(group))
                          _     <- Abort.fail("application failed")
                      yield ()
                    })
          stops  <- closes.get
          group  <- saved.get.map(_.get)
          ended  <- group.watcher.done
          waiters <- group.count.waiters
          before <- group.observations.get
          _      <- group.count.set(99)
          after  <- group.observations.get
          drained <- group.count.waiters
          _      <- Sync.defer(println(s"failed cleanup: closed=$stops done=$ended queued=$waiters afterWrite=$drained"))
      yield
          val failed = result match
              case Result.Failure("application failed") => true
              case _ => false
          assert(failed)
          assert(stops == 1 && ended && drained == 0)
          assert(before == after)
    }

    "interrupting the app provider ends grouped background work" in {
      for
          released <- Promise.init[Unit, Any]
          started  <- Promise.init[CounterGroup, Any]
          hold     <- Promise.init[Unit, Any]
          owner    <- Fiber.init(Scope.run {
                        for
                            group <- CounterGroup.setup((), released.completeUnitDiscard)
                            _     <- started.completeDiscard(Result.succeed(group))
                            _     <- hold.get
                        yield ()
                      })
          group    <- started.get
          _        <- owner.interruptAwait
          _        <- released.get
          ended    <- group.watcher.done
          before   <- group.observations.get
          _        <- group.count.set(99)
          after    <- group.observations.get
          queued   <- group.count.waiters
      yield
          assert(ended && queued == 0)
          assert(before == after)
    }

    "a failed observer is reported by its fiber without hanging group setup" in {
      for
          error  <- Sync.defer(IllegalStateException("observer failed"))
          closes <- Signal.initRef(0)
          failed <- Scope.run {
                      for
                          group  <- CounterGroup.setup(
                                      (),
                                      closes.updateAndGet(_ + 1).unit,
                                      _ => Sync.defer(throw error),
                                    )
                          result <- Abort.run[Throwable](group.watcher.get)
                      yield result match
                          case Result.Panic(cause) => cause eq error
                          case _ => false
                    }
          stops  <- closes.get
      yield
          assert(failed)
          assert(stops == 1)
    }

    "a layer first initialized in a child scope caches an already disposed group" in {
      for
          closes <- Signal.initRef(0)
          layer   = Layer(CounterGroup.setup((), closes.updateAndGet(_ + 1).unit))
          result <- Scope.run(Memo.run {
                      for
                          child <- Scope.run(Env.runLayer(layer)(Env.get[CounterGroup]))
                          next  <- Env.runLayer(layer)(Env.get[CounterGroup])
                          stops <- closes.get
                          ended <- next.watcher.done
                      yield (child, next, stops, ended)
                    })
      yield
          assert(result._1 eq result._2)
          assert(result._3 == 1 && result._4)
    }
end GroupedStoreTest
