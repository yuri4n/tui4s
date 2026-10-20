"""A program in a pseudo-terminal: start, keys, screen, resize, signals, and the checks of the restore contract.

The screen is the small VT emulator of the PTY kit of tui4s (examples/ptycheck/vt.py), fed by a reader thread. Each
chunk of output has a monotonic time stamp (ns), so the harness can say when the screen first showed a state.
"""
# claude 2026-10-20: created (L5): the PTY session of the bench harness

import codecs
import fcntl
import re
import os
import select
import signal
import struct
import subprocess
import sys
import termios
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.dirname(HERE)
REPO = os.path.dirname(BENCH)
sys.path.insert(0, os.path.join(REPO, "examples", "ptycheck"))
import vt  # noqa: E402

KEYS = {
    "up": b"\x1b[A",
    "down": b"\x1b[B",
    "pgup": b"\x1b[5~",
    "pgdn": b"\x1b[6~",
    "tab": b"\t",
    "ctrl-c": b"\x03",
    "ctrl-x": b"\x18",
    "backspace": b"\x7f",
}


# A complete escape sequence at the start of a string (CSI, OSC, charset, short ESC).
COMPLETE = re.compile(r"\x1b(?:\[[0-9;:?>=!<]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[()*+][A-Za-z0-9]|[^\[\]()*+])")


# The queries that a real terminal answers (xterm): cursor position (CPR), colours (OSC 10 and 11), device
# attributes (DA1, DA2). A stack that asks waits for the answer, so the harness answers as a terminal does.
QUERY = re.compile(r"\x1b\[6n|\x1b\]1([01]);\?(?:\x07|\x1b\\)|\x1b\[0?c|\x1b\[>0?c")


def split_tail(text):
    """Splits off an escape sequence that the chunk cuts: vt.Screen reads only whole sequences."""
    i = text.rfind("\x1b")
    if i < 0 or COMPLETE.match(text, i) or len(text) - i > 256:
        return text, ""
    return text[:i], text[i:]


class Screen(vt.Screen):
    """vt.Screen with a resize: the cells that still fit stay, as in a real terminal."""

    def resize(self, rows, cols):
        def fit(buf):
            out = [[" "] * cols for _ in range(rows)]
            for y in range(min(rows, self.rows)):
                for x in range(min(cols, self.cols)):
                    out[y][x] = buf[y][x] if buf[y][x] is not None else " "
            return out

        self.main, self.alt = fit(self.main), fit(self.alt)
        self.rows, self.cols = rows, cols
        self.clamp()


def descendants(pid):
    """The PIDs of the processes under pid (by the parent PID in /proc)."""
    kids = {}
    for d in os.listdir("/proc"):
        if d.isdigit():
            try:
                with open(f"/proc/{d}/stat") as f:
                    fields = f.read().rsplit(")", 1)[1].split()
                kids.setdefault(int(fields[1]), []).append(int(d))
            except OSError:
                pass
    out, todo = [], [pid]
    while todo:
        p = todo.pop()
        for k in kids.get(p, []):
            out.append(k)
            todo.append(k)
    return out


def session_members(sid):
    """The live PIDs whose session is sid (zombies are not alive)."""
    out = []
    for d in os.listdir("/proc"):
        if d.isdigit():
            try:
                with open(f"/proc/{d}/stat") as f:
                    fields = f.read().rsplit(")", 1)[1].split()
                if int(fields[3]) == sid and fields[0] != "Z":
                    out.append(int(d))
            except OSError:
                pass
    return out


def alive(pid):
    try:
        with open(f"/proc/{pid}/stat") as f:
            return f.read().rsplit(")", 1)[1].split()[0] != "Z"
    except OSError:
        return False


class Session:
    def __init__(self, argv, cwd=None, rows=30, cols=100, env=None):
        self.rows, self.cols = rows, cols
        self.master, self.slave = os.openpty()
        fcntl.ioctl(self.slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        self.saved = termios.tcgetattr(self.slave)
        self.screen = Screen(rows, cols)
        self.raw = bytearray()
        self.chunks = []  # (ns, len)
        self.lock = threading.Condition()
        self.pending = None
        self.watch = None  # called by the reader after each chunk: watch(ns, session), under the lock
        self.stop = False
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self.tail = ""
        e = dict(os.environ if env is None else env)
        # A user's terminal has none of these: CI makes ink render only at the end; the JVM prints a line for
        # each JAVA_TOOL_OPTIONS or JDK_JAVA_OPTIONS.
        for k in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "COLUMNS", "LINES", "CI", "GITHUB_ACTIONS"):
            e.pop(k, None)
        e.setdefault("TERM", "xterm-256color")
        e.setdefault("TZ", "UTC")
        self.t0 = time.monotonic_ns()
        self.proc = subprocess.Popen(
            argv,
            cwd=cwd,
            env=e,
            stdin=self.slave,
            stdout=self.slave,
            stderr=self.slave,
            start_new_session=True,
            preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0),
            close_fds=True,
        )
        self.pid = self.proc.pid
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    # -- output

    def _read(self):
        while not self.stop:
            r, _, _ = select.select([self.master], [], [], 0.05)
            if not r:
                continue
            try:
                data = os.read(self.master, 65536)
            except OSError:
                break
            if not data:
                break
            ns = time.monotonic_ns()
            with self.lock:
                self.raw += data
                self.chunks.append((ns, len(data)))
                text, self.tail = split_tail(self.tail + self.decoder.decode(data))
                self.screen.feed(text)
                for q in QUERY.finditer(text):
                    self._answer(q)
                if self.watch is not None:
                    self.watch(ns, self)
                p = self.pending
                if p is not None and p["ns"] is None and p["pred"](self):
                    p["ns"] = ns
                self.lock.notify_all()

    def _answer(self, q):
        t = q.group(0)
        if t == "\x1b[6n":
            reply = f"\x1b[{self.screen.row + 1};{min(self.screen.col, self.cols - 1) + 1}R"
        elif q.group(1) is not None:
            reply = f"\x1b]1{q.group(1)};rgb:{'ffff/ffff/ffff' if q.group(1) == '0' else '0000/0000/0000'}\x1b\\"
        elif t.startswith("\x1b[>"):
            reply = "\x1b[>41;380;0c"
        else:
            reply = "\x1b[?62;22c"
        try:
            os.write(self.master, reply.encode())
        except OSError:
            pass

    def lines(self):
        return self.screen.lines()

    def row(self, n):
        """Row n (1-based); a negative n counts from the bottom (-1 is the last row)."""
        ls = self.screen.lines()
        return ls[n - 1] if n > 0 else ls[n]

    def text(self):
        return self.screen.text()

    def expect(self, pred, timeout=1.5):
        """Waits until pred(self) holds; returns the time stamp (ns) of the chunk that made it hold, or None."""
        deadline = time.monotonic() + timeout
        with self.lock:
            p = {"pred": pred, "ns": None}
            if pred(self):
                return self.chunks[-1][0] if self.chunks else self.t0
            self.pending = p
            try:
                while p["ns"] is None:
                    left = deadline - time.monotonic()
                    if left <= 0:
                        return None
                    self.lock.wait(left)
                return p["ns"]
            finally:
                self.pending = None

    def arm(self, pred):
        """Registers pred before an input: the reader stamps the first chunk after which it holds."""
        with self.lock:
            self.pending = {"pred": pred, "ns": None}
            return self.pending

    def await_armed(self, p, timeout=1.5):
        deadline = time.monotonic() + timeout
        with self.lock:
            try:
                while p["ns"] is None:
                    left = deadline - time.monotonic()
                    if left <= 0:
                        return None
                    self.lock.wait(left)
                return p["ns"]
            finally:
                self.pending = None

    # -- input

    def send(self, data):
        if isinstance(data, str):
            data = KEYS.get(data, data.encode())
        ns = time.monotonic_ns()
        os.write(self.master, data)
        return ns

    def resize(self, rows, cols):
        with self.lock:
            self.screen.resize(rows, cols)
            self.rows, self.cols = rows, cols
        fcntl.ioctl(self.master, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    def signal(self, sig):
        os.kill(self.pid, sig)

    # -- process

    def tree(self):
        return [self.pid] + descendants(self.pid)

    def cpu_ticks(self):
        total = 0
        for p in self.tree():
            try:
                with open(f"/proc/{p}/stat") as f:
                    fields = f.read().rsplit(")", 1)[1].split()
                total += int(fields[11]) + int(fields[12])
            except OSError:
                pass
        return total

    def rss_kb(self):
        total = 0
        for p in self.tree():
            try:
                with open(f"/proc/{p}/status") as f:
                    for line in f:
                        if line.startswith("VmRSS:"):
                            total += int(line.split()[1])
            except OSError:
                pass
        return total

    def wait(self, timeout):
        try:
            return self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            return None

    # claude 2026-10-20: created: the terminal modes while the program runs (review fix: the oracle checks them)
    def modes(self):
        """The terminal modes while the program runs (counter.md, "Terminal modes")."""
        with self.lock:
            lflag = termios.tcgetattr(self.slave)[3]
            return {
                "raw": not (lflag & (termios.ICANON | termios.ECHO)),
                "alt_screen": bool(self.screen.in_alt),
                "cursor_hidden": not self.screen.cursor_shown,
            }

    def restore_report(self, members_before):
        """The restore contract after the end of the program (counter.md, "Restore contract")."""
        time.sleep(0.15)  # the last bytes reach the reader
        with self.lock:
            now = termios.tcgetattr(self.slave)
            lbits = termios.ICANON | termios.ECHO | termios.ISIG
            cooked = (now[3] & lbits) == (self.saved[3] & lbits) and (now[0] & termios.ICRNL) == (
                self.saved[0] & termios.ICRNL
            )
            s = self.screen
            if s.alt_entered == 0:
                alt = "never-entered"
            else:
                alt = "left" if not s.in_alt else "not-left"
            cursor = "visible" if s.cursor_shown else "hidden"
        left = [p for p in members_before if alive(p)] + [p for p in session_members(self.pid) if p != self.pid]
        return {
            "cooked": cooked,
            "alt_screen": alt,
            "cursor": cursor,
            "orphans": sorted(set(left)),
            "restored": cooked and alt != "not-left" and cursor == "visible" and not left,
        }

    def close(self):
        if self.proc.poll() is None:
            for p in self.tree():
                try:
                    os.kill(p, signal.SIGKILL)
                except OSError:
                    pass
            self.wait(5)
        for p in session_members(self.pid):
            try:
                os.kill(p, signal.SIGKILL)
            except OSError:
                pass
        self.stop = True
        self.reader.join(1)
        for fd in (self.master, self.slave):
            try:
                os.close(fd)
            except OSError:
                pass
