package tui4s

import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import tui.{Borders, Span, Spans}
import tui4s.FramesPerUnit.fps
import tui4s.widgets

object Demo extends IOApp with widgets:

    def run(args: List[String]): IO[ExitCode] =
      withTerminal[IO](
        app = Container(
          // child = Text("Hello, World!"),
          child = Tabs(
            tabs = Array(
              Tabs.Tab(), // title = Spans.from(Span.nostyle("Tab 1"))),
              Tabs.Tab(), // title = Spans.from(Span.nostyle("Tab 2"))),
              Tabs.Tab(), // title = Spans.from(Span.nostyle("Tab 3"))),
            ),
          ),
          title = Some(Spans.from(Span.nostyle("Welcome"))),
          borders = Borders.ALL,
        ),
        frameRate = 60.fps,
      ).useForever
        .as(ExitCode.Success)
        .handleErrorWith { (error: Throwable) =>
          Console[IO]
            .errorln(s"Error: ${error.getMessage}")
            .as(ExitCode.Error)
        }
end Demo
