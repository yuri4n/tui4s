package examples.assistant

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.widgets.Cells

/** The laws of the pure domain, 200 samples each, on the JVM and on Node.js:
  * the fold of a streamed reply ([[Transcript]]), the title rule ([[Title]]),
  * the text forms ([[ConversationCodec]], [[SettingsCodec]], [[Escape]]) and
  * the start rule of the settings.
  */
// claude 2026-10-19: created (L3)
class DomainPropertyTest extends PropertyTest[Any]:
    import Gens.*

    override protected def numSamples: Int = 200

    private val deltas: Gen[Chunk[String]] =
      below(10).flatMap(n => Gen.listOfN(n, text))

    private val usage: Gen[Usage] = Gen.zip(count, count).map(Usage(_, _))

    private val start = Transcript.start(Instant.Epoch)

    private def fold(events: Chunk[ChatEvent]): Message =
      events.foldLeft(start)(Transcript.apply)

    "Transcript: the deltas joined give the text; the message streams" - {
      forAll(deltas) { ds =>
        val m = fold(ds.map(ChatEvent.TextDelta(_)))
        assert(m.content == ds.mkString && m.status == MessageStatus.Streaming)
      }
    }

    "Transcript: Stop ends the message; a later event changes nothing" - {
      forAll(deltas, usage, deltas) { (before, u, after) =>
        val events = before.map(ChatEvent.TextDelta(_)) ++
          Chunk(ChatEvent.Stop(StopReason.EndTurn, u)) ++
          after.map(ChatEvent.TextDelta(_)) :+
          ChatEvent.Error(ChatError.Cancelled)
        val m      = fold(events)
        assert(
          m.content == before.mkString && m.status == MessageStatus.Done(
            StopReason.EndTurn,
          ) &&
            m.usage == u,
        )
      }
    }

    "Transcript: an Error marks the message Failed and keeps the partial text" - {
      forAll(deltas, chatError, deltas) { (before, e, after) =>
        val events = before.map(ChatEvent.TextDelta(_)) ++
          Chunk(ChatEvent.Error(e)) ++ after.map(ChatEvent.TextDelta(_))
        val m      = fold(events)
        assert(
          m.content == before.mkString && m.status == MessageStatus.Failed(e),
        )
      }
    }

    "Transcript.reply folds only a streaming reply at the end" - {
      forAll(conversation, text) { (c, t) =>
        val after = Transcript.reply(c, ChatEvent.TextDelta(t))
        c.messages.lastOption match
            case Some(m)
                if m.role == Role.Assistant && m.status == MessageStatus.Streaming =>
              assert(after.messages.last.content == m.content + t)
            case _ => assert(after == c)
      }
    }

    "Title: it fits in its cells, on one line; a text that fits is its own title" - {
      forAll(text, below(30)) { (t, n) =>
        val max   = n + 1
        val title = Title.of(t, max)
        val line  = t.split("\\s+").nn.filter(_.nn.nonEmpty).mkString(" ")
        assert(
          Cells.width(title) <= max.max(Cells.width(Title.empty)) &&
            !title.exists(c => c == '\n' || c == '\r'),
        )
        if line.isEmpty then assert(title == Title.empty)
        else if Cells.width(line) <= max then assert(title == line)
        else assert(title.endsWith("…") && line.startsWith(title.dropRight(1)))
      }
    }

    "ConversationCodec: decode(encode(c)) is c" - {
      forAll(conversation) { c =>
        assert(
          ConversationCodec.decode(ConversationCodec.encode(c)) == Right(c),
        )
      }
    }

    "SettingsCodec: decode(encode(s)) is s" - {
      forAll(settings) { s =>
        assert(SettingsCodec.decode(SettingsCodec.encode(s)) == Right(s))
      }
    }

    "Escape: one line, and undo gives the text back" - {
      forAll(text) { t =>
        val e = Escape(t)
        assert(
          !e.exists(c => c == '\n' || c == '\r') && Escape.undo(e) == Right(t),
        )
      }
    }

    // claude 2026-10-19: changed: the prompt and the temperature stay (L3 review)
    "Settings.pick: --fake wins; else the stored provider with a key; else the first with a key; the prompt stays" - {
      forAll(Gen.boolean, Gen.listOfN(3, Gen.boolean), settings) {
        (force, has, stored) =>
          val available = Chunk
            .from(ProviderChoice.values)
            .zip(has)
            .collect { case (p, true) => p }
          val forced    = if force then Present(ProviderChoice.Fake) else Absent
          val s         = Settings.pick(Present(stored), available, forced)
          assert(
            s.systemPrompt == stored.systemPrompt &&
              s.temperature == stored.temperature,
          )
          if force then assert(s.provider == ProviderChoice.Fake)
          else if available.contains(stored.provider) then assert(s == stored)
          else
              assert(
                s.provider == ProviderChoice.values
                  .find(available.contains)
                  .getOrElse(ProviderChoice.Fake),
              )
          end if
      }
    }
end DomainPropertyTest
