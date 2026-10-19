package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import kyo.*
import kyo.UI.Ast.*
import tui4s.Component

/** Mounting and unmounting regions. A mount reads the signal's current value
  * synchronously (so the first frame needs no scheduler round trip) and starts
  * one watcher fiber that posts every later value to the runner's inbox.
  */
// claude 2026-10-19: changed: the gauge of the run, a parameter (P2, A7)
private[tui4s] final class Mount(
    inbox: Channel[Msg],
    gauge: FiberGauge,
    knobs: Knobs = Knobs.default,
):

    // claude 2026-10-13: created (C4, the C2 cut back): the hook mount posts
    // its content with it
    private def post(path: Loc, gen: Long, v: Any)(using
        Frame,
    ): Unit < Async =
      Abort.run[Closed](inbox.put(Msg.Content(path, gen, v))).unit

    /** Starts the watcher of the region at `path` (of generation `gen`). The
      * caller puts the region in `World.unarmed` ([[newWatcher]]): until its
      * `Msg.Armed` arrives, an Input step verifies it.
      */
    // claude 2026-10-19: changed: the gauge starts the watcher (P2, A7); the
    // unarmed regions are in the World (P2)
    private def watch[A](signal: Signal[A], path: Loc, gen: Long)(using
        Frame,
    ): Fiber[Any, Nothing] < Sync =
      gauge.fork(Abort.run[Closed](loop(signal, path, gen)).unit)

    /** `w` with the new region at `path` (of generation `gen`) in
      * `World.unarmed` and in the young regions of this step.
      */
    // claude 2026-10-19: created (P2): the armkey entry of a new watcher
    private def newWatcher(w: World, path: Loc, gen: Long): World =
      w.copy(
        unarmed = w.unarmed.updated(path, gen),
        youngNow = w.youngNow + path,
      )

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
        // claude 2026-10-19: changed: the arm step only posts `Msg.Armed`; the
        // runner takes the region out of `World.unarmed` (P2)
        val arm: Unit < (Async & Abort[Closed])  =
          if n == 0 then inbox.put(Msg.Armed(path, gen))
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

    /** Walks `ui` at `path`, mounting every region in it. A region of `reclaim`
      * with the same signal (or ref) is kept as is with its watcher and its
      * content.
      */
    // claude 2026-10-19: changed: the steps of Loc (P2, A6)
    def walk(ui: UI, path: Loc, w: World, reclaim: Map[Loc, RegionState])(using
        Frame,
    ): World < Sync =
      ui match
          case f: Fragment[?]    =>
            Kyo.foldLeft(f.children.toSeq.zipWithIndex)(w) {
              case (acc, (c, i)) => walk(c, path.child(i), acc, reclaim)
            }
          // claude 2026-10-13: changed (C4, the C2 cut back): a `Reactive`
          // whose signal is in the component registry is a component node
          case r: Reactive[?]    =>
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
                          path.content,
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
                                         path.content,
                                         newWatcher(
                                           w1.copy(regions =
                                             w1.regions.updated(path, region),
                                           ),
                                           path,
                                           gen,
                                         ),
                                         reclaim,
                                       )
                        yield w2
                        end for
                  end match
            end match
          // claude 2026-10-19: changed: a type pattern binds the item type
          // `a` of the Foreach, so no cast (purity plan, P1)
          case fe: Foreach[a, ?] =>
            reclaim.get(path) match
                case Some(
                      old @ RegionState(_, Source.Many(prev), _, _, items, _),
                    ) if prev.signal eq fe.signal =>
                  Kyo.foldLeft(items)(
                    w.copy(regions = w.regions.updated(path, old)),
                  ) { (acc, it) =>
                    walk(it.ui, path.item(it.key), acc, reclaim)
                  }
                case _ =>
                  val (gen, w1) = w.gen
                  for
                      values  <- fe.signal.current
                      watcher <- watch(fe.signal, path, gen)
                      items    = Mount.reconcile(fe, Vector.empty, values.toVector)
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
                          newWatcher(
                            w1.copy(regions = w1.regions.updated(path, region)),
                            path,
                            gen,
                          ),
                        ) { (acc, it) =>
                          walk(it.ui, path.item(it.key), acc, reclaim)
                        }
                  yield w2
                  end for
            end match
          case el: Element       =>
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
                        yield newWatcher(
                          w1.copy(
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
                          ),
                          path,
                          gen,
                        )
                        end for
            withBound.map { w2 =>
              el match
                  case _: Select | _: Dropdown => w2
                  case _                       =>
                    Kyo.foldLeft(el.children.toSeq.zipWithIndex)(w2) {
                      case (acc, (c, i)) =>
                        walk(c, path.child(i), acc, reclaim)
                    }
            }
          case _                 => w

    /** Mounts a component node at `path`.
      *
      * A [[tui4s.Component]] (`ComponentSpec.Instance`): its state is the
      * `SignalRef` of `w.comps` under its path and its state key
      * (`Component.stateKey`, by default the class), or a new one with
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
      * region is `Content.Pending`. A body that fails gives `UI.empty`. A new
      * mount puts its path in `w.pending`: the settle of this frame waits for
      * it (`TuiOptions.pendingTimeout`). A reclaimed region does not: a later
      * frame does not wait for it again.
      */
    // claude 2026-10-13: created (C4, the C2 cut back: the prototype's
    // mountComponent, Mount.scala lines 114-154)
    // claude 2026-10-13: changed (C4, P-6): the Scaladoc of the Instance form
    // and of the pending wait
    // claude 2026-10-13: changed (C4, review R2-5): a body that fails
    // claude 2026-10-13: changed (C4, review R2-4): `w.comps` is keyed by the
    // path and the state key
    // claude 2026-10-13: changed (C4, review R1-5): no PR name in the Scaladoc
    // claude 2026-10-19: changed: the steps of Loc (P2, A6)
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
            // claude 2026-10-13: changed (C4, review R2-4): the key of
            // `w.comps` is the path and the state key, not one string (the
            // path "0/#a/0" of a keyed item "a/0" and of the first child of
            // the item "a" was one string)
            val key       = Component.stateKeyOf(c)
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
                state   <- w1.comps.get((path, key)) match
                               case Some(ref) =>
                                 // claude 2026-10-19: changed: one KEEP
                                 // helper, no cast (P1)
                                 Kyo.lift(Mount.stateRef[c.State](ref))
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
                             path.content,
                             w1.copy(
                               regions = w1.regions.updated(path, region),
                               // claude 2026-10-19: changed: no cast (P1)
                               comps = w1.comps.updated((path, key), state),
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
                        walk(c, path.content, w1, reclaim)
                      case _                 => w1
                case _ =>
                  val (gen, w1) = w.gen
                  // A body that fails posts an empty UI, so the frame does not
                  // wait for content that cannot come (the failure is not
                  // logged yet).
                  // claude 2026-10-19: changed: the gauge starts the fiber and
                  // adds it in one bracket (P2, A7)
                  gauge
                    .fork(
                      Abort
                        .run[Throwable](
                          Scope.run(
                            body()
                              .map(ui => post(path, gen, ui))
                              .andThen(Async.never[Unit]),
                          ),
                        )
                        .map {
                          case Result.Success(_) => Kyo.unit
                          case _                 => post(path, gen, UI.empty)
                        },
                    )
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
    // claude 2026-10-19: changed: the gauge starts the fiber and adds it in
    // one bracket (P2, A7)
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
          gauge.fork(
            Abort
              .run[Throwable](
                Scope.run(c.onMount(state).andThen(Async.never[Unit])),
              )
              .unit,
          ),
        )

    /** Replaces the content of the region at `path` (already updated in `w`),
      * mounting the regions in its new content and unmounting the old ones it
      * no longer has.
      */
    // claude 2026-10-19: changed: the steps of Loc (P2, A6)
    def remount(w: World, path: Loc)(using Frame): World < Sync =
        val old                  = w.regions.filter((p, _) => p.isUnder(path))
        val w0                   = w.copy(regions = w.regions -- old.keys)
        val walked: World < Sync = w0.regions.get(path) match
            case Some(RegionState(_, _, _, Content.Single(c), _, _))  =>
              walk(c, path.content, w0, old)
            case Some(RegionState(_, Source.Many(_), _, _, items, _)) =>
              Kyo.foldLeft(items)(w0)((acc, it) =>
                walk(it.ui, path.item(it.key), acc, old),
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
            .andThen(Mount.gcComps(w1))
        }
    end remount

end Mount

private[tui4s] object Mount:
    /** Drops the state of components that are no longer mounted, and the wait
      * for the first content of a hook component that is no longer mounted (its
      * path leaves `pending`: the settle does not wait for it). A path stays in
      * `pending` only while its region is a hook component that has no content
      * yet: another region at that path (a `Reactive`, a `Component`) does not
      * post the content that the wait is for. It also drops the unarmed entry
      * of a region that is gone or that has a new generation (its watcher was
      * interrupted before its arm).
      */
    // claude 2026-10-19: changed: also the unarmed entries; a pure function in
    // the companion (P2)
    def gcComps(w: World): World =
        val live = w.regions.values.collect {
          case RegionState(
                path,
                Source.Comp(_: ComponentSpec.Instance, key),
                _,
                _,
                _,
                _,
              ) =>
            (path, key)
        }.toSet
        w.copy(
          comps = w.comps.filter((k, _) => live.contains(k)),
          pending = w.pending.filter(p => w.regions.get(p).exists(waits)),
          unarmed =
            w.unarmed.filter((p, g) => w.regions.get(p).exists(_.gen == g)),
        )
    end gcComps

    /** True when `r` is a hook component that waits for its first content. */
    // claude 2026-10-19: changed: in the companion, with gcComps (P2)
    private def waits(r: RegionState): Boolean =
      r match
          case RegionState(
                _,
                Source.Comp(_: ComponentSpec.Hook, _),
                _,
                Content.Pending,
                _,
                _,
              ) =>
            true
          case _ => false

    /** Unique keys for a `Foreach`: kyo-ui does not reject duplicates, but two
      * items on one path would collide here.
      */
    // claude 2026-10-19: changed: the item type A, not Any (P1); moved to
    // the companion, a pure function (PF-a, F8)
    private def keysOf[A](
        fe: Foreach[A, ?],
        items: Vector[A],
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
      * An item whose index moved and whose new UI equals its old UI keeps the
      * old UI value: the result is equal, and the old value keeps its identity
      * (structural sharing).
      */
    // claude 2026-10-19: changed: an item that only moved keeps its equal UI
    // value; moved to the companion (PF-a, F8)
    def reconcile[A](
        fe: Foreach[A, ?],
        old: Vector[ItemState],
        values: Vector[A],
    ): Vector[ItemState] =
        val byKey = old.map(it => it.key -> it).toMap
        keysOf(fe, values).zip(values).zipWithIndex.map { case ((k, a), i) =>
          byKey.get(k).filter(_.value == a) match
              case Some(o) if o.index == i => o
              case Some(o)                 =>
                val ui = fe.render(i, a)
                ItemState(k, i, a, if ui == o.ui then o.ui else ui)
              case None                    => ItemState(k, i, a, fe.render(i, a))
        }
    end reconcile

    /** True when the items show the values `cur`, in order. One walk, no new
      * collection.
      */
    // claude 2026-10-19: created (PF-a, F8): the compare of the verify pass
    def sameValues(items: Vector[ItemState], cur: Chunk[?]): Boolean =
      items.sizeCompare(cur.size) == 0 &&
        items.iterator.map(_.value).sameElements(cur.iterator)

    /** The state of a mounted [[tui4s.Component]] with its own `State` type.
      * `World.comps` keeps the state under the path and the state key. Only a
      * component with that state key writes it there, and one state key has one
      * `State` type (the contract of `Component.stateKey`).
      */
    // claude 2026-10-19: created: the one cast of the component state (P1)
    def stateRef[S](ref: SignalRef[?]): SignalRef[S] =
      ref.asInstanceOf[SignalRef[S]] // scalafix:ok DisableSyntax.asInstanceOf; KEEP: the contract of Component.stateKey (one key at one position has one State type)
end Mount

/** The live-fiber gauge of the running app, for the fibers that the hooks start
  * ([[tui4s.Hooks]]: `useEffect`, `useInterval`, `useHold`, `useFold`). A
  * `Local`, so it reaches the construction of the UI, the bodies of the hook
  * components, the `onMount` of a [[tui4s.Component]], and the fibers that they
  * start. Such a fiber joins the [[FiberGauge]] of the run, so the end of the
  * run interrupts it (`FiberGauge.close`), also when the `Scope` that owns it
  * closes later (the kit's) or never closes (an interrupt that lands inside a
  * step). Outside a run it is a plain `Fiber.init`.
  */
// claude 2026-10-19: changed: the Local holds the FiberGauge; `init` starts the
// fiber and adds it in one bracket (P2, A7)
private[tui4s] object Tui4sTrack:
    private val local: Local[Maybe[FiberGauge]] = Local.init(Absent)

    def let[A, S](gauge: FiberGauge)(v: A < S)(using Frame): A < S =
      local.let(Present(gauge))(v)

    /** Starts `v` in a fiber of the enclosing `Scope` (as `Fiber.init`: the
      * close of the `Scope` interrupts the fiber and waits for it) and adds the
      * fiber to the gauge of the run that runs the caller. The start and the
      * add are one bracket (as in `FiberGauge.fork`).
      */
    def init(v: => Any < Async)(using Frame): Unit < (Sync & Scope) =
      local.use {
        case Present(g) =>
          Sync.acquireReleaseWith(Fiber.init(v))(g.add)(_ => ())
        case Absent     => Fiber.init(v).unit
      }
end Tui4sTrack
