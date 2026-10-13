package tui4s

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.UI.*
import scala.jdk.CollectionConverters.*
import tui4s.internal.Knobs
import tui4s.internal.Loc
import tui4s.internal.showLoc
import tui4s.testkit.Headless

/** The write seam (plan 0.3): every write of a bound `SignalRef` by the
  * dispatcher goes through `Dispatch.writeBound`. The test hook
  * `Knobs.onBoundWrite` sees each one; the default hook does nothing and the
  * refs still change.
  */
// claude 2026-10-13: created (C3)
class WriteSeamTest extends RunnerSuite:

    final case class Refs(
        text: SignalRef[String],
        box: SignalRef[Boolean],
        radio: SignalRef[Boolean],
        pick: SignalRef[String],
    )

    private def refs(using Frame): Refs < Sync =
      for
          t <- Signal.initRef("")
          b <- Signal.initRef(false)
          r <- Signal.initRef(false)
          p <- Signal.initRef("a")
      yield Refs(t, b, r, p)

    /** A text input, a checkbox, a radio and a select, each bound to a ref, in
      * this focus order. `onInput` records the text it gets and the value of
      * the ref at that time.
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
      )

    /** Types "hi" and pastes "!" in the input; Space on the checkbox and on the
      * radio; Enter and ArrowDown on the select. Returns the four values of the
      * refs.
      */
    // claude 2026-10-13: changed (C3, RC-14 part): the paste writes through
    // the seam too
    private def drive(h: Headless, r: Refs)(using
        Frame,
    ): (String, Boolean, Boolean, String) < Async =
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
          t <- r.text.get
          b <- r.box.get
          o <- r.radio.get
          p <- r.pick.get
      yield (t, b, o, p)

    "every bound write (text input: keys and a paste; checkbox, radio, select) goes through the seam; the ref is written BEFORE onInput" in {
      val writes      = new ConcurrentLinkedQueue[(Loc, Any)]()
      val onInputSeen = new ConcurrentLinkedQueue[(String, String)]()
      val hook        = Knobs(onBoundWrite =
        (p, v) => Sync.defer { val _ = writes.add((p, v)) },
      )
      for
          r      <- refs
          values <- Knobs.let(hook) {
                      Headless
                        .start(40, 6)(_ => app(r, onInputSeen))
                        .map(h => drive(h, r))
                    }
      yield
          val seen = writes.asScala.toVector
          val line =
            s"[seam] writes=${seen.size} values=${seen.map(_._2)} paths=${seen
                .map(_._1.showLoc)
                .distinct} refs=$values onInput=${onInputSeen.asScala.toVector}"
          java.lang.System.out.println(line)
          assert(seen.size == 7, line)
          assert(
            seen.map(_._2) == Vector("h", "hi", "hi!", true, true, "b", "c"),
            line,
          )
          assert(seen.map(_._1).distinct.size == 4, line)
          assert(values == ("hi!", true, true, "c"), line)
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

    "with the default hook (it does nothing) the seam still writes the refs; the hook count is 0" in {
      val writes      = new ConcurrentLinkedQueue[(Loc, Any)]()
      val onInputSeen = new ConcurrentLinkedQueue[(String, String)]()
      for
          r      <- refs
          h      <- Headless.start(40, 6)(_ => app(r, onInputSeen))
          values <- drive(h, r)
      yield
          val line =
            s"[seam default] hook writes=${writes.size} refs=$values onInput=${onInputSeen.asScala.toVector}"
          java.lang.System.out.println(line)
          assert(writes.isEmpty, line)
          assert(values == ("hi!", true, true, "c"), line)
      end for
    }
end WriteSeamTest
