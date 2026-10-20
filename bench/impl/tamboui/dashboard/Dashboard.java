// claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in TamboUI (Java 25)

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.list.ScrollMode;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.sparkline.Sparkline;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The dashboard: TuiRunner sends a tick event every 100 ms; the handler updates the state. */
public final class Dashboard {
  static final int ITEMS = 1000;
  static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
  static final List<ListItem> ITEM_ROWS = new ArrayList<>();

  static {
    for (int i = 1; i <= ITEMS; i++) ITEM_ROWS.add(ListItem.from(String.format("Item %04d", i)));
  }

  static int ticks = 0;
  static final ArrayDeque<Long> history = new ArrayDeque<>();
  static final ListState list = new ListState();
  static boolean onLoad = false;
  static int page = 1; // the visible item rows of the last frame

  static int value(int t) {
    return (int) Math.round(50 + 40 * Math.sin(t / 5.0));
  }

  static void select(int i) {
    list.select(Math.max(0, Math.min(ITEMS - 1, i)));
  }

  public static void main(String[] args) throws Exception {
    list.select(0);
    var config = TuiConfig.builder().tickRate(Duration.ofMillis(100)).build();
    try (var tui = TuiRunner.create(config)) {
      tui.run(
          (event, runner) -> {
            if (event instanceof TickEvent) {
              ticks++;
              history.addLast((long) value(ticks));
              if (history.size() > 40) history.removeFirst();
              return true;
            }
            if (!(event instanceof KeyEvent key)) return false;
            int selected = list.selected();
            if (key.isChar('q') || key.isCtrlC()) runner.quit();
            else if (key.hasCtrl() && key.isCharIgnoreCase('x'))
              throw new IllegalStateException("forced failure (Ctrl-X)");
            else if (key.isChar('\t') || key.code() == dev.tamboui.tui.event.KeyCode.TAB)
              onLoad = !onLoad;
            else if (onLoad) return false;
            else if (key.isDown()) select(selected + 1);
            else if (key.isUp()) select(selected - 1);
            else if (key.isPageDown()) select(selected + page);
            else if (key.isPageUp()) select(selected - page);
            else return false;
            return true;
          },
          Dashboard::render);
    }
  }

  static Block panel(String title, boolean focused) {
    return Block.builder()
        .title(title)
        .borders(dev.tamboui.widgets.block.Borders.ALL)
        .borderColor(focused ? Color.YELLOW : Color.GRAY)
        .build();
  }

  static void render(Frame frame) {
    List<Rect> rows =
        Layout.vertical()
            .constraints(Constraint.length(1), Constraint.fill(), Constraint.length(1))
            .split(frame.area());
    List<Rect> header =
        Layout.horizontal().constraints(Constraint.fill(), Constraint.length(8)).split(rows.get(0));
    frame.renderWidget(Paragraph.from("Dashboard"), header.get(0));
    frame.renderWidget(Paragraph.from(LocalTime.now().format(CLOCK)), header.get(1));

    List<Rect> body =
        Layout.horizontal()
            .constraints(Constraint.percentage(50), Constraint.percentage(50))
            .split(rows.get(1));
    page = Math.max(1, body.get(0).height() - 2);
    var items =
        ListWidget.builder()
            .items(ITEM_ROWS.toArray(ListItem[]::new))
            .block(panel("Items", !onLoad))
            .highlightSymbol("> ")
            .scrollMode(ScrollMode.AUTO_SCROLL)
            .build();
    frame.renderStatefulWidget(items, body.get(0), list);

    Block load = panel("Load", onLoad);
    frame.renderWidget(load, body.get(1));
    List<Rect> lines =
        Layout.vertical()
            .constraints(
                Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.fill())
            .split(load.inner(body.get(1)));
    frame.renderWidget(Paragraph.from("Ticks: " + ticks), lines.get(0));
    frame.renderWidget(Gauge.percent(value(ticks)), lines.get(1));
    frame.renderWidget(
        Sparkline.builder().data(new ArrayList<>(history)).max(100).build(), lines.get(2));

    String focus = onLoad ? "load" : "list";
    frame.renderWidget(
        Paragraph.from(
            String.format(
                "Selected %04d/%d | Focus: %s | Tab focus, q quit",
                list.selected() + 1, ITEMS, focus)),
        rows.get(2));
  }
}
