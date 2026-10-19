package tui4s.use

import kyo.*
import kyo.UI.{Keyboard, KeyboardEvent, Modifiers}

/** A key and its modifier keys, on kyo-ui's `Keyboard` and `Modifiers`. For a
  * `Char` key, the case of the character carries Shift (tui4s and the browser
  * give Shift-a as `Char('A')`), so the chord of a character does not compare
  * `shift`; for another key (Tab, the arrows) `shift` counts.
  *
  * {{{
  * Chord.ctrl('z')                 // Ctrl-Z
  * Chord(Keyboard.ArrowUp)         // ↑
  * Chord(Keyboard.Tab, Modifiers(shift = true))
  * }}}
  */
// claude 2026-10-19: created (L2a)
final case class Chord(key: Keyboard, mods: Modifiers = Modifiers.none)
    derives CanEqual:

    /** True when `e` is this chord. */
    def matches(e: KeyboardEvent): Boolean =
      e.key == key && (key match
          case Keyboard.Char(_) =>
            e.modifiers.copy(shift = false) == mods.copy(shift = false)
          case _                => e.modifiers == mods)

    /** The name of the chord for a key hint: "Ctrl-Z", "↑", "PgDn", "q". */
    def label: String =
        val names = Chunk(
          (mods.ctrl, "Ctrl-"),
          (mods.alt, "Alt-"),
          (mods.meta, "Meta-"),
          (mods.shift && !Chord.isChar(key), "Shift-"),
        ).collect { case (true, n) => n }.mkString
        val plain = mods.ctrl || mods.alt || mods.meta
        names + (key match
            case Keyboard.Char(c) if plain => c.toUpper.toString
            case Keyboard.Char(c)          => c.toString
            case Keyboard.Escape           => "Esc"
            case Keyboard.Delete           => "Del"
            case Keyboard.Insert           => "Ins"
            case Keyboard.PageUp           => "PgUp"
            case Keyboard.PageDown         => "PgDn"
            case Keyboard.ArrowUp          => "↑"
            case Keyboard.ArrowDown        => "↓"
            case Keyboard.ArrowLeft        => "←"
            case Keyboard.ArrowRight       => "→"
            case Keyboard.Unknown(raw)     => raw
            case other                     => other.toString)
    end label
end Chord

/** Makes the chords of a character and of Ctrl and a letter. */
// claude 2026-10-19: created (L2a); changed: Scaladoc (L2a review)
object Chord:
    /** A character with no modifier key. */
    def char(c: Char): Chord = Chord(Keyboard.Char(c))

    /** Ctrl and a letter: the terminal and the browser give the lower case. */
    def ctrl(c: Char): Chord =
      Chord(Keyboard.Char(c.toLower), Modifiers(ctrl = true))

    private def isChar(k: Keyboard): Boolean = k match
        case Keyboard.Char(_) => true
        case _                => false
end Chord

/** The chords of one action, its help text (empty: no key hint), and the
  * action. Give the action the type `Any < Async`, and put a side effect in
  * `Sync.defer`: a plain value is not an effect, so it does not run on the key.
  */
// claude 2026-10-19: created (L2a)
final case class Binding(
    chords: Chunk[Chord],
    help: String,
    action: Any < Async,
)

/** Makes a binding from its chords. */
// claude 2026-10-19: created (L2a); changed: Scaladoc (L2a review)
object Binding:
    /** A binding of one or more chords.
      *
      * {{{
      * Binding.of("down", Chord(Keyboard.ArrowDown), Chord.char('j'))(selection.moveDown)
      * }}}
      */
    def of(help: String, chord: Chord, more: Chord*)(
        action: Any < Async,
    ): Binding =
      Binding(Chunk(chord) ++ Chunk.from(more), help, action)
end Binding

/** A key map as data: a handler for `onKeyDown` and the key hints for a help
  * line. Make it with [[Keymap.of]].
  *
  * @param bindings
  *   the bindings, in order: the first one that matches a key runs
  */
// claude 2026-10-19: created (L2a)
final class Keymap private[use] (val bindings: Chunk[Binding]):

    /** The handler for `onKeyDown`: the action of the first binding with a
      * chord that matches the key. Another key does nothing.
      */
    val handler: KeyboardEvent => Any < Async = e =>
      bindings.find(_.chords.exists(_.matches(e))) match
          case Some(b) => b.action
          case None    => Kyo.unit

    /** The key hints, in the order of the bindings: the chords ("↑/k") and the
      * help text of each binding that has one.
      */
    def help: Chunk[(String, String)] =
      bindings
        .filter(_.help.nonEmpty)
        .map(b => (b.chords.map(_.label).mkString("/"), b.help))
end Keymap

/** Makes a [[Keymap]]. A keymap is plain data with no lifetime, so it has no
  * `use` name.
  */
// claude 2026-10-19: created (L2a review: the name of a pure value)
object Keymap:
    /** A declarative key map: the first binding whose chord matches a key runs.
      * Its `handler` goes to `onKeyDown`, and its `help` lists the key hints
      * for a help line or a help box.
      *
      * {{{
      * val keys = Keymap.of(
      *   Binding.of("undo", Chord.ctrl('z'))(history.undo),
      *   Binding.of("up", Chord(Keyboard.ArrowUp), Chord.char('k'))(selection.moveUp),
      * )
      * div.onKeyDown(keys.handler)(
      *   list,
      *   span(keys.help.map((k, h) => s"$k: $h").mkString("  ")),
      * )
      * }}}
      */
    def of(bindings: Binding*): Keymap = Keymap(Chunk.from(bindings))
end Keymap
