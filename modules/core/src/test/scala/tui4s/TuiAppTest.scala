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
        // claude 2026-10-19: changed (P3, A2): the one hook of the run is
        // added and removed
        assert(
          fake.calls.filter(_.startsWith("hook")) == Seq(
            "hook-added tui4s-stop",
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
        val calls = fake.calls
        assert(
          calls.indexOf("onExit") > calls.lastIndexOf("raw-mode-off") &&
            calls.indexOf("onExit") > calls.lastIndexOf("flush"),
          calls.mkString("\n"),
        )
        // claude 2026-10-19: changed (P3, A2): one hook per run; it is
        // removed before onExit runs
        assert(
          calls.filter(_.startsWith("hook")) == Seq(
            "hook-added tui4s-stop",
            "hook-removed tui4s-stop",
          ) && calls.indexOf("onExit") > calls.indexOf(
            "hook-removed tui4s-stop",
          ),
          calls.mkString("\n"),
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

    // claude 2026-10-19: changed (P3, A2): one hook per run; it records that
    // the JVM stops, so the run prints nothing and onExit does not run
    "SIGTERM: the one hook of the run restores the terminal once; nothing printed, no onExit"
      .timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      val took  = AtomicLong(-1)
      MainCounter.reset(fake.log)
      main(fake, hooks, err) {
        waitFor(hooks.added.size == 1 && fake.screen.contains("Count"))
        // What the JVM does on SIGTERM: it runs every hook at once.
        val times = runHooks(hooks.added.asScala.toSeq)
        took.set(times.getOrElse("tui4s-stop", -1L))
      }.map { code =>
        assert(code == 1)
        assert(hooks.shuttingDown, "the hook did not record the stop")
        assert(printed(err).isEmpty, err.lines.toString)
        assert(MainCounter.exited.get == -1, "onExit ran while the JVM stops")
        assert(
          took.get >= 0 && took.get < 1500,
          s"the stop hook took ${took.get} ms (-1: it did not run)",
        )
        assert(
          fake.calls.count(_ == "raw-mode-off") == 1 &&
            fake.calls.containsSlice(Seq("flush", "raw-mode-off")),
          fake.calls.mkString("\n"),
        )
        // A running hook does not remove itself.
        assert(hooks.removed.isEmpty, fake.calls.mkString("\n"))
      }
    }

    // claude 2026-10-19: created (P3, A1, A2): the run restores the terminal
    // and the hook comes while it does: the second runAll does nothing, and
    // the hook waits until the other release is complete
    "the hook while the run restores the terminal: one restore, the hook waits for it, no onExit"
      .timeout(30.seconds) in {
      val gate  = CountDownLatch(1)
      val fake  = FakeCrossterm(leaveGate = gate)
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      val took  = AtomicLong(-1)
      MainCounter.reset(fake.log)
      main(fake, hooks, err) {
        pushWhen(fake, "Count")('q')
        // The release of the run is in the leave commands; they wait.
        waitFor(
          fake.calls.exists(_.startsWith("execute DisableBracketedPaste")),
        )
        val hook = Thread { () =>
          val times = runHooks(hooks.added.asScala.toSeq)
          took.set(times.getOrElse("tui4s-stop", -1L))
        }
        hook.start()
        Thread.sleep(300)
        gate.countDown()
        hook.join(10_000)
      }.map { code =>
        assert(code == 1, err.lines.toString)
        assert(printed(err).isEmpty, err.lines.toString)
        assert(MainCounter.exited.get == -1, "onExit ran while the JVM stops")
        assert(
          took.get >= 250 && took.get < 1500,
          s"the stop hook took ${took.get} ms: it must wait for the release",
        )
        assert(
          fake.calls.count(_ == "raw-mode-off") == 1 &&
            fake.calls.count(
              _.startsWith("execute DisableBracketedPaste"),
            ) == 1,
          fake.calls.mkString("\n"),
        )
      }
    }

    // claude 2026-10-19: changed (P3, A2): one hook per run
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
        waitFor(hooks.added.size == 1 && fake.screen.contains("Count"))
        // The handler blocks its thread, as System.exit does while the JVM
        // runs the shutdown hooks.
        fake.push(char('w'))
        waitFor(MainCounter.blocked.get)
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
