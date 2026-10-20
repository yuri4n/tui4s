// claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in TamboUI (Java 25)

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.List;

/** The counter: TuiRunner owns the terminal, the event loop and the restore. */
public final class Counter {
  private static int count = 0;

  public static void main(String[] args) throws Exception {
    try (var tui = TuiRunner.create(TuiConfig.builder().noTick().build())) {
      tui.run(
          (event, runner) -> {
            if (!(event instanceof KeyEvent key)) return false;
            if (key.isChar('q') || key.isCtrlC()) {
              runner.quit();
              return false;
            }
            if (key.hasCtrl() && key.isCharIgnoreCase('x')) {
              throw new IllegalStateException("forced failure (Ctrl-X)");
            }
            if (key.isChar('+') || key.isUp()) count++;
            else if (key.isChar('-') || key.isDown()) count--;
            else return false;
            return true;
          },
          Counter::render);
    }
  }

  static void render(Frame frame) {
    List<Rect> rows =
        Layout.vertical()
            .constraints(
                Constraint.length(1),
                Constraint.length(1),
                Constraint.length(1),
                Constraint.fill(),
                Constraint.length(1))
            .split(frame.area());
    frame.renderWidget(Paragraph.from("Counter"), rows.get(0));
    frame.renderWidget(Paragraph.from("Count: " + count), rows.get(2));
    frame.renderWidget(Paragraph.from("+/- or Up/Down change the count, q quits"), rows.get(4));
  }
}
