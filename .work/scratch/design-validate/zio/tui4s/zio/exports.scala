package tui4s.zio

// The shared surface (SPEC 2.2) under the facade package. (validated: Scala 3.9 accepts an export from a package,
// so the short form works; `Source` is not exported: the facade has its own `object Source`.)
export tui4s.dsl.*
export tui4s.{App, Next, Quit, Event, Key, Mods, Mouse, MouseKind, Button, Resize, Paste, FocusGained, FocusLost}
export tui4s.{Keymap, Bind, Focus, TextField, View, Line, Style, Color, Flex, Align, Border, BorderKind, Constraint}
export tui4s.{Theme, TuiOptions, Viewport, TuiError, Stats, CallSite}

type Source[S] = tui4s.Source[S] // the companion is the facade's own `object Source`
