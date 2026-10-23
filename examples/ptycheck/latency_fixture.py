#!/usr/bin/env python3
"""A raw PTY application with deliberate input faults for latency_test.py."""
# codex 2026-10-22: created: the real-process measurement fixture.

import argparse
import os
from pathlib import Path
import select
import signal
import termios
import time
import tty


# codex 2026-10-22: created: complete rows let the observer test actual screen text.
def draw(text, updates, running=True):
    state = "running" if running else "done"
    os.write(1, (f"\x1b[1;1HInput: {text}\x1b[K"
                 f"\x1b[2;1HUpdates: {updates}\x1b[K"
                 f"\x1b[3;1HLoad: {state}\x1b[K").encode())


# codex 2026-10-22: created: status output continues while keys are delayed or lost.
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("normal", "delay", "drop", "wrong", "batch", "hung", "no-ready"),
                        default="normal")
    parser.add_argument("--ignore-quit", action="store_true")
    parser.add_argument("--exit-file", type=Path)
    args = parser.parse_args()
    saved = termios.tcgetattr(0)
    tty.setraw(0)
    if args.ignore_quit or args.mode in ("hung", "no-ready"):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    text, pending, count, updates = "", [], 0, 0
    start = time.monotonic()
    next_status = start
    try:
        if args.mode != "no-ready":
            draw(text, updates)
        while True:
            now = time.monotonic()
            ready, _, _ = select.select([0], [], [], .005)
            if ready:
                data = os.read(0, 1024)
                if not data or (b"\x03" in data and not args.ignore_quit):
                    return
                data = data.replace(b"\x03", b"")
                for char in data.decode():
                    count += 1
                    if args.mode == "drop" and count == 3:
                        continue
                    if args.mode in ("hung", "no-ready"):
                        continue
                    value = "!" if args.mode == "wrong" and count == 3 else char
                    delay = .25 if args.mode == "delay" else 0
                    pending.append((now + delay, value))
            due = [item for item in pending if item[0] <= now]
            pending = [item for item in pending if item[0] > now]
            for _, char in due:
                text += char
            if args.mode in ("batch", "delay") and count < 6:
                shown = ""
            else:
                shown = text
            if args.mode != "no-ready" and (due or now >= next_status):
                updates += 1
                draw(shown, updates)
                next_status = now + .01
    finally:
        termios.tcsetattr(0, termios.TCSANOW, saved)
        if args.exit_file:
            args.exit_file.write_text("graceful exit\n")


if __name__ == "__main__":
    main()
