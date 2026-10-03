package integration

import cats.effect.IO
import cats.effect.IOApp
import zio.ZIOAppDefault

// The user-facing entry points: a ZIO or Cats user starts a facade app on the terminal with ONE call, `Tui.run(app)`.
// The apps are the facade apps of .work/proto/kyoui-facades/{zio,cats}/apps, unchanged. Ctrl-C quits (they have no quit key).

// claude 2026-10-03: created the ZIO mains (ZIOAppDefault + tui4s.interop.zio.ui.Tui.run)
object ZioCounterMain extends ZIOAppDefault:
    def run = tui4s.interop.zio.ui.Tui.run(apps.zio.CounterApp.app)

object ZioTodoMain extends ZIOAppDefault:
    def run = tui4s.interop.zio.ui.Tui.run(apps.zio.TodoApp.app)

// claude 2026-10-03: created the Cats mains (IOApp.Simple + tui4s.interop.cats.ui.Tui.run)
object CatsCounterMain extends IOApp.Simple:
    def run: IO[Unit] = tui4s.interop.cats.ui.Tui.run(apps.cats.CounterApp.app)

object CatsTodoMain extends IOApp.Simple:
    def run: IO[Unit] = tui4s.interop.cats.ui.Tui.run(apps.cats.TodoApp[IO])
