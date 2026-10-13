package tui4s

import kyo.*
import kyo.UI.*
import tui4s.testkit.Headless

/** The key routing of the runner (`tui4s.internal.Dispatch`): the quit key, the
  * focus moves, bubbling and `stopPropagation`.
  */
// claude 2026-10-13: created (C3)
class DispatchTest extends RunnerSuite:

    "Ctrl-C ends the run (TuiOptions.ctrlCQuits); with ctrlCQuits = false it is a key for the handlers" in {
      for
          h    <- Headless.start(20, 3)(_ => div("x"))
          f    <- h.key(Keyboard.Char('c'), ctrl = true)
          fin  <- h.awaitEnd(10.seconds)
          _     = assert(fin && f.cause == FrameCause.Input, s"$f")
          seen <- AtomicInt.init(0)
          h2   <- Headless.start(20, 3, TuiOptions(ctrlCQuits = false))(_ =>
                    div.onKeyDown {
                      case KeyboardEvent(Keyboard.Char('c'), m, _) if m.ctrl =>
                        seen.incrementAndGet
                      case _                                                 => ()
                    }("x"),
                  )
          _    <- h2.key(Keyboard.Char('c'), ctrl = true)
          n    <- seen.get
          fin2 <- h2.finished
      yield assert(n == 1 && !fin2, s"handler runs: $n, run ended: $fin2")
    }

    /** The focus of the frame `f` (the id of the focused element). */
    private def focusOf(f: FrameStats): String = f.focus

    "Tab moves in document order (a positive tabIndex first) and wraps; Shift-Tab goes back" in {
      for
          h  <- Headless.start(30, 3)(_ =>
                  div.style(_.row)(
                    button("a").id("a"),
                    button("b").id("b"),
                    button("c").id("c").tabIndex(1),
                  ),
                )
          fs <- Kyo.foreach(1 to 4)(_ => h.key(Keyboard.Tab))
          bs <- Kyo.foreach(1 to 2)(_ => h.key(Keyboard.Tab, shift = true))
      yield
          val order = (fs ++ bs).map(focusOf)
          assert(order == Seq("a", "b", "c", "a", "c", "b"), s"$order")
    }

    "a focus group: ArrowLeft and ArrowRight move inside the group and wrap; outside a group they do nothing" in {
      for
          h  <- Headless.start(40, 3)(_ =>
                  div.style(_.row)(
                    button("x").id("x").focusGroup("g"),
                    button("y").id("y").focusGroup("g"),
                    button("z").id("z").focusGroup("g"),
                    button("o").id("o"),
                  ),
                )
          rs <- Kyo.foreach(1 to 3)(_ => h.key(Keyboard.ArrowRight))
          ls <- Kyo.foreach(1 to 2)(_ => h.key(Keyboard.ArrowLeft))
          _  <- h.key(Keyboard.Tab).andThen(h.key(Keyboard.Tab))
          o  <- h.key(Keyboard.ArrowRight)
      yield
          val moves = (rs ++ ls :+ o).map(focusOf)
          assert(moves == Seq("y", "z", "x", "z", "y", "o"), s"$moves")
    }

    "a focus trap: Tab and Shift-Tab wrap among the focusables inside it" in {
      for
          h  <- Headless.start(60, 3)(_ =>
                  div.style(_.row)(
                    button("out1").id("out1"),
                    div
                      .focusTrap(true)
                      .style(_.row)(
                        button("in1").id("in1"),
                        button("in2").id("in2"),
                      ),
                    button("out2").id("out2"),
                  ),
                )
          fs <- Kyo.foreach(1 to 4)(_ => h.key(Keyboard.Tab))
          b  <- h.key(Keyboard.Tab, shift = true)
      yield
          val order = (fs :+ b).map(focusOf)
          assert(order == Seq("in1", "in2", "in1", "in2", "in1"), s"$order")
    }

    "stopPropagation(true) on a handler stops the parent's onKeyDown; without it both run (child first)" in {
      def run(stop: Boolean)(using Frame): Vector[String] < (Async & Scope) =
        for
            log <- AtomicRef.init(Vector.empty[String])
            h   <- Headless.start(20, 3)(_ =>
                     div.onKeyDown(_ => log.updateAndGet(_ :+ "parent"))(
                       div
                         .onKeyDown(_ => log.updateAndGet(_ :+ "child"))
                         .stopPropagation(stop)(button("b")),
                     ),
                   )
            _   <- h.char('k')
            l   <- log.get
        yield l
      for
          stopped <- run(stop = true)
          both    <- run(stop = false)
      yield assert(
        stopped == Vector("child") && both == Vector("child", "parent"),
        s"with stopPropagation: $stopped; without: $both",
      )
    }

    "onBlur of the old element and onFocus of the new one run on a Tab" in {
      for
          log <- AtomicRef.init(Vector.empty[String])
          h   <- Headless.start(30, 3)(_ =>
                   div.style(_.row)(
                     button("a")
                       .onFocus(log.updateAndGet(_ :+ "focus a"))
                       .onBlur(log.updateAndGet(_ :+ "blur a")),
                     button("b")
                       .onFocus(log.updateAndGet(_ :+ "focus b"))
                       .onBlur(log.updateAndGet(_ :+ "blur b")),
                   ),
                 )
          l0  <- log.get // the initial focus (autoFocus) runs no handler
          _   <- h.key(Keyboard.Tab)
          l1  <- log.get
      yield assert(
        l0.isEmpty && l1 == Vector("blur a", "focus b"),
        s"before: $l0; after Tab: $l1",
      )
    }

    "a disabled element: Tab skips it; as the key target its onKeyDown and its click do not run" in {
      for
          n    <- AtomicInt.init(0)
          h    <- Headless.start(40, 3)(_ =>
                    div.style(_.row)(
                      button("a").id("a"),
                      button("b")
                        .id("b")
                        .disabled(true)
                        .onKeyDown(_ => n.incrementAndGet),
                      button("c").id("c"),
                    ),
                  )
          t    <- h.key(Keyboard.Tab)
          _     = assert(t.focus == "c", s"focus after Tab: ${t.focus}")
          // no focusable element: the key goes to the first element, a
          // disabled button
          h2   <- Headless.start(20, 3, TuiOptions(autoFocus = false))(_ =>
                    button("d")
                      .disabled(true)
                      .onKeyDown(_ => n.incrementAndGet)
                      .onClick(n.incrementAndGet),
                  )
          _    <- h2.char('k')
          _    <- h2.key(Keyboard.Enter)
          runs <- n.get
      yield assert(runs == 0, s"handler runs of a disabled element: $runs")
    }
end DispatchTest
