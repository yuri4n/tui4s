//> using scala 3.9.0
//> using dep io.getkyo::kyo-core:1.0.0-RC7
//> using dep io.getkyo::kyo-zio:1.0.0-RC7
//> using dep dev.zio::zio:2.1.26
//> using dep org.typelevel::cats-effect:3.7.1
//> using file ../CatsBridge.scala
//> using mainClass probe

// Kyo 1.0.0-RC7 API probe for tui4s, FIXED for RC7 + JDK 25 (2026-09-30).
// Changes vs .work/scratch/kyo-probe.scala:
//  - kyo-cats 1.0.0-RC5 removed (binary-incompatible with kyo-core RC7: NoSuchMethodError Safepoint$.get());
//    Cats.run/Cats.get replaced by the vendored tui4s.interop.CatsBridge (../CatsBridge.scala);
//  - cats-effect 3.7.0 -> 3.7.1.
// Needs a big compiler stack (default 1 MB overflows in the staging phase):
//   source .work/env.sh && export JDK_JAVA_OPTIONS=-Xss16m
//   scala-cli run --server=false .work/scratch/kyo-rc7/probe/kyo-probe.scala
import tui4s.interop.CatsBridge
import kyo.*

// ---------- Plain data used by the probe ----------
final case class Config(title: String)
enum Ev derives CanEqual:
    case Key(c: Char)
    case Tick
    case Quit

// ---------- 1. Var: a counter, pure state ----------
val counter: Int < Var[Int] =
    for
        _ <- Var.update[Int](_ + 1)
        _ <- Var.updateDiscard[Int](_ + 10)
        n <- Var.get[Int]
    yield n

// ---------- 2. Emit: push events, then collect or fold ----------
val emitter: String < Emit[Ev] =
    Emit.value[Ev](Ev.Key('a'))
        .andThen(Emit.value[Ev](Ev.Tick))
        .andThen(Emit.value[Ev](Ev.Key('b')))
        .andThen("emitted")

// Emit + Var together: an update loop that folds events into state.
def reduce(ev: Ev): Unit < Var[Int] = ev match
    case Ev.Key(_) => Var.updateDiscard[Int](_ + 1)
    case _         => ()

// ---------- 3. Stream: init, map/filter/fold/run, polling source ----------
val evens: Stream[Int, Any] = Stream.init(1 to 10).map(_ * 2).filter(_ % 4 == 0)

// A polling source: drains a mutable Java queue until empty. chunkSize = 1 so each item is emitted
// immediately (the default 4096 makes rechunk buffer until 4096 items or end-of-stream).
def pollingStream(q: java.util.concurrent.ConcurrentLinkedQueue[Ev]): Stream[Ev, Sync] =
    Stream.repeatPresent[Ev, Sync](Sync.defer(Maybe(q.poll()).map(Seq(_))), chunkSize = 1)

// ---------- 4. Env + Abort ----------
val needsConfig: String < (Env[Config] & Abort[String]) =
    Env.use[Config] { cfg =>
        if cfg.title.isEmpty then Abort.fail("empty title") else cfg.title.toUpperCase
    }

// ---------- 5. Scope: acquire/release a resource (e.g. raw mode / alt screen) ----------
final class FakeTerminal:
    @volatile var open = true
    def close(): Unit  = open = false

def terminalResource(log: AtomicRef[Chunk[String]]): FakeTerminal < (Scope & Sync) =
    Scope.acquireRelease(Sync.defer { new FakeTerminal })(t =>
        Sync.defer(t.close()).andThen(log.updateAndGet(_.append("released")).unit)
    )

// ---------- 6. Async + Fiber + Channel + Signal + Clock + Loop + Latch + Meter ----------
def asyncPart(log: AtomicRef[Chunk[String]]): String < (Async & Abort[Closed] & Scope) =
    for
        start   <- Clock.stopwatch
        channel <- Channel.init[Ev](16)                       // scoped: closed when Scope closes
        latch   <- Latch.init(1)
        producer <- Fiber.init {                              // scoped fiber: interrupted at Scope close
            Kyo.foreachDiscard(Seq(Ev.Key('x'), Ev.Tick, Ev.Key('y'), Ev.Quit))(e =>
                Async.sleep(5.millis).andThen(channel.put(e))
            ).andThen(latch.release)
        }
        // Consume until Quit with Loop (state = count of keys)
        keys <- Loop(0) { n =>
            channel.take.map {
                case Ev.Quit   => Loop.done(n)
                case Ev.Key(_) => Loop.continue(n + 1)
                case Ev.Tick   => Loop.continue(n)
            }
        }
        _       <- latch.await
        // Signal: reactive value, streamChanges drops duplicates
        sig     <- Signal.initRef(0)
        watcher <- Fiber.init(sig.streamChanges.take(3).run)
        _       <- Async.sleep(20.millis)                    // let the watcher subscribe
        _       <- sig.set(1)
        _       <- Async.sleep(20.millis)
        _       <- sig.set(1)                                // same value: no notification
        _       <- sig.set(2)
        seen    <- watcher.get
        // timeout + race
        slow    <- Abort.run[Timeout](Async.timeout(10.millis)(Async.sleep(1.second).andThen("slow")))
        winner  <- Async.race(Async.sleep(50.millis).andThen("tortoise"), Async.sleep(1.millis).andThen("hare"))
        par     <- Async.zip(Async.sleep(10.millis).andThen(1), Async.sleep(10.millis).andThen(2))
        // Meter as a mutex
        mutexed <- Meter.useMutex(m => m.run(Sync.defer("in-mutex")))
        idx     <- Loop.indexed(i => if i < 5 then Loop.continue else Loop.done(i))
        elapsed <- start.elapsed
        _       <- log.updateAndGet(_.append(s"async elapsed ~${elapsed.toMillis}ms"))
    yield s"keys=$keys signal=$seen timeout=${slow.isFailure} race=$winner zip=$par mutex=$mutexed loopIdx=$idx"

// ---------- 7. A full "app" program: Async & Scope & Abort ----------
def kyoProgram: String < (Async & Abort[Throwable]) =
    AtomicRef.init(Chunk.empty[String]).map { log =>
        Scope.run {
            for
                term    <- terminalResource(log)
                counted <- Var.runTuple(0)(counter)
                (evs, r) <- Emit.run[Ev](emitter)
                folded  <- Var.run(0)(Kyo.foreachDiscard(evs)(reduce).andThen(Var.get[Int]))
                viaFold <- Emit.runFold[Ev](0)((acc, ev) => if ev == Ev.Tick then acc + 1 else acc)(emitter)
                stream  <- evens.run
                sum     <- evens.fold(0)(_ + _)
                q = java.util.concurrent.ConcurrentLinkedQueue[Ev](java.util.List.of(Ev.Key('p'), Ev.Tick, Ev.Quit))
                polled  <- pollingStream(q).takeWhile(_ != Ev.Quit).run
                cfg     <- Abort.run[String](Env.run(Config("tui4s"))(needsConfig))
                bad     <- Abort.run[String](Env.run(Config(""))(needsConfig))
                asyncR  <- Abort.run[Closed](asyncPart(log))
                open    = term.open
            yield s"""counter=$counted emitted=$evs/$r folded=$folded ticks=$viaFold
                     |stream=$stream sum=$sum polled=$polled
                     |env=$cfg bad=$bad
                     |async=$asyncR termOpenInsideScope=$open""".stripMargin
        }.map(s => log.get.map(l => s + "\nlog=" + l))
    }

// ---------- 8. KyoApp entry point (not the default main here) ----------
object ProbeApp extends KyoApp:
    run {
        kyoProgram.map(s => Console.printLine(s"[KyoApp] $s"))
    }

// ---------- 9. Default main: run from Kyo, from ZIO and from cats-effect ----------
@main def probe(): Unit =
    import AllowUnsafe.embrace.danger
    // 9a. Kyo's own runner (blocking, only at the edge)
    val r1 = KyoApp.Unsafe.runAndBlock(10.seconds)(kyoProgram)
    println(s"[Kyo] $r1")

    // 9b. Run a Kyo program from ZIO; lift a ZIO into Kyo
    val zioProg: zio.ZIO[Any, Throwable, String] =
        ZIOs.run {
            ZIOs.get(zio.ZIO.succeed(21)).map(n => Async.sleep(5.millis).andThen(s"zio-bridge ${n * 2}"))
        }.zipWith(ZIOs.run(kyoProgram))((a, b) => s"$a | ${b.linesIterator.next()}")
    val r2 = zio.Unsafe.unsafe(implicit u => zio.Runtime.default.unsafe.run(zioProg).getOrThrowFiberFailure())
    println(s"[ZIO] $r2")

    // 9c. Run a Kyo program from cats-effect IO; lift an IO into Kyo
    import cats.effect.unsafe.implicits.global
    val ceProg: cats.effect.IO[String] =
        CatsBridge.run {
            CatsBridge.get(cats.effect.IO.pure(20)).map(n => Async.sleep(5.millis).andThen(s"ce-bridge ${n + 1}"))
        }.flatMap(a => CatsBridge.run(kyoProgram).map(b => s"$a | ${b.linesIterator.next()}"))
    val r3 = ceProg.unsafeRunSync()
    println(s"[CE] $r3")
end probe
