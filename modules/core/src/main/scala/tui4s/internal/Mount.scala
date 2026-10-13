package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import kyo.*
import kyo.UI.Ast.*
import tui4s.Component

/** Mounting and unmounting regions. A mount reads the signal's current value
  * synchronously (so the first frame needs no scheduler round trip) and starts
  * one watcher fiber that posts every later value to the runner's inbox.
  */
// claude 2026-10-12: changed: `knobs` (the test seams)
private[tui4s] final class Mount(
    inbox: Channel[Msg],
    knobs: Knobs = Knobs.default,
):

    // claude 2026-10-12: created (R1, RC-8): the fibers this run started that
    // have not ended, for the live-fiber gauge of the tests. Weak, so that the
    // gauge itself keeps no fiber alive.
    private val started =
      java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(
          new java.util.WeakHashMap[Fiber[Any, Nothing], java.lang.Boolean](),
        ),
      )

    // claude 2026-10-12: created (D-16, armkey): the regions whose watcher has
    // not armed yet (path -> gen). Until the arm, a write is not seen by the
    // watcher; an Input step verifies these regions.
    private val unarmed =
      new java.util.concurrent.ConcurrentHashMap[Loc, java.lang.Long]()

    /** The regions of `w` whose watcher has not armed yet; stale entries (an
      * old generation) are dropped.
      */
    private[tui4s] def unarmedRegions(w: World): Set[Loc] =
        import scala.jdk.CollectionConverters.*
        unarmed.asScala.toList.flatMap { (p, g) =>
          if w.regions.get(p).exists(_.gen == g.longValue) then Some(p)
          else
              discard(unarmed.remove(p, g))
              None
        }.toSet
    end unarmedRegions

    /** Adds a fiber of this run to the gauge. */
    private[tui4s] def track(f: Fiber[Any, Nothing]): Unit =
      discard(started.add(f))

    /** The fibers of this run that have not ended (the watchers and the fibers
      * the runner tracked): the R1 gauge for tests.
      */
    private[tui4s] def liveFibers(using Frame): Int < Sync =
        import scala.jdk.CollectionConverters.*
        Sync
          .defer(started.synchronized(started.asScala.toList))
          .map(fs =>
            Kyo.foldLeft(fs)(0)((n, f) =>
              f.done.map(d => if d then n else n + 1),
            ),
          )
    end liveFibers

    // claude 2026-10-13: created (C4, the C2 cut back): the hook mount posts
    // its content with it
    private def post(path: Loc, gen: Long, v: Any)(using
        Frame,
    ): Unit < Async =
      Abort.run[Closed](inbox.put(Msg.Content(path, gen, v))).unit

    // claude 2026-10-12: changed: the watcher joins the gauge and runs the own
    // loop below, not `Signal.observe`
    private def watch[A](signal: Signal[A], path: Loc, gen: Long)(using
        Frame,
    ): Fiber[Any, Nothing] < Sync =
      Sync
        .defer(discard(unarmed.put(path, java.lang.Long.valueOf(gen))))
        .andThen(
          Fiber.initUnscoped(Abort.run[Closed](loop(signal, path, gen)).unit),
        )
        .map(f => Sync.defer { track(f); f })

    // TODO(#8): go back to `signal.observe` when Kyo fixes the leak of
    // `Signal.observe` (one `Async.race` per value in a long-lived fiber keeps
    // about 1.7 KB per change in Kyo 1.0.0-RC7). This loop removes only the
    // watcher's own race: a region on `combineLatest`, `zip`, `switchMap` or
    // `combineLatestAll` still runs one race per change inside Kyo's
    /* `nextWith` (`Signal.awaitAny`), which this loop cannot avoid (review
     * R2-6). */
    // https://github.com/yuri4n/tui4s/issues/8
    /** The watcher loop (K29). It uses only `Signal.currentWith` and
      * `Signal.nextWith`, no race: step 0 posts `Msg.Armed` and waits for the
      * first change (the mount read the first value already); each later step
      * reads `current`, posts it, and waits for the next change. A write that
      * lands between the read of `current` and the registration of `nextWith`
      * is missed here; the verify pass and the repair tick of the runner
      * re-read `current` and repair it. `knobs.seam` holds that window open for
      * the tests.
      */
    // claude 2026-10-12: created
    private def loop[A](signal: Signal[A], path: Loc, gen: Long)(using
        Frame,
    ): Unit < (Async & Abort[Closed]) =
      Loop(0) { n =>
        val post: Unit < (Async & Abort[Closed]) =
          if n == 0 then Kyo.unit
          else signal.currentWith(cur => inbox.put(Msg.Content(path, gen, cur)))
        val delay                                = knobs.seam(n)
        // claude 2026-10-12: changed (armkey): the arm step leaves `unarmed`
        val arm: Unit < (Async & Abort[Closed])  =
          if n == 0 then
              Sync
                .defer(
                  discard(unarmed.remove(path, java.lang.Long.valueOf(gen))),
                )
                .andThen(inbox.put(Msg.Armed(path, gen)))
          else Kyo.unit
        post
          .andThen(if delay > Duration.Zero then Async.sleep(delay)
          else Kyo.unit)
          .andThen(arm)
          .andThen(signal.nextWith(_ => ()))
          .andThen(Loop.continue(n + 1))
      }

    /** The `SignalRef` an element is bound to (`.value(ref)` or
      * `.checked(ref)`), as kyo-ui's `collectSignalRef`.
      */
    def boundRef(el: Element): Option[SignalRef[?]] =
        def refOf(v: Maybe[UI.Bound[?]]): Option[SignalRef[?]] = v match
            case Present(UI.Bound.Ref(ref)) => Some(ref)
            case _                          => None
        el match
            case ti: TextInput    => refOf(ti.value)
            case ri: RangeInput   => refOf(ri.value)
            case pi: PickerInput  => refOf(pi.value)
            case bi: BooleanInput => refOf(bi.checked)
            case _                => None
    end boundRef

    /** Unique keys for a `Foreach`: kyo-ui does not reject duplicates, but two
      * items on one path would collide here.
      */
    private def keysOf(
        fe: Foreach[Any, UI],
        items: Vector[Any],
    ): Vector[String] =
        val raw =
          items.zipWithIndex.map((a, i) => fe.key.fold(i.toString)(k => k(a)))
        raw.zipWithIndex
          .foldLeft((Vector.empty[String], Set.empty[String])) {
            case ((acc, seen), (k, i)) =>
              val unique = if seen.contains(k) then s"$k~$i" else k
              (acc :+ unique, seen + unique)
          }
          ._1
    end keysOf

    /** Re-renders only the items that are new, or whose value or index changed;
      * the others keep their UI value (so their nested regions are kept too).
      */
    def reconcile(
        fe: Foreach[Any, UI],
        old: Vector[ItemState],
        values: Vector[Any],
    ): Vector[ItemState] =
        val byKey = old.map(it => it.key -> it).toMap
        keysOf(fe, values).zip(values).zipWithIndex.map { case ((k, a), i) =>
          byKey
            .get(k)
            .filter(o => o.value == a && o.index == i)
            .getOrElse(ItemState(k, i, a, fe.render(i, a)))
        }
    end reconcile

    /** Walks `ui` at `path`, mounting every region in it. A region of `reclaim`
      * with the same signal (or ref) is kept as is with its watcher and its
      * content.
      */
    def walk(ui: UI, path: Loc, w: World, reclaim: Map[Loc, RegionState])(using
        Frame,
    ): World < Sync =
      ui match
          case f: Fragment[?]     =>
            Kyo.foldLeft(f.children.toSeq.zipWithIndex)(w) {
              case (acc, (c, i)) => walk(c, path :+ i.toString, acc, reclaim)
            }
          // claude 2026-10-13: changed (C4, the C2 cut back): a `Reactive`
          // whose signal is in the component registry is a component node
          case r: Reactive[?]     =>
            ComponentSpec.lookup(r.signal) match
                case Some(spec) => mountComponent(spec, path, w, reclaim)
                case None       =>
                  reclaim.get(path) match
                      case Some(
                            old @ RegionState(
                              _,
                              Source.One(sig),
                              _,
                              Content.Single(c),
                              _,
                              _,
                            ),
                          ) if sig eq r.signal =>
                        walk(
                          c,
                          path :+ "~",
                          w.copy(regions = w.regions.updated(path, old)),
                          reclaim,
                        )
                      case _ =>
                        val (gen, w1) = w.gen
                        for
                            cur     <- r.signal.current
                            watcher <- watch(r.signal, path, gen)
                            region   = RegionState(
                                         path,
                                         Source.One(r.signal),
                                         gen,
                                         Content.Single(cur),
                                         Vector.empty,
                                         Some(watcher),
                                       )
                            w2      <- walk(
                                         cur,
                                         path :+ "~",
                                         w1.copy(
                                           regions =
                                             w1.regions.updated(path, region),
                                           youngNow = w1.youngNow + path,
                                         ),
                                         reclaim,
                                       )
                        yield w2
                        end for
                  end match
            end match
          case fe0: Foreach[?, ?] =>
            /* Erasure-forced: a Foreach carries its own A for signal, key and
             * render (kyo-ui's `applyTyped` is private). */
            val fe = fe0.asInstanceOf[Foreach[Any, UI]]
            reclaim.get(path) match
                case Some(
                      old @ RegionState(_, Source.Many(prev), _, _, items, _),
                    ) if prev.signal eq fe.signal =>
                  Kyo.foldLeft(items)(
                    w.copy(regions = w.regions.updated(path, old)),
                  ) { (acc, it) =>
                    walk(it.ui, path :+ s"#${it.key}", acc, reclaim)
                  }
                case _ =>
                  val (gen, w1) = w.gen
                  for
                      values  <- fe.signal.current
                      watcher <- watch(fe.signal, path, gen)
                      items    = reconcile(fe, Vector.empty, values.toVector)
                      region   = RegionState(
                                   path,
                                   Source.Many(fe),
                                   gen,
                                   Content.Many(items.map(it => (it.key, it.ui))),
                                   items,
                                   Some(watcher),
                                 )
                      w2      <-
                        Kyo.foldLeft(items)(
                          w1.copy(
                            regions = w1.regions.updated(path, region),
                            youngNow = w1.youngNow + path,
                          ),
                        ) { (acc, it) =>
                          walk(it.ui, path :+ s"#${it.key}", acc, reclaim)
                        }
                  yield w2
                  end for
            end match
          case el: Element        =>
            val withBound: World < Sync = boundRef(el) match
                case None      => w
                case Some(ref) =>
                  reclaim.get(path) match
                      case Some(
                            old @ RegionState(_, Source.Bound(prev), _, _, _, _),
                          ) if prev eq ref =>
                        w.copy(regions = w.regions.updated(path, old))
                      case _ =>
                        val (gen, w1) = w.gen
                        for
                            cur     <- ref.current
                            watcher <- watch(ref, path, gen)
                        yield w1.copy(
                          regions = w1.regions.updated(
                            path,
                            RegionState(
                              path,
                              Source.Bound(ref),
                              gen,
                              Content.Value(cur),
                              Vector.empty,
                              Some(watcher),
                            ),
                          ),
                          youngNow = w1.youngNow + path,
                        )
                        end for
            withBound.map { w2 =>
              el match
                  case _: Select | _: Dropdown => w2
                  case _                       =>
                    Kyo.foldLeft(el.children.toSeq.zipWithIndex)(w2) {
                      case (acc, (c, i)) =>
                        walk(c, path :+ i.toString, acc, reclaim)
                    }
            }
          case _                  => w

    /** Mounts a component node at `path` (C4).
      *
      * A [[tui4s.Component]] (`ComponentSpec.Instance`): its state is the
      * `SignalRef` of `w.comps` under its state key (the path and
      * `Component.stateKey`, by default the class), or a new one with
      * `initial`. `view(state)` of the new instance gives the content at once.
      * When `reclaim` has a component of the same key at this path, its mount
      * goes on: the state, the generation and the `onMount` fiber stay.
      * Otherwise the effects of the old region at this path stop, and
      * `onMount(state)` runs in a new fiber, with a `Scope` that closes when
      * the fiber ends (at the unmount: an interrupt).
      *
      * A hook component (`ComponentSpec.Hook`, [[tui4s.Hooks.component]]): a
      * region of the same identity in `reclaim` is kept as it is (its fiber,
      * its content, its state). Otherwise a new fiber runs `body` in a `Scope`
      * and posts the UI that it gives; the fiber then waits until the unmount
      * interrupts it, which closes the `Scope`. Until the content arrives, the
      * region is `Content.Pending`. A new mount puts its path in `w.pending`:
      * the settle of this frame waits for it (`TuiOptions.pendingTimeout`). A
      * reclaimed region does not: a later frame does not wait for it again.
      */
    // claude 2026-10-13: created (C4, the C2 cut back: the prototype's
    // mountComponent, Mount.scala lines 114-154)
    // claude 2026-10-13: changed (C4, P-6): the Scaladoc of the Instance form
    // and of the pending wait
    private def mountComponent(
        spec: ComponentSpec,
        path: Loc,
        w: World,
        reclaim: Map[Loc, RegionState],
    )(using Frame): World < Sync =
      spec match
          case ComponentSpec.Instance(c)          =>
            // claude 2026-10-13: changed (C4, FC-17): the state key of the
            // component (`Component.stateKey`, the class name by default)
            val key       =
              s"${path.mkString("/")}|${Component.stateKeyOf(c)}"
            // claude 2026-10-13: changed (C4, P-6): a mount of the same key at
            // this path goes on (its generation and its onMount fiber stay),
            // so a parent that renders again (also the verify pass and the
            // repair tick, which render a UI with closures again) does not
            // run onMount again; only the view is new
            val mounted   = reclaim.get(path).collect {
              case RegionState(
                    _,
                    Source.Comp(_: ComponentSpec.Instance, k),
                    g,
                    _,
                    _,
                    Some(f),
                  ) if k == key =>
                (g, f)
            }
            val (gen, w1) = mounted.fold(w.gen)((g, _) => (g, w))
            for
                state   <- w1.comps.get(key) match
                               case Some(ref) =>
                                 Kyo.lift(ref.asInstanceOf[SignalRef[c.State]])
                               case None      =>
                                 given CanEqual[c.State, c.State] =
                                   CanEqual.derived
                                 Signal.initRef[c.State](c.initial)
                content  = c.view(state)
                effects <- mounted match
                               case Some((_, f)) =>
                                 Kyo.lift[Fiber[Any, Nothing], Sync](f)
                               case None         =>
                                 // the effects of the region that was at this
                                 // path (another key) stop; onMount runs for
                                 // the new mount
                                 startEffects(c, state, reclaim.get(path))
                region   = RegionState(
                             path,
                             Source.Comp(spec, key),
                             gen,
                             Content.Single(content),
                             Vector.empty,
                             Some(effects),
                           )
                w2      <- walk(
                             content,
                             path :+ "~",
                             w1.copy(
                               regions = w1.regions.updated(path, region),
                               comps = w1.comps.updated(
                                 key,
                                 state.asInstanceOf[SignalRef[Any]],
                               ),
                             ),
                             reclaim.removed(path),
                           )
            yield w2
            end for
          case ComponentSpec.Hook(identity, body) =>
            reclaim.get(path) match
                case Some(
                      old @ RegionState(
                        _,
                        Source.Comp(ComponentSpec.Hook(id, _), _),
                        _,
                        content,
                        _,
                        _,
                      ),
                    ) if id == identity =>
                  val w1 = w.copy(regions = w.regions.updated(path, old))
                  // claude 2026-10-13: changed (C4, P-6): a reclaimed component
                  // whose content has not come yet does not go back into
                  // `pending`: only the frame of its mount waits for it (a
                  // later render of the parent, as the repair tick, made each
                  // of its frames wait again)
                  content match
                      case Content.Single(c) =>
                        walk(c, path :+ "~", w1, reclaim)
                      case _                 => w1
                case _ =>
                  val (gen, w1) = w.gen
                  // claude 2026-10-13: changed (C4, RC-8): the fiber joins the
                  // gauge (R1 option b)
                  Fiber
                    .initUnscoped(
                      Abort
                        .run[Throwable](
                          Scope.run(
                            body()
                              .map(ui => post(path, gen, ui))
                              .andThen(Async.never[Unit]),
                          ),
                        )
                        .unit,
                    )
                    .map(f => Sync.defer { track(f); f })
                    .map { fiber =>
                      w1.copy(
                        regions = w1.regions.updated(
                          path,
                          RegionState(
                            path,
                            Source.Comp(spec, identity),
                            gen,
                            Content.Pending,
                            Vector.empty,
                            Some(fiber),
                          ),
                        ),
                        pending = w1.pending + path,
                      )
                    }
            end match
    end mountComponent

    /** Stops the effects of `old` (the region that was at the path) and runs
      * `c.onMount(state)` in a new fiber, with a `Scope` that stays open after
      * `onMount` returns and closes when the fiber ends (the unmount, or the
      * end of the run, interrupts it). The fiber joins the gauge.
      */
    // claude 2026-10-13: created (C4, P-6): the prototype's start of the
    // effects, out of mountComponent; it joins the gauge (RC-8)
    private def startEffects(
        c: Component,
        state: SignalRef[c.State],
        old: Option[RegionState],
    )(using Frame): Fiber[Any, Nothing] < Sync =
      old
        .flatMap(_.watcher)
        .fold(Kyo.unit)(_.interrupt.unit)
        .andThen(
          // claude 2026-10-13: changed (C4, P-6): the Scope stays open until
          // the mount ends (an interrupt), as for a hook component: it closed
          // when onMount returned, so an interval or a resource that onMount
          // started ended at once
          Fiber.initUnscoped(
            Abort
              .run[Throwable](
                Scope.run(c.onMount(state).andThen(Async.never[Unit])),
              )
              .unit,
          ),
        )
        .map(f => Sync.defer { track(f); f })

    /** Replaces the content of the region at `path` (already updated in `w`),
      * mounting the regions in its new content and unmounting the old ones it
      * no longer has.
      */
    def remount(w: World, path: Loc)(using Frame): World < Sync =
        val old                  = w.regions.filter((p, _) => p.isUnder(path))
        val w0                   = w.copy(regions = w.regions -- old.keys)
        val walked: World < Sync = w0.regions.get(path) match
            case Some(RegionState(_, _, _, Content.Single(c), _, _))  =>
              walk(c, path :+ "~", w0, old)
            case Some(RegionState(_, Source.Many(_), _, _, items, _)) =>
              Kyo.foldLeft(items)(w0)((acc, it) =>
                walk(it.ui, path :+ s"#${it.key}", acc, old),
              )
            case _                                                    => w0
        walked.map { w1 =>
          val stale = old
            .filterNot((p, r) => w1.regions.get(p).exists(_.gen == r.gen))
            .values
          // claude 2026-10-13: changed (C4, the C2 cut back): gcComps
          Kyo
            .foreachDiscard(stale.toSeq)(r =>
              r.watcher.fold(Kyo.unit)(_.interrupt.unit),
            )
            .andThen(gcComps(w1))
        }
    end remount

    /** Drops the state of components that are no longer mounted, and the wait
      * for the first content of a hook component that is no longer mounted (its
      * path leaves `pending`: the settle does not wait for it).
      */
    // claude 2026-10-13: created (C4, the C2 cut back)
    // claude 2026-10-13: changed (C4, P-6): also the pending paths whose
    // region is gone (a component that mounted and unmounted in one settle
    // kept the settle waiting until pendingTimeout)
    def gcComps(w: World): World =
        val live = w.regions.values.collect {
          case RegionState(
                _,
                Source.Comp(_: ComponentSpec.Instance, key),
                _,
                _,
                _,
                _,
              ) =>
            key
        }.toSet
        w.copy(
          comps = w.comps.filter((k, _) => live.contains(k)),
          pending = w.pending.filter(w.regions.contains),
        )
    end gcComps

    /** Unmounts every region (end of the run). */
    def unmountAll(w: World)(using Frame): Unit < Sync =
      Kyo.foreachDiscard(w.regions.values.toSeq)(r =>
        r.watcher.fold(Kyo.unit)(_.interrupt.unit),
      )

    /** Interrupts every fiber of this run that the gauge still holds (a done
      * fiber ignores the interrupt). The runner calls it when the run stops: a
      * watcher that a step started inside a settle or a verify pass is in the
      * step's state only until the next `remember`, so an interrupt that lands
      * there would leave it parked on `nextWith` for ever. A live watcher is
      * strongly reachable from its signal, so the weak set holds it. It does
      * not wait.
      */
    // claude 2026-10-12: created (review R2-1)
    private[tui4s] def interruptAll(using Frame): Unit < Sync =
        import scala.jdk.CollectionConverters.*
        Sync
          .defer(started.synchronized(started.asScala.toList))
          .map(fs => Kyo.foreachDiscard(fs)(_.interrupt.unit))
end Mount

/** The live-fiber gauge of the running app, for the fibers that the hooks start
  * ([[tui4s.Hooks]]: `useEffect`, `useInterval`, `useHold`, `useFold`). A
  * `Local`, so it reaches the construction of the UI, the bodies of the hook
  * components, the `onMount` of a [[tui4s.Component]], and the fibers that they
  * start. Such a fiber joins the gauge of [[Mount]], so the end of the run
  * interrupts it (`Mount.interruptAll`), also when the `Scope` that owns it
  * closes later (the kit's) or never closes (an interrupt that lands inside a
  * step). Outside a run it does nothing.
  */
// claude 2026-10-13: created (C4, RC-8: R1 option b for the hooks)
private[tui4s] object Tui4sTrack:
    private val local: Local[Fiber[Any, Nothing] => Unit] = Local.init(_ => ())

    def let[A, S](track: Fiber[Any, Nothing] => Unit)(v: A < S)(using
        Frame,
    ): A < S =
      local.let(track)(v)

    /** Adds `f` to the gauge of the run that runs the caller. */
    def apply(f: Fiber[Any, Nothing])(using Frame): Unit < Sync =
      local.use(t => Sync.defer(t(f)))
end Tui4sTrack
