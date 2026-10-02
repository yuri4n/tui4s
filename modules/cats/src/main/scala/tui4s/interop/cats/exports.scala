package tui4s.interop

/** tui4s for Cats Effect: `import tui4s.interop.cats.*` is the only tui4s
  * import a Cats Effect app needs. Do not also `import tui4s.*`: its `Source`,
  * `Tui`, `TuiApp` and `TuiHandle` are the Kyo ones, and the names clash.
  *
  * This package re-exports the model of tui4s unchanged (`App`, `Next`, `Quit`,
  * the events, `Keymap`, `View` and every view constructor, the styles,
  * `TuiOptions`, `TuiError`, `Stats`), so the app logic is the same text as in
  * a Kyo or a ZIO app. It adds three things:
  *
  *   - [[Source]] constructors that take an `F[A]` with `Async[F]` or an
  *     `fs2.Stream[F, A]`,
  *   - the runner [[Tui]] (`Tui.run[F](app): F[S]`, `Tui.resource`), and
  *   - the main class [[TuiApp]], an `IOApp.Simple`.
  *
  * {{{
  * import tui4s.interop.cats.*
  *
  * object CounterApp extends TuiApp[Int]:
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
  * Tests use the same synchronous `tui4s.testkit.TuiTest` as Kyo apps (`import
  * tui4s.testkit.*`), and [[testkit.LiveTuiTest]] for the real loop with
  * running sources.
  *
  * The app runs on the Kyo scheduler; its `F` effects (sources, tasks) run on
  * the runtime of the caller of `Tui.run`. One `F` per app: every source of an
  * app must use the `F` of its runner.
  */
package object cats:
    export tui4s.dsl.*
    export tui4s.{
      App,
      Button,
      Event,
      FocusGained,
      FocusLost,
      Key,
      Mods,
      Mouse,
      MouseKind,
      Next,
      Paste,
      Quit,
      Resize,
    }
    export tui4s.{
      Align,
      Bind,
      Border,
      BorderKind,
      Color,
      Constraint,
      Flex,
      Focus,
      Keymap,
      Line,
      Style,
      TextField,
      View,
    }
    export tui4s.{CallSite, Stats, Theme, TuiError, TuiOptions, Viewport}

    /** Background work that folds values into the state: the type of core
      * tui4s. Build sources with [[Source]] (or `source` in a [[TuiApp]]).
      */
    type Source[S] = tui4s.Source[S]
end cats
