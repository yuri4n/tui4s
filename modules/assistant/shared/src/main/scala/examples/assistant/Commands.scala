package examples.assistant

import kyo.*
import kyo.UI.{Keyboard, Modifiers}
import tui4s.use.Chord

/** The commands of the app: the command palette lists them, the key hints show
  * them, and the help lists them with their keys. Pure data: the UI gives each
  * command its action ([[AssistantUi.keymap]]).
  */
// claude 2026-10-19: created (L3)
enum ChatCommand derives CanEqual:
    /** Starts a new conversation. */
    case NewChat

    /** Goes to the next conversation of the list. */
    case NextChat

    /** Goes to the conversation before in the list. */
    case PreviousChat

    /** Deletes the conversation (it asks first). */
    case DeleteChat

    /** Renames the conversation (it asks for the title). */
    case RenameChat

    /** Opens the settings. */
    case OpenSettings

    /** Shows or hides the log. */
    case ToggleLog

    /** Stops the reply that streams. */
    case Cancel

    /** Opens the help. */
    case Help

    /** Opens the command palette. */
    case Palette

    /** Ends the app. */
    case Quit

    /** The text of the command in the palette and in the hints. */
    def help: String = this match
        case NewChat      => "new chat"
        case NextChat     => "next chat"
        case PreviousChat => "previous chat"
        case DeleteChat   => "delete chat"
        case RenameChat   => "rename chat"
        case OpenSettings => "settings"
        case ToggleLog    => "log"
        case Cancel       => "stop"
        case Help         => "help"
        case Palette      => "commands"
        case Quit         => "quit"

    /** The keys of the command on `on`. The composer uses Ctrl-A, E, U, K, W
      * and J, so no command takes them.
      *
      * A browser acts on most Ctrl chords of Windows and Linux (Ctrl-S saves
      * the page, Ctrl-P prints, Ctrl-R reloads it) and on F1, and kyo-ui
      * 1.0.0-RC7 cannot stop that. So on the web each command has an Alt chord
      * first; on macOS (where the browser takes Cmd, and Option and a letter
      * types a character) it also has the chord of the terminal
      * ([[Surface.Os]]). The web has no chord that a browser keeps for itself:
      * Ctrl-PgUp and Ctrl-PgDn switch the tabs (the chats move with Alt-↑ and
      * Alt-↓), and no quit (the person closes the tab).
      */
    // claude 2026-10-19: changed: the chords of the web (L4); the chords of
    // the terminal only on macOS (L4 review)
    def chords(on: Surface): Chunk[Chord] = on match
        case Surface.Terminal       => terminal
        case Surface.Browser(_, os) => browser(os)

    /** The chords of the terminal. */
    private def terminal: Chunk[Chord] = this match
        case NewChat      => Chunk(Chord.ctrl('n'))
        case NextChat     =>
          Chunk(Chord(Keyboard.PageDown, Modifiers(ctrl = true)))
        case PreviousChat =>
          Chunk(Chord(Keyboard.PageUp, Modifiers(ctrl = true)))
        case DeleteChat   => Chunk(Chord.ctrl('d'))
        case RenameChat   => Chunk(Chord.ctrl('r'))
        case OpenSettings => Chunk(Chord.ctrl('s'))
        case ToggleLog    => Chunk(Chord.ctrl('l'))
        case Cancel       => Chunk(Chord(Keyboard.Escape))
        case Help         => Chunk(Chord(Keyboard.F1))
        case Palette      => Chunk(Chord.ctrl('p'))
        case Quit         => Chunk(Chord.ctrl('q'))

    /** The chords of the web on `os`: an Alt chord, then on macOS the chord
      * of the terminal.
      */
    // claude 2026-10-19: created (L4); changed: the chords of the terminal
    // only on macOS (L4 review)
    private def browser(os: Surface.Os): Chunk[Chord] =
        def alt(c: Char) = Chord(Keyboard.Char(c), Modifiers(alt = true))
        def mac(c: Chord): Chunk[Chord] = os match
            case Surface.Os.Mac   => Chunk(c)
            case Surface.Os.Other => Chunk.empty
        this match
            case NewChat      => Chunk(alt('n')) ++ mac(Chord.ctrl('n'))
            case NextChat     =>
              Chunk(Chord(Keyboard.ArrowDown, Modifiers(alt = true)))
            case PreviousChat =>
              Chunk(Chord(Keyboard.ArrowUp, Modifiers(alt = true)))
            case DeleteChat   => Chunk(alt('x')) ++ mac(Chord.ctrl('d'))
            case RenameChat   => Chunk(alt('r')) ++ mac(Chord.ctrl('r'))
            case OpenSettings => Chunk(alt(',')) ++ mac(Chord.ctrl('s'))
            case ToggleLog    => Chunk(alt('l')) ++ mac(Chord.ctrl('l'))
            case Cancel       => Chunk(Chord(Keyboard.Escape))
            case Help         => Chunk(alt('?')) ++ mac(Chord(Keyboard.F1))
            case Palette      => Chunk(alt('p')) ++ mac(Chord.ctrl('p'))
            case Quit         => Chunk.empty
        end match
    end browser
end ChatCommand

/** The order of the commands. */
// claude 2026-10-19: created (L3)
object ChatCommand:
    /** The commands of the palette, in its order (the palette does not list
      * itself).
      */
    val palette: Chunk[ChatCommand] =
      Chunk.from(values).filterNot(_ == Palette)

    /** The commands of the hint bar under the composer. */
    val hints: Chunk[ChatCommand] =
      Chunk(Palette, NewChat, Cancel, ToggleLog, Help, Quit)
end ChatCommand
