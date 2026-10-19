package tui4s

import kyo.*
import kyo.UI.*
import tui4s.fixtures as apps
import tui4s.internal.Knobs
import tui4s.testkit.Headless

/** The app tests (RC-19): the fixtures on the headless kit, driven by writes to
  * their `SignalRef`s and by keys. The screen is 100 x 30.
  */
// claude 2026-10-12: created (the prototype's Apps.test.scala, in kyo-test and
// without keys)
// claude 2026-10-13: changed (C3): the key-driven cases of the prototype are
// back (the leaves "... by keys")
class AppsTest extends RunnerSuite:

    // claude 2026-10-13: created (C3; the prototype's counter test); changed:
    // q quits (Tui.quit)
    "counter by keys: + + - with keys, Tab and Enter on the buttons, nothing happens for an unknown key, q quits" in {
      for
          h   <- Headless.start(100, 30)(_ => apps.Counter.app)
          s0  <- h.screen
          _    = assert(s0.contains("Count: 0"), s0.text)
          _   <- h.char('+')
          _   <- h.char('+')
          _   <- h.char('-')
          s1  <- h.screen
          _    = assert(s1.contains("Count: 1"), s1.text)
          _   <- h.key(Keyboard.Enter) // the first button (+) has the focus
          s2  <- h.screen
          _    = assert(s2.contains("Count: 2"), s2.text)
          _   <- h.key(Keyboard.Tab)   // to (-)
          _   <- h.key(Keyboard.Space)
          _   <- h.key(Keyboard.Space)
          s3  <- h.screen
          _    = assert(s3.contains("Count: 0"), s3.text)
          z   <- h.char('z')
          _    = assert(
                   z.cause == FrameCause.Input &&
                     (z.repainted, z.flushed, z.updates) == (0, 0, 0),
                   s"the frame of an unknown key: $z",
                 )
          _   <- h.char('q')
          fin <- h.awaitEnd(10.seconds)
      yield assert(fin, "q quits")
    }

    // claude 2026-10-13: created (C3; the prototype's two-counters test)
    "two counters by keys are independent; Tab order is document order" in {
      for
          h     <- Headless.start(100, 30)(_ => apps.Counter.twoCounters)
          _     <- h.key(Keyboard.Enter)                            // Left +
          _     <- h.key(Keyboard.Tab).andThen(h.key(Keyboard.Tab)) // Right +
          _     <- h.key(Keyboard.Enter).andThen(h.key(Keyboard.Enter))
          _     <- h.key(Keyboard.Tab, Modifiers(shift = true))                // back to Left -
          _     <- h.key(Keyboard.Enter)
          lines <- h.lines
          row    = lines.find(_.contains("Count:")).getOrElse("")
      yield assert(
        row.matches(""".*Count: 0.*Count: 2.*"""),
        lines.mkString("\n"),
      )
    }

    // claude 2026-10-13: created (C3; the prototype's todo test); changed: Esc
    // quits (Tui.quit)
    "todo by keys: type + Enter adds, Tab to the list, arrows + Space toggle, d deletes, Ctrl-S saves, Esc quits" in {
      val file = java.nio.file.Files.createTempFile("todo", ".txt")
      java.nio.file.Files
        .writeString(file, "[ ] buy milk\n[x] walk dog\n[ ] write report\n")
      for
          h   <- Headless.start(100, 30)(_ => apps.Todo.app(file.toString))
          s0  <- h.screen
          _    = assert(
                   s0.contains("2 open / 3") && s0.contains("[x] walk dog"),
                   s0.text,
                 )
          _   <- h.typeText("call mom")
          s1  <- h.screen
          _    = assert(s1.contains("New: call mom"), s1.text)
          _   <- h.key(Keyboard.Enter)
          s2  <- h.screen
          _    = assert(
                   s2.contains("[ ] call mom") && s2.contains("3 open / 4"),
                   s2.text,
                 )
          t   <- h.key(Keyboard.Tab)
          _    = assert(t.focus == "list", s"focus after Tab: ${t.focus}")
          _   <- h.key(Keyboard.ArrowDown)
          _   <- h.key(Keyboard.Space)
          s3  <- h.screen
          _    = assert(
                   s3.contains("[ ] walk dog") && s3.contains("4 open / 4"),
                   s3.text,
                 )
          y    = s3.rows.indexWhere(_.contains("walk dog"))
          _    = assert(
                   s3.styleAt(2, y).bg == Some("#6366f1"),
                   "the selected row has the indigo background",
                 )
          _   <- h.char('d')
          s4  <- h.screen
          _    = assert(
                   !s4.contains("walk dog") && s4.contains("3 open / 3"),
                   s4.text,
                 )
          _   <- h.key(Keyboard.Char('s'), Modifiers(ctrl = true))
          s5  <- h.screen
          _    = assert(s5.contains("saved 3 items"), s5.text)
          _    = assert(
                   java.nio.file.Files.readString(file) ==
                     "[ ] buy milk\n[ ] write report\n[ ] call mom\n",
                 )
          _   <- h.key(Keyboard.Escape)
          fin <- h.awaitEnd(10.seconds)
      yield assert(fin, "Esc quits")
      end for
    }

    // claude 2026-10-13: created (C3; the prototype's dashboard test)
    "dashboard by keys: ArrowRight changes the tab, p pauses the sampler" in {
      // the repair tick is off (see the dashboard leaf below)
      Knobs.let(Knobs(repairInterval = 1.hour)) {
        for
            s  <- apps.Dashboard.init
            h  <- Headless.start(100, 30)(_ => apps.Dashboard.view(s))
            _  <- apps.Dashboard.tick(s, Chunk(0.5, 0.25, 1.0))
            ok <- awaitAll(h, 2.seconds)("CPU usage", " 50%")
            _   = assert(ok)
            _  <- h.key(Keyboard.ArrowRight)
            s1 <- h.screen
            _   = assert(
                    s1.contains("Memory usage") && s1.contains(" 25%"),
                    s1.text,
                  )
            _  <- h.char('p')
            s2 <- h.screen
        yield assert(s2.contains("sampler PAUSED"), s2.text)
      }
    }

    // claude 2026-10-13: created (C4; the prototype's hooks test,
    // Apps.test.scala lines 62-81)
    // claude 2026-10-13: changed (C4, review R1-6): the mark is one comment
    // again (the format step had cut it in two)
    "hooks: top-level sequencing and runner-mounted components (state dropped when hidden)" in {
      for
          h  <- Headless.start(100, 30)(_ => apps.CounterHooks.app)
          _  <- h.key(Keyboard.Enter) // Left +
          s  <- h.screen
          _   = assert(
                  s.rows.exists(_.matches(""".*Count: 1.*Count: 0.*""")),
                  s.text,
                )
          d  <- Headless.start(100, 30)(_ => apps.CounterHooks.dynamic)
          _  <- d.key(Keyboard.Enter).andThen(d.key(Keyboard.Enter))
          s1 <- d.screen
          _   = assert(
                  s1.rows.exists(_.matches(""".*Count: 2.*Count: 0.*""")),
                  s1.text,
                )
          _  <- d.char('h')           // hide: the two components unmount
          s2 <- d.screen
          _   = assert(!s2.contains("Count:"), s2.text)
          _  <- d.char('h')           // show: two new mounts, fresh state
          s3 <- d.screen
      yield assert(
        s3.rows.exists(_.matches(""".*Count: 0.*Count: 0.*""")),
        s3.text,
      )
    }

    // claude 2026-10-13: created (C4; the prototype's Component test,
    // Apps.test.scala lines 83-100)
    "Component class: state per position, kept across a parent re-render" in {
      for
          title <- Signal.initRef("A")
          ui     = div.onKeyDown {
                     case KeyboardEvent(Keyboard.Char('r'), _, _) =>
                       title.updateAndGet(_ + "A")
                     case _                                       => ()
                   }(
                     title.render(t =>
                       div(
                         h2(s"title $t"),
                         div.style(_.row.gap(2.px))(
                           apps.CounterBox("Left"),
                           apps.CounterBox("Right"),
                         ),
                       ),
                     ),
                   )
          h     <- Headless.start(100, 30)(_ => ui)
          _     <- h.key(Keyboard.Enter) // Left +
          _     <- h.key(Keyboard.Tab)
                     .andThen(h.key(Keyboard.Tab))
                     .andThen(h.key(Keyboard.Enter))
                     .andThen(h.key(Keyboard.Enter)) // Right + twice
          _     <- h.char('r')           // the parent renders again
          s     <- h.screen
      yield
          assert(s.contains("title AA"), s.text)
          assert(
            s.rows.exists(_.matches(""".*Count: 1.*Count: 2.*""")),
            s.text,
          )
      end for
    }

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
      // the repair tick is off: it would render the tabs and the overview
      // again (their UI holds handlers, so two renders never compare equal)
      // and its frame could be the one `nextFrame` returns (review R3-9)
      Knobs.let(Knobs(repairInterval = 1.hour)) {
        for
            s   <- apps.Dashboard.init
            h   <- Headless.start(100, 30)(_ => apps.Dashboard.view(s))
            // the arm verifies of the start can render the tabs region again
            // (the same reason): let them pass before the tick
            _   <- Async.sleep(150.millis)
            _   <- h.drainFrames
            _   <- apps.Dashboard.tick(s, Chunk(0.5, 0.25, 1.0))
            f   <- h.nextFrame
            s0  <- h.screen /* the frame of the tick has both the gauge and the
             * sparkline */
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

    // claude 2026-10-12: changed (review R3-2, K2-2, R2-3): one write is the
    // event; the assertion is 0 (1 with the load flag); the count is printed
    "glitch-free: one event (one write to one ref) updates all 4 dependent regions in one frame (500 events)" in {
      // The frame contract is SOFT (D-17 c): 0 split frames with no load, at
      // most 1 per 10 000 under load. The assertion is 0; a run under CPU
      // contention sets `-Dtui4s.load=1` in the test JVM, which allows 1 of
      // 500. The event is ONE write (a pair in one ref): two writes from the
      // test fiber are two events for the runner when the test fiber is
      // descheduled between them (a handler that writes two refs inside a
      // step comes with the keys). The frames of an event are read after the
      // stats of the frame that changed the screen arrived (`framesUntil`).
      val underLoad =
        Option(java.lang.System.getProperty("tui4s.load")).exists(_.trim == "1")
      for
          ab     <- Signal.initRef((0, 0))
          a       = ab.map(_._1)
          b       = ab.map(_._2)
          ui      = div(
                      span(a.map(n => s"a=$n")),
                      span(b.map(n => s"b=$n")),
                      span(a.combineLatest(b).map((x, y) => s"sum=${x + y}")),
                      span(a.map(n => s"twice=${n * 2}")),
                    )
          h      <- Headless.start(40, 6)(_ => ui)
          _      <- Async.sleep(100.millis)
          _      <- h.drainFrames
          events <- Kyo.foreach(1 to 500) { i =>
                      for
                          _  <- ab.set((i, i))
                          ok <- awaitAll(h, 2.seconds)(
                                  s"a=$i",
                                  s"b=$i",
                                  s"sum=${2 * i}",
                                  s"twice=${2 * i}",
                                )
                          fs <- framesUntil(h, 500.millis)(_.updates > 0)
                      yield (
                        ok,
                        fs.count(_.updates > 0),
                        fs.exists(_.settleTimeouts > 0),
                      )
                    }
          s      <- h.screen
      yield
          val splits   = events.count((ok, n, _) => !ok || n != 1)
          val perEvent = events
            .groupBy(_._2)
            .toSeq
            .sortBy(_._1)
            .map((n, es) => s"$n frames: ${es.size}")
            .mkString(", ")
          val line     =
            s"[glitch] splits=$splits of 500 (events by frame count: $perEvent; events with a settle timeout: ${events
                .count(_._3)}; not seen in 2 s: ${events.count(!_._1)}; load flag: $underLoad)"
          java.lang.System.out.println(line)
          assert(splits <= (if underLoad then 1 else 0), line)
          assert(
            s.contains("a=500") && s.contains("b=500") && s.contains(
              "sum=1000",
            ) && s.contains("twice=1000"),
            s.text,
          )
      end for
    }

    // claude 2026-10-13: created (C3; the prototype's glitch test): one key is
    // one event; its handler writes two refs inside the runner's step
    "glitch-free by keys: one key whose handler writes two refs updates all 4 dependent regions in its frame (500 keys)" in {
      // The same SOFT contract and load flag as the leaf above. The frame of
      // the key is the Input frame that `char` returns: a split is a key frame
      // with fewer than 4 region values (combineLatest can add an
      // intermediate value, so 5 is right too).
      val underLoad =
        Option(java.lang.System.getProperty("tui4s.load")).exists(_.trim == "1")
      for
          a  <- Signal.initRef(0)
          b  <- Signal.initRef(0)
          ui  = div.onKeyDown {
                  case KeyboardEvent(Keyboard.Char('+'), _, _) =>
                    a.updateAndGet(_ + 1).andThen(b.updateAndGet(_ + 1))
                  case _                                       => ()
                }(
                  span(a.map(n => s"a=$n")),
                  span(b.map(n => s"b=$n")),
                  span(a.combineLatest(b).map((x, y) => s"sum=${x + y}")),
                  span(a.map(n => s"twice=${n * 2}")),
                )
          h  <- Headless.start(40, 6)(_ => ui)
          fs <- Kyo.foreach(1 to 500)(_ => h.char('+'))
          s  <- h.screen
      yield
          val splits = fs.count(_.updates < 4)
          val line   =
            s"[glitch-keys] splits=$splits of 500 (key frames by region values: ${fs
                .groupBy(_.updates)
                .toSeq
                .sortBy(_._1)
                .map((n, f) => s"$n: ${f.size}")
                .mkString(", ")}; frames with a settle timeout: ${fs
                .count(_.settleTimeouts > 0)}; load flag: $underLoad)"
          java.lang.System.out.println(line)
          assert(splits <= (if underLoad then 1 else 0), line)
          assert(
            s.contains("a=500") && s.contains("b=500") && s.contains(
              "sum=1000",
            ) && s.contains("twice=1000"),
            s.text,
          )
      end for
    }
end AppsTest
