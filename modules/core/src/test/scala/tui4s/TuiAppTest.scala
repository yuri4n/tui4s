package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui.crossterm.KeyCode
import tui4s.EventMappingTest.{Ctrl, key}
import tui4s.internal.TerminalIO

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import scala.jdk.CollectionConverters.*

/** A main class for the tests: a counter that shows its arguments, plus a key
  * that throws, a key that prints, and a key that blocks its thread as
  * `System.exit` does (until [[gate]] opens; an interrupt does not stop the
  * wait). [[failUi]] makes the construction of the UI fail, [[failExit]] makes
  * `onExit` fail.
  */
// claude 2026-10-13: changed (T2, RC-27 part): a kyo-ui app (PR 5 had a
// TuiApp[Int] of the old model with a Keymap)
object MainCounter extends TuiApp:
    val exited: AtomicInteger = AtomicInteger(-1)
    private val last          = AtomicInteger(0)

    @volatile var gate: CountDownLatch                      = CountDownLatch(0)
    val blocked: AtomicBoolean                              = AtomicBoolean(false)
    @volatile var failUi: Boolean                           = false
    // claude 2026-10-13: created (T2, review R2-1)
    @volatile var failExit: Boolean                         = false
    @volatile var opts: TuiOptions                          = TuiOptions()
    // The call log of the current test: onExit writes "onExit" into it.
    @volatile var log: ConcurrentLinkedQueue[String] | Null = null

    /** Sets the app back to its start values for a test that logs to `l`. */
    def reset(l: ConcurrentLinkedQueue[String]): Unit =
        exited.set(-1)
        last.set(0)
        failUi = false
        failExit = false
        opts = TuiOptions()
        log = l

    private def block(): Unit =
        blocked.set(true)
        while gate.getCount > 0 do
            try gate.await()
            catch case _: InterruptedException => ()

    override def options: TuiOptions = opts

    def ui: UI < (Async & Scope) =
      if failUi then
          Sync.defer(
            throw IllegalStateException(
              "boom",
              java.io.IOException("disk gone"),
            ),
          )
      else
          Signal.initRef(0).map { count =>
            div.onKeyDown {
              case KeyboardEvent(Keyboard.Char('+'), _, _) =>
                count.updateAndGet(_ + 1).map(n => Sync.defer(last.set(n)))
              case KeyboardEvent(Keyboard.Char('x'), _, _) =>
                Sync.defer(throw IllegalStateException("boom"))
              case KeyboardEvent(Keyboard.Char('p'), _, _) =>
                Sync.defer(
                  java.lang.System.out.println("printed while the UI is up"),
                )
              case KeyboardEvent(Keyboard.Char('w'), _, _) =>
                Sync.defer(block())
              case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
              case _                                       => ()
            }(
              span("Count: ", count.map(_.toString)),
              span("Args: " + args.mkString(" ")),
            )
          }

    def seenArgs: Chunk[String] = args

    // claude 2026-10-13: changed (T2, review R2-1): fails with failExit
    override protected def onExit: Unit < (Async & Abort[Throwable]) =
      Sync
        .defer {
          exited.set(last.get)
          val l = log
          if l != null then
              val _ = l.add("onExit")
          failExit
        }
        .map(fail =>
          if fail then Abort.fail[Throwable](IllegalStateException("exit boom"))
          else Kyo.unit,
        )
end MainCounter

/** `TuiApp.main` (RC-24, D-24 a) through its seam `runMain`, on a fake
  * crossterm: the exit code, the error printed after the restore, the arguments
  * and the options, `onExit`, and the shutdown hooks (the models of SIGTERM and
  * of `System.exit`). Sequential: one main at a time.
  */
// claude 2026-10-13: changed (T2, RC-27 part): kyo-ui (the six leaves of PR 5
// adapted: a quit and the Ctrl-C key give 0, a stop by the JVM prints nothing
// and skips onExit); new leaves: Ctrl-C, a failing handler, args, options
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

    /** What the run printed to stderr, without the R3 warning: in this test JVM
      * another suite can use `scala.Console` before `MainCounter` installs the
      * forwarders (`OutputCaptureTest` covers the warning).
      */
    // claude 2026-10-13: created (T2)
    private def printed(err: FakeErr): Seq[String] =
      err.lines.filterNot(_ == TerminalIO.ConsoleNotCaptured)

    /** Runs `MainCounter.runMain` with `args` on `fake` and returns the exit
      * code; `script` runs on its own thread meanwhile, and the result comes
      * after the script has ended (at most 15 s).
      */
    // claude 2026-10-13: created (T2)
    private def main(
        fake: FakeCrossterm,
        hooks: FakeHooks,
        err: FakeErr,
        args: Array[String] = Array(),
    )(script: => Unit)(using Frame): Int < Async =
      onThread {
        val t    = Thread(() => script)
        t.start()
        val code = MainCounter.runMain(args, fake, hooks, err.stream)
        t.join(15_000)
        code
      }

    /** Waits on this thread until `cond` holds (at most 10 s). */
    private def waitFor(cond: => Boolean): Unit =
        val until = java.lang.System.nanoTime() + 10_000_000_000L
        while !cond && java.lang.System.nanoTime() < until do Thread.sleep(5)

    /** Waits until the app shows `text`, then pushes `c`. */
    // claude 2026-10-13: created (T2)
    private def pushWhen(fake: FakeCrossterm, text: String)(c: Char): Unit =
        waitFor(fake.screen.contains(text))
        fake.push(char(c))

    "no terminal: exit code 1 and the message, printed after the restore"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm(failAt = "raw")
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      MainCounter.reset(fake.log)
      main(fake, hooks, err, Array("a", "b"))(()).map { code =>
        assert(code == 1)
        assert(
          printed(err).headOption.contains("stdin/stdout is not a terminal"),
          err.lines.toString,
        )
        assert(printed(err).exists(_.contains("cause:")))
        assert(MainCounter.seenArgs == Chunk("a", "b"))
        assert(MainCounter.exited.get == -1, "onExit ran after a failure")
        // The terminal never changed.
        assert(!fake.calls.exists(_.startsWith("execute")), fake.calls.toString)
        // claude 2026-10-19: changed (P3, A1): the terminal hook is added
        // before the terminal changes; both hooks are removed again
        val hookCalls = fake.calls.filter(_.startsWith("hook"))
        assert(
          hookCalls.take(2).toSet == Set(
            "hook-added tui4s-stop",
            "hook-added tui4s-restore",
          ) && hookCalls.drop(2) == Seq(
            "hook-removed tui4s-restore",
            "hook-removed tui4s-stop",
          ),
          fake.calls.toString,
        )
      }
    }

    "a run that quits: exit code 0, onExit runs after the restore".timeout(
      30.seconds,
    ) in {
      val log   = ConcurrentLinkedQueue[String]()
      val fake  = FakeCrossterm(log = log)
      val hooks = FakeHooks(log)
      val err   = FakeErr(log)
      MainCounter.reset(log)
      // '+' twice, then 'q' once the second '+' is drawn.
      main(fake, hooks, err) {
        fake.push(char('+'))
        fake.push(char('+'))
        pushWhen(fake, "Count: 2")('q')
      }.map { code =>
        assert(code == 0, err.lines.toString)
        assert(MainCounter.exited.get == 2)
        assert(printed(err).isEmpty, err.lines.toString)
        val calls     = fake.calls
        assert(
          calls.indexOf("onExit") > calls.lastIndexOf("raw-mode-off") &&
            calls.indexOf("onExit") > calls.lastIndexOf("flush"),
          calls.mkString("\n"),
        )
        // The two hooks are added in either order (the run starts on its
        // own fiber); the terminal's hook goes first.
        val hookCalls = calls.filter(_.startsWith("hook"))
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

    // claude 2026-10-13: created (T2, D-24 a)
    "the Ctrl-C key: exit code 0 and onExit, as a quit".timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      MainCounter.reset(fake.log)
      main(fake, hooks, err) {
        fake.push(char('+'))
        waitFor(fake.screen.contains("Count: 1"))
        fake.push(key(KeyCode.Char('c'), Ctrl))
      }.map { code =>
        assert(code == 0, err.lines.toString)
        assert(MainCounter.exited.get == 1)
        assert(printed(err).isEmpty, err.lines.toString)
        assert(
          fake.calls.containsSlice(Seq("flush", "raw-mode-off")),
          fake.calls.mkString("\n"),
        )
      }
    }

    // claude 2026-10-13: changed (T2): a UI that fails (PR 5: an update that
    // threw); its exception comes as it is, with its cause
    "a UI that fails: exit code 1, the stack trace with its cause after the restore"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm()
      val err  = FakeErr(fake.log)
      MainCounter.reset(fake.log)
      MainCounter.failUi = true
      main(fake, FakeHooks(fake.log), err)(()).map { code =>
        assert(code == 1)
        val lines = printed(err)
        assert(
          lines.headOption.contains("java.lang.IllegalStateException: boom"),
          lines.toString,
        )
        assert(
          lines.exists(_.contains("Caused by: java.io.IOException: disk gone")),
          lines.mkString("\n"),
        )
        assert(MainCounter.exited.get == -1, "onExit ran after a failure")
        // Printed after the terminal was restored, and never on the screen.
        val calls = fake.calls
        assert(
          calls.lastIndexOf("raw-mode-off") <
            calls.indexOf("err: java.lang.IllegalStateException: boom"),
          calls.mkString("\n"),
        )
        assert(!fake.screen.contains("boom"), fake.screen.text)
      }
    }

    // claude 2026-10-13: created (T2, review R2-1)
    "an onExit that fails: exit code 1, its stack trace after the restore"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm()
      val err  = FakeErr(fake.log)
      MainCounter.reset(fake.log)
      MainCounter.failExit = true
      main(fake, FakeHooks(fake.log), err) {
        fake.push(char('+'))
        pushWhen(fake, "Count: 1")('q')
      }.map { code =>
        assert(code == 1, err.lines.toString)
        assert(MainCounter.exited.get == 1, "onExit did not run")
        val lines = printed(err)
        assert(
          lines.headOption.contains(
            "java.lang.IllegalStateException: exit boom",
          ),
          lines.mkString("\n"),
        )
        // onExit ran after the restore, and its error was printed after it.
        val calls = fake.calls
        val boom  =
          calls.indexOf("err: java.lang.IllegalStateException: exit boom")
        assert(
          calls.lastIndexOf("raw-mode-off") < calls.indexOf("onExit") &&
            calls.indexOf("onExit") < boom,
          calls.mkString("\n"),
        )
        assert(!fake.screen.contains("boom"), fake.screen.text)
      }
    }

    // claude 2026-10-13: created (T2): a handler that fails is logged and the
    // run goes on (C3); its log line comes after the restore
    "a handler that fails: the run goes on, the error comes after the restore, q gives 0"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      MainCounter.reset(fake.log)
      main(fake, hooks, err) {
        fake.push(char('x'))
        fake.push(char('+'))
        pushWhen(fake, "Count: 1")('q')
      }.map { code =>
        assert(code == 0, err.lines.toString)
        assert(MainCounter.exited.get == 1)
        val lines = printed(err)
        assert(
          lines.exists(_.contains("onKeyDown handler failed")),
          lines.mkString("\n"),
        )
        assert(
          lines.exists(_.contains("java.lang.IllegalStateException: boom")),
          lines.mkString("\n"),
        )
        val calls = fake.calls
        val first = calls.indexWhere(_.startsWith("err: "))
        assert(first > calls.lastIndexOf("raw-mode-off"), calls.mkString("\n"))
        assert(!fake.screen.contains("boom"), fake.screen.text)
      }
    }

    // claude 2026-10-13: created (T2)
    "the arguments reach the app: the UI shows them".timeout(30.seconds) in {
      val fake = FakeCrossterm()
      val err  = FakeErr(fake.log)
      MainCounter.reset(fake.log)
      main(fake, FakeHooks(fake.log), err, Array("--hello", "world"))(
        pushWhen(fake, "Args: --hello world")('q'),
      ).map { code =>
        assert(code == 0, err.lines.toString)
        assert(MainCounter.seenArgs == Chunk("--hello", "world"))
        assert(fake.screen.contains("Args: --hello world"), fake.screen.text)
      }
    }

    // claude 2026-10-13: created (T2)
    "the options of the app are used: a print goes to its log file, not to the screen"
      .timeout(30.seconds) in {
      Sync.defer(Files.createTempFile("tui4s-tuiapp", ".log")).map { file =>
        val fake = FakeCrossterm()
        val err  = FakeErr(fake.log)
        MainCounter.reset(fake.log)
        MainCounter.opts = TuiOptions(logFile = Some(file))
        main(fake, FakeHooks(fake.log), err) {
          waitFor(fake.screen.contains("Count: 0"))
          fake.push(char('p'))
          fake.push(char('+'))
          pushWhen(fake, "Count: 1")('q')
        }.map { code =>
          val text = Files.readString(file, StandardCharsets.UTF_8)
          assert(code == 0, err.lines.toString)
          assert(text.contains("printed while the UI is up"), text)
          assert(
            !printed(err).exists(_.contains("printed")),
            err.lines.toString,
          )
          assert(!fake.screen.contains("printed"), fake.screen.text)
        }
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

    // claude 2026-10-13: changed (T2): the JVM shuts down (its hooks run), so
    // the run prints nothing and onExit does not run; the stop hook returns
    // within its 2 s
    "SIGTERM: both hooks at once interrupt the run and restore the terminal"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      val took  = AtomicLong(-1)
      MainCounter.reset(fake.log)
      main(fake, hooks, err) {
        waitFor(hooks.added.size == 2 && fake.screen.contains("Count"))
        // What the JVM does on SIGTERM: it takes no new hook and runs every
        // hook at once.
        hooks.shuttingDown = true
        val times = runHooks(hooks.added.asScala.toSeq)
        took.set(times.getOrElse("tui4s-stop", -1L))
      }.map { code =>
        assert(code == 1)
        assert(printed(err).isEmpty, err.lines.toString)
        assert(MainCounter.exited.get == -1, "onExit ran while the JVM stops")
        assert(
          took.get >= 0 && took.get < 1500,
          s"the stop hook took ${took.get} ms (-1: it did not run)",
        )
        assert(
          fake.calls.containsSlice(Seq("flush", "raw-mode-off")),
          fake.calls.mkString("\n"),
        )
      }
    }

    // claude 2026-10-13: changed (T2): the release of the restore hook ends
    // the run (a closed inbox is a quit), and still nothing is printed and
    // onExit does not run
    "the restore hook first, then the stop hook: nothing printed, no onExit"
      .timeout(30.seconds) in {
      val fake                          = FakeCrossterm()
      val hooks                         = FakeHooks(fake.log)
      val err                           = FakeErr(fake.log)
      val took                          = AtomicLong(-1)
      def named(n: String): Seq[Thread] =
        hooks.added.asScala.toSeq.filter(_.getName == n)
      MainCounter.reset(fake.log)
      main(fake, hooks, err) {
        waitFor(hooks.added.size == 2 && fake.screen.contains("Count"))
        hooks.shuttingDown = true
        // The restore hook closes the inbox under the running loop.
        val _     = runHooks(named("tui4s-restore"))
        val times = runHooks(named("tui4s-stop"))
        took.set(times.getOrElse("tui4s-stop", -1L))
      }.map { code =>
        assert(code == 1)
        assert(printed(err).isEmpty, err.lines.toString)
        assert(MainCounter.exited.get == -1, "onExit ran while the JVM stops")
        assert(took.get >= 0 && took.get < 1500, s"${took.get} ms")
      }
    }

    "System.exit in a handler: the stop hook waits for the restore, not 2 s"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      val took  = AtomicLong(-1)
      MainCounter.reset(fake.log)
      MainCounter.blocked.set(false)
      MainCounter.gate = CountDownLatch(1)
      main(fake, hooks, err) {
        waitFor(hooks.added.size == 2 && fake.screen.contains("Count"))
        // The handler blocks its thread, as System.exit does while the JVM
        // runs the shutdown hooks.
        fake.push(char('w'))
        waitFor(MainCounter.blocked.get)
        hooks.shuttingDown = true
        val times = runHooks(hooks.added.asScala.toSeq)
        took.set(times.getOrElse("tui4s-stop", -1L))
        MainCounter.gate.countDown()
      }.map { code =>
        assert(code == 1, err.lines.toString)
        assert(took.get >= 0 && took.get < 1500, s"the stop hook took $took ms")
        assert(
          fake.calls.containsSlice(Seq("flush", "raw-mode-off")),
          fake.calls.mkString("\n"),
        )
        assert(printed(err).isEmpty, err.lines.toString)
      }
    }
end TuiAppTest
