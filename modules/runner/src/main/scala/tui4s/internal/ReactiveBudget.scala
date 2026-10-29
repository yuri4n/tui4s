package tui4s.internal

/** How many reactive regions keep a watcher, and which regions a frame reads. */
// cursor 2026-10-28: created: pure watcher budget and sampled-region rotation.
private[tui4s] object ReactiveBudget:
    /** Reactive children of one parent at this count and above are sampled. */
    // cursor 2026-10-28: created: keep ordinary screens on the watcher loop; cursor 2026-10-28: changed: watch two reactive regions; cursor 2026-10-28: changed: the cap is per parent.
    val watchedLimit: Int = 16

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

    /** One mounted region as the pull decision sees it. */
    // cursor 2026-10-28: created: path, kind and whether a watcher fiber exists.
    final case class View(path: Loc, kind: Kind, watched: Boolean)

    /** True when another reactive region can take a watcher fiber. */
    // cursor 2026-10-28: created: the mount asks before it forks.
    def watchAnother(watchedReactive: Int): Boolean =
      watchedReactive < watchedLimit

    /** True when a queued message is input. A background frame yields to it. */
    // cursor 2026-10-28: created: keys, pastes and resizes are input.
    def hasInput(backlog: Vector[Msg]): Boolean =
      backlog.exists {
        case Msg.Key(_, _) | Msg.Paste(_, _) | Msg.Resize(_, _) => true
        case _                                                  => false
      }

    /** Paths to re-read, parents first, and the next sample cursor.
      *
      * An input frame reads every watched reactive region, every bound region,
      * every `Foreach`, and the young, unarmed and focus-related regions. A
      * later frame also reads one slice of the sampled reactive regions.
      */
    // cursor 2026-10-28: created: keep the input frame off the sampled tail.
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
            case View(p, _, _) if young.contains(p) || unarmed.contains(p) =>
              p
            case View(p, Kind.Bound | Kind.Many, _)                        =>
              p
            case View(p, Kind.Reactive, true) if input                     => p
            case View(p, Kind.Reactive, _) if focuses(p, focus)            => p
          }
          .distinct
          .sortBy(_.depth)
        val sampled  = regions
          .collect { case View(p, Kind.Reactive, false) =>
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
