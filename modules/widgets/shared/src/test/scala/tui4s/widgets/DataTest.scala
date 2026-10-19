package tui4s.widgets

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.Element
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.use.useSelection

import UiTree.*

/** The data widgets: the list, the table, the tree, the pager and the charts,
  * through their trees, their keys and their pure rules.
  */
// claude 2026-10-19: created (L2b)
class DataTest extends Test[Any]:

    private def activeRow(ui: UI)(using Frame): String < Sync =
      for
          box <- byRole(ui, "listbox")
          id   = box.flatMap(b =>
                   Maybe.fromOption(b.attrs.ariaAttrs.get("activedescendant")),
                 )
          row <- id.fold(Kyo.lift(Absent: Maybe[Element]))(byId(ui, _))
          t   <- row.fold(Kyo.lift(""))(text)
      yield t

    "a list: the arrows move the selection that aria-activedescendant names" in {
      for
          items  <- Signal.initRef(Chunk("a", "b", "c"))
          seen   <- Signal.initRef(Chunk.empty[String])
          done   <- Signal.initRef(Chunk.empty[String])
          ui     <- ListView(
                      items,
                      identity[String],
                      onSelect = s => seen.updateAndGet(_ :+ s),
                      onActivate = s => done.updateAndGet(_ :+ s),
                    )(span(_))
          first  <- activeRow(ui)
          _      <- press(ui, "listbox", Keyboard.ArrowDown)
          _      <- press(ui, "listbox", Keyboard.Char('j'))
          _      <- press(ui, "listbox", Keyboard.ArrowDown)
          _      <- press(ui, "listbox", Keyboard.Enter)
          last   <- activeRow(ui)
          moves  <- seen.get
          active <- done.get
      yield
          assert(first == "› a" && last == "› c", (first, last).toString)
          assert(moves == Chunk("b", "c") && active == Chunk("c"))
    }

    "a virtual list builds only the rows of its window" in {
      val many = Chunk.from((1 to 1000).map(i => s"row $i"))
      for
          items <- Signal.initRef(many)
          sel   <- useSelection(items, identity[String])
          ui    <- ListView.virtual(items, sel, height = 5)(span(_))
          _     <- sel.last
          _     <- untilTrue(
                     textsOf(ui, "option").map(_.lastOption.contains("› row 1000")),
                   )
          rows  <- textsOf(ui, "option")
      yield assert(rows.size == 5 && rows.head == "  row 996", rows.toString)
      end for
    }

    /** Polls `cond` (the window follows in a fiber), at most 2 s. */
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

    "a table: the key 2 sorts by the second column; the header marks it" in {
      val cols = (
        DataTable.Column[(String, Int)]("Name", _._1),
        DataTable.Column[(String, Int)](
          "Size",
          _._2.toString,
          order = Present(Ordering.by(_._2)),
        ),
      )
      for
          rows <- Signal.initRef(Chunk(("b", 2), ("a", 10), ("c", 1)))
          ui   <- DataTable(rows, _._1, cols._1, cols._2)()
          _    <- press(ui, "listbox", Keyboard.Char('2'))
          asc  <- textsOf(ui, "option")
          head <- textsOf(ui, "row")
          _    <- press(ui, "listbox", Keyboard.Char('2'))
          desc <- textsOf(ui, "option")
      yield
          assert(
            asc.map(_.drop(2).take(1)) == Chunk("c", "b", "a"),
            asc.toString,
          )
          assert(head.head.contains("Size ▲"), head.toString)
          assert(
            desc.map(_.drop(2).take(1)) == Chunk("a", "b", "c"),
            desc.toString,
          )
      end for
    }

    "a tree: ArrowRight opens a node; ArrowLeft goes back to the parent" in {
      val tree = Chunk(
        TreeView.Node(
          "src",
          Chunk(TreeView.Node("a.scala"), TreeView.Node("b.scala")),
        ),
        TreeView.Node("README"),
      )
      for
          roots <- Signal.initRef(tree)
          open  <- Signal.initRef(Set.empty[String])
          ui    <- TreeView(roots, identity[String], expanded = Model.Bound(open))(
                     span(_),
                   )
          t0    <- textsOf(ui, "option")
          _     <- press(ui, "listbox", Keyboard.ArrowRight)
          _     <- press(ui, "listbox", Keyboard.ArrowRight)
          a     <- activeRow(ui)
          _     <- press(ui, "listbox", Keyboard.ArrowLeft)
          b     <- activeRow(ui)
          t1    <- textsOf(ui, "option")
          set   <- open.get
      yield
          assert(t0 == Chunk("› ▸ src", "    README"), t0.toString)
          assert(a == "›     a.scala" && b == "› ▾ src", (a, b).toString)
          assert(t1.size == 4 && set == Set("src"), t1.toString)
      end for
    }

    "a pager follows the end; a key that scrolls up stops it" in {
      for
          lines  <- Signal.initRef(Chunk.from((1 to 30).map(i => s"line $i")))
          follow <- Signal.initRef(Pager.Follow.Tail)
          ui     <- Pager(lines, height = 5, follow = Model.Bound(follow))
          t0     <- text(ui)
          _      <- lines.updateAndGet(_ :+ "line 31")
          t1     <- text(ui)
          _      <- press(ui, "log", Keyboard.ArrowUp)
          f      <- follow.get
          t2     <- text(ui)
      yield
          assert(t0.contains("line 30") && !t0.contains("line 25"), t0)
          assert(t1.contains("line 31") && t1.contains("following"), t1)
          assert(
            f == Pager.Follow.Off && t2.contains("line 26") && !t2.contains(
              "line 31",
            ),
            t2,
          )
    }

    // claude 2026-10-19: created (L2b review): only a move up stops the follow
    "a pager: End and ArrowDown at the end keep the follow; f stops it; N and n go to a match" in {
      for
          lines  <- Signal.initRef(Chunk.from((1 to 30).map(i => s"line $i")))
          follow <- Signal.initRef(Pager.Follow.Tail)
          query  <- Signal.initRef("line 3")
          ui     <- Pager(
                      lines,
                      height = 5,
                      follow = Model.Bound(follow),
                      query = Model.Bound(query),
                    )
          _      <- press(ui, "log", Keyboard.End)
          _      <- press(ui, "log", Keyboard.ArrowDown)
          f1     <- follow.get
          _      <- press(ui, "log", Keyboard.Char('f'))
          f2     <- follow.get
          _      <- press(ui, "log", Keyboard.Char('N'))
          t3     <- text(ui)
          _      <- press(ui, "log", Keyboard.Char('n'))
          t4     <- text(ui)
          f4     <- follow.get
      yield
          assert(f1 == Pager.Follow.Tail && f2 == Pager.Follow.Off, (f1, f2).toString)
          assert(t3.contains("lines 3-7 of 30  2 matches"), t3)
          assert(t4.contains("lines 26-30 of 30") && f4 == Pager.Follow.Off, t4)
      end for
    }

    // claude 2026-10-19: created (L2b review): Fit counts cells
    "a Fit column counts cells: a wide character takes two, a combining mark none" in {
      val col = DataTable.Column[String]("N", identity)
      assert(DataTable.fit(Chunk(col), Chunk("日本語", "ab")) == Chunk(Present(6)))
      assert(DataTable.fit(Chunk(col), Chunk("e\u0301e\u0301")) == Chunk(Present(3)))
    }

    "the texts of the gauge, the sparkline, the bars and the progress" in {
      assert(Gauge.text(0.42, 10) == "████░░░░░░  42%")
      assert(
        Gauge.text(0.0, 4) == "░░░░   0%" && Gauge.text(1.0, 4) == "████ 100%",
      )
      assert(
        Gauge.text(-0.5, 4) == Gauge.text(0.0, 4) && Gauge.text(1.7, 4) == Gauge
          .text(1.0, 4),
      )
      assert(
        Sparkline.text(Chunk.empty) == "" && Sparkline.text(
          Chunk(0.0, 0.5, 1.0),
        ) == "▁▅█",
      )
      assert(
        Sparkline.text(Chunk(10.0, 20.0), 10, 20) == "▁█" && Sparkline.text(
          Chunk(-1.0, 2.0),
        ) == "▁█",
      )
      assert(BarChart.bar(5, 10, 4) == "██" && BarChart.bar(1, 8, 1) == "▏")
      assert(ProgressBar.bar(3, 10, 10) == "▕███       ▏")
    }
end DataTest

/** The laws of the data widgets. */
// claude 2026-10-19: created (L2b)
class DataPropertyTest extends PropertyTest[Any]:

    override protected def numSamples: Int = 200

    private val nums: Gen[Chunk[Int]] =
      Gen.listOfN(20, Gen.int.map(n => Math.floorMod(n, 50))).map(Chunk.from)

    private val cols = Chunk(
      DataTable.Column[Int]("n", _.toString, order = Present(Ordering.Int)),
    )

    "a sort keeps the rows and orders them by the column" - {
      forAll(
        nums,
        Gen.oneOf(DataTable.Direction.Ascending, DataTable.Direction.Descending),
      ) { (xs, d) =>
        val s       = DataTable.sorted(xs, cols, DataTable.Sort.By(0, d))
        val ordered = s
          .zip(s.drop(1))
          .forall((a, b) =>
            if d == DataTable.Direction.Ascending then a <= b else a >= b,
          )
        assert(s.sorted == xs.sorted && ordered)
      }
    }

    "three presses of a column key give back no sort" - {
      forAll(Gen.int.map(n => Math.floorMod(n, 9))) { c =>
        val s = (1 to 3).foldLeft(DataTable.Sort.Unsorted: DataTable.Sort)(
          (o, _) => DataTable.nextSort(o, c),
        )
        assert(s == DataTable.Sort.Unsorted)
      }
    }

    private def tree(seed: Int, depth: Int): Chunk[TreeView.Node[Int]] =
      if depth == 0 then Chunk.empty
      else
          Chunk.from((0 until Math.floorMod(seed, 3)).map(i =>
              val k = seed * 7 + i * 13 + depth
              TreeView.Node(k, tree(k, depth - 1)),
          ))

    // claude 2026-10-19: changed (L2b review): the open and the close are
    // tested (the old law compared one expression with itself)
    "a tree: an open adds the children under the node; a close gives back the rows; a row's parent is shallower" - {
      forAll(Gen.int, Gen.int, Gen.int) { (seed, pick, pick2) =>
        val roots = tree(Math.floorMod(seed, 1000) + 3, 3)
        val all   = TreeView.rows(roots, identity[Int], Set.empty)
        if all.isEmpty then assert(roots.isEmpty)
        else
            val node   = roots(Math.floorMod(pick, roots.size))
            val k      = node.value
            val rows1  = TreeView.rows(roots, identity[Int], Set(k))
            val at     = rows1.indexWhere(_.value == k)
            val kids   = rows1.slice(at + 1, at + 1 + node.children.size)
            val opened =
              rows1.size == all.size + node.children.size &&
                kids.map(_.value) == node.children.map(_.value) &&
                kids.forall(_.depth == 1)
            // open a child too, then close it: the rows of the first open
            val inner  = node.children.filter(_.children.nonEmpty)
            val closed =
              inner.isEmpty || {
                  val c     = inner(Math.floorMod(pick2, inner.size)).value
                  val rows2 = TreeView.rows(roots, identity[Int], Set(k, c))
                  val back  = TreeView.rows(roots, identity[Int], Set(k, c) - c)
                  rows2.size > rows1.size && back == rows1
              }
            val parents = rows1.indices.forall(i =>
              TreeView.parent(rows1, i).forall(p => rows1(p).depth < rows1(i).depth),
            )
            assert(all.forall(_.depth == 0) && opened && closed && parents)
        end if
      }
    }

    "the top line of a pager stays in the text; Tail shows the end" - {
      forAll(Gen.int, Gen.int, Gen.int) { (n, h, o) =>
        val total  = Math.floorMod(n, 100)
        val height = Math.floorMod(h, 20) + 1
        val off    = Pager.top(total, height, o, Pager.Follow.Off)
        val tail   = Pager.top(total, height, o, Pager.Follow.Tail)
        assert(
          off >= 0 && off <= (total - height).max(0) && tail == (total - height)
            .max(0),
        )
      }
    }

    "a bar never passes its width and grows with its value" - {
      forAll(Gen.int, Gen.int, Gen.int) { (a, b, w) =>
        val width    = Math.floorMod(w, 40)
        val (lo, hi) =
          (Math.floorMod(a, 100).toDouble, Math.floorMod(b, 100).toDouble)
        val (x, y)   = (lo.min(hi), lo.max(hi))
        val bx       = BarChart.bar(x, 100, width)
        val by       = BarChart.bar(y, 100, width)
        assert(by.length <= width && bx.length <= by.length)
      }
    }

    "a spinner frame is in 0 to n - 1" - {
      forAll(Gen.int, Gen.int) { (t, n) =>
        val i = Spinner.frameAt(
          Instant.Epoch + Math.floorMod(t, 1000000).millis,
          100.millis,
          Math.floorMod(n, 12) + 1,
        )
        assert(i >= 0 && i <= Math.floorMod(n, 12))
      }
    }
end DataPropertyTest
