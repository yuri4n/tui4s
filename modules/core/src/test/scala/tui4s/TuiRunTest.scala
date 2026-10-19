package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui.crossterm.KeyCode
import tui4s.EventMappingTest.{Ctrl, key}
import tui4s.fixtures.Counter

/** `Tui.run` (RC-2) on a fake crossterm, through its seam `Tui.runWith`: a run
  * starts and a scripted key quits it, Ctrl-C quits it (or reaches the
  * handlers), the quit value ends it, and in every way that a run ends (a quit,
  * an interrupt, a UI that fails, an input or output failure) the terminal was
  * entered and is left again. Only the native crossterm is not here
  * (NativeTerminalTest, the PTY kit).
  */
// claude 2026-10-13: created (T1, RC-2)
class TuiRunTest extends Test[Any]:
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

    private val counter: (Unit < Sync) => UI < (Async & Scope) =
      _ => Counter.app

    /** The terminal was entered, then left; the shutdown hook was added before
      * the terminal changed, and removed after raw mode ended.
      */
    // claude 2026-10-19: changed (P3, A1): the order of the release stack
    private def enteredAndLeft(fake: FakeCrossterm): Boolean =
        val c     = fake.calls
        val enter = c.indexWhere(_.startsWith("execute EnterAlternateScreen"))
        val leave = c.indexWhere(_.startsWith("execute DisableBracketedPaste"))
        val added = c.indexOf("hook-added tui4s-stop")
        enter >= 0 && leave > enter &&
        c.indexOf("raw-mode-on") < enter &&
        c.lastIndexOf("raw-mode-off") > leave &&
        c.count(_ == "raw-mode-off") == 1 &&
        added >= 0 && added < c.indexOf("load") &&
        c.indexOf("hook-removed tui4s-stop") > c.lastIndexOf("raw-mode-off")
    end enteredAndLeft

    "a run starts, a scripted key quits it: the terminal is entered, then left"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(events = Seq(char('+'), char('q')))
      Tui
        .runWith(TuiOptions(), fake, FakeHooks(fake.log))(counter)
        .map { _ =>
          assert(fake.screen.contains("Count: 1"), fake.screen.text)
          assert(enteredAndLeft(fake), fake.calls.mkString("\n"))
        }
    }

    "Ctrl-C quits the run; with ctrlCQuits = false it is a key of the handlers"
      .timeout(30.seconds) in {
      val ctrlC                                       = key(KeyCode.Char('c'), Ctrl)
      val seen: (Unit < Sync) => UI < (Async & Scope) = quit =>
        Signal.initRef("none").map { last =>
          div.onKeyDown { (e: KeyboardEvent) =>
            (e match
                case KeyboardEvent(Keyboard.Char('c'), m, _) if m.ctrl =>
                  last.set("ctrl-c")
                case KeyboardEvent(Keyboard.Char('q'), _, _)           => quit
                case _                                                 => Kyo.unit
            ): Any < Async
          }(span("last: ", last.map(identity)))
        }
      val quits                                       = FakeCrossterm(events = Seq(ctrlC))
      val keeps                                       = FakeCrossterm(events = Seq(ctrlC))
      for
          _     <- Tui.runWith(TuiOptions(), quits, FakeHooks(quits.log))(seen)
          fiber <- Fiber.initUnscoped(
                     Tui.runWith(
                       TuiOptions(ctrlCQuits = false),
                       keeps,
                       FakeHooks(keeps.log),
                     )(seen),
                   )
          shown <- eventually()(keeps.screen.contains("last: ctrl-c"))
          _     <- Sync.defer(keeps.push(char('q')))
          _     <- fiber.get
      yield
          assert(enteredAndLeft(quits), quits.calls.mkString("\n"))
          assert(quits.screen.contains("last: none"), quits.screen.text)
          assert(shown, keeps.screen.text)
          assert(enteredAndLeft(keeps), keeps.calls.mkString("\n"))
      end for
    }

    "the quit value that the UI gets ends the run".timeout(30.seconds) in {
      val fake                                       = FakeCrossterm()
      val app: (Unit < Sync) => UI < (Async & Scope) = quit =>
        Fiber.init(Async.sleep(200.millis).andThen(quit)).andThen(div("x"))
      Tui
        .runWith(TuiOptions(), fake, FakeHooks(fake.log))(app)
        .map(_ => assert(enteredAndLeft(fake), fake.calls.mkString("\n")))
    }

    "an interrupted run leaves the terminal".timeout(30.seconds) in {
      val fake  = FakeCrossterm()
      val hooks = FakeHooks(fake.log)
      Fiber
        .initUnscoped(Tui.runWith(TuiOptions(), fake, hooks)(counter))
        .map { fiber =>
          eventually()(fake.screen.contains("Count: 0"))
            .andThen(fiber.interrupt)
            // OS-34: a bounded poll of `done`, never a join of the run
            .andThen(Loop(0) { n =>
              fiber.done.map { d =>
                if d || n >= 500 then Loop.done(d)
                else Async.sleep(10.millis).andThen(Loop.continue(n + 1))
              }
            })
            .andThen(fiber.getResult)
            .map { r =>
              assert(
                r match
                    case Result.Panic(_: Interrupted) => true
                    case _                            => false
                ,
                r.toString,
              )
              assert(enteredAndLeft(fake), fake.calls.mkString("\n"))
              assert(hooks.removed.size == 1)
            }
        }
    }

    "a UI that fails to build leaves the terminal, and its error comes out as it is"
      .timeout(30.seconds) in {
      val fake                                          = FakeCrossterm()
      val broken: (Unit < Sync) => UI < (Async & Scope) =
        _ => Sync.defer(throw IllegalStateException("no UI"))
      Abort
        .run(Tui.runWith(TuiOptions(), fake, FakeHooks(fake.log))(broken))
        .map { r =>
          assert(
            r match
                case Result.Panic(e: IllegalStateException) =>
                  e.getMessage == "no UI"
                case _                                      => false
            ,
            r.toString,
          )
          assert(enteredAndLeft(fake), fake.calls.mkString("\n"))
        }
    }

    "the input fails: the run ends with TerminalIO and leaves the terminal"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(failAt = "poll")
      Abort
        .run(Tui.runWith(TuiOptions(), fake, FakeHooks(fake.log))(counter))
        .map { r =>
          assert(
            r match
                case Result.Failure(TuiError.TerminalIO(e)) =>
                  e.getMessage == "tty gone"
                case _                                      => false
            ,
            r.toString,
          )
          assert(enteredAndLeft(fake), fake.calls.mkString("\n"))
        }
    }

    "a write to the terminal fails: the run ends with TerminalIO and leaves the terminal"
      .timeout(30.seconds) in {
      val fake = FakeCrossterm(failAt = "draw")
      Abort
        .run(Tui.runWith(TuiOptions(), fake, FakeHooks(fake.log))(counter))
        .map { r =>
          assert(
            r match
                case Result.Failure(TuiError.TerminalIO(e)) =>
                  e.getMessage == "broken pipe"
                case _                                      => false
            ,
            r.toString,
          )
          assert(enteredAndLeft(fake), fake.calls.mkString("\n"))
        }
    }

    "Tui.run and Tui.run(options) take the UI with its quit value" in {
      // Built, not run: these need a real terminal (the PTY kit runs them).
      val plain: Unit < (Async & Abort[TuiError])       =
        Tui.run(quit => div.onClick(quit)("x"))
      val withOptions: Unit < (Async & Abort[TuiError]) =
        Tui.run(TuiOptions(paste = false))(_ => Counter.app)
      discard((plain, withOptions))
      assert(true)
    }

    private def discard(a: Any): Unit =
        val _ = a
end TuiRunTest
