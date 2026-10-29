package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import kyo.*
import kyo.UI.Ast.*

/** A message to the runner fiber. Watchers, the input source and handlers only
  * ever talk to the runner through these.
  */
private[tui4s] enum Msg:
    // claude 2026-10-13: created (C3): a key, with the sequence number of its
    // Input frame
    case Key(ev: UI.KeyboardEvent, seq: Long)
    // claude 2026-10-13: created (C3, RC-14 part): a bracketed paste, one
    // event, with the sequence number of its Input frame
    case Paste(text: String, seq: Long)
    case Resize(size: Size, seq: Long)
    case Content(region: Loc, gen: Long, value: Any)
    // claude 2026-10-12: created the two cases below (K29, D-16): a watcher is
    // armed (its `nextWith` is about to be registered); the repair tick
    case Armed(region: Loc, gen: Long)
    case Repair
    // cursor 2026-10-28: created: an idle sample of regions that have no watcher.
    case Sample
    case Quit
    // codex 2026-10-23: created: a watcher invalidates its region without retaining an older signal value.
    case Changed(region: Loc, gen: Long)
end Msg

/** What drives a region. */
private[tui4s] enum Source:
    case One(signal: Signal[UI])
    // claude 2026-10-19: changed: the item type of the Foreach is a type
    // parameter, not Any (purity plan, P1: no cast at the mount)
    case Many[A](fe: Foreach[A, ?]) extends Source
    case Bound(ref: SignalRef[?])
    case Local
    // claude 2026-10-13: created (C4, the C2 cut back): a component node; `key`
    // is the state key of a `Component` or the identity of a hook component
    // claude 2026-10-13: changed (C4, review R2-4): the state key alone (the
    // path is the region's)
    case Comp(spec: ComponentSpec, key: String)
end Source

/** A focus seed of kyo-ui's `focusAuto`: the element at `at` took the focus
  * when it appeared. `back` is the element that had the focus before it, when
  * the element at `at` has `focusRestore(true)`: the focus goes back there when
  * that element leaves the screen.
  */
// claude 2026-10-19: created (L1b)
private[tui4s] final case class Seed(at: Loc, back: Option[Loc])

/** One rendered item of a `Foreach` region. */
private[tui4s] final case class ItemState(
    key: String,
    index: Int,
    value: Any,
    ui: UI,
)

/** A mounted region: its source, its generation (a remount gets a new one, so
  * late messages of the old watcher are ignored), what it shows now, and the
  * fiber that watches its signal.
  */
private[tui4s] final case class RegionState(
    path: Loc,
    source: Source,
    gen: Long,
    content: Content,
    items: Vector[ItemState],
    watcher: Option[Fiber[Any, Nothing]],
)

/** The runner's whole state. Immutable; one `World` per step of the runner
  * loop.
  *
  * `carets` holds the caret of each text input that a key moved or edited, as a
  * GRAPHEME index: 0 to the number of grapheme clusters of its text (what a
  * reader sees as one character), not a UTF-16 index. No entry is the end of
  * the text. `Paint` turns it into a cell column.
  */
// claude 2026-10-13: changed the Scaladoc (C3): carets are grapheme indexes
private[tui4s] final case class World(
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
    // claude 2026-10-13: created the two fields below (C4, the C2 cut back):
    // the state of each mounted `Component`, by its state key; the hook
    // components that wait for their first content
    // claude 2026-10-13: changed (C4, review R2-4): `comps` by the path and
    // the state key (a joined string can be the same for two paths)
    // claude 2026-10-19: changed: SignalRef[?], so a write needs no cast (P1)
    comps: Map[(Loc, String), SignalRef[?]],
    pending: Set[Loc],
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
    // claude 2026-10-19: created (P2, D-16 armkey): the regions whose watcher
    // has not armed yet (path -> gen); the mount adds an entry, `Msg.Armed`
    // takes it out
    unarmed: Map[Loc, Long] = Map.empty,
    // claude 2026-10-19: created the three fields below (L1a): the scroll
    // offset of each viewport (no entry is 0; `Scroll.kept` drops the entry
    // of a viewport that unmounted), the scroll target of the last frame, and
    // when this frame scrolls the target into view
    scroll: Map[Loc, Offset] = Map.empty,
    followed: Option[Loc] = None,
    reveal: Reveal = Reveal.OnMove,
    // claude 2026-10-19: created (L1a review): the viewports that a key
    // scrolled in this step; the frame lays them out in place
    scrolled: Set[Loc] = Set.empty,
    // claude 2026-10-19: created (L1b): the focus seeds of kyo-ui's
    // focusAuto, the newest first (Runner.fixFocus)
    seeds: List[Seed] = Nil,
    // claude 2026-10-19: created (L1b): the custom properties of the
    // stylesheet of the run
    vars: CssVars = CssVars.empty,
    // claude 2026-10-19: created (L1b): the warnings that the run logged
    // (Warnings.scan), each one once
    warned: Set[Warn] = Set.empty,
    // claude 2026-10-19: created (L1c review): the focused textarea that the
    // user edited, with its text before the first edit since it got the
    // focus; its blur runs onChange when the text changed (Dispatch.setFocus)
    edited: Option[(Loc, String)] = None,
    // claude 2026-10-19: created (PF-b, F9b): the plan that the canvas holds
    // (the plan of the last frame that wrote)
    lastPlan: Option[Plan] = None,
    // cursor 2026-10-28: created: the next index in the sorted sampled-region rotation.
    sampleFrom: Int = 0,
    // cursor 2026-10-28: created: reactive regions an input frame reads, with or without a watcher fiber.
    tracked: Set[Loc] = Set.empty,
):
    // claude 2026-10-19: changed: the terminal cursor shows the caret (L1c)
    def env: Scene =
      Scene(
        p => regions.get(p).map(_.content),
        focus,
        size,
        p => carets.getOrElse(p, Int.MaxValue),
        p => scroll.getOrElse(p, Offset.zero),
        vars,
        CaretLook.Cursor,
      )

    def gen: (Long, World) = (nextGen, copy(nextGen = nextGen + 1))

    // codex 2026-10-28: changed: compare exact path steps without vector equality or an unchanged copy.
    def markRegion(p: Loc): World =
      if dirtyRegions.exists(r => r.depth == p.depth && r.isAtOrUnder(p)) then
          this
      else copy(dirtyRegions = dirtyRegions :+ p)
    def markNode(p: Loc): World   = copy(dirtyNodes = dirtyNodes + p)

    /** The world after a key scrolled the viewport at `p` to `o`: the frame
      * lays out the viewport in place (its kids move), not its owner.
      */
    // claude 2026-10-19: created (L1a review)
    def scrollTo(p: Loc, o: Offset): World =
      copy(scroll = scroll.updated(p, o), scrolled = scrolled + p)

    /** The regions whose watcher has not armed yet: until the arm, the watcher
      * does not see a write, so an Input step verifies them. An entry of an old
      * generation does not count.
      */
    // claude 2026-10-19: created (P2): a pure function of the World, not a
    // ConcurrentHashMap of Mount
    def unarmedRegions: Set[Loc] =
      unarmed.collect {
        case (p, g) if regions.get(p).exists(_.gen == g) => p
      }.toSet

    /** The world after `Msg.Armed(p, g)`: the watcher of generation `g` at `p`
      * has armed. An arm of an old generation leaves a newer entry.
      */
    // claude 2026-10-19: created (P2)
    def armed(p: Loc, g: Long): World =
      if unarmed.get(p).contains(g) then copy(unarmed = unarmed - p) else this

    /** Reactive regions under `parent` that own a watcher fiber. */
    // cursor 2026-10-28: created: the mount spends the watcher budget per parent.
    def reactiveWatchersUnder(parent: Loc): Int =
      regions.values.count {
        case RegionState(p, Source.One(_), _, _, _, Some(_))
            if p.parent == parent =>
          true
        case _ => false
      }

    /** Reactive regions under `parent` that an input frame reads. */
    // cursor 2026-10-28: created: the input read stays when the watcher fiber does not.
    def reactiveTrackedUnder(parent: Loc): Int =
      tracked.count(p => p.parent == parent && regions.contains(p))

    /** True when at least one reactive region is sampled. */
    // cursor 2026-10-28: created: the idle sample wait runs only for this set.
    def hasSampled: Boolean =
      regions.values.exists {
        case RegionState(_, Source.One(_), _, _, _, None) => true
        case _                                            => false
      }
end World

private[tui4s] object World:
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
        // claude 2026-10-13: changed (C4): the init values of comps and pending
        Map.empty,
        Set.empty,
        Vector.empty,
        0,
        false,
      )
end World
