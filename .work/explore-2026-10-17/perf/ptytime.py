"""PTY timing driver: time from spawn to the first terminal frame of a tui4s app.

Usage (one run, prints one JSON line):
  python3 ptytime.py --expect TEXT [--hold-ms N] [--key q] [--log FILE] -- CMD ARGS...

Clock: time.monotonic_ns (CLOCK_MONOTONIC), the same clock as Java's System.nanoTime on Linux and the
-Xlog decorator `tn`, so JVM log lines and app marks can be placed on the same time line.
"""
import argparse
import codecs
import fcntl
import json
import os
import select
import signal
import struct
import subprocess
import sys
import termios
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from vtscreen import Screen  # noqa: E402  (a copy of examples/ptycheck/vt.py)


def proc_status(pid):
    out = {}
    try:
        with open(f"/proc/{pid}/status") as f:
            for line in f:
                k, _, v = line.partition(":")
                if k in ("VmHWM", "VmRSS", "Threads"):
                    out[k] = v.strip()
    except OSError:
        pass
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--expect", required=True, help="text that marks the first full frame")
    ap.add_argument("--hold-ms", type=int, default=300, help="time to stay up after the first frame")
    ap.add_argument("--key", default="q")
    ap.add_argument("--rows", type=int, default=30)
    ap.add_argument("--cols", type=int, default=100)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--log", default=None, help="write the raw output here")
    ap.add_argument("--script", default=None,
                    help="training script: comma list of KEY@MS (sent MS after the first frame), before the quit key")
    ap.add_argument("cmd", nargs=argparse.REMAINDER)
    a = ap.parse_args()
    cmd = a.cmd[1:] if a.cmd and a.cmd[0] == "--" else a.cmd

    master, slave = os.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", a.rows, a.cols, 0, 0))
    env = dict(os.environ)
    env.pop("JAVA_TOOL_OPTIONS", None)  # the container's proxy flags: "Picked up ..." would print into the terminal
    env["TERM"] = "xterm-256color"
    env["JAVA_HOME"] = "/usr/lib/jvm/java-25-openjdk-amd64"  # the container default is JDK 21

    def ctty():
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)

    t0 = time.monotonic_ns()
    p = subprocess.Popen(cmd, stdin=slave, stdout=slave, stderr=slave, env=env, start_new_session=True,
                         preexec_fn=ctty, close_fds=True)
    os.close(slave)
    pid = p.pid
    dec = codecs.getincrementaldecoder("utf-8")("replace")
    scr = Screen(a.rows, a.cols)
    raw = bytearray()
    t = {"first_byte": None, "alt_enter": None, "first_text": None, "frame": None, "quit_sent": None,
         "alt_leave": None, "eof": None}
    status = {}
    script = []
    if a.script:
        for item in a.script.split(","):
            k, _, ms = item.rpartition("@")
            k = k.encode().decode("unicode_escape")
            script.append((int(ms), k))
    deadline = t0 + int(a.timeout * 1e9)
    quit_at = None
    sent = 0
    while True:
        now = time.monotonic_ns()
        if now > deadline:
            os.kill(pid, signal.SIGKILL)
            t["timeout"] = True
            break
        if t["frame"] is not None:
            while sent < len(script) and now >= t["frame"] + script[sent][0] * 1_000_000:
                os.write(master, script[sent][1].encode())
                sent += 1
        if quit_at is not None and t["quit_sent"] is None and now >= quit_at and sent >= len(script):
            status = proc_status(pid)
            t["quit_sent"] = time.monotonic_ns()
            os.write(master, a.key.encode())
        try:
            r, _, _ = select.select([master], [], [], 0.002)
        except InterruptedError:
            continue
        if not r:
            if p.poll() is not None and t["eof"] is None:
                # the child is gone; drain what is left
                pass
            else:
                continue
        try:
            chunk = os.read(master, 65536)
        except OSError:
            chunk = b""
        tn = time.monotonic_ns()
        if not chunk:
            t["eof"] = tn
            break
        raw += chunk
        if t["first_byte"] is None:
            t["first_byte"] = tn
        scr.feed(dec.decode(chunk))
        if t["alt_enter"] is None and scr.alt_entered:
            t["alt_enter"] = tn
        if scr.in_alt and (t["first_text"] is None or t["frame"] is None):
            txt = "\n".join("".join(c for c in row if c is not None) for row in scr.alt)
            if t["first_text"] is None and txt.strip():
                t["first_text"] = tn
            if t["frame"] is None and a.expect in txt:
                t["frame"] = tn
                quit_at = tn + a.hold_ms * 1_000_000
        if t["alt_leave"] is None and scr.alt_left:
            t["alt_leave"] = tn
    _, wstatus, ru = os.wait4(pid, 0)
    t_exit = time.monotonic_ns()
    os.close(master)
    if a.log:
        with open(a.log, "wb") as f:
            f.write(bytes(raw))

    def ms(x):
        return None if x is None else round((x - t0) / 1e6, 2)

    res = {k: ms(v) for k, v in t.items() if k != "timeout"}
    res["exit"] = ms(t_exit)
    res["t0_ns"] = t0
    res["exit_code"] = os.waitstatus_to_exitcode(wstatus)
    res["maxrss_kb"] = ru.ru_maxrss
    res["hwm_before_quit"] = status.get("VmHWM")
    res["rss_before_quit"] = status.get("VmRSS")
    res["threads_before_quit"] = status.get("Threads")
    res["timeout"] = bool(t.get("timeout"))
    res["bytes"] = len(raw)
    print(json.dumps(res))


if __name__ == "__main__":
    main()
