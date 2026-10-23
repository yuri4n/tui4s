package tui4s

import kyo.*
import kyo.test.*
import tui4s.internal.{
  DiagnosticPhase,
  DiagnosticRecorder,
  Loc,
  Msg,
  ReleaseStack,
  RunOptions,
  discard,
}

import java.nio.file.{Files, NoSuchFileException, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The observer exports progress without a completed frame or app contents. */
// codex 2026-10-22: created: diagnostic file lifecycle and failure tests
class DiagnosticRecorderTest extends Test[Any]:
    // codex 2026-10-22: created: thread observations belong to one test at a time
    override def config: RunConfig = super.config.sequential

    // codex 2026-10-22: created: fixed options for the property-free file seam
    private val options = RunOptions.of(TuiOptions())

    // codex 2026-10-22: created: tolerate the first snapshot not existing yet
    private def snapshot(file: Path): String =
      try Files.readString(file)
      catch case _: NoSuchFileException => ""

    // codex 2026-10-22: created: read fixed numeric schema fields without app data
    private def number(text: String, field: String): Long =
      ("\"" + field + "\":(-?[0-9]+)").r
        .findFirstMatchIn(text)
        .map(_.group(1).nn.toLong)
        .getOrElse(-1L)

    // codex 2026-10-22: created: bounded wait for the independent observer
    private def eventually(cond: => Boolean)(using Frame): Boolean < Async =
      Loop(0) { n =>
        Sync.defer(cond).map { ok =>
          if ok then Loop.done(true)
          else if n >= 500 then Loop.done(false)
          else Async.sleep(10.millis).andThen(Loop.continue(n + 1))
        }
      }

    // codex 2026-10-22: created: identify observer threads for cleanup checks
    private def observers: Set[Thread] =
      Thread.getAllStackTraces.keySet.asScala
        .filter(_.getName == "tui4s-diagnostics")
        .toSet

    // codex 2026-10-22: created: remove observer resources and files after any result
    private def withFiles[A](
        use: (Path, ReleaseStack) => A < Async,
    )(using Frame): A < Async =
      Sync
        .defer(
          (
            Files.createTempDirectory("tui4s-diagnostics-test"),
            ReleaseStack(_ => ()),
          ),
        )
        .map { (dir, stack) =>
          Sync.ensure(Sync.defer {
            stack.runAll()
            Using.resource(Files.walk(dir)) { paths =>
              paths.iterator.asScala.toVector
                .sortBy(_.getNameCount)
                .reverse
                .foreach { p =>
                  discard(Files.deleteIfExists(p))
                }
            }
          })(use(dir, stack))
        }

    // codex 2026-10-22: created: disabled hooks allocate no observer and accept no data
    "disabled diagnostics do not start an observer" in {
      val before   = observers
      val recorder = DiagnosticRecorder.Disabled
      recorder.received(1L)
      recorder.seen(Msg.Paste("private-input", 1L))
      recorder.dispatched(Msg.Paste("private-input", 1L))
      recorder.phase(DiagnosticPhase.Settling, 1, 0)
      recorder.drained(3, Some(false))
      recorder.timedOut()
      recorder.frame(1L, 10L, 1L, 2L, 3L)
      assert(!recorder.enabled)
      assert(observers == before)
    }

    // codex 2026-10-22: created: live stalls stay visible; repeated exports replace one file
    "a stalled frame exports pressure without input text, then stops cleanly"
      .timeout(20.seconds) in {
      withFiles { (dir, stack) =>
        val file = dir.resolve("snapshot.json")
        for
            before   <- Sync.defer(observers)
            recorder <- DiagnosticRecorder.openFile(file, options, stack)
            _        <- Sync.defer {
                          recorder.received(1L)
                          recorder.offered(1)
                          recorder.accepted(1, success = true)
                          recorder.seen(Msg.Paste("private-paste-contents", 1L))
                          recorder.seen(
                            Msg.Content(Loc.root, 1L, "private-status-value"),
                          )
                          recorder.seen(Msg.Armed(Loc.root, 1L))
                          recorder.seen(Msg.Repair)
                          recorder.phase(
                            DiagnosticPhase.Settling,
                            backlog = 1,
                            pending = 2,
                          )
                          recorder.drained(4, Some(false))
                        }
            ready    <- eventually {
                          val text = snapshot(file)
                          text.contains("\"phase\":\"Settling\"") && number(
                            text,
                            "contentMessages",
                          ) == 1L
                        }
            first    <- Sync.defer(snapshot(file))
            owned    <- Sync.defer(observers -- before)
            advanced <- eventually(
                          number(snapshot(file), "elapsedNanos") > number(
                            first,
                            "elapsedNanos",
                          ),
                        )
            _        <- Sync.defer(stack.runAll())
            stopped  <-
              eventually(snapshot(file).contains("\"phase\":\"Stopped\""))
            ended    <- eventually(owned.forall(t => !t.isAlive))
            last     <- Sync.defer(snapshot(file))
            names    <- Sync.defer(
                          Using.resource(Files.list(dir))(
                            _.iterator.asScala.map(_.getFileName.toString).toSet,
                          ),
                        )
        yield
            assert(recorder.enabled && ready && advanced)
            assert(number(first, "framePasses") == 0L)
            assert(number(first, "inputPending") == 1L)
            assert(
              number(first, "backlog") == 1L && number(
                first,
                "backlogMax",
              ) == 1L,
            )
            assert(number(first, "pendingComponents") == 2L)
            assert(
              number(first, "armedMessages") == 1L && number(
                first,
                "repairMessages",
              ) == 1L,
            )
            assert(
              number(first, "busyProbes") == 1L && number(
                first,
                "nonemptyDrains",
              ) == 1L,
            )
            assert(number(first, "oldestTrackedInputAgeNanos") > 0L)
            assert(
              !first.contains("private-paste-contents") && !first.contains(
                "private-status-value",
              ),
            )
            assert(
              !last.contains("private-paste-contents") && !last.contains(
                "private-status-value",
              ),
            )
            assert(owned.size == 1 && stopped && ended)
            assert(names == Set("snapshot.json"))
            assert(last.length < 16_384)
        end for
      }
    }

    // codex 2026-10-22: created: export failure cannot stop input hooks and is visible after recovery
    "a missing output directory does not crash recording and reports write failures"
      .timeout(20.seconds) in {
      withFiles { (dir, stack) =>
        val parent = dir.resolve("missing")
        val file   = parent.resolve("snapshot.json")
        for
            before    <- Sync.defer(observers)
            recorder  <- DiagnosticRecorder.openFile(file, options, stack)
            _         <- Sync.defer {
                           recorder.received(1L)
                           recorder.phase(
                             DiagnosticPhase.Settling,
                             backlog = 1,
                             pending = 0,
                           )
                         }
            running   <- eventually((observers -- before).nonEmpty)
            owned     <- Sync.defer(observers -- before)
            _         <- Async.sleep(600.millis)
            _         <- Sync.defer(discard(Files.createDirectory(parent)))
            recovered <-
              eventually(number(snapshot(file), "writeFailures") > 0L)
            text      <- Sync.defer(snapshot(file))
            _         <- Sync.defer(stack.runAll())
            ended     <- eventually(owned.forall(t => !t.isAlive))
        yield
            assert(recorder.enabled && running && recovered && ended)
            assert(
              number(text, "inputReceived") == 1L && number(
                text,
                "inputPending",
              ) == 1L,
            )
            assert(number(text, "framePasses") == 0L)
        end for
      }
    }
end DiagnosticRecorderTest
