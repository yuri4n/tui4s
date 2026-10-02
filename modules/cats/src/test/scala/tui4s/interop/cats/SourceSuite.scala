package tui4s.interop.cats

import _root_.cats.effect.IO
import _root_.cats.effect.kernel.Deferred
import _root_.cats.syntax.all.*
import _root_.fs2.Stream
import _root_.fs2.concurrent.SignallingRef
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import tui4s.interop.cats.fixtures.Apps
import tui4s.interop.cats.fixtures.Apps.Tally
import tui4s.testkit.*

/** The Cats Effect sources on the real loop (SPEC 8.4, K2): backpressure from
  * the inbox to fs2, cancel of a stopped source, and each constructor.
  */
class SourceSuite extends CatsEffectSuite:
    override def munitIOTimeout: Duration = 60.seconds

    private val source = Source.For[IO, Tally]

    /** Runs `app` with no input until it quits. */
    private def run(app: App[Tally]): IO[Tally] =
      Tui.runWith[IO, Tally](app, RecordingControl(), ScriptedInput.never)

    test(
      "fs2 backpressure: 10 000 elements through an inbox of 16, none lost",
    ) {
      val n      = 10000
      val stream = Stream.iterate(0)(_ + 1).take(n.toLong).covary[IO]
      val app    = Apps.tally(
        _ => Seq(source.stream(stream)(_.add(_, n)).id("numbers")),
        TuiOptions(inboxCapacity = 16),
      )
      run(app).map { t =>
        assertEquals(t.count, n)
        assertEquals(t.sum, n.toLong * (n - 1) / 2)
      }
    }

    test("fs2 backpressure: one chunk of 10 000 elements is one change") {
      val n   = 10000
      val app = Apps.tally(
        _ =>
          Seq(
            source.stream(Stream.emits(0 until n).covary[IO])(_.add(_, n)),
          ),
        TuiOptions(inboxCapacity = 16),
      )
      run(app).map(t =>
        assertEquals((t.count, t.sum), (n, n.toLong * (n - 1) / 2)),
      )
    }

    /** An app whose only source runs `work` while the count is 0; '+' removes
      * it. Returns when `cancelled` completed, i.e. the source's F was
      * cancelled.
      */
    private def stops(
        work: (Deferred[IO, Unit], Deferred[IO, Unit]) => Source[Tally],
    ): IO[Unit] =
      for
          started   <- IO.deferred[Unit]
          cancelled <- IO.deferred[Unit]
          input      = ScriptedInput.empty
          app        = Apps.tally(t =>
                         if t.count == 0 then Seq(work(started, cancelled))
                         else Nil,
                       )
          fiber     <- Tui
                         .runWith[IO, Tally](app, RecordingControl(), input)
                         .start
          _         <- started.get.timeout(10.seconds)
          _         <- IO(input.push(Key.Char('+')))
          _         <- cancelled.get.timeout(10.seconds)
          _         <- IO(input.push(Key.Char('q')))
          end       <- fiber.joinWithNever
      yield assertEquals(end.count, 1)

    test("a stopped task source cancels its IO (onCancel runs)") {
      stops((started, cancelled) =>
        source.task(
          (started.complete(()) *> IO.never[Int])
            .onCancel(cancelled.complete(()).void),
        )(_.add(_, 1)),
      )
    }

    test("a stopped stream source cancels its fs2 stream") {
      stops((started, cancelled) =>
        source.stream(
          (Stream.eval(started.complete(())) >> Stream.never[IO])
            .onFinalize(cancelled.complete(()).void)
            .as(1),
        )(_.add(_, 1)),
      )
    }

    test("a stopped poll source cancels its IO") {
      stops((started, cancelled) =>
        source.poll(1.second)(
          (started.complete(()) *> IO.never[Int])
            .onCancel(cancelled.complete(()).void),
        )(_.add(_, 1)),
      )
    }

    test("poll runs the IO now, then after each period") {
      val app = Apps.tally(_ => Seq(source.poll(20.millis)(IO(2))(_.add(_, 5))))
      run(app).map(t => assertEquals((t.count, t.sum), (5, 10L)))
    }

    test("every applies the function after each period") {
      val app = Apps.tally(_ => Seq(source.every(20.millis)(_.add(1, 4))))
      run(app).map(t => assertEquals(t.count, 4))
    }

    test("signal folds the current value, then the latest value") {
      for
          ref <- SignallingRef[IO, Int](7)
          app  = Apps.tally(_ =>
                   Seq(
                     source.signal(ref) { (t, v) =>
                       val next = t.copy(count = t.count + 1, sum = v.toLong)
                       if v == 1000 then Quit(next) else next
                     },
                   ),
                 )
          f   <- run(app).start
          _   <- IO.sleep(100.millis)
          _   <- (1 to 1000).toList.traverse_(ref.set)
          end <- f.joinWithNever
      yield
          assertEquals(end.sum, 1000L)
          assert(end.count >= 1 && end.count <= 1001, end.toString)
    }

    test("a failing task without recover ends the app with SourceFailed") {
      val boom = new IllegalStateException("boom")
      val app  = Apps.tally(_ =>
        Seq(source.task(IO.raiseError[Int](boom))(_.add(_, 1)).id("bad")),
      )
      run(app).attempt.map {
        case Left(TuiError.SourceFailed(label, e)) =>
          assertEquals((label, e), ("bad", boom))
        case other                                 => fail(s"not SourceFailed: $other")
      }
    }

    test("recover folds the failure of an IO into the state") {
      val app = Apps.tally(t =>
        if t.count > 0 then Nil
        else
            Seq(
              source
                .task(IO.raiseError[Int](new RuntimeException("x")))(
                  _.add(_, 1),
                )
                .recover((t, _) => Quit(t.copy(count = 42))),
            ),
      )
      run(app).map(t => assertEquals(t.count, 42))
    }

    test("a Cats source in an app that a Kyo runner runs fails with a hint") {
      val app = Apps.tally(_ => Seq(source.task(IO(1))(_.add(_, 1)).id("t")))
      tui4s.interop.cats.internal.CatsBridge
        .runF[IO, Tally](
          tui4s.internal.Runner
            .run(app, RecordingControl(), Some(ScriptedInput.never)),
        )
        .attempt
        .map {
          case Left(TuiError.SourceFailed("t", e)) =>
            assert(e.getMessage.contains("tui4s.interop.cats.Tui.run"), e)
          case other                               => fail(s"not SourceFailed: $other")
        }
    }
end SourceSuite
