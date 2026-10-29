// tui4s-core: one dependency for an app. The jar holds TuiApp, the composables
// (tui4s.use) and the widgets (tui4s.widgets), plus the object tui4s.Core.
// The POM depends on tui4s-runner and tui4s-testkit. The test sources are the
// integration suites. The root build.sbt gives the dependencies, the packaged
// classes and the test settings.
// claude 2026-10-19: created (D25); cursor 2026-10-29: changed: the jar holds TuiApp, use and widgets (ADR 0024)
description := "tui4s: the terminal runner, TuiApp, the composables and the widgets, in one dependency."
