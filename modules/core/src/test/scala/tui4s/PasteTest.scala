package tui4s

import kyo.*
import kyo.UI.*
import tui4s.fixtures as apps
import tui4s.testkit.Headless

/** The paste (RC-14 part). With `TuiOptions.paste` (bracketed paste, the
  * default) a paste is ONE edit of the focused text input and never a submit.
  * With `paste = false` the kit sends the text as single keys, as a terminal
  * without bracketed paste does: a line break is an Enter, so a form submits
  * (the lesson of the review thread TuiTest.scala:166 of the old PR 3).
  */
// claude 2026-10-13: created (C3)
class PasteTest extends RunnerSuite:

    /** A form with one bound text input; it counts the submits and the
      * `onInput` calls.
      */
    private def form1(
        draft: SignalRef[String],
        submits: AtomicInt,
        inputs: AtomicInt,
    )(using Frame): UI =
      form.onSubmit(submits.incrementAndGet)(
        input
          .value(draft)
          .onInput(_ => inputs.incrementAndGet)
          .style(_.width(30.px)),
      )

    "paste = true: a two-line paste into a form's input is one edit ('one two'), one frame, one onInput, and no submit" in {
      for
          draft   <- Signal.initRef("")
          submits <- AtomicInt.init(0)
          inputs  <- AtomicInt.init(0)
          h       <- Headless.start(40, 3)(_ => form1(draft, submits, inputs))
          fs      <- h.paste("one\ntwo")
          text    <- draft.get
          n       <- submits.get
          i       <- inputs.get
          s       <- h.screen
      yield assert(
        text == "one two" && fs.size == 1 && n == 0 && i == 1 && s.contains(
          "one two",
        ),
        s"text=$text frames=${fs.size} submits=$n onInput=$i\n$s",
      )
    }

    "paste = false: the same text is single keys; the line break is an Enter and submits the form once" in {
      for
          draft   <- Signal.initRef("")
          submits <- AtomicInt.init(0)
          inputs  <- AtomicInt.init(0)
          h       <- Headless.start(40, 3, TuiOptions(paste = false))(_ =>
                       form1(draft, submits, inputs),
                     )
          fs      <- h.paste("one\ntwo")
          text    <- draft.get
          n       <- submits.get
          i       <- inputs.get
      yield assert(
        text == "onetwo" && fs.size == 7 && n == 1 && i == 6,
        s"text=$text frames=${fs.size} submits=$n onInput=$i",
      )
    }

    "the Todo form: a bracketed paste stays in the draft; without it the first line is added and the second line lands in the empty draft" in {
      def todo(file: java.nio.file.Path, options: TuiOptions)(using
          Frame,
      )        =
        for
            s <- apps.Todo.init(file.toString)
            h <- Headless.start(100, 30, options)(_ => apps.Todo.view(s))
            _ <- h.paste("one\ntwo")
            d <- s.draft.get
            t <- s.items.get
            c <- h.screen
        yield (d, t.map(_.text).toSeq, c)
      val file = java.nio.file.Files.createTempFile("todo", ".txt")
      java.nio.file.Files.writeString(file, "[ ] buy milk\n")
      for
          bracketed <- todo(file, TuiOptions())
          _          = assert(
                         bracketed._1 == "one two" && bracketed._2 == Seq(
                           "buy milk",
                         ) && bracketed._3.contains("New: one two"),
                         s"$bracketed",
                       )
          keys      <- todo(file, TuiOptions(paste = false))
      yield assert(
        keys._1 == "two" && keys._2 == Seq("buy milk", "one") && keys._3
          .contains("[ ] one"),
        s"$keys",
      )
      end for
    }

    "a paste on an element that is not a text input does nothing" in {
      for
          clicks <- AtomicInt.init(0)
          h      <- Headless.start(20, 3)(_ =>
                      div(button("b").onClick(clicks.incrementAndGet)),
                    )
          fs     <- h.paste("x\ny")
          n      <- clicks.get
          fin    <- h.finished
      yield assert(
        n == 0 && !fin && fs.map(f => (f.repainted, f.updates)) == Seq((0, 0)),
        s"clicks=$n ended=$fin frames=$fs",
      )
    }
end PasteTest
