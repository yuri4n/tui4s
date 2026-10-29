package tui4s.internal

import kyo.UI
import kyo.UI.Ast.*

/** How many reactive regions keep a watcher, and which regions a frame reads. */
// cursor 2026-10-28: created: pure watcher budget and sampled-region rotation.
private[tui4s] object ReactiveBudget:
    /** Reactive children of one parent read on an input frame. */
    // cursor 2026-10-28: created: keep ordinary screens on the watcher loop; cursor 2026-10-28: changed: watch two reactive regions; cursor 2026-10-28: changed: the cap is per parent; cursor 2026-10-28: changed: this cap is the input read, not the watcher fiber.
    val trackedLimit: Int = 16

    /** A parent with more reactive children than this forks no reactive
      * watcher.
      */
    // cursor 2026-10-28: created: a crowded parent does not run one race per row.
    val watcherLimit: Int = 2

    /** Sampled regions read on one non-input frame. */
    // cursor 2026-10-28: created: bound one sample slice; cursor 2026-10-28: changed: keep one slice small enough for the 250 ms gate.
    val sampleBatch: Int = 8

    /** Idle wait before a sample frame, when sampled regions exist. */
    // cursor 2026-10-28: created: one local-settlement interval.
    val sampleNanos: Long = 16_000_000L

    /** The kind of source a pull decision needs. The signal itself stays in the
      * runner.
      */
    // cursor 2026-10-28: created: a view with no Kyo signal.
    enum Kind derives CanEqual:
        case Reactive, Bound, Many, Other

    /** One mounted region as the pull decision sees it.
      *
      * `tracked` regions are read on an input frame. `fiber` regions already
      * have a watcher. A crowded parent tracks rows and leaves `fiber` false,
      * so a background frame samples them.
      */
    // cursor 2026-10-28: created: path, kind and whether a watcher fiber exists; cursor 2026-10-28: changed: split the input read from the watcher fiber.
    final case class View(
        path: Loc,
        kind: Kind,
        tracked: Boolean,
        fiber: Boolean,
    )

    /** True when another reactive child of this parent is read on an input
      * frame.
      */
    // cursor 2026-10-28: created: the mount asks before it forks; cursor 2026-10-28: changed: the question is the input read.
    def trackAnother(tracked: Int): Boolean =
      tracked < trackedLimit

    /** True when this parent is small enough to fork a watcher per reactive
      * child.
      */
    // cursor 2026-10-28: created: three or more reactive children are sampled.
    def forkWatchers(reactiveChildren: Int): Boolean =
      reactiveChildren <= watcherLimit

    /** Reactive texts under `ui`, with no budget boundary. */
    // cursor 2026-10-28: created: a span around a signal is not a separate parent.
    private def subtreeSlots(ui: UI): Int = ui match
        case _: Reactive[?] => 1
        case el: Element    => el.children.toSeq.map(subtreeSlots).sum
        case f: Fragment[?] => f.children.toSeq.map(subtreeSlots).sum
        case _              => 0

    /** True when `ui` is text, one signal, or a wrapper of those. */
    // cursor 2026-10-28: created: a row span rolls its signal up to the list.
    private def transparent(ui: UI): Boolean = ui match
        case _: Reactive[?] | _: Text => true
        case el: Element              =>
          subtreeSlots(el) <= 1 && el.children.forall(transparent)
        case f: Fragment[?]           => f.children.forall(transparent)
        case _                        => false

    /** True when `el` wraps at most one signal. Its parent owns that signal. */
    // cursor 2026-10-28: created: the fanout div owns each row signal.
    def thin(el: Element): Boolean =
      subtreeSlots(el) <= 1 && el.children.forall(transparent)

    /** Signals `el` owns. A nested element with two or more signals keeps its
      * own count.
      */
    // cursor 2026-10-28: created: count span-wrapped signals on the containing element.
    def slots(el: Element): Int =
      el.children.toSeq.map(contribute).sum

    /** Signals a child adds to its parent. A list inside the child adds none. */
    // cursor 2026-10-28: created: a crowded child list is its own parent.
    private def contribute(ui: UI): Int = ui match
        case _: Reactive[?]          => 1
        case el: Element if thin(el) => subtreeSlots(el)
        case f: Fragment[?]          => f.children.toSeq.map(contribute).sum
        case _                       => 0

    /** True when `ui` is one signal, or a wrapper of one signal. */
    // cursor 2026-10-28: created: a row is one signal, not a widget.
    private def signalRow(ui: UI): Boolean = ui match
        case _: Reactive[?] => true
        case el: Element    => thin(el) && subtreeSlots(el) == 1
        case _              => false

    /** The size of a list whose every child is one signal row. A mixed element
      * is 0.
      */
    // cursor 2026-10-28: created: only a homogeneous row list can be crowded.
    def rowList(el: Element): Int =
        val kids = el.children.toSeq.flatMap {
          case f: Fragment[?] => f.children.toSeq
          case other          => Seq(other)
        }
        if kids.nonEmpty && kids.forall(signalRow) then
            kids.map(subtreeSlots).sum
        else 0
    end rowList

    /** True when a queued message is input. A background frame yields to it. */
    // cursor 2026-10-28: created: keys, pastes and resizes are input.
    def hasInput(backlog: Vector[Msg]): Boolean =
      backlog.exists {
        case Msg.Key(_, _) | Msg.Paste(_, _) | Msg.Resize(_, _) => true
        case _                                                  => false
      }

    /** Paths to re-read, parents first, and the next sample cursor.
      *
      * An input frame reads every tracked reactive region, every bound region,
      * every `Foreach`, and the young, unarmed and focus-related regions. A
      * later frame also reads one slice of the reactive regions that have no
      * watcher fiber.
      */
    // cursor 2026-10-28: created: keep the input frame off the sampled tail; cursor 2026-10-28: changed: tracked rows stay on the input frame without a fiber.
    def pulls(
        regions: Vector[View],
        young: Set[Loc],
        unarmed: Set[Loc],
        focus: Option[Loc],
        input: Boolean,
        sampleFrom: Int,
    ): (Vector[Loc], Int) =
        val required = regions
          .collect {
            case View(p, _, _, _) if young.contains(p) || unarmed.contains(p) =>
              p
            case View(p, Kind.Bound | Kind.Many, _, _)                        =>
              p
            case View(p, Kind.Reactive, true, _) if input                     => p
            case View(p, Kind.Reactive, _, _) if focuses(p, focus)            => p
          }
          .distinct
          .sortBy(_.depth)
        val sampled  = regions
          .collect { case View(p, Kind.Reactive, _, false) =>
            p
          }
          .sortBy(_.showLoc)
        if input || sampled.isEmpty then (required, sampleFrom)
        else
            val count = sampleBatch.min(sampled.length)
            val start = Math.floorMod(sampleFrom, sampled.length)
            val batch = Vector.tabulate(count)(i =>
              sampled(Math.floorMod(start + i, sampled.length)),
            )
            (
              (required ++ batch).distinct.sortBy(_.depth),
              Math.floorMod(start + count, sampled.length),
            )
        end if
    end pulls

    /** True when `path` contains the focus or the focus contains `path`. */
    // cursor 2026-10-28: created: the focused field's reactive ancestors stay on the input frame.
    private def focuses(path: Loc, focus: Option[Loc]): Boolean =
      focus.exists(f => f.isAtOrUnder(path) || path.isAtOrUnder(f))
end ReactiveBudget
