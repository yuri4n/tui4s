package examples.assistant

import kyo.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.widgets.Cells

/** The pure rules of the screen: the wrap of the transcript and the metrics of
  * the layout. On the JVM and on Node.js.
  */
// claude 2026-10-19: created (L3)
class UiRulesTest extends PropertyTest[Any]:
    import Gens.{below, text}

    override protected def numSamples: Int = 200

    "Line.wrap: each line fits; the words stay in order; a wide character is never cut" - {
      forAll(text, below(40)) { (t, n) =>
        val width = n + 2
        val lines = Line.wrap(t, width)
        val words = (s: String) =>
          s.split("\\s+").nn.map(_.nn).filter(_.nonEmpty).mkString
        assert(lines.forall(l => Cells.width(l) <= width))
        assert(words(lines.mkString) == words(t.replace("\t", " ")))
      }
    }

    "the metrics of a screen: the rows of the transcript, the log pane, the rows of the hints" in {
      val hints =
        Chunk("Ctrl-P" -> "commands", "Ctrl-N" -> "new chat", "Esc" -> "stop")
      assert(
        Metrics.of(ScreenSize(100, 30), Pane.Hidden, 1) == Metrics(
          100 - Metrics.listCells - 4,
          21,
        ) &&
          Metrics.of(ScreenSize(100, 30), Pane.Shown, 1).textRows == 13 &&
          Metrics.of(ScreenSize(20, 5), Pane.Shown, 3) == Metrics(10, 3) &&
          Metrics.hintRows(hints, 80) == 1 && Metrics.hintRows(
            hints,
            30,
          ) == 2 &&
          Metrics.hintRows(hints, 10) == 3,
      )
    }

    "the lines of a conversation: the writers, the text, a stop, an error, the empty hint" in {
      val at = Instant.Epoch
      val c  = Conversation(
        ConversationId.parse("u1").get,
        "t",
        Chunk(
          Message(Role.User, "hi", at, MessageStatus.Done),
          Message(
            Role.Assistant,
            "part",
            at,
            MessageStatus.Failed(ChatError.Cancelled),
          ),
          Message(Role.User, "again", at, MessageStatus.Done),
          Message(Role.Assistant, "", at, MessageStatus.Failed(ChatError.Auth)),
        ),
        at,
      )
      val ls = Line.of(Present(c), 200)
      assert(
        ls.map(_.text).take(8) ==
          Chunk("You", "hi", "", "Assistant", "part", "■ stopped", "", "You") &&
          ls.last.kind == Line.Kind.Note && ls.last.text.startsWith(
            "✗ the provider refused",
          ) &&
          Line.of(Absent, 200).map(_.kind) == Chunk(Line.Kind.Hint),
      )
    }
end UiRulesTest
