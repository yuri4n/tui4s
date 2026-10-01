package tui4s

/** A terminal app: pure functions of one immutable state `S`.
  *
  * Kyo, ZIO and Cats Effect users implement this trait. No method has an effect
  * type. The runtime calls every method on one fiber, so the methods must be
  * pure and fast.
  *
  * @tparam S
  *   the state. Use an immutable value (a case class, a number, a string).
  */
trait App[S]:
    /** The first state. */
    def init: S

    /** The screen for a state. Pure. Called only when the state changed or
      * after a resize.
      */
    def view(state: S): View

    /** The next state after an event. Pure. Return `state` itself (the same
      * object) when nothing changed: then the runtime does no more work.
      */
    def update(state: S, event: Event): Next[S]

    /** The standing background work for this state. Pure. The runtime calls it
      * after each change and compares the result by [[Source.label]]: new
      * sources start, missing sources stop. The default is no work.
      */
    def sources(state: S): Seq[Source[S]] = Nil

    /** Terminal and loop options. Read once, at start. */
    def options: TuiOptions = TuiOptions()

    /** A key binding with `S` fixed, so that `_ + 1` and `_.toggle` infer.
      *
      * @param key
      *   a [[Key]], or a `Char` for `Key.Char(c)`
      * @param help
      *   the text that `help(keymap)` shows for this binding
      * @param action
      *   the transition
      */
    protected final def bind(key: Key | Char, help: String)(
        action: S => Next[S],
    ): Bind[S] =
      Bind[S](key, help)(action)
end App

/** Builds apps as values. */
object App:
    /** An app as a value: for apps built from runtime data, and for tests.
      *
      * @param init
      *   the first state
      * @param view
      *   the screen for a state
      * @param update
      *   the next state after an event
      * @param sources
      *   the standing background work for a state (default: none)
      * @param options
      *   terminal and loop options
      */
    def apply[S](
        init: S,
        view: S => View,
        update: (S, Event) => Next[S],
        // Typed default: `(_: S) => Nil` would infer Seq[Source[Nothing]].
        sources: S => Seq[Source[S]] = (_: S) => Seq.empty[Source[S]],
        options: TuiOptions = TuiOptions(),
    ): App[S] =
        val first    = init
        val draw     = view
        val step     = update
        val work     = sources
        val options0 = options
        new App[S]:
            def init: S                                    = first
            def view(state: S): View                       = draw(state)
            def update(state: S, event: Event): Next[S]    = step(state, event)
            override def sources(state: S): Seq[Source[S]] = work(state)
            override def options: TuiOptions               = options0
    end apply
end App

/** What `update`, a fold and a key binding return.
  *
  *   - a plain state `s`: the next state (`n + 1`, `todo.add`);
  *   - [[Quit]]: stop, keep the current state;
  *   - `Quit(last)`: stop with the state `last`;
  *   - `state.withTask(task)`: the next state, plus one-shot work that starts
  *     after this transition.
  *
  * Write the result type of `update` (`: Next[Int]`): an inferred union widens.
  * A state type must not itself be `Quit.type` or a [[Next.Then]].
  */
type Next[S] = S | Quit.type | Next.Then[S]

/** Stops the app. `Quit` keeps the current state; `Quit(last)` sets it. The run
  * returns that state.
  */
case object Quit:
    /** Stops the app with the state `last`. */
    def apply[S](last: S): Next[S] = Next.Then(last, Nil, quit = true)

/** Helpers for [[Next]]. */
object Next:
    /** A state plus the effects of the transition. Built by `withTask` and
      * `Quit(last)`.
      *
      * @param state
      *   the next state
      * @param tasks
      *   one-shot tasks that start after the transition, in order
      * @param quit
      *   true when the app stops with `state`
      */
    final case class Then[S] private[tui4s] (
        state: S,
        tasks: Seq[Source[S]],
        quit: Boolean,
    )
end Next
