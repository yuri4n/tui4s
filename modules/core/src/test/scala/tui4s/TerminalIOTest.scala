package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui.crossterm.KeyCode
import tui4s.EventMappingTest.key
import tui4s.fixtures.*
import tui4s.internal.{LogSink, OutputCapture}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The real-terminal path on a fake crossterm: the order of the acquire and the
  * release, the error of each failing step, the output capture, the Kyo log of
  * a run and the shutdown hook.
  *
  * Sequential: the output capture is JVM-global.
  */
// claude 2026-10-13: changed (T1): the kyo-ui runner through the run seam
// Tui.runWith and the fixture Counter (TuiRun and the Elm apps are not
// carried); no inline viewport (D-22), no mouse and no focus modes (D-30), no
// trace file (P1); the Tui.start leaf is a run that the UI's quit value ends
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
      "execute EnterAlternateScreen[],Clear[clear_type=All],Hide[],EnableBracketedPaste[]"
    private val leaveDefault =
      "execute DisableBracketedPaste[],Show[],LeaveAlternateScreen[]"

    /** The calls of `fake` without the hook calls. */
    // claude 2026-10-19: created (P3, A1): the hook of the run comes first
    private def terminalCalls(fake: FakeCrossterm): Seq[String] =
      fake.calls.filterNot(_.startsWith("hook"))

    /** The Counter of the fixtures: `+`, `-`, and `q` quits (`Tui.quit`). */
    private val counter: (Unit < Sync) => UI < (Async & Scope) =
      _ => Counter.app

    /** A UI whose key `p` runs `onP` and whose key `q` quits. */
    private def keys(onP: => Unit): (Unit < Sync) => UI < (Async & Scope) =
      quit =>
        div.onKeyDown { (e: KeyboardEvent) =>
          (e.key match
              case Keyboard.Char('p') => Sync.defer(onP)
              case Keyboard.Char('q') => quit
              case _                  => Kyo.unit
          ): Any < Async
        }("p prints, q quits")

    // claude 2026-10-13: changed (T1): Tui.runWith and the fixture Counter; the
    // screen shows the count; no SPEC section in the name
    "open and close in order".timeout(30.seconds) in {
      val fake  = FakeCrossterm(events = Seq(char('+'), char('+')))
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Fiber
        .initUnscoped(
          Tui.runWith(TuiOptions(), fake, hooks, () => err.stream)(counter),
        )
        .map { fiber =>
          eventually()(fake.screen.contains("Count: 2"))
            .andThen(Sync.defer(fake.push(char('q'))))
            .andThen(fiber.get)
            .map { _ =>
              assert(fake.screen.contains("Count: 2"), fake.screen.text)
              // claude 2026-10-19: changed (P3, A1): the hook comes first and
              // goes last; the flush comes before raw mode ends
              assert(
                fake.calls == Seq(
                  "hook-added tui4s-restore",
                  "load",
                  "raw-mode-on",
                  enterDefault,
                  "backend",
                  leaveDefault,
                  "flush",
                  "raw-mode-off",
                  "hook-removed tui4s-restore",
                ),
                fake.calls.mkString("\n"),
              )
              assert(hooks.added.size == 1 && hooks.removed.size == 1)
            }
        }
    }

    // claude 2026-10-13: changed (T1): the leaf of the modes: no inline
    // viewport, no mouse and no focus mode (D-22, D-30); paste = false
    "paste = false: no bracketed paste mode".timeout(30.seconds) in {
      val fake = FakeCrossterm(events = Seq(char('q')))
      val err  = FakeErr(fake.log)
      Tui
        .runWith(
          TuiOptions(paste = false),
          fake,
          FakeHooks(fake.log),
          () => err.stream,
        )(counter)
        .map { _ =>
          val calls = fake.calls.filter(_.startsWith("execute"))
          assert(
            calls == Seq(
              "execute EnterAlternateScreen[],Clear[clear_type=All],Hide[]",
              "execute Show[],LeaveAlternateScreen[]",
            ),
            calls.mkString("\n"),
          )
        }
    }

    // claude 2026-10-13: changed (T1): Tui.runWith and the fixture Counter
    // (TuiRun and CounterApp of PR 5 are not carried)
    "no terminal: NotATerminal before anything else changes".timeout(
      30.seconds,
    ) in {
      val fake  = FakeCrossterm(failAt = "raw")
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Abort
        .run(Tui.runWith(TuiOptions(), fake, hooks, () => err.stream)(counter))
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
          // claude 2026-10-19: changed (P3, A1): the hook of the run is added
          // before the terminal changes and removed again
          assert(
            terminalCalls(fake) == Seq("load", "raw-mode-on"),
            fake.calls.toString,
          )
          assert(hooks.added.size == 1 && hooks.removed.size == 1)
          // The capture was stopped: a new one takes the output.
          val sink  = LogSink(None)
          val c     = OutputCapture.start(sink)
          val taken = c.capturing
          c.stop()
          assert(taken, "the output capture was not stopped")
        }
    }

    // claude 2026-10-13: changed (T1): Tui.runWith and the fixture Counter
    // (TuiRun and CounterApp of PR 5 are not carried)
    "stdin is not a terminal: NotATerminal before raw mode".timeout(
      30.seconds,
    ) in {
      // crossterm would take /dev/tty: raw mode alone does not see this.
      val fake  = FakeCrossterm(stdinTty = false)
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Abort
        .run(Tui.runWith(TuiOptions(), fake, hooks, () => err.stream)(counter))
        .map { r =>
          assert(
            r match
                case Result.Failure(e @ TuiError.NotATerminal(_)) =>
                  e.getMessage == "stdin/stdout is not a terminal" &&
                  String.valueOf(e.getCause).contains("stdin is not a terminal")
                case _                                            => false
            ,
            r.toString,
          )
          // claude 2026-10-19: changed (P3, A1): the hook of the run is added
          // before the terminal changes and removed again
          assert(terminalCalls(fake) == Seq("load"), fake.calls.toString)
          assert(hooks.added.size == 1 && hooks.removed.size == 1)
        }
    }

    // claude 2026-10-13: changed (T1): the UI construction fails (AppFailed is
    // not carried): the failure comes out as a panic after the restore
    "a failing UI construction restores the streams too".timeout(
      30.seconds,
    ) in {
      val broken: (Unit < Sync) => UI < (Async & Scope) = _ =>
        Sync.defer {
          java.lang.System.out.println("before the failure")
          throw IllegalStateException("boom")
        }
      val fake                                          = FakeCrossterm()
      val err                                           = FakeErr(fake.log)
      Abort
        .run(
          Tui
            .runWith(TuiOptions(), fake, FakeHooks(fake.log), () => err.stream)(
              broken,
            ),
        )
        .map { r =>
          assert(
            r match
                case Result.Panic(e: IllegalStateException) =>
                  e.getMessage == "boom"
                case _                                      => false
            ,
            r.toString,
          )
          assert(err.lines.contains("before the failure"), err.lines.toString)
          val sink  = LogSink(None)
          val c     = OutputCapture.start(sink)
          val taken = c.capturing
          c.stop()
          assert(taken, "the output capture was not stopped")
        }
    }

    // claude 2026-10-13: changed (T1): Tui.runWith and the fixture Counter
    // (TuiRun and CounterApp of PR 5 are not carried)
    "a native library that cannot load: NativeAccessDenied".timeout(
      30.seconds,
    ) in {
      val fake = FakeCrossterm(failAt = "load")
      Abort
        .run(Tui.runWith(TuiOptions(), fake, FakeHooks(fake.log))(counter))
        .map { r =>
          assert(
            r match
                case Result.Failure(TuiError.NativeAccessDenied(_)) => true
                case _                                              => false
            ,
            r.toString,
          )
          assert(terminalCalls(fake) == Seq("load"), fake.calls.toString)
        }
    }

    // claude 2026-10-13: created (T1, bot fix Crossterm.scala:103)
    "a native library that cannot be found: NativeLoadFailed with the cause"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(failAt = "link")
      Abort
        .run(Tui.runWith(TuiOptions(), fake, FakeHooks(fake.log))(counter))
        .map { r =>
          assert(
            r match
                case Result.Failure(e @ TuiError.NativeLoadFailed(_)) =>
                  e.getMessage.contains("no crossterm") &&
                  !e.getMessage.contains("--enable-native-access")
                case _                                                => false
            ,
            r.toString,
          )
          assert(terminalCalls(fake) == Seq("load"), fake.calls.toString)
        }
    }

    // claude 2026-10-13: changed (T1): Tui.runWith and the fixture Counter
    // (TuiRun and CounterApp of PR 5 are not carried)
    "a failing mode command: TerminalIO, raw mode is left again".timeout(
      30.seconds,
    ) in {
      val fake = FakeCrossterm(failAt = "enter")
      Abort
        .run(Tui.runWith(TuiOptions(), fake, FakeHooks(fake.log))(counter))
        .map { r =>
          assert(
            r match
                case Result.Failure(TuiError.TerminalIO(_)) => true
                case _                                      => false
            ,
            r.toString,
          )
          // claude 2026-10-19: changed (P3, A1): the flush before raw mode ends
          assert(
            terminalCalls(fake) == Seq(
              "load",
              "raw-mode-on",
              enterDefault,
              leaveDefault,
              "flush",
              "raw-mode-off",
            ),
            fake.calls.mkString("\n"),
          )
        }
    }

    // claude 2026-10-13: changed (T1): Tui.runWith and the fixture Counter
    // (TuiRun and CounterApp of PR 5 are not carried)
    "a failing release step does not stop the others; the error is printed"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(failAt = "leave", events = Seq(char('q')))
      val err  = FakeErr(fake.log)
      Tui
        .runWith(TuiOptions(), fake, FakeHooks(fake.log), () => err.stream)(
          counter,
        )
        .map { _ =>
          val calls = fake.calls
          assert(
            calls.containsSlice(
              Seq(
                leaveDefault,
                "flush",
                "raw-mode-off",
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

    // claude 2026-10-13: created (PR #14 review: Codex, TerminalIO.scala:138)
    "a LinkageError in a release step does not stop the others either: raw mode ends, the hook goes, the error is printed"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(failAt = "leave-link", events = Seq(char('q')))
      val err  = FakeErr(fake.log)
      Tui
        .runWith(TuiOptions(), fake, FakeHooks(fake.log), () => err.stream)(
          counter,
        )
        .map { _ =>
          val calls = fake.calls
          assert(
            calls.containsSlice(
              Seq(
                leaveDefault,
                "flush",
                "raw-mode-off",
                "hook-removed tui4s-restore",
              ),
            ),
            calls.mkString("\n"),
          )
          assert(
            err.lines.exists(l =>
              l.contains("restoring the terminal failed") &&
                l.contains("UnsatisfiedLinkError"),
            ),
            err.lines.mkString("\n"),
          )
        }
    }

    // claude 2026-10-13: changed (T1): the print is in a key handler of a
    // kyo-ui UI (keys); Tui.runWith
    "output while the UI is up is printed after the restore".timeout(
      30.seconds,
    ) in {
      val noisy = keys {
        java.lang.System.out.println("hello from a handler")
        java.lang.System.err.println("to stderr")
      }
      val fake  = FakeCrossterm(events = Seq(char('p'), char('q')))
      val err   = FakeErr(fake.log)
      Tui
        .runWith(TuiOptions(), fake, FakeHooks(fake.log), () => err.stream)(
          noisy,
        )
        .map { _ =>
          val calls = fake.calls
          val flush = calls.indexOf("flush")
          val hello = calls.indexOf("err: hello from a handler")
          val toErr = calls.indexOf("err: to stderr")
          assert(
            flush >= 0 && hello > flush && toErr > flush,
            calls.mkString("\n"),
          )
        }
    }

    // claude 2026-10-13: changed (T1): the print is in a key handler of a
    // kyo-ui UI (keys); Tui.runWith
    "captureOutput = false: the output is not taken".timeout(30.seconds) in {
      val app  = keys(java.lang.System.out.println("tui4s test: not captured"))
      val fake = FakeCrossterm(events = Seq(char('p'), char('q')))
      val err  = FakeErr(fake.log)
      Tui
        .runWith(
          TuiOptions(captureOutput = false),
          fake,
          FakeHooks(fake.log),
          () => err.stream,
        )(app)
        .map(_ => assert(!err.lines.exists(_.contains("not captured"))))
    }

    // claude 2026-10-13: changed (T1): a fiber of the UI logs, then quits (the
    // sources of the old engine are not carried)
    "Kyo log lines at warn or above are printed after the restore".timeout(
      30.seconds,
    ) in {
      val app: (Unit < Sync) => UI < (Async & Scope) = quit =>
        Fiber
          .init(
            Log.warn("careful now").andThen(Log.info("detail")).andThen(quit),
          )
          .andThen(div("x"))
      val fake                                       = FakeCrossterm()
      val err                                        = FakeErr(fake.log)
      Tui
        .runWith(TuiOptions(), fake, FakeHooks(fake.log), () => err.stream)(app)
        .andThen(eventually()(err.lines.exists(_.contains("careful now"))))
        .map { seen =>
          assert(seen, fake.calls.mkString("\n"))
          val line = err.lines.find(_.contains("careful now")).getOrElse("")
          assert(line.contains("WARN"), line)
          assert(!err.lines.exists(_.contains("detail")))
        }
    }

    // claude 2026-10-13: changed (T1): the print is in a key handler of a
    // kyo-ui UI (keys); Tui.runWith
    "with a log file, the output and the log go to the file".timeout(
      30.seconds,
    ) in {
      Sync.defer(Files.createTempFile("tui4s-log", ".txt")).map { file =>
        val app  = keys(java.lang.System.out.println("into the file"))
        val fake = FakeCrossterm(events = Seq(char('p'), char('q')))
        val err  = FakeErr(fake.log)
        Tui
          .runWith(
            TuiOptions(logFile = Some(file)),
            fake,
            FakeHooks(fake.log),
            () => err.stream,
          )(app)
          .andThen(Sync.defer(Files.readString(file, StandardCharsets.UTF_8)))
          .map { text =>
            assert(text.contains("into the file"), text)
            assert(!err.lines.exists(_.contains("into the file")))
          }
      }
    }

    // claude 2026-10-13: changed (T1): a fiber of the UI logs, then quits (the
    // sources of the old engine are not carried)
    "the Kyo log of the run has the level of the caller's logger".timeout(
      30.seconds,
    ) in {
      Sync.defer(Files.createTempFile("tui4s-log", ".txt")).map { file =>
        val app: (Unit < Sync) => UI < (Async & Scope) = quit =>
          Fiber
            .init(Log.info("detail at info").andThen(quit))
            .andThen(div("x"))
        val fake                                       = FakeCrossterm()
        val err                                        = FakeErr(fake.log)
        Log
          .withConsoleLogger("app", Log.Level.info)(
            Tui.runWith(
              TuiOptions(logFile = Some(file)),
              fake,
              FakeHooks(fake.log),
              () => err.stream,
            )(app),
          )
          .andThen(Sync.defer(Files.readString(file, StandardCharsets.UTF_8)))
          .map(text => assert(text.contains("detail at info"), text))
      }
    }

    // claude 2026-10-13: changed (T1): a fiber of the UI logs every line, then
    // quits
    "every Kyo log line of the run is in the log file when the run returns"
      .timeout(30.seconds) in {
      Sync.defer(Files.createTempFile("tui4s-log", ".txt")).map { file =>
        val lines                                      = 3000
        // Kyo writes the lines later, on its own fiber: the release can come
        // before the last of them.
        val logAll                                     = Kyo.foreachDiscard(1 to lines)(i =>
          Log.warn(s"line $i of the run " + "x" * 200),
        )
        val app: (Unit < Sync) => UI < (Async & Scope) =
          quit => Fiber.init(logAll.andThen(quit)).andThen(div("x"))
        val fake                                       = FakeCrossterm()
        val err                                        = FakeErr(fake.log)
        Tui
          .runWith(
            TuiOptions(logFile = Some(file)),
            fake,
            FakeHooks(fake.log),
            () => err.stream,
          )(app)
          .andThen(Sync.defer(Files.readString(file, StandardCharsets.UTF_8)))
          .map { text =>
            val written = text.linesIterator.count(_.contains("of the run"))
            assert(written == lines, s"$written of $lines lines in the file")
            assert(!err.lines.exists(_.contains("of the run")))
          }
      }
    }

    // claude 2026-10-13: changed (T1): Tui.runWith; the release closes the
    // inbox, and the run ends as a quit does (it ended as an interrupt)
    "the shutdown hook restores the terminal while the app runs".timeout(
      30.seconds,
    ) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      val err   = FakeErr(fake.log)
      Fiber
        .initUnscoped(
          Tui.runWith(TuiOptions(), fake, hooks, () => err.stream)(counter),
        )
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
            .map { r =>
              val calls = fake.calls
              assert(
                calls.count(_ == "raw-mode-off") == 1 &&
                  calls
                    .containsSlice(Seq(leaveDefault, "flush", "raw-mode-off")),
                calls.mkString("\n"),
              )
              assert(hooks.removed.isEmpty, "a running hook is not removed")
              // The release closed the inbox under the runner: its next take
              // ends the run, as a quit does.
              assert(r == Result.succeed(()), r.toString)
            }
        }
    }

    // claude 2026-10-13: changed (T1): replaces the Tui.start leaf of PR 5: a
    // fiber of the UI sets the count, then quits; the screen shows its writes
    "the UI's quit value ends the run; the screen showed the UI's own writes"
      .timeout(30.seconds) in {
      val fake                                       = FakeCrossterm()
      val app: (Unit < Sync) => UI < (Async & Scope) = quit =>
        Signal.initRef(0).map { count =>
          Fiber
            .init(
              count
                .set(6)
                .andThen(Async.sleep(300.millis))
                .andThen(quit),
            )
            .andThen(Counter.view(count))
        }
      Tui
        .runWith(TuiOptions(), fake, FakeHooks(fake.log))(app)
        .map { _ =>
          assert(fake.screen.contains("Count: 6"), fake.screen.text)
          assert(
            fake.calls
              .containsSlice(Seq(leaveDefault, "flush", "raw-mode-off")),
            fake.calls.mkString("\n"),
          )
        }
    }
end TerminalIOTest
