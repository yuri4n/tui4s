package tui4s.internal

import kyo.*
import kyo.UI.Ast.*

/** Mounting and unmounting regions. A mount reads the signal's current value
  * synchronously (so the first frame needs no scheduler round trip) and starts
  * one watcher fiber that posts every later value to the runner's inbox.
  */
final class Mount(inbox: Channel[Msg]):

    private def watch[A](signal: Signal[A], path: Loc, gen: Long)(using
        Frame,
    ): Fiber[Any, Nothing] < Sync =
      Fiber.initUnscoped(
        Abort
          .run[Closed](
            signal.observe(v => inbox.put(Msg.Content(path, gen, v))),
          )
          .unit,
      )

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
          case r: Reactive[?]     =>
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
                                   w1.copy(regions =
                                     w1.regions.updated(path, region),
                                   ),
                                   reclaim,
                                 )
                  yield w2
                  end for
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
                          w1.copy(regions = w1.regions.updated(path, region)),
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
                        yield w1.copy(regions =
                          w1.regions.updated(
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
          Kyo
            .foreachDiscard(stale.toSeq)(r =>
              r.watcher.fold(Kyo.unit)(_.interrupt.unit),
            )
            .andThen(w1)
        }
    end remount

    /** Unmounts every region (end of the run). */
    def unmountAll(w: World)(using Frame): Unit < Sync =
      Kyo.foreachDiscard(w.regions.values.toSeq)(r =>
        r.watcher.fold(Kyo.unit)(_.interrupt.unit),
      )
end Mount
