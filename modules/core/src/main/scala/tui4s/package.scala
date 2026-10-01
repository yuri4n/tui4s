/** Declarative terminal user interfaces for Scala 3, rendered with jatatui.
  *
  * `import tui4s.*` is the only import a Kyo app needs. ZIO and Cats Effect
  * apps import their facade package instead (`tui4s.interop.zio.*`,
  * `tui4s.interop.cats.*`), which re-exports the same model.
  *
  * ==The model==
  *
  * An app is a set of pure functions of one immutable state `S` (see [[App]]):
  *
  *   - `init: S` is the first state.
  *   - `update(state, event): Next[S]` gives the next state for a terminal
  *     [[Event]] (a [[Key]], a [[Mouse]] event, a [[Resize]], a [[Paste]]).
  *   - `view(state): View` describes the screen for a state.
  *   - `sources(state): Seq[Source[S]]` declares the background work that this
  *     state needs. The runtime compares the list after each change: new
  *     sources start, missing sources stop.
  *
  * No function has an effect type, so the same app text serves Kyo, ZIO and
  * Cats Effect users, and tests call the functions directly.
  *
  * `update` returns a [[Next]]: the new state (`state + 1`), the same object
  * when nothing changed (`state`), [[Quit]] or `Quit(last)` to stop, or
  * `state.withTask(task)` to start one-shot work after the transition.
  *
  * {{{
  * import tui4s.*
  *
  * object CounterApp extends App[Int]:
  *   val keys: Keymap[Int] = Keymap(
  *     bind('+', "increment")(_ + 1),
  *     bind('-', "decrement")(_ - 1),
  *     bind('q', "quit")(_ => Quit)
  *   )
  *
  *   def init: Int = 0
  *   def update(n: Int, event: Event): Next[Int] = keys(n, event)
  *   def view(n: Int): View =
  *     column(
  *       text("Count: ", n.toString.bold.yellow).centered.size(1),
  *       help(keys).centered.size(1)
  *     ).flex(Flex.Center).bordered(" Counter ")
  * }}}
  *
  * ==Keys, focus and text==
  *
  * A [[Keymap]] routes keys in `update` (`keys(state, event)`, or
  * `case keys(action) => action(state)`) and renders the help bar
  * (`help(keys)`), so the help text cannot drift from the bindings. A [[Focus]]
  * value in the state says which part of the screen has the focus; choose a
  * keymap per focus. A [[TextField]] is an immutable single-line editor value:
  * `field.edit(event)`.
  *
  * ==The view==
  *
  * A [[View]] is an immutable tree of plain data, built with the functions of
  * [[dsl]] (`text`, `column`, `row`, `list`, `table`, `gauge`, `input`, ...)
  * and changed with modifiers (`.size(3)`, `.bordered(" Title ")`,
  * `.padded(1)`, `.id("files")`, `.tag("ok")`). Two views compare with `==`.
  * Styled text uses [[Line]], [[Line.Span]], [[Style]] and [[Color]]:
  * `"error".bold.red`, `line("✔ ".green, title)`.
  *
  * ==When the screen is drawn==
  *
  *   1. An event whose result is the same state object draws nothing and does
  *      not call `view`.
  *   1. A new state whose view is equal to the last drawn view draws nothing.
  *   1. A resize always draws the next frame.
  *
  * ==Testing==
  *
  * `tui4s.testkit.TuiTest(app, width, height)` runs the same fold, frame rules
  * and painter as the runtime, synchronously, on a jatatui `TestBackend`:
  * `TuiTest(CounterApp).press('+').screen.contains("Count: 1")`.
  */
package object tui4s:
    export dsl.*
