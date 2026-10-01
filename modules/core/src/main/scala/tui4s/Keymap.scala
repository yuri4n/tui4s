package tui4s

import _root_.tui4s.internal.discard

/** One key binding: its keys, its help text and its transition.
  *
  * In an [[App]], build it with `bind(key, help)(action)`; for an app built as
  * a value, with `Bind[S](key, help)(action)`.
  *
  * @param keys
  *   the keys that trigger the binding, in help order
  * @param help
  *   the text that `help(keymap)` shows
  * @param action
  *   the transition
  * @param hidden
  *   true when `help(keymap)` does not show the binding
  */
final class Bind[S] private (
    val keys: Seq[Key],
    val help: String,
    val action: S => Next[S],
    val hidden: Boolean,
):
    /** More keys for the same action: `bind(Key.Up, "up")(_.move(-1)).or('k')`.
      * The help bar shows "↑/k".
      */
    def or(more: (Key | Char)*): Bind[S] =
      new Bind(keys ++ more.map(Bind.toKey), help, action, hidden)

    /** The same binding, not shown by `help(keymap)`. */
    def hide: Bind[S] = new Bind(keys, help, action, true)

    private[tui4s] def withKeys(ks: Seq[Key]): Bind[S] =
      new Bind(ks, help, action, hidden)

    override def toString: String =
      s"Bind(${keys.map(_.show).mkString("/")} -> $help)"
end Bind

/** Builds key bindings. */
object Bind:
    /** A binding for apps built as values:
      * `Bind[Dash]('p', "pause")(_.togglePause)`. In an [[App]], use
      * `bind(...)`, which fixes `S`.
      */
    def apply[S](key: Key | Char, help: String)(
        action: S => Next[S],
    ): Bind[S] =
      new Bind(Seq(toKey(key)), help, action, false)

    private[tui4s] def toKey(k: Key | Char): Key = k match
        case c: Char => Key.Char(c)
        case k: Key  => k
end Bind

/** Key bindings. The same value routes keys in `update` and renders the help
  * bar, so the help cannot drift from the bindings.
  *
  * Build keymaps once (as `val`s). To change the bindings with the focus,
  * choose between keymaps: `if inInput then inputKeys else listKeys`.
  *
  * @param binds
  *   the bindings, in help order
  */
final class Keymap[S] private (val binds: Seq[Bind[S]]):
    // One hash probe per lookup.
    private val index: java.util.HashMap[Key, Bind[S]] =
        val m = new java.util.HashMap[Key, Bind[S]]()
        binds.foreach(b => b.keys.foreach(k => discard(m.putIfAbsent(k, b))))
        m

    /** The bound transition for `event`, or `state` itself (the same object)
      * when no binding matches.
      */
    def apply(state: S, event: Event): Next[S] = event match
        case k: Key =>
          val b = index.get(k)
          if b == null then state else b.action(state)
        case _      => state

    /** Extractor for `update`: `case keys(action) => action(state)`. Matches
      * the key events that have a binding.
      */
    def unapply(event: Event): Option[S => Next[S]] =
      lookup(event).map(_.action)

    /** The binding for `event`, if `event` is a bound key. */
    def lookup(event: Event): Option[Bind[S]] = event match
        case k: Key => Option(index.get(k))
        case _      => None

    /** Both keymaps. A key bound in both: the left one wins (an intended
      * override). A right binding whose keys are all bound on the left is
      * dropped.
      */
    def ++(other: Keymap[S]): Keymap[S] =
        val rest = other.binds.flatMap { b =>
          val free = b.keys.filterNot(index.containsKey)
          if free.isEmpty then None
          else if free.size == b.keys.size then Some(b)
          else Some(b.withKeys(free))
        }
        new Keymap(binds ++ rest)
    end ++

    /** For tests and agents: "'d' -> delete", or "'x' -> no binding (bound: + -
      * q)".
      */
    def explain(event: Event): String =
        val name = event match
            case k: Key => k.quoted
            case other  => other.toString
        lookup(event) match
            case Some(b) => s"$name -> ${b.help}"
            case None    =>
              val bound = binds.flatMap(_.keys).map(_.show).mkString(" ")
              s"$name -> no binding (bound: $bound)"
    end explain

    /** The visible bindings as (keys, help) pairs: ("↑/k", "up"). */
    private[tui4s] def helpEntries: Seq[(String, String)] =
      binds.collect {
        case b if !b.hidden => (b.keys.map(_.show).mkString("/"), b.help)
      }

    override def toString: String = binds.mkString("Keymap(", ", ", ")")
end Keymap

/** Builds keymaps. */
object Keymap:
    /** A keymap of `binds`, in help order.
      *
      * @throws IllegalArgumentException
      *   when two bindings of the list share a key (a duplicate is a bug), or
      *   when a key is one that a terminal never sends: a control character as
      *   `Key.Char` ('\t' arrives as `Key.Tab`), or `Key.Ctrl` with an
      *   upper-case letter (Ctrl-S arrives as `Key.Ctrl('s')`)
      */
    def apply[S](binds: Bind[S]*): Keymap[S] =
        val seen = scala.collection.mutable.HashSet.empty[Key]
        binds.foreach(b =>
          b.keys.foreach { k =>
            checkSent(k)
            if !seen.add(k) then
                throw new IllegalArgumentException(
                  s"key ${k.quoted} is bound twice in one Keymap",
                )
          },
        )
        new Keymap(binds)
    end apply

    /** Rejects a key that never reaches `update`, so that a binding cannot pass
      * in the test kit and never fire in a terminal.
      */
    private def checkSent(k: Key): Unit =
        val sent = Key.sent(k)
        if sent != k then
            throw new IllegalArgumentException(
              s"a terminal never sends ${name(k)}: bind ${sent.show}",
            )
        k match
            case Key.Char(c) if Character.isISOControl(c) =>
              throw new IllegalArgumentException(
                s"a terminal never sends the control character ${name(k)}: " +
                  "bind a named key or Key.Ctrl",
              )
            case _                                        => ()
    end checkSent

    private def name(k: Key): String = k match
        case Key.Char(c) if Character.isISOControl(c) =>
          "Key.Char(" + "U+%04X".format(c.toInt) + ")"
        case Key.Ctrl(c)                              => s"Key.Ctrl('$c')"
        case other                                    => other.quoted
end Keymap
