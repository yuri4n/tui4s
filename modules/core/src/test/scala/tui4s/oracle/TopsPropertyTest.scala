package tui4s.oracle

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** F2: `Loc.tops` must give the same paths, in the same order, as the old
  * filters of `Paint` and `Runner` ([[TopsOracle]]). The paths are short and
  * come from a small alphabet, so a set has prefixes, duplicates, the empty
  * path (the screen) and deep chains.
  */
// claude 2026-10-19: created (PF-a, F2)
class TopsPropertyTest extends PropertyTest[Any]:
    import TopsPropertyTest.*
    override protected def numSamples: Int = 200

    "Loc.tops equals the old filters" - {
      forAll(paths)(ps => assert(Loc.tops(ps) == TopsOracle.runner(ps)))
      forAll(paths)(ps => assert(Loc.tops(ps.distinct) == TopsOracle.paint(ps)))
    }
end TopsPropertyTest

object TopsPropertyTest:
    private val step: Gen[String] = Gen.oneOf("0", "1", "~", "#a")

    /** A path of depth 0 to 6. */
    val path: Gen[Loc] =
      UiGen.between(0, 6).flatMap(n => Gen.listOfN(n, step)).map(_.toVector)

    /** 0 to 16 paths. */
    val paths: Gen[Vector[Loc]] =
      UiGen.between(0, 16).flatMap(n => Gen.listOfN(n, path)).map(_.toVector)
end TopsPropertyTest
