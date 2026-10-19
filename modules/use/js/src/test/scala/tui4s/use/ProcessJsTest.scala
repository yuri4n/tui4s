package tui4s.use

import kyo.*
import kyo.test.*

/** Scala.js has no processes: `useProcess` fails with a typed error. */
// claude 2026-10-19: created (L2a)
class ProcessJsTest extends Test[Any]:
    "useProcess fails with Unsupported" in {
      Scope
        .run(Abort.run[ProcessError](useProcess(Chunk("ls"))))
        .map(r => assert(r == Result.fail(ProcessError.Unsupported)))
    }
