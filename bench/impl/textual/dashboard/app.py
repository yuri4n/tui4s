# claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in Textual
import math
import asyncio
import signal
import sys
import time

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.reactive import reactive
from textual.widget import Widget
from textual.widgets import ProgressBar, Sparkline, Static

ITEMS = 1000


def value(t: int) -> int:
    """v(T) of the specification."""
    return round(50 + 40 * math.sin(t / 5))


class ItemList(Widget, can_focus=True):
    """The 1,000 items; it renders only the visible rows."""

    BINDINGS = [
        ("down", "move(1)"),
        ("up", "move(-1)"),
        ("pagedown", "page(1)"),
        ("pageup", "page(-1)"),
    ]
    selected = reactive(0)
    offset = 0

    def action_move(self, n: int) -> None:
        self.selected = max(0, min(ITEMS - 1, self.selected + n))

    def action_page(self, n: int) -> None:
        self.action_move(n * self.content_size.height)

    def render(self) -> str:
        rows = max(1, self.content_size.height)
        self.offset = max(min(self.offset, self.selected), self.selected - rows + 1)
        last = min(ITEMS, self.offset + rows)
        return "\n".join(
            f"{'> ' if i == self.selected else '  '}Item {i + 1:04}"
            for i in range(self.offset, last)
        )


class Load(Vertical, can_focus=True):
    pass


class DashboardApp(App):
    CSS = """
    #header { height: 1; }
    #title { width: 1fr; }
    #clock { width: 8; }
    ItemList, Load { width: 1fr; border: solid gray; }
    ItemList:focus, Load:focus { border: solid yellow; }
    ProgressBar Bar { width: 40; }
    Sparkline { width: 40; height: 1; }
    #status { dock: bottom; }
    """
    BINDINGS = [
        ("q", "quit"),
        Binding("ctrl+c", "quit", priority=True),
        ("ctrl+x", "fail"),
    ]
    ticks = reactive(0)

    def compose(self) -> ComposeResult:
        with Horizontal(id="header"):
            yield Static("Dashboard", id="title")
            yield Static(id="clock")
        with Horizontal():
            items = ItemList()
            items.border_title = "Items"
            yield items
            with Load() as load:
                load.border_title = "Load"
                yield Static(id="ticks")
                yield ProgressBar(total=100, show_eta=False)
                yield Sparkline([], min_color="green", max_color="green")
        yield Static(id="status")

    def on_mount(self) -> None:
        for sig in (signal.SIGTERM, signal.SIGINT):
            asyncio.get_running_loop().add_signal_handler(sig, self.exit)
        self.history: list[int] = []
        self.set_interval(0.1, self.tick)
        self.set_interval(1, self.show_clock)
        self.show_clock()
        self.query_one(ItemList).focus()

    def tick(self) -> None:
        self.ticks += 1
        self.history = (self.history + [value(self.ticks)])[-40:]
        self.query_one("#ticks", Static).update(f"Ticks: {self.ticks}")
        self.query_one(ProgressBar).update(progress=value(self.ticks))
        self.query_one(Sparkline).data = self.history

    def show_clock(self) -> None:
        self.query_one("#clock", Static).update(time.strftime("%H:%M:%S"))

    def on_descendant_focus(self) -> None:
        self.show_status()

    def watch_ticks(self) -> None:
        self.show_status()

    def show_status(self) -> None:
        if not self.is_mounted:
            return
        items = self.query_one(ItemList)
        focus = "list" if items.has_focus else "load"
        self.query_one("#status", Static).update(
            f"Selected {items.selected + 1:04}/{ITEMS} | Focus: {focus} | Tab focus, q quit"
        )

    def action_fail(self) -> None:
        raise RuntimeError("forced failure (Ctrl-X)")


if __name__ == "__main__":
    app = DashboardApp()
    app.run()
    sys.exit(app.return_code or 0)
