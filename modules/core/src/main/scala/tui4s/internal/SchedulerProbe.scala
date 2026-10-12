package tui4s.internal

// claude 2026-10-09: created (carried from .work/proto/kyoui-runner/src/tui4s/kyoui/internal/SchedulerProbe.scala; new path and package only)

import kyo.scheduler.Scheduler

/** Reads the load of Kyo's scheduler, to know when the watchers that a write woke up have all run.
  *
  * A write to a `SignalRef` completes a promise; its callbacks run synchronously and ENQUEUE every parked watcher
  * (`IOTask`: `promise.onComplete(_ => Scheduler.get.schedule(this))`). A running watcher that forks (a race) enqueues
  * its children before it finishes. So when no worker holds a queued or running task except the runner's own, every
  * consequence of the event has been posted. The total load is `loadAvg * currentWorkers`; `currentWorkers` is private,
  * so it is read once by reflection (a PROTOTYPE HACK: upstream would expose a total load, or better, a signal-level
  * transaction API). Without it, the probe falls back to `busyWorkers() <= 1`.
  */
object SchedulerProbe:
    private val scheduler = Scheduler.get

    private val currentWorkers: Option[java.lang.reflect.Field] =
        try
            val f = classOf[Scheduler].getDeclaredFields.find(_.getName.endsWith("currentWorkers"))
            f.foreach(_.setAccessible(true))
            f
        catch case _: Throwable => None

    /** Queued plus running tasks over all workers (the caller's own task counts 1). */
    def totalLoad(): Int =
        currentWorkers match
            case Some(f) => math.round(scheduler.loadAvg() * f.getInt(scheduler)).toInt
            case None    => -1

    val debug: Boolean = java.lang.Boolean.getBoolean("tui4s.debugSettle")

    def describe(): String =
        val st = scheduler.status()
        s"totalLoad=${totalLoad()} busy=${scheduler.busyWorkers()} workers=" +
            st.workers.filter(_ != null).filter(_.load > 0).map(w => s"[${w.id} load=${w.load} blocked=${w.isBlocked} ${w.mount} ${w.frame}]").mkString(" ")

    /** True when nothing but the caller runs or waits in the scheduler. */
    def quiescent(): Boolean =
        val total = totalLoad()
        scheduler.busyWorkers() <= 1 && total <= 1
end SchedulerProbe
