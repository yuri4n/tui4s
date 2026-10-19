package tui4s.use

import kyo.*

/** Scala.js has no processes: [[useProcess]] fails at once. */
// claude 2026-10-19: created (L2a)
private[use] object ProcessPlatform:

    def start(@scala.annotation.unused cmd: Chunk[String])(using
        Frame,
    ): RunningProcess < (Sync & Scope & Abort[ProcessError]) =
      Abort.fail(ProcessError.Unsupported)
