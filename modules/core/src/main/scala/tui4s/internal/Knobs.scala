package tui4s.internal

import kyo.*

/** Test seams of the runner. Not public API (`private[tui4s]`): a test sets
  * them with `Knobs.let` around `Headless.start`; a real run has the defaults.
  *
  * @param seam
  *   how long a watcher sleeps after its n-th step (n = 0 is the arm, after the
  *   mount read the first value; n = 1, 2, ... are the posts of later values).
  *   The sleep holds open the window between the read of `current` and the
  *   registration of `nextWith`, in which a write is missed by the watcher (the
  *   lost wakeup of Kyo's `Signal`). The R2 tests use it.
  * @param repairInterval
  *   how often the repair tick re-reads `current` of every region (the same
  *   role as Kyo's `Signal.defaultRepairInterval`, 1 s)
  */
// claude 2026-10-12: created (D-16, K29)
private[tui4s] final case class Knobs(
    seam: Int => Duration = _ => Duration.Zero,
    repairInterval: Duration = Signal.defaultRepairInterval,
)

private[tui4s] object Knobs:
    val default: Knobs = Knobs()

    private val local: Local[Knobs] = Local.init(default)

    /** Runs `v` with the knobs `k`; `Headless.start` reads them. */
    def let[A, S](k: Knobs)(v: A < S)(using Frame): A < S = local.let(k)(v)

    def get(using Frame): Knobs < Any = local.get
end Knobs
