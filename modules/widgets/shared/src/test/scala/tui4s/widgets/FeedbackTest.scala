package tui4s.widgets

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.Input
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.use.{Binding, Chord, Keymap}

import UiTree.*

/** The feedback and navigation widgets: their trees, their keys and their pure
  * rules.
  */
// claude 2026-10-19: created (L2b)
class FeedbackTest extends Test[Any]:

    /** Polls `cond` (a dialog flow runs on its own fiber), at most 2 s. */
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

    private def typeInto(ui: UI, text: String)(using Frame): Unit < Sync =
      elements(ui).map(_.collect { case i: Input => i }.head.value match
          case Present(UI.Bound.Ref(r)) => r.set(text)
          case _                        => Kyo.unit)

    "a status bar and the key hints show their texts" in {
      val keys = Keymap.of(
        Binding.of("quit", Chord.ctrl('q'))(Kyo.unit),
        Binding.of("", Chord.char('x'))(Kyo.unit),
      )
      for
          a <-
            text(StatusBar(Signal.initConst("file.txt"), right = span("UTF-8")))
          b <- text(KeyHints(keys))
      yield assert(a == "file.txtUTF-8" && b == "Ctrl-Q quit", (a, b).toString)
    }

    "a command palette: open, filter, Enter runs the command and closes" in {
      for
          ran  <- Signal.initRef("")
          keys  =
            Keymap.of(
              Binding.of("save the file", Chord.ctrl('s'))(ran.set("save")),
              Binding.of("quit", Chord.ctrl('q'))(ran.set("quit")),
            )
          p    <- CommandPalette(keys)
          t0   <- text(p.view)
          _    <- p.open
          _    <- typeInto(p.view, "qt")
          rows <- textsOf(p.view, "option")
          _    <- press(p.view, "dialog", Keyboard.Enter)
          did  <- ran.get
          open <- p.isOpen.current
      yield
          assert(t0.isEmpty && rows == Chunk("› quitCtrl-Q"), rows.toString)
          assert(did == "quit" && !open)
    }

    "a confirm: ask shows the layer; y answers true" in {
      for
          answer <- Signal.initRef(Absent: Maybe[Boolean])
          m      <- Modal.confirm()
          _      <- m.ask("Delete?")(yes => answer.set(Present(yes)))
          shown  <- untilTrue(m.request.current.map(_ == Present("Delete?")))
          t      <- text(m.view)
          _      <- press(m.view, "alertdialog", Keyboard.Char('y'))
          got    <- untilTrue(answer.current.map(_ == Present(true)))
      yield assert(
        shown && got && t.contains("Delete?") && t.contains("Yes"),
        t,
      )
    }

    "a prompt: Enter answers the text; Esc answers Absent" in {
      for
          got <- Signal.initRef(Chunk.empty[Maybe[String]])
          m   <- Modal.prompt()
          _   <- m.ask("Name?")(a => got.updateAndGet(_ :+ a))
          _   <- untilTrue(m.request.current.map(_.nonEmpty))
          _   <- typeInto(m.view, "Ada")
          _   <- press(m.view, "alertdialog", Keyboard.Enter)
          _   <- m.ask("Again?")(a => got.updateAndGet(_ :+ a))
          _   <- untilTrue(m.request.current.map(_ == Present("Again?")))
          _   <- press(m.view, "alertdialog", Keyboard.Escape)
          ok  <- untilTrue(got.current.map(_.size == 2))
          all <- got.get
      yield assert(ok && all == Chunk(Present("Ada"), Absent), all.toString)
    }

    "toasts show the newest last and at most max" in {
      for
          t  <- Toasts(max = 2)
          _  <- t.show("one")
          _  <- t.show("two", Toasts.Level.Success)
          _  <- t.show("three", Toasts.Level.Error)
          ok <- untilTrue(text(t.view).map(_.contains("three")))
          s  <- text(t.view)
      yield assert(
        ok && !s.contains("one") && s.contains("✓ two") && s.contains(
          "✗ three",
        ),
        s,
      )
    }

    "a menu bar: ArrowDown opens a menu; Enter runs its item" in {
      for
          ran  <- Signal.initRef("")
          ui   <- Menu.bar(
                    Menu.Group(
                      "File",
                      Keymap.of(
                        Binding.of("Open", Chord.ctrl('o'))(ran.set("open")),
                      ),
                    ),
                    Menu.Group(
                      "Edit",
                      Keymap.of(
                        Binding.of("Undo", Chord.ctrl('z'))(ran.set("undo")),
                      ),
                    ),
                  )
          bs   <- buttons(ui)
          _    <- bs(1).attrs.onKeyDown
                    .get(KeyboardEvent(Keyboard.ArrowDown, Modifiers.none, Absent))
          its  <- textsOf(ui, "menuitem")
          _    <- press(ui, "menu", Keyboard.Enter)
          did  <- ran.get
          gone <- byRole(ui, "menu")
      yield assert(
        its == Chunk("› UndoCtrl-Z") && did == "undo" && gone.isEmpty,
        its.toString,
      )
    }

    // claude 2026-10-19: created (L2b review): the arrows in an open menu
    "a menu: ArrowDown moves the named highlight; ArrowRight opens the next menu" in {
      for
          ui   <- Menu.bar(
                    Menu.Group(
                      "File",
                      Keymap.of(
                        Binding.of("Open", Chord.ctrl('o'))(Kyo.unit),
                        Binding.of("Save", Chord.ctrl('s'))(Kyo.unit),
                      ),
                    ),
                    Menu.Group(
                      "Edit",
                      Keymap.of(Binding.of("Undo", Chord.ctrl('z'))(Kyo.unit)),
                    ),
                  )
          bs   <- buttons(ui)
          _    <- bs(0).attrs.onKeyDown
                    .get(KeyboardEvent(Keyboard.ArrowDown, Modifiers.none, Absent))
          _    <- press(ui, "menu", Keyboard.ArrowDown)
          a    <- textsOf(ui, "menuitem")
          m    <- byRole(ui, "menu")
          id    = m.flatMap(e =>
                    Maybe.fromOption(e.attrs.ariaAttrs.get("activedescendant")),
                  )
          row  <- id.fold(Kyo.lift(Absent: Maybe[Ast.Element]))(byId(ui, _))
          name <- row.fold(Kyo.lift(""))(text)
          _    <- press(ui, "menu", Keyboard.ArrowRight)
          b    <- textsOf(ui, "menuitem")
          _    <- press(ui, "menu", Keyboard.ArrowRight)
          c    <- textsOf(ui, "menuitem")
      yield
          assert(a == Chunk("  OpenCtrl-O", "› SaveCtrl-S"), a.toString)
          assert(name == "› SaveCtrl-S", name)
          assert(
            b == Chunk("› UndoCtrl-Z") && c.headOption.contains("› OpenCtrl-O"),
            (b, c).toString,
          )
      end for
    }

    "a tooltip shows while its target has the focus" in {
      for
          ui <- Tooltip("Saves the file")(button("Save"))
          b  <- buttons(ui)
          t0 <- text(ui)
          _  <- b.head.attrs.onFocus.get
          t1 <- text(ui)
          _  <- b.head.attrs.onBlur.get
          t2 <- text(ui)
      yield assert(
        t0 == "Save" && t1 == "SaveSaves the file" && t2 == "Save",
        (t0, t1, t2).toString,
      )
    }

    "breadcrumbs: a part before the last is a button that picks its index" in {
      for
          picked <- Signal.initRef(-1)
          ui     <- Breadcrumbs(
                      Signal.initConst(Chunk("home", "docs", "api")),
                      picked.set,
                    )
          bs     <- buttons(ui)
          _      <- click(ui, 1)
          p      <- picked.get
          t      <- text(ui)
      yield assert(bs.size == 2 && p == 1 && t == "home › docs › api", t)
    }

    "pagination: the arrows and End move a bound page in its range" in {
      for
          page <- Signal.initRef(0)
          ui   <- Pagination(Signal.initConst(20), page = Model.Bound(page))
          nav   = (k: Keyboard) =>
                    elements(ui).map(
                      _.find(_.attrs.tabIndex.contains(0)).get.attrs.onKeyDown
                        .get(KeyboardEvent(k, Modifiers.none, Absent))
                        .unit,
                    )
          _    <- nav(Keyboard.ArrowLeft)
          a    <- page.get
          _    <- nav(Keyboard.End)
          b    <- page.get
          t    <- text(ui)
      yield assert(a == 0 && b == 19 && t == "‹1…19[20]›", t)
    }

    "tags: Enter adds a tag; Delete on a chip removes it" in {
      for
          tags <- Signal.initRef(Chunk("scala"))
          ui   <- Tags(tags = Model.Bound(tags))
          f     = (k: Keyboard) =>
                    elements(ui).map(
                      _.collect { case i: Input => i }.head.attrs.onKeyDown
                        .get(KeyboardEvent(k, Modifiers.none, Absent))
                        .unit,
                    )
          _    <- typeInto(ui, "kyo")
          _    <- f(Keyboard.Enter)
          a    <- tags.get
          _    <- press(ui, "listitem", Keyboard.Delete)
          b    <- tags.get
      yield assert(
        a == Chunk("scala", "kyo") && b == Chunk("kyo"),
        (a, b).toString,
      )
    }

    "a wizard: Next moves; Finish calls onFinish on the last step" in {
      for
          done <- Signal.initRef(false)
          ui   <- Wizard(
                    Wizard.Step("One", span("[1]")),
                    Wizard.Step("Two", span("[2]")),
                  )(onFinish = done.set(true))
          t0   <- text(ui)
          _    <- click(ui, 1)
          t1   <- text(ui)
          _    <- click(ui, 1)
          d    <- done.get
          bs   <- buttons(ui)
      yield
          assert(
            t0.contains("● One") && t0.contains("[1]") && t1.contains(
              "✓ One",
            ) && t1.contains("[2]"),
            t1,
          )
          assert(
            d && bs.map(b => b.disabled.getOrElse(false)) == Chunk(false, false),
          )
    }
end FeedbackTest

/** The laws of the feedback and navigation widgets. */
// claude 2026-10-19: created (L2b)
class FeedbackPropertyTest extends PropertyTest[Any]:

    override protected def numSamples: Int = 200

    private val small: Gen[Int] = Gen.int.map(n => Math.floorMod(n, 40))

    "page cells: the first, the last and the current page; in order; no two gaps" - {
      forAll(small, small, Gen.int.map(n => Math.floorMod(n, 3))) { (p, n, a) =>
        val pages = n + 1
        val page  = p.min(pages - 1)
        val cs    = Pagination.cells(page, pages, a)
        val idx   = cs.collect { case Pagination.Cell.Page(i) => i }
        val gaps  = cs
          .zip(cs.drop(1))
          .exists((x, y) =>
            x == Pagination.Cell.Gap && y == Pagination.Cell.Gap,
          )
        assert(
          idx.contains(0) && idx.contains(pages - 1) && idx.contains(page) &&
            idx
              .zip(idx.drop(1))
              .forall((x, y) => x < y) && !gaps && cs.size <= 2 * a + 5,
        )
      }
    }

    "a tag added twice is there once; a removed tag is gone" - {
      forAll(Gen.listOfN(8, small), small) { (xs, x) =>
        val tags  =
          xs.foldLeft(Chunk.empty[String])((t, n) => Tags.add(t, s"t$n"))
        val twice = Tags.add(Tags.add(tags, s"t$x"), s" t$x ")
        val i     = twice.indexOf(s"t$x")
        assert(
          tags.distinct == tags && twice
            .count(_ == s"t$x") == 1 && !Tags.remove(twice, i).contains(s"t$x"),
        )
      }
    }

    "visible toasts are not gone, at most max, the newest last" - {
      forAll(
        Gen.listOfN(10, small),
        small,
        Gen.int.map(n => Math.floorMod(n, 4)),
      ) { (ends, now, max) =>
        val all   = Chunk.from(
          ends.zipWithIndex.map((e, i) =>
            Toasts.Toast(s"$i", Toasts.Level.Info, Instant.Epoch + e.seconds),
          ),
        )
        val t     = Instant.Epoch + now.seconds
        val shown = Toasts.visible(all, t, max)
        val alive = all.count(_.until > t)
        val order = shown.map(_.text.toInt)
        // claude 2026-10-19: changed (L2b review): laws, not the body again
        assert(
          shown.forall(_.until > t) && shown.size == alive.min(max) &&
            order == order.sorted && (shown.isEmpty || shown.last == all
              .filter(_.until > t)
              .last),
        )
      }
    }

    // claude 2026-10-19: created (L2b review): the law of push
    "a push keeps at most max toasts, the new one last, and none that is gone" - {
      forAll(
        Gen.listOfN(10, small),
        small,
        Gen.int.map(n => Math.floorMod(n, 4) + 1),
      ) { (ends, now, max) =>
        val all = Chunk.from(
          ends.zipWithIndex.map((e, i) =>
            Toasts.Toast(s"$i", Toasts.Level.Info, Instant.Epoch + e.seconds),
          ),
        )
        val t   = Instant.Epoch + now.seconds
        val nw  = Toasts.Toast("new", Toasts.Level.Success, t + 3.seconds)
        val q   = Toasts.push(all, nw, t, max)
        assert(
          q.size <= max && q.lastOption.contains(nw) && q
            .dropRight(1)
            .forall(_.until > t),
        )
      }
    }

    "a wizard move stays in the steps; the status of the current step is Current" - {
      forAll(small, Gen.int.map(_ % 50), small) { (c, d, n) =>
        val steps = n + 1
        val m     = Wizard.move(c, d, steps)
        val ss    = (0 until steps).map(Wizard.status(_, m))
        // claude 2026-10-19: changed (L2b review): one Current, Done before it,
        // Todo after it
        assert(
          m >= 0 && m < steps && ss.count(_ == Wizard.Status.Current) == 1 &&
            ss.take(m).forall(_ == Wizard.Status.Done) && ss
              .drop(m + 1)
              .forall(_ == Wizard.Status.Todo),
        )
      }
    }

    "the palette keeps the bindings whose help has the query in order" - {
      forAll(Gen.listOfN(3, small)) { qs =>
        val bs = Chunk.from(
          (0 until 6).map(i =>
            Binding.of(s"cmd $i alpha", Chord.char('a'))(Kyo.unit),
          ),
        )
        val q  = qs.map(n => "cmd alph0123456789".charAt(n % 18)).mkString
        val r  = CommandPalette.filter(bs, q)
        assert(
          r.forall(b =>
            Autocomplete.matches(Autocomplete.Match.Fuzzy, q, b.help),
          ) && CommandPalette.filter(bs, "") == bs,
        )
      }
    }
end FeedbackPropertyTest
