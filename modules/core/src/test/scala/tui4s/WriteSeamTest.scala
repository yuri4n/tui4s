package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.{Knobs, Loc}
import tui4s.testkit.Headless

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The write seam (plan 0.3): every write of a bound `SignalRef` by the
  * dispatcher (a text input, a checkbox, a radio, a select, a textarea) goes
  * through `Dispatch.writeBound`. The test hook `Knobs.onBoundWrite` sees each
  * one; with the default hook (it does nothing) the refs still change.
  */
// claude 2026-10-13: created (C3); changed (review R1-5, R2-6): the textarea
class WriteSeamTest extends RunnerSuite:

    final case class Refs(
        text: SignalRef[String],
        box: SignalRef[Boolean],
        radio: SignalRef[Boolean],
        pick: SignalRef[String],
        // claude 2026-10-13: created (review R1-5, R2-6)
        area: SignalRef[String],
    )

    private def refs(using Frame): Refs < Sync =
      for
          t <- Signal.initRef("")
          b <- Signal.initRef(false)
          r <- Signal.initRef(false)
          p <- Signal.initRef("a")
          a <- Signal.initRef("")
      yield Refs(t, b, r, p, a)

    /** A text input, a checkbox, a radio, a select and a textarea, each bound
      * to a ref, in this focus order. `onInput` records the text it gets and
      * the value of the ref at that time.
      */
    private def app(
        r: Refs,
        onInputSeen: ConcurrentLinkedQueue[(String, String)],
    )(using
        Frame,
    ): UI =
      div(
        input
          .value(r.text)
          .onInput(s =>
            r.text.get
              .map(cur => Sync.defer { val _ = onInputSeen.add((s, cur)) }),
          )
          .style(_.width(10.px)),
        checkbox.checked(r.box),
        radio.checked(r.radio),
        select.value(r.pick)(
          option.value("a")("a"),
          option.value("b")("b"),
          option.value("c")("c"),
        ),
        // claude 2026-10-19: changed: the textarea is a TextInput of kyo-ui
        // RC7, edited in lines (L1c; plan 0.3 names it)
        textarea.value(r.area).style(_.width(10.px)),
      )

    /** Types "hi" and pastes "!" in the input; Space on the checkbox and on the
      * radio; Enter and ArrowDown on the select; types "t" in the textarea.
      * Returns the five values of the refs.
      */
    // claude 2026-10-13: changed (C3, RC-14 part): the paste writes through
    // the seam too; changed (review R1-5, R2-6): the textarea
    private def drive(h: Headless, r: Refs)(using
        Frame,
    ): (String, Boolean, Boolean, String, String) < Async =
      for
          _ <- h.typeText("hi")
          _ <- h.paste("!")
          _ <- h.key(Keyboard.Tab)
          _ <- h.key(Keyboard.Space)
          _ <- h.key(Keyboard.Tab)
          _ <- h.key(Keyboard.Space)
          _ <- h.key(Keyboard.Tab)
          _ <- h.key(Keyboard.Enter)
          _ <- h.key(Keyboard.ArrowDown)
          _ <- h.key(Keyboard.Tab)
          _ <- h.char('t')
          t <- r.text.get
          b <- r.box.get
          o <- r.radio.get
          p <- r.pick.get
          a <- r.area.get
      yield (t, b, o, p, a)

    "every bound write (text input: keys and a paste; checkbox, radio, select, textarea) goes through the seam; the ref is written BEFORE onInput" in {
      val writes      = new ConcurrentLinkedQueue[(Loc, Any)]()
      val onInputSeen = new ConcurrentLinkedQueue[(String, String)]()
      val hook        = Knobs(onBoundWrite =
        (p, v) => Sync.defer { val _ = writes.add((p, v)) },
      )
      for
          r      <- refs
          values <- Knobs.let(hook) {
                      Headless
                        .start(40, 10)(_ => app(r, onInputSeen))
                        .map(h => drive(h, r))
                    }
      yield
          val seen = writes.asScala.toVector
          val line =
            s"[seam] writes=${seen.size} values=${seen.map(_._2)} paths=${seen
                .map(_._1.showLoc)
                .distinct} refs=$values onInput=${onInputSeen.asScala.toVector}"
          java.lang.System.out.println(line)
          // claude 2026-10-13: changed (review R1-5, R2-6): the textarea
          assert(seen.size == 8, line)
          assert(
            seen
              .map(_._2) == Vector("h", "hi", "hi!", true, true, "b", "c", "t"),
            line,
          )
          assert(seen.map(_._1).distinct.size == 5, line)
          assert(values == ("hi!", true, true, "c", "t"), line)
          // A3: onInput reads the ref and sees the new text
          assert(
            onInputSeen.asScala.toVector == Vector(
              ("h", "h"),
              ("hi", "hi"),
              ("hi!", "hi!"),
            ),
            line,
          )
      end for
    }

    // claude 2026-10-13: changed (review R1-5): no count (no hook records
    // here, so a count could not fail); the refs change
    "with the default hook (it does nothing) the seam still writes the refs" in {
      val onInputSeen = new ConcurrentLinkedQueue[(String, String)]()
      for
          r      <- refs
          h      <- Headless.start(40, 10)(_ => app(r, onInputSeen))
          values <- drive(h, r)
      yield
          val line =
            s"[seam default] refs=$values onInput=${onInputSeen.asScala.toVector}"
          java.lang.System.out.println(line)
          assert(values == ("hi!", true, true, "c", "t"), line)
      end for
    }
end WriteSeamTest
