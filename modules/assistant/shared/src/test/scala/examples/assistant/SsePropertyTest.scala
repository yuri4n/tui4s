package examples.assistant

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}

import java.nio.charset.StandardCharsets.UTF_8

/** The law of the SSE parser ([[Sse]]): any cut of the bytes of a stream into
  * chunks gives the same events as the whole bytes; and the events are the ones
  * that the stream was made of (`data:` lines joined with line feeds, comments
  * dropped, "\r\n" or "\n" at the end of a line). 200 samples, on the JVM and
  * on Node.js.
  */
// claude 2026-10-19: created (L3)
class SsePropertyTest extends PropertyTest[Any]:
    import Gens.below

    override protected def numSamples: Int = 200

    /** One line of data: no line break in it. */
    private val dataLine: Gen[String] =
      Gen.oneOf(
        "",
        "x",
        """{"type":"content_block_delta","delta":{"text":"中文 👍"}}""",
        "[DONE]",
        " leading space",
        "a: b",
        "é",
      )

    /** An event: its type (or none) and its data lines (one or more). */
    private val event: Gen[(Maybe[String], Chunk[String])] =
      Gen.zip(
        Gen.oneOf[Maybe[String]](
          Absent,
          Present("message_start"),
          Present("ping"),
        ),
        below(3).flatMap(n => Gen.listOfN(n + 1, dataLine)),
      )

    /** The text of the events, with a comment, and with "\r\n" or "\n". */
    private def text(
        events: Chunk[(Maybe[String], Chunk[String])],
        crlf: Boolean,
    ) =
        val nl = if crlf then "\r\n" else "\n"
        events
          .map((e, data) =>
            ": a comment" + nl +
              e.fold("")(n => s"event: $n$nl") +
              data.map(d => s"data: $d$nl").mkString + nl,
          )
          .mkString
    end text

    "any cut of the bytes gives the same events, the events of the text" - {
      forAll(
        below(6).flatMap(n => Gen.listOfN(n, event)),
        Gen.listOfN(8, Gen.int),
        Gen.boolean,
      ) { (events, cuts, crlf) =>
        val bytes  = Chunk.from(text(events, crlf).getBytes(UTF_8))
        val points = Chunk.from(
          cuts.map(c => Math.floorMod(c, bytes.size + 1)).sorted.distinct,
        )
        val pieces = (0 +: points)
          .zip(points :+ bytes.size)
          .map((a, b) => bytes.slice(a, b))
        val whole  = Sse.parse(Chunk(bytes))
        val want   = events.map((e, data) =>
          SseEvent(e.getOrElse("message"), data.mkString("\n")),
        )
        assert(Sse.parse(pieces) == whole && whole == want)
      }
    }
end SsePropertyTest
