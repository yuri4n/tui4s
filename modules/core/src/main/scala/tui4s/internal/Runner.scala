package tui4s.internal

import kyo.*
import kyo.UI.Keyboard
import tui4s.FrameCause
import tui4s.FrameStats
import tui4s.spi.Settle
import tui4s.TuiOptions

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
final class Runner(
    inbox: Channel[Msg],
    canvas: Canvas,
    options: TuiOptions,
    stats: Maybe[Channel[FrameStats]],
    knobs: Knobs = Knobs.default,
):

    // claude 2026-10-12: changed: the mount gets the test seams
    private val mount = Mount(inbox, knobs)

    private def nanos(using Frame): Long < Sync =
      Sync.defer(java.lang.System.nanoTime())

    // claude 2026-10-12: created (R1 option b, RC-8): the last world, so that
    // `stop` can unmount it when the run ends in any way
    private val last =
      new java.util.concurrent.atomic.AtomicReference[Option[World]](None)

    private def remember(w: World)(using Frame): World < Sync =
      Sync.defer { last.set(Some(w)); w }

    /** The fibers of this run that have not ended: a gauge for tests (R1). */
    private[tui4s] def liveFibers(using Frame): Int < Sync = mount.liveFibers

    /** Stops every fiber of the run: the watchers of the last world, the tick,
      * and then every fiber of the run that still lives (the watchers that a
      * step started inside a settle or a verify pass and had not remembered yet
      * when the interrupt landed). It interrupts them and does not wait.
      */
    // claude 2026-10-12: changed (review R2-1, R2-2): `stopped` is set first
    // (the tick reads it); `interruptAll` over the gauge set closes the window
    // between a watcher start inside a step and the next `remember`
    private def stop(using Frame): Unit < Sync =
      Sync
        .defer { stopped.set(true); last.get() }
        .map(_.fold(Kyo.unit)(mount.unmountAll))
        .andThen(
          Sync.defer(ticker.get()).map(_.fold(Kyo.unit)(_.interrupt.unit)),
        )
        .andThen(mount.interruptAll)

    // claude 2026-10-12: created (review R2-2, R2-8 b): set by `stop` (the tick
    // ends at its next wake-up, also when `ticker` was never set) and by
    // `quit` (the step reads it, so a quit that the full inbox dropped still
    // ends the run)
    private val stopped       = new java.util.concurrent.atomic.AtomicBoolean(false)
    private val quitRequested =
      new java.util.concurrent.atomic.AtomicBoolean(false)

    /** Asks the run to end: it posts the quit message and sets a flag that the
      * next step reads, in case the inbox is full and drops the message.
      */
    // claude 2026-10-12: created (review R2-8 b)
    def quit(using Frame): Unit < Sync =
      Sync
        .defer(quitRequested.set(true))
        .andThen(Abort.run[Closed](inbox.offer(Msg.Quit)).unit)

    // claude 2026-10-13: created (C3): the key routing; its handlers get the
    // runner's quit
    private val dispatch = Dispatch(quit)

    // claude 2026-10-12: created (K29): the repair tick fiber
    private val ticker =
      new java.util.concurrent.atomic.AtomicReference[Option[
        Fiber[Any, Nothing],
      ]](
        None,
      )

    // TODO(#8): remove the repair tick when the watchers go back to
    // `Signal.observe` (it has its own repair timer).
    // https://github.com/yuri4n/tui4s/issues/8
    /** The repair tick: every `knobs.repairInterval` (default 1 s, Kyo's
      * `Signal.defaultRepairInterval`) it asks the runner to re-read `current`
      * of every region, so that a write the watcher loop missed reaches the
      * screen within about one interval (a tick that arrives inside a settle is
      * kept for the next step). The tick ends when `stopped` is set.
      */
    // claude 2026-10-12: created; changed (review R2-2): the own exit
    private def repairTick(using Frame): Unit < Async =
      Abort
        .run[Closed](
          Loop.foreach {
            Async
              .sleep(knobs.repairInterval)
              .andThen(Sync.defer(stopped.get()))
              .map { stop =>
                if stop then Loop.done
                else inbox.put(Msg.Repair).andThen(Loop.continue)
              }
          },
        )
        .unit

    // claude 2026-10-12: changed (R1 option b, RC-8): `unmountAll` runs in a
    // `Sync.ensure` around the run, so an interrupted run stops its watchers
    // too. It does not wait for them: a run-owned Scope (option a) waits for a
    // CPU-bound watcher and breaks the end order (spike S2, gates.md G2).
    def run(root: UI)(using Frame): Unit < Async =
      Sync.ensure(stop) {
        for
            tick <- Fiber.initUnscoped(repairTick)
            _    <- Sync.defer { mount.track(tick); ticker.set(Some(tick)) }
            t0   <- nanos
            w0   <- mount
                      .walk(
                        root,
                        Loc.root,
                        World.init(root, canvas.size),
                        Map.empty,
                      )
                      .map(remember)
            // claude 2026-10-12: changed (D-16, armkey): the initial mount is
            // settled and verified before the Initial frame
            w0s  <- settleAndVerify(w0, input = false).map(remember)
            w1   <- frame(w0s, 0L, FrameCause.Initial, t0).map(remember)
            _    <- Loop(w1)(step)
        yield ()
      }

    private def step(
        w: World,
    )(using Frame): Loop.Outcome[World, World] < Async =
      // claude 2026-10-12: changed (review R2-8 b): a quit that the full inbox
      // dropped ends the run here
      Sync.defer(quitRequested.get()).map { q =>
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
                                 ).map(remember)
                  t1          <- nanos
                  // claude 2026-10-13: changed (C3): a key is an Input step
                  // too, so the verify pass re-reads the unarmed regions
                  input        = msg match
                                     case Msg.Key(_, _)    => true
                                     case Msg.Resize(_, _) => true
                                     case _                => false
                  w3          <- settleAndVerify(w2, input).map(remember)
                  t2          <- nanos
                  // claude 2026-10-13: changed (C3): a key gives an Input frame
                  (seq, cause) = msg match
                                     case Msg.Key(_, s)    => (s, FrameCause.Input)
                                     case Msg.Resize(_, s) =>
                                       (s, FrameCause.Input)
                                     case _                => (0L, FrameCause.Background)
                  w4          <- frame(w3, seq, cause, t0, t1 - t0, t2 - t1)
                                   .map(remember)
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
          case Msg.Resize(size, _)                                 =>
            Sync
              .defer(canvas.resize(size))
              .andThen(w.copy(size = size, full = true))
          case c: Msg.Content                                      => apply(w, c)
          // claude 2026-10-12: created the two cases below (K29, D-16): a
          // watcher armed: verify its region one time; the repair tick
          // re-reads every region
          case Msg.Armed(p, g)                                     => verifyRegion(w, p, Some(g))
          case Msg.Repair                                          => repairAll(w)
          case Msg.Quit                                            => w.copy(quit = true)

    /** The settle, the verify pass, and the re-check (D-17 c): when the verify
      * pass repaired a value, its consequences (a remount, the watchers that
      * the write woke) get one more settle before the frame.
      */
    // claude 2026-10-12: created (RC-11)
    private def settleAndVerify(w: World, input: Boolean)(using
        Frame,
    ): World < Async =
      settle(w).map { w1 =>
        verify(w1, input).map { w2 =>
          // TODO(#9): drop the re-check when Kyo closes the lost-wakeup window
          // of `Signal` (the conditions of D-17 c).
          // https://github.com/yuri4n/tui4s/issues/9
          if w2.repaired > w1.repaired then settle(w2) else w2
        }
      }

    /** The verify pass (D-16, armkey; spike S2 section 4.3). After the settle
      * it re-reads `current` of the regions mounted in this step or the step
      * before, and in an Input step (a key or a resize) also of the regions
      * whose watcher has not armed yet. A write that landed in the window of a
      * young watcher (between the mount's read of `current` and the
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
    // claude 2026-10-13: changed: the Scaladoc names the keys (C3)
    private def verify(w: World, input: Boolean)(using Frame): World < Sync =
        val unarmed = if input then mount.unarmedRegions(w) else Set.empty[Loc]
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
            fe.signal.current.map { cur =>
              if items.map(_.value) == cur.toVector then acc
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
    private def apply(w: World, c: Msg.Content)(using Frame): World < Sync =
      w.regions.get(c.region) match
          case Some(r) if r.gen == c.gen =>
            r.source match
                case Source.One(_)   =>
                  val content = Content.Single(c.value.asInstanceOf[UI])
                  if r.content == content then w
                  else
                      val w1 = w.copy(
                        regions = w.regions
                          .updated(c.region, r.copy(content = content)),
                      )
                      mount.remount(
                        w1.markRegion(c.region).copy(updates = w1.updates + 1),
                        c.region,
                      )
                  end if
                case Source.Many(fe) =>
                  val items = mount.reconcile(
                    fe,
                    r.items,
                    c.value.asInstanceOf[Chunk[Any]].toVector,
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
                case Source.Bound(_) =>
                  if r.content == Content.Value(c.value) then w
                  else
                      w.copy(
                        regions = w.regions.updated(
                          c.region,
                          r.copy(content = Content.Value(c.value)),
                        ),
                        updates = w.updates + 1,
                      ).markNode(c.region)
                case Source.Local    => w
          case _                         => w

    private def absorb(w: World, msg: Msg)(using Frame): World < Sync =
      msg match
          case c: Msg.Content  => apply(w, c)
          case Msg.Quit        => w.copy(quit = true)
          // claude 2026-10-12: created the two cases below (K29, D-16): an arm
          // inside a settle verifies its region; a tick inside a settle is kept
          // for the next step, once (review R2-7)
          case Msg.Armed(p, g) => verifyRegion(w, p, Some(g))
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
      * `combineLatest`). See [[tui4s.spi.Settle]] for the two strategies.
      */
    private def settle(w: World)(using Frame): World < Async =
      nanos.map { start =>
        Loop(w, 0, start) { (w, quiet, last) =>
          // The order is "probe, THEN drain". A loop that drains first, finds
          // nothing, and then reads the probe can miss a watcher that posted
          // between the drain and the probe: it is parked again when the probe
          // runs, so the probe says "quiescent" and the frame flushes without
          // that value (it gets its own Background frame). Seen on a 4-CPU
          // container under CPU contention: 2-5 split frames per 500 events
          // in the glitch test. "Done" needs the probe to say quiescent BEFORE
          // a drain that finds nothing.
          val idleBefore = options.settle match
              case Settle.Quiescent(_, _) => SchedulerProbe.quiescent()
              case Settle.Hops(_, _)      => true
          Abort.run[Closed](inbox.drain).map {
            case Result.Success(msgs) if msgs.nonEmpty =>
              Kyo
                .foldLeft(msgs)(w)(absorb)
                .map(w1 => nanos.map(t => Loop.continue(w1, 0, t)))
            case _                                     =>
              nanos.map { now =>
                options.settle match
                    // `quiet` counts quiescent observations in a row (each
                    // followed by an empty drain); the settle ends after
                    // `confirm` of them, one hop apart.
                    case Settle.Quiescent(max, confirm) =>
                      if idleBefore && quiet + 1 >= confirm then Loop.done(w)
                      else if now - start > max.toNanos then
                          // claude 2026-10-12: changed (D-17 c, RR O6): the
                          // frame counts the timeout; no print to stderr
                          Loop.done(
                            w.copy(settleTimeouts = w.settleTimeouts + 1),
                          )
                      else
                          hop.andThen(
                            Loop.continue(
                              w,
                              if idleBefore then quiet + 1 else 0,
                              last,
                            ),
                          )
                    case Settle.Hops(hops, window)      =>
                      if quiet >= hops && now - last >= window.toNanos then
                          Loop.done(w)
                      else hop.andThen(Loop.continue(w, quiet + 1, last))
              }
          }
        }
      }
    end settle

    // ---- layout, paint, flush ----

    private def fixFocus(w: World): Option[Loc] =
        def stillFocusable(f: Loc): Boolean = w.laid.nodes
          .get(f)
          .exists(_.kind match
              case Kind.El(e) => Props.isFocusable(e)
              case _          => false)
        w.focus match
            case Some(f) if stillFocusable(f) => Some(f)
            case _ if !options.autoFocus      => None
            case _                            =>
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

    /** Lays out what changed. Pure: returns the new world and the damage to
      * paint.
      */
    private def layoutStep(w: World): (World, Vector[Damage]) =
      if w.full then
          val laid0 = Layout.layoutAll(w.root, w.env)
          val focus = fixFocus(w.copy(laid = laid0))
          val w1    = w.copy(focus = focus)
          val laid  =
            if focus != w.focus then Layout.layoutAll(w.root, w1.env) else laid0
          (
            w1.copy(laid = laid),
            Vector(
              Damage.Clear(Rect(0, 0, w.size.w, w.size.h), None),
              Damage.Paint(Loc.screen),
            ),
          )
      else
          val env           = w.env
          val regions       = w.dirtyRegions.filterNot(r =>
            w.dirtyRegions.exists(o => o != r && r.isUnder(o)),
          )
          val (laid1, dmg1) = regions.foldLeft((w.laid, Vector.empty[Damage])) {
            case ((l, d), r) =>
              l.regionOwner.get(r) match
                  case Some(owner) =>
                    val (l2, d2) =
                      Layout.relayout(l, owner, Set.empty, env, w.root)
                    (l2, d ++ d2)
                  case None        => (l, d)
          }
          val (laid2, dmg2) = relayoutAll(w, w.dirtyNodes, laid1, dmg1, env)
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

    private def frame(
        w: World,
        seq: Long,
        cause: FrameCause,
        t0: Long,
        handleNs: Long = 0,
        settleNs: Long = 0,
    )(using Frame): World < Async =
        val idle = !w.full && w.dirtyRegions.isEmpty && w.dirtyNodes.isEmpty
        val tr   = java.lang.System.nanoTime()
        Sync
          .defer {
            if idle then
                (
                  w,
                  // claude 2026-10-12: changed: repaired and settleTimeouts in
                  // the frame stats (K29, D-17 c)
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
                )
            else
                val (w1, damage)   = layoutStep(w)
                val (rects, roots) = Paint(canvas, w1.laid, w1.env, damage)
                val flushed        = canvas.flush(rects)
                val repainted      = canvas.takeWrites()
                val done           = w1.copy(
                  dirtyRegions = Vector.empty,
                  dirtyNodes = Set.empty,
                  full = false,
                )
                (
                  done,
                  // claude 2026-10-12: changed: repaired and settleTimeouts in
                  // the frame stats (K29, D-17 c)
                  FrameStats(
                    seq,
                    cause,
                    repainted,
                    flushed,
                    0L,
                    w.updates,
                    subtrees = roots,
                    repaired = w.repaired,
                    settleTimeouts = w.settleTimeouts,
                  ),
                )
          }
          .map { (w1, s) =>
            nanos.map { t1 =>
              val emit = stats match
                  case Present(_) if idle && cause == FrameCause.Background =>
                    Kyo.unit // nothing happened: no frame
                  case Present(ch) =>
                    val focus = w1.focus.fold("") { f =>
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
    end frame
end Runner
