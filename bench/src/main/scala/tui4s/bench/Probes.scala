package tui4s.bench

import jatatui.core.backend.Backend
import jatatui.core.backend.ClearType
import jatatui.core.backend.TestBackend
import jatatui.core.backend.WindowSize
import jatatui.core.buffer.BufferUpdate
import jatatui.core.layout.Position
import jatatui.core.layout.Size
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kyo.*
import tui4s.Event
import tui4s.Key
import tui4s.spi.InputPort

/** Shared parts of the probes (SPEC 7.4): timers, CPU and allocation counters,
  * a timed input port, a backend that reports flushes, and the output (a table
  * row on stdout and one JSON line per run in `results/<probe>.jsonl`).
  */
object Probes:
    /** Runs `v` on the Kyo scheduler and blocks until it ends. */
    def block[A](timeout: Duration)(
        v: => A < (Async & Abort[Throwable]),
    )(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(timeout)(v) match
            case Result.Success(a) => a
            case Result.Failure(e) => throw e
            case Result.Panic(e)   => throw e
    end block

    private val os      = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
    private val threads = ManagementFactory.getThreadMXBean
      .asInstanceOf[com.sun.management.ThreadMXBean]

    /** CPU time of this process, all threads. */
    def processCpuNanos: Long = os.getProcessCpuTime

    /** Bytes allocated so far by all live threads. */
    def allocatedBytes: Long = threads.getTotalThreadAllocatedBytes

    /** Live threads now. */
    def threadCount: Int = threads.getThreadCount

    /** The heap in use after a full collection, in bytes. */
    def heapAfterGc(): Long =
        var i = 0
        while i < 3 do
            java.lang.System.gc()
            Thread.sleep(200)
            i += 1
        ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed

    /** The machine, the JDK and the load, for the record. */
    def machine: String =
      s"${sys.props("os.name")} ${sys.props("os.arch")}, " +
        s"${Runtime.getRuntime.availableProcessors} CPUs, " +
        s"JDK ${sys.props("java.version")}, " +
        f"load ${os.getSystemLoadAverage}%.1f"

    /** The value at quantile `q` (0..1) of `xs`. */
    def quantile(xs: Seq[Double], q: Double): Double =
        val s = xs.sorted
        if s.isEmpty then Double.NaN
        else s(((s.size - 1) * q).round.toInt)

    /** Prints one result row and appends it as a JSON line. */
    def record(probe: String, fields: (String, Any)*): Unit =
        val all  = fields :+ ("machine" -> machine)
        val row  = all.map((k, v) => s"$k=${show(v)}").mkString("  ")
        println(s"[$probe] $row")
        val json = all
          .map((k, v) =>
              val value = v match
                  case n: (Int | Long)       => n.toString
                  case d: Double if !d.isNaN => f"$d%.3f"
                  case b: Boolean            => b.toString
                  case other                 => "\"" + other.toString + "\""
              s"\"$k\":$value",
          )
          .mkString("{", ",", "}")
        val dir  = Path.of(sys.props.getOrElse("tui4s.bench.results", "results"))
        Files.createDirectories(dir)
        val _    = Files.writeString(
          dir.resolve(s"$probe.jsonl"),
          json + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
        )
    end record

    private def show(v: Any): String = v match
        case d: Double => f"$d%.3f"
        case other     => other.toString

    /** Starts this JVM's class path again in a new JVM with `flags`, runs
      * `main` with `args`, and returns its stdout lines.
      */
    def fork(main: String, flags: Seq[String], args: Seq[String]): Seq[String] =
        val java = Path.of(sys.props("java.home"), "bin", "java").toString
        val cmd  = Seq(java, "-Xmx512m") ++ flags ++
          Seq(
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "-cp",
            sys.props("java.class.path"),
            main,
          ) ++ args
        val p    = ProcessBuilder(cmd*).redirectErrorStream(true).start()
        val out  = String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
        discard(p.waitFor())
        out.linesIterator.toVector
    end fork

    /** The Kyo scheduler settings of `tui4s.spi.SchedulerDefaults`. */
    val tuiFlags: Seq[String] =
      tui4s.spi.SchedulerDefaults.defaults.map((k, v) => s"-D$k=$v")

    /** Kyo's own defaults: tui4s sets nothing. */
    val kyoDefaults: Seq[String] = Seq("-Dtui4s.schedulerDefaults=false")

    private inline def discard[A](a: A): Unit =
        val _ = a
end Probes

/** Input that behaves like the terminal: `poll` waits for its timeout when no
  * event is queued. Records when each event was read.
  */
final class TimedInput extends InputPort:
    private val queue                          = LinkedBlockingQueue[Event]()
    @volatile private var peeked: Event | Null = null
    @volatile var readAt: Long                 = 0L

    def push(e: Event): Unit = queue.put(e match
        case k: Key => k
        case other  => other)

    def poll(timeoutMillis: Int): Boolean =
      peeked != null || {
        val e = queue.poll(timeoutMillis.max(0).toLong, TimeUnit.MILLISECONDS)
        if e == null then false
        else
            peeked = e
            true
      }

    def read(): Event | Null =
        val p = peeked
        peeked = null
        readAt = java.lang.System.nanoTime()
        if p != null then p else queue.poll()
end TimedInput

/** A `TestBackend` that counts flushes and records the time of the last one. */
final class FlushTimed(width: Int, height: Int) extends Backend:
    val underlying: TestBackend   = TestBackend(width, height)
    @volatile var flushes: Long   = 0L
    @volatile var flushedAt: Long = 0L

    def draw(content: java.lang.Iterable[BufferUpdate]): Unit    =
      underlying.draw(content)
    override def appendLines(n: Int): Unit                       = underlying.appendLines(n)
    def hideCursor(): Unit                                       = underlying.hideCursor()
    def showCursor(): Unit                                       = underlying.showCursor()
    def getCursorPosition(): Position                            = underlying.getCursorPosition()
    def setCursorPosition(position: Position): Unit              =
      underlying.setCursorPosition(position)
    def clear(): Unit                                            = underlying.clear()
    def clearRegion(clearType: ClearType): Unit                  =
      underlying.clearRegion(clearType)
    def size(): Size                                             = underlying.size()
    def windowSize(): WindowSize                                 = underlying.windowSize()
    def flush(): Unit                                            =
        underlying.flush()
        flushedAt = java.lang.System.nanoTime()
        flushes += 1
    def scrollRegionUp(start: Int, end: Int, lines: Int): Unit   =
      underlying.scrollRegionUp(start, end, lines)
    def scrollRegionDown(start: Int, end: Int, lines: Int): Unit =
      underlying.scrollRegionDown(start, end, lines)
end FlushTimed
