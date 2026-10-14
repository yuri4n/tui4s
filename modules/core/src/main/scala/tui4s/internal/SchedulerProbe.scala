package tui4s.internal

import kyo.scheduler.Scheduler

/** Reads the load of Kyo's scheduler through the public `Scheduler.status()`,
  * to know when the watchers that a write woke up have all run.
  *
  * A write to a `SignalRef` completes a promise; its callbacks run
  * synchronously and ENQUEUE every parked watcher (`IOTask`:
  * `promise.onComplete(_ => Scheduler.get.schedule(this))`). So when no worker
  * holds a queued or running task except the runner's own, every consequence of
  * the event has been posted. `WorkerStatus.load` is the queue size of a worker
  * plus its running task. The probe reads only public API: no reflection (D-17
  * c). Spike S1: one `status()` call costs 11 to 12 us (`Worker.status()` reads
  * the stack of the mounted thread), so a settle with this probe is 37 to 68 us
  * slower than with the old reflection probe; under load it is the most
  * reliable probe.
  *
  * The probe cannot see everything: a task is in no counter between
  * `queue.poll()` and `runTask`, and Kyo's `Signal` can lose a wakeup in
  * `combineLatest` (S1 section 5). The `confirm` of the settle, the verify pass
  * and the pull check of the runner cover what the probe misses.
  */
// claude 2026-10-12: changed the whole file (D-17 c, RC-11): the public
// status() probe only; the reflection field, the `tui4s.debugSettle` property
// and `describe` are gone
object SchedulerProbe:
    private val scheduler = Scheduler.get

    /** True when nothing but the caller runs or waits in the scheduler: the
      * loads of the workers sum to at most 1, and at most one worker has a
      * load.
      */
    def quiescent(): Boolean =
        val st    = scheduler.status()
        var total = 0
        var busy  = 0
        st.workers.foreach { w =>
          if w != null then
              total += w.load
              if w.load > 0 then busy += 1
        }
        busy <= 1 && total <= 1
    end quiescent
end SchedulerProbe
