package tui4s

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.test.*
import tui.crossterm.KeyCode
import tui4s.EventMappingTest.key

/** A main class for the tests: the Counter, plus a key that throws. */
object MainCounter extends TuiApp[Int]:
    val exited: AtomicInteger = AtomicInteger(-1)

    val keys: Keymap[Int] = Keymap(
      bind('+', "increment")(_ + 1),
      bind('x', "explode")(_ => throw IllegalStateException("boom")),
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

    "the SIGTERM hook interrupts the run, which restores the terminal"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Sync
        .defer {
          Thread(() =>
              val until = java.lang.System.nanoTime() + 10_000_000_000L
              while hooks.added.size < 2 && java.lang.System.nanoTime() < until
              do Thread.sleep(5)
              // What the JVM does on SIGTERM: run the main hook.
              hooks.added.forEach(h =>
                if h.getName == "tui4s-stop" then h.run(),
              ),
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
end TuiAppTest
