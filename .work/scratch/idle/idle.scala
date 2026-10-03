//> using scala 3.9.0
//> using dep io.getkyo::kyo-core:1.0.0-RC7

// Idle CPU of the Kyo runtime (no events, one fiber in Async.sleep), per JVM thread.
// Usage: idle.scala <mode>   mode = jvm | kyo
// Scheduler flags are passed as -D system properties by the caller (see run.sh).
package tui4s.idle

import java.lang.management.ManagementFactory
import kyo.*

object IdleMain:
    val os = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
    val mx = ManagementFactory.getThreadMXBean

    def threads(): Map[Long, (String, Long)] =
        mx.getAllThreadIds.toSeq.flatMap { id =>
            val info = mx.getThreadInfo(id)
            val cpu  = mx.getThreadCpuTime(id)
            if info == null || cpu < 0 then None else Some(id -> (info.getThreadName, cpu))
        }.toMap

    def measure(label: String, warmMs: Long, windowMs: Long): Unit =
        Thread.sleep(warmMs)
        val t0   = threads()
        val cpu0 = os.getProcessCpuTime
        val w0   = System.nanoTime()
        Thread.sleep(windowMs)
        val cpu  = os.getProcessCpuTime - cpu0
        val wall = System.nanoTime() - w0
        val t1   = threads()
        val per = t1.toSeq.map { case (id, (name, c)) =>
            val before = t0.get(id).map(_._2).getOrElse(0L)
            (name.replaceAll("\\d+", "#"), c - before)
        }.groupMapReduce(_._1)(_._2)(_ + _).toSeq.sortBy(-_._2).take(6)
        val pct = 100.0 * cpu / wall
        println(f"$label%-34s process cpu = ${cpu / 1e6}%7.1f ms / ${wall / 1e6}%6.0f ms wall = $pct%5.2f%% of one core")
        per.foreach { case (n, c) => println(f"    ${c / 1e6}%7.1f ms  $n") }

    def main(args: Array[String]): Unit =
        val label = sys.props.getOrElse("probe.label", args.headOption.getOrElse("?"))
        args.headOption match
            case Some("jvm") =>
                measure(label, 2000, 3000)
            case _ =>
                val app = new Thread(() =>
                    import AllowUnsafe.embrace.danger
                    val _ = KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Async.sleep(6.seconds))
                )
                app.setDaemon(true)
                app.start()
                measure(label, 2000, 3000)
        end match
    end main
end IdleMain
