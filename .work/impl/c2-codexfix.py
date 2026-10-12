#!/usr/bin/env python3
"""PR #11 (C2) review fixes for the Codex findings. usage: c2-codexfix.py <ROOT> <G3|DOCS|all> <tests|code|both>
claude 2026-10-12: created"""
import pathlib, sys
ROOT = pathlib.Path(sys.argv[1]); WHICH = sys.argv[2]; PART = sys.argv[3]
MAIN = ROOT / 'modules/core/src/main/scala/tui4s'; TEST = ROOT / 'modules/core/src/test/scala/tui4s'
MARK = 'claude 2026-10-12'
def rep(path, old, new):
    t = path.read_text(); n = t.count(old)
    if n != 1: sys.exit(f'{path.name}: anchor found {n} times:\n{old[:120]!r}')
    path.write_text(t.replace(old, new, 1)); print(f'  {path.name}: ok')

def g3_code():
    K = MAIN / 'internal/Knobs.scala'
    rep(K, """  * @param repairInterval
  *   how often the repair tick re-reads `current` of every region (the same
  *   role as Kyo's `Signal.defaultRepairInterval`, 1 s)
  */
// claude 2026-10-12: created (D-16, K29)
private[tui4s] final case class Knobs(
    seam: Int => Duration = _ => Duration.Zero,
    repairInterval: Duration = Signal.defaultRepairInterval,
)
""", f"""  * @param repairInterval
  *   how often the repair tick re-reads `current` of every region (the same
  *   role as Kyo's `Signal.defaultRepairInterval`, 1 s)
  * @param flushSeam
  *   a spin between the row chunks of a flush. It holds open the window in
  *   which a reader of the backend can see a torn frame (microseconds wide
  *   with no seam). The torn-screen test of the headless kit uses it.
  */
// claude 2026-10-12: created (D-16, K29); changed (PR #11 review: Codex,
// Headless.scala:88): flushSeam
private[tui4s] final case class Knobs(
    seam: Int => Duration = _ => Duration.Zero,
    repairInterval: Duration = Signal.defaultRepairInterval,
    flushSeam: Duration = Duration.Zero,
)
""")
    P = MAIN / 'internal/Paint.scala'
    rep(P, """final class Canvas private (
    private var back: Buffer,
    private var front: Buffer,
    val backend: Backend,
):
    private var writes = 0
""", f"""final class Canvas private (
    private var back: Buffer,
    private var front: Buffer,
    val backend: Backend,
    flushSeamNanos: Long, // {MARK}: created (the test seam `Knobs.flushSeam`)
):
    private var writes = 0
""")
    rep(P, """    /** A new size. A `TestBackend` (the headless kit) has no terminal that
      * resizes it, so the canvas resizes it here: on the runner fiber, the one
      * fiber that writes the backend (it is not thread-safe).
      */
    // claude 2026-10-12: changed (review R2-4): the TestBackend is resized here
    def resize(s: Size): Unit =
        backend match
            case tb: TestBackend => tb.resize(s.w, s.h)
            case _               => ()
        back = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        front = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        backend.clear()
""", f"""    /** A new size. A `TestBackend` (the headless kit) has no terminal that
      * resizes it, so the canvas resizes it here: on the runner fiber, the one
      * fiber that writes the backend (it is not thread-safe). The backend's
      * monitor is held: a reader of a `TestBackend` holds it too (see `flush`).
      */
    // claude 2026-10-12: changed (review R2-4): the TestBackend is resized here;
    // changed (PR #11 review: Codex, Headless.scala:88): under the monitor
    def resize(s: Size): Unit =
      backend.synchronized {{
        backend match
            case tb: TestBackend => tb.resize(s.w, s.h)
            case _               => ()
        back = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        front = Buffer.empty(JRect.of(0, 0, s.w, s.h))
        backend.clear()
      }}
""")
    rep(P, """        if updates.nonEmpty then
            backend.draw(updates.asJava)
            backend.flush()
        updates.size
    end flush
end Canvas

object Canvas:
    def apply(backend: Backend): Canvas =
        val s = backend.size()
        new Canvas(
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          backend,
        )
end Canvas
""", f"""        if updates.nonEmpty then
            // {MARK}: changed (PR #11 review: Codex, Headless.scala:88): the
            // backend's monitor makes the flush one step for a reader of a
            // `TestBackend` (the headless kit reads under the same monitor), so
            // a screen is never a torn frame. The terminal backend has no
            // other holder of the monitor. With the test seam, the draw goes
            // one row at a time with a spin between the rows.
            backend.synchronized {{
              if flushSeamNanos <= 0L then backend.draw(updates.asJava)
              else
                  updates.grouped(back.area.width.max(1)).foreach {{ chunk =>
                    backend.draw(chunk.asJava)
                    val t0 = java.lang.System.nanoTime()
                    while java.lang.System.nanoTime() - t0 < flushSeamNanos do ()
                  }}
              backend.flush()
            }}
        updates.size
    end flush
end Canvas

object Canvas:
    /** @param flushSeam
      *   the test seam `Knobs.flushSeam` (zero in a real run)
      */
    // {MARK}: changed (PR #11 review: Codex, Headless.scala:88): flushSeam
    def apply(backend: Backend, flushSeam: Duration = Duration.Zero): Canvas =
        val s = backend.size()
        new Canvas(
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          backend,
          flushSeam.toNanos,
        )
end Canvas
""")
    H = MAIN / 'testkit/Headless.scala'
    rep(H, """          canvas  <- Sync.defer(Canvas(backend))
""", f"""          // {MARK}: changed (PR #11 review: Codex, Headless.scala:88): the seam
          canvas  <- Sync.defer(Canvas(backend, knobs.flushSeam))
""")
    rep(H, """    /** The screen as it is now: rows, cell styles and the cursor. */
    def screen(using Frame): Screen < Sync = Sync.defer(Screen.of(backend))

    /** The rows of the screen as it is now, with their trailing spaces (one
      * string per row, one place per cell).
      */
    def lines(using Frame): Vector[String] < Sync = Sync.defer {
      val buf = backend.buffer()
      Vector.tabulate(buf.area.height)(y =>
        (0 until buf.area.width).map(x => buf.cellAt(x, y).symbol()).mkString,
      )
    }
""", f"""    /** The screen as it is now: rows, cell styles and the cursor. The read
      * holds the backend's monitor, which the runner holds while it flushes a
      * frame or resizes the backend: a screen is never a torn frame.
      */
    // {MARK}: changed (PR #11 review: Codex, Headless.scala:88): the monitor
    def screen(using Frame): Screen < Sync =
      Sync.defer(backend.synchronized(Screen.of(backend)))

    /** The rows of the screen as it is now, with their trailing spaces (one
      * string per row, one place per cell). Under the backend's monitor, as
      * `screen`.
      */
    // {MARK}: changed (PR #11 review: Codex, Headless.scala:88): the monitor
    def lines(using Frame): Vector[String] < Sync = Sync.defer {{
      backend.synchronized {{
        val buf = backend.buffer()
        Vector.tabulate(buf.area.height)(y =>
          (0 until buf.area.width).map(x => buf.cellAt(x, y).symbol()).mkString,
        )
      }}
    }}
""")

def g3_tests():
    rep(TEST / 'HeadlessTest.scala', "import tui4s.testkit.Headless\n", "import tui4s.internal.Knobs\nimport tui4s.testkit.Headless\n")
    rep(TEST / 'HeadlessTest.scala', """    "start fails fast when the UI fails, with the error" in {""",
f"""    // {MARK}: created (PR #11 review: Codex, Headless.scala:88): the runner
    // flushes a frame cell by cell on its fiber while a test fiber can read the
    // screen. ONE region paints all the rows from one value, so a frame is
    // never torn by itself; the seam widens the flush (a spin between the
    // rows); without the backend's monitor the reader then sees torn frames
    // (rows with two values); with it, never. The natural window is
    // microseconds wide.
    "screen is never a torn frame: a read during a flush sees one value on every row" in {{
      Knobs.let(Knobs(flushSeam = 100.micros)) {{
        for
            n      <- Signal.initRef(0)
            h      <- Headless.start(24, 12)(_ =>
                        div(n.map(v => div(Vector.fill(12)(span((v % 10).toString * 22))*))),
                      )
            writer <- Fiber.initUnscoped(
                        Kyo.foreachDiscard(1 to 150)(i =>
                          n.set(i).andThen(
                            Clock.let(Clock.live)(Async.sleep(2.millis)),
                          ),
                        ),
                      )
            read   <- Loop(0, 0, Set.empty[String]) {{ (reads, torn, seen) =>
                        writer.done.map {{ done =>
                          if done then Loop.done((reads, torn, seen.size))
                          else
                              h.screen.map {{ s =>
                                val values =
                                  s.rows.map(_.trim).filter(_.nonEmpty).distinct
                                // one scheduler round trip: the writer and the
                                // runner get their workers between two reads
                                Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(
                                  Loop.continue(
                                    reads + 1,
                                    if values.size > 1 then torn + 1 else torn,
                                    seen ++ values,
                                  ),
                                )
                              }}
                        }}
                      }}
            fin    <- h.finished
        yield
            assert(
              read._2 == 0,
              s"${{read._2}} torn reads of ${{read._1}} (${{read._3}} distinct values seen)",
            )
            assert(read._1 > 100, s"only ${{read._1}} reads: the reader did not run")
            assert(read._3 > 3, s"only ${{read._3}} distinct values: the screen did not change")
            assert(!fin, "the run ended during the writes")
      }}
    }}

    "start fails fast when the UI fails, with the error" in {{""")

def docs_code():
    A = ROOT / 'AGENTS.md'
    rep(A, """  sets `-Dtui4s.load=1` in the test JVM, which allows 1. One event is one
  write: two writes from a test fiber are two events for the runner. The
  runner settles on the JVM-wide Kyo scheduler, so core test suites run one
""", f"""  sets `-Dtui4s.load=1` in the test JVM, which allows 1. One event is one
  write. The runner cannot tell a consequence of the active event from an
  independent write that lands while it settles (a Kyo signal carries no
  cause), so the writes that land inside one settle window share one frame:
  a frame is a batch, and `FrameStats.updates` counts its region values. The
  glitch test writes one ref at a time and waits for its frame; two writes
  from a test fiber with no wait between them give one frame or two.
  <!-- {MARK}: changed this sentence (PR #11 review: Codex, Runner.scala:394) -->
  The runner settles on the JVM-wide Kyo scheduler, so core test suites run one
""")
    H = MAIN / 'testkit/Headless.scala'
    rep(H, """  * The run ends with the enclosing `Scope`: `start` registers a finalizer that
  * posts the quit message and waits (at most 5 s) until the run fiber has
  * ended, so no watcher outlives the test.
  */
""", f"""  * The run ends with the enclosing `Scope`: `start` registers a finalizer that
  * posts the quit message and waits (at most 5 s) until the run fiber has
  * ended, so no watcher outlives the test.
  *
  * One test fiber drives a `Headless`. Its methods are not for two fibers at
  * the same time: `resize` and `nextFrame` take frames from one shared channel,
  * so a second caller can take the frame that the first one waits for.
  */
// {MARK}: changed (PR #11 review: Codex, Headless.scala:115): the one-driver rule
""")

FIXES = {{'G3': (g3_tests, g3_code), 'DOCS': (lambda: None, docs_code)}} if False else {'G3': (g3_tests, g3_code), 'DOCS': (lambda: print('  (no tests)'), docs_code)}
for n in (list(FIXES) if WHICH == 'all' else [WHICH]):
    t, c = FIXES[n]
    if PART in ('tests', 'both'): print(f'{n} tests'); t()
    if PART in ('code', 'both'): print(f'{n} code'); c()
