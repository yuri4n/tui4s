package tui4s.oracle

import tui4s.internal.Layout.textWidth

/** The oracle of F4: `Layout.wrap` and its `chunksByWidth` as they were at
  * commit 95bcf2a, copied without a change (only `private` is gone). They
  * measure the open line again for each word. Do not change them: the new fold
  * must give the same lines.
  */
// claude 2026-10-19: created (PF-a, F4): a private copy of the old code
private[tui4s] object WrapOracle:
    def wrap(text: String, width: Int): Vector[String] =
        val w = width.max(1)
        text.split("\n", -1).toVector.flatMap { para =>
          val words = para.split(" ", -1).toVector
          val lines = words.foldLeft(Vector.empty[String]) { (acc, word) =>
            acc.lastOption match
                case Some(last) if textWidth(last) + 1 + textWidth(word) <= w =>
                  acc.init :+ s"$last $word"
                case _ if acc.isEmpty && textWidth(word) <= w                 => Vector(word)
                case _ if textWidth(word) <= w                                => acc :+ word
                case _                                                        => acc ++ chunksByWidth(word, w)
          }
          if lines.isEmpty then Vector("") else lines
        }
    end wrap

    def chunksByWidth(word: String, w: Int): Vector[String] =
        val glyphs = Vector.unfold(0) { i =>
          if i >= word.length then None
          else
              val cp = word.codePointAt(i)
              Some((String(Character.toChars(cp)), i + Character.charCount(cp)))
        }
        glyphs.foldLeft(Vector.empty[String]) { (acc, g) =>
          acc.lastOption match
              case Some(last) if textWidth(last) + textWidth(g) <= w =>
                acc.init :+ (last + g)
              case _                                                 => acc :+ g
        }
    end chunksByWidth
end WrapOracle
