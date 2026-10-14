package tui4s.internal

import kyo.*
import kyo.UI.Ast.*

/** A message to the runner fiber. Watchers, the input source and handlers only
  * ever talk to the runner through these.
  */
enum Msg:
    case Resize(size: Size, seq: Long)
    case Content(region: Loc, gen: Long, value: Any)
    // claude 2026-10-12: created the two cases below (K29, D-16): a watcher is
    // armed (its `nextWith` is about to be registered); the repair tick
    case Armed(region: Loc, gen: Long)
    case Repair
    case Quit
end Msg

/** What drives a region. */
enum Source:
    case One(signal: Signal[UI])
    case Many(fe: Foreach[Any, UI])
    case Bound(ref: SignalRef[?])
    case Local

/** One rendered item of a `Foreach` region. */
final case class ItemState(key: String, index: Int, value: Any, ui: UI)

/** A mounted region: its source, its generation (a remount gets a new one, so
  * late messages of the old watcher are ignored), what it shows now, and the
  * fiber that watches its signal.
  */
final case class RegionState(
    path: Loc,
    source: Source,
    gen: Long,
    content: Content,
    items: Vector[ItemState],
    watcher: Option[Fiber[Any, Nothing]],
)

/** The runner's whole state. Immutable; one `World` per step of the runner
  * loop.
  */
final case class World(
    root: UI,
    regions: Map[Loc, RegionState],
    laid: Laid,
    focus: Option[Loc],
    carets: Map[Loc, Int],
    size: Size,
    nextGen: Long,
    dirtyRegions: Vector[Loc],
    dirtyNodes: Set[Loc],
    full: Boolean,
    backlog: Vector[Msg],
    updates: Int,
    quit: Boolean,
    // claude 2026-10-12: created (K29): values that the runner found by a
    // re-read of `current` in this step (the repair tick)
    repaired: Int = 0,
    // claude 2026-10-12: created the two fields below (D-16, armkey): the
    // regions mounted in this step and in the step before; the verify pass
    // re-reads them after the settle
    youngNow: Set[Loc] = Set.empty,
    youngPrev: Set[Loc] = Set.empty,
    // claude 2026-10-12: created (D-17 c, RR O6): settles of this step that
    // ran out of `max`
    settleTimeouts: Int = 0,
):
    def env: Scene =
      Scene(
        p => regions.get(p).map(_.content),
        focus,
        size,
        p => carets.getOrElse(p, Int.MaxValue),
      )

    def gen: (Long, World) = (nextGen, copy(nextGen = nextGen + 1))

    def markRegion(p: Loc): World = copy(dirtyRegions =
      if dirtyRegions.contains(p) then dirtyRegions else dirtyRegions :+ p,
    )
    def markNode(p: Loc): World   = copy(dirtyNodes = dirtyNodes + p)
end World

object World:
    def init(root: UI, size: Size): World =
      World(
        root,
        Map.empty,
        Laid.empty,
        None,
        Map.empty,
        size,
        1L,
        Vector.empty,
        Set.empty,
        true,
        Vector.empty,
        0,
        false,
      )
end World
