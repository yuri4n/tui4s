package tui4s

import kyo.*
import kyo.UI.*
import tui4s.fixtures as apps
import tui4s.testkit.Headless

/** The app tests (RC-19): the fixtures on the headless kit, driven by writes to
  * their `SignalRef`s (keys come in C3). The screen is 100 x 30.
  */
// claude 2026-10-12: created (the prototype's Apps.test.scala, in kyo-test and
// without keys)
class AppsTest extends RunnerSuite:

    "counter: writes to the count reach the screen; the kit's quit ends the run" in {
      for
          count <- Signal.initRef(0)
          h     <- Headless.start(100, 30)(_ => apps.Counter.view(count))
          s0    <- h.screen
          _      = assert(s0.contains("Count: 0"), s0.text)
          _     <- count.updateAndGet(_ + 1).andThen(count.updateAndGet(_ + 1))
          _     <- count.updateAndGet(_ - 1)
          ok1   <- h.awaitScreen(showing("Count: 1"), 2.seconds)
          _      = assert(ok1)
          _     <- count.set(0)
          ok0   <- h.awaitScreen(showing("Count: 0"), 2.seconds)
          _      = assert(ok0)
          _     <- h.quit
          fin   <- h.awaitEnd(10.seconds)
      yield assert(fin, "quit ends the run")
    }

    "two counters are independent" in {
      for
          left  <- Signal.initRef(0)
          right <- Signal.initRef(0)
          h     <- Headless.start(100, 30)(_ =>
                     apps.Counter.twoCountersView(
                       apps.Counter.counterView("Left", left),
                       apps.Counter.counterView("Right", right),
                     ),
                   )
          _     <- left.set(1).andThen(right.set(2)).andThen(left.set(0))
          ok    <- h.awaitScreen(
                     _.rows.exists(_.matches(""".*Count: 0.*Count: 2.*""")),
                     2.seconds,
                   )
          s     <- h.screen
      yield assert(ok, s.text)
    }

    "todo: load, add, select, toggle, delete, save, status" in {
      val file = java.nio.file.Files.createTempFile("todo", ".txt")
      java.nio.file.Files
        .writeString(file, "[ ] buy milk\n[x] walk dog\n[ ] write report\n")
      for
          s     <- apps.Todo.init(file.toString)
          h     <- Headless.start(100, 30)(_ => apps.Todo.view(s))
          s0    <- h.screen
          _      = assert(
                     s0.contains("2 open / 3") && s0.contains("[x] walk dog"),
                     s0.text,
                   )
          _     <- s.draft.set("call mom")
          ok1   <- h.awaitScreen(showing("New: call mom"), 2.seconds)
          _      = assert(ok1)
          _     <- apps.Todo.add(s)
          ok2   <- awaitAll(h, 2.seconds)("[ ] call mom", "3 open / 4")
          _      = assert(ok2)
          _     <- s.cursor.set(1)
          ok3   <- h.awaitScreen(
                     scr =>
                       scr.rows.indexWhere(_.contains("walk dog")) >= 0 && {
                         val y = scr.rows.indexWhere(_.contains("walk dog"))
                         scr.styleAt(2, y).bg == Some("#6366f1") // indigo
                       },
                     2.seconds,
                   )
          _      = assert(ok3, "the selected row has the indigo background")
          _     <- s.items.updateAndGet(ts =>
                     ts.updated(1, ts(1).copy(done = !ts(1).done)),
                   )
          ok4   <- awaitAll(h, 2.seconds)("[ ] walk dog", "4 open / 4")
          _      = assert(ok4)
          _     <- s.items.updateAndGet(ts => ts.take(1) ++ ts.drop(2))
          ok5   <- h.awaitScreen(
                     scr =>
                       !scr.contains("walk dog") && scr.contains("3 open / 3"),
                     2.seconds,
                   )
          _      = assert(ok5)
          items <- s.items.get
          _     <-
            Abort.run[java.io.IOException](apps.Todo.save(file.toString, items))
          _     <- s.status.set(s"saved ${items.size} items to $file")
          ok6   <- h.awaitScreen(showing("saved 3 items"), 2.seconds)
      yield
          assert(ok6)
          assert(
            java.nio.file.Files.readString(file) ==
              "[ ] buy milk\n[ ] write report\n[ ] call mom\n",
          )
      end for
    }

    "overflow: a list taller than the screen keeps the header and the input; the list is clipped" in {
      val file = java.nio.file.Files.createTempFile("todo", ".txt")
      java.nio.file.Files.writeString(
        file,
        (1 to 40).map(i => s"[ ] item $i").mkString("", "\n", "\n"),
      )
      for
          h <- Headless.start(100, 30)(_ => apps.Todo.app(file.toString))
          s <- h.screen
      yield
          assert(
            s.contains("Todo") && s.contains("40 open / 40") && s.contains(
              "New:",
            ),
            s.text,
          )
          assert(
            s.contains("[ ] item 1") && s.contains("[ ] item 20") && !s
              .contains("item 40"),
            s.text,
          )
      end for
    }

    "dashboard: a sampler tick updates the gauge and the sparkline in one frame; tabs; pause" in {
      for
          s   <- apps.Dashboard.init
          h   <- Headless.start(100, 30)(_ => apps.Dashboard.view(s))
          // the arm verifies of the start can render the tabs region again (its
          // buttons hold handlers, so two renders never compare equal): let
          // them pass before the tick
          _   <- Async.sleep(150.millis)
          _   <- h.drainFrames
          _   <- apps.Dashboard.tick(s, Chunk(0.5, 0.25, 1.0))
          f   <- h.nextFrame
          s0  <- h.screen // the frame of the tick has both the gauge and the sparkline
          _    = assert(f.updates >= 2, s"frame of the tick: $f")
          _    = assert(
                   s0.contains("CPU usage") &&
                     s0.contains("███████████████░░░░░░░░░░░░░░░  50%") && s0
                       .contains("▅"),
                   s0.text,
                 )
          _   <- s.tab.set(1)
          ok  <- awaitAll(h, 2.seconds)("Memory usage", " 25%")
          _    = assert(ok)
          _   <- s.paused.set(true)
          ok2 <- h.awaitScreen(showing("sampler PAUSED"), 2.seconds)
      yield assert(ok2)
    }

    "idle: no frame at all when nothing changes" in {
      for
          count <- Signal.initRef(0)
          h     <- Headless.start(100, 30)(_ => apps.Counter.view(count))
          _     <- count.set(1)
          ok    <- h.awaitScreen(showing("Count: 1"), 2.seconds)
          _      = assert(ok)
          _     <- Async.sleep(300.millis)
          _     <- h.drainFrames
          _     <- Async.sleep(1500.millis)
          late  <- h.drainFrames
      yield assert(late.isEmpty, s"frames while idle: $late")
    }

    "glitch-free: one event that writes two refs updates all 4 dependent regions in one frame (500 events)" in {
      // The allowance (D-17 c, SOFT): 0 split frames with no load; at most 1
      // per 10 000 under load. 500 events get an allowance of 1, for the run
      // under CPU contention of the acceptance.
      for
          a      <- Signal.initRef(0)
          b      <- Signal.initRef(0)
          ui      = div(
                      span(a.map(n => s"a=$n")),
                      span(b.map(n => s"b=$n")),
                      span(a.combineLatest(b).map((x, y) => s"sum=${x + y}")),
                      span(a.map(n => s"twice=${n * 2}")),
                    )
          h      <- Headless.start(40, 6)(_ => ui)
          _      <- Async.sleep(100.millis)
          _      <- h.drainFrames
          splits <- Kyo.foreach(1 to 500) { i =>
                      for
                          _  <-
                            a.updateAndGet(_ + 1).andThen(b.updateAndGet(_ + 1))
                          ok <- awaitAll(h, 2.seconds)(
                                  s"a=$i",
                                  s"b=$i",
                                  s"sum=${2 * i}",
                                  s"twice=${2 * i}",
                                )
                          fs <- h.drainFrames
                      yield if ok && fs.count(_.updates > 0) == 1 then 0 else 1
                    }
          s      <- h.screen
      yield
          assert(splits.sum <= 1, s"split frames: ${splits.sum} of 500")
          assert(
            s.contains("a=500") && s.contains("b=500") && s.contains(
              "sum=1000",
            ) && s.contains("twice=1000"),
            s.text,
          )
    }
end AppsTest
