package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.{Knobs, Render}
import tui4s.testkit.{Headless, Screen}

/** A modal on the headless kit: an overlay with kyo-ui's `focusTrap`, its first
  * button with `focusAuto` and `focusRestore`. The repair tick and the damage
  * rule under an opaque and a transparent layer.
  */
// claude 2026-10-19: created (L1b)
class OverlayTest extends RunnerSuite:

    private def app(open: SignalRef[Boolean])(using Frame): UI =
      div(
        button("Open").id("open").onClick(open.set(true)),
        button("Other").id("other"),
        open.map(o =>
          if !o then div
          else
              div.style(
                _.position(_.overlay).justify(_.center).align(_.center),
              )(
                div
                  .id("modal")
                  .focusTrap(true)
                  .onKeyDown {
                    case KeyboardEvent(Keyboard.Escape, _, _) => open.set(false)
                    case _                                    => ()
                  }
                  .style(_.border(1.px, _.white).bg(_.black).row.gap(1.px))(
                    button("OK").id("ok").focusAuto(true).focusRestore(true),
                    button("Cancel").id("cancel"),
                  ),
              ),
        ),
      )

    "a modal: its focusAuto button takes the focus when it appears, Tab stays in its focusTrap, Esc in the app's onKeyDown closes it, and focusRestore gives the focus back to its trigger" in {
      for
          open   <- Signal.initRef(false)
          h      <- Headless.start(30, 7)(_ => app(open))
          shown  <- h.key(Keyboard.Enter) // the click of Open
          screen <- h.screen
          tab1   <- h.key(Keyboard.Tab)
          tab2   <- h.key(Keyboard.Tab)
          esc    <- h.key(Keyboard.Escape)
          closed <- h.screen
      yield
          assert(
            screen.contains("OK") && screen.contains("Cancel"),
            screen.text,
          )
          assert(shown.focus.contains("ok"), shown.toString)
          assert(tab1.focus.contains("cancel"), tab1.toString)
          assert(tab2.focus.contains("ok"), tab2.toString)
          assert(esc.focus.contains("open"), esc.toString)
          assert(!closed.contains("OK"), closed.text)
      end for
    }

    /** Two regions with closures in the flow, one under an opaque fixed layer
      * and one under a transparent fixed layer; the opaque layer is a region
      * with closures too.
      */
    // claude 2026-10-19: created (L1b review): the repair tick under layers
    private def layered(n: SignalRef[Int])(using Frame): UI =
      div(
        n.map(v => button(s"under-opaque $v").onClick(n.set(v + 1))),
        n.map(v => button(s"under-clear $v").onClick(n.set(v + 1))),
        n.map(v =>
          div.style(
            _.position(_.fixed).top(0.px).left(6.px).width(5.px).bg(_.black),
          )(button("X").onClick(n.set(v + 1))),
        ),
        div.style(_.position(_.fixed).top(1.px).left(6.px))("T"),
      )

    // claude 2026-10-19: created (L1b review): the repair-tick rule of
    // ARCHITECTURE.md under layers
    // claude 2026-10-20: changed: ARCHITECTURE.md (ADR 0021)
    "a repair tick of closure regions under an opaque and a transparent layer paints again and sends no cell (repaired > 0, flushed == 0)" in {
      Knobs.let(Knobs(repairInterval = 50.millis)) {
        for
            n  <- Signal.initRef(0)
            h  <- Headless.start(24, 3)(_ => layered(n))
            _  <- h.drainFrames
            s0 <- h.screen
            fs <- framesUntil(h, 2.seconds)(_.repaired > 0)
            s1 <- h.screen
        yield
            val repairs = fs.filter(_.repaired > 0)
            assert(repairs.nonEmpty, s"no repair frame in 2 s: $fs")
            assert(repairs.forall(_.flushed == 0), repairs.toString)
            assert(s1 == s0, s"${s0.text}\n---\n${s1.text}")
        end for
      }
    }

    // claude 2026-10-19: created (L1b review): the damage rule under layers
    "a text that changes under an opaque and under a transparent layer: the frame of the step has the cells of a full frame" in {
      def scene(a: UI, b: UI)(using Frame): UI =
        div(
          a,
          b,
          div.style(
            _.position(_.fixed).top(0.px).left(2.px).width(3.px).bg(_.blue),
          )("MOD"),
          div.style(_.position(_.fixed).top(1.px).left(2.px))("T"),
        )
      for
          t  <- Signal.initRef("aaaaaaaa")
          h  <- Headless.start(10, 2)(_ =>
                  scene(t.map(v => span(v)), t.map(v => span(v.toUpperCase))),
                )
          s0 <- h.screen
          _  <- t.set("bbbbbbbbb")
          ok <- h.awaitScreen(showing("BBTBBBBBB"), 2.seconds)
          s1 <- h.screen
      yield
          val full = Screen.of(
            Render.buffer(scene(span("bbbbbbbbb"), span("BBBBBBBBB")), 10, 2),
            None,
          )
          assert(s0.rows == Vector("aaMODaaa", "AATAAAAA"), s0.text)
          assert(ok, s1.text)
          assert(s1.rows == Vector("bbMODbbbb", "BBTBBBBBB"), s1.text)
          assert(
            s1.rows == full.rows && s1.styles == full.styles,
            s"${s1.text}\n---\n${full.text}",
          )
      end for
    }

    "Esc does nothing by itself inside a focus trap" in {
      for
          h <- Headless.start(20, 3)(_ =>
                 div.focusTrap(true)(button("a").id("a"), button("b").id("b")),
               )
          f <- h.key(Keyboard.Escape)
          s <- h.screen
      yield assert(f.focus.contains("a") && s.contains("a"), s.text)
    }
end OverlayTest
