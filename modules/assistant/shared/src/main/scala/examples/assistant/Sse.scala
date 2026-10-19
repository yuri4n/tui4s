package examples.assistant

import kyo.*

import java.nio.charset.StandardCharsets.UTF_8

/** One event of a server-sent event stream: its type (`event:`, "message" by
  * default) and its data (the `data:` lines, joined with line feeds).
  */
// claude 2026-10-19: created (L3)
final case class SseEvent(event: String, data: String) derives CanEqual

/** The pure parser of a server-sent event stream (the SSE format of the HTML
  * standard), in two folds: [[Sse.Lines]] cuts the bytes into lines, and
  * [[Sse.Events]] makes the events of the lines. The law (`SsePropertyTest`):
  * any cut of the bytes into chunks gives the same events as the whole bytes.
  * The HTTP interpreter of a platform runs `Lines` on the bytes of the body;
  * the providers run `Events` on the lines ([[SseProvider]]).
  */
// claude 2026-10-19: created (L3)
object Sse:
    /** The bytes after the last line feed: the start of the next line. */
    final case class Lines(carry: Chunk[Byte])

    /** The fold of the bytes into lines. */
    object Lines:
        /** No bytes yet. */
        val empty: Lines = Lines(Chunk.empty)

        /** The complete lines of the bytes so far (UTF-8, without the line feed
          * and a carriage return before it), and the rest. A line feed is never
          * inside a UTF-8 character, so a cut at any place gives the same
          * lines.
          */
        def feed(state: Lines, bytes: Chunk[Byte]): (Lines, Chunk[String]) =
            val all = state.carry ++ bytes
            val cut = all.lastIndexOf('\n'.toByte)
            if cut < 0 then (Lines(all), Chunk.empty)
            else
                val text = String(all.take(cut).toArray, UTF_8)
                (
                  Lines(all.drop(cut + 1)),
                  Chunk.from(
                    text.split("\n", -1).nn.map(_.nn.stripSuffix("\r")),
                  ),
                )
            end if
        end feed

        /** The last line, when the bytes do not end with a line feed. */
        def end(state: Lines): Chunk[String] =
          if state.carry.isEmpty then Chunk.empty
          else Chunk(String(state.carry.toArray, UTF_8).stripSuffix("\r"))
    end Lines

    /** The fields of the event that the lines build: its type and its data
      * lines.
      */
    final case class Events(event: Maybe[String], data: Chunk[String])

    /** The fold of the lines into events. */
    object Events:
        /** No field yet. */
        val empty: Events = Events(Absent, Chunk.empty)

        /** The state after `line`, and the event that an empty line ends (an
          * event with no data line is not one). A line that starts with ":" is
          * a comment. `id` and `retry` are read and dropped.
          */
        def feed(state: Events, line: String): (Events, Maybe[SseEvent]) =
            if line.isEmpty then
                (
                  empty,
                  Maybe.when(state.data.nonEmpty)(
                    SseEvent(
                      state.event.getOrElse("message"),
                      state.data.mkString("\n"),
                    ),
                  ),
                )
            else if line.startsWith(":") then (state, Absent)
            else
                val (name, value) = line.indexOf(':') match
                    case -1 => (line, "")
                    case i  => (line.take(i), line.drop(i + 1).stripPrefix(" "))
                name match
                    case "event" => (state.copy(event = Present(value)), Absent)
                    case "data"  =>
                      (state.copy(data = state.data :+ value), Absent)
                    case _       => (state, Absent)
            end if
        end feed
    end Events

    /** The events of the bytes `chunks`, by the two folds: the oracle of the
      * law. A last event with no empty line after it is dropped, as the
      * standard says.
      */
    def parse(chunks: Chunk[Chunk[Byte]]): Chunk[SseEvent] =
        val (rest, lines) =
          chunks.foldLeft((Lines.empty, Chunk.empty[String])) {
            case ((s, acc), c) =>
              val (s2, ls) = Lines.feed(s, c)
              (s2, acc ++ ls)
          }
        (lines ++ Lines.end(rest))
          .foldLeft((Events.empty, Chunk.empty[SseEvent])) {
            case ((s, acc), l) =>
              val (s2, e) = Events.feed(s, l)
              (s2, e.fold(acc)(acc :+ _))
          }
          ._2
    end parse
end Sse
