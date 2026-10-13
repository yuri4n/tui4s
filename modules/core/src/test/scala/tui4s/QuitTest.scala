package tui4s

import kyo.*
import kyo.UI.*
import tui4s.testkit.Headless

/** `Tui.quit` (RC-2 part): it ends the run from a key handler, from a fiber
  * that a handler started, and from the construction of the UI (in a forked
  * fiber); outside a run it does nothing.
  */
// claude 2026-10-13: created (C3)
class QuitTest extends RunnerSuite:

    "a key handler calls Tui.quit: the run ends" in {
      for
          h   <- Headless.start(20, 3)(_ =>
                   div.onKeyDown {
                     case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
                     case _                                       => ()
                   }("x"),
                 )
          f   <- h.char('q')
          fin <- h.awaitEnd(10.seconds)
      yield assert(fin && f.cause == FrameCause.Input, s"ended: $fin, $f")
    }

    "a fiber that a handler forks calls Tui.quit after a delay: the run ends" in {
      for
          h   <- Headless.start(20, 3)(_ =>
                   div.onKeyDown {
                     case KeyboardEvent(Keyboard.Char('f'), _, _) =>
                       // claude 2026-10-13: changed (review R1-3): 1 s, well
                       // above the settle max (100 ms), so the quit cannot
                       // come before the check of `now` under load
                       Fiber
                         .initUnscoped(
                           Async.sleep(1.second).andThen(Tui.quit),
                         )
                         .unit
                     case _                                       => ()
                   }("x"),
                 )
          _   <- h.char('f')
          // the handler returned before the quit: the run still runs
          now <- h.finished
          fin <- h.awaitEnd(10.seconds)
      yield assert(!now && fin, s"ended at once: $now, ended later: $fin")
    }

    "the construction of the UI forks a fiber that calls Tui.quit after a delay: the run ends" in {
      for
          h   <- Headless.start(20, 3)(_ =>
                   Fiber
                     .init(Async.sleep(200.millis).andThen(Tui.quit))
                     .andThen(div("x")),
                 )
          s0  <- h.screen
          _    = assert(s0.contains("x"), s0.text)
          fin <- h.awaitEnd(10.seconds)
      yield assert(fin, "the run did not end")
    }

    "Tui.quit outside a run does nothing" in {
      for
          h   <- Headless.start(20, 3)(_ => div("x"))
          _   <- Tui.quit // the test fiber: no run in scope
          _   <- Async.sleep(200.millis)
          fin <- h.finished
      yield assert(!fin, "a quit outside the run ended it")
    }
end QuitTest
