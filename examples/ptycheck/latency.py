#!/usr/bin/env python3
"""Measure independently paced keys against the decoded PTY screen.

Example: python3 examples/ptycheck/latency.py --output work/latency.json
  --command examples/target/bin/settlement --updates=1000 --duration=10
The endpoint is terminal output, not the paint of a physical display.
"""
# codex 2026-10-22: created: the PTY input-latency probe.

import argparse
import json
import math
import os
from pathlib import Path
import random
import re
import select
import signal
import subprocess
import sys
import termios
import threading
import time
from dataclasses import dataclass

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "bench" / "harness"))
from term import QUERY, Session, split_tail  # noqa: E402


# codex 2026-10-22: created: one key's schedule, write and screen times.
@dataclass
class Sample:
    planned: int
    attempted: int | None = None
    write_started: int | None = None
    written: int | None = None
    visible: int | None = None
    load_running: bool = False


# codex 2026-10-22: created: deterministic text with no terminal control keys.
def key_text(count):
    rng = random.Random(1729)
    return "".join(rng.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(count))


# codex 2026-10-22: created: nearest-rank percentiles retain the sample count.
def distribution(values):
    values = sorted(values)
    if not values:
        return {"samples": 0, "p50": None, "p95": None, "p99": None, "max": None}
    return {"samples": len(values), **{
        name: round(values[max(0, math.ceil(q * len(values)) - 1)] / 1e6, 3)
        for name, q in (("p50", .5), ("p95", .95), ("p99", .99), ("max", 1))
    }}


# codex 2026-10-22: created: exact prompt parsing, separate from the PTY effects.
def fields(lines):
    inputs = [line[len("Input: "):] if line.startswith("Input: ") else ""
              for line in lines if line.startswith("Input: ") or line == "Input:"]
    updates = [int(m.group(1)) for line in lines
               if (m := re.fullmatch(r"Updates: ([0-9]+)", line))]
    running = "Load: running" in lines
    return inputs, updates[0] if len(updates) == 1 else None, running


# codex 2026-10-22: created: bounded operational state, guarded by Session.lock.
class Observation:
    # codex 2026-10-22: created: the screen state and finite per-key trace; changed: retain first readiness separately from input latency.
    def __init__(self, expected):
        self.expected = expected
        self.samples = []
        self.ready = False
        self.ready_ns = None
        self.started = None
        self.last_input = None
        self.current_input = None
        self.incorrect = 0
        self.bad_inputs = []
        self.update_value = None
        self.first_update = None
        self.last_update = None
        self.update_changes = 0

    # codex 2026-10-22: created: one observer acknowledges all covered prefixes; changed: stamp only the first ready screen.
    def screen(self, ns, session):
        inputs, updates, running = fields(session.lines())
        self.current_input = inputs[0] if len(inputs) == 1 else None
        if not self.ready and len(inputs) == 1 and inputs[0] == "" and updates is not None:
            self.ready = True
            self.ready_ns = ns
        if self.started is None:
            self.update_value = updates
            return
        if updates is not None and (self.update_value is None or updates > self.update_value):
            self.update_changes += 1
            self.first_update = self.first_update or ns
            self.last_update = ns
            self.update_value = updates
        if len(inputs) != 1:
            return
        value = inputs[0]
        if value == self.last_input:
            return
        self.last_input = value
        written = sum(s.written is not None for s in self.samples)
        if len(value) > written or not self.expected.startswith(value):
            self.incorrect += 1
            if len(self.bad_inputs) < 8:
                self.bad_inputs.append({"at_ms": (ns - self.started) / 1e6,
                                        "text": value[:200]})
            return
        for sample in self.samples[:len(value)]:
            if sample.visible is None and sample.write_started is not None and ns >= sample.write_started:
                sample.visible = ns
                sample.load_running = running


# codex 2026-10-22: created: the existing PTY session with bounded output storage.
class ProbeSession(Session):
    # codex 2026-10-22: created: install the observer and cleanup state before the reader starts; changed: retain shutdown evidence and reader drainage.
    def __init__(self, command, observation, cols):
        self.observation = observation
        self.reader_error = None
        self._closed = False
        self.shutdown = None
        self.output_drained = threading.Event()
        try:
            super().__init__(command, cwd=REPO, rows=20, cols=cols)
        except BaseException:
            if hasattr(self, "proc"):
                self.proc.kill()
                self.proc.wait()
            for name in ("master", "slave"):
                if hasattr(self, name):
                    os.close(getattr(self, name))
            raise

    # codex 2026-10-22: created: continuously consume output without a per-key wait; changed: acknowledge drained output for the terminal restore check.
    def _read(self):
        try:
            while not self.stop:
                ready, _, _ = select.select([self.master], [], [], .05)
                if not ready:
                    self.output_drained.set()
                    continue
                self.output_drained.clear()
                try:
                    data = os.read(self.master, 65536)
                except BlockingIOError:
                    continue
                except OSError:
                    break
                if not data:
                    break
                ns = time.monotonic_ns()
                with self.lock:
                    self.raw = (self.raw + data)[-4096:]
                    self.chunks = (self.chunks + [(ns, len(data))])[-16:]
                    text, self.tail = split_tail(self.tail + self.decoder.decode(data))
                    self.screen.feed(text)
                    for query in QUERY.finditer(text):
                        self._answer(query)
                    self.observation.screen(ns, self)
                    self.lock.notify_all()
        except Exception as error:
            with self.lock:
                self.reader_error = f"{type(error).__name__}: {error}"
                self.lock.notify_all()

    # codex 2026-10-22: created: signal the private process group without Linux /proc; changed: report whether a group received the signal.
    def _signal_group(self, sig):
        try:
            os.killpg(self.proc.pid, sig)
            return True
        except ProcessLookupError:
            return False

    # codex 2026-10-22: created: check the same cooked-mode, alternate-screen and cursor contract as the PTY kit.
    def terminal_state(self):
        with self.lock:
            actual = termios.tcgetattr(self.slave)
            bits = termios.ICANON | termios.ECHO | termios.ISIG
            cooked = ((actual[3] & bits) == (self.saved[3] & bits)
                      and (actual[0] & termios.ICRNL) == (self.saved[0] & termios.ICRNL))
            return {"cooked": cooked, "alt_screen_left": not self.screen.in_alt,
                    "cursor_visible": self.screen.cursor_shown}

    # codex 2026-10-22: created: allow a final diagnostic snapshot, then bound POSIX cleanup; changed: expose forced termination and terminal restoration before closing descriptors.
    def close(self, grace_seconds=1):
        if self._closed:
            return self.shutdown
        self._closed = True
        started = time.monotonic_ns()
        outcome = {"quit_sent": False, "term_sent": False, "kill_sent": False,
                   "exit_code": None, "terminal_restored": False, "errors": []}
        try:
            if self.proc.poll() is None:
                try:
                    os.set_blocking(self.master, False)
                    outcome["quit_sent"] = os.write(self.master, b"\x03") == 1
                except OSError as error:
                    outcome["errors"].append(f"quit write: {error}")
                try:
                    self.proc.wait(timeout=grace_seconds)
                except subprocess.TimeoutExpired:
                    outcome["term_sent"] = self._signal_group(signal.SIGTERM)
                    try:
                        self.proc.wait(timeout=.25)
                    except subprocess.TimeoutExpired:
                        pass
        finally:
            # A launcher can exit before its children. Clear its remaining group too.
            outcome["kill_sent"] = self._signal_group(signal.SIGKILL)
            try:
                self.proc.wait(timeout=1)
            except subprocess.TimeoutExpired:
                outcome["errors"].append("process did not exit after SIGKILL")
            outcome["exit_code"] = self.proc.returncode
            outcome["elapsed_ms"] = round((time.monotonic_ns() - started) / 1e6, 3)
            self.output_drained.clear()
            if not self.output_drained.wait(1):
                outcome["errors"].append("terminal output did not drain after exit")
            try:
                outcome["terminal"] = self.terminal_state()
                outcome["terminal_restored"] = all(outcome["terminal"].values())
            except (OSError, termios.error) as error:
                outcome["errors"].append(f"terminal restore check: {error}")
            self.stop = True
            self.reader.join(1)
            if self.reader.is_alive():
                outcome["errors"].append("PTY reader did not stop")
            if self.reader_error:
                outcome["errors"].append("PTY reader: " + self.reader_error)
            for fd in (self.master, self.slave):
                try:
                    os.close(fd)
                except OSError:
                    pass
            outcome["graceful"] = (outcome["quit_sent"] and outcome["exit_code"] == 0
                                   and not outcome["term_sent"] and not outcome["kill_sent"]
                                   and not outcome["errors"])
            self.shutdown = outcome
        return outcome


# codex 2026-10-22: created: schedule each write independently of screen feedback.
def send_keys(session, observation, stop, end_ns, errors):
    try:
        for char, sample in zip(observation.expected, observation.samples):
            if stop.wait(max(0, (sample.planned - time.monotonic_ns()) / 1e9)):
                return
            with session.lock:
                sample.attempted = time.monotonic_ns()
            while not stop.is_set() and time.monotonic_ns() < end_ns:
                _, writable, _ = select.select([], [session.master], [], .02)
                if not writable:
                    continue
                with session.lock:
                    try:
                        sample.write_started = time.monotonic_ns()
                        if os.write(session.master, char.encode("ascii")) == 1:
                            sample.written = time.monotonic_ns()
                            session.lock.notify_all()
                            break
                    except BlockingIOError:
                        sample.write_started = None
            else:
                return
    except Exception as error:
        with session.lock:
            errors.append(f"input writer: {type(error).__name__}: {error}")
            session.lock.notify_all()


# codex 2026-10-22: created: report every planned key, including missing effects.
def report(observation, args, errors, elapsed_ns):
    samples = observation.samples
    visible = [s for s in samples if s.visible is not None]
    attempted = [s for s in samples if s.attempted is not None]
    missing = args.keys - len(visible)
    deadline = args.deadline_ms * 1e6
    late = sum(s.visible - s.planned > deadline for s in visible)
    running = sum(s.load_running for s in visible)
    overlap = bool(observation.first_update is not None and any(
        observation.first_update < s.visible < observation.last_update for s in visible))
    failures = list(errors)
    if missing:
        failures.append(f"{missing} keys have no correct screen effect")
    if late:
        failures.append(f"{late} visible keys exceeded the planned-send deadline")
    if observation.incorrect:
        failures.append(f"{observation.incorrect} incorrect input states were observed")
    if not missing and observation.current_input != observation.expected:
        failures.append("the final input field differs from the sent text")
    if args.require_updates and not overlap:
        failures.append("no input effect was bracketed by advancing update observations")
    if args.require_load_running and running != args.keys:
        failures.append("not every key was observed with Load: running")
    start = observation.started or 0
    return {
        "schema": 1, "ok": not failures, "failures": failures,
        "endpoint": "decoded PTY input field; excludes physical display paint",
        "deadline_basis": "planned send to correct screen effect; missing keys also fail",
        "load_evidence": "screen observations only; a running marker can be stale",
        "command": args.command, "rate_per_second": args.rate,
        "columns": args.cols, "expected_text": observation.expected,
        "extra_observation_ms": args.observe_ms,
        "deadline_ms": args.deadline_ms, "elapsed_ms": round(elapsed_ns / 1e6, 3),
        "planned_count": args.keys, "attempted_count": len(attempted),
        "written_count": sum(s.written is not None for s in samples),
        "visible_count": len(visible), "missing_count": missing,
        "incorrect_count": observation.incorrect, "deadline_count": late + missing,
        "final_text_matches": observation.current_input == observation.expected,
        "observed_load_running_count": running,
        "observed_update_changes": observation.update_changes,
        "observed_updates_bracket_input": overlap,
        "send_to_visible_ms": distribution([s.visible - s.attempted for s in visible]),
        "planned_to_visible_ms": distribution([s.visible - s.planned for s in visible]),
        "send_drift_ms": distribution([s.attempted - s.planned for s in attempted]),
        "send_blocking_ms": distribution([s.written - s.attempted for s in samples
                                          if s.written is not None]),
        "bad_inputs": observation.bad_inputs,
        "samples": [{"index": i + 1, **{
            name + "_ms": None if (ns := getattr(s, name)) is None else round((ns - start) / 1e6, 3)
            for name in ("planned", "attempted", "write_started", "written", "visible")},
            "load_running_at_observation": s.load_running}
            for i, s in enumerate(samples)],
    }


# codex 2026-10-22: created: shutdown is part of acceptance, separate from the input timing endpoint.
def with_shutdown(result, shutdown, required):
    failures = list(result["failures"])
    if required:
        if shutdown is None:
            failures.append("shutdown: no application session to check")
        else:
            if not shutdown["graceful"]:
                failures.append("shutdown: Ctrl-C did not give a normal exit without forced cleanup")
            if not shutdown["terminal_restored"]:
                failures.append("shutdown: terminal modes, alternate screen or cursor were not restored")
            failures.extend("shutdown: " + error for error in shutdown["errors"])
    return {**result, "ok": not failures, "failures": failures,
            "require_graceful_exit": required, "shutdown": shutdown}


# codex 2026-10-22: created: external watchdog and cleanup own both I/O threads; changed: include cleanup outcomes and separate startup timing in the returned report.
def run(args):
    observation = Observation(key_text(args.keys))
    errors, stop = [], threading.Event()
    session, writer, result, shutdown = None, None, None, None
    begin = time.monotonic_ns()
    try:
        session = ProbeSession(args.command, observation, args.cols)
        os.set_blocking(session.master, False)
        startup_end = begin + int(args.startup_seconds * 1e9)
        with session.lock:
            while not observation.ready and time.monotonic_ns() < startup_end:
                if session.reader_error or session.proc.poll() is not None:
                    break
                session.lock.wait(.02)
            if not observation.ready:
                errors.append("startup: no empty Input field and Updates counter before the watchdog")
            else:
                start = time.monotonic_ns() + 50_000_000
                observation.started = start
                observation.samples = [Sample(start + round(i * 1e9 / args.rate))
                                       for i in range(args.keys)]
        if observation.ready:
            end = observation.samples[-1].planned + int(
                (args.deadline_ms + args.observe_ms) * 1e6)
            writer = threading.Thread(target=send_keys,
                                      args=(session, observation, stop, end, errors), daemon=True)
            writer.start()
            with session.lock:
                while time.monotonic_ns() < end:
                    if session.reader_error or session.proc.poll() is not None:
                        errors.append("application or PTY reader ended during observation")
                        break
                    session.lock.wait(min(.02, max(0, (end - time.monotonic_ns()) / 1e9)))
        stop.set()
        if writer:
            writer.join(1)
            if writer.is_alive():
                errors.append("input writer did not stop at the watchdog")
        with session.lock:
            if session.reader_error:
                errors.append("PTY reader: " + session.reader_error)
            result = report(observation, args, errors, time.monotonic_ns() - begin)
            result["screen_tail"] = session.lines()
    except (OSError, ValueError) as error:
        errors.append(f"probe setup: {type(error).__name__}: {error}")
        result = report(observation, args, errors, time.monotonic_ns() - begin)
    finally:
        stop.set()
        if session:
            shutdown = session.close(args.quit_seconds)
        if writer:
            writer.join(1)
    result["startup_to_ready_ms"] = (None if observation.ready_ns is None
                                     else round((observation.ready_ns - begin) / 1e6, 3))
    return with_shutdown(result, shutdown, args.require_graceful_exit)


# codex 2026-10-22: created: validate a finite scenario before allocating its trace; changed: configure the graceful-quit watchdog and require shutdown by default.
def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rate", type=float, default=10)
    parser.add_argument("--keys", type=int, default=60)
    parser.add_argument("--cols", type=int, default=120)
    parser.add_argument("--deadline-ms", type=float, default=100)
    parser.add_argument("--observe-ms", type=float, default=1000,
                        help="extra observation after the last deadline; late keys still fail")
    parser.add_argument("--startup-seconds", type=float, default=30)
    parser.add_argument("--quit-seconds", type=float, default=1,
                        help="time allowed for Ctrl-C before forced cleanup")
    parser.add_argument("--require-graceful-exit", action=argparse.BooleanOptionalAction, default=True,
                        help="require a normal Ctrl-C exit and terminal restoration (default: true)")
    parser.add_argument("--require-updates", action="store_true")
    parser.add_argument("--require-load-running", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--command", nargs=argparse.REMAINDER,
                        default=[str(REPO / "examples/target/bin/settlement")])
    args = parser.parse_args(argv)
    if not (1 <= args.keys <= 10000 and args.keys + 10 <= args.cols <= 12000):
        parser.error("use 1..10000 keys and keys + 10 <= cols <= 12000; the full input must fit")
    for name in ("rate", "deadline_ms", "startup_seconds", "quit_seconds"):
        value = getattr(args, name)
        if not math.isfinite(value) or value <= 0:
            parser.error(name + " must be finite and positive")
    if not math.isfinite(args.observe_ms) or args.observe_ms < 0:
        parser.error("observe-ms must be finite and nonnegative")
    if (args.keys - 1) / args.rate + (args.deadline_ms + args.observe_ms) / 1000 > 3600:
        parser.error("the input and observation period must not exceed one hour")
    if args.startup_seconds > 300 or args.quit_seconds > 30 or not args.command:
        parser.error("startup-seconds must be <= 300, quit-seconds <= 30 and command must not be empty")
    return args


# codex 2026-10-22: created: errors and missing input effects give a failing exit code.
def main(argv=None):
    args = arguments(argv)
    result = run(args)
    payload = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload)
    print(payload, end="")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
