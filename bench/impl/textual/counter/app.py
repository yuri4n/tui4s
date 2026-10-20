# claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in Textual
import asyncio
import signal
import sys

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.reactive import reactive
from textual.widgets import Static


class CounterApp(App):
    CSS = """
    #count { margin-top: 1; }
    #help { dock: bottom; }
    """
    BINDINGS = [
        ("plus,up", "add(1)"),
        ("minus,down", "add(-1)"),
        ("q", "quit"),
        Binding("ctrl+c", "quit", priority=True),
        ("ctrl+x", "fail"),
    ]
    count = reactive(0)

    def compose(self) -> ComposeResult:
        yield Static("Counter")
        yield Static("Count: 0", id="count")
        yield Static("+/- or Up/Down change the count, q quits", id="help")

    def on_mount(self) -> None:
        # SIGTERM and SIGINT: leave through App.exit, which restores the terminal.
        for sig in (signal.SIGTERM, signal.SIGINT):
            asyncio.get_running_loop().add_signal_handler(sig, self.exit)

    def watch_count(self, count: int) -> None:
        self.query_one("#count", Static).update(f"Count: {count}")

    def action_add(self, n: int) -> None:
        self.count += n

    def action_fail(self) -> None:
        raise RuntimeError("forced failure (Ctrl-X)")


if __name__ == "__main__":
    app = CounterApp()
    app.run()
    sys.exit(app.return_code or 0)
