package tui4s.oracle

import kyo.UI.Ast.Foreach
import tui4s.internal.*

/** The oracle of F8: `Mount.reconcile` with its `keysOf`, and the compare of
  * the verify pass (`Runner.verifyRegion`, the `Foreach` case), as they were at
  * commit 95bcf2a, copied without a change (only `private` is gone). Do not
  * change them: the new code must give equal items and the same answer.
  */
// claude 2026-10-19: created (PF-a, F8): a private copy of the old code
private[tui4s] object ReconcileOracle:
    def keysOf[A](
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

    def reconcile[A](
        fe: Foreach[A, ?],
        old: Vector[ItemState],
        values: Vector[A],
    ): Vector[ItemState] =
        val byKey = old.map(it => it.key -> it).toMap
        keysOf(fe, values).zip(values).zipWithIndex.map { case ((k, a), i) =>
          byKey
            .get(k)
            .filter(o => o.value == a && o.index == i)
            .getOrElse(ItemState(k, i, a, fe.render(i, a)))
        }
    end reconcile

    def sameValues(items: Vector[ItemState], cur: kyo.Chunk[?]): Boolean =
      items.map(_.value) == cur.toVector
end ReconcileOracle
