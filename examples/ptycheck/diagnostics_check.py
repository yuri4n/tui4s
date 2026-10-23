#!/usr/bin/env python3
"""Check the recorder and the external screen report from a quiet PTY run."""
# codex 2026-10-22: created: the CI smoke checks actual input-to-recorder wiring.

import json
from pathlib import Path
import sys


# codex 2026-10-22: created: incomplete input or a failed exporter cannot pass the smoke; codex 2026-10-23: changed: checks also run with Python optimization.
def main():
    diagnostic = json.loads(Path(sys.argv[1]).read_text())
    screen = json.loads(Path(sys.argv[2]).read_text())
    checks = (
        (screen["ok"] and screen["visible_count"] == screen["planned_count"], "screen input failed"),
        (diagnostic["schemaVersion"] == 1, "unsupported schema"),
        (diagnostic["phase"] == "Stopped", "exporter did not stop"),
        (diagnostic["inputReceived"] >= screen["written_count"], "input receipts missing"),
        (diagnostic["inputCompleted"] == diagnostic["inputReceived"], "input incomplete"),
        (diagnostic["sampledCompletedInputs"] == diagnostic["inputCompleted"], "samples missing"),
        (diagnostic["inputPending"] == 0 and diagnostic["untrackedInputs"] == 0, "input coverage incomplete"),
        (diagnostic["writeFailures"] == 0, "snapshot write failed"),
        (diagnostic["completionBoundary"] == "runner_frame_pass_before_warning_log", "wrong timing boundary"),
        (screen["expected_text"] not in json.dumps(diagnostic), "input text leaked"),
    )
    for passed, message in checks:
        if not passed:
            raise SystemExit("diagnostics: " + message)
    print("diagnostics: completed input, stopped exporter, no input text")


if __name__ == "__main__":
    main()
