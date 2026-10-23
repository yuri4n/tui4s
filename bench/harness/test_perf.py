"""Performance accounting regression tests; no terminal process or real timing is required."""
# codex 2026-10-22: created: exercise unanswered keys, partial summaries and the performance gate

import argparse
import os
import tempfile
import unittest
from unittest.mock import patch

import bench
import report


# codex 2026-10-22: created: simulate visible responses and the Session deadline contract
class FakeSession:
    # codex 2026-10-22: created: response delays beyond the deadline never become successful samples
    def __init__(self, delays, first_frame=True):
        self.delays = iter(delays)
        self.first_frame = first_frame
        self.t0 = 0
        self.sent = []
        self.closed = False

    # codex 2026-10-22: created: deterministic first paint or startup failure
    def expect(self, predicate, timeout):
        return 1_000_000 if self.first_frame else None

    # codex 2026-10-22: created: predicates are opaque to the transport fake
    def arm(self, predicate):
        return predicate

    # codex 2026-10-22: created: retain the exact number of attempted writes
    def send(self, data):
        self.sent.append(data)
        return 10_000_000

    # codex 2026-10-22: created: model no response and responses too late for the measurement
    def await_armed(self, predicate, timeout):
        delay = next(self.delays)
        return None if delay is None or delay > timeout else 10_000_000 + int(delay * 1e9)

    # codex 2026-10-22: created: idle resources are deterministic and irrelevant to completion
    def cpu_ticks(self):
        return 0

    # codex 2026-10-22: created: avoid querying a real process
    def rss_kb(self):
        return 1024

    # codex 2026-10-22: created: verify cleanup also occurs after missing responses
    def close(self):
        self.closed = True


# codex 2026-10-22: created: compare complete runs with plausible-looking partial latency results
class PerfTests(unittest.TestCase):
    # codex 2026-10-22: created: run the actual sampler without sleeping or creating a PTY
    def sample(self, delays, keys=3, first_frame=True):
        terminal = FakeSession(delays, first_frame)
        with patch.object(bench, "session", return_value=terminal), \
                patch.object(bench.time, "sleep"), \
                patch.object(bench.time, "monotonic", side_effect=[1.0, 2.0]), \
                patch.object(bench.oracle, "quit_and_restore", return_value=(0, {})), \
                patch.object(bench.oracle, "screen_dump", return_value="blank"):
            result = bench.perf_once("tui4s-aot", "counter", keys, 1)
        self.assertTrue(terminal.closed)
        self.assertEqual(result["keys_attempted"], len(terminal.sent))
        return result

    # codex 2026-10-22: created: exercise the persisted summary and command status
    def aggregate(self, run):
        args = argparse.Namespace(stacks=["tui4s-aot"], tasks=["counter"], runs=1, keys=3, idle=1)
        with patch.object(bench, "perf_once", return_value=run), \
                patch.object(bench.st, "artifact", return_value=(1000, "fake")), \
                patch.object(bench.time, "sleep"), patch.object(bench, "log"), \
                patch.object(bench, "update") as update:
            status = bench.perf(args)
        return status, update.call_args.args[2]

    # codex 2026-10-22: created: isolate performance from already-successful conformance checks
    def gate(self, summary):
        args = argparse.Namespace(stacks=["tui4s-aot"], tasks=["counter"])
        data = {"conformance": {"steps": []}, "resilience": {}, "change": {"steps": []}}
        if summary is not None:
            data["perf"] = summary
        with patch.object(bench, "load_json", return_value=data), patch.object(bench, "log"):
            return bench.check(args)

    # codex 2026-10-22: created: a failed key must survive both aggregation and the CI gate
    def test_missing_or_delayed_response_after_successes_fails(self):
        for delay in (None, 2.5):
            with self.subTest(delay=delay):
                run = self.sample([0.001, 0.002, delay])
                self.assertEqual({key: run[key] for key in bench.PERF_COUNTS}, {
                    "keys_planned": 4, "keys_attempted": 3, "keys_completed": 2,
                    "keys_missing": 1, "keys_unattempted": 1,
                })
                self.assertEqual(run["key_median_ms"], 2.0)
                status, summary = self.aggregate(run)
                self.assertEqual(status, 1)
                self.assertEqual(summary["runs_failed"], 1)
                self.assertGreater(summary["error_count"], 0)
                self.assertTrue(any("key 3: no effect" in error for error in summary["errors"]))
                self.assertEqual(summary["key_median_ms"], 2.0)
                self.assertEqual(summary["keys_missing"], 1)
                self.assertEqual(self.gate(summary), 1)
                self.assertIn("FAIL", report.perf_result(summary))
                self.assertEqual(report.perf_key_counts(summary), "2/4")

    # codex 2026-10-22: created: include the separate first key while preserving warm latency samples
    def test_complete_run_counts_first_key_and_passes(self):
        run = self.sample([0.001, 0.002, 0.003, 0.004])
        status, summary = self.aggregate(run)
        self.assertEqual(status, 0)
        self.assertEqual(summary["keys_completed"], 4)
        self.assertEqual(summary["keys_missing"], 0)
        self.assertEqual(summary["keys_unattempted"], 0)
        self.assertEqual(summary["first_key_ms"], 1.0)
        self.assertEqual(summary["key_median_ms"], 3.0)
        self.assertEqual(summary["errors"], [])
        self.assertEqual(self.gate(summary), 0)
        self.assertEqual(report.perf_result(summary), "complete")

    # codex 2026-10-22: created: startup failure keeps its planned workload visible
    def test_first_frame_failure_counts_unattempted_keys(self):
        run = self.sample([], first_frame=False)
        self.assertEqual(run["keys_attempted"], 0)
        self.assertEqual(run["keys_completed"], 0)
        self.assertEqual(run["keys_unattempted"], 4)
        self.assertIn("no first frame", run["error"])
        status, summary = self.aggregate(run)
        self.assertEqual(status, 1)
        self.assertEqual(self.gate(summary), 1)

    # codex 2026-10-22: created: older data remains readable but cannot pass the gate without completion evidence
    def test_historical_results_are_unknown_or_failed(self):
        old = {"runs": [{"key_median_ms": 1.2, "exit": 0}], "key_median_ms": 1.2}
        self.assertEqual(self.gate(old), 1)
        self.assertEqual(report.perf_result(old), "completion unknown")
        self.assertEqual(report.perf_key_counts(old), "unknown")
        old["runs"][0]["key_error"] = "key 3: no effect in 2 s"
        self.assertEqual(self.gate(old), 1)
        self.assertIn("key 3", report.perf_result(old))

    # codex 2026-10-22: created: operational and programming failures cannot leave stale healthy measurements
    def test_exception_invalidates_previous_performance_result(self):
        _, healthy = self.aggregate(self.sample([0.001, 0.002, 0.003, 0.004]))
        args = argparse.Namespace(stacks=["tui4s-aot"], tasks=["counter"], runs=1, keys=3, idle=1)
        for failure in ("construction", "send", "programming"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory, \
                    patch.object(bench, "RESULTS", directory), patch.object(bench.time, "sleep"), \
                    patch.object(bench, "log"):
                path = bench.result_path("tui4s-aot", "counter")
                bench.update(path, "perf", healthy)
                bench.update(path, "conformance", {"steps": []})
                bench.update(path, "resilience", {})
                bench.update(path, "change", {"steps": []})
                self.assertEqual(bench.check(args), 0)
                terminal = FakeSession([0.001, 0.002])
                exception = RuntimeError("unexpected bug") if failure == "programming" else OSError("transport failed")
                with patch.object(bench, "session", return_value=terminal,
                                  side_effect=exception if failure == "construction" else None), \
                        patch.object(terminal, "send", side_effect=[10_000_000, 10_000_000, exception]):
                    with self.assertRaises(type(exception)):
                        bench.perf(args)
                current = bench.load_json(path)
                self.assertIn("did not finish", current["perf"]["error"])
                self.assertNotIn("key_median_ms", current["perf"])
                self.assertEqual(current["perf"]["keys_planned"], 4)
                self.assertEqual(current["conformance"], {"steps": []})
                self.assertEqual(bench.check(args), 1)
                self.assertEqual(os.listdir(directory), ["tui4s-aot-counter.json"])
                if failure != "construction":
                    self.assertTrue(terminal.closed)

    # codex 2026-10-22: created: count evidence gates failures even without a descriptive error
    def test_incomplete_or_missing_results_fail_without_key_error(self):
        for summary in (None, {"runs": []}, {"runs": [{}], "runs_planned": 2},
                        {"runs": [{"keys_planned": 4, "keys_completed": 2}]},
                        {"runs": [{"keys_missing": 1}]}, {"error_count": 1}):
            with self.subTest(summary=summary):
                self.assertEqual(self.gate(summary), 1)

    # codex 2026-10-22: created: a good run cannot erase an earlier run's failure
    def test_mixed_runs_keep_failure(self):
        failed = self.sample([0.001, 0.002, None])
        complete = self.sample([0.001, 0.002, 0.003, 0.004])
        self.assertTrue(bench.perf_errors({"runs": [failed, complete]}))

    # codex 2026-10-22: created: the generated performance table includes the verdict and coverage
    def test_report_table_exposes_failures(self):
        _, summary = self.aggregate(self.sample([0.001, 0.002, None]))
        with patch.object(report.st, "ORDER", ["tui4s-aot"]), patch.object(report.st, "MODES", []), \
                patch.object(report.st, "TASKS", ["counter"]), patch.object(report, "concepts", return_value={}), \
                patch.object(report, "load", side_effect=lambda path: {"perf": summary}
                             if path.endswith("tui4s-aot-counter.json") else {}):
            table = report.blocks("fake")["perf-counter"]
        self.assertIn("keys completed/planned", table)
        self.assertIn("FAIL", table)
        self.assertIn("2/4", table)

    # codex 2026-10-22: created: script entry point preserves the measurement command's failure
    def test_main_propagates_perf_status(self):
        with patch.object(bench.sys, "argv", ["bench.py", "perf"]), \
                patch.object(bench, "machine"), patch.object(bench, "perf", return_value=1):
            self.assertEqual(bench.main(), 1)


if __name__ == "__main__":
    unittest.main()
