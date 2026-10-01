package tui4s

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import jatatui.core.backend.TestBackend
import kyo.*
import kyo.test.*
import scala.jdk.CollectionConverters.*
import tui4s.fixtures.CounterApp
import tui4s.internal.CountingBackend
import tui4s.internal.Engine
import tui4s.internal.HeadlessControl
import tui4s.internal.Observer
import tui4s.internal.Runner
import tui4s.internal.discard
import tui4s.testkit.*

/** The frame contracts of SPEC 7.2 on the real loop: C4 to C7, and C9 (no batch
  * writes a `slow` line). They assert on `Stats`, not on time.
  */
class RuntimeContractTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    private def traced[S](app: App[S], file: Path): App[S] =
      App[S](
        app.init,
        app.view,
        app.update,
        app.sources,
        app.options.copy(trace = Some(file)),
      )

    private def slowLines(file: Path): Seq[String] =
      Files
        .readAllLines(file, StandardCharsets.UTF_8)
        .asScala
        .toVector
        .filter(
          _.contains("\"kind\":\"slow\""),
        )

    /** Watches the supervisor from the render fiber. */
    private final class Watcher[S] extends Observer[S]:
        @volatile var idle         = false
        @volatile var folds        = 0L
        @volatile var tasks        = 0
        def published(
            engine: Engine[S],
            terminal: jatatui.core.terminal.Terminal[CountingBackend],
            drawn: Boolean,
        )(using AllowUnsafe): Unit =
            idle = engine.sup.idle
            folds = engine.stats.folds
            tasks = engine.stats.tasks
        end published
    end Watcher

    /** Waits (real time, at most `max`) until `cond` holds. */
    private def eventually(max: Duration = 10.seconds)(
        cond: => Boolean < Sync,
    )(using Frame): Boolean < Async =
      Loop(0) { n =>
        cond.map { ok =>
          if ok then Loop.done(true)
          else if n * 10 >= max.toMillis then Loop.done(false)
          else Async.sleep(10.millis).andThen(Loop.continue(n + 1))
        }
      }

    "C4: 10 000 keys queued at once: no loss, few frames".timeout(
      60.seconds,
    ) in {
      val file  = Files.createTempFile("tui4s-c4", ".jsonl")
      val app   = App[Int](
        0,
        n => text(s"n=$n"),
        (n, e) =>
          if e == Key.Char('+') then
              if n + 1 == 10000 then Quit(n + 1) else n + 1
          else n,
        options = TuiOptions(fps = 60, inboxCapacity = 1024, trace = Some(file)),
      )
      val input = ScriptedInput(Seq.fill(10000)(Key.Char('+'))*)
      val start = java.lang.System.nanoTime()
      Runner
        .startUnscoped(app, HeadlessControl(TestBackend(20, 2)), Some(input))
        .map { h =>
          h.await.map { n =>
            h.stats.map { st =>
              val elapsedMillis =
                (java.lang.System.nanoTime() - start) / 1_000_000
              assert(n == 10000)
              assert(st.events == 10000)
              assert(
                st.frames <= 2 + elapsedMillis / 16,
                s"$st in $elapsedMillis ms",
              )
              assert(slowLines(file).isEmpty, slowLines(file).mkString("\n"))
              Files.delete(file)
            }
          }
        }
    }

    "C5: a late value of a stopped source and of a replaced task is dropped"
      .timeout(30.seconds) in {
      final case class S(n: Int, on: Boolean, tasks: Int) derives CanEqual
      val emitters                                                 =
        new java.util.concurrent.ConcurrentHashMap[String, spi.Emitter.Unsafe[
          Int,
        ]]()
      def capture(
          name: String,
      )(emit: spi.Emitter[Int]): Unit < (Async & Abort[Throwable]) =
        Sync
          .defer(discard(emitters.put(name, emit.unsafe)))
          .andThen(Async.never)
      val app                                                      = App[S](
        S(0, on = true, 0),
        s => text(s.toString),
        (s, e) =>
          e match
              case Key.Char('p') => s.copy(on = false)
              case Key.Char('t') =>
                s.copy(tasks = s.tasks + 1)
                  .withTask(
                    spi.Sources
                      .makeTask[S, Int]("k")(capture(s"task${s.tasks + 1}"))(
                        (s, a) => s.copy(n = s.n + a),
                      )
                      .id("k"),
                  )
              case _             => s,
        s =>
          if s.on then
              Seq(
                spi.Sources.make[S, Int]("src")(capture("src"))((s, a) =>
                  s.copy(n = s.n + a),
                ),
              )
          else Nil,
      )
      Scope.run(
        spi.Headless.start(app, TestBackend(40, 2), ScriptedInput.empty).map {
          h =>
            def put(name: String, v: Int): Unit < Sync =
              Sync.Unsafe.defer(emitters.get(name).nn.put(v, () => ()))
            eventually()(emitters.containsKey("src"))
              .andThen(h.send(Key.Char('t')))
              .andThen(eventually()(emitters.containsKey("task1")))
              .andThen(h.send(Key.Char('t')))
              .andThen(eventually()(emitters.containsKey("task2")))
              .andThen(h.send(Key.Char('p')))
              .andThen(eventually()(h.states.current.map(!_.on)))
              .andThen(put("src", 100))    // stopped source
              .andThen(put("task1", 1000)) // replaced task
              .andThen(put("task2", 1))    // the live task
              .andThen(eventually()(h.states.current.map(_.n == 1)))
              .andThen(Async.sleep(100.millis))
              .andThen(h.stats)
              .map { st =>
                h.states.current.map { s =>
                  assert(s.n == 1)
                  assert(st.droppedFolds == 2 && st.folds == 1)
                }
              }
        },
      )
    }

    "C6: 10 000 one-shot tasks that complete leave the supervisor empty"
      .timeout(60.seconds) in {
      val task  = Source.For[Int].task(Kyo.lift(1))((n, a: Int) => n + a)
      val app   = App[Int](
        0,
        n => text(n.toString),
        (n, e) => if e == Key.Char('t') then n.withTask(task) else n,
      )
      val watch = Watcher[Int]()
      val input = ScriptedInput(Seq.fill(10000)(Key.Char('t'))*)
      Runner
        .startUnscoped(
          app,
          HeadlessControl(TestBackend(10, 1)),
          Some(input),
          watch,
        )
        .map { h =>
          eventually(30.seconds)(watch.folds == 10000 && watch.idle).map { ok =>
            h.stop.andThen(h.await).map { n =>
              assert(
                ok,
                s"folds=${watch.folds} tasks=${watch.tasks} idle=${watch.idle}",
              )
              assert(n == 10000 && watch.tasks == 0)
            }
          }
        }
    }

    "C7: an app with no sources, idle for 1 s: no batch, no frame after the first"
      .timeout(30.seconds) in {
      val file = Files.createTempFile("tui4s-c7", ".jsonl")
      Scope
        .run(
          spi.Headless
            .start(
              traced(CounterApp, file),
              TestBackend(40, 5),
              ScriptedInput.empty,
            )
            .map { h =>
              Async.sleep(1.second).andThen(h.stats).map { st =>
                assert(st.batches == 0 && st.frames == 1 && st.viewBuilds == 1)
              }
            },
        )
        .andThen {
          assert(slowLines(file).isEmpty)
          Files.delete(file)
        }
    }

    "frame cap: the first change draws at once, a burst waits for one frame"
      .timeout(30.seconds) in {
      val app   = App[Int](
        CounterApp.init,
        CounterApp.view,
        CounterApp.update,
        options = TuiOptions(fps = 4), // 250 ms per frame
      )
      val input = ScriptedInput.empty
      Scope.run(
        spi.Headless.start(app, TestBackend(40, 5), input).map { h =>
          def frames: Long < Sync = h.stats.map(_.frames)
          Sync
            .defer(input.push(Key.Char('+')))
            .andThen(eventually()(frames.map(_ == 2)))
            .andThen(Sync.defer {
              input.push(Key.Char('+'))
              input.push(Key.Char('+'))
            })
            .andThen(Async.sleep(60.millis))
            .andThen(frames)
            .map { early =>
              eventually()(frames.map(_ == 3)).map { late =>
                h.states.current.map { n =>
                  assert(early == 2 && late && n == 3)
                }
              }
            }
        },
      )
    }

    "a slow update writes a slow line to the trace file".timeout(
      30.seconds,
    ) in {
      val file = Files.createTempFile("tui4s-slow", ".jsonl")
      val app  = App[Int](
        0,
        n => text(n.toString),
        (n, e) =>
          if e == Key.Char('s') then
              Thread.sleep(40)
              n + 1
          else if e == Key.Char('q') then Quit
          else n,
        options = TuiOptions(trace = Some(file)),
      )
      spi.Headless.run(app, TestBackend(10, 1), ScriptedInput('s', 'q')).map {
        n =>
          val slow = slowLines(file)
          assert(n == 1 && slow.size == 1)
          assert(slow.head.contains("\"where\":\"update on key:s\""))
          Files.delete(file)
      }
    }

    "C9: the synchronous contracts C1-C3 write no slow line" in {
      val t = TuiTest(CounterApp)
      t.press('x', Key.Up, '+', '-')
      t.send(Key.Char('+'), Key.Char('+'))
      assert(!t.trace.exists(_.contains("\"kind\":\"slow\"")))
      assert(t.trace.count(_.contains("\"kind\":\"batch\"")) == 5)
    }
end RuntimeContractTest
