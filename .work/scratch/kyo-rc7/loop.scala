//> using scala 3.9.0
//> using dep io.getkyo::kyo-core:1.0.0-RC7
//> using mainClass tui4s.loop.loopMain

// Event-loop skeleton for tui4s on Kyo 1.0.0-RC7 (no TTY: a fake blocking event source stands in for crossterm).
// Run:
//   source .work/env.sh && export JDK_JAVA_OPTIONS=-Xss16m
//   scala-cli run --server=false .work/scratch/kyo-rc7/loop.scala
//
// Shape (one Scope):
//   input fiber : Loop { poll(50ms) ; if ready read() (+ every event already queued) ; channel.putBatch }   (backpressure)
//   tick fiber  : Loop { sleep(tick) ; channel.offer(Tick) }                                             (drop if full)
//   render loop : take (park) -> drain (coalesce) -> update Var[Model] -> if changed and frame budget
//                 allows: draw ; else sleep the rest of the frame, drain again, then draw.
//   shutdown    : Quit event -> render loop returns -> Scope closes (fibers interrupted, channel closed);
//                 external interrupt -> same finalizers. Terminal restore is a Sync.ensure (synchronous release:
//                 done before the fiber's result is visible), NOT a Scope finalizer (Scope finalizers are drained
//                 on a detached fiber after an interrupt, see .work/reports/kyo-rc7.md F8).
package tui4s.loop

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kyo.*

// ------------------------------------------------------------------------------------------------
// Events, model, pure update
// ------------------------------------------------------------------------------------------------

enum Ev derives CanEqual:
    case Key(c: Char)
    case Resize(w: Int, h: Int)
    case Tick
    case Quit

final case class Model(keys: Int, last: Char, width: Int, height: Int, ticks: Int) derives CanEqual:
    def update(ev: Ev): Model = ev match
        case Ev.Key(c)       => copy(keys = keys + 1, last = c)
        case Ev.Resize(w, h) => copy(width = w, height = h)
        case Ev.Tick         => copy(ticks = ticks + 1)
        case Ev.Quit         => this
end Model
object Model:
    val initial: Model = Model(0, ' ', 80, 24, 0)

// ------------------------------------------------------------------------------------------------
// Fake blocking event source with crossterm's shape: poll(timeout): Boolean ; read(): Ev (blocks)
// ------------------------------------------------------------------------------------------------

final class FakeEventSource:
    private val queue          = new LinkedBlockingQueue[Ev]()
    private var peeked: Ev | Null = null // single consumer: the input fiber
    def push(e: Ev): Unit      = queue.put(e)
    def pushAll(es: Iterable[Ev]): Unit = es.foreach(queue.put)

    /** Like crossterm::event::poll: blocks up to `timeoutMs`, true if an event can be read without blocking. */
    def poll(timeoutMs: Long): Boolean =
        if peeked != null then true
        else
            val e = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if e == null then false
            else
                peeked = e
                true
    end poll

    /** Like crossterm::event::read: blocks until an event is available. */
    def read(): Ev =
        val p = peeked
        if p != null then
            peeked = null
            p
        else queue.take()
    end read
end FakeEventSource

// ------------------------------------------------------------------------------------------------
// Config, instrumentation
// ------------------------------------------------------------------------------------------------

enum InputMode derives CanEqual:
    case BlockingPoll // Kyo fiber: Sync.defer(poll(pollMs)) blocks a Kyo worker up to pollMs
    case SleepPoll    // Kyo fiber: Sync.defer(poll(0)); if nothing, Async.sleep(sleepPollMs) (worker free, interruptible)
    case Thread       // dedicated daemon platform thread: poll(pollMs)/read(), putBatchFiber + wait (backpressure)

final case class Cfg(
    pollMs: Long = 50,
    sleepPollMs: Long = 10,
    frameBudget: Duration = 16.millis,
    tick: Maybe[Duration] = Absent,
    capacity: Int = 1024,
    input: InputMode = InputMode.BlockingPoll,
    batchInput: Boolean = true, // read every already-queued event per wake-up and putBatch them
    coalesce: Boolean = true,   // false: render after every single event (measures raw per-iteration cost)
    verbose: Boolean = false
)

final class Stats:
    val frames      = new AtomicLong(0)
    val events      = new AtomicLong(0)
    val wakeups     = new AtomicLong(0)
    val lastFrame   = new AtomicReference[Model](Model.initial)
    val restores    = new AtomicLong(0)
    @volatile var firstWakeNs = 0L
    @volatile var quitNs      = 0L
    private val t0  = System.nanoTime()
    def log(msg: String): Unit =
        println(f"  [${(System.nanoTime() - t0) / 1e6}%8.2f ms] $msg")
end Stats

// ------------------------------------------------------------------------------------------------
// Fibers
// ------------------------------------------------------------------------------------------------

private def readBatch(src: FakeEventSource): Chunk[Ev] =
    val b = Chunk.newBuilder[Ev]
    b += src.read()
    var n = 1
    while n < 512 && src.poll(0) do
        b += src.read()
        n += 1
    b.result()
end readBatch

def inputLoop(src: FakeEventSource, ch: Channel[Ev], cfg: Cfg, stats: Stats)(using Frame): Unit < (Async & Abort[Closed]) =
    def deliver(ready: Boolean): Unit < (Async & Abort[Closed]) =
        if !ready then Kyo.unit
        else if cfg.batchInput then Sync.defer(readBatch(src)).map(batch => ch.putBatch(batch))
        else Sync.defer(src.read()).map(ch.put)
    Sync.ensure(Sync.defer(if cfg.verbose then stats.log("input fiber stopped (Sync.ensure in the input fiber)"))) {
        cfg.input match
            case InputMode.SleepPoll =>
                Loop.forever(Sync.defer(src.poll(0)).map(ready => if ready then deliver(true) else Async.sleep(cfg.sleepPollMs.millis)))
            case _ =>
                // poll with a timeout so an interrupt lands between polls; a JNI read cannot be interrupted.
                Loop.forever(Sync.defer(src.poll(cfg.pollMs)).map(deliver))
    }
end inputLoop

/** InputMode.Thread: a daemon platform thread owns the blocking source; stopped by a flag, joined in the release. */
def inputThread(src: FakeEventSource, ch: Channel[Ev], cfg: Cfg, stats: Stats)(using Frame): Unit < (Scope & Sync) =
    Scope.acquireRelease(Sync.Unsafe.defer {
        val running = new java.util.concurrent.atomic.AtomicBoolean(true)
        val t = new java.lang.Thread(
            () =>
                try
                    while running.get() do
                        if src.poll(cfg.pollMs) then
                            val batch = readBatch(src)
                            // backpressure: wait until the channel accepted the batch (or closed)
                            val done  = new java.util.concurrent.CountDownLatch(1)
                            ch.unsafe.putBatchFiber(batch).onComplete(_ => done.countDown())
                            done.await()
                    end while
                catch case _: InterruptedException => ()
                finally if cfg.verbose then stats.log("input thread stopped"),
            "tui4s-input"
        )
        t.setDaemon(true)
        t.start()
        (t, running)
    }) { (t, running) =>
        Sync.defer {
            running.set(false)
            t.join(cfg.pollMs * 4)
        }
    }.unit
end inputThread

def tickLoop(ch: Channel[Ev], every: Duration)(using Frame): Unit < (Async & Abort[Closed]) =
    // A tick is only a wake-up: drop it when the channel is full instead of blocking.
    Loop.forever(Async.sleep(every).andThen(ch.offerDiscard(Ev.Tick)))

/** Applies a batch; returns true when a Quit was seen (events after Quit are ignored). */
def applyBatch(batch: Chunk[Ev], stats: Stats)(using Frame): Boolean < Var[Model] =
    Var.use[Model] { m0 =>
        var m    = m0
        var quit = false
        val it   = batch.iterator
        while !quit && it.hasNext do
            val ev = it.next()
            if ev == Ev.Quit then quit = true else m = m.update(ev)
        end while
        if quit then stats.quitNs = System.nanoTime()
        discard(stats.events.addAndGet(batch.size.toLong))
        Var.setDiscard(m).andThen(quit)
    }

private inline def discard[A](inline v: => A): Unit =
    val _ = v
    ()

def draw(model: Model, stats: Stats, cfg: Cfg)(using Frame): Unit < Sync =
    // Stand-in for terminal.draw(frame => ...): one Sync step per frame.
    Sync.defer {
        val n = stats.frames.incrementAndGet()
        stats.lastFrame.set(model)
        if cfg.verbose then stats.log(s"draw #$n $model")
    }

def renderLoop(ch: Channel[Ev], cfg: Cfg, stats: Stats)(using Frame): Unit < (Var[Model] & Async & Abort[Closed]) =
    val budgetNanos = cfg.frameBudget.toNanos
    // Loop state: (monotonic nanos of the last draw, the model last drawn)
    Loop(0L, Model.initial) { (lastDrawAt, lastDrawn) =>
        ch.take.map { first =>
            if stats.wakeups.incrementAndGet() == 1 then stats.firstWakeNs = System.nanoTime()
            val pending: Chunk[Ev] < (Sync & Abort[Closed]) =
                if cfg.coalesce then ch.drain.map(rest => Chunk(first).concat(rest)) else Chunk(first)
            pending.map(batch => applyBatch(batch, stats)).map { quit =>
                if quit then Loop.done(())
                else
                    Var.get[Model].map { model =>
                        if model == lastDrawn then Loop.continue(lastDrawAt, lastDrawn) // nothing visible changed
                        else
                            val now  = System.nanoTime()
                            val wait = lastDrawAt + budgetNanos - now
                            if wait <= 0 || !cfg.coalesce then
                                draw(model, stats, cfg).andThen(Loop.continue(now, model))
                            else
                                // Too early for a new frame: let more events arrive, fold them in, then draw once.
                                Async.sleep(Duration.fromNanos(wait))
                                    .andThen(ch.drain)
                                    .map(more => applyBatch(more, stats))
                                    .map { quit2 =>
                                        if quit2 then Loop.done(())
                                        else
                                            Var.get[Model].map(m2 =>
                                                draw(m2, stats, cfg).andThen(Loop.continue(System.nanoTime(), m2))
                                            )
                                    }
                            end if
                    }
            }
        }
    }
end renderLoop

/** The whole app: terminal setup/restore around one Scope that owns the channel and the fibers. */
def app(src: FakeEventSource, cfg: Cfg, stats: Stats)(using Frame): Model < (Async & Abort[Closed]) =
    Sync.defer(if cfg.verbose then stats.log("enter raw mode + alternate screen (stand-in)")).andThen {
        Sync.ensure(Sync.defer {
            discard(stats.restores.incrementAndGet())
            if cfg.verbose then stats.log("RESTORE TERMINAL: leave alternate screen, disable raw mode (Sync.ensure)")
        }) {
            Scope.run {
                for
                    _  <- Scope.ensure(Sync.defer(if cfg.verbose then stats.log("Scope finalizer: event source released (Scope.ensure)")))
                    ch <- Channel.init[Ev](cfg.capacity, Access.MultiProducerSingleConsumer)
                    _  <- if cfg.input == InputMode.Thread then inputThread(src, ch, cfg, stats)
                          else Fiber.init(inputLoop(src, ch, cfg, stats)).unit
                    _  <- cfg.tick match
                        case Present(every) => Fiber.init(tickLoop(ch, every)).unit
                        case Absent         => Kyo.unit
                    result <- Var.runTuple(Model.initial)(renderLoop(ch, cfg, stats))
                yield result._1
            }
        }
    }

// ------------------------------------------------------------------------------------------------
// Scenarios
// ------------------------------------------------------------------------------------------------

@main def loopMain(args: String*): Unit =
    import AllowUnsafe.embrace.danger
    def runK[A](v: A < (Async & Scope & Abort[Any]), timeout: Duration = 60.seconds): A =
        KyoApp.Unsafe.runAndBlock(timeout)(v).getOrThrow
    val only = args.headOption.getOrElse("all")

    // ---------- 1. Quit event: scripted session ----------
    if only == "all" then
        println("== 1. scripted session ending with Quit (tick every 30 ms) ==")
        val src   = new FakeEventSource
        val stats = new Stats
        val cfg   = Cfg(tick = Present(30.millis), verbose = true)
        val model = runK {
            for
                app <- Fiber.initUnscoped(app(src, cfg, stats))
                _   <- Async.sleep(50.millis)
                _   <- Sync.defer(src.pushAll(Seq(Ev.Key('h'), Ev.Key('i'), Ev.Resize(100, 40))))
                _   <- Async.sleep(40.millis)
                _   <- Sync.defer(src.pushAll((1 to 50).map(i => Ev.Key(('a' + i % 26).toChar))))
                _   <- Async.sleep(40.millis)
                _   <- Sync.defer(src.push(Ev.Quit))
                m   <- app.get
            yield m
        }
        stats.log(s"app returned: $model ; frames=${stats.frames.get} events=${stats.events.get} wakeups=${stats.wakeups.get} restores=${stats.restores.get}")
    end if

    // ---------- 2. External interruption ----------
    if only == "all" then
        for mode <- InputMode.values do
            println(s"== 2. external interruption, input=$mode (interruptAwait after 150 ms; ticks every 40 ms) ==")
            val src   = new FakeEventSource
            val stats = new Stats
            val cfg   = Cfg(tick = Present(40.millis), input = mode, verbose = true)
            val r = runK {
                for
                    fiber <- Fiber.initUnscoped(app(src, cfg, stats))
                    _     <- Async.sleep(150.millis)
                    _     <- Sync.defer(stats.log(s"interrupting app fiber"))
                    _     <- fiber.interruptAwait
                    _     <- Sync.defer(stats.log(s"interruptAwait returned (restores=${stats.restores.get})"))
                    res   <- fiber.getResult
                yield res
            }
            stats.log(s"app fiber result: ${r.toString.take(60)}")
            Thread.sleep(250)
            stats.log("(+250 ms, anything detached has printed by now)")
        end for
    end if

    // ---------- 3. Throughput / per-iteration overhead, 10k events ----------
    def bench(label: String, cfg: Cfg, n: Int = 10000, rounds: Int = 9): Unit =
        val results = (1 to rounds).map { round =>
            val src   = new FakeEventSource
            val stats = new Stats
            src.pushAll((1 to n).map(i => Ev.Key(('a' + i % 26).toChar)))
            src.push(Ev.Quit)
            val t0    = System.nanoTime()
            val model = runK(app(src, cfg, stats))
            val t1    = System.nanoTime()
            assert(model.keys == n, s"lost events: ${model.keys}")
            assert(stats.restores.get == 1)
            // (processing = first wake-up .. Quit applied, shutdown = Quit applied .. app returned, total)
            (stats.quitNs - stats.firstWakeNs, t1 - stats.quitNs, t1 - t0, stats.frames.get, stats.wakeups.get)
        }
        val warm   = results.drop(3) // JIT warm-up
        def med(f: ((Long, Long, Long, Long, Long)) => Long) = warm.map(f).sorted.apply(warm.size / 2)
        val proc   = med(_._1)
        println(f"  $label%-58s processing=${proc / 1e6}%6.2f ms (${proc.toDouble / n}%5.0f ns/event) shutdown=${med(_._2) / 1e6}%6.2f ms total=${med(_._3) / 1e6}%6.2f ms frames=${warm.last._4} wakeups=${warm.last._5}")
    end bench
    if only == "all" || only == "bench" then
        println("== 3. 10k key events pre-queued, then Quit (median of 6 warm rounds) ==")
        bench("blocking poll, batched input, coalescing (production shape)", Cfg())
        bench("blocking poll, single put per event, coalescing", Cfg(batchInput = false))
        bench("blocking poll, batched, NO coalescing (draw every event)", Cfg(coalesce = false, frameBudget = Duration.Zero))
        bench("blocking poll, single put, NO coalescing (1 event = 1 iter)", Cfg(batchInput = false, coalesce = false, frameBudget = Duration.Zero))
        bench("sleep poll (10 ms), batched, coalescing", Cfg(input = InputMode.SleepPoll))
        bench("platform thread, batched, coalescing", Cfg(input = InputMode.Thread))
        bench("platform thread, batched, NO coalescing", Cfg(input = InputMode.Thread, coalesce = false, frameBudget = Duration.Zero))
    end if

    // ---------- 4. Idle CPU: no events ----------
    if only == "all" || only == "idle" then
        println("== 4. idle cost over 3 s, no events (process CPU time / wall) ==")
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
        def measure(label: String)(start: => Unit < (Async & Abort[Closed]), stop: => Unit < Sync): Unit =
            val (cpu, wall) = runK {
                for
                    f    <- Fiber.initUnscoped(start)
                    _    <- Async.sleep(300.millis) // settle
                    cpu0 <- Sync.defer(os.getProcessCpuTime)
                    t0   <- Sync.defer(System.nanoTime())
                    _    <- Async.sleep(3.seconds)
                    cpu1 <- Sync.defer(os.getProcessCpuTime)
                    t1   <- Sync.defer(System.nanoTime())
                    _    <- stop
                    _    <- f.get
                yield (cpu1 - cpu0, t1 - t0)
            }
            println(f"  $label%-48s cpu=${cpu / 1e6}%7.1f ms / ${wall / 1e6}%5.0f ms wall = ${100.0 * cpu / wall}%5.2f%% of one core")
        end measure
        val stopFlag = new java.util.concurrent.atomic.AtomicBoolean(false)
        {
            // plain JVM baseline: no Kyo work, the main thread sleeps
            val cpu0 = os.getProcessCpuTime; val t0 = System.nanoTime()
            Thread.sleep(3000)
            val cpu = os.getProcessCpuTime - cpu0; val wall = System.nanoTime() - t0
            println(f"  ${"JVM main thread sleeping (Kyo already loaded)"}%-48s cpu=${cpu / 1e6}%7.1f ms / ${wall / 1e6}%5.0f ms wall = ${100.0 * cpu / wall}%5.2f%% of one core")
        }
        measure("Kyo runtime only (one fiber in Async.sleep)")(Async.sleep(3500.millis), Kyo.unit)
        for (label, cfg) <- Seq(
                "app, input=BlockingPoll(50 ms)"          -> Cfg(),
                "app, input=SleepPoll(10 ms)"             -> Cfg(input = InputMode.SleepPoll),
                "app, input=Thread(poll 50 ms)"           -> Cfg(input = InputMode.Thread),
                "app, input=BlockingPoll + tick 250 ms"   -> Cfg(tick = Present(250.millis))
            )
        do
            val src = new FakeEventSource
            measure(label)(app(src, cfg, new Stats).unit, Sync.defer(src.push(Ev.Quit)))
        end for
    end if
    // ---------- 5. Micro: raw Kyo costs per iteration (no fake source) ----------
    if only == "all" || only == "micro" then
        println("== 5. micro benchmarks, n = 10k per round, best and median of 20 rounds ==")
        val n = 10000
        def time(label: String)(v: => Any < (Async & Abort[Closed])): Unit =
            val ts = (1 to 20).map { _ =>
                val t0 = System.nanoTime()
                discard(runK(v))
                System.nanoTime() - t0
            }.drop(5).sorted
            println(f"  $label%-62s best=${ts.head.toDouble / n}%6.0f ns/iter median=${ts(ts.size / 2).toDouble / n}%6.0f ns/iter")
        end time
        time("Loop + Var.update (pure, one fiber)") {
            Var.run(Model.initial)(Loop.indexed(i => if i < n then Var.updateDiscard[Model](_.update(Ev.Key('x'))).andThen(Loop.continue) else Loop.done(())))
        }
        time("Loop + Var.update + Sync.defer(draw stand-in)") {
            val c = new AtomicLong(0)
            Var.run(Model.initial)(Loop.indexed(i =>
                if i < n then Var.updateDiscard[Model](_.update(Ev.Key('x'))).andThen(Sync.defer(discard(c.incrementAndGet()))).andThen(Loop.continue)
                else Loop.done(())
            ))
        }
        val keys = Chunk.from((1 to n).map(i => Ev.Key(('a' + i % 26).toChar): Ev))
        time("Channel: producer putBatch(512) / consumer take one by one") {
            Channel.initUnscoped[Ev](1024, Access.SingleProducerSingleConsumer).map { ch =>
                Fiber.initUnscoped(Kyo.foreachDiscard(keys.grouped(512).toSeq)(b => ch.putBatch(b))).andThen(
                    Loop.indexed(i => if i < n then ch.take.andThen(Loop.continue) else Loop.done(()))
                )
            }
        }
        time("Channel: producer put one by one / consumer take one by one") {
            Channel.initUnscoped[Ev](1024, Access.SingleProducerSingleConsumer).map { ch =>
                Fiber.initUnscoped(Kyo.foreachDiscard(keys)(ch.put)).andThen(
                    Loop.indexed(i => if i < n then ch.take.andThen(Loop.continue) else Loop.done(()))
                )
            }
        }
        time("Channel: producer putBatch(512) / consumer take + drain") {
            Channel.initUnscoped[Ev](1024, Access.SingleProducerSingleConsumer).map { ch =>
                Fiber.initUnscoped(Kyo.foreachDiscard(keys.grouped(512).toSeq)(b => ch.putBatch(b))).andThen(
                    Loop(0)(got => if got < n then ch.take.andThen(ch.drain).map(rest => Loop.continue(got + 1 + rest.size)) else Loop.done(()))
                )
            }
        }
    end if
    sys.exit(0)
end loopMain
