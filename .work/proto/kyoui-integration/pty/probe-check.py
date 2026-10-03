"""EXTRA: where did the PROBE-* lines of the output probes go? (terminal while running, terminal after, tui4s.log)"""
# claude 2026-10-03: created the check of the output probe
import os
import re
import sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, "..", "..", "kyoui-runner", "pty"))
import vt  # noqa: E402

logs = os.path.join(here, "..", "logs")
snaps = vt.replay(os.path.join(logs, "pty-probe-session.log"))
work = os.environ["PTY_WORK"]
filelog = open(os.path.join(work, "tui4s.log")).read() if os.path.exists(os.path.join(work, "tui4s.log")) else ""
for main, kinds in (("integration.ZioOutputProbeMain", ["PROBE-ZIO-LOGINFO", "PROBE-ZIO-CONSOLE", "PROBE-ZIO-PRINTLN", "PROBE-ZIO-SYSTEMOUT"]),
                    ("integration.CatsOutputProbeMain", ["PROBE-CATS-IOPRINTLN", "PROBE-CATS-PRINTLN", "PROBE-CATS-SYSTEMOUT"]),
                    # claude 2026-10-03: changed: also the early-println variant (the same handler as the ZIO probe)
                    ("integration.ZioOutputProbeEarlyMain", ["PROBE-ZIO-LOGINFO", "PROBE-ZIO-CONSOLE", "PROBE-ZIO-PRINTLN", "PROBE-ZIO-SYSTEMOUT"])):
    by = {name: (seg, alt, in_alt) for name, seg, alt, in_alt in snaps}
    enter_seg, enter_alt, _ = by.get(f"{main}-enter", ("", "", False))
    ctrlc_seg, _, _ = by.get(f"{main}-ctrlc", ("", "", False))
    print(f"== {main}: screen after the press:\n{enter_alt}\n")
    for k in kinds:
        on_term_running = k in enter_seg
        on_term_after = k in ctrlc_seg
        # claude 2026-10-03: changed: count the lines in tui4s.log (the file is shared by the probe mains)
        in_file = filelog.count(k)
        print(f"{k}: on the terminal while the UI runs={on_term_running}, on the terminal after the restore={on_term_after}, in tui4s.log={in_file}")
print("\n== tui4s.log:\n" + filelog)
