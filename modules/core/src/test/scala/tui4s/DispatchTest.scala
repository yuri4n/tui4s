package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.{Dispatch, Kind, Layout, Size, World}
import tui4s.testkit.Headless

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The key routing of the runner (`tui4s.internal.Dispatch`): the quit key, the
  * focus moves, bubbling and `stopPropagation`, a handler that fails.
  */
// claude 2026-10-13: created (C3); changed: the leaves of the reviews R2-1 and
// R2-3
class DispatchTest extends RunnerSuite:

    "Ctrl-C ends the run (TuiOptions.ctrlCQuits); with ctrlCQuits = false it is a key for the handlers" in {
      for
          h    <- Headless.start(20, 3)(_ => div("x"))
          f    <- h.key(Keyboard.Char('c'), Modifiers(ctrl = true))
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
          _    <- h2.key(Keyboard.Char('c'), Modifiers(ctrl = true))
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
          bs <- Kyo.foreach(1 to 2)(_ => h.key(Keyboard.Tab, Modifiers(shift = true)))
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
          b  <- h.key(Keyboard.Tab, Modifiers(shift = true))
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

    // claude 2026-10-13: created (review R2-3)
    "a disabled key target: its stopPropagation does not stop the bubbling, as its handler does not run (kyo-ui's rule)" in {
      // The runner never leaves the focus on a disabled element (each layout
      // step moves it away), so this leaf builds that world for Dispatch.
      for
          log   <- AtomicRef.init(Vector.empty[String])
          ui     = div.onKeyDown(_ => log.updateAndGet(_ :+ "parent"))(
                     button("d")
                       .id("d")
                       .disabled(true)
                       .onKeyDown(_ => log.updateAndGet(_ :+ "target"))
                       .stopPropagation(true),
                   )
          w0     = World.init(ui, Size(20, 3))
          laid   = Layout.layoutAll(ui, w0.env)
          target = laid.nodes.collectFirst {
                     case (p, n) if (n.kind match
                             case Kind.El(e) =>
                               e.attrs.identifier.toOption.contains("d")
                             case _          => false
                         ) =>
                       p
                   }
          _     <- Dispatch(Kyo.unit).onKey(
                     w0.copy(laid = laid, focus = target),
                     KeyboardEvent(Keyboard.Char('k'), Modifiers.none, Absent),
                   )
          l     <- log.get
      yield assert(
        target.nonEmpty && l == Vector("parent"),
        s"target: $target; handlers that ran: $l",
      )
    }

    /** Waits (at most `timeout`, a poll every 5 ms on the live clock) until `q`
      * holds `n` elements.
      */
    // claude 2026-10-13: created (review R2-1)
    private def awaitSize(
        q: ConcurrentLinkedQueue[String],
        n: Int,
        timeout: Duration,
    )(using Frame): Unit < Async =
      now.map { t0 =>
        Loop.foreach {
          now.map { t =>
            if q.size >= n || t - t0 > timeout.toNanos then Loop.done
            else
                Clock
                  .let(Clock.live)(Async.sleep(5.millis))
                  .andThen(Loop.continue)
          }
        }
      }

    // claude 2026-10-13: created (review R2-1)
    "a handler that fails or panics is logged (Kyo's Log) and the event goes on; a failing onInput keeps the bound write" in {
      val errors = new ConcurrentLinkedQueue[String]()
      for
          after <- AtomicInt.init(0)
          ref   <- Signal.initRef("")
          h     <- Log.let(Log(ErrorRecorder(errors))) {
                     Headless.start(30, 3)(_ =>
                       div.onKeyDown {
                         case KeyboardEvent(Keyboard.Char('t'), _, _) =>
                           Sync.defer(throw new IllegalStateException("t"))
                         case KeyboardEvent(Keyboard.Char('p'), _, _) =>
                           Abort.panic(new IllegalStateException("p"))
                         case _                                       =>
                           after.incrementAndGet
                       }(
                         input
                           .value(ref)
                           .onInput(_ =>
                             Sync.defer(throw new IllegalStateException("i")),
                           )
                           .style(_.width(10.px)),
                       ),
                     )
                   }
          fs    <- h.typeText("tpa")
          fin   <- h.finished
          n     <- after.get
          text  <- ref.get
          // Kyo's Log can write on its own daemon fiber: wait for the lines
          _     <- awaitSize(errors, 5, 5.seconds)
      yield
          val logged = errors.asScala.toVector
          assert(
            fs.map(_.cause) == Seq.fill(3)(FrameCause.Input) && !fin,
            s"frames: ${fs.map(_.cause)}; run ended: $fin",
          )
          assert(n == 1 && text == "tpa", s"handler after: $n; text: $text")
          assert(
            logged.sorted == Vector(
              "tui4s: onInput handler failed: i",
              "tui4s: onInput handler failed: i",
              "tui4s: onInput handler failed: i",
              "tui4s: onKeyDown handler failed: t",
              "tui4s: onKeyDown handler panicked: p",
            ),
            s"logged: $logged",
          )
      end for
    }
    // claude 2026-10-13: created the three leaves below (PR #13 review: Codex,
    // Dispatch.scala:401, :204, :180): the event forms of the handlers and the
    // click rule for a keyboard activation
    "a focus move runs both forms of onBlur and onFocus, blur first (kyo-ui's Focus and Blur rules)" in {
      for
          log <- AtomicRef.init(Vector.empty[String])
          h   <- Headless.start(30, 3)(_ =>
                   div.style(_.row)(
                     button("a")
                       .id("a")
                       .onFocus(log.updateAndGet(_ :+ "focus a"))
                       .onFocus((e: MouseEvent) =>
                         log.updateAndGet(
                           _ :+ s"focusEvt a ${e.targetId.getOrElse("-")}",
                         ),
                       )
                       .onBlur(log.updateAndGet(_ :+ "blur a"))
                       .onBlur((e: MouseEvent) =>
                         log.updateAndGet(
                           _ :+ s"blurEvt a ${e.targetId.getOrElse("-")}",
                         ),
                       ),
                     button("b")
                       .id("b")
                       .onFocus(log.updateAndGet(_ :+ "focus b"))
                       .onFocus((e: MouseEvent) =>
                         log.updateAndGet(
                           _ :+ s"focusEvt b ${e.targetId.getOrElse("-")}",
                         ),
                       ),
                   ),
                 )
          _   <- h.key(Keyboard.Tab)
          l   <- log.get
      yield assert(
        l == Vector("blur a", "blurEvt a a", "focus b", "focusEvt b b"),
        s"after Tab: $l",
      )
    }

    "a submit runs both forms of onSubmit once: Enter or Space on a button (the click rule), Enter on an anchor and in a text input; Space on an anchor does not submit" in {
      def run(key: Keyboard, child: UI)(using
          Frame,
      ): Vector[String] < (Async & Scope) =
        for
            log <- AtomicRef.init(Vector.empty[String])
            h   <- Headless.start(40, 4)(_ =>
                     form
                       .onSubmit(log.updateAndGet(_ :+ "submit"))
                       .onSubmit((_: MouseEvent) =>
                         log.updateAndGet(_ :+ "submitEvt"),
                       )(child),
                   )
            _   <- h.key(key)
            l   <- log.get
        yield l
      for
          draft    <- Signal.initRef("")
          btnEnter <- run(Keyboard.Enter, button("go"))
          btnSpace <- run(Keyboard.Space, button("go"))
          anchor   <- run(Keyboard.Enter, a("go"))
          text     <- run(Keyboard.Enter, input.value(draft))
          anchorSp <- run(Keyboard.Space, a("go"))
      yield
          val both = Vector("submit", "submitEvt")
          assert(
            btnEnter == both && btnSpace == both && anchor == both && text == both && anchorSp.isEmpty,
            s"button Enter: $btnEnter; button Space: $btnSpace; anchor Enter: $anchor; text Enter: $text; anchor Space: $anchorSp",
          )
      end for
    }

    "Enter on a button is a click that bubbles: the self forms on the target, then onClick and its event form on each ancestor, until stopPropagation" in {
      def run(stopAt: String)(using Frame): Vector[String] < (Async & Scope) =
        for
            log <- AtomicRef.init(Vector.empty[String])
            h   <- Headless.start(30, 3)(_ =>
                     div
                       .onClick(log.updateAndGet(_ :+ "outer"))
                       .onClick((_: MouseEvent) =>
                         log.updateAndGet(_ :+ "outerEvt"),
                       )(
                         div
                           .onClick(log.updateAndGet(_ :+ "inner"))
                           .onClickSelf(log.updateAndGet(_ :+ "inner self"))
                           .stopPropagation(stopAt == "inner")(
                             button("b")
                               .onClickSelf(log.updateAndGet(_ :+ "self"))
                               .onClickSelf((_: MouseEvent) =>
                                 log.updateAndGet(_ :+ "selfEvt"),
                               )
                               .onClick((_: MouseEvent) =>
                                 log.updateAndGet(_ :+ "evt"),
                               )
                               .onClick(log.updateAndGet(_ :+ "click"))
                               .stopPropagation(stopAt == "button"),
                           ),
                       ),
                   )
            _   <- h.key(Keyboard.Enter)
            l   <- log.get
        yield l
      for
          all      <- run(stopAt = "none")
          atInner  <- run(stopAt = "inner")
          atButton <- run(stopAt = "button")
      yield assert(
        all == Vector(
          "self",
          "selfEvt",
          "evt",
          "click",
          "inner",
          "outerEvt",
          "outer",
        )
          && atInner == Vector("self", "selfEvt", "evt", "click", "inner")
          && atButton == Vector("self", "selfEvt", "evt", "click"),
        s"no stop: $all; stop at the inner div: $atInner; stop at the button: $atButton",
      )
      end for
    }
end DispatchTest

/** A Kyo log backend that keeps the lines at level error (with the message of
  * the error) and writes nothing to the console.
  */
// claude 2026-10-13: created (review R2-1)
private final class ErrorRecorder(lines: ConcurrentLinkedQueue[String])
    extends Log.Unsafe:
    def level: Log.Level                                                       = Log.Level.error
    def name: String                                                           = "tui4s.test"
    def withName(name: String): Log.Unsafe                                     = this
    def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
      ()
    def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
      ()
    def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
    def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  =
      ()
    def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
    def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  =
      ()
    def error(msg: => String)(using Frame, AllowUnsafe): Unit                  =
        val _ = lines.add(msg)
    def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
        val _ = lines.add(s"$msg: ${t.getMessage}")
end ErrorRecorder
