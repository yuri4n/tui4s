package examples.assistant

import kyo.*
import kyo.test.prop.Gen

/** The generators of the domain types: explicit `Gen` values (no derived and no
  * given `Gen`, AGENTS.md "Purity"). A text joins pieces that test the edges of
  * the formats: line breaks, a backslash, the escape of a line feed, wide
  * characters and an emoji.
  */
// claude 2026-10-19: created (L3)
object Gens:
    /** A number in 0 to `n - 1`. */
    def below(n: Int): Gen[Int] = Gen.int.map(i => Math.floorMod(i, n))

    /** The pieces of a text. */
    val piece: Gen[String] = Gen.oneOf(
      "",
      "a",
      "hello world",
      " ",
      "\t",
      "\n",
      "\r\n",
      "\\",
      "\\n",
      "é",
      "中文",
      "👍",
      "data: x",
      "message user",
    )

    /** A text of 0 to 11 pieces. */
    val text: Gen[String] =
      below(12).flatMap(n => Gen.listOfN(n, piece)).map(_.mkString)

    /** An instant from the epoch to about the year 2100, with nanoseconds. */
    val instant: Gen[Instant] =
      Gen.long.map(l =>
        Instant.Epoch + Math.floorMod(l, 4_000_000_000_000_000_000L).nanos,
      )

    /** A count of tokens. */
    val count: Gen[Int] = below(100_000)

    /** An error of a chat. */
    val chatError: Gen[ChatError] =
      below(6).flatMap {
        case 0 => Gen.const(ChatError.Auth)
        case 1 => Gen.const(ChatError.Cancelled)
        case 2 => Gen.const(ChatError.RateLimited(Absent))
        case 3 =>
          below(1_000_000).map(ms => ChatError.RateLimited(Present(ms.millis)))
        case 4 => text.map(ChatError.Network(_))
        case _ => text.map(ChatError.Protocol(_))
      }

    /** A status of a message. */
    val status: Gen[MessageStatus] =
      below(3).flatMap {
        case 0 => Gen.const(MessageStatus.Done)
        case 1 => Gen.const(MessageStatus.Streaming)
        case _ => chatError.map(MessageStatus.Failed(_))
      }

    /** A message. */
    val message: Gen[Message] =
      Gen
        .zip4(Gen.boolean, text, instant, status)
        .flatMap((user, content, at, st) =>
          Gen
            .zip(count, count)
            .map((in, out) =>
              Message(
                if user then Role.User else Role.Assistant,
                content,
                at,
                st,
                Usage(in, out),
              ),
            ),
        )

    /** An id of a conversation. */
    val id: Gen[ConversationId] =
      Gen.string.map(s => ConversationId.parse(("c" + s).take(64)).get)

    /** A conversation of 0 to 5 messages. */
    val conversation: Gen[Conversation] =
      Gen
        .zip3(id, text, instant)
        .flatMap((i, t, at) =>
          below(6)
            .flatMap(n => Gen.listOfN(n, message))
            .map(ms => Conversation(i, t, ms, at)),
        )

    /** The settings. */
    val settings: Gen[Settings] =
      Gen
        .zip4(
          Gen.oneOf(ProviderChoice.values.toSeq*),
          text,
          Gen.oneOf[Maybe[Double]](
            Absent,
            Present(0.0),
            Present(0.7),
            Present(1.25),
            Present(2.0),
          ),
          text,
        )
        .map((p, m, t, s) => Settings(p, m, t, s))
end Gens
