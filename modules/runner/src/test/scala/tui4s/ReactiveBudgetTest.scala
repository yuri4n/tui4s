package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.*

/** Watcher admission and the per-frame pull set. */
// cursor 2026-10-28: created: the reactive budget stays pure and bounded.
class ReactiveBudgetTest extends Test[Any]:

    private def view(
        path: Loc,
        kind: ReactiveBudget.Kind,
        tracked: Boolean,
        fiber: Boolean = false,
    ): ReactiveBudget.View =
      ReactiveBudget.View(path, kind, tracked, fiber)

    private val bound = view(Loc.root, ReactiveBudget.Kind.Bound, true)
    private val many  =
      view(Loc.root.child(1), ReactiveBudget.Kind.Many, true)

    // cursor 2026-10-28: created: the 33rd reactive region is sampled; cursor 2026-10-28: changed: watch two reactive regions; cursor 2026-10-28: changed: split the input read from the watcher fiber.
    "a reactive region past the input budget is sampled, and a crowded parent forks no watcher" in {
      assert(ReactiveBudget.trackAnother(0))
      assert(ReactiveBudget.trackAnother(ReactiveBudget.trackedLimit - 1))
      assert(ReactiveBudget.trackAnother(ReactiveBudget.trackedLimit) == false)
      assert(ReactiveBudget.forkWatchers(ReactiveBudget.watcherLimit))
      assert(
        ReactiveBudget.forkWatchers(ReactiveBudget.watcherLimit + 1) == false,
      )
    }

    // cursor 2026-10-28: created: input reads watched regions and leaves the sampled tail for later.
    "an input frame skips the sampled tail and a later frame rotates it" in {
      val watched           = Vector.tabulate(2)(i =>
        view(
          Loc.root.child(i + 2),
          ReactiveBudget.Kind.Reactive,
          tracked = true,
          fiber = true,
        ),
      )
      val sampled           = Vector.tabulate(ReactiveBudget.sampleBatch + 1)(i =>
        view(
          Loc.root.child(i + 10),
          ReactiveBudget.Kind.Reactive,
          false,
        ),
      )
      val regions           = (bound +: many +: watched) ++ sampled
      val ordered           = sampled.map(_.path).sortBy(_.showLoc)
      val (input, cursor)   =
        ReactiveBudget.pulls(
          regions,
          Set(Loc.root.child(2)),
          Set(Loc.root.child(3)),
          None,
          input = true,
          sampleFrom = 0,
        )
      val sampledPaths      = ordered.toSet
      assert(cursor == 0)
      assert(input.contains(bound.path))
      assert(input.contains(many.path))
      assert(watched.forall(v => input.contains(v.path)))
      assert(input.forall(p => !sampledPaths.contains(p)))
      val (next, advanced)  =
        ReactiveBudget.pulls(
          regions,
          Set.empty,
          Set.empty,
          None,
          input = false,
          sampleFrom = 0,
        )
      val width             = ordered.length
      val first             = ordered.take(ReactiveBudget.sampleBatch)
      assert(advanced == Math.floorMod(ReactiveBudget.sampleBatch, width))
      assert(first.forall(next.contains))
      assert(next.contains(ordered.last) == false)
      val (rotated, second) =
        ReactiveBudget.pulls(
          sampled,
          Set.empty,
          Set.empty,
          None,
          input = false,
          sampleFrom = advanced,
        )
      assert(rotated.contains(ordered.last))
      assert(
        second == Math.floorMod(advanced + ReactiveBudget.sampleBatch, width),
      )
    }

    // cursor 2026-10-28: created: the focus path is read even when its region is sampled.
    "a sampled region that holds the focus is read on the input frame" in {
      val path            = Loc.root.child(4)
      val focus           = path.content.child(0)
      val (paths, cursor) =
        ReactiveBudget.pulls(
          Vector(view(path, ReactiveBudget.Kind.Reactive, false)),
          Set.empty,
          Set.empty,
          Some(focus),
          input = true,
          sampleFrom = 3,
        )
      assert(paths == Vector(path))
      assert(cursor == 3)
    }

    // cursor 2026-10-28: created: queued input is visible to the frame decision.
    "queued keys, pastes and resizes count as input" in {
      val key = KeyboardEvent(Keyboard.Char('x'), Modifiers.none, Absent)
      assert(
        ReactiveBudget.hasInput(Vector(Msg.Changed(Loc.root, 1L))) == false,
      )
      assert(ReactiveBudget.hasInput(Vector(Msg.Key(key, 1L))))
      assert(ReactiveBudget.hasInput(Vector(Msg.Paste("x", 2L))))
      assert(ReactiveBudget.hasInput(Vector(Msg.Resize(Size(2, 2), 3L))))
    }

    // cursor 2026-10-28: created: the bound field and two status lines stay watched.
    "a settlement-shaped tree watches the bound field and two status lines" in {
      val prompt          =
        view(Loc.root.child(0), ReactiveBudget.Kind.Bound, true)
      val statuses        = Vector.tabulate(2)(i =>
        view(
          Loc.root.child(i + 1),
          ReactiveBudget.Kind.Reactive,
          tracked = true,
          fiber = true,
        ),
      )
      val tasks           = Vector.tabulate(4)(i =>
        view(
          Loc.root.child(i + 3),
          ReactiveBudget.Kind.Reactive,
          false,
        ),
      )
      val regions         = Vector(prompt) ++ statuses ++ tasks
      assert(ReactiveBudget.trackAnother(0))
      assert(ReactiveBudget.trackAnother(ReactiveBudget.trackedLimit - 1))
      assert(ReactiveBudget.trackAnother(ReactiveBudget.trackedLimit) == false)
      assert(ReactiveBudget.forkWatchers(statuses.length))
      assert(ReactiveBudget.forkWatchers(tasks.length) == false)
      val (input, held)   =
        ReactiveBudget.pulls(
          regions,
          Set.empty,
          Set.empty,
          None,
          input = true,
          sampleFrom = 0,
        )
      val sampled         = tasks.map(_.path).toSet
      assert(held == 0)
      assert(input.contains(prompt.path))
      assert(statuses.forall(line => input.contains(line.path)))
      assert(input.forall(path => !sampled.contains(path)))
      val (background, _) =
        ReactiveBudget.pulls(
          regions,
          Set.empty,
          Set.empty,
          None,
          input = false,
          sampleFrom = 0,
        )
      assert(tasks.forall(row => background.contains(row.path)))
    }

    // cursor 2026-10-28: created: a span around a signal counts for the element that contains the span.
    "a span-wrapped signal counts for the element that contains the span" in {
      val line    = Signal.initConst("x")
      val rows    = div(Vector.tabulate(4)(_ => span(line))*)
      val labeled = div(Vector.tabulate(4)(_ => span("Task ", line))*)
      val status  = div(span(line), span(line))
      val screen  = div(span(line), span(line), rows)
      assert(ReactiveBudget.thin(rows) == false)
      assert(ReactiveBudget.slots(rows) == 4)
      assert(ReactiveBudget.slots(labeled) == 4)
      assert(ReactiveBudget.slots(status) == 2)
      assert(ReactiveBudget.thin(status) == false)
      assert(ReactiveBudget.slots(screen) == 2)
      assert(ReactiveBudget.rowList(rows) == 4)
      assert(ReactiveBudget.rowList(screen) == 0)
      assert(ReactiveBudget.forkWatchers(ReactiveBudget.slots(rows)) == false)
      assert(ReactiveBudget.forkWatchers(ReactiveBudget.slots(screen)))
      assert(ReactiveBudget.forkWatchers(ReactiveBudget.slots(status)))
    }

    // cursor 2026-10-28: created: the fanout list forks no watcher and tracks 16 rows; cursor 2026-10-28: changed: a list of 4 spans keeps a watcher on each span.
    "a crowded list of span-wrapped signals forks no watcher" in {
      val line = Signal.initConst("row")
      val rows = div(Vector.tabulate(20)(_ => span(line))*)
      val ui   = div(span(line), span(line), rows, span(line))
      Scope.run {
        for
            inbox <- Channel.init[Msg](32)
            gauge <- FiberGauge.init
            world <- new Mount(inbox, gauge).walk(
                       ui,
                       Loc.root,
                       World.init(ui, Size(40, 12)),
                       Map.empty,
                     )
            live  <- gauge.live
            _     <- gauge.close
        yield
            val watchers = world.regions.values.count(_.watcher.nonEmpty)
            assert(watchers == 3)
            assert(live == 3)
            assert(world.tracked.size == 3 + ReactiveBudget.trackedLimit)
            assert(world.hasSampled)
      }
    }

    // cursor 2026-10-28: created: four span-wrapped signals stay on the watcher loop.
    "a short list of span-wrapped signals keeps a watcher each" in {
      val line = Signal.initConst("row")
      val ui   = div(Vector.tabulate(4)(_ => span(line))*)
      Scope.run {
        for
            inbox <- Channel.init[Msg](32)
            gauge <- FiberGauge.init
            world <- new Mount(inbox, gauge).walk(
                       ui,
                       Loc.root,
                       World.init(ui, Size(40, 12)),
                       Map.empty,
                     )
            live  <- gauge.live
            _     <- gauge.close
        yield
            assert(world.regions.values.count(_.watcher.nonEmpty) == 4)
            assert(live == 4)
            assert(world.hasSampled == false)
      }
    }
end ReactiveBudgetTest
