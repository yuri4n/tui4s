package tui4s.widgets

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.Input
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

import UiTree.*

/** The input widgets: their trees, their keys and their pure rules. */
// claude 2026-10-19: created (L2b)
class InputsTest extends Test[Any]:

    private def field(ui: UI, role: String)(using Frame): Input < Sync =
      elements(ui).map(_.collect {
        case i: Input if i.attrs.role.contains(role) => i
      }.head)

    "a search box: the query follows the text after the quiet time; Esc clears" in {
      for
          text <- Signal.initRef("")
          box  <- SearchBox(quiet = 20.millis, text = Model.Bound(text))
          _    <- text.set("kyo")
          ok   <- untilTrue(box.query.current.map(_ == "kyo"))
          f    <- field(box.view, "searchbox")
          _    <- f.attrs.onKeyDown.get(
                    KeyboardEvent(Keyboard.Escape, Modifiers.none, Absent),
                  )
          now  <- text.get
      yield assert(ok && now.isEmpty)
    }

    /** Polls `cond` (a fiber moves the query), at most 2 s. */
    private def untilTrue(
        cond: => Boolean < Sync,
    )(using Frame): Boolean < Async =
      Abort
        .run[Timeout](Async.timeout(2.seconds)(Loop.foreach {
          cond.map(ok =>
            if ok then Loop.done(true)
            else Async.sleep(5.millis).andThen(Loop.continue),
          )
        }))
        .map(_.getOrElse(false))

    "an autocomplete: typing opens the list; ArrowDown and Enter pick" in {
      for
          text   <- Signal.initRef("")
          picked <- Signal.initRef("")
          ui     <- Autocomplete(
                      Signal.initConst(Chunk("scala", "scalajs", "kotlin")),
                      text = Model.Bound(text),
                      onPick = picked.set,
                    )
          f      <- field(ui, "combobox")
          _      <- text.set("sca")
          _      <- f.onInput.get("sca")
          shown  <- textsOf(ui, "option")
          _      <- f.attrs.onKeyDown.get(
                      KeyboardEvent(Keyboard.ArrowDown, Modifiers.none, Absent),
                    )
          _      <- f.attrs.onKeyDown.get(
                      KeyboardEvent(Keyboard.Enter, Modifiers.none, Absent),
                    )
          now    <- text.get
          p      <- picked.get
          closed <- textsOf(ui, "option")
      yield
          assert(shown == Chunk("› scala", "  scalajs"), shown.toString)
          assert(now == "scalajs" && p == "scalajs" && closed.isEmpty)
    }

    "a checklist: Space checks the selected item" in {
      for
          done <- Signal.initRef(Set.empty[Int])
          ui   <- Checklist(
                    Signal.initConst(Chunk(1, 2, 3)),
                    identity[Int],
                    checked = Model.Bound(done),
                  )(n => span(s"task $n"))
          _    <- press(ui, "listbox", Keyboard.ArrowDown)
          _    <- press(ui, "listbox", Keyboard.Space)
          set  <- done.get
          rows <- textsOf(ui, "option")
      yield assert(set == Set(2) && rows(1) == "› [x] task 2", rows.toString)
    }

    "a radio group: the arrows choose and wrap" in {
      for
          size <- Signal.initRef("M")
          ui   <- RadioGroup("S", "M", "L")(value = Model.Bound(size))
          _    <- press(ui, "radiogroup", Keyboard.ArrowDown)
          a    <- size.get
          _    <- press(ui, "radiogroup", Keyboard.ArrowDown)
          b    <- size.get
          t    <- textsOf(ui, "radio")
      yield assert(a == "L" && b == "S" && t.head == "(•) S", t.toString)
    }

    "a number stepper: steps, pages and the ends stay in the range" in {
      for
          n  <- Signal.initRef(5)
          ui <-
            NumberStepper(value = Model.Bound(n), min = 0, max = 20, step = 2)
          _  <- press(ui, "spinbutton", Keyboard.ArrowUp)
          a  <- n.get
          _  <- press(ui, "spinbutton", Keyboard.PageUp)
          b  <- n.get
          _  <- press(ui, "spinbutton", Keyboard.Home)
          c  <- n.get
      yield assert((a, b, c) == (7, 20, 0))
    }

    "a date field: the check of the text" in {
      assert(DateField.parse("2024-02-29").map(_.text) == Right("2024-02-29"))
      assert(
        DateField.parse("2023-02-29") == Left(DateField.Problem.Day(29, 28)),
      )
      assert(DateField.parse("2023-13-01") == Left(DateField.Problem.Month(13)))
      assert(DateField.parse("23-1-1") == Left(DateField.Problem.Format))
      assert(DateField.days(1900, 2) == 28 && DateField.days(2000, 2) == 29)
    }
end InputsTest

/** The laws of the input widgets. */
// claude 2026-10-19: created (L2b)
class InputsPropertyTest extends PropertyTest[Any]:

    override protected def numSamples: Int = 200

    private val word: Gen[String] =
      Gen
        .listOfN(6, Gen.int.map(n => ('a' + Math.floorMod(n, 4)).toChar))
        .map(_.mkString)

    "a suggestion matches the text; Prefix ⊆ Contains ⊆ Fuzzy" - {
      forAll(Gen.listOfN(10, word), word) { (opts, w) =>
        val t         = w.take(2)
        val all       = Chunk.from(opts)
        import Autocomplete.Match.*
        val (p, c, f) = (
          Autocomplete.suggest(all, t, Prefix, 99),
          Autocomplete.suggest(all, t, Contains, 99),
          Autocomplete.suggest(all, t, Fuzzy, 99),
        )
        assert(
          c.forall(_.contains(t)) && p.forall(c.contains) && c.forall(
            f.contains,
          ) &&
            Autocomplete.suggest(all, t, Fuzzy, 3).size <= 3,
        )
      }
    }

    "a step stays in min to max and keeps the order of the steps" - {
      forAll(Gen.int, Gen.int, Gen.int) { (n, a, b) =>
        val (s1, s2) = (Math.floorMod(a, 30) - 15, Math.floorMod(b, 30) - 15)
        val x        = NumberStepper.stepBy(n, s1.min(s2), 7, -100, 100)
        val y        = NumberStepper.stepBy(n, s1.max(s2), 7, -100, 100)
        assert(x >= -100 && y <= 100 && x <= y)
      }
    }

    "a valid date reads back from its text; a day past the month is a problem" - {
      forAll(Gen.int, Gen.int, Gen.int) { (y, m, d) =>
        val year  = Math.floorMod(y, 9999) + 1
        val month = Math.floorMod(m, 12) + 1
        val last  = DateField.days(year, month)
        val day   = Math.floorMod(d, last) + 1
        val date  = DateField.Date(year, month, day)
        assert(
          DateField.parse(date.text) == Right(date) &&
            DateField.parse(DateField.Date(year, month, last + 1).text) ==
            Left(DateField.Problem.Day(last + 1, last)),
        )
      }
    }

    "a radio step wraps into the options" - {
      forAll(Gen.int, Gen.int, Gen.int) { (i, d, n) =>
        val size = Math.floorMod(n, 9) + 1
        val r    = RadioGroup.step(i, d, size)
        assert(r >= 0 && r < size)
      }
    }
end InputsPropertyTest
