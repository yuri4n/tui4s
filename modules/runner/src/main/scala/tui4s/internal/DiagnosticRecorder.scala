// scalafix:off DisableSyntax
// EDGE: diagnostic clock, atomic state, file snapshots and daemon observer
package tui4s.internal

import kyo.*
import tui4s.spi.Settle

import java.nio.charset.StandardCharsets
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  Path,
  StandardCopyOption,
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.util.control.NonFatal

/** An optional observer. It never writes a file on the runner or input thread. */
// codex 2026-10-22: created: content-free diagnostic hooks with a disabled fast path
private[tui4s] sealed abstract class DiagnosticRecorder(val enabled: Boolean):
    protected def update(f: DiagnosticState => DiagnosticState): Unit

    // codex 2026-10-22: created: record terminal receipt without storing the event
    final def received(seq: Long): Unit =
      if enabled then
          val now = java.lang.System.nanoTime()
          update(_.receive(seq, now))

    // codex 2026-10-22: created: whole-batch admission is separate from per-key timing
    final def offered(count: Int): Unit =
      if enabled then
          val now = java.lang.System.nanoTime()
          update(s =>
            s.copy(
              offered = s.offered + count,
              batchStarted = Some(now),
              batchCompleted = None,
            ),
          )

    // codex 2026-10-22: created: a failed or closed batch is not reported as admitted
    final def accepted(count: Int, success: Boolean): Unit =
      if enabled then
          val now = java.lang.System.nanoTime()
          update(s =>
            s.copy(
              accepted = s.accepted + (if success then count else 0),
              batchCompleted = Some(now),
            ),
          )

    // codex 2026-10-22: created: count dequeued notifications, including a live settle; codex 2026-10-23: changed: include current-state change notices; cursor 2026-10-28: changed: a sample tick adds no counts.
    final def seen(msg: Msg): Unit =
      if enabled then
          val now = java.lang.System.nanoTime()
          update(s =>
            msg match
                case Msg.Key(_, seq)                 => s.see(seq, now)
                case Msg.Paste(_, seq)               => s.see(seq, now)
                case Msg.Resize(_, seq)              => s.see(seq, now)
                case _: Msg.Content | _: Msg.Changed =>
                  s.copy(contents = s.contents + 1)
                case _: Msg.Armed                    => s.copy(armed = s.armed + 1)
                case Msg.Repair                      => s.copy(repairs = s.repairs + 1)
                // cursor 2026-10-28: created: a sample tick carries no application contents.
                case Msg.Sample | Msg.Quit           => s,
          )

    // codex 2026-10-22: created: dispatch time includes time deferred in World.backlog
    final def dispatched(msg: Msg): Unit =
      if enabled then
          val now = java.lang.System.nanoTime()
          val seq = msg match
              case Msg.Key(_, n)    => n
              case Msg.Paste(_, n)  => n
              case Msg.Resize(_, n) => n
              case _                => 0L
          update(_.dispatch(seq, now))

    // codex 2026-10-22: created: record phase and pressure without changing runner decisions
    final def phase(next: DiagnosticPhase, backlog: Int, pending: Int): Unit =
      if enabled then
          val now = java.lang.System.nanoTime()
          update(s =>
            s.at(next, now)
              .copy(
                backlog = backlog,
                backlogMax = s.backlogMax.max(backlog),
                pending = pending,
              ),
          )

    // codex 2026-10-22: created: record the existing probe result, with no extra scheduler scan
    final def drained(count: Int, idle: Option[Boolean]): Unit =
      if enabled then
          update(s =>
            s.copy(
              drains = s.drains + 1,
              nonemptyDrains =
                s.nonemptyDrains + (if count > 0 then 1L else 0L),
              busyProbes =
                s.busyProbes + (if idle.contains(false) then 1L else 0L),
              quietProbes =
                s.quietProbes + (if idle.contains(true) then 1L else 0L),
            ),
          )

    // codex 2026-10-22: created: count actual timeout decisions, without enforcing a new deadline
    final def timedOut(): Unit =
      if enabled then update(s => s.copy(timeouts = s.timeouts + 1))

    // codex 2026-10-22: created: completion uses the runner's existing clock reading
    final def frame(
        seq: Long,
        now: Long,
        handle: Long,
        settle: Long,
        render: Long,
    ): Unit =
      if enabled then update(_.frame(seq, now, handle, settle, render))

    // codex 2026-10-22: created: stop frame timing before warning logging or stats-channel waits
    final def frameCompleted(
        seq: Long,
        handle: Long,
        settle: Long,
        renderStarted: Long,
    ): Unit =
      if enabled then
          val now = java.lang.System.nanoTime()
          frame(seq, now, handle, settle, now - renderStarted)
end DiagnosticRecorder

// codex 2026-10-22: created: opt-in lifecycle, separate from Kyo's observed scheduler
private[tui4s] object DiagnosticRecorder:
    val Property       = "tui4s.diagnostics.file"
    val IntervalMillis = 250L

    // codex 2026-10-22: created: one shared no-op, with no buffers, clocks or thread
    object Disabled extends DiagnosticRecorder(false):
        protected def update(f: DiagnosticState => DiagnosticState): Unit = ()

    // codex 2026-10-22: created: optional property read and resource registration in one effect
    def open(options: RunOptions, stack: ReleaseStack)(using
        Frame,
    ): DiagnosticRecorder < Sync =
      Sync.defer {
        try
            Option(java.lang.System.getProperty(Property))
              .filter(_.trim.nonEmpty) match
                case None       => Disabled
                case Some(file) => startNow(Path.of(file), options, stack)
        catch case NonFatal(_) => Disabled
      }

    // codex 2026-10-22: created: file seam for tests, without mutating a JVM property
    def openFile(file: Path, options: RunOptions, stack: ReleaseStack)(using
        Frame,
    ): DiagnosticRecorder < Sync =
      Sync.defer {
        try startNow(file, options, stack)
        catch case NonFatal(_) => Disabled
      }

    // codex 2026-10-22: created: register cleanup before the daemon can start
    private def startNow(
        file: Path,
        options: RunOptions,
        stack: ReleaseStack,
    ): DiagnosticRecorder =
        val live = new Live(file, options)
        stack.add("diagnostics")(() => live.close())
        live.start()
        live
    end startNow

    // codex 2026-10-22: created: one atomic immutable state; the observer never locks producers
    private final class Live(file: Path, options: RunOptions)
        extends DiagnosticRecorder(true):
        private val state    = new AtomicReference(
          DiagnosticState.initial(java.lang.System.nanoTime()),
        )
        private val stopped  = new AtomicBoolean(false)
        private val failures = new AtomicLong(0L)
        private val observer = new Thread(() => observe(), "tui4s-diagnostics")
        private val temp     = file.resolveSibling(
          file.getFileName.toString + s".${ProcessHandle.current().pid()}.${observer.threadId()}.tmp",
        )
        observer.setDaemon(true)

        // codex 2026-10-22: created: retry only pure transitions when the input and runner race
        protected def update(f: DiagnosticState => DiagnosticState): Unit =
          discard(
            state.updateAndGet(s =>
              if s.phase == DiagnosticPhase.Stopped then s else f(s),
            ),
          )

        // codex 2026-10-22: created: a stop registered during acquire also covers a failed start
        def start(): Unit = if !stopped.get() then observer.start()

        // codex 2026-10-22: created: bounded release; file IO stays on the daemon
        def close(): Unit =
          if stopped.compareAndSet(false, true) then
              val now = java.lang.System.nanoTime()
              discard(state.updateAndGet(_.at(DiagnosticPhase.Stopped, now)))
              observer.interrupt()
              try observer.join(200L)
              catch
                  case _: InterruptedException =>
                    Thread.currentThread().interrupt()

        // codex 2026-10-22: created: export during a stalled frame and once after stop
        private def observe(): Unit =
            while !stopped.get() do
                writeSnapshot()
                try Thread.sleep(IntervalMillis)
                catch
                    case _: InterruptedException => ()
            // codex 2026-10-22: created: file IO can preserve the observer's interrupt flag.
            discard(Thread.interrupted())
            writeSnapshot()
            try discard(Files.deleteIfExists(temp))
            catch case NonFatal(_) => ()
        end observe

        // codex 2026-10-22: created: one replaceable file, no append growth or terminal output
        private def writeSnapshot(): Unit =
          try
              val text = DiagnosticJson.render(
                state.get(),
                java.lang.System.nanoTime(),
                options,
                failures.get(),
              )
              discard(Files.writeString(temp, text, StandardCharsets.UTF_8))
              try
                  discard(
                    Files.move(
                      temp,
                      file,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING,
                    ),
                  )
              catch
                  case _: AtomicMoveNotSupportedException =>
                    discard(
                      Files.move(
                        temp,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                      ),
                    )
              end try
          catch case NonFatal(_) => discard(failures.incrementAndGet())
    end Live
end DiagnosticRecorder

// codex 2026-10-22: created: serialization of fixed names and numbers only
private[tui4s] object DiagnosticJson:
    // codex 2026-10-22: created: bounded snapshots do not serialize app data or file paths; codex 2026-10-23: changed: identify local settlement without fabricating global scheduler observations.
    def render(
        s: DiagnosticState,
        now: Long,
        options: RunOptions,
        writeFailures: Long,
    ): String =
        def age(t: Long): Long = (now - t).max(0L)
        val oldest             = s.inputs.valuesIterator
          .map(i => age(i.received))
          .maxOption
          .getOrElse(0L)
        val mode               = options.options.settle match
            case Settle.Local(_, _, _)  => "local"
            case Settle.Quiescent(_, _) => "quiescent"
            case Settle.Hops(_, _)      => "hops"
        val samples            = s.recent
          .map { r =>
            s"""{"seq":${r.seq},"receiveToFrameNanos":${r.receiveToFrame},"receiveToDispatchNanos":${r.receiveToDispatch},"afterInboxRemovalNanos":${r.afterInboxRemoval},"handleNanos":${r.handle},"settleNanos":${r.settle},"renderNanos":${r.render}}"""
          }
          .mkString(",")
        s"""{"schemaVersion":1,"inputOrigin":"terminal_read","completionBoundary":"runner_frame_pass_before_warning_log","elapsedNanos":${age(
            s.started,
          )},"phase":"${s.phase}","phaseAgeNanos":${age(
            s.phaseSince,
          )},"observationAgeNanos":${age(
            s.progress,
          )},"currentSeq":${s.currentSeq},"inputReceived":${s.received},"inputCompleted":${s.completed},"sampledCompletedInputs":${s.sampledCompleted},"lastCompletedSeq":${s.lastCompletedSeq},"inputPending":${(s.received - s.completed)
            .max(
              0L,
            )},"trackedPending":${s.inputs.size},"untrackedInputs":${s.untracked},"oldestTrackedInputAgeNanos":$oldest,"backlog":${s.backlog},"backlogMax":${s.backlogMax},"pendingComponents":${s.pending},"contentMessages":${s.contents},"armedMessages":${s.armed},"repairMessages":${s.repairs},"drains":${s.drains},"nonemptyDrains":${s.nonemptyDrains},"busyProbes":${s.busyProbes},"quietProbes":${s.quietProbes},"settleTimeouts":${s.timeouts},"framePasses":${s.frames},"lastFramePassAgeNanos":${s.lastFrame
            .map(age)
            .getOrElse(
              -1L,
            )},"batchOfferedInputs":${s.offered},"batchAcceptedInputs":${s.accepted},"batchOfferAgeNanos":${s.batchStarted
            .map(age)
            .getOrElse(-1L)},"batchCompletionAgeNanos":${s.batchCompleted
            .map(age)
            .getOrElse(
              -1L,
            )},"settleMode":"$mode","settleConfiguredNanos":${options.settleNanos},"pendingConfiguredNanos":${options.pendingNanos},"inboxCapacity":${options.inboxCapacity},"trackedInputLimit":${DiagnosticState.InputLimit},"recentSampleLimit":${DiagnosticState.SampleLimit},"writeFailures":$writeFailures,"recentInputs":[$samples]}
"""
    end render
end DiagnosticJson
