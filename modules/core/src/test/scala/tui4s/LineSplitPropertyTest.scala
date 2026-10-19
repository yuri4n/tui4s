package tui4s

import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.LineSplit

import java.nio.charset.StandardCharsets

/** Property tests of [[LineSplit]], the splitter of the output capture: the
  * lines do not depend on how the writes cut the bytes, and no line holds a
  * line break.
  */
// claude 2026-10-19: created (P6): the splitter is a pure value
class LineSplitPropertyTest extends PropertyTest[Any]:
    import LineSplitPropertyTest.*

    "LineSplit" - {
      // One write or one write per byte: the same lines, the same rest.
      forAll(text) { s =>
          val bytes = s.getBytes(StandardCharsets.UTF_8)
          val whole = LineSplit.empty.feed(bytes, 0, bytes.length)
          val byOne = bytes.indices.foldLeft(
            (LineSplit.empty, Vector.empty[Vector[Byte]]),
          ) { case ((split, lines), i) =>
            val (next, more) = split.feed(bytes, i, 1)
            (next, lines ++ more)
          }
          assert(whole == byOne)
      }

      // No line holds a '\n' or a '\r'.
      forAll(text) { s =>
          val bytes      = s.getBytes(StandardCharsets.UTF_8)
          val (_, lines) = LineSplit.empty.feed(bytes, 0, bytes.length)
          assert(lines.forall(l => !l.contains('\n'.toByte) && !l.contains('\r'.toByte)))
      }
    }
end LineSplitPropertyTest

object LineSplitPropertyTest:
    /** One piece of captured output: text, line breaks, a progress update. */
    val piece: Gen[String] = Gen.oneOf(
      "a",
      "line",
      " ",
      "\n",
      "\r\n",
      "\r",
      "50%\r",
      "é",       // a 2-byte UTF-8 sequence
      "中",       // a 3-byte UTF-8 sequence
      "👍", // a 4-byte UTF-8 sequence
    )

    /** A text of 0 to `size` pieces. */
    val text: Gen[String] = Gen.list(piece).map(_.mkString)
end LineSplitPropertyTest
