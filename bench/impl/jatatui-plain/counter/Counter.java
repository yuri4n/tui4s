// claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in plain jatatui (Java 25,
// no Kyo)

import jatatui.core.layout.Constraint;
import jatatui.core.layout.Layout;
import jatatui.core.layout.Rect;
import jatatui.core.terminal.Frame;
import jatatui.crossterm.Jatatui;
import jatatui.widgets.paragraph.Paragraph;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tui.crossterm.Duration;
import tui.crossterm.Event;
import tui.crossterm.KeyCode;
import tui.crossterm.KeyModifiers;

/** The counter: one loop that draws, then waits at most 100 ms for an event. */
public final class Counter {
  private static volatile boolean running = true;
  private static final CountDownLatch restored = new CountDownLatch(1);

  public static void main(String[] args) throws Exception {
    // SIGTERM and SIGINT: stop the loop and wait until it restored the terminal.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  running = false;
                  try {
                    restored.await(2, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
    try {
      Jatatui.runIo(
          terminal -> {
            var jni = terminal.backend().writer();
            int count = 0;
            while (running) {
              int shown = count;
              terminal.draw(frame -> render(frame, shown));
              if (!jni.poll(new Duration(0, 100_000_000))) continue;
              if (jni.read() instanceof Event.Key(var key)) {
                boolean ctrl = (key.modifiers().bits() & KeyModifiers.CONTROL) != 0;
                switch (key.code()) {
                  case KeyCode.Char(var c) when ctrl && c == 'c' -> running = false;
                  case KeyCode.Char(var c) when ctrl && c == 'x' ->
                      throw new IllegalStateException("forced failure (Ctrl-X)");
                  case KeyCode.Char(var c) when c == 'q' -> running = false;
                  case KeyCode.Char(var c) when c == '+' -> count++;
                  case KeyCode.Char(var c) when c == '-' -> count--;
                  case KeyCode.Up u -> count++;
                  case KeyCode.Down d -> count--;
                  default -> {}
                }
              }
            }
          });
    } finally {
      restored.countDown();
    }
  }

  static void render(Frame frame, int count) {
    Rect[] rows =
        Layout.vertical(
                new Constraint.Length(1),
                new Constraint.Length(1),
                new Constraint.Length(1),
                new Constraint.Fill(1),
                new Constraint.Length(1))
            .split(frame.area());
    frame.renderWidget(Paragraph.of("Counter"), rows[0]);
    frame.renderWidget(Paragraph.of("Count: " + count), rows[2]);
    frame.renderWidget(Paragraph.of("+/- or Up/Down change the count, q quits"), rows[4]);
  }
}
