"""Validate the observer with real PTY processes and deliberate input faults.

Run: python3 -m unittest discover -s examples/ptycheck -p latency_test.py
"""
# codex 2026-10-22: created: measurement tests that fail on false success.

from contextlib import redirect_stderr
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import latency

HERE = Path(__file__).resolve().parent


# codex 2026-10-22: created: verify the external metric, failure counts and watchdog.
class LatencyTest(unittest.TestCase):
    # codex 2026-10-22: created: a finite scenario with six independently sent keys.
    def scenario(self, mode="normal", deadline=1000, **options):
        args = latency.arguments([
            "--keys", "6", "--rate", "30", "--deadline-ms", str(deadline),
            "--observe-ms", "1500", "--startup-seconds", "3",
            "--command", sys.executable, str(HERE / "latency_fixture.py"), "--mode", mode,
        ])
        for name, value in options.items():
            setattr(args, name, value)
        return latency.run(args)

    # codex 2026-10-22: created: both input and status must progress in a healthy app.
    def test_healthy_input_with_updates(self):
        result = self.scenario(require_updates=True, require_load_running=True)
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["visible_count"], 6)
        self.assertTrue(result["final_text_matches"])
        self.assertTrue(result["observed_updates_bracket_input"])
        self.assertEqual(result["deadline_count"], 0)

    # codex 2026-10-22: created: a known delay appears in the metric and fails its gate.
    def test_delay_is_measured_without_throttling_the_sender(self):
        result = self.scenario("delay", deadline=80)
        self.assertFalse(result["ok"])
        self.assertEqual(result["missing_count"], 0, result)
        self.assertEqual(result["deadline_count"], 6, result)
        self.assertGreaterEqual(result["send_to_visible_ms"]["p50"], 200)
        samples = result["samples"]
        self.assertLess(samples[-1]["attempted_ms"], samples[0]["visible_ms"], result)

    # codex 2026-10-22: created: a dropped middle key cannot be hidden by status traffic.
    def test_dropped_key_fails(self):
        result = self.scenario("drop")
        self.assertFalse(result["ok"])
        self.assertGreater(result["missing_count"], 0)
        self.assertFalse(result["final_text_matches"])
        self.assertGreater(result["observed_update_changes"], 0)

    # codex 2026-10-22: created: output with the wrong character is not an acknowledgement.
    def test_wrong_text_fails(self):
        result = self.scenario("wrong")
        self.assertFalse(result["ok"])
        self.assertGreater(result["incorrect_count"], 0)
        self.assertGreater(result["missing_count"], 0)

    # codex 2026-10-22: created: an attempted but blocked write cannot acknowledge invented output.
    def test_early_expected_text_cannot_acknowledge_an_unsent_key(self):
        observation = latency.Observation("ab")
        observation.started = 10
        observation.samples = [latency.Sample(10, attempted=11, write_started=12, written=13),
                               latency.Sample(20, attempted=21)]
        session = SimpleNamespace(lines=lambda: ["Input: ab", "Updates: 1", "Load: running"])
        observation.screen(22, session)
        self.assertEqual(observation.incorrect, 1)
        self.assertTrue(all(s.visible is None for s in observation.samples))

    # codex 2026-10-22: created: output read before the write stays invalid if lock acquisition is delayed.
    def test_output_timestamp_before_write_cannot_acknowledge_it(self):
        observation = latency.Observation("a")
        observation.started = 10
        observation.samples = [latency.Sample(10, attempted=11, write_started=20, written=21)]
        session = SimpleNamespace(lines=lambda: ["Input: a", "Updates: 1", "Load: running"])
        observation.screen(15, session)
        self.assertIsNone(observation.samples[0].visible)

    # codex 2026-10-22: created: a correct earlier frame cannot conceal a missing final input field.
    def test_final_input_is_read_from_the_current_screen(self):
        observation = latency.Observation("a")
        observation.started = 10
        observation.samples = [latency.Sample(10, attempted=11, write_started=12, written=13)]
        observation.screen(20, SimpleNamespace(lines=lambda: ["Input: a", "Updates: 1"]))
        observation.screen(30, SimpleNamespace(lines=lambda: ["Updates: 2"]))
        args = latency.arguments(["--keys", "1"])
        result = latency.report(observation, args, [], 30)
        self.assertEqual(result["visible_count"], 1)
        self.assertFalse(result["final_text_matches"])
        self.assertFalse(result["ok"])

    # codex 2026-10-22: created: one correct frame may cover several sent keys.
    def test_batched_correct_keys_are_all_observed(self):
        result = self.scenario("batch")
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["visible_count"], 6)
        self.assertEqual(len({s["visible_ms"] for s in result["samples"]}), 1)

    # codex 2026-10-22: created: arbitrary output cannot satisfy any input predicate.
    def test_busy_status_without_input_hits_external_watchdog(self):
        begin = time.monotonic()
        result = self.scenario("hung", deadline=50)
        self.assertFalse(result["ok"])
        self.assertEqual(result["visible_count"], 0)
        self.assertEqual(result["missing_count"], 6)
        self.assertGreater(result["observed_update_changes"], 0)
        self.assertLess(time.monotonic() - begin, 5)

    # codex 2026-10-22: created: startup failure is bounded even when the child ignores TERM.
    def test_startup_watchdog(self):
        begin = time.monotonic()
        result = self.scenario("no-ready", startup_seconds=.15)
        self.assertFalse(result["ok"])
        self.assertEqual(result["attempted_count"], 0)
        self.assertEqual(result["missing_count"], 6)
        self.assertLess(time.monotonic() - begin, 5)

    # codex 2026-10-22: created: reject scenarios whose text would scroll out of the oracle.
    def test_reject_clipped_input(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            latency.arguments(["--cols", "20", "--keys", "15"])

    # codex 2026-10-22: created: a missing launcher remains a reportable failure.
    def test_missing_launcher(self):
        args = latency.arguments(["--keys", "6", "--command", "/no-such-latency-launcher"])
        result = latency.run(args)
        self.assertFalse(result["ok"])
        self.assertEqual(result["missing_count"], 6)
        self.assertIn("probe setup:", result["failures"][0])

    # codex 2026-10-22: created: graceful and forced cleanup need no /proc and close the PTY reader.
    def test_cleanup_without_proc_filesystem(self):
        for ignore_quit in (False, True):
            with self.subTest(ignore_quit=ignore_quit), tempfile.TemporaryDirectory() as directory:
                exit_file = Path(directory) / "exit.txt"
                command = [sys.executable, str(HERE / "latency_fixture.py"), "--exit-file", str(exit_file)]
                if ignore_quit:
                    command.append("--ignore-quit")
                observation = latency.Observation("")
                session = latency.ProbeSession(command, observation, 120)
                try:
                    deadline = time.monotonic() + 3
                    with session.lock:
                        while not observation.ready and time.monotonic() < deadline:
                            session.lock.wait(.02)
                    self.assertTrue(observation.ready)
                    begin = time.monotonic()
                    with patch.object(session, "tree", side_effect=AssertionError("/proc tree")), \
                            patch("term.session_members", side_effect=AssertionError("/proc sessions")):
                        session.close()
                    self.assertLess(time.monotonic() - begin, 4)
                    self.assertEqual(session.proc.returncode, -signal.SIGKILL if ignore_quit else 0)
                    self.assertFalse(session.reader.is_alive())
                    self.assertEqual(exit_file.exists(), not ignore_quit)
                    for fd in (session.master, session.slave):
                        with self.assertRaises(OSError):
                            os.fstat(fd)
                finally:
                    session.close()

    # codex 2026-10-22: created: a failed probe stays failed in its JSON and process exit.
    def test_cli_preserves_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            completed = subprocess.run([
                sys.executable, str(HERE / "latency.py"), "--keys", "6", "--rate", "30",
                "--deadline-ms", "50", "--observe-ms", "100", "--output", str(output),
                "--command", sys.executable, str(HERE / "latency_fixture.py"), "--mode", "hung",
            ], capture_output=True, text=True, timeout=5)
            self.assertEqual(completed.returncode, 1, completed.stderr)
            result = json.loads(output.read_text())
            self.assertEqual(result, json.loads(completed.stdout))
            self.assertEqual(result["missing_count"], 6)


if __name__ == "__main__":
    unittest.main()
