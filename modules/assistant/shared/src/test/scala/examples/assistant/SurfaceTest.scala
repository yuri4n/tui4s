package examples.assistant

import kyo.*
import kyo.UI.{Keyboard, Modifiers}
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.use.Chord

/** The rules of the surfaces: the units (the terminal keeps its cells), the
  * size of a browser window in cells, the chords of the web, and the composer
  * of the web.
  */
// claude 2026-10-19: created (L4)
class SurfaceTest extends Test[Any]:

    private val vault: KeyVault = new KeyVault:
        def keep(p: ProviderChoice, key: ApiKey)(using Frame): Unit < Sync = ()
        def forget(p: ProviderChoice)(using Frame): Unit < Sync            = ()

    // claude 2026-10-19: changed: the system of the browser (L4 review)
    private val web = Surface.Browser(vault, Surface.Os.Other)
    private val mac = Surface.Browser(vault, Surface.Os.Mac)

    "the units: n cells in a terminal; n lines and n columns of the font in a browser" in {
      assert(
        Surface.Terminal.rows(7) == 7.em && Surface.Terminal.cols(
          13,
        ) == 13.em &&
          web.rows(4) == 5.em && web.cols(10) == 6.em &&
          Surface.Terminal.lines == Style.empty &&
          Surface.Terminal.panel == Style.empty,
      )
    }

    "a window of 1200 x 800 px with a font of 16 px is 125 x 40 cells" in {
      assert(
        Surface.cells(1200, 800, 16) == ScreenSize(125, 40) &&
          Surface.cells(0, 0, 16) == ScreenSize(1, 1),
      )
    }

    // claude 2026-10-19: changed: no Ctrl chord and no F1 off macOS; the
    // chords of the terminal on macOS; Ctrl-J only on macOS (L4 review)
    "the chords of the web: off macOS no Ctrl chord and no F1; on macOS also the chords of the terminal but not its tab keys; no quit; an Alt chord for each letter command" in {
      val tabs = Chunk(
        Chord(Keyboard.PageUp, Modifiers(ctrl = true)),
        Chord(Keyboard.PageDown, Modifiers(ctrl = true)),
        Chord.ctrl('q'),
      )
      val all  = Chunk.from(ChatCommand.values)
      assert(
        all.forall(c =>
          c.chords(web).forall(k => !k.mods.ctrl && k.key != Keyboard.F1),
        ) &&
          all.forall(c => !c.chords(mac).exists(tabs.contains)) &&
          ChatCommand.Quit.chords(web).isEmpty &&
          ChatCommand.Quit.chords(mac).isEmpty &&
          ChatCommand.Palette.chords(mac) == Chunk(
            Chord(Keyboard.Char('p'), Modifiers(alt = true)),
            Chord.ctrl('p'),
          ) &&
          ChatCommand.Help.chords(mac).contains(Chord(Keyboard.F1)) &&
          !web.composerHelp.exists(_._1.contains("Ctrl-J")) &&
          mac.composerHelp.exists(_._1.contains("Ctrl-J")) &&
          all
            .filterNot(c => c == ChatCommand.Quit || c == ChatCommand.Cancel)
            .forall(c =>
              c.chords(web).exists(_.mods.alt) &&
                c.chords(mac).exists(_.mods.alt),
            ) &&
          ChatCommand.Palette.chords(Surface.Terminal) == Chunk(
            Chord.ctrl('p'),
          ) &&
          ChatCommand.NextChat.chords(Surface.Terminal) ==
          Chunk(Chord(Keyboard.PageDown, Modifiers(ctrl = true))),
      )
    }

    // claude 2026-10-19: created (L4 review)
    "the system of a browser: macOS and iOS are Mac; Windows, Linux and an unknown platform are Other" in {
      assert(
        Surface.Os.of("MacIntel") == Surface.Os.Mac &&
          Surface.Os.of("iPhone") == Surface.Os.Mac &&
          Surface.Os.of("Win32") == Surface.Os.Other &&
          Surface.Os.of("Linux x86_64") == Surface.Os.Other &&
          Surface.Os.of("") == Surface.Os.Other,
      )
    }

    "the composer of the web: the break of Enter after a send gives the text that the send left" in {
      val sent = Surface.Echo.Sent("hi", "")
      assert(
        Surface.echo(sent, "hi\n") == "" &&
          Surface.echo(sent, "hi there") == "hi there" &&
          Surface.echo(Surface.Echo.Sent("a\nb", "a\nb"), "a\nb\n") == "a\nb" &&
          Surface.echo(Surface.Echo.Off, "x\n") == "x\n",
      )
    }
end SurfaceTest

/** The law of the composer of the web: an input with no new line break stays. */
// claude 2026-10-19: created (L4)
class SurfacePropertyTest extends PropertyTest[Any]:

    private val text: Gen[String] =
      Gen.listOfN(8, Gen.oneOf('a', 'b', ' ', '\n')).map(_.mkString)

    "an input with no more line breaks than the sent text stays as it is" - {
      forAll(text, text, text) { (before, after, t) =>
        val kept = t.count(_ == '\n') <= before.count(_ == '\n')
        assert(!kept || Surface.echo(Surface.Echo.Sent(before, after), t) == t)
      }
    }
end SurfacePropertyTest
