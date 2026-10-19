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

    /** The keys of the command. The composer uses Ctrl-A, E, U, K, W and J, so
      * no command takes them.
      */
    def chords: Chunk[Chord] = this match
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
