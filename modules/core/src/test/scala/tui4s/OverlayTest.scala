package tui4s

import kyo.*
import kyo.UI.*
import tui4s.testkit.Headless

/** A modal on the headless kit: an overlay with kyo-ui's `focusTrap`, its first
  * button with `focusAuto` and `focusRestore`.
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
