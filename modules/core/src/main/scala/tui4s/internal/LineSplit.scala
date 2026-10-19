package tui4s.internal

/** The line split of one forwarding stream of [[OutputCapture]], as a value:
  * the bytes of the line so far, and whether the last byte was a '\r'.
  *
  * A '\n' ends a line; a '\r' just before it is not part of the line. A '\r'
  * that is not followed by '\n' starts the line again (a progress bar writes
  * its updates this way). A line is cut at [[LineSplit.MaxLine]] bytes, only
  * before the first byte of a UTF-8 sequence, so a long-running capture keeps
  * little in memory.
  *
  * {{{
  * val (split, lines) = LineSplit.empty.feed("one\ntw".getBytes, 0, 6)
  * // lines: the bytes of "one"; split.partial: the bytes of "tw"
  * }}}
  */
// claude 2026-10-19: created (P6): the splitter of the capture as a pure
// value
private[tui4s] final case class LineSplit(
    partial: Vector[Byte],
    carriage: Boolean,
):
    /** The split after the bytes `b(off until off + len)`, and the lines that
      * they end, in order (the bytes of each line, without its line break).
      */
    def feed(
        b: Array[Byte],
        off: Int,
        len: Int,
    ): (LineSplit, Vector[Vector[Byte]]) =
      (off until off + len).foldLeft((this, Vector.empty[Vector[Byte]])) {
        case ((split, lines), i) =>
          val (next, line) = split.put(b(i))
          (next, line.fold(lines)(lines :+ _))
      }

    /** The split after the byte `b`, and the line that `b` ends. */
    def put(b: Byte): (LineSplit, Option[Vector[Byte]]) =
        val c = b & 0xff
        if c == '\n' then (LineSplit.empty, Some(partial))
        else
            val line = if carriage then Vector.empty[Byte] else partial
            if c == '\r' then (LineSplit(line, carriage = true), None)
            else if line.size >= LineSplit.MaxLine && (c & 0xc0) != 0x80 then
                (LineSplit(Vector(b), carriage = false), Some(line))
            else (LineSplit(line :+ b, carriage = false), None)
        end if
    end put

    /** The empty split, and the line without its line break that this split
      * had (none when it had no byte).
      */
    def drain: (LineSplit, Option[Vector[Byte]]) =
      (LineSplit.empty, Option.when(partial.nonEmpty)(partial))
end LineSplit

private[tui4s] object LineSplit:
    /** No byte, and no '\r' before. */
    val empty: LineSplit = LineSplit(Vector.empty, carriage = false)

    /** The longest line the capture keeps, in bytes; a longer one is cut. */
    val MaxLine: Int = 8192
end LineSplit
