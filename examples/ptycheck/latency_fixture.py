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


# codex 2026-10-22: created: status output continues while keys are delayed or lost; changed: expose independent shutdown faults after correct input.
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("normal", "delay", "drop", "wrong", "batch", "hung", "no-ready"),
                        default="normal")
    parser.add_argument("--ignore-quit", action="store_true")
    parser.add_argument("--no-restore", action="store_true")
    parser.add_argument("--exit-code", type=int, default=0)
    parser.add_argument("--leave-child", action="store_true")
    parser.add_argument("--startup-delay", type=float, default=0)
    parser.add_argument("--exit-file", type=Path)
    args = parser.parse_args()
    saved = termios.tcgetattr(0)
    tty.setraw(0)
    os.write(1, b"\x1b[?1049h\x1b[?25l")
    if args.ignore_quit or args.mode in ("hung", "no-ready"):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    text, pending, count, updates = "", [], 0, 0
    start = time.monotonic()
    next_status = start
    try:
        time.sleep(args.startup_delay)
        if args.mode != "no-ready":
            draw(text, updates)
        while True:
            now = time.monotonic()
            ready, _, _ = select.select([0], [], [], .005)
            if ready:
                data = os.read(0, 1024)
                if not data or (b"\x03" in data and not args.ignore_quit):
                    # cursor 2026-10-28: changed: the sleeper ignores SIGHUP so the session end does not reap it before the harness.
                    if args.leave_child and os.fork() == 0:
                        signal.signal(signal.SIGHUP, signal.SIG_IGN)
                        signal.signal(signal.SIGTERM, signal.SIG_IGN)
                        time.sleep(60)
                        os._exit(0)
                    return args.exit_code
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
        if not args.no_restore:
            termios.tcsetattr(0, termios.TCSANOW, saved)
            os.write(1, b"\x1b[?25h\x1b[?1049l")
        if args.exit_file:
            args.exit_file.write_text("graceful exit\n")


# codex 2026-10-22: changed: propagate the fixture exit code to the parent harness.
if __name__ == "__main__":
    raise SystemExit(main())
