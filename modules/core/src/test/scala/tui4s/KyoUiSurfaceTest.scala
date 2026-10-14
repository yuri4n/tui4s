package tui4s

import kyo.*
import kyo.test.*

/** The kyo-ui surface guard (RC-12). When kyo-ui changes a type of `UI.Ast`, a
  * case of `Style.Prop`, a field of `Reactive` or `Foreach`, a case of
  * `UI.Bound`, or when kyo-core changes `Signal.currentWith` or `nextWith`,
  * this test fails and names the line. Then read the change, update the runner,
  * and update `src/test/resources/kyo-ui-surface.txt`.
  */
// claude 2026-10-12: created; changed: the C2 items
class KyoUiSurfaceTest extends Test[Any]:

    "the UI.Ast types, the Style.Prop cases, the region fields, the Bound cases and the Signal methods are the checked-in list"
      .timeout(120.seconds) in {
      val now   = KyoUiSurface.current
      val base  = KyoUiSurface.checkedIn
      val diffs = KyoUiSurface.compare(now, base)
      assert(now.size > 100, s"the surface has only ${now.size} items")
      assert(
        diffs.isEmpty,
        "the kyo-ui surface is not the checked-in list:\n" + diffs.mkString(
          "\n",
        ),
      )
    }

    "compare reports an added, a removed and a changed line" in {
      import KyoUiSurface.Item
      val base = List(Item("k", "a", "1"), Item("k", "b", "1"))
      val now  = List(Item("k", "a", "2"), Item("k", "c", "1"))
      val out  = KyoUiSurface.compare(now, base)
      assert(
        out == List(
          "ADDED\tk\tc\t1",
          "REMOVED\tk\tb\t1",
          "CHANGED\tk\ta\t2\twas: 1",
        ),
      )
    }
end KyoUiSurfaceTest
