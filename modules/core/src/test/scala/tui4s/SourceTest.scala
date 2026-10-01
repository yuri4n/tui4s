package tui4s

import kyo.*
import kyo.test.*
import tui4s.testkit.*

/** Sources and tasks in the synchronous test kit: the diff, the `#n` suffixes,
  * the current-fold rule, keyed tasks, recover, failures (SPEC 2.13 rules 6-12,
  * 6.2).
  */
class SourceTest extends Test[Any]:
    private val src = Source.For[W]

    /** A state for these tests: a number, a step that the folds read, and flags
      * that change the declared sources.
      */
    final case class W(
        n: Int = 0,
        step: Int = 1,
        ticking: Boolean = true,
        twins: Boolean = false,
        loaded: Boolean = false,
    ) derives CanEqual

    private def tick(w: W): Source[W] =
      src.every(1.second)(s => s.copy(n = s.n + w.step)).id("tick")

    private def twin: Source[W] = src.every(1.second)(identity)

    private def keys(w: W, e: Event): Next[W] = e match
        case Key.Char('s') => w.copy(step = w.step + 1)
        case Key.Char('p') => w.copy(ticking = !w.ticking)
        case Key.Char('2') => w.copy(twins = true)
        case Key.Char('t') =>
          w.copy(n = w.n + 1)
            .withTask(src.task(Kyo.lift(5))((s, a: Int) => s.copy(n = s.n * a)))
        case Key.Char('k') =>
          w.withTask(
            src.task(Kyo.lift(0))((s, a: Int) => s.copy(n = a)).id("k"),
          )
        case Key.Char('q') => Quit
        case _             => w

    private val app = App[W](
      W(),
      w => text(s"n=${w.n}"),
      keys,
      w =>
        (if w.ticking then Seq(tick(w)) else Nil) ++
          (if w.twins then Seq(twin, twin, twin) else Nil),
    )

    "the declared sources are recorded, not run" in {
      val t = TuiTest(app, 20, 2)
      assert(t.sources == Seq("tick"))
      assert(t.started.isEmpty && t.stats.sources == 1 && t.stats.tasks == 0)
      assert(t.state.n == 0)
    }

    "a source left out stops; declared again it starts" in {
      val t = TuiTest(app, 20, 2)
      t.press('p')
      assert(t.sources.isEmpty && t.stats.sources == 0)
      assert(t.lastTrace.contains("\"stop\":[\"tick\"]"))
      t.press('p')
      assert(t.sources == Seq("tick"))
      assert(t.lastTrace.contains("\"start\":[\"tick\"]"))
    }

    "repeated labels get #1, #2 suffixes" in {
      val t  = TuiTest(app, 20, 2)
      t.press('2')
      val at = twin.label
      assert(at.startsWith("SourceTest.scala:"))
      assert(t.sources == Seq("tick", at, s"$at#1", s"$at#2"))
      t.emit(s"$at#2", ())
      assert(!t.quit)
    }

    "a value folds with the CURRENT fold of its label" in {
      val t = TuiTest(app, 20, 2)
      t.emit("tick", ())
      assert(t.state.n == 1)
      t.press('s', 's') // the fold now adds 3
      assert(t.sources == Seq("tick"))
      assert(!t.trace.exists(_.contains("\"start\":[\"tick\"]")))
      t.emit("tick", ())
      assert(t.state.n == 4)
      assert(t.screen.contains("n=4"))
      assert(t.stats.folds == 2)
    }

    "a one-shot task starts after its batch and folds once" in {
      val t     = TuiTest(app, 20, 2)
      t.press('t')
      assert(t.state.n == 1)
      val label = t.started.head
      assert(t.started == Seq(label) && label.startsWith("SourceTest.scala:"))
      assert(t.stats.tasks == 1)
      assert(t.lastTrace.contains(s"\"tasks\":[${"\"" + label + "\""}]"))
      t.emit(label, 5)
      assert(t.state.n == 5 && t.stats.tasks == 0)
      val done  = intercept[AssertionError](t.emit(label, 5))
      assert(done.getMessage.contains("no running source or task"))
    }

    "tasks without an id all run; a task with an id replaces the older one" in {
      val t = TuiTest(app, 20, 2)
      t.press('t', 't')
      assert(t.started.size == 2 && t.stats.tasks == 2)
      t.press('k', 'k')
      assert(t.started.drop(2) == Seq("k", "k"))
      assert(t.stats.tasks == 3) // the first "k" stopped
      t.emit("k", 7)
      assert(t.state.n == 7 && t.stats.tasks == 2)
    }

    "a task returned together with a quit does not start" in {
      val later = Source.For[Int].task(Kyo.lift(1))((s, _: Int) => s)
      val quits = App[Int](
        0,
        n => text(n.toString),
        (n, e) =>
          e match
              case Key.Char('a') => (n + 1).withTask(later)
              case Key.Char('x') => Next.Then(n + 10, Seq(later), quit = true)
              case Key.Char('q') => Quit
              case _             => n,
      )
      val one   = TuiTest(quits, 10, 1)
      one.press('x')
      assert(one.quit && one.state == 10 && one.started.isEmpty)
      val two   = TuiTest(quits, 10, 1)
      two.send(Key.Char('a'), Key.Char('q')) /* the task of 'a' waits for the
       * batch */
      assert(two.quit && two.state == 1 && two.started.isEmpty)
    }

    "a fold can return Quit, Quit(state) or withTask" in {
      final case class Q(n: Int, more: Boolean) derives CanEqual
      val s = Source.For[Q]
      val a = App[Q](
        Q(0, more = false),
        q => text(q.n.toString),
        (q, _) => q,
        q =>
          Seq(s.every(1.second)(q => q.copy(n = q.n + 1)).id("inc")) ++
            (if q.more then Nil
             else
                 Seq(
                   s.task(Kyo.lift(0))((q, a: Int) =>
                     if a < 0 then Quit
                     else if a == 0 then Quit(q.copy(n = 99))
                     else
                         q.copy(more = true)
                           .withTask(
                             s.task(Kyo.lift(1))((q, _: Int) => q).id("next"),
                           ),
                   ).id("job"),
                 )),
      )
      val t = TuiTest(a, 10, 1)
      t.emit("job", 1)
      assert(t.started == Seq("next") && t.sources == Seq("inc"))
      val u = TuiTest(a, 10, 1)
      u.emit("inc", ())
      u.emit("job", 0)
      assert(u.quit && u.state.n == 99 && u.screen.row(0) == "1")
      val v = TuiTest(a, 10, 1)
      v.emit("job", -1)
      assert(v.quit && v.state.n == 0 && v.error.isEmpty)
      assert(v.trace.last.contains("\"kind\":\"quit\",\"reason\":\"fold\""))
    }

    "a standing task stays done while it is declared (rule 7)" in {
      val s = Source.For[W]
      val a = App[W](
        W(),
        w => text(w.n.toString),
        (w, e) => if e == Key.Char('l') then w.copy(loaded = !w.loaded) else w,
        w =>
          if w.loaded then Nil
          else
              Seq(
                s.task(Kyo.lift(3))((w, a: Int) => w.copy(n = w.n + a))
                  .id("load"),
              ),
      )
      val t = TuiTest(a, 10, 1)
      t.emit("load", 3)
      assert(t.state.n == 3 && t.sources == Seq("load") && t.stats.sources == 0)
      val _ = intercept[AssertionError](t.emit("load", 3))
      t.press('l', 'l') // left out, then declared again: it runs again
      t.emit("load", 3)
      assert(t.state.n == 6)
    }

    "recover folds a failure; without recover the app ends with SourceFailed" in {
      val boom = new RuntimeException("boom")
      val s    = Source.For[Int]
      val a    = App[Int](
        0,
        n => text(n.toString),
        (n, _) => n,
        _ =>
          Seq(
            s.every(1.second)(_ + 1)
              .id("safe")
              .recover((n, e) => n + e.getMessage.length),
            s.every(1.second)(_ + 1).id("bare"),
          ),
      )
      val t    = TuiTest(a, 10, 1)
      t.fail("safe", boom)
      assert(t.state == 4 && !t.quit && t.screen.row(0) == "4")
      assert(t.sources == Seq("safe", "bare"))
      t.fail("bare", boom)
      assert(t.quit && t.error.contains(TuiError.SourceFailed("bare", boom)))
      assert(t.trace.last.contains("\"reason\":\"error\""))
    }

    "user code that throws ends the run with AppFailed" in {
      val boom                           = new IllegalStateException("bad")
      def where(t: TuiTest[Int]): String = t.error match
          case Some(TuiError.AppFailed(w, e)) if e eq boom => w
          case other                                       => s"unexpected: $other"
      val s                              = Source.For[Int]
      val base                           = App[Int](0, n => text(n.toString), (n, _) => n + 1)
      val upd                            =
        TuiTest(App[Int](0, n => text(n.toString), (_, _) => throw boom))
      upd.press('x')
      assert(upd.quit && where(upd) == "update on key:x")
      val vw                             = TuiTest(
        App[Int](
          0,
          n => if n > 0 then throw boom else text("0"),
          (n, _) => n + 1,
        ),
      )
      vw.press('x')
      assert(vw.quit && where(vw) == "view" && vw.screen.row(0) == "0")
      val srcs                           = TuiTest(
        App[Int](
          0,
          base.view,
          base.update,
          n => if n > 0 then throw boom else Nil,
        ),
      )
      srcs.press('x')
      assert(srcs.quit && where(srcs) == "sources")
      val fld                            = TuiTest(
        App[Int](
          0,
          base.view,
          base.update,
          _ => Seq(s.every(1.second)(_ => throw boom).id("f")),
        ),
      )
      fld.emit("f", ())
      assert(fld.quit && where(fld) == "fold of f")
      val init                           = TuiTest(new App[Int]:
          def init: Int                           = throw boom
          def view(n: Int): View                  = base.view(n)
          def update(n: Int, e: Event): Next[Int] = base.update(n, e))
      assert(init.quit && where(init) == "init" && init.frames == 0)
      val hand                           = TuiTest(base)
      hand.fold(_ => throw boom)
      assert(hand.quit && where(hand) == "fold")
    }

    "fold sends any change, as a general source would" in {
      val t = TuiTest(app, 20, 2)
      t.fold(w => w.copy(n = 42))
      assert(t.state.n == 42 && t.screen.contains("n=42") && t.stats.folds == 1)
    }

    "a general source folds the functions it dispatches" in {
      val s = Source.For[Int]
      val a = App[Int](
        0,
        n => text(n.toString),
        (n, _) => n,
        _ => Seq(s(_ => Kyo.unit).id("gen")),
      )
      val t = TuiTest(a, 10, 1)
      t.emit("gen", (n: Int) => n + 2)
      assert(t.state == 2)
    }

    "a stream source folds one element per emit" in {
      val s = Source.For[Vector[String]]
      val a = App[Vector[String]](
        Vector.empty,
        v => text(v.mkString(",")),
        (v, _) => v,
        _ => Seq(s.stream(Stream.init(Seq("a", "b")))(_ :+ _).id("lines")),
      )
      val t = TuiTest(a, 10, 1)
      t.emit("lines", "a")
      t.emit("lines", "b")
      assert(t.state == Vector("a", "b") && t.screen.row(0) == "a,b")
    }

    "emit checks the label and the type of the value" in {
      val t       = TuiTest(app, 20, 2)
      val unknown = intercept[AssertionError](t.emit("nope", 1))
      assert(unknown.getMessage.contains("emit(nope)"))
      assert(unknown.getMessage.contains("known: tick"))
      val s       = Source.For[Int]
      val cpu     = App[Int](
        0,
        n => text(n.toString),
        (n, _) => n,
        _ =>
          Seq(
            s.poll(1.second)(Kyo.lift(0.5))((n, d: Double) => n + d.toInt)
              .id("cpu"),
          ),
      )
      val u       = TuiTest(cpu, 10, 1)
      val wrong   = intercept[AssertionError](u.emit("cpu", "x"))
      assert(
        wrong.getMessage == "emit(cpu): x (String) does not fit the fold of source cpu",
      )
      assert(!u.quit)
      u.emit("cpu", 0.42)
      assert(u.state == 0 && u.stats.folds == 1)
      val gone    = intercept[AssertionError](u.fail("nope", new RuntimeException))
      assert(gone.getMessage.contains("fail(nope)"))
    }
end SourceTest
