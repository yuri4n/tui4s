package tui4s

/** One binding: its keys, its help text, its transition. */
final class Bind[S] private (val keys: Seq[Key], val help: String, val action: S => Next[S], val hidden: Boolean):
  /** More keys for the same action: `bind(Key.Up, "up")(_.move(-1)).or('k')`. Help shows "↑/k". */
  def or(more: (Key | Char)*): Bind[S] = new Bind(keys ++ more.map(Bind.toKey), help, action, hidden)

  /** Bound, but not shown by `help`. */
  def hide: Bind[S] = new Bind(keys, help, action, true)

  private[tui4s] def withKeys(ks: Seq[Key]): Bind[S] = new Bind(ks, help, action, hidden)
end Bind

object Bind:
  /** For apps built as values: `Bind[Dash]('p', "pause")(_.togglePause)`. In a TuiApp use `bind(...)`. */
  def apply[S](key: Key | Char, help: String)(action: S => Next[S]): Bind[S] =
    new Bind(Seq(toKey(key)), help, action, false)

  private[tui4s] def toKey(k: Key | Char): Key = k match
    case c: Char => Key.Char(c)
    case k: Key  => k
end Bind

/** Key bindings. The same value routes keys in `update` and renders the help bar (G3). */
final class Keymap[S] private (val binds: Seq[Bind[S]]):
  private val index: java.util.HashMap[Key, Bind[S]] =
    val m = new java.util.HashMap[Key, Bind[S]]()
    binds.foreach(b => b.keys.foreach(k => discard(m.putIfAbsent(k, b))))
    m

  /** The bound transition for `event`, or `state` itself when no binding matches. */
  def apply(state: S, event: Event): Next[S] = event match
    case k: Key =>
      val b = index.get(k)
      if b == null then state else b.action(state)
    case _ => state

  /** Extractor for `update`: `case keys(action) => action(state)`. Matches key events that have a binding. */
  def unapply(event: Event): Option[S => Next[S]] = lookup(event).map(_.action)

  def lookup(event: Event): Option[Bind[S]] = event match
    case k: Key => Option(index.get(k))
    case _      => None

  /** Both keymaps. A key bound in both: the left one wins (an intended override). */
  def ++(other: Keymap[S]): Keymap[S] =
    val rest = other.binds.flatMap { b =>
      val free = b.keys.filterNot(index.containsKey)
      if free.isEmpty then None else Some(b.withKeys(free))
    }
    new Keymap(binds ++ rest)

  /** For tests and agents: "'d' -> delete", or "'x' -> no binding (bound: + - q)". */
  def explain(event: Event): String =
    val name = event match
      case Key.Char(c) => s"'$c'"
      case k: Key      => k.show
      case other       => other.toString
    lookup(event) match
      case Some(b) => s"$name -> ${b.help}"
      case None    => s"$name -> no binding (bound: ${binds.flatMap(_.keys).map(_.show).mkString(" ")})"
  end explain
end Keymap

object Keymap:
  /** Throws IllegalArgumentException when two binds of the list share a key (dev check G16). */
  def apply[S](binds: Bind[S]*): Keymap[S] =
    val seen = scala.collection.mutable.HashSet.empty[Key]
    binds.foreach(b =>
      b.keys.foreach { k =>
        if !seen.add(k) then
          val name = k match
            case Key.Char(c) => s"'$c'"
            case other       => other.show
          throw new IllegalArgumentException(s"key $name is bound twice in one Keymap")
      }
    )
    new Keymap(binds)
  end apply
end Keymap

/** Which part of a fixed set has the focus. Immutable, kept in the app state (G4). Structural equality. */
final class Focus[A] private (val order: Vector[A], val index: Int):
  def current: A          = order(index)
  def is(a: A): Boolean   = current == a
  def next: Focus[A]      = new Focus(order, (index + 1) % order.size)
  def prev: Focus[A]      = new Focus(order, (index - 1 + order.size) % order.size)
  def to(a: A): Focus[A] =
    val i = order.indexOf(a)
    if i < 0 || i == index then this else new Focus(order, i)
  override def equals(that: Any): Boolean = that match
    case f: Focus[?] => f.index == index && f.order == order
    case _           => false
  override def hashCode: Int    = order.hashCode * 31 + index
  override def toString: String = s"Focus(${current})"
end Focus

object Focus:
  def apply[A](first: A, rest: A*): Focus[A] = new Focus((first +: rest).toVector, 0)

/** A single-line text editor value. `cursor` counts grapheme clusters (java.text.BreakIterator), not UTF-16 chars. */
final case class TextField private (text: String, cursor: Int) derives CanEqual:
  /** Printable keys insert; Backspace, Delete, Left, Right, Home, End, Ctrl('a'/'e'/'u'/'k'/'w') edit; Paste inserts.
    * Any other event returns `this` (the same object, so level 1 skips).
    */
  def edit(event: Event): TextField =
    val gs = TextField.graphemes(text)
    def at(i: Int, g: Vector[String]): TextField = new TextField(g.mkString, i)
    event match
      case Key.Char(c)                       => at(cursor + 1, gs.patch(cursor, Seq(c.toString), 0))
      case Paste(s)                          =>
        val ins = TextField.graphemes(s)
        at(cursor + ins.size, gs.patch(cursor, ins, 0))
      case Key.Backspace if cursor > 0       => at(cursor - 1, gs.patch(cursor - 1, Nil, 1))
      case Key.Delete if cursor < gs.size    => at(cursor, gs.patch(cursor, Nil, 1))
      case Key.Left if cursor > 0            => new TextField(text, cursor - 1)
      case Key.Right if cursor < gs.size     => new TextField(text, cursor + 1)
      case Key.Home | Key.Ctrl('a') if cursor > 0       => new TextField(text, 0)
      case Key.End | Key.Ctrl('e') if cursor < gs.size  => new TextField(text, gs.size)
      case Key.Ctrl('u') if cursor > 0       => at(0, gs.drop(cursor))
      case Key.Ctrl('k') if cursor < gs.size => at(cursor, gs.take(cursor))
      case Key.Ctrl('w') if cursor > 0       =>
        var i = cursor
        while i > 0 && gs(i - 1).isBlank do i -= 1
        while i > 0 && !gs(i - 1).isBlank do i -= 1
        at(i, gs.patch(i, Nil, cursor - i))
      case _ => this
    end match
  end edit

  def isBlank: Boolean      = text.isBlank
  def cleared: TextField    = TextField.empty
end TextField

object TextField:
  val empty: TextField                 = new TextField("", 0)
  def apply(text: String): TextField   = new TextField(text, graphemes(text).size) // cursor at the end

  private[tui4s] def graphemes(s: String): Vector[String] =
    if s.isEmpty then Vector.empty
    else
      val it = java.text.BreakIterator.getCharacterInstance()
      it.setText(s)
      val b     = Vector.newBuilder[String]
      var start = it.first()
      var end   = it.next()
      while end != java.text.BreakIterator.DONE do
        b += s.substring(start, end)
        start = end
        end = it.next()
      b.result()
end TextField

private[tui4s] inline def discard[A](inline v: => A): Unit =
  val _ = v
  ()
