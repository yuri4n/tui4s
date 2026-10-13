package tui4s

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.test.*
import scala.jdk.CollectionConverters.*
import tui.crossterm.KeyCode
import tui4s.EventMappingTest.key

/** A main class for the tests: the Counter, plus a key that throws and a key
  * that blocks its thread like `System.exit` does (until [[gate]] opens; an
  * interrupt does not stop the wait).
  */
object MainCounter extends TuiApp[Int]:
    val exited: AtomicInteger = AtomicInteger(-1)

    @volatile var gate: CountDownLatch = CountDownLatch(0)
    val blocked: AtomicBoolean         = AtomicBoolean(false)

    private def block(n: Int): Int =
        blocked.set(true)
        while gate.getCount > 0 do
            try gate.await()
            catch case _: InterruptedException => ()
        n

    val keys: Keymap[Int] = Keymap(
      bind('+', "increment")(_ + 1),
      bind('x', "explode")(_ => throw IllegalStateException("boom")),
      bind('w', "block")(block),
      bind('q', "quit")(_ => Quit),
    )

    def init: Int = 0

    def update(n: Int, event: Event): Next[Int] = keys(n, event)

    def view(n: Int): View = text(s"Count: $n")

    def seenArgs: Seq[String] = args

    override protected def onExit(
        state: Int,
    ): Unit < (Async & Abort[Throwable]) =
      Sync.defer(exited.set(state))
end MainCounter

/** `TuiApp.main` (SPEC 5.9): the exit code, the message after the restore, the
  * shutdown hooks, `onExit`. Sequential: one main at a time.
  */
class TuiAppTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    /** `f` on a platform thread (it blocks), awaited without blocking. */
    private def onThread[A](f: => A)(using Frame): A < Async =
      Sync.Unsafe
        .defer {
          val p = Promise.Unsafe.init[A, Any]()
          Thread(() =>
              import AllowUnsafe.embrace.danger
              p.completeDiscard(
                try Result.succeed(f)
                catch case e: Throwable => Result.panic(e),
              ),
          ).start()
          p.safe
        }
        .map(_.get)

    private def char(c: Char) = key(KeyCode.Char(c))

    "no terminal: exit code 1 and the message, printed after the restore"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm(failAt = "raw")
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      onThread(MainCounter.runMain(Array("a", "b"), fake, hooks, err.stream))
        .map { code =>
          assert(code == 1)
          assert(
            err.lines.headOption.contains("stdin/stdout is not a terminal"),
            err.lines.toString,
          )
          assert(err.lines.exists(_.contains("cause:")))
          assert(MainCounter.seenArgs == Seq("a", "b"))
          // The main hook is added and removed; the terminal hook never came.
          assert(
            fake.calls.filter(_.startsWith("hook")) == Seq(
              "hook-added tui4s-stop",
              "hook-removed tui4s-stop",
            ),
            fake.calls.toString,
          )
        }
    }

    "a run that quits: exit code 0, onExit gets the last state".timeout(
      30.seconds,
    ) in {
      val log   = ConcurrentLinkedQueue[String]()
      val fake  = FakeCrossterm(log = log)
      val hooks = FakeHooks(log)
      val err   = FakeErr(log)
      Sync
        .defer {
          MainCounter.exited.set(-1)
          // '+' twice, then 'q' once the second '+' is drawn.
          Thread(() =>
              fake.push(char('+'))
              fake.push(char('+'))
              val until = java.lang.System.nanoTime() + 10_000_000_000L
              while !fake.screen.contains("Count: 2") &&
                  java.lang.System.nanoTime() < until
              do Thread.sleep(5)
              fake.push(char('q')),
          ).start()
        }
        .andThen(
          onThread(MainCounter.runMain(Array(), fake, hooks, err.stream)),
        )
        .map { code =>
          assert(code == 0, err.lines.toString)
          assert(MainCounter.exited.get == 2)
          assert(err.lines.isEmpty, err.lines.toString)
          // The two hooks are added in either order (the run starts on its
          // own fiber); the terminal's hook goes first.
          val hookCalls = fake.calls.filter(_.startsWith("hook"))
          assert(
            hookCalls.take(2).toSet == Set(
              "hook-added tui4s-stop",
              "hook-added tui4s-restore",
            ) && hookCalls.drop(2) == Seq(
              "hook-removed tui4s-restore",
              "hook-removed tui4s-stop",
            ),
            hookCalls.toString,
          )
        }
    }

    "update throws: exit code 1, the message and the cause's stack trace"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(events = Seq(char('x')))
      val err  = FakeErr(fake.log)
      onThread(
        MainCounter.runMain(Array(), fake, FakeHooks(fake.log), err.stream),
      ).map { code =>
        assert(code == 1)
        val lines = err.lines
        assert(
          lines.headOption.contains("update on key:x threw"),
          lines.toString,
        )
        assert(lines.exists(_.contains("IllegalStateException: boom")))
        // Printed after the terminal was restored.
        val calls = fake.calls
        assert(
          calls.indexOf("flush") < calls.indexOf("err: update on key:x threw"),
          calls.mkString("\n"),
        )
      }
    }

    /** Starts `hooks` on their own threads, as the JVM does on SIGTERM and on
      * `System.exit`, and waits for them; returns how long each one took, in
      * milliseconds, by name.
      */
    private def runHooks(hooks: Seq[Thread]): Map[String, Long] =
        val took    = java.util.concurrent.ConcurrentHashMap[String, Long]()
        val threads = hooks.map { h =>
          Thread(() =>
              val t0 = java.lang.System.nanoTime()
              h.run()
              val _  = took.put(
                h.getName,
                (java.lang.System.nanoTime() - t0) / 1_000_000,
              ),
          )
        }
        threads.foreach(_.start())
        threads.foreach(_.join(10_000))
        took.asScala.toMap
    end runHooks

    /** Waits on this thread until `cond` holds (at most 10 s). */
    private def waitFor(cond: => Boolean): Unit =
        val until = java.lang.System.nanoTime() + 10_000_000_000L
        while !cond && java.lang.System.nanoTime() < until do Thread.sleep(5)

    "SIGTERM: both hooks at once interrupt the run and restore the terminal"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Sync
        .defer {
          Thread(() =>
              waitFor(hooks.added.size == 2 && fake.screen.contains("Count"))
              // What the JVM does on SIGTERM: run every hook at once.
              val _ = runHooks(hooks.added.asScala.toSeq),
          ).start()
        }
        .andThen(
          onThread(MainCounter.runMain(Array(), fake, hooks, err.stream)),
        )
        .map { code =>
          assert(code == 1)
          assert(err.lines.contains("interrupted"), err.lines.toString)
          assert(
            fake.calls.containsSlice(Seq("raw-mode-off", "flush")),
            fake.calls.mkString("\n"),
          )
        }
    }

    "the restore hook first, then the stop hook: the run is interrupted"
      .timeout(30.seconds) in {
      val fake                          = FakeCrossterm()
      val hooks                         = FakeHooks(fake.log)
      val err                           = FakeErr(fake.log)
      def named(n: String): Seq[Thread] =
        hooks.added.asScala.toSeq.filter(_.getName == n)
      Sync
        .defer {
          Thread(() =>
              waitFor(hooks.added.size == 2 && fake.screen.contains("Count"))
              // The restore hook closes the inbox under the running loop.
              val _ = runHooks(named("tui4s-restore"))
              val _ = runHooks(named("tui4s-stop")),
          ).start()
        }
        .andThen(
          onThread(MainCounter.runMain(Array(), fake, hooks, err.stream)),
        )
        .map { code =>
          assert(code == 1)
          assert(err.lines == Seq("interrupted"), err.lines.toString)
        }
    }

    "System.exit in update: the stop hook waits for the restore, not 2 s"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Sync
        .defer {
          MainCounter.blocked.set(false)
          MainCounter.gate = CountDownLatch(1)
          val took = AtomicLong(-1)
          Thread(() =>
              waitFor(hooks.added.size == 2 && fake.screen.contains("Count"))
              // `update` blocks its thread, as System.exit does while the
              // JVM runs the shutdown hooks.
              fake.push(char('w'))
              waitFor(MainCounter.blocked.get)
              val times = runHooks(hooks.added.asScala.toSeq)
              took.set(times.getOrElse("tui4s-stop", -1L))
              MainCounter.gate.countDown(),
          ).start()
          took
        }
        .map(took =>
          onThread(MainCounter.runMain(Array(), fake, hooks, err.stream))
            .map(code => (code, took.get)),
        )
        .map { (code, took) =>
          assert(code == 1, err.lines.toString)
          assert(took >= 0 && took < 1500, s"the stop hook took $took ms")
          assert(
            fake.calls.containsSlice(Seq("raw-mode-off", "flush")),
            fake.calls.mkString("\n"),
          )
        }
    }
end TuiAppTest
