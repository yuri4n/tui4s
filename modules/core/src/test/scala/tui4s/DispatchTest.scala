package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.{Dispatch, Kind, Layout, Size, World}
import tui4s.testkit.{Headless, Screen}

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
    private def focusOf(f: FrameStats): String = f.focus.getOrElse("")

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
          bs <- Kyo.foreach(1 to 2)(_ =>
                  h.key(Keyboard.Tab, Modifiers(shift = true)),
                )
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
          _     = assert(t.focus.contains("c"), s"focus after Tab: ${t.focus}")
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

    // claude 2026-10-19: created the helpers and the five leaves below (L1a)
    /** The spans "line 1" to "line n". */
    private def lines(n: Int)(using Frame): Seq[UI.Ast.HtmlChildVal] =
      (1 to n).map[UI.Ast.HtmlChildVal](i => span(s"line $i"))

    /** The numbers of the lines and rows that `s` shows, top first. */
    private def shown(s: Screen): Vector[Int] =
      "(?:line|row) (\\d+)".r
        .findAllMatchIn(s.text)
        .map(_.group(1).nn.toInt)
        .toVector

    "a viewport that scrolls is focusable (Tab reaches it); on it Down, PageDown, End, Up, PageUp and Home scroll it; at the end a key does nothing" in {
      for
          h  <- Headless.start(12, 5)(_ =>
                  div(
                    button("top").id("top"),
                    div
                      .id("log")
                      .style(_.height(3.px).overflowY(_.scroll))(
                        lines(10)*,
                      ),
                  ),
                )
          f  <- h.key(Keyboard.Tab)
          s0 <- h.screen
          ss <- Kyo.foreach(
                  Seq(
                    Keyboard.ArrowDown,
                    Keyboard.PageDown,
                    Keyboard.End,
                    Keyboard.PageDown,
                    Keyboard.ArrowUp,
                    Keyboard.PageUp,
                    Keyboard.Home,
                  ),
                )(k => h.key(k).andThen(h.screen))
      yield
          val seen = (s0 +: ss).map(shown)
          assert(focusOf(f) == "log", s"$f")
          assert(
            seen == Seq(
              Vector(1, 2, 3),
              Vector(2, 3, 4),
              Vector(5, 6, 7),
              Vector(8, 9, 10),
              Vector(8, 9, 10),
              Vector(7, 8, 9),
              Vector(4, 5, 6),
              Vector(1, 2, 3),
            ),
            s"$seen",
          )
    }

    "follow-focus: Tab to a button below the window scrolls the viewport the least distance that shows it; Shift-Tab scrolls back" in {
      for
          h  <- Headless.start(12, 4)(_ =>
                  div
                    .tabIndex(-1)
                    .style(_.height(3.px).overflowY(_.auto))(
                      button("a").id("a"),
                      span("x1"),
                      span("x2"),
                      span("x3"),
                      button("b").id("b"),
                    ),
                )
          f1 <- h.key(Keyboard.Tab)
          s1 <- h.screen
          f2 <- h.key(Keyboard.Tab, Modifiers(shift = true))
          s2 <- h.screen
      yield
          assert(focusOf(f1) == "b" && focusOf(f2) == "a", s"$f1 $f2")
          assert(
            s1.row(0).contains("x2") && s1.row(1).contains("x3") &&
              s1.row(2).contains("b"),
            s1.text,
          )
          assert(s2.row(0).contains("a") && s2.row(1).contains("x1"), s2.text)
    }

    "preventScrollKeys: the navigation keys reach the handler and do not scroll; a focus group in a row viewport: the arrow moves the focus and the viewport follows it" in {
      for
          keys <- AtomicInt.init(0)
          h    <- Headless.start(12, 4)(_ =>
                    div
                      .id("v")
                      .preventScrollKeys
                      .onKeyDown(_ => keys.incrementAndGet.unit)
                      .style(_.height(3.px).overflowY(_.scroll))(lines(6)*),
                  )
          _    <- h.key(Keyboard.PageDown)
          _    <- h.key(Keyboard.ArrowDown)
          s    <- h.screen
          n    <- keys.get
          h2   <- Headless.start(20, 2)(_ =>
                    div
                      .tabIndex(-1)
                      .style(_.row.width(8.px).overflowX(_.auto))(
                        button("p").id("p").focusGroup("g"),
                        button("q").id("q").focusGroup("g"),
                        button("r").id("r").focusGroup("g"),
                      ),
                  )
          f    <- h2.key(Keyboard.ArrowRight)
          s2   <- h2.screen
      yield
          assert(n == 2 && shown(s) == Vector(1, 2, 3), s"$n ${s.text}")
          assert(focusOf(f) == "q", s"$f")
          assert(s2.row(0).startsWith("p ][ q ]"), s2.text)
    }

    "aria-activedescendant: a list that keeps the focus and moves a selection scrolls its viewport to the selected element" in {
      for
          cursor <- Signal.initRef(0)
          down    = (e: KeyboardEvent) =>
                      (e.key match
                          case Keyboard.ArrowDown =>
                            cursor.updateAndGet(_ + 1).unit
                          case _                  => Kyo.unit
                      ): Any < Async
          h      <- Headless.start(12, 4)(_ =>
                      div
                        .tabIndex(-1)
                        .style(_.height(3.px).overflowY(_.auto))(
                          cursor.map(c =>
                            ul.id("list")
                              .tabIndex(0)
                              .preventScrollKeys
                              .aria("activedescendant", s"r$c")
                              .onKeyDown(down)(
                                (0 until 8).map[UI.Ast.HtmlChildVal](i =>
                                  li(s"row $i").id(s"r$i"),
                                )*,
                              ): UI,
                          ),
                        ),
                    )
          fs     <- Kyo.foreach(1 to 4)(_ => h.key(Keyboard.ArrowDown))
          s      <- h.screen
      yield
          assert(fs.lastOption.map(focusOf).contains("list"), s"$fs")
          assert(shown(s) == Vector(2, 3, 4), s.text)
    }

    "a viewport keeps its offset while it stays mounted (a render of the same shape) and starts at 0 after an unmount" in {
      for
          state <- Signal.initRef(0)
          h     <- Headless.start(12, 4)(_ =>
                     div(
                       state.map(n =>
                         if n < 2 then
                             div
                               .id("v")
                               .style(_.height(3.px).overflowY(_.scroll))(
                                 lines(8)*,
                               ): UI
                         else span("gone"): UI,
                       ),
                     ),
                   )
          _     <- h.key(Keyboard.PageDown)
          s1    <- h.screen
          _     <- state.set(1)
          kept  <- h.awaitScreen(s => shown(s) == Vector(4, 5, 6), 5.seconds)
          _     <- state.set(2)
          gone  <- h.awaitScreen(_.contains("gone"), 5.seconds)
          _     <- state.set(0)
          fresh <- h.awaitScreen(s => shown(s) == Vector(1, 2, 3), 5.seconds)
      yield assert(
        shown(s1) == Vector(4, 5, 6) && kept && gone && fresh,
        s"${s1.text} kept $kept gone $gone fresh $fresh",
      )
    }

    // claude 2026-10-19: created (L1a): wide characters in a text input
    "a text input on CJK text: the caret is a cell position (a wide character takes two cells), and a long value scrolls by cells" in {
      for
          h  <- Headless.start(20, 1)(_ => input.id("i"))
          _  <- h.typeText("中文字")
          s1 <- h.screen
          _  <- h.key(Keyboard.ArrowLeft)
          s2 <- h.screen
          _  <- h.typeText("漢字" * 10)
          s3 <- h.screen
      yield
          def caretAt(s: Screen): Vector[Int] =
            (0 until 20).filter(x => s.styleAt(x, 0).reversed).toVector
          // after the three characters: the caret is the cell after them
          assert(
            s1.row(0) == "中文字" && caretAt(s1) == Vector(6),
            s"${s1.row(0)} ${caretAt(s1)}",
          )
          // one left: the caret is on 字, at cell 4
          assert(caretAt(s2) == Vector(4), s"${caretAt(s2)}")
          // 20 more wide characters before 字: the field shows the 9 before the
          // caret (18 cells) and 字 under the caret at cell 18
          assert(
            s3.row(0) == "字漢" * 4 + "字字" && caretAt(s3) == Vector(18),
            s"${s3.row(0)} ${caretAt(s3)}",
          )
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
