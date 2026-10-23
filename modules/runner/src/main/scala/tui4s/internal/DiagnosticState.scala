package tui4s.internal

// codex 2026-10-22: created: bounded timing data with no input contents
private[tui4s] final case class DiagnosticInput(
    received: Long,
    seen: Option[Long] = None,
    dispatched: Option[Long] = None,
)

/** Completed input timings in nanoseconds. `afterInboxRemoval` includes the
  * wait in the deferred backlog and the work before dispatch starts.
  */
// codex 2026-10-22: created: one completed input, all durations in nanoseconds
private[tui4s] final case class DiagnosticSample(
    seq: Long,
    receiveToFrame: Long,
    receiveToDispatch: Long,
    afterInboxRemoval: Long,
    handle: Long,
    settle: Long,
    render: Long,
)

// codex 2026-10-22: created: fixed runner phases, without app names or values
private[tui4s] enum DiagnosticPhase:
    case Starting, Mounting, Waiting, Handling, Pending, Settling, Verifying,
      Rendering, Stopped

// codex 2026-10-22: created: pure bounded state shared by the diagnostic edges
private[tui4s] final case class DiagnosticState(
    started: Long,
    phase: DiagnosticPhase,
    phaseSince: Long,
    progress: Long,
    currentSeq: Long = 0L,
    received: Long = 0L,
    completed: Long = 0L,
    sampledCompleted: Long = 0L,
    lastCompletedSeq: Long = 0L,
    untracked: Long = 0L,
    inputs: Map[Long, DiagnosticInput] = Map.empty,
    recent: Vector[DiagnosticSample] = Vector.empty,
    backlog: Int = 0,
    backlogMax: Int = 0,
    pending: Int = 0,
    contents: Long = 0L,
    armed: Long = 0L,
    repairs: Long = 0L,
    drains: Long = 0L,
    nonemptyDrains: Long = 0L,
    busyProbes: Long = 0L,
    quietProbes: Long = 0L,
    timeouts: Long = 0L,
    frames: Long = 0L,
    lastFrame: Option[Long] = None,
    offered: Long = 0L,
    accepted: Long = 0L,
    batchStarted: Option[Long] = None,
    batchCompleted: Option[Long] = None,
):
    // codex 2026-10-22: created: repeated observations do not reset phase age
    def at(next: DiagnosticPhase, now: Long): DiagnosticState =
      copy(
        phase = next,
        phaseSince = if phase == next then phaseSince else now,
        progress = now,
      )

    // codex 2026-10-22: created: retain the first blocked inputs within the cap
    def receive(seq: Long, now: Long): DiagnosticState =
      if inputs.size >= DiagnosticState.InputLimit then
          copy(received = received + 1, untracked = untracked + 1)
      else
          copy(
            received = received + 1,
            inputs = inputs.updated(seq, DiagnosticInput(now)),
          )

    // codex 2026-10-22: created: preserve the first inbox removal through backlog
    def see(seq: Long, now: Long): DiagnosticState =
      copy(inputs =
        inputs
          .get(seq)
          .fold(inputs)(i =>
            inputs.updated(seq, i.copy(seen = i.seen.orElse(Some(now)))),
          ),
      )

    // codex 2026-10-22: created: dispatch starts after inbox or backlog removal
    def dispatch(seq: Long, now: Long): DiagnosticState =
      see(seq, now)
        .at(DiagnosticPhase.Handling, now)
        .copy(
          currentSeq = seq,
          inputs = inputs
            .get(seq)
            .fold(inputs)(i =>
              inputs.updated(
                seq,
                i.copy(seen = i.seen.orElse(Some(now)), dispatched = Some(now)),
              ),
            ),
        )

    // codex 2026-10-22: created: bounded recent samples; frame counts include background
    def frame(
        seq: Long,
        now: Long,
        handle: Long,
        settle: Long,
        render: Long,
    ): DiagnosticState =
        val sample = inputs
          .get(seq)
          .flatMap(i =>
            i.dispatched.map(d =>
              DiagnosticSample(
                seq,
                now - i.received,
                d - i.received,
                i.seen.fold(0L)(d - _),
                handle,
                settle,
                render,
              ),
            ),
          )
        copy(
          completed = completed + (if seq > 0 then 1L else 0L),
          sampledCompleted =
            sampledCompleted + (if sample.nonEmpty then 1L else 0L),
          lastCompletedSeq = if seq > 0 then seq else lastCompletedSeq,
          inputs = inputs - seq,
          recent = sample.fold(recent)(s =>
            (recent :+ s).takeRight(DiagnosticState.SampleLimit),
          ),
          frames = frames + 1,
          lastFrame = Some(now),
          progress = now,
        )
    end frame
end DiagnosticState

// codex 2026-10-22: created: fixed memory limits, independent of app traffic
private[tui4s] object DiagnosticState:
    val InputLimit  = 1024
    val SampleLimit = 64

    // codex 2026-10-22: created: deterministic initial state for live use and tests
    def initial(now: Long): DiagnosticState =
      DiagnosticState(now, DiagnosticPhase.Starting, now, now)
