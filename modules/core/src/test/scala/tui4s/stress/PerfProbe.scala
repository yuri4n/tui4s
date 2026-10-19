package tui4s.stress

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import kyo.UI.Ast.{Foreach, Text}
import tui4s.fixtures.Dashboard
import tui4s.internal.*

import java.lang.management.ManagementFactory

/** The frame-path probe (PF-b). It measures the pure layout and paint of a
  * frame, with no runner and no terminal, on four scenarios:
  *   - `dashboard full`: the Dashboard on 200x60, a full layout and a paint;
  *   - `list full`, `list edit`, `list scroll`: a list of 2,000 rows in a
  *     viewport on 120x40, a full layout and a paint; a step that changes the
  *     text of one row; a step that scrolls the viewport by one row;
  *   - `log append`: a log viewport that gets one new line in each step (200
  *     steps from 100 lines);
  *   - `dashboard canvas`: the write and the diff of the plan of the dashboard
  *     on a canvas that holds it already (the work that a repair frame with an
  *     equal plan skips, F9b).
  *
  * A step does what the runner does in a frame: the relayout of the owner of
  * each changed region and of each scrolled viewport, the damage of the layers,
  * the paint, and the scan for warnings. Each scenario runs `rounds` warm-up
  * rounds, then `rounds` measured rounds. The probe prints the median and the
  * 90th percentile of the time of one operation in ms, and the median of the
  * bytes that the thread allocated for it (ThreadMXBean). Run it alone on the
  * machine: `sbt 'set core / Test / javaOptions += "-Dtui4s.stress=30"'
  * 'core/testOnly tui4s.stress.PerfProbe'`.
  */
// claude 2026-10-19: created (PF-b): the measurement probe
class PerfProbe extends StressSuite:
    import PerfProbe.*

    probe("the frame path: layout, paint and canvas") in {
      val n = rounds.getOrElse(30)
      for dash <- dashboardScene
      yield
          val (ui, env) = dash
          val laid      = Layout.layoutAll(ui, env)
          val plan      = Paint(laid, env, full(env.screen))
          val canvas    = Canvas(TestBackend(env.screen.w, env.screen.h))
          canvas.write(plan)
          discard(canvas.flush(plan))
          val results   = Vector(
            measure("dashboard full", n)(() =>
                val l = Layout.layoutAll(ui, env)
                Paint(l, env, full(env.screen)),
            ),
            measure("dashboard canvas", n)(() =>
                canvas.write(plan)
                canvas.flush(plan),
            ),
          ) ++ ListScene().results(n) ++ Vector(LogScene().result(n))
          results.foreach(r => report(r.line))
          assert(results.forall(_.samples > 0))
      end for
    }
end PerfProbe

object PerfProbe:
    /** The damage of a full frame of a screen of `size`. */
    def full(size: Size): Vector[Damage] =
      Vector(
        Damage.Clear(Rect(0, 0, size.w, size.h), None),
        Damage.Paint(Loc.screen),
      )

    /** The numbers of one scenario. */
    final case class Result(
        name: String,
        nanos: Vector[Long],
        bytes: Vector[Long],
    ):
        def samples: Int = nanos.size

        private def at(q: Double, xs: Vector[Long]): Long =
            val s = xs.sorted
            s(((s.size - 1) * q).round.toInt)

        def line: String =
          f"perf $name%-18s n=$samples%5d median=${at(0.5, nanos) / 1e6}%9.3f ms p90=${at(0.9, nanos) / 1e6}%9.3f ms alloc=${at(0.5, bytes) / 1024.0}%10.1f KB"
    end Result

    private val threads = ManagementFactory.getThreadMXBean match
        case t: com.sun.management.ThreadMXBean => Some(t)
        case _                                  => None

    private def allocated(): Long =
      threads.fold(0L)(_.getCurrentThreadAllocatedBytes)

    // a sink for the results, so that the JIT keeps the work
    @volatile var sink: Any = ()

    /** The time and the allocation of one run of `f`. */
    def once(f: () => Any): (Long, Long) =
        val b0 = allocated()
        val t0 = java.lang.System.nanoTime()
        sink = f()
        val t1 = java.lang.System.nanoTime()
        ((t1 - t0), allocated() - b0)

    /** `rounds` warm-up runs of `f`, then `rounds` measured runs. */
    def measure(name: String, rounds: Int)(f: () => Any): Result =
        (1 to rounds).foreach(_ => sink = f())
        val runs = Vector.fill(rounds)(once(f))
        Result(name, runs.map(_._1), runs.map(_._2))

    /** The pure part of a frame of the runner: the relayout of the owner of
      * each region of `regions` (not under another one), then of each viewport
      * of `scrolled` in place, the damage of the layers, the paint and the scan
      * for warnings.
      */
    def step(
        laid: Laid,
        ui: UI,
        env: Scene,
        regions: Vector[Loc],
        scrolled: Vector[Loc],
    ): (Laid, Plan) =
        val (l1, d1) =
          Loc.tops(regions).foldLeft((laid, Vector.empty[Damage])) {
            case ((l, d), r) =>
              l.regionOwner.get(r) match
                  case Some(owner) =>
                    val (l2, d2) = Layout.relayout(l, owner, Set.empty, env, ui)
                    (l2, d ++ d2)
                  case None        => (l, d)
          }
        val (l2, d2) = scrolled.foldLeft((l1, d1)) { case ((l, d), p) =>
          val (l3, d3) = Layout.relayout(l, p, Set.empty, env, ui)
          (l3, d ++ d3)
        }
        val damage   = d2 ++ Layout.layerDamage(laid, l2)
        val plan     = Paint(l2, env, damage)
        sink = Warnings.scan(l2, damage, Set.empty)
        (l2, plan)
    end step

    /** The Dashboard of the fixtures with 40 samples and 6 log lines, mounted
      * once ([[Mount.walk]]) on 200x60; the watchers stop at once.
      */
    def dashboardScene(using Frame): (UI, Scene) < (Async & Scope) =
      for
          s     <- Dashboard.init
          _     <- Kyo.foreach(1 to 40)(i =>
                     Dashboard.tick(s, Chunk(i / 40.0, 1 - i / 40.0, 0.5)),
                   )
          _     <-
            s.logs.set(
              Chunk.from((1 to 6).map(i => (i, s"event #$i: request served"))),
            )
          ui     = Dashboard.view(s)
          inbox <- Channel.initUnscoped[Msg](1 << 16)
          gauge <- FiberGauge.init
          w     <- Mount(inbox, gauge).walk(
                     ui,
                     Loc.root,
                     World.init(ui, Size(200, 60)),
                     Map.empty,
                   )
          _     <- gauge.close
      yield (ui, w.env)

    /** A `Foreach` whose items come from the scene (the generators of the
      * oracle do the same).
      */
    def region(using Frame): UI =
      Foreach[String, UI](
        Signal.initConst(Chunk.empty[String]),
        Absent,
        (_, _) => Text(""),
      )

    def scene(contents: Map[Loc, Content], size: Size, scroll: Loc => Offset) =
      Scene(contents.get, None, size, _ => Int.MaxValue, scroll)

    /** 2,000 rows in a viewport of 30 rows on 120x40. */
    class ListScene(using Frame):
        val size     = Size(120, 40)
        val viewport = Loc.root.child(1)
        val rows     = viewport.child(0)

        def row(i: Int, t: String): UI =
          div.style(_.row.gap(1.px))(span(f"$i%5d"), span(t))

        val ui: UI = div(
          h1("List"),
          div.style(
            _.height(30.px).overflowY(_.scroll).border(1.px, Style.Color.slate),
          )(
            region,
          ),
        )

        val items: Vector[(String, UI)] =
          Vector.tabulate(2000)(i =>
            (i.toString, row(i, s"item number $i with some text")),
          )

        val before: Map[Loc, Content] = Map(rows -> Content.Many(items))
        val after: Map[Loc, Content]  =
          Map(
            rows -> Content.Many(
              items.updated(1000, ("1000", row(1000, "an edited row"))),
            ),
          )

        def results(n: Int): Vector[Result] =
            val envA  = scene(before, size, _ => Offset.zero)
            val envB  = scene(after, size, _ => Offset.zero)
            val envS  = scene(
              before,
              size,
              p => if p == viewport then Offset(0, 1) else Offset.zero,
            )
            val laidA = Layout.layoutAll(ui, envA)
            Vector(
              measure("list full", n)(() =>
                Paint(Layout.layoutAll(ui, envA), envA, full(size)),
              ),
              measure("list edit", n)(() =>
                step(laidA, ui, envB, Vector(rows), Vector.empty),
              ),
              measure("list scroll", n)(() =>
                step(laidA, ui, envS, Vector.empty, Vector(viewport)),
              ),
            )
        end results
    end ListScene

    /** A log of 100 lines in a viewport of 20 rows on 120x40; each step adds a
      * line at the end (200 steps).
      */
    class LogScene(using Frame):
        val size  = Size(120, 40)
        val lines = Loc.root.child(1).child(0).child(0)

        val ui: UI = div(
          h1("Log"),
          div.style(
            _.height(20.px).overflowY(_.auto).border(1.px, Style.Color.slate),
          )(
            ul(region),
          ),
        )

        def line(i: Int): (String, UI) =
          (
            i.toString,
            li(s"event #$i: request served by host-${i % 7} in ${i % 13} ms"),
          )

        /** One round: 200 steps from 100 lines; the time of each step. */
        private def round(): Vector[(Long, Long)] =
            val items0 = Vector.tabulate(100)(line)
            val laid0  =
              Layout.layoutAll(
                ui,
                scene(
                  Map(lines -> Content.Many(items0)),
                  size,
                  _ => Offset.zero,
                ),
              )
            (100 until 300)
              .foldLeft((laid0, items0, Vector.empty[(Long, Long)])) {
                case ((laid, items, acc), k) =>
                  val next = items :+ line(k)
                  val env  = scene(
                    Map(lines -> Content.Many(next)),
                    size,
                    _ => Offset.zero,
                  )
                  var out  = laid
                  val t    = once(() =>
                      val (l, plan) =
                        step(laid, ui, env, Vector(lines), Vector.empty)
                      out = l
                      plan,
                  )
                  (out, next, acc :+ t)
              }
              ._3
        end round

        def result(n: Int): Result =
            (1 to n).foreach(_ => sink = round())
            val runs = Vector.fill(n)(round()).flatten
            Result("log append", runs.map(_._1), runs.map(_._2))
    end LogScene
end PerfProbe
