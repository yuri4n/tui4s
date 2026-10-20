// claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in plain jatatui (Java
// 25, no Kyo)

import jatatui.core.layout.Constraint;
import jatatui.core.layout.Layout;
import jatatui.core.layout.Rect;
import jatatui.core.style.Style;
import jatatui.core.terminal.Frame;
import jatatui.crossterm.Jatatui;
import jatatui.widgets.block.Block;
import jatatui.widgets.gauge.Gauge;
import jatatui.widgets.list.HighlightSpacing;
import jatatui.widgets.list.ListItem;
import jatatui.widgets.list.ListState;
import jatatui.widgets.paragraph.Paragraph;
import jatatui.widgets.sparkline.Sparkline;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tui.crossterm.Duration;
import tui.crossterm.Event;
import tui.crossterm.KeyCode;
import tui.crossterm.KeyModifiers;

/** The dashboard: one loop that draws, waits for an event until the next tick, and ticks. */
public final class Dashboard {
  static final long TICK_NS = 100_000_000L;
  static final int ITEMS = 1000;
  static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
  static final List<ListItem> ITEM_ROWS = new ArrayList<>();

  static {
    for (int i = 1; i <= ITEMS; i++) ITEM_ROWS.add(ListItem.of(String.format("Item %04d", i)));
  }

  enum Focus {
    LIST,
    LOAD
  }

  static final class State {
    int ticks = 0;
    final ArrayDeque<Long> history = new ArrayDeque<>();
    final ListState list = ListState.empty().withSelected(Optional.of(0));
    Focus focus = Focus.LIST;
    int page = 1; // the visible item rows of the last frame

    int selected() {
      return list.selected().orElse(0);
    }

    void select(int i) {
      list.select(Optional.of(Math.max(0, Math.min(ITEMS - 1, i))));
    }

    void tick() {
      ticks++;
      history.addLast((long) value(ticks));
      if (history.size() > 40) history.removeFirst();
    }
  }

  static int value(int t) {
    return (int) Math.round(50 + 40 * Math.sin(t / 5.0));
  }

  private static volatile boolean running = true;
  private static final CountDownLatch restored = new CountDownLatch(1);

  public static void main(String[] args) throws Exception {
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
            var state = new State();
            long nextTick = System.nanoTime() + TICK_NS;
            while (running) {
              terminal.draw(frame -> render(frame, state));
              long wait = Math.max(0, nextTick - System.nanoTime());
              if (jni.poll(new Duration(0, (int) wait))
                  && jni.read() instanceof Event.Key(var key)) {
                handle(state, key.code(), (key.modifiers().bits() & KeyModifiers.CONTROL) != 0);
              }
              if (System.nanoTime() >= nextTick) {
                state.tick();
                nextTick += TICK_NS;
              }
            }
          });
    } finally {
      restored.countDown();
    }
  }

  static void handle(State s, KeyCode code, boolean ctrl) {
    switch (code) {
      case KeyCode.Char(var c) when ctrl && c == 'c' -> running = false;
      case KeyCode.Char(var c) when ctrl && c == 'x' ->
          throw new IllegalStateException("forced failure (Ctrl-X)");
      case KeyCode.Char(var c) when c == 'q' -> running = false;
      case KeyCode.Tab t -> s.focus = s.focus == Focus.LIST ? Focus.LOAD : Focus.LIST;
      case KeyCode.Down d when s.focus == Focus.LIST -> s.select(s.selected() + 1);
      case KeyCode.Up u when s.focus == Focus.LIST -> s.select(s.selected() - 1);
      case KeyCode.PageDown p when s.focus == Focus.LIST -> s.select(s.selected() + s.page);
      case KeyCode.PageUp p when s.focus == Focus.LIST -> s.select(s.selected() - s.page);
      default -> {}
    }
  }

  static Block panel(String title, boolean focused) {
    return Block.bordered()
        .withTitle(title)
        .withBorderStyle(focused ? Style.empty().yellow() : Style.empty().gray());
  }

  static void render(Frame frame, State s) {
    Rect[] rows =
        Layout.vertical(new Constraint.Length(1), new Constraint.Fill(1), new Constraint.Length(1))
            .split(frame.area());
    Rect[] header =
        Layout.horizontal(new Constraint.Fill(1), new Constraint.Length(8)).split(rows[0]);
    frame.renderWidget(Paragraph.of("Dashboard"), header[0]);
    frame.renderWidget(Paragraph.of(LocalTime.now().format(CLOCK)), header[1]);

    Rect[] body =
        Layout.horizontal(new Constraint.Percentage(50), new Constraint.Percentage(50))
            .split(rows[1]);
    s.page = Math.max(1, body[0].height() - 2);
    var list =
        jatatui.widgets.list.List.of(ITEM_ROWS)
            .withBlock(panel("Items", s.focus == Focus.LIST))
            .withHighlightSymbol("> ")
            .withHighlightSpacing(HighlightSpacing.Always);
    frame.renderStatefulWidget(list, body[0], s.list);

    Block load = panel("Load", s.focus == Focus.LOAD);
    frame.renderWidget(load, body[1]);
    Rect[] lines =
        Layout.vertical(
                new Constraint.Length(1),
                new Constraint.Length(1),
                new Constraint.Length(1),
                new Constraint.Fill(1))
            .split(load.inner(body[1]));
    frame.renderWidget(Paragraph.of("Ticks: " + s.ticks), lines[0]);
    frame.renderWidget(Gauge.empty().withPercent(value(s.ticks)), lines[1]);
    long[] data = s.history.stream().mapToLong(Long::longValue).toArray();
    frame.renderWidget(Sparkline.empty().withDataLongs(data).withMax(100), lines[2]);

    String focus = s.focus == Focus.LIST ? "list" : "load";
    frame.renderWidget(
        Paragraph.of(
            String.format(
                "Selected %04d/%d | Focus: %s | Tab focus, q quit",
                s.selected() + 1, ITEMS, focus)),
        rows[2]);
  }
}
