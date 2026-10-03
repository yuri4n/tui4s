package dev

import java.lang.management.ManagementFactory
import kyo.*
import kyo.UI.*
import tui4s.kyoui.*

/** Step 6 measurements, headless on a 100x30 `TestBackend`.
  *
  * Latency = from the moment the bench sends the event (a key into the inbox, or a write to the sampler's ref) to the end of
  * the flush of that event's frame (`FrameStats.endNanos`). After each event the bench drains frames that came late; a late
  * frame is a split (the event's consequences were not all in its frame).
  */
object Bench:

    // claude 2026-10-03: created Sample (step 6 bench)
    final case class Sample(latNs: Long, f: FrameStats, late: Int)

    // claude 2026-10-03: created now (step 6 bench)
    private def now(using Frame): Long < Sync = Sync.defer(java.lang.System.nanoTime())

    // claude 2026-10-03: created pct (step 6 bench)
    private def pct(xs: Seq[Long], p: Double): Long =
        val s = xs.sorted
        s(((s.size - 1) * p).round.toInt)

    // claude 2026-10-03: created med (step 6 bench)
    private def med(xs: Seq[Int]): Int = pct(xs.map(_.toLong), 0.5).toInt

    // claude 2026-10-03: created row (step 6 bench)
    /** One markdown table row. */
    def row(name: String, ss: Seq[Sample]): String =
        val lat = ss.map(_.latNs)
        val us  = (n: Long) => f"${n / 1000.0}%.0f"
        val fl  = ss.map(_.f.flushed)
        val rp  = ss.map(_.f.repainted)
        val up  = ss.map(_.f.updates)
        val st  = ss.map(_.f.subtrees)
        val se  = ss.map(_.f.settleNanos)
        val rn  = ss.map(_.f.renderNanos)
        val late = ss.map(_.late).sum
        s"| $name | ${ss.size} | ${us(pct(lat, 0.5))} | ${us(pct(lat, 0.99))} | ${us(lat.max)} | ${med(fl)} (${fl.min}-${fl.max}) | " +
            s"${med(rp)} (${rp.min}-${rp.max}) | ${med(up)} (${up.min}-${up.max}) | ${med(st)} (${st.min}-${st.max}) | " +
            s"${us(pct(se, 0.5))} | ${us(pct(rn, 0.5))} | $late |"
    end row

    // claude 2026-10-03: created header (step 6 bench)
    val header: String =
        "| scenario | events | p50 us | p99 us | max us | cells flushed: median (min-max) | cell writes | region updates | subtrees painted | settle p50 us | layout+paint+flush p50 us | late frames |\n" +
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"

    // claude 2026-10-03: created timed (step 6 bench)
    /** Sends one event with `send` (which returns the event's own frame), then drains the frames that came late. */
    def timed(h: Headless)(send: => FrameStats < Async)(using Frame): Sample < Async =
        for
            t0   <- now
            f    <- send
            late <- h.drainFrames
        yield Sample(f.endNanos - t0, f, late.size)

    // claude 2026-10-03: created counter (step 6 bench)
    def counter(warm: Int, n: Int)(using Frame): Seq[Sample] < (Async & Scope) =
        for
            h  <- Headless.start(100, 30)(apps.Counter.app)
            _  <- Kyo.foreach(1 to warm)(_ => h.char('+'))
            ss <- Kyo.foreach(1 to n)(_ => timed(h)(h.char('+')))
            _  <- h.quit
        yield ss

    // claude 2026-10-03: created todoFile (step 6 bench)
    def todoFile(using Frame): String < Sync = Sync.defer {
        val f = java.nio.file.Files.createTempFile("todo-bench", ".txt")
        java.nio.file.Files.writeString(f, "[ ] buy milk\n[x] walk dog\n[ ] write report\n")
        f.toString
    }

    // claude 2026-10-03: created todoToggle (step 6 bench)
    /** Space on the list toggles the item at the cursor (the first item), `n` times. */
    def todoToggle(warm: Int, n: Int)(using Frame): Seq[Sample] < (Async & Scope) =
        for
            file <- todoFile
            h    <- Headless.start(100, 30)(apps.Todo.app(file))
            t    <- h.key(Keyboard.Tab)
            _ = require(t.focus == "list", s"focus: ${t.focus}")
            _  <- Kyo.foreach(1 to warm)(_ => h.key(Keyboard.Space))
            ss <- Kyo.foreach(1 to n)(_ => timed(h)(h.key(Keyboard.Space)))
            _  <- h.quit
        yield ss

    // claude 2026-10-03: created todoMixed (step 6 bench)
    /** One loop: type `x` into the input, Enter adds it, Tab to the list, Space toggles the first item, `d` deletes the first
      * item, Shift-Tab back to the input. The list stays at 3-4 items, so every loop sees the same screen.
      */
    def todoMixed(warm: Int, n: Int)(using Frame): Map[String, Seq[Sample]] < (Async & Scope) =
        def once(h: Headless)(using Frame): Seq[(String, Sample)] < Async =
            for
                a <- timed(h)(h.char('x'))
                b <- timed(h)(h.key(Keyboard.Enter))
                c <- timed(h)(h.key(Keyboard.Tab))
                d <- timed(h)(h.key(Keyboard.Space))
                e <- timed(h)(h.char('d'))
                f <- timed(h)(h.key(Keyboard.Tab, shift = true))
            yield Seq("type" -> a, "add" -> b, "tab" -> c, "toggle" -> d, "delete" -> e, "backtab" -> f)
        for
            file <- todoFile
            h    <- Headless.start(100, 30)(apps.Todo.app(file))
            _    <- Kyo.foreach(1 to warm)(_ => once(h))
            ss   <- Kyo.foreach(1 to n)(_ => once(h))
            s    <- h.screen
            _ = require(s.contains("3 open / 3") || s.contains("open / 3"), s"todo screen after the mixed loops:\n$s")
            _ <- h.quit
        yield ss.flatten.groupMap(_._1)(_._2)
    end todoMixed

    // claude 2026-10-03: created todoGrow (step 6 bench)
    /** Adds `n` items without deleting: the list grows past the 30-row screen (the overflow case). */
    def todoGrow(n: Int)(using Frame): (Seq[Sample], String) < (Async & Scope) =
        for
            file <- todoFile
            h    <- Headless.start(100, 30)(apps.Todo.app(file))
            ss   <- Kyo.foreach(1 to n)(_ => h.char('x').andThen(timed(h)(h.key(Keyboard.Enter))))
            s    <- h.screen
            _    <- h.quit
        yield (ss, s)

    // claude 2026-10-03: created dashboard (step 6 bench)
    /** One sampler tick written directly (the sampler fiber is not started), then the frame it causes. */
    def dashboard(warm: Int, n: Int)(using Frame): Seq[Sample] < (Async & Scope) =
        val rnd = new scala.util.Random(42)
        def values(using Frame): Chunk[Double] < Sync = Sync.defer(Chunk.fill(3)(rnd.nextDouble()))
        def tickOnce(s: apps.Dashboard.State, h: Headless)(using Frame): Sample < Async =
            values.map { vs =>
                for
                    t0   <- now
                    _    <- apps.Dashboard.tick(s, vs)
                    f    <- h.nextFrame
                    late <- h.drainFrames
                yield Sample(f.endNanos - t0, f, late.size)
            }
        for
            s  <- apps.Dashboard.init
            h  <- Headless.start(100, 30)(apps.Dashboard.view(s))
            _  <- Kyo.foreach(1 to warm)(_ => tickOnce(s, h))
            ss <- Kyo.foreach(1 to n)(_ => tickOnce(s, h))
            _  <- h.quit
        yield ss
    end dashboard

    // ---- idle CPU ----

    // claude 2026-10-03: created os (step 6 bench)
    private val os      = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
    // claude 2026-10-03: created threads (step 6 bench)
    private val threads = ManagementFactory.getThreadMXBean

    // claude 2026-10-03: created threadCpu (step 6 bench)
    private def threadCpu(): Map[String, Long] =
        threads.getAllThreadIds.toSeq.flatMap { id =>
            val info = threads.getThreadInfo(id)
            val t    = threads.getThreadCpuTime(id)
            if info == null || t < 0 then None else Some(s"${info.getThreadName}#$id" -> t)
        }.toMap

    // claude 2026-10-03: created idle (step 6 bench)
    /** CPU of the whole JVM over `secs` seconds with nothing sent; the top threads by CPU in that window. */
    def idle(name: String, h: Maybe[Headless], secs: Int)(using Frame): String < Async =
        for
            _  <- Async.sleep(1.second)
            _  <- h.fold(Kyo.unit)(_.drainFrames.unit)
            c0 <- Sync.defer(os.getProcessCpuTime)
            p0 <- Sync.defer(threadCpu())
            t0 <- now
            _  <- Async.sleep(secs.seconds)
            c1 <- Sync.defer(os.getProcessCpuTime)
            p1 <- Sync.defer(threadCpu())
            t1 <- now
            fr <- h.fold(Kyo.lift(Chunk.empty[FrameStats]))(_.drainFrames)
        yield
            val pct = (c1 - c0) * 100.0 / (t1 - t0)
            val top = p1.toSeq.map((k, v) => k -> (v - p0.getOrElse(k, 0L))).sortBy(-_._2).take(5)
                .map((k, v) => f"$k=${v / 1e6}%.1fms").mkString(", ")
            f"| $name | ${(t1 - t0) / 1e9}%.1f | ${(c1 - c0) / 1e6}%.0f | $pct%.2f | ${fr.size} | $top |"

    // claude 2026-10-03: created main (step 6 bench)
    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val warm = args.lift(0).map(_.toInt).getOrElse(300)
        val n    = args.lift(1).map(_.toInt).getOrElse(1000)
        val secs = args.lift(2).map(_.toInt).getOrElse(10)
        def say(s: String)(using Frame): Unit < Sync = Sync.defer(java.lang.System.out.println(s))
        val r = KyoApp.Unsafe.runAndBlock(1800.seconds) {
            for
                _  <- say(s"cpus=${Runtime.getRuntime.availableProcessors} load=${os.getSystemLoadAverage} warm=$warm n=$n")
                c  <- Scope.run(counter(warm, n))
                tt <- Scope.run(todoToggle(warm, n))
                tm <- Scope.run(todoMixed(warm / 3, n))
                tg <- Scope.run(todoGrow(200))
                d  <- Scope.run(dashboard(warm, n))
                (grow, growScreen) = tg
                _ <- say(header)
                _ <- say(row("Counter: `+` key", c))
                _ <- say(row("Todo: Space toggles the first item (list of 3, focus on the list)", tt))
                _ <- say(row("Todo loop: type `x` into the input", tm("type")))
                _ <- say(row("Todo loop: Enter adds", tm("add")))
                _ <- say(row("Todo loop: Tab to the list", tm("tab")))
                _ <- say(row("Todo loop: Space toggles", tm("toggle")))
                _ <- say(row("Todo loop: `d` deletes the first item", tm("delete")))
                _ <- say(row("Todo loop: Shift-Tab to the input", tm("backtab")))
                _ <- say(row("Todo grow: Enter adds, items 4-53", grow.take(50)))
                _ <- say(row("Todo grow: Enter adds, items 154-203", grow.takeRight(50)))
                _ <- say(row("Dashboard: one sampler tick", d))
                _ <- say(s"todo grow: the screen after 200 adds (203 items):\n$growScreen")
                _ <- say(s"load after the event runs: ${os.getSystemLoadAverage}")
                _ <- say("| idle case | seconds | process CPU ms | % of one core | frames | top threads (CPU in the window) |\n| --- | --- | --- | --- | --- | --- |")
                i0 <- idle("no app mounted (JVM + Kyo baseline)", Absent, secs)
                _  <- say(i0)
                i1 <- Scope.run(Headless.start(100, 30)(apps.Counter.app).map(h => idle("Counter", Present(h), secs).map(r => h.quit.andThen(r))))
                _  <- say(i1)
                f  <- todoFile
                i2 <- Scope.run(Headless.start(100, 30)(apps.Todo.app(f)).map(h => idle("Todo", Present(h), secs).map(r => h.quit.andThen(r))))
                _  <- say(i2)
                i3 <- Scope.run(
                    Headless.start(100, 30)(apps.Dashboard.app).map(h => idle("Dashboard, live (sampler 250 ms + log 400 ms)", Present(h), secs).map(r => h.quit.andThen(r)))
                )
                _ <- say(i3)
                _ <- say(s"load at the end: ${os.getSystemLoadAverage}")
            yield ()
        }
        println(r)
    end main
end Bench
