package tui4s.interop.zio

// The shared surface of tui4s (SPEC 2.2, 4.1) under the facade package, so
// that `import tui4s.interop.zio.*` is the only tui4s import a ZIO app needs.
// None of these names is a Kyo type. `Source` is not exported: the facade has
// its own `object Source` with ZIO constructors, and `Tui`, `TuiHandle` and
// `TuiApp` are the facade's own.

export _root_.tui4s.dsl.*
export _root_.tui4s.{
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
export _root_.tui4s.{
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
export _root_.tui4s.{CallSite, Stats, Theme, TuiError, TuiOptions, Viewport}

/** Background work that folds values into the state: the type of the sources
  * that [[Source]] builds and that `App.sources` returns.
  */
type Source[S] = _root_.tui4s.Source[S]
