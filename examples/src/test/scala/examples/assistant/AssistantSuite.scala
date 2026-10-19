package examples.assistant

import kyo.*
import kyo.UI.{Keyboard, Modifiers}
import munit.FunSuite
import tui4s.Tui
import tui4s.testkit.{Headless, Screen}
import tui4s.use.Storage

/** The assistant on the headless test kit, wired as the main wires it, with the
  * interpreters in memory (the storage, the keys) and the scripted provider (a
  * gate that the test opens one piece at a time, where a frame in the middle of
  * a reply counts): a send and the reply that streams, a stop, an error, the
  * follow rule of the transcript, the conversations, the palette, the settings
  * (a key never shows), the log pane, a resize, the keys of the composer, the
  * help and the quit.
  */
// claude 2026-10-19: created (L3); changed: the gate, the follow rule (L3 review)
class AssistantSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 60 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(60.seconds)(v).getOrThrow

    // claude 2026-10-19: changed: start: the looks, as the main gives them (L4)
    // region: test
    /** The app on a `width` x `height` screen: the storage and the keys in
      * memory, the scripted provider `fake` (also as the real providers).
      */
    private def start(
        fake: ChatProvider = FakeProvider.make(delay = 5.millis),
        keys: Map[ProviderChoice, String] = Map.empty,
        width: Int = 100,
        height: Int = 30,
    )(using Frame): Headless < (Async & Scope) =
      Storage.inMemory.map { storage =>
        val (files, secrets) = Memory.wiring(storage, keys)
        Headless.start(width, height, stylesheet = Look.palette)(quit =>
          Tui.size.map(size =>
            Memo.run(
              Env.runLayer(
                files,
                secrets,
                Wiring.conversations,
                Wiring.log,
                Wiring.providers((_, _) => fake, fake, StartMode.Detect),
              )(
                AssistantUi.run(
                  StartMode.Detect,
                  quit,
                  size.map(t => ScreenSize(t.width, t.height)),
                ),
              ),
            ),
          ),
        )
      }
    // endregion: test

    private val done = "\\d+ in, [1-9]\\d* out".r

    /** True when the status bar shows the tokens of a finished reply. */
    private def finished(s: Screen): Boolean = done.findFirstIn(s.text).nonEmpty

    private val ctrl = Modifiers(ctrl = true)

    /** Types `text` into the composer and presses Enter. */
    private def send(h: Headless, text: String)(using Frame): Unit < Async =
      h.typeText(text).andThen(h.key(Keyboard.Enter)).unit

    private def wait(h: Headless)(p: Screen => Boolean)(using Frame) =
      h.awaitScreen(p, 10.seconds).andThen(h.screen)

    test(
      "the first screen: the chats, the hint, the composer, the status bar, the hints",
    ) {
      val s = run(start().map(_.screen))
      assert(s.contains("Chats") && s.contains("› New chat"), s.text)
      assert(s.contains("Type a message below."), s.text)
      assert(s.contains("Message · Enter sends"), s.text)
      assert(
        s.contains("Fake · fake-1 · 0 in, 0 out") && s.contains("ready"),
        s.text,
      )
      assert(s.contains("Ctrl-P commands") && s.contains("Ctrl-Q quit"), s.text)
      assert(s.rows.last.contains("Ctrl-P commands"), s.text)
    }

    test(
      "type hi, Enter: the message and the streamed reply show; the title and the tokens follow",
    ) {
      val s = run(start().map(h => send(h, "hi").andThen(wait(h)(finished))))
      assert(s.contains("You") && s.contains("Assistant"), s.text)
      assert(s.contains("You said: \"hi\"."), s.text)
      assert(s.contains("› hi"), s.text)
      assert(!s.contains("Type a message below."), s.text)
    }

    // claude 2026-10-19: changed: a gate, not the clock (L3 review)
    test(
      "the reply grows in place: a frame in the middle has part of it and the spinner",
    ) {
      val (mid, end) = run {
        PieceGate.init.map(gate =>
          start(fake = gate.provider()).map(h =>
            for
                _   <- send(h, "hi")
                _   <- gate.release(3)
                mid <- wait(h)(_.contains("You said: \"hi\"."))
                _   <- gate.release(100)
                end <- wait(h)(finished)
            yield (mid, end),
          ),
        )
      }
      assert(mid.contains("streaming") && !mid.contains("stop me."), mid.text)
      assert(end.contains("ready") && end.text.contains("stop"), end.text)
    }

    // claude 2026-10-19: changed: a gate, not the clock (L3 review)
    test("Esc stops the reply: its text stays, marked stopped") {
      val s = run {
        PieceGate.init.map(gate =>
          start(fake = gate.provider()).map(h =>
            for
                _ <- send(h, "hi")
                _ <- gate.release(3)
                _ <- wait(h)(_.contains("You said: \"hi\"."))
                _ <- h.key(Keyboard.Escape)
                s <- wait(h)(_.contains("■ stopped"))
            yield s,
          ),
        )
      }
      assert(s.contains("You said") && s.contains("ready"), s.text)
      assert(!s.contains("stop me."), s.text)
    }

    /** The rows of the transcript that show a line of [[PieceGate.lines]]. */
    private def lines(s: Screen): Seq[String] =
      s.rows.filter(_.contains("line "))

    // claude 2026-10-19: created (L3 review)
    test(
      "the transcript follows the end; after a scroll up it stays while the reply grows; f follows again",
    ) {
      val (followed, up, grown, again) = run {
        PieceGate.init.map(gate =>
          start(fake = gate.provider(PieceGate.lines(60))).map(h =>
            for
                _        <- send(h, "go")
                _        <- gate.release(40)
                followed <- wait(h)(_.contains("line 40"))
                // Shift-Tab: from the composer to the transcript
                _        <- h.key(Keyboard.Tab, Modifiers(shift = true))
                _        <- h.key(Keyboard.ArrowUp)
                _        <- h.key(Keyboard.ArrowUp)
                up       <- wait(h)(s => lines(s) != lines(followed))
                _        <- gate.release(20)
                grown    <- wait(h)(finished)
                _        <- h.key(Keyboard.Char('f'))
                again    <- wait(h)(_.contains("line 60"))
            yield (followed, up, grown, again),
          ),
        )
      }
      assert(!followed.contains("line 41"), followed.text)
      assert(lines(grown) == lines(up), s"${up.text}\n${grown.text}")
      assert(!grown.contains("line 60"), grown.text)
      assert(again.contains("line 60"), again.text)
    }

    test("an error of the provider shows under the partial reply") {
      val s = run(
        start().map(h =>
          send(h, "/fail").andThen(wait(h)(_.contains("✗ network"))),
        ),
      )
      assert(s.contains("This reply fails half way"), s.text)
      assert(s.contains("the fake provider failed on purpose"), s.text)
    }

    test(
      "Ctrl-N starts a chat; Ctrl-PgDn and Ctrl-PgUp switch; Ctrl-R renames; Ctrl-D deletes after a yes",
    ) {
      val (two, back, renamed, deleted) = run {
        start().map(h =>
          for
              _       <- send(h, "first")
              _       <- wait(h)(finished)
              _       <- h.key(Keyboard.Char('n'), ctrl)
              two     <- wait(h)(_.contains("› New chat"))
              _       <- h.key(Keyboard.PageDown, ctrl)
              back    <- wait(h)(_.contains("› first"))
              _       <- h.key(Keyboard.Char('r'), ctrl)
              _       <- wait(h)(_.contains("A new title for the chat"))
              _       <- h.typeText("renamed")
              _       <- h.key(Keyboard.Enter)
              renamed <- wait(h)(_.contains("› renamed"))
              _       <- h.key(Keyboard.Char('d'), ctrl)
              _       <- wait(h)(_.contains("Delete the chat \"renamed\"?"))
              _       <- h.key(Keyboard.Char('y'))
              deleted <- wait(h)(s => !s.contains("renamed"))
          yield (two, back, renamed, deleted),
        )
      }
      assert(
        two.contains("  first") && two.contains("Type a message below."),
        two.text,
      )
      assert(back.contains("You said: \"first\"."), back.text)
      assert(renamed.contains("› renamed"), renamed.text)
      assert(
        deleted.contains("› New chat") && !deleted.contains("first"),
        deleted.text,
      )
    }

    test(
      "the palette: Ctrl-P lists the commands; typing filters; Enter runs one",
    ) {
      val (listed, ran) = run {
        start().map(h =>
          for
              _      <- h.key(Keyboard.Char('p'), ctrl)
              listed <- wait(h)(_.contains("Commands"))
              _      <- h.typeText("log")
              _      <- h.key(Keyboard.Enter)
              ran    <-
                wait(h)(s => s.contains("Log") && !s.contains("type a command"))
          yield (listed, ran),
        )
      }
      assert(
        Seq(
          "new chat",
          "next chat",
          "delete chat",
          "rename chat",
          "settings",
          "log",
          "stop",
          "help",
          "quit",
        )
          .forall(listed.contains),
        listed.text,
      )
      assert(ran.contains("provider: Fake"), ran.text)
    }

    test(
      "the settings never show a key: only where it comes from; Save takes the model",
    ) {
      val (shown, saved) = run {
        start(keys = Map(ProviderChoice.Anthropic -> "sk-ant-SECRET-123")).map(
          h =>
            for
                _     <- h.key(Keyboard.Char('s'), ctrl)
                shown <- wait(h)(_.contains("System prompt"))
                _     <- Kyo.foreachDiscard(1 to 20)(_ => h.key(Keyboard.Backspace))
                _     <- h.typeText("my-model")
                _     <- h.key(Keyboard.Enter)
                saved <- wait(h)(_.contains("· my-model ·"))
            yield (shown, saved),
        )
      }
      assert(
        shown.contains("Anthropic: a key from $ANTHROPIC_API_KEY"),
        shown.text,
      )
      assert(
        shown.contains("(•) Anthropic") && shown.contains(Models.anthropic),
        shown.text,
      )
      assert(
        !shown.text.contains("SECRET") && !saved.text.contains("SECRET"),
        shown.text,
      )
      assert(
        saved.contains("Anthropic · my-model ·") && !saved.contains(
          "System prompt",
        ),
        saved.text,
      )
    }

    // claude 2026-10-19: created (L4)
    test(
      "the settings switch the look: the colours change; the text does not",
    ) {
      val (before, after)   = run {
        start().map(h =>
          for
              before <- h.screen
              _      <- h.key(Keyboard.Char('s'), ctrl)
              _      <- wait(h)(_.contains("(•) terminal look"))
              _      <- h.key(Keyboard.Tab)
              _      <- h.key(Keyboard.Tab)
              _      <- h.key(Keyboard.ArrowDown)
              _      <- wait(h)(_.contains("(•) native web"))
              _      <- h.key(Keyboard.Enter)
              after  <- wait(h)(!_.contains("System prompt"))
          yield (before, after),
        )
      }
      def accent(s: Screen) =
        s.find("Chats").map((x, y) => s.styleAt(x, y).fg)
      assert(before.text == after.text, after.text)
      assert(
        accent(before).isDefined && accent(before) != accent(after),
        s"${accent(before)} ${accent(after)}",
      )
    }

    test("the log pane: Ctrl-L shows the log of the app; Ctrl-L hides it") {
      val (shown, hidden) = run {
        start().map(h =>
          for
              _      <- h.key(Keyboard.Char('l'), ctrl)
              shown  <- wait(h)(_.contains("provider: Fake, model fake-1"))
              _      <- h.key(Keyboard.Char('l'), ctrl)
              hidden <- wait(h)(!_.contains("provider: Fake, model fake-1"))
          yield (shown, hidden),
        )
      }
      assert(
        shown.contains("Log") && shown.rows.last.contains("Ctrl-P"),
        shown.text,
      )
      assert(hidden.rows.last.contains("Ctrl-P"), hidden.text)
    }

    test(
      "a resize keeps the layout: the composer, the status and the hints stay at the bottom",
    ) {
      val (wide, narrow) = run {
        start().map(h =>
          for
              _      <- send(h, "a long message " * 6)
              wide   <- wait(h)(finished)
              _      <- h.resize(80, 24)
              narrow <- wait(h)(_.rows.size == 24)
          yield (wide, narrow),
        )
      }
      Seq(wide -> 30, narrow -> 24).foreach { (s, rows) =>
        assertEquals(s.rows.size, rows, s.text)
        assert(s.rows(rows - 1).contains("Ctrl-P commands"), s.text)
        assert(s.rows(rows - 2).contains("Fake · fake-1"), s.text)
        assert(s.rows.exists(_.contains("Message · Enter sends")), s.text)
        assert(s.contains("You said"), s.text)
      }
      assert(
        narrow.contains("a long message a long message a long message a"),
        narrow.text,
      )
    }

    test(
      "the composer: Alt-Enter and Ctrl-J give new lines; Enter sends the three lines as one message",
    ) {
      val s    = run {
        start().map(h =>
          for
              _ <- h.typeText("one")
              _ <- h.key(Keyboard.Enter, Modifiers(alt = true))
              _ <- h.typeText("two")
              _ <- h.key(Keyboard.Char('j'), ctrl)
              _ <- h.typeText("three")
              _ <- h.key(Keyboard.Enter)
              s <- wait(h)(finished)
          yield s,
        )
      }
      val rows = s.rows
      val at   = rows.indexWhere(_.contains("You"))
      assert(
        at >= 0 && rows(at + 1).contains("one") && rows(at + 2)
          .contains("two") && rows(at + 3).contains("three"),
        s.text,
      )
      assert(s.contains("› one two three"), s.text)
    }

    test("F1 opens the help with the keys; Esc closes it; Ctrl-Q quits") {
      val (help, closed, ended) = run {
        start().map(h =>
          for
              _      <- h.key(Keyboard.F1)
              help   <- wait(h)(_.contains("Close"))
              _      <- h.key(Keyboard.Escape)
              closed <- wait(h)(!_.contains("Close"))
              _      <- h.key(Keyboard.Char('q'), ctrl)
              ended  <- h.awaitEnd(5.seconds)
          yield (help, closed, ended),
        )
      }
      assert(
        help.contains("Ctrl-N") && help.contains("Alt-Enter/Ctrl-J"),
        help.text,
      )
      assert(!closed.contains("Alt-Enter/Ctrl-J"), closed.text)
      assert(ended)
    }
end AssistantSuite
