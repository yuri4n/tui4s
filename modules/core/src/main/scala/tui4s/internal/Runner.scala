package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import kyo.*
import kyo.UI.Keyboard
import tui4s.spi.Settle
import tui4s.{FrameCause, FrameStats, TuiError}

/** The runner: one fiber that owns the world and the canvas.
  *
  * Each step takes one message (a key, a resize, or a region's new value),
  * applies it (the key handlers run here, through [[Dispatch]]), then SETTLES:
  * it applies every queued region value until the settle rule of
  * [[tui4s.spi.Settle]] says that the consequences are in (default: the Kyo
  * scheduler is quiescent, twice, one hop apart, each time before an empty
  * drain). Only then does it lay out what changed, paint it into the kept
  * buffer and flush the changed cells: one frame per event, with all of that
  * event's consequences in it.
  */
// claude 2026-10-13: changed: the Scaladoc names the dispatcher (C3)
// claude 2026-10-19: changed: built by `Runner.init`, with the checked
// options (RunOptions, A10), the gauge and the quit flag of the run (P2, A7)
private[tui4s] final class Runner private (
    inbox: Channel[Msg],
    canvas: Canvas,
    runOpts: RunOptions,
    stats: Maybe[Channel[FrameStats]],
    knobs: Knobs,
    gauge: FiberGauge,
    quitRequested: AtomicBoolean,
):
    private val options = runOpts.options

    // claude 2026-10-19: changed: the mount gets the gauge (P2, A7)
    private val mount = Mount(inbox, gauge, knobs)

    /** The monotonic time in nanoseconds. Its value can be negative: the runner
      * uses only differences. The Kyo `Clock` is not used here, because it
      * gives 0 for a negative `System.nanoTime`.
      */
    // claude 2026-10-19: changed: one source of time for the runner, inside
    // the effect (P2, skeptic: not the Kyo Clock, R1-3, R2-3)
    private def nanos(using Frame): Long < Sync =
      Sync.defer(java.lang.System.nanoTime()) // scalafix:ok DisableSyntax.jvmGlobal; KEEP: Kyo's Clock maps a negative nanoTime to 0 (R1-3, R2-3)

    /** The fibers of this run that have not ended: a gauge for tests (R1). */
    // claude 2026-10-19: changed: the FiberGauge (P2, A7)
    private[tui4s] def liveFibers(using Frame): Int < Sync = gauge.live

    /** Stops every fiber of the run: the close of the gauge interrupts the
      * watchers, the component and hook fibers and the repair tick, also the
      * ones that a step started inside a settle or a verify pass. A fiber that
      * starts after the close is interrupted at once. It does not wait.
      */
    // claude 2026-10-19: changed: only the close of the gauge; the last
    // world, the `stopped` flag and the tick handle are gone (P2, A7)
    private def stop(using Frame): Unit < Sync = gauge.close

    /** Asks the run to end: it posts the quit message and sets a flag that the
      * next step reads, in case the inbox is full and drops the message.
      */
    // claude 2026-10-19: changed: a Kyo AtomicBoolean (P2)
    def quit(using Frame): Unit < Sync =
      quitRequested
        .set(true)
        .andThen(Abort.run[Closed](inbox.offer(Msg.Quit)).unit)

    // claude 2026-10-13: created (C3): the key routing; its handlers get the
    // runner's quit; the knobs carry the hook of the write seam
    private val dispatch = Dispatch(quit, knobs)

    /** Runs `v` with the quit of this run in scope, for [[tui4s.Tui.quit]]: the
      * construction of the UI, and the fibers that it starts. The live-fiber
      * gauge of this run is in scope too ([[Tui4sTrack]]), so a fiber that a
      * hook starts ([[tui4s.Hooks]]) joins it and stops when the run ends.
      */
    // claude 2026-10-19: changed: the FiberGauge (P2, A7)
    def scoped[A, S](v: => A < S)(using Frame): A < S =
      Tui4sQuit.let(quit)(Tui4sTrack.let(gauge)(v))

    // TODO(#8): remove the repair tick when the watchers go back to
    // `Signal.observe` (it has its own repair timer).
    // https://github.com/yuri4n/tui4s/issues/8
    /** The repair tick: every `knobs.repairInterval` (default 1 s, Kyo's
      * `Signal.defaultRepairInterval`) it asks the runner to re-read `current`
      * of every region, so that a write the watcher loop missed reaches the
      * screen within about one interval (a tick that arrives inside a settle is
      * kept for the next step). The close of the gauge ends the tick; a closed
      * inbox ends it too.
      */
    // claude 2026-10-19: changed: no `stopped` flag: the gauge ends it (P2)
    private def repairTick(using Frame): Unit < Async =
      Abort
        .run[Closed](
          Loop.forever(
            Async.sleep(knobs.repairInterval).andThen(inbox.put(Msg.Repair)),
          ),
        )
        .unit

    /** Runs the UI until a quit. The close of the gauge runs in a `Sync.ensure`
      * around the run, so a run that ends in any way (a quit, a failure, an
      * interrupt) stops its fibers. It does not wait for them: a run-owned
      * `Scope` waits for a CPU-bound watcher and breaks the end order (spike
      * S2, gates.md G2).
      */
    // claude 2026-10-19: changed: the gauge starts the tick; no `remember`
    // of each world (P2, A7)
    def run(root: UI)(using Frame): Unit < Async =
      scoped(Sync.ensure(stop) {
        for
            _   <- gauge.fork(repairTick)
            t0  <- nanos
            w0  <- mount.walk(
                     root,
                     Loc.root,
                     World.init(root, canvas.size),
                     Map.empty,
                   )
            // the initial mount is settled and verified before the Initial
            // frame (D-16, armkey)
            w0s <- settleAndVerify(w0, input = false)
            w1  <- frame(w0s, 0L, FrameCause.Initial, t0)
            _   <- Loop(w1)(step)
        yield ()
      })

    private def step(
        w: World,
    )(using Frame): Loop.Outcome[World, World] < Async =
      // a quit that the full inbox dropped ends the run here (review R2-8 b)
      // claude 2026-10-19: changed: the Kyo AtomicBoolean; no `remember` (P2)
      quitRequested.get.map { q =>
        if w.quit || q then Loop.done(w)
        else
            next(w).map { (msg, w1) =>
              for
                  t0          <- nanos
                  // claude 2026-10-12: changed (D-16, armkey): the young
                  // regions shift by one step; the verify pass runs after
                  // the settle
                  w2          <- handle(
                                   w1.copy(
                                     updates = 0,
                                     repaired = 0,
                                     settleTimeouts = 0,
                                     youngPrev = w1.youngNow,
                                     youngNow = Set.empty,
                                   ),
                                   msg,
                                 )
                  t1          <- nanos
                  // claude 2026-10-13: changed (C3): a key and a paste are
                  // Input steps too, so the verify pass re-reads the unarmed
                  // regions
                  input        = msg match
                                     case Msg.Key(_, _)    => true
                                     case Msg.Paste(_, _)  => true
                                     case Msg.Resize(_, _) => true
                                     case _                => false
                  w3          <- settleAndVerify(w2, input)
                  t2          <- nanos
                  // claude 2026-10-13: changed (C3): a key and a paste give an
                  // Input frame
                  (seq, cause) = msg match
                                     case Msg.Key(_, s)    => (s, FrameCause.Input)
                                     case Msg.Paste(_, s)  =>
                                       (s, FrameCause.Input)
                                     case Msg.Resize(_, s) =>
                                       (s, FrameCause.Input)
                                     case _                => (0L, FrameCause.Background)
                  w4          <- frame(w3, seq, cause, t0, t1 - t0, t2 - t1)
              yield Loop.continue(w4)
            }
      }

    private def next(w: World)(using Frame): (Msg, World) < Async =
      w.backlog.headOption match
          case Some(m) => (m, w.copy(backlog = w.backlog.tail))
          case None    =>
            Abort.run[Closed](inbox.take).map {
              case Result.Success(m) => (m, w)
              case _                 => (Msg.Quit, w)
            }

    // claude 2026-10-13: created (C3)
    private def isCtrlC(ev: UI.KeyboardEvent): Boolean =
      ev.key == Keyboard.Char('c') && ev.modifiers.ctrl

    private def handle(w: World, msg: Msg)(using Frame): World < Async =
      msg match
          // claude 2026-10-13: created the two cases below (C3): Ctrl-C quits
          // (TuiOptions.ctrlCQuits); every other key goes to the dispatcher
          case Msg.Key(ev, _) if options.ctrlCQuits && isCtrlC(ev) =>
            w.copy(quit = true)
          case Msg.Key(ev, _)                                      => dispatch.onKey(w, ev)
          // claude 2026-10-13: created (C3, RC-14 part): a paste is one edit
          // of the focused text input
          case Msg.Paste(text, _)                                  => dispatch.onPaste(w, text)
          // claude 2026-10-19: changed (P5): a failed resize ends the run
          case Msg.Resize(size, _)                                 =>
            Sync
              .defer(canvas.resize(size))
              .map(orFail)
              .andThen(w.copy(size = size, full = true))
          case c: Msg.Content                                      => apply(w, c)
          // claude 2026-10-12: created the two cases below (K29, D-16): a
          // watcher armed: verify its region one time; the repair tick
          // re-reads every region
          // claude 2026-10-19: changed: the region leaves World.unarmed (P2)
          case Msg.Armed(p, g)                                     => verifyRegion(w.armed(p, g), p, Some(g))
          case Msg.Repair                                          => repairAll(w)
          case Msg.Quit                                            => w.copy(quit = true)

    /** The settle, the verify pass, and the re-check (D-17 c): when the verify
      * pass repaired a value, its consequences (a remount, the watchers that
      * the write woke) get one more settle before the frame.
      */
    // claude 2026-10-12: created (RC-11)
    // claude 2026-10-13: changed (C4, P-6): one deadline for the wait for a
    // mounting component, for the settle and the re-check of this frame
    // claude 2026-10-13: changed (C4, review R1-3, R2-3): the start of the
    // frame, not an absolute deadline: the settle compares differences of
    // `System.nanoTime` (its value can be negative)
    private def settleAndVerify(w: World, input: Boolean)(using
        Frame,
    ): World < Async =
      nanos.map { t0 =>
        settle(w, t0).map { w1 =>
          verify(w1, input).map { w2 =>
            // TODO(#9): drop the re-check when Kyo closes the lost-wakeup
            // window of `Signal` (the conditions of D-17 c).
            // https://github.com/yuri4n/tui4s/issues/9
            if w2.repaired > w1.repaired then settle(w2, t0) else w2
          }
        }
      }

    /** The verify pass (D-16, armkey; spike S2 section 4.3). After the settle
      * it re-reads `current` of the regions mounted in this step or the step
      * before, and in an Input step (a key, a paste or a resize) also of the
      * regions whose watcher has not armed yet. A write that landed in the
      * window of a young watcher (between the mount's read of `current` and the
      * registration of `nextWith`) reaches this frame, not the repair tick. The
      * regions that a repair mounts are not young: no cascade (a `Reactive`
      * value with closures never compares equal).
      *
      * The pull check (D-17 c, spike S1 section 4.3) adds every bound and
      * `Foreach` region: a derived signal computes `current` synchronously, so
      * the re-read sees each write of the event, also one that the probe
      * missed. `Reactive` regions are not pulled: their value is a UI with
      * closures, so two renders never compare equal.
      */
    // claude 2026-10-12: created (RC-9, RC-11)
    // claude 2026-10-13: changed: the Scaladoc names the keys and the paste
    // (C3)
    private def verify(w: World, input: Boolean)(using Frame): World < Sync =
        // claude 2026-10-19: changed: a function of the World (P2)
        val unarmed = if input then w.unarmedRegions else Set.empty[Loc]
        // TODO(#9): drop the pull check when Kyo closes the lost-wakeup window
        // of `Signal` (the conditions of D-17 c).
        // https://github.com/yuri4n/tui4s/issues/9
        val pulled  = w.regions.collect {
          case (
                p,
                RegionState(_, Source.Bound(_) | Source.Many(_), _, _, _, _),
              ) =>
            p
        }
        val targets = (w.youngNow ++ w.youngPrev ++ unarmed ++ pulled).toSeq
        val keep    = w.youngNow
        Kyo
          .foldLeft(targets)(w)((acc, p) => verifyRegion(acc, p, None))
          .map(_.copy(youngNow = keep))
    end verify

    /** Re-reads `current` of every region and applies a value that differs (as
      * if its watcher had posted it): the repair of a lost wakeup. A `Reactive`
      * region whose UI holds closures never compares equal, so it is rendered
      * again each time (Kyo's `observe` repair timer does the same).
      */
    // claude 2026-10-12: created (K29)
    private def repairAll(w: World)(using Frame): World < Sync =
      Kyo.foldLeft(w.regions.keys.toSeq)(w)((acc, p) =>
        verifyRegion(acc, p, None),
      )

    /** Re-reads `current` of the region at `p` (of generation `gen`, when
      * given) and applies a value that differs; counts it in `repaired`.
      */
    // claude 2026-10-12: created (K29, D-16; the S2 armkey verifyRegion)
    private def verifyRegion(acc: World, p: Loc, gen: Option[Long])(using
        Frame,
    ): World < Sync =
      acc.regions.get(p).filter(r => gen.forall(_ == r.gen)) match
          case Some(RegionState(_, Source.One(sig), g, content, _, _))   =>
            sig.current.map { cur =>
              if content == Content.Single(cur) then acc
              else
                  apply(
                    acc.copy(repaired = acc.repaired + 1),
                    Msg.Content(p, g, cur),
                  )
            }
          case Some(RegionState(_, Source.Bound(ref), g, content, _, _)) =>
            ref.current.map { cur =>
              if content == Content.Value(cur) then acc
              else
                  apply(
                    acc.copy(repaired = acc.repaired + 1),
                    Msg.Content(p, g, cur),
                  )
            }
          case Some(RegionState(_, Source.Many(fe), g, _, items, _))     =>
            // claude 2026-10-19: changed: Mount.sameValues, no new vectors
            // (PF-a, F8)
            fe.signal.current.map { cur =>
              if Mount.sameValues(items, cur) then acc
              else
                  apply(
                    acc.copy(repaired = acc.repaired + 1),
                    Msg.Content(p, g, cur),
                  )
            }
          case _                                                         => acc

    /** A region's new value. Late messages of an unmounted watcher (old
      * generation) are dropped.
      */
    // claude 2026-10-13: changed (C4, the C2 cut back): a component region
    // takes its content as a `Reactive` region does; the content of a hook
    // component ends its wait (`pending`)
    private def apply(w: World, c: Msg.Content)(using Frame): World < Sync =
      w.regions.get(c.region) match
          case Some(r) if r.gen == c.gen =>
            r.source match
                case Source.One(_) | Source.Comp(_, _) =>
                  val content = Content.Single(c.value.asInstanceOf[UI]) // scalafix:ok DisableSyntax.asInstanceOf; KEEP: true by construction (Msg.Content)
                  if r.content == content then w
                  else
                      val w1 = w.copy(
                        regions = w.regions
                          .updated(c.region, r.copy(content = content)),
                        pending = w.pending - c.region,
                      )
                      mount.remount(
                        w1.markRegion(c.region).copy(updates = w1.updates + 1),
                        c.region,
                      )
                  end if
                // claude 2026-10-19: changed: the item type `a` of the Foreach
                // (Source.Many is typed, P1)
                case many: Source.Many[a]              =>
                  val items = Mount.reconcile(
                    many.fe,
                    r.items,
                    c.value.asInstanceOf[Chunk[a]].toVector, // scalafix:ok DisableSyntax.asInstanceOf; KEEP: true by construction (Msg.Content)
                  )
                  if items == r.items then w
                  else
                      val r1 = r.copy(
                        items = items,
                        content = Content.Many(items.map(it => (it.key, it.ui))),
                      )
                      mount.remount(
                        w.copy(
                          regions = w.regions.updated(c.region, r1),
                          updates = w.updates + 1,
                        ).markRegion(c.region),
                        c.region,
                      )
                  end if
                case Source.Bound(_)                   =>
                  if r.content == Content.Value(c.value) then w
                  else
                      w.copy(
                        regions = w.regions.updated(
                          c.region,
                          r.copy(content = Content.Value(c.value)),
                        ),
                        updates = w.updates + 1,
                      ).markNode(c.region)
                case Source.Local                      => w
          case _                         => w

    private def absorb(w: World, msg: Msg)(using Frame): World < Sync =
      msg match
          case c: Msg.Content  => apply(w, c)
          case Msg.Quit        => w.copy(quit = true)
          // claude 2026-10-12: created the two cases below (K29, D-16): an arm
          // inside a settle verifies its region; a tick inside a settle is kept
          // for the next step, once (review R2-7)
          // claude 2026-10-19: changed: the region leaves World.unarmed (P2)
          case Msg.Armed(p, g) => verifyRegion(w.armed(p, g), p, Some(g))
          case Msg.Repair      =>
            if w.backlog.contains(Msg.Repair) then w
            else w.copy(backlog = w.backlog :+ Msg.Repair)
          case other           => w.copy(backlog = w.backlog :+ other)

    /** One scheduler round trip: lets the watcher fibers that a write woke up
      * run and post their values.
      */
    private def hop(using Frame): Unit < Async =
      Fiber.initUnscoped(Kyo.unit).map(_.get)

    /** Settles the consequences of one event. kyo's `Signal` has no synchronous
      * change hook: a write completes a promise and each watcher resumes as a
      * scheduled task, often through one or more races (`observe`,
      * `combineLatest`). See [[tui4s.spi.Settle]] for the two strategies. While
      * a component that this frame mounts waits for its first content
      * (`World.pending`), the settle takes messages until `pendingTimeout`
      * after `frameStart` (`System.nanoTime`), and then goes on without it.
      */
    // claude 2026-10-13: changed (C4, the C2 cut back): the bounded wait for
    // a mounting component (the `pending` branch)
    // claude 2026-10-13: changed (C4, P-6): the deadline of the frame
    // claude 2026-10-13: changed (C4, review R1-3, R2-3): the start of the
    // frame; the time left is a difference
    // claude 2026-10-13: changed (C4, review R1-1, R2-1): `start` is in the
    // loop state: the end of the wait for a component sets it, so the `max`
    // of the settle counts from there (the wait counted as a settle timeout,
    // and the frame skipped the `confirm` observations)
    private def settle(w: World, frameStart: Long)(using
        Frame,
    ): World < Async =
      nanos.map { t0 =>
        Loop(w, 0, t0, t0) { (w, quiet, start, last) =>
          if w.pending.nonEmpty then
              // a component mount is running its effect: its first content
              // will come, wait for it (bounded)
              // claude 2026-10-13: changed (C4, P-6): the bound is in total,
              // from the start of the settle: each message that arrives
              // meanwhile no longer starts the wait again (with steady
              // traffic, the settle of a slow component did not end); the
              // deadline is the frame's (the settle and its re-check)
              nanos.map { now =>
                // claude 2026-10-13: changed (C4, review R1-3, R2-3): by
                // difference, so that a negative `nanoTime` cannot overflow
                // (`pendingNanos` and `waited` are not negative)
                val waited = now - frameStart
                // claude 2026-10-19: changed: the checked timeout
                // (RunOptions, A10)
                val left   =
                  if waited <= 0L then runOpts.pendingNanos
                  else runOpts.pendingNanos - waited
                // claude 2026-10-13: changed (C4, review R2-2): a run that
                // ends does not wait for the component
                ending(w).map { end =>
                  if left <= 0L || end then
                      Loop.continue(w.copy(pending = Set.empty), 0, now, now)
                  else
                      Abort
                        .run[Timeout | Closed](
                          Async.timeout(Duration.fromNanos(left))(inbox.take),
                        )
                        .map {
                          case Result.Success(m) =>
                            absorb(w, m).map(w1 =>
                              nanos.map(t => Loop.continue(w1, 0, t, t)),
                            )
                          case _                 =>
                            nanos.map(t =>
                              Loop.continue(
                                w.copy(pending = Set.empty),
                                0,
                                t,
                                t,
                              ),
                            )
                        }
                  end if
                }
              }
          else
              // The order is "probe, THEN drain". A loop that drains first,
              // finds nothing, and then reads the probe can miss a watcher
              // that posted between the drain and the probe: it is parked
              // again when the probe runs, so the probe says "quiescent" and
              // the frame flushes without that value (it gets its own
              // Background frame). Seen on a 4-CPU container under CPU
              // contention: 2-5 split frames per 500 events in the glitch
              // test. "Done" needs the probe to say quiescent BEFORE a drain
              // that finds nothing.
              val idleBefore = options.settle match
                  case Settle.Quiescent(_, _) => SchedulerProbe.quiescent()
                  case Settle.Hops(_, _)      => true
              Abort.run[Closed](inbox.drain).map {
                case Result.Success(msgs) if msgs.nonEmpty =>
                  Kyo
                    .foldLeft(msgs)(w)(absorb)
                    .map(w1 => nanos.map(t => Loop.continue(w1, 0, start, t)))
                case _                                     =>
                  nanos.map { now =>
                    options.settle match
                        // `quiet` counts quiescent observations in a row
                        // (each followed by an empty drain); the settle ends
                        // after `confirm` of them, one hop apart.
                        // claude 2026-10-19: changed: the checked `max`
                        // (RunOptions.settleNanos, A10)
                        case Settle.Quiescent(_, confirm) =>
                          if idleBefore && quiet + 1 >= confirm then
                              Loop.done(w)
                          else if now - start > runOpts.settleNanos then
                              // claude 2026-10-12: changed (D-17 c, RR O6):
                              // the frame counts the timeout; no print to
                              // stderr
                              Loop.done(
                                w.copy(settleTimeouts = w.settleTimeouts + 1),
                              )
                          else
                              hop.andThen(
                                Loop.continue(
                                  w,
                                  if idleBefore then quiet + 1 else 0,
                                  start,
                                  last,
                                ),
                              )
                        // claude 2026-10-19: changed: the checked `window`
                        // (RunOptions.settleNanos, A10)
                        case Settle.Hops(hops, _)         =>
                          if quiet >= hops && now - last >= runOpts.settleNanos
                          then Loop.done(w)
                          else
                              hop.andThen(
                                Loop.continue(w, quiet + 1, start, last),
                              )
                  }
              }
          end if
        }
      }
    end settle

    /** True when the run ends: a quit (a `Msg.Quit` that the settle took, or
      * the flag of `quit`), or a Ctrl-C key in the backlog
      * (`TuiOptions.ctrlCQuits`; inside a settle a key waits in the backlog,
      * and its step comes after this frame). The wait for a mounting component
      * ends then: the run does not wait for a UI that it will not show.
      */
    // claude 2026-10-19: changed: the Kyo AtomicBoolean (P2)
    private def ending(w: World)(using Frame): Boolean < Sync =
      quitRequested.get.map(q =>
        w.quit || q ||
          (options.ctrlCQuits && w.backlog.exists {
            case Msg.Key(ev, _) => isCtrlC(ev)
            case _              => false
          }),
      )

    // ---- layout, paint, flush ----

    // claude 2026-10-19: changed: the focusable nodes of the layout (a
    // viewport stop, L1a review)
    private def fixFocus(w: World): Option[Loc] =
        w.focus match
            case Some(f) if Layout.isFocusable(w.laid, f) => Some(f)
            case _ if !options.autoFocus                  => None
            case _                                        =>
              val order = Layout.focusOrder(w.laid)
              val auto  = order.find(p =>
                w.laid.nodes.get(p).map(_.kind).exists {
                  case Kind.El(e) => e.attrs.focusAuto.getOrElse(false)
                  case _          => false
                },
              )
              auto.orElse(order.headOption)
        end match
    end fixFocus

    private def relayoutAll(
        w: World,
        paths: Iterable[Loc],
        laid: Laid,
        dmg: Vector[Damage],
        env: Scene,
    ): (Laid, Vector[Damage]) =
      paths.foldLeft((laid, dmg)) { case ((l, d), p) =>
        l.nodes.get(p).flatMap(_.owner) match
            case Some(owner) =>
              val (l2, d2) = Layout.relayout(l, owner, Set(p), env, w.root)
              (l2, d ++ d2)
            case None        => (l, d)
      }

    /** Lays out again in place each viewport of `paths` after a scroll:
      * [[Layout.relayout]] of the viewport itself moves its kids, and its owner
      * is laid out again only when the size of the viewport changes.
      */
    // claude 2026-10-19: created (L1a review)
    private def relayoutIn(
        w: World,
        paths: Iterable[Loc],
        acc: (Laid, Vector[Damage]),
        env: Scene,
    ): (Laid, Vector[Damage]) =
      paths.foldLeft(acc) { case ((l, d), p) =>
        val (l2, d2) = Layout.relayout(l, p, Set.empty, env, w.root)
        (l2, d ++ d2)
      }

    /** Lays out what changed, then scrolls the scroll target into view
      * ([[reveal]]). Pure: returns the new world and the damage to paint.
      */
    // claude 2026-10-19: changed: the follow-focus step (L1a)
    private def layoutStep(w: World): (World, Vector[Damage]) =
        val (w1, damage) = laidOut(w)
        val (w2, more)   = reveal(w1)
        (w2, if w.full then damage else damage ++ more)

    /** The follow-focus step (D13: kyo-ui's `scrollIntoView` is private, so the
      * runner has its own rule). When the scroll target ([[Scroll.target]]: the
      * focus, or its `aria-activedescendant`) is not the one of the last frame,
      * or a key or a paste reached the focused element and did not scroll
      * (`Reveal.Now`), each viewport that holds the target scrolls the least
      * distance that shows it ([[Scroll.follow]]), and is laid out again. The
      * offsets that the frame keeps are the clamped ones of the layout
      * ([[Scroll.kept]]).
      */
    // claude 2026-10-19: created (L1a)
    private def reveal(w: World): (World, Vector[Damage]) =
        val target          = Scroll.target(w.laid, w.focus)
        val kept            = Scroll.kept(w.laid, w.scroll)
        val (scroll, moved) =
          if target == w.followed && w.reveal == Reveal.OnMove then
              (kept, Vector.empty)
          else
              target.fold((kept, Vector.empty[Loc]))(
                Scroll.follow(w.laid, _, kept),
              )
        val w1              =
          w.copy(scroll = scroll, followed = target, reveal = Reveal.OnMove)
        if moved.isEmpty then (w1, Vector.empty)
        else
            val (laid, dmg) =
              relayoutIn(w1, moved, (w1.laid, Vector.empty), w1.env)
            (w1.copy(laid = laid, scroll = Scroll.kept(laid, scroll)), dmg)
    end reveal

    /** The layout of what changed, and its damage. */
    // claude 2026-10-19: created (L1a): the old body of layoutStep
    private def laidOut(w: World): (World, Vector[Damage]) =
      if w.full then
          val laid0 = Layout.layoutAll(w.root, w.env)
          val focus = fixFocus(w.copy(laid = laid0))
          val w1    = w.copy(focus = focus)
          // claude 2026-10-19: changed: a new focus lays out again only the
          // owners of the old and the new focus, not the whole screen (PF-a,
          // F8); the full frame paints everything, so the damage is dropped
          val laid  =
            if focus == w.focus then laid0
            else
                relayoutAll(
                  w1,
                  w.focus.toList ++ focus.toList,
                  laid0,
                  Vector.empty,
                  w1.env,
                )._1
          (
            w1.copy(laid = laid),
            Vector(
              Damage.Clear(Rect(0, 0, w.size.w, w.size.h), None),
              Damage.Paint(Loc.screen),
            ),
          )
      else
          val env           = w.env
          // claude 2026-10-19: changed: Loc.tops, not a compare of each pair
          // (PF-a, F2)
          val regions       = Loc.tops(w.dirtyRegions)
          val (laid1, dmg1) = regions.foldLeft((w.laid, Vector.empty[Damage])) {
            case ((l, d), r) =>
              l.regionOwner.get(r) match
                  case Some(owner) =>
                    val (l2, d2) =
                      Layout.relayout(l, owner, Set.empty, env, w.root)
                    (l2, d ++ d2)
                  case None        => (l, d)
          }
          val (laid2, dmg2) =
            relayoutIn(
              w,
              w.scrolled,
              relayoutAll(w, w.dirtyNodes, laid1, dmg1, env),
              env,
            )
          val w2            = w.copy(laid = laid2)
          val focus         = fixFocus(w2)
          if focus == w.focus then (w2, dmg2)
          else
              val w3            = w2.copy(focus = focus)
              val (laid3, dmg3) = relayoutAll(
                w3,
                (w.focus.toList ++ focus.toList),
                laid2,
                dmg2,
                w3.env,
              )
              (w3.copy(laid = laid3), dmg3)
          end if

    /** The value of a canvas call, or the end of the run: a write to the
      * terminal that failed ([[tui4s.TuiError.TerminalIO]]) fails the run fiber
      * with that error, and `Tui.runWith` gives it in `Abort[TuiError]`.
      */
    // claude 2026-10-19: created (P5): the canvas gives its failure as a value
    private def orFail[A](r: Result[TuiError, A])(using Frame): A < Sync =
      r match
          case Result.Success(a) => a
          case Result.Failure(e) => Abort.panic(e)
          case Result.Panic(e)   => Abort.panic(e)

    private def frame(
        w: World,
        seq: Long,
        cause: FrameCause,
        t0: Long,
        handleNs: Long = 0,
        settleNs: Long = 0,
    )(using Frame): World < Async =
        // claude 2026-10-19: changed: the viewports that a key scrolled (L1a
        // review)
        val idle = !w.full && w.dirtyRegions.isEmpty && w.dirtyNodes.isEmpty &&
          w.scrolled.isEmpty
        // claude 2026-10-19: changed: the start of the render is read inside
        // the effect (P2)
        nanos.map { tr =>
          Sync
            .defer {
              if idle then
                  Result.succeed(
                    (
                      // claude 2026-10-19: changed: a frame with no change
                      // does not scroll the target into view later (L1a)
                      w.copy(reveal = Reveal.OnMove),
                      // claude 2026-10-12: changed: repaired and settleTimeouts
                      // in the frame stats (K29, D-17 c)
                      FrameStats(
                        seq,
                        cause,
                        0,
                        0,
                        0L,
                        w.updates,
                        repaired = w.repaired,
                        settleTimeouts = w.settleTimeouts,
                      ),
                    ),
                  )
              else
                  val (w1, damage) = layoutStep(w)
                  // claude 2026-10-19: changed (P5): the pure painter gives
                  // the plan; the canvas writes it and sends its changed
                  // cells; the plan counts the cells written
                  val plan         = Paint(w1.laid, w1.env, damage)
                  canvas.write(plan)
                  canvas.flush(plan).map { flushed =>
                    val done = w1.copy(
                      dirtyRegions = Vector.empty,
                      dirtyNodes = Set.empty,
                      scrolled = Set.empty,
                      full = false,
                    )
                    (
                      done,
                      // claude 2026-10-12: changed: repaired and settleTimeouts
                      // in the frame stats (K29, D-17 c)
                      FrameStats(
                        seq,
                        cause,
                        plan.cells,
                        flushed,
                        0L,
                        w.updates,
                        subtrees = plan.roots,
                        repaired = w.repaired,
                        settleTimeouts = w.settleTimeouts,
                      ),
                    )
                  }
            }
            .map(orFail)
            .map { (w1, s) =>
              nanos.map { t1 =>
                val emit = stats match
                    case Present(_) if idle && cause == FrameCause.Background =>
                      Kyo.unit // nothing happened: no frame
                    case Present(ch) =>
                      // claude 2026-10-19: changed: None when nothing has the
                      // focus, not "" (P1b, A5 c)
                      val focus = w1.focus.map { f =>
                        w1.laid.nodes.get(f).map(_.kind) match
                            case Some(Kind.El(e)) =>
                              e.attrs.identifier.getOrElse(f.showLoc)
                            case _                => f.showLoc
                      }
                      Abort
                        .run[Closed](
                          ch.put(
                            s.copy(
                              endNanos = t1,
                              nanos = t1 - t0,
                              handleNanos = handleNs,
                              settleNanos = settleNs,
                              renderNanos = t1 - tr,
                              focus = focus,
                            ),
                          ),
                        )
                        .unit
                    case Absent      => Kyo.unit
                emit.andThen(w1)
              }
            }
        }
    end frame
end Runner

private[tui4s] object Runner:
    /** A runner with a new gauge and a new quit flag of its own. */
    // claude 2026-10-19: created (P2, A7): the gauge and the flag are Kyo
    // atomics, so they are made in `Sync`
    def init(
        inbox: Channel[Msg],
        canvas: Canvas,
        runOpts: RunOptions,
        stats: Maybe[Channel[FrameStats]],
        knobs: Knobs = Knobs.default,
    )(using Frame): Runner < Sync =
      for
          gauge <- FiberGauge.init
          quit  <- AtomicBoolean.init(false)
      yield new Runner(inbox, canvas, runOpts, stats, knobs, gauge, quit)
end Runner
