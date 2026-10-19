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

    // claude 2026-10-19: created (L1a review): the viewport stops
    "a viewport is a tab stop only when it can scroll and holds no focusable element: one whose content fits and one around a button are not" in {
      for
          h  <- Headless.start(12, 9)(_ =>
                  div(
                    button("x").id("x"),
                    div
                      .id("fits")
                      .style(_.height(3.px).overflowY(_.scroll))(span("one")),
                    div
                      .id("around")
                      .style(_.height(2.px).overflowY(_.auto))(
                        button("y").id("y"),
                        span("two"),
                        span("three"),
                      ),
                    div
                      .id("log")
                      .style(_.height(3.px).overflowY(_.scroll))(lines(5)*),
                  ),
                )
          fs <- Kyo.foreach(1 to 3)(_ => h.key(Keyboard.Tab))
      yield assert(fs.map(focusOf) == Seq("y", "log", "x"), s"$fs")
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

    // claude 2026-10-19: created (L1a review): nested viewports
    "nested viewports: at the end of the inner one PageDown scrolls the outer one; Tab to a button deep in both scrolls both, the inner one first" in {
      for
          h  <- Headless.start(12, 6)(_ =>
                  div
                    .id("outer")
                    .style(_.height(4.px).overflowY(_.scroll))(
                      div
                        .id("inner")
                        .style(_.height(2.px).overflowY(_.scroll))(lines(4)*),
                      span("row 1"),
                      span("row 2"),
                      span("row 3"),
                      span("row 4"),
                    ),
                )
          f  <- h.key(Keyboard.PageDown)
          s1 <- h.screen
          _  <- h.key(Keyboard.PageDown)
          s2 <- h.screen
          h2 <- Headless.start(12, 4)(_ =>
                  div
                    .style(_.height(3.px).overflowY(_.scroll))(
                      button("top").id("top"),
                      span("row 1"),
                      span("row 2"),
                      span("row 3"),
                      div
                        .style(_.height(2.px).overflowY(_.scroll))(
                          button("a").id("a"),
                          span("line 1"),
                          span("line 2"),
                          button("b").id("b"),
                        ),
                    ),
                )
          fa <- h2.key(Keyboard.Tab)
          sa <- h2.screen
          fb <- h2.key(Keyboard.Tab)
          sb <- h2.screen
      yield
          assert(focusOf(f) == "inner", s"$f")
          assert(shown(s1) == Vector(3, 4, 1, 2), s1.text)
          assert(shown(s2) == Vector(1, 2, 3, 4), s2.text)
          assert(focusOf(fa) == "a" && focusOf(fb) == "b", s"$fa $fb")
          assert(
            sa.row(0).contains("row 2") && sa.row(1).contains("row 3") &&
              sa.row(2).contains("[ a"),
            sa.text,
          )
          assert(
            sb.row(0).contains("row 3") && sb.row(1).contains("line 2") &&
              sb.row(2).contains("[ b"),
            sb.text,
          )
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
          // claude 2026-10-19: changed: the terminal cursor (L1c)
          def caretAt(s: Screen): Vector[Int] =
            s.cursor.collect { case (x, 0) => x }.toVector
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

    /** The cell of the caret of `s`: the terminal cursor (column, row). */
    // claude 2026-10-19: created (L1c)
    private def caretOf(s: Screen): Option[(Int, Int)] = s.cursor

    // claude 2026-10-19: created (L1c)
    "a textarea outside a form: Alt-Enter, Ctrl-J, Shift-Enter and a plain Enter insert a line break; its view of 3 rows follows the caret" in {
      for
          ref <- Signal.initRef("")
          h   <- Headless.start(12, 3)(_ =>
                   textarea.value(ref).style(_.width(10.px)),
                 )
          _   <- h.typeText("a")
          _   <- h.key(Keyboard.Enter, Modifiers(alt = true))
          _   <- h.typeText("b")
          _   <- h.key(Keyboard.Char('j'), Modifiers(ctrl = true))
          _   <- h.typeText("c")
          _   <- h.key(Keyboard.Enter, Modifiers(shift = true))
          _   <- h.typeText("d")
          _   <- h.key(Keyboard.Enter)
          _   <- h.typeText("e")
          v   <- ref.get
          s   <- h.screen
      yield
          assert(v == "a\nb\nc\nd\ne", v)
          // five lines in a port of 3 rows: the view shows the last three
          // (the scroll indicator takes the last column), and the caret is
          // after "e"
          assert(
            s.rows.map(_.take(1)) == Vector("c", "d", "e"),
            s.rows.mkString("\n"),
          )
          assert(caretOf(s) == Some((1, 2)), s"${caretOf(s)}")
    }

    // claude 2026-10-19: created (L1c)
    "a textarea in a form: Enter submits the form (onChange with the text first) and inserts nothing; Alt-Enter inserts a line break and does not submit" in {
      for
          ref     <- Signal.initRef("hi")
          sent    <- Signal.initRef(Vector.empty[String])
          changed <- Signal.initRef(Vector.empty[String])
          h       <- Headless.start(20, 4)(_ =>
                       form.onSubmit(
                         ref.get.map(t => sent.updateAndGet(_ :+ t).unit),
                       )(
                         textarea
                           .value(ref)
                           .onChange(t => changed.updateAndGet(_ :+ t).unit),
                       ),
                     )
          _       <- h.key(Keyboard.Enter, Modifiers(alt = true))
          _       <- h.typeText("x")
          s1      <- sent.get
          _       <- h.key(Keyboard.Enter)
          v       <- ref.get
          s2      <- sent.get
          c       <- changed.get
      yield assert(
        s1.isEmpty && v == "hi\nx" && s2 == Vector("hi\nx") &&
          c == Vector("hi\nx"),
        s"$s1 $v $s2 $c",
      )
    }

    // claude 2026-10-19: created (L1c)
    "a textarea: Up, Down, PageUp and PageDown move the caret by visual lines and keep its column; the view scrolls the least distance that shows the caret" in {
      for
          ref <- Signal.initRef("l0\nl1\nl2\nl3\nl4 long\nl5")
          h   <- Headless.start(12, 3)(_ =>
                   textarea.value(ref).style(_.width(10.px)),
                 )
          // the caret starts at the end of the text: the view shows l3 to l5
          s0  <- h.screen
          _   <- h.key(Keyboard.PageUp)
          s1  <- h.screen
          _   <- h.key(Keyboard.ArrowUp)
          _   <- h.key(Keyboard.ArrowUp)
          s2  <- h.screen
          _   <- h.key(Keyboard.ArrowDown)
          s3  <- h.screen
          _   <- h.key(Keyboard.PageDown)
          _   <- h.key(Keyboard.End)
          s4  <- h.screen
          v   <- ref.get
      yield
          def firsts(s: Screen) = s.rows.map(_.take(2))
          assert(firsts(s0) == Vector("l3", "l4", "l5"), s0.rows.mkString("\n"))
          assert(caretOf(s0) == Some((2, 2)), s"${caretOf(s0)}")
          // PageUp: 3 rows up, to l2 at the same column; the view starts at l2
          assert(firsts(s1) == Vector("l2", "l3", "l4"), s1.rows.mkString("\n"))
          assert(caretOf(s1) == Some((2, 0)), s"${caretOf(s1)}")
          // two rows up: l0, the view starts at l0
          assert(firsts(s2) == Vector("l0", "l1", "l2"), s2.rows.mkString("\n"))
          assert(caretOf(s2) == Some((2, 0)), s"${caretOf(s2)}")
          // one down: l1 shows already, so the view does not move
          assert(firsts(s3) == Vector("l0", "l1", "l2"), s3.rows.mkString("\n"))
          assert(caretOf(s3) == Some((2, 1)), s"${caretOf(s3)}")
          // PageDown to l4 at column 2, End to the end of "l4 long"
          assert(firsts(s4) == Vector("l2", "l3", "l4"), s4.rows.mkString("\n"))
          assert(caretOf(s4) == Some((7, 2)), s"${caretOf(s4)}")
          assert(v == "l0\nl1\nl2\nl3\nl4 long\nl5")
    }

    // claude 2026-10-19: created (L1c)
    "a number input: the keys take digits, one sign and one point; ArrowUp and ArrowDown step by step, clamped to min and max; Enter commits the number" in {
      for
          ref  <- Signal.initRef("")
          nums <- Signal.initRef(Vector.empty[Double])
          h    <- Headless.start(20, 1)(_ =>
                    numberInput
                      .value(ref)
                      .min(0)
                      .max(2)
                      .step(0.5)
                      .onChangeNumeric(d => nums.updateAndGet(_ :+ d).unit),
                  )
          _    <- h.typeText("-1x.5.2")
          t1   <- ref.get
          _    <- h.key(Keyboard.ArrowUp)
          t2   <- ref.get
          // claude 2026-10-19: changed: a step at max gives no callback (L1c
          // review)
          _    <- Kyo.foreach(1 to 4)(_ => h.key(Keyboard.ArrowUp))
          t3   <- ref.get
          n3   <- nums.get.map(_.size)
          _    <- h.key(Keyboard.ArrowUp)
          n4   <- nums.get.map(_.size)
          _    <- h.key(Keyboard.ArrowDown)
          t4   <- ref.get
          _    <- h.key(Keyboard.Backspace)
          t5   <- ref.get
          _    <- h.key(Keyboard.Enter)
          t6   <- ref.get
          ns   <- nums.get
          s    <- h.screen
      yield
          // "x" and the second point are not taken
          assert(t1 == "-1.52", t1)
          // a step from -1.52 is clamped to min 0; four more reach max 2, and
          // a step at max changes nothing and runs no callback
          assert(t2 == "0" && t3 == "2" && t4 == "1.5", s"$t2 $t3 $t4")
          assert(n4 == n3, s"$n3 $n4")
          // "1." is a number that the user still types; Enter commits it
          assert(t5 == "1." && t6 == "1", s"$t5 $t6")
          assert(ns.lastOption.contains(1.0) && ns.size == 7, s"$ns")
          assert(s.row(0).startsWith("1"), s.row(0))
    }

    // claude 2026-10-19: created (L1c)
    "a range: the arrows move it by step, PageUp and PageDown by 10 steps, Home and End to min and max; a dropdown cycles with the keys of a select" in {
      for
          ref  <- Signal.initRef(6.0)
          pick <- Signal.initRef("a")
          sent <- Signal.initRef(0)
          h    <- Headless.start(12, 2)(_ =>
                    form.onSubmit(sent.updateAndGet(_ + 1).unit)(
                      rangeInput.value(ref).min(0).max(20).step(2),
                      dropdown("a" -> "Apple", "b" -> "Bean").value(pick),
                    ),
                  )
          vs   <- Kyo.foreach(
                    Seq(
                      Keyboard.ArrowRight,
                      Keyboard.ArrowDown,
                      Keyboard.PageUp,
                      Keyboard.Home,
                      Keyboard.PageDown,
                      Keyboard.End,
                      Keyboard.ArrowLeft,
                    ),
                  )(k => h.key(k).andThen(ref.get))
          _    <- h.key(Keyboard.Tab)
          _    <- h.key(Keyboard.ArrowDown)
          p1   <- pick.get
          _    <- h.key(Keyboard.Enter)
          p2   <- pick.get
          n    <- sent.get
          s    <- h.screen
      yield
          assert(
            vs == Seq(8.0, 6.0, 20.0, 0.0, 0.0, 20.0, 18.0),
            s"$vs",
          )
          assert(p1 == "b" && p2 == "a" && n == 0, s"$p1 $p2 $n")
          assert(s.row(0).trim.startsWith("─") && s.row(0).contains("●"))
    }

    // claude 2026-10-19: created (L1c)
    "disabled and readOnly: Tab skips a disabled input; a read-only input, textarea and number take the focus and move the caret, and take no edit, no paste, no line break, no step and no onChange" in {
      for
          off     <- Signal.initRef("ab")
          ro      <- Signal.initRef("cd")
          area    <- Signal.initRef("x\ny")
          num     <- Signal.initRef("5")
          changes <- Signal.initRef(0)
          h       <- Headless.start(30, 8)(_ =>
                       div(
                         input.value(off).disabled(true).id("off"),
                         input
                           .value(ro)
                           .readOnly(true)
                           .id("ro")
                           .onChange(_ => changes.updateAndGet(_ + 1).unit),
                         textarea.value(area).readOnly(true).id("ta"),
                         numberInput.value(num).readOnly(true).id("num"),
                       ),
                     )
          // the first focus skips the disabled input
          f0      <- h.key(Keyboard.Home)
          c0      <- h.screen.map(_.cursor)
          _       <- h.typeText("zz")
          _       <- h.key(Keyboard.Delete)
          _       <- h.paste("p")
          _       <- h.key(Keyboard.Enter)
          f1      <- h.key(Keyboard.Tab)
          _       <- h.key(Keyboard.Enter, Modifiers(alt = true))
          _       <- h.key(Keyboard.Enter)
          _       <- h.paste("q\nr")
          _       <- h.key(Keyboard.ArrowUp)
          c1      <- h.screen.map(_.cursor)
          f2      <- h.key(Keyboard.Tab)
          _       <- h.key(Keyboard.ArrowUp)
          f3      <- h.key(Keyboard.Tab)
          vs      <- Kyo.foreach(Seq(off, ro, area, num))(_.get)
          n       <- changes.get
      yield
          assert(
            Seq(f0, f1, f2, f3).map(focusOf) == Seq("ro", "ta", "num", "ro"),
            s"${Seq(f0, f1, f2, f3).map(focusOf)}",
          )
          assert(vs == Seq("ab", "cd", "x\ny", "5") && n == 0, s"$vs $n")
          // Home moved the caret of the read-only input to its start; ArrowUp
          // moved the caret of the read-only textarea to its first line
          assert(c0 == Some((0, 1)) && c1 == Some((1, 2)), s"$c0 $c1")
    }

    // claude 2026-10-19: created (L1c)
    "the terminal cursor: a focused text input or textarea places it at its caret, with no reversed cell; a focused button hides it" in {
      for
          h  <- Headless.start(20, 5)(_ =>
                  div(
                    input.value("ab中").id("i"),
                    textarea.value("one\ntwo").style(_.width(10.px)).id("t"),
                    button("ok").id("b"),
                  ),
                )
          s0 <- h.screen
          _  <- h.key(Keyboard.ArrowLeft)
          s1 <- h.screen
          _  <- h.key(Keyboard.Tab)
          s2 <- h.screen
          _  <- h.key(Keyboard.ArrowUp)
          s3 <- h.screen
          _  <- h.key(Keyboard.Tab)
          s4 <- h.screen
      yield
          def reversed(s: Screen): Boolean =
            s.rows.indices.exists(y =>
              (0 until s.rows(y).length).exists(x =>
                s.styleAt(x, y).reversed && s.rows(y)(x) != '[' &&
                  s.rows(y)(x) != ']',
              ),
            )
          // after "ab中": column 4 (中 takes two cells); one left: column 2
          assert(
            s0.cursor == Some((4, 0)) && s1.cursor == Some((2, 0)),
            s"${s0.cursor} ${s1.cursor}",
          )
          // the textarea: after "two" on its second row, then up to "one"
          assert(
            s2.cursor == Some((3, 2)) && s3.cursor == Some((3, 1)),
            s"${s2.cursor} ${s3.cursor}",
          )
          assert(s4.cursor.isEmpty, s"${s4.cursor}")
          assert(!reversed(s0) && !reversed(s2), s0.rows.mkString("\n"))
    }

    // claude 2026-10-19: created (L1c review)
    "a textarea: onChange runs with the text when the focus leaves it after an edit (the DOM change event); a blur with the same text runs none" in {
      for
          ref     <- Signal.initRef("")
          changed <- Signal.initRef(Vector.empty[String])
          h       <- Headless.start(20, 4)(_ =>
                       div(
                         textarea
                           .value(ref)
                           .onChange(t => changed.updateAndGet(_ :+ t).unit),
                         button("ok"),
                       ),
                     )
          _       <- h.typeText("ab")
          c0      <- changed.get
          _       <- h.key(Keyboard.Tab)
          c1      <- changed.get
          // back to the textarea (Tab wraps), then away with no edit
          _       <- h.key(Keyboard.Tab)
          _       <- h.key(Keyboard.Tab)
          c2      <- changed.get
          // an edit that gives the same text back
          _       <- h.key(Keyboard.Tab)
          _       <- h.typeText("x")
          _       <- h.key(Keyboard.Backspace)
          _       <- h.key(Keyboard.Tab)
          c3      <- changed.get
      yield assert(
        c0.isEmpty && c1 == Vector("ab") && c2 == c1 && c3 == c1,
        s"$c0 $c1 $c2 $c3",
      )
    }

    // claude 2026-10-19: created (L1c review)
    "a paste into a textarea: one edit (one onInput); CR LF and CR become line breaks and a tab a space; the caret ends after the paste and the view follows it" in {
      for
          ref    <- Signal.initRef("")
          inputs <- Signal.initRef(0)
          h      <- Headless.start(12, 3)(_ =>
                      textarea
                        .value(ref)
                        .onInput(_ => inputs.updateAndGet(_ + 1).unit)
                        .style(_.width(10.px)),
                    )
          _      <- h.paste("x\r\ny\tz\r1\n2")
          v      <- ref.get
          n      <- inputs.get
          s      <- h.screen
      yield
          assert(v == "x\ny z\n1\n2" && n == 1, s"$v $n")
          // four lines in a port of 3 rows: the view shows the last three
          assert(
            s.rows.map(_.take(3).trim) == Vector("y z", "1", "2"),
            s.rows.mkString("\n"),
          )
          assert(caretOf(s) == Some((1, 2)), s"${caretOf(s)}")
    }

    // claude 2026-10-19: created (L1c review)
    "a textarea in a viewport: PageUp and PageDown move its caret and its own view; the viewport around it does not scroll" in {
      for
          ref <- Signal.initRef("l0\nl1\nl2\nl3\nl4\nl5\nl6")
          h   <- Headless.start(14, 4)(_ =>
                   div.style(_.height(4.px).overflow(_.auto))(
                     textarea.value(ref).style(_.width(10.px).height(3.px)),
                     p("below 1"),
                     p("below 2"),
                     p("below 3"),
                   ),
                 )
          s0  <- h.screen
          _   <- h.key(Keyboard.PageUp)
          s1  <- h.screen
          _   <- h.key(Keyboard.PageDown)
          s2  <- h.screen
      yield
          def firsts(s: Screen) = s.rows.map(_.take(2))
          // the caret starts at the end: the view of the textarea shows l4 to
          // l6, and the viewport shows its first row under it
          assert(
            firsts(s0) == Vector("l4", "l5", "l6", "be"),
            s0.rows.mkString("\n"),
          )
          assert(caretOf(s0) == Some((2, 2)), s"${caretOf(s0)}")
          // PageUp: 3 rows up, to l3; the viewport stays at its top
          assert(
            firsts(s1) == Vector("l3", "l4", "l5", "be"),
            s1.rows.mkString("\n"),
          )
          assert(caretOf(s1) == Some((2, 0)), s"${caretOf(s1)}")
          // PageDown: back to l6
          assert(
            firsts(s2) == Vector("l4", "l5", "l6", "be"),
            s2.rows.mkString("\n"),
          )
          assert(caretOf(s2) == Some((2, 2)), s"${caretOf(s2)}")
    }

    // claude 2026-10-19: created (L1c)
    "maxLength: the keys stop at the limit; a paste is cut by grapheme clusters" in {
      for
          ref <- Signal.initRef("")
          h   <- Headless.start(20, 1)(_ => input.value(ref).maxLength(3))
          _   <- h.typeText("abcd")
          v1  <- ref.get
          _   <- h.key(Keyboard.Backspace).andThen(h.key(Keyboard.Backspace))
          _   <- h.paste("中👍🏽é!")
          v2  <- ref.get
      yield assert(v1 == "abc" && v2 == "a中👍🏽", s"$v1 $v2")
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
