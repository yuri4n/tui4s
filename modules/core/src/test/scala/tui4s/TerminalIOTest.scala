package tui4s

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kyo.*
import kyo.test.*
import tui.crossterm.KeyCode
import tui4s.EventMappingTest.key
import tui4s.fixtures.*
import tui4s.internal.LogSink
import tui4s.internal.OutputCapture
import tui4s.internal.TuiRun

/** The real-terminal path (SPEC 5.9, 5.10) on a fake crossterm: the order of
  * the acquire and the release, the error of each failing step, the output
  * capture, the Kyo log of a run, the shutdown hook and `Tui.start`.
  *
  * Sequential: the output capture is JVM-global.
  */
class TerminalIOTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    private def eventually(max: Duration = 5.seconds)(cond: => Boolean)(using
        Frame,
    ): Boolean < Async =
      Loop(0) { n =>
        if cond then Loop.done(true)
        else if n * 10 >= max.toMillis then Loop.done(false)
        else Async.sleep(10.millis).andThen(Loop.continue(n + 1))
      }

    private def char(c: Char) = key(KeyCode.Char(c))

    private val enterDefault =
      "execute EnterAlternateScreen[],Hide[],EnableBracketedPaste[]"
    private val leaveDefault =
      "execute DisableBracketedPaste[],Show[],LeaveAlternateScreen[]"

    /** The Counter with other options. */
    private def counter(options: TuiOptions): App[Int] =
      App[Int](0, CounterApp.view, CounterApp.update, options = options)

    "open and close in the order of SPEC 5.9".timeout(30.seconds) in {
      val fake  = FakeCrossterm(events = Seq(char('+'), char('+')))
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Fiber
        .initUnscoped(TuiRun.run(CounterApp, fake, hooks, () => err.stream))
        .map { fiber =>
          eventually()(fake.screen.contains("Count: 2"))
            .andThen(Sync.defer(fake.push(char('q'))))
            .andThen(fiber.get)
            .map { n =>
              assert(n == 2)
              assert(
                fake.calls == Seq(
                  "load",
                  "raw-mode-on",
                  enterDefault,
                  "backend",
                  "hook-added tui4s-restore",
                  leaveDefault,
                  "raw-mode-off",
                  "flush",
                  "hook-removed tui4s-restore",
                ),
                fake.calls.mkString("\n"),
              )
              assert(hooks.added.size == 1 && hooks.removed.size == 1)
            }
        }
    }

    "the options choose the modes; an inline viewport ends on a new line"
      .timeout(30.seconds) in {
      val opts = TuiOptions(
        mouse = true,
        focusEvents = true,
        paste = false,
        viewport = Viewport.Inline(3),
      )
      val fake = FakeCrossterm(events = Seq(char('q')))
      val err  = FakeErr(fake.log)
      TuiRun
        .run(counter(opts), fake, FakeHooks(fake.log), () => err.stream)
        .map { _ =>
          val calls = fake.calls.filter(_.startsWith("execute"))
          assert(
            calls == Seq(
              "execute Hide[],EnableMouseCapture[],EnableFocusChange[]",
              "execute DisableFocusChange[],DisableMouseCapture[],Show[]",
              "execute MoveTo[x=0, y=2],Print[value=\r\n]",
            ),
            calls.mkString("\n"),
          )
        }
    }

    "no terminal: NotATerminal before anything else changes".timeout(
      30.seconds,
    ) in {
      val fake  = FakeCrossterm(failAt = "raw")
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Abort
        .run(TuiRun.run(CounterApp, fake, hooks, () => err.stream))
        .map { r =>
          assert(
            r match
                case Result.Failure(TuiError.NotATerminal(_)) => true
                case _                                        => false
            ,
            r.toString,
          )
          assert(
            r.failure.exists(_.getMessage == "stdin/stdout is not a terminal"),
          )
          assert(fake.calls == Seq("load", "raw-mode-on"), fake.calls.toString)
          assert(hooks.added.isEmpty)
          // The capture was stopped: a new one takes the output.
          val sink  = LogSink(None)
          val c     = OutputCapture.start(sink)
          val taken = c.capturing
          c.stop()
          assert(taken, "the output capture was not stopped")
        }
    }

    "a native library that cannot load: NativeAccessDenied".timeout(
      30.seconds,
    ) in {
      val fake = FakeCrossterm(failAt = "load")
      Abort
        .run(TuiRun.run(CounterApp, fake, FakeHooks(fake.log)))
        .map { r =>
          assert(
            r match
                case Result.Failure(TuiError.NativeAccessDenied(_)) => true
                case _                                              => false
            ,
            r.toString,
          )
          assert(fake.calls == Seq("load"))
        }
    }

    "a failing mode command: TerminalIO, raw mode is left again".timeout(
      30.seconds,
    ) in {
      val fake = FakeCrossterm(failAt = "enter")
      Abort
        .run(TuiRun.run(CounterApp, fake, FakeHooks(fake.log)))
        .map { r =>
          assert(
            r match
                case Result.Failure(TuiError.TerminalIO(_)) => true
                case _                                      => false
            ,
            r.toString,
          )
          assert(
            fake.calls == Seq(
              "load",
              "raw-mode-on",
              enterDefault,
              leaveDefault,
              "raw-mode-off",
            ),
            fake.calls.mkString("\n"),
          )
        }
    }

    "a failing release step does not stop the others; the error is printed"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(failAt = "leave", events = Seq(char('q')))
      val err  = FakeErr(fake.log)
      TuiRun
        .run(CounterApp, fake, FakeHooks(fake.log), () => err.stream)
        .map { _ =>
          val calls = fake.calls
          assert(
            calls.containsSlice(
              Seq(
                leaveDefault,
                "raw-mode-off",
                "flush",
                "hook-removed tui4s-restore",
              ),
            ),
            calls.mkString("\n"),
          )
          assert(
            err.lines.exists(_.contains("restoring the terminal failed")),
            calls.mkString("\n"),
          )
        }
    }

    "output while the UI is up is printed after the restore".timeout(
      30.seconds,
    ) in {
      val noisy = App[Int](
        0,
        CounterApp.view,
        (n, e) =>
          e match
              case Key.Char('p') =>
                java.lang.System.out.println("hello from update")
                java.lang.System.err.println("to stderr")
                n
              case other         => CounterApp.update(n, other),
      )
      val fake  = FakeCrossterm(events = Seq(char('p'), char('q')))
      val err   = FakeErr(fake.log)
      TuiRun
        .run(noisy, fake, FakeHooks(fake.log), () => err.stream)
        .map { _ =>
          val calls = fake.calls
          val flush = calls.indexOf("flush")
          val hello = calls.indexOf("err: hello from update")
          val toErr = calls.indexOf("err: to stderr")
          assert(
            flush >= 0 && hello > flush && toErr > flush,
            calls.mkString("\n"),
          )
        }
    }

    "captureOutput = false: the output is not taken".timeout(30.seconds) in {
      val app  = App[Int](
        0,
        CounterApp.view,
        (n, e) =>
          e match
              case Key.Char('p') =>
                java.lang.System.out.println("tui4s test: not captured")
                n
              case other         => CounterApp.update(n, other)
        ,
        options = TuiOptions(captureOutput = false),
      )
      val fake = FakeCrossterm(events = Seq(char('p'), char('q')))
      val err  = FakeErr(fake.log)
      TuiRun
        .run(app, fake, FakeHooks(fake.log), () => err.stream)
        .map(_ => assert(!err.lines.exists(_.contains("not captured"))))
    }

    "Kyo log lines at warn or above are printed after the restore".timeout(
      30.seconds,
    ) in {
      val source = Source.For[Int]
      val app    = App[Int](
        0,
        CounterApp.view,
        CounterApp.update,
        sources = _ =>
          Seq(
            source.task(
              Log.warn("careful now").andThen(Log.info("detail")).andThen(1),
            )((_, _) => Quit),
          ),
      )
      val fake   = FakeCrossterm()
      val err    = FakeErr(fake.log)
      TuiRun
        .run(app, fake, FakeHooks(fake.log), () => err.stream)
        .andThen(eventually()(err.lines.exists(_.contains("careful now"))))
        .map { seen =>
          assert(seen, fake.calls.mkString("\n"))
          val line = err.lines.find(_.contains("careful now")).getOrElse("")
          assert(line.contains("WARN"), line)
          assert(!err.lines.exists(_.contains("detail")))
        }
    }

    "a trace file that cannot be written is a warning after the restore"
      .timeout(30.seconds) in {
      val bad  = Path.of("/nonexistent-tui4s-dir/trace.jsonl")
      val fake = FakeCrossterm(events = Seq(char('q')))
      val err  = FakeErr(fake.log)
      TuiRun
        .run(
          counter(TuiOptions(trace = Some(bad))),
          fake,
          FakeHooks(fake.log),
          () => err.stream,
        )
        .map { n =>
          assert(n == 0)
          val calls = fake.calls
          val warn  = calls.indexWhere(l =>
            l.startsWith("err: ") && l.contains("cannot be written"),
          )
          assert(
            warn > calls.indexOf("flush") && calls.indexOf("flush") >= 0,
            calls.mkString("\n"),
          )
        }
    }

    "with a log file, the output and the log go to the file".timeout(
      30.seconds,
    ) in {
      Sync.defer(Files.createTempFile("tui4s-log", ".txt")).map { file =>
        val app  = App[Int](
          0,
          CounterApp.view,
          (n, e) =>
            e match
                case Key.Char('p') =>
                  java.lang.System.out.println("into the file")
                  n
                case other         => CounterApp.update(n, other)
          ,
          options = TuiOptions(logFile = Some(file)),
        )
        val fake = FakeCrossterm(events = Seq(char('p'), char('q')))
        val err  = FakeErr(fake.log)
        TuiRun
          .run(app, fake, FakeHooks(fake.log), () => err.stream)
          .andThen(Sync.defer(Files.readString(file, StandardCharsets.UTF_8)))
          .map { text =>
            assert(text.contains("into the file"), text)
            assert(!err.lines.exists(_.contains("into the file")))
          }
      }
    }

    "the shutdown hook restores the terminal while the app runs".timeout(
      30.seconds,
    ) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Fiber
        .initUnscoped(TuiRun.run(CounterApp, fake, hooks, () => err.stream))
        .map { fiber =>
          eventually()(!hooks.added.isEmpty)
            .andThen(Sync.defer {
              // What the JVM does on System.exit: run the hook on its thread.
              val hook = hooks.added.peek().nn
              val t    = Thread(() => hook.run())
              t.start()
              t.join(5000)
            })
            .andThen(Abort.run(fiber.get))
            .map { _ =>
              val calls = fake.calls
              assert(
                calls.count(_ == "raw-mode-off") == 1 &&
                  calls
                    .containsSlice(Seq(leaveDefault, "raw-mode-off", "flush")),
                calls.mkString("\n"),
              )
              assert(hooks.removed.isEmpty, "a running hook is not removed")
            }
        }
    }

    "Tui.start on a terminal: the handle updates, sends and stops".timeout(
      30.seconds,
    ) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Scope
        .run(
          TuiRun.start(CounterApp, fake, hooks, () => err.stream).map { h =>
            h.update(_ + 5)
              .andThen(h.send(Key.Char('+')))
              .andThen(eventually()(fake.screen.contains("Count: 6")))
              .andThen(h.states.current)
              .map(now => h.stop.andThen(h.await).map(last => (now, last)))
          },
        )
        .map { (now, last) =>
          assert(now == 6 && last == 6)
          assert(
            fake.calls.containsSlice(Seq(leaveDefault, "raw-mode-off")),
            fake.calls.mkString("\n"),
          )
        }
    }
end TerminalIOTest
