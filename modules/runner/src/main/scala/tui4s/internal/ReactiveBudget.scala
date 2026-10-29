package tui4s.internal

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
