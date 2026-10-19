// tui4s-core: the composite of tui4s-runner, tui4s-app and tui4s-testkit. Its
// jar holds only the object `tui4s.Core` (the Scaladoc of the whole), so that
// the sources and the javadoc jars exist for Maven Central. Its test sources
// are the integration suites (Headless, TuiApp). The root build.sbt gives the
// dependencies and the test settings.
// claude 2026-10-19: created (D25)
description := "tui4s: the terminal runner for kyo-ui, the app main class and the headless test kit, in one dependency."
