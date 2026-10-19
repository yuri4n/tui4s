package tui4s.use

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}

import java.nio.charset.StandardCharsets.UTF_8

/** The law of the line split of a process output: a cut of the bytes at any
  * places gives the lines of the whole output (UTF-8 text with wide characters
  * and "\r\n").
  */
// claude 2026-10-19: created (L2a)
class LinesPropertyTest extends PropertyTest[Any]:

    private val word: Gen[String] =
      Gen.oneOf("", "a", "hello world", "é", "中文", "👍", "x\r", "tab\there")

    "any cut of the bytes gives the same lines" - {
      forAll(Gen.listOfN(8, word), Gen.listOfN(6, Gen.int), Gen.boolean) {
        (words, cuts, endsWithBreak) =>
          val text           = words.mkString("\n") + (if endsWithBreak then "\n" else "")
          val bytes          = Chunk.from(text.getBytes(UTF_8))
          val points         =
            Chunk.from(
              cuts.map(c => Math.floorMod(c, bytes.size + 1)).sorted.distinct,
            )
          val pieces         =
            (0 +: points :+ bytes.size)
              .zip(points :+ bytes.size)
              .map((a, b) => bytes.slice(a, b))
          val (carry, lines) =
            pieces.foldLeft((Chunk.empty[Byte], Chunk.empty[String])) {
              case ((c, acc), piece) =>
                val (rest, ls) = Lines.split(c, piece)
                (rest, acc ++ ls)
            }
          val all            = lines ++ Chunk.from(Lines.last(carry).toList)
          val expect         =
            Chunk.from(text.split("\n", -1)).map(_.stripSuffix("\r"))
          val wanted         =
            if expect.lastOption.contains("") then expect.dropRight(1)
            else expect
          assert(all == wanted)
      }
    }
end LinesPropertyTest
