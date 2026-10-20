#!/usr/bin/env python3
# Records terminal casts of the examples for the docs site, in the asciinema
# v2 format (one JSON header line, then one [time, "o", data] line for each
# output). It starts each start script of examples/target/bin in a
# pseudo-terminal of its own (python3 pty: the shell that runs this script is
# not changed), sends the keys of the script of the app, and keeps the output
# from the entry into the alternate screen up to the exit from it.
# Usage: python3 cast.py <bin dir> <out dir> [app ...]
# With no app, it records all the apps of SCRIPTS. scripts/docs-casts.sh runs
# it (it builds the start scripts first).
# claude 2026-10-20: created (L5): the casts of the docs site
import codecs
import fcntl
import json
import os
import pty
import select
import signal
import struct
import sys
import tempfile
import termios
import time

# The size of the terminal of a cast, unless its script gives one.
COLS, ROWS = 100, 30
ENTER_ALT = b"\x1b[?1049h"
LEAVE_ALT = b"\x1b[?1049l"
# The longest pause that a cast keeps: a longer wait (the JVM start, a quiet
# time of the script) becomes this pause.
MAX_GAP = 1.0

ESC, TAB, CR = "\x1b", "\t", "\r"
UP, DOWN, RIGHT, LEFT = "\x1b[A", "\x1b[B", "\x1b[C", "\x1b[D"


def ctrl(c):
    return chr(ord(c.lower()) - ord("a") + 1)


def paste(text):
    return "\x1b[200~" + text + "\x1b[201~"


def typed(text, each=0.08):
    """One step for each character, as a human types it."""
    return [(c, each) for c in text]


# The script of each app: the arguments of its start script, a text that its
# first frame shows, the size of the terminal (columns, rows), a file to write
# in its working directory, and the steps (the keys to send, and the seconds
# to wait after them).
SCRIPTS = {
    "counter": {
        "args": [],
        "ready": "Count:",
        "size": (60, 6),
        "steps": [("", 1.0), ("+", 0.6), ("+", 0.6), ("+", 0.6), ("-", 0.8),
                  (TAB, 0.6), (CR, 0.6), (CR, 0.6), (" ", 0.6), (TAB, 0.6),
                  (CR, 0.6), (" ", 0.8), ("+", 0.6), ("+", 1.0), ("q", 0.5)],
    },
    "todo": {
        "args": ["todos.txt"],
        "ready": "Todo",
        "file": ("todos.txt", "".join(
            f"[{'x' if i % 3 == 0 else ' '}] {t}\n" for i, t in enumerate([
                "write the docs", "record the casts", "build the web demo",
                "check every code block", "review the key tables",
                "a very long title that does not fit in one line of the "
                "list and so ends with an ellipsis at the edge of the box",
                "tune the layout", "plan the release", "read the log",
                "water the plants", "call the bank", "fix the bike",
                "book the train", "pay the rent", "clean the desk",
                "update the notes", "answer the mail", "sort the photos",
                "buy a new lamp", "walk the dog", "renew the passport",
                "repair the shelf", "print the tickets", "back up the laptop",
                "plan the trip", "learn a new song", "bake bread",
                "visit the museum", "return the books", "order the parts",
                "test the backup", "change the filter", "check the tyres",
                "write to grandma"]))),
        "steps": [("", 1.0)] + typed("buy milk") + [(CR, 0.6)]
                 + [(paste("tea time"), 0.6), (CR, 0.8), (TAB, 0.6)]
                 + [(DOWN, 0.2)] * 30 + [(" ", 0.6)] + [(UP, 0.2)] * 4
                 + [("d", 0.8), (ctrl("z"), 1.0), (ctrl("s"), 1.2), (ESC, 0.5)],
    },
    "dashboard": {
        "args": [],
        "ready": "Dashboard",
        "steps": [("", 2.0), ("p", 1.5), ("p", 1.0), (RIGHT, 1.2), (RIGHT, 1.2),
                  ("?", 2.0), (ESC, 1.0), (LEFT, 1.0), (LEFT, 1.5), ("q", 0.5)],
    },
    "forms": {
        "args": [],
        "ready": "Sign up",
        "size": (100, 20),
        "steps": [("", 1.0)] + typed("Ada Lovelace") + [(TAB, 0.4)]
                 + typed("secret") + [(TAB, 0.4)]
                 + [(UP, 0.3)] * 3 + [(TAB, 0.4)] + [(RIGHT, 0.3)] * 4
                 + [(TAB, 0.4), (" ", 0.4), (TAB, 0.4), (TAB, 0.4), (TAB, 0.4),
                    (" ", 0.5), (TAB, 0.4), (DOWN, 0.5), (TAB, 0.4), (DOWN, 0.5),
                    (TAB, 0.4)]
                 + typed("Hello") + [(ctrl("j"), 0.3)] + typed("from the terminal")
                 + [(CR, 2.0), (ESC, 0.5)],
    },
    "gallery": {
        "args": [],
        "ready": "Families",
        "steps": [("", 1.5), (DOWN, 1.8), (DOWN, 1.8), (DOWN, 1.2),
                  (ctrl("t"), 1.8), (DOWN, 1.8), (ctrl("p"), 1.0)]
                 + typed("help") + [(ESC, 1.0), ("\x11", 0.5)],
    },
    "assistant": {
        "args": ["--fake"],
        "ready": "Chats",
        "steps": [("", 1.0)] + typed("hi") + [(CR, 4.0)]
                 + typed("how do I test a UI?") + [(CR, 5.0)]
                 + typed("/fail") + [(CR, 4.0), ("\x11", 0.5)],
    },
}


def record(bin_dir, app, script):
    """The events of one run of `app`: (seconds, text), from the first frame."""
    work = tempfile.mkdtemp(prefix=f"tui4s-cast-{app}-")
    if "file" in script:
        name, text = script["file"]
        with open(os.path.join(work, name), "w", encoding="utf-8") as f:
            f.write(text)
    env = {k: v for k, v in os.environ.items()
           if k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")}
    cols, rows = script.get("size", (COLS, ROWS))
    env.update(TERM="xterm-256color", COLUMNS=str(cols), LINES=str(rows),
               HOME=work, XDG_DATA_HOME=work, XDG_CONFIG_HOME=work)
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(work)
        os.execve(os.path.join(bin_dir, app), [app] + script["args"], env)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    raw = []  # (time, bytes)
    seen = b""

    def pump(seconds):
        nonlocal seen
        end = time.monotonic() + seconds
        while True:
            left = end - time.monotonic()
            if left <= 0:
                return True
            r, _, _ = select.select([fd], [], [], min(left, 0.05))
            if r:
                try:
                    data = os.read(fd, 65536)
                except OSError:
                    return False
                if not data:
                    return False
                raw.append((time.monotonic(), data))
                seen = (seen + data)[-200000:]

    deadline = time.monotonic() + 90
    while script["ready"].encode() not in seen:
        if time.monotonic() > deadline or not pump(0.2):
            os.kill(pid, signal.SIGKILL)
            raise SystemExit(f"cast: {app}: no first frame ('{script['ready']}')")
    for keys, wait in script["steps"]:
        if keys:
            os.write(fd, keys.encode())
        pump(wait)
    end = time.monotonic() + 15
    while time.monotonic() < end:
        if os.waitpid(pid, os.WNOHANG)[0] == pid:
            break
        if not pump(0.2):
            os.waitpid(pid, 0)
            break
    else:
        os.kill(pid, signal.SIGTERM)
        os.waitpid(pid, 0)
    os.close(fd)
    return raw


def events(raw):
    """The output from the entry into the alternate screen up to the exit from
    it, as (seconds, text) with gaps of at most MAX_GAP."""
    data = b"".join(d for _, d in raw)
    start = data.find(ENTER_ALT)
    stop = data.find(LEAVE_ALT, start + 1)
    if start < 0 or stop < 0:
        raise SystemExit("cast: the run did not enter and leave the alternate screen")
    out, pos, clock, last = [], 0, 0.0, None
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    for t, d in raw:
        lo, hi = max(start, pos), min(stop, pos + len(d))
        pos += len(d)
        if lo >= hi:
            continue
        chunk = d[lo - (pos - len(d)):hi - (pos - len(d))]
        if last is not None:
            clock += min(t - last, MAX_GAP)
        last = t
        text = decoder.decode(chunk)
        if text:
            out.append((round(clock, 3), text))
    return out


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: cast.py <bin dir> <out dir> [app ...]")
    bin_dir, out_dir = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    apps = sys.argv[3:] or list(SCRIPTS)
    os.makedirs(out_dir, exist_ok=True)
    for app in apps:
        evs = events(record(bin_dir, app, SCRIPTS[app]))
        path = os.path.join(out_dir, f"{app}.cast")
        cols, rows = SCRIPTS[app].get("size", (COLS, ROWS))
        header = {"version": 2, "width": cols, "height": rows,
                  "title": f"tui4s: examples/target/bin/{app} {' '.join(SCRIPTS[app]['args'])}".strip(),
                  "env": {"TERM": "xterm-256color"}}
        with open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(header) + "\n")
            for t, text in evs:
                f.write(json.dumps([t, "o", text], ensure_ascii=False) + "\n")
        print(f"cast: {path}: {os.path.getsize(path)} bytes, {evs[-1][0] if evs else 0:.1f} s")


if __name__ == "__main__":
    main()
