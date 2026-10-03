package tui4s

/** A terminal app: pure functions of one immutable state `S`. Kyo, ZIO and Cats users implement this trait. */
trait App[S]:
  /** The first state. */
  def init: S

  /** The screen for a state. Pure. Called only when the state changed or after a resize. */
  def view(state: S): View

  /** The next state after an event. Pure. Return `state` itself (the same object) when nothing changed. */
  def update(state: S, event: Event): Next[S]

  /** The standing background work for this state. Pure. Diffed by identity after each change (2.13). */
  def sources(state: S): Seq[Source[S]] = Nil

  /** Terminal and loop options. Read once, at start. */
  def options: TuiOptions = TuiOptions()

  /** A key binding with `S` fixed, so that `_ + 1` and `_.toggle` infer. */
  protected final def bind(key: Key | Char, help: String)(action: S => Next[S]): Bind[S] =
    Bind[S](key, help)(action)
end App

object App:
  /** An app as a value: for apps built from runtime data, and for tests. */
  def apply[S](
      init: S,
      view: S => View,
      update: (S, Event) => Next[S],
      sources: S => Seq[Source[S]] = (_: S) => Seq.empty[Source[S]], // (validated: `=> Nil` fails when omitted)
      options: TuiOptions = TuiOptions()
  ): App[S] =
    val i   = init
    val v   = view
    val u   = update
    val src = sources
    val o   = options
    new App[S]:
      def init: S                                         = i
      def view(state: S): View                            = v(state)
      def update(state: S, event: Event): Next[S]         = u(state, event)
      override def sources(state: S): Seq[Source[S]]      = src(state)
      override def options: TuiOptions                    = o
  end apply
end App

/** What `update` and every fold return. */
type Next[S] = S | Quit.type | Next.Then[S]

/** Stops the app. `Quit` keeps the current state; `Quit(last)` sets it. `Tui.run` returns that state. */
case object Quit:
  def apply[S](last: S): Next[S] = Next.Then(last, Nil, quit = true)

object Next:
  /** A state plus the effects of the transition. Built by `withTask` and `Quit(last)`. */
  final case class Then[S] private[tui4s] (state: S, tasks: Seq[Source[S]], quit: Boolean)
