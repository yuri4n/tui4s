package tui4s

import kyo.test.*
import tui4s.internal.{DiagnosticPhase, DiagnosticState}

/** The timing state keeps queue delay and reports gaps in its sample coverage. */
// codex 2026-10-22: created: deterministic checks of bounded diagnostic state
class DiagnosticStateTest extends Test[Any]:

    // codex 2026-10-22: created: receipt starts latency before dispatch or paint
    "input latency includes time in the inbox and backlog" in {
      val done = DiagnosticState
        .initial(0L)
        .receive(1L, 100L)
        .see(1L, 200L)
        .see(1L, 300L)
        .dispatch(1L, 500L)
        .frame(1L, 800L, handle = 40L, settle = 200L, render = 60L)

      assert(done.recent.size == 1)
      val sample = done.recent.head
      assert(sample.seq == 1L)
      assert(sample.receiveToFrame == 700L)
      assert(sample.receiveToDispatch == 400L)
      assert(sample.afterInboxRemoval == 300L)
      assert(
        sample.handle == 40L && sample.settle == 200L && sample.render == 60L,
      )
      assert(done.inputs.isEmpty)
      assert(done.received == 1L && done.completed == 1L)
      assert(done.sampledCompleted == 1L && done.lastCompletedSeq == 1L)
      assert(done.frames == 1L && done.lastFrame.contains(800L))
    }

    // codex 2026-10-22: created: direct dispatch has no deferred backlog interval
    "direct dispatch supports negative monotonic timestamps" in {
      val dispatched = DiagnosticState
        .initial(-200L)
        .receive(1L, -100L)
        .dispatch(1L, -50L)
      val done       = dispatched.frame(1L, 0L, 10L, 30L, 10L)

      assert(dispatched.inputs(1L).seen.contains(-50L))
      assert(dispatched.inputs(1L).dispatched.contains(-50L))
      assert(done.recent.head.receiveToFrame == 100L)
      assert(done.recent.head.receiveToDispatch == 50L)
      assert(done.recent.head.afterInboxRemoval == 0L)
    }

    // codex 2026-10-22: created: overload preserves blocked inputs and exposes missing samples
    "a full timing buffer retains blocked inputs and counts uncovered arrivals" in {
      val limit            = DiagnosticState.InputLimit
      val full             = (1 to limit).foldLeft(DiagnosticState.initial(0L))((s, n) =>
        s.receive(n.toLong, n.toLong),
      )
      val missing          = limit.toLong + 1L
      val over             = full.receive(missing, 2000L)
      val completedMissing = over
        .see(missing, 2100L)
        .dispatch(missing, 2200L)
        .frame(missing, 2300L, 20L, 60L, 20L)
      val freed            = completedMissing
        .dispatch(1L, 2400L)
        .frame(1L, 2500L, 20L, 60L, 20L)
      val resumed          = freed.receive(missing + 1L, 2600L)

      assert(limit == 1024)
      assert(over.inputs.size == limit)
      assert(over.inputs(1L).received == 1L)
      assert(over.inputs(limit.toLong).received == limit.toLong)
      assert(over.received == limit.toLong + 1L && over.untracked == 1L)
      assert(!over.inputs.contains(missing))
      assert(completedMissing.recent.isEmpty)
      assert(completedMissing.completed == 1L)
      assert(completedMissing.sampledCompleted == 0L)
      assert(completedMissing.lastCompletedSeq == missing)
      assert(completedMissing.inputs.size == limit)
      assert(resumed.inputs.size == limit)
      assert(resumed.inputs.contains(missing + 1L))
      assert(!resumed.inputs.contains(1L))
      assert(resumed.untracked == 1L)
      assert(resumed.recent.map(_.seq) == Vector(1L))
    }

    // codex 2026-10-22: created: missing timestamps never become invented latency samples
    "an unobserved input frame counts completion without a latency sample" in {
      val noDispatch = DiagnosticState
        .initial(0L)
        .receive(1L, 10L)
        .frame(1L, 20L, 0L, 0L, 0L)
      val unknown    = noDispatch
        .dispatch(2L, 30L)
        .frame(2L, 40L, 0L, 0L, 0L)
      val background = unknown.frame(0L, 50L, 0L, 0L, 0L)

      assert(noDispatch.recent.isEmpty && noDispatch.inputs.isEmpty)
      assert(unknown.recent.isEmpty && unknown.inputs.isEmpty)
      assert(unknown.received == 1L && unknown.completed == 2L)
      assert(unknown.sampledCompleted == 0L && unknown.lastCompletedSeq == 2L)
      assert(background.completed == 2L)
      assert(background.lastCompletedSeq == 2L)
      assert(background.frames == 3L && background.lastFrame.contains(50L))
    }

    // codex 2026-10-22: created: completion history stays bounded without losing totals
    "only the latest completion samples remain in the fixed history" in {
      val count = DiagnosticState.SampleLimit + 5
      val done  = (1 to count).foldLeft(DiagnosticState.initial(0L)) { (s, n) =>
        val seq      = n.toLong
        val received = seq * 100L
        s.receive(seq, received)
          .dispatch(seq, received + 10L)
          .frame(seq, received + 30L, 5L, 10L, 5L)
      }

      assert(DiagnosticState.SampleLimit == 64)
      assert(done.recent.size == 64)
      assert(done.recent.map(_.seq) == (6 to count).map(_.toLong).toVector)
      assert(done.received == count.toLong && done.completed == count.toLong)
      assert(done.frames == count.toLong)
      assert(
        done.sampledCompleted == count.toLong && done.lastCompletedSeq == count.toLong,
      )
      assert(done.untracked == 0L && done.inputs.isEmpty)
    }

    // codex 2026-10-22: created: progress inside a phase does not hide a long wait
    "phase age remains anchored while the same phase makes progress" in {
      val settling  = DiagnosticState
        .initial(100L)
        .at(DiagnosticPhase.Settling, 200L)
        .at(DiagnosticPhase.Settling, 300L)
      val verifying = settling.at(DiagnosticPhase.Verifying, 500L)

      assert(settling.phase == DiagnosticPhase.Settling)
      assert(settling.phaseSince == 200L && settling.progress == 300L)
      assert(verifying.phase == DiagnosticPhase.Verifying)
      assert(verifying.phaseSince == 500L && verifying.progress == 500L)
    }
end DiagnosticStateTest
