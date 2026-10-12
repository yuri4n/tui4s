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
  * applies it (handlers run here), then SETTLES: it applies every queued region
  * value until the settle rule of [[tui4s.spi.Settle]] says that the
  * consequences are in (default: the Kyo scheduler is quiescent, twice, one hop
  * apart, each time before an empty drain). Only then does it lay out what
  * changed, paint it into the kept buffer and flush the changed cells: one
  * frame per event, with all of that event's consequences in it.
  */
final class Runner(
    inbox: Channel[Msg],
    canvas: Canvas,
    options: TuiOptions,
    stats: Maybe[Channel[FrameStats]],
):

    private def quitAction(using Frame): Unit < Sync =
      Abort.run[Closed](inbox.offer(Msg.Quit)).unit
    private val mount                                = Mount(inbox)
    private val dispatch                             = Dispatch(quitAction)

    private def nanos(using Frame): Long < Sync =
      Sync.defer(java.lang.System.nanoTime())

    /** Runs `v` with this run's quit action in scope (the UI's construction, so
      * fibers it starts can quit too).
      */
    def scoped[A, S](v: => A < S)(using Frame): A < S =
      Tui4sQuit.let(quitAction)(v)

    def run(root: UI)(using Frame): Unit < Async =
      Tui4sQuit.let(quitAction) {
        for
            t0 <- nanos
            w0 <- mount.walk(
                    root,
                    Loc.root,
                    World.init(root, canvas.size),
                    Map.empty,
                  )
            w1 <- frame(w0, 0L, FrameCause.Initial, t0)
            wn <- Loop(w1)(step)
            _  <- mount.unmountAll(wn)
        yield ()
      }

    private def step(
        w: World,
    )(using Frame): Loop.Outcome[World, World] < Async =
      if w.quit then Loop.done(w)
      else
          next(w).map { (msg, w1) =>
            for
                t0          <- nanos
                w2          <- handle(w1.copy(updates = 0), msg)
                t1          <- nanos
                w3          <- settle(w2)
                t2          <- nanos
                (seq, cause) = msg match
                                   case Msg.Key(_, s)    => (s, FrameCause.Input)
                                   case Msg.Resize(_, s) =>
                                     (s, FrameCause.Input)
                                   case _                => (0L, FrameCause.Background)
                w4          <- frame(w3, seq, cause, t0, t1 - t0, t2 - t1)
            yield Loop.continue(w4)
          }

    private def next(w: World)(using Frame): (Msg, World) < Async =
      w.backlog.headOption match
          case Some(m) => (m, w.copy(backlog = w.backlog.tail))
          case None    =>
            Abort.run[Closed](inbox.take).map {
              case Result.Success(m) => (m, w)
              case _                 => (Msg.Quit, w)
            }

    private def isCtrlC(ev: UI.KeyboardEvent): Boolean =
      ev.key == Keyboard.Char('c') && ev.modifiers.ctrl

    private def handle(w: World, msg: Msg)(using Frame): World < Async =
      msg match
          case Msg.Key(ev, _) if options.ctrlCQuits && isCtrlC(ev) =>
            w.copy(quit = true)
          case Msg.Key(ev, _)                                      => dispatch.onKey(w, ev)
          case Msg.Resize(size, _)                                 =>
            Sync
              .defer(canvas.resize(size))
              .andThen(w.copy(size = size, full = true))
          case c: Msg.Content                                      => apply(w, c)
          case Msg.Quit                                            => w.copy(quit = true)

    /** A region's new value. Late messages of an unmounted watcher (old
      * generation) are dropped.
      */
    private def apply(w: World, c: Msg.Content)(using Frame): World < Sync =
      w.regions.get(c.region) match
          case Some(r) if r.gen == c.gen =>
            r.source match
                case Source.One(_) | Source.Comp(_, _) =>
                  val content = Content.Single(c.value.asInstanceOf[UI])
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
                case Source.Many(fe)                   =>
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
          case c: Msg.Content => apply(w, c)
          case Msg.Quit       => w.copy(quit = true)
          case other          => w.copy(backlog = w.backlog :+ other)

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
          if w.pending.nonEmpty then
              /* a component mount is running its effect: its first content will
               * come, wait for it (bounded) */
              Abort
                .run[Timeout | Closed](
                  Async.timeout(options.pendingTimeout)(inbox.take),
                )
                .map {
                  case Result.Success(m) =>
                    absorb(w, m)
                      .map(w1 => nanos.map(t => Loop.continue(w1, 0, t)))
                  case _                 =>
                    nanos.map(t =>
                      Loop.continue(w.copy(pending = Set.empty), 0, t),
                    )
                }
          else
              /* The order is "probe, THEN drain". A loop that drains first,
               * finds nothing, and then reads the probe */
              /* can miss a watcher that posted between the drain and the probe:
               * it is parked again when the probe */
              /* runs, so the probe says "quiescent" and the frame flushes
               * without that value (it gets its own */
              /* Background frame). Seen on a 4-CPU container under CPU
               * contention: 2-5 split frames per 500 events */
              /* in the glitch test. "Done" needs the probe to say quiescent
               * BEFORE a drain that finds nothing. */
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
                        /* `quiet` counts quiescent observations in a row (each
                         * followed by an empty drain); */
                        /* the settle ends after `confirm` of them, one hop
                         * apart. */
                        case Settle.Quiescent(max, confirm) =>
                          if idleBefore && quiet + 1 >= confirm then
                              Loop.done(w)
                          else if now - start > max.toNanos then
                              if SchedulerProbe.debug then
                                  java.lang.System.err.println(
                                    s"tui4s: settle timed out: ${SchedulerProbe.describe()}",
                                  )
                              Loop.done(w)
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
            if idle then (w, FrameStats(seq, cause, 0, 0, 0L, w.updates))
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
                  FrameStats(
                    seq,
                    cause,
                    repainted,
                    flushed,
                    0L,
                    w.updates,
                    subtrees = roots,
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
