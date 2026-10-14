import re, sys
impl = "/home/user/tui4s/.work/impl/"
src = open(impl + "t2-pr-body-fix.txt", encoding="utf-8").read()
body = src

def rep(old, new, count=1):
    global body
    n = body.count(old)
    if n != count:
        sys.exit(f"expected {count} of {old[:80]!r}, found {n}")
    body = body.replace(old, new)

# --- first paragraph
rep("and an INFO line with the class-load count of the real Counter launcher (4087 classes). Base: PR #15",
    "and an INFO line with the class-load count of the real Counter launcher (4085 to 4091 classes in the final runs). The final runs at the tip 4f174ed are green: `SBT_TPOLECAT_CI=1 sbt clean check` with 0 warnings, 3 test runs in a row and 3 PTY runs in a row with all rows, one of each under CPU contention (P-13). Base: PR #15")

# --- P-3 second paragraph
m = re.search(r"\nThis PR adds 20 commits:.*?\n", body)
assert m and body.count("This PR adds 20 commits:") == 1
body = body[:m.start()] + "\n" + (
 "This PR adds 20 commits: 15, then 5 review fixes (\"Review fixes\"); no code change after them. "
 "The final runs at the tip 4f174ed, alone on the machine (4 CPUs, 15 GB, JDK 25.0.4.1; `pgrep -x java` empty before each run; the tree clean): "
 "`SBT_TPOLECAT_CI=1 sbt -Dsbt.supershell=false clean check`: exit 0, 158 s, 0 `[warn]`, 0 `[error]`, core 228 passed, 0 failed, 3 ignored, examples 35 passed (`.work/impl/t2-final-check.log`; the fixer's check at the same tip: the same, 152 s, `t2-fix-check.log`); "
 "`bash scripts/version-order-test.sh`: 55 of 55 (`t2-final-version-order-test.log`); "
 "3 test runs in a row (`sbt core/test examples/test`) and 3 PTY runs in a row (53 of 53 terminal rows and 68 of 68 screen checks in each), run 2 of each under CPU contention: green (P-13, \"Acceptance\"). "
 "Before the review fixes, at 490aea7: `t2-check.log` (227 + 35 passed, 0 warnings), `t2-pty.log` (53 + 65 rows).\n") + body[m.end():]

# --- P-12
rep("- The CI job `terminal` green 3 times, and the 3 final test runs and 3 PTY runs in a row (one of each under CPU contention): the final-runs agent, then the push.",
    "- The CI jobs `sbt` and `terminal` on GitHub (plan 6.6: `terminal` green 3 times): after the push; the local runs are green (P-13).")

# --- P-13
rep("**0 warnings.** `SBT_TPOLECAT_CI=1 sbt -Dsbt.supershell=false clean check` (`-Werror` on) at the tip 4f174ed, alone: exit 0, 152 s, 0 `[warn]`, 0 `[error]` (`.work/impl/t2-fix-check.log`).",
    "**0 warnings.** `SBT_TPOLECAT_CI=1 sbt -Dsbt.supershell=false clean check` (`-Werror` on) at the tip 4f174ed, alone, as the final run: exit 0, 158 s, 0 `[warn]`, 0 `[error]` (`.work/impl/t2-final-check.log`; the fixer's `t2-fix-check.log` has the same result, 152 s).")
rep("`[pending]` 105 ms. Each step compiled",
    "`[pending]` 104 ms (105 ms in the fixer's check). Each step compiled")
rep("The dev-mode compile of the launchers build in the PTY run gives 0 `[warn]` (`t2-fix-pty-files/launchers.log`).",
    "The dev-mode compiles give 0 `[warn]`: the final test run 1 (`t2-final-run-1.log`) and the launchers build of each final PTY run (`t2-final-pty-*-files/launchers.log`).")

final_runs = """
The 3 final runs of `sbt core/test examples/test` in a row at 4f174ed, alone (plan 6.0: the timing-sensitive suites green in 3 local runs, one under CPU contention). `pgrep -x java` was empty before each run; the tree was clean before and after. No run has the load flag, so both glitch leaves assert the strict 0, also under contention.

| Run | Machine | Result | Glitch and pending lines | Log |
| --- | --- | --- | --- | --- |
| 1 | quiet (load 0.09); the dev-mode compile first, 0 `[warn]` | exit 0, 153 s (core 133 s with the compile, examples 12 s); core 228 passed, 0 failed, 3 ignored, 34 suites; examples 35 passed, 8 suites | `[glitch] splits=0 of 500` (1 frame: 500; 0 settle timeouts); `[glitch-keys] splits=0 of 500` (region values 4: 500); `[pending]` 104 ms | `t2-final-run-1.log` |
| 2 | **CPU contention**, the model of the CI runner: `taskset -c 0,1 sbt ...` with two busy loops `taskset -c 0,1 bash -c "while :; do :; done"` (PIDs 24835, 24836; `taskset -p` after the start: mask 3; killed by PID; `ps -p`: both gone) | exit 0, 89 s (core 65 s, examples 9 s); core 227 passed, 0 failed, 3 ignored, 1 cancelled: the SettleTest canary cancels itself, by design, as in T1 and C4 ("Problems"); examples 35 passed (DashboardSuite 2.3 s; 1.6 s alone) | `[glitch] splits=0 of 500`; `[glitch-keys] splits=0 of 500` (region values 4: 497, 5: 3; the leaf accepts 5: an intermediate value of `combineLatest`); `[pending]` 109 ms | `t2-final-run-contention.log` |
| 3 | quiet (load 2.82: the lag of run 2) | exit 0, 57 s (core 44 s, examples 4 s); core 228 passed, 0 failed, 3 ignored; examples 35 passed | `[glitch] splits=0 of 500`; `[glitch-keys] splits=0 of 500` (4: 500); `[pending]` 104 ms | `t2-final-run-3.log` |

The 3 final PTY runs in a row at 4f174ed, each with its own launchers build (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PTY_WORK=.work/impl/t2-final-pty-<n>-files examples/ptycheck/run.sh`; `pgrep -x java` empty before each; the tree clean after each; plan 6.6: the CI job `terminal` green 3 times, here its local model). The rows: "Acceptance".

| Run | Machine | Result | First frame (ms, JVM start included) | Class-load INFO | Log |
| --- | --- | --- | --- | --- | --- |
| 1 | quiet | 53 of 53 terminal rows, 68 of 68 screen checks, `ALL ROWS PASSED`, exit 0, 71 s; 26 bytes per key | 1028 to 1306 | 4090 classes | `t2-final-pty-1.log` |
| 2 | **CPU contention**: `taskset -c 0,1 examples/ptycheck/run.sh` with two busy loops (PIDs 26712, 26713; mask 3; killed by PID, gone); sbt, expect and the apps get the same mask | 53 of 53, 68 of 68, `ALL ROWS PASSED`, exit 0, 96 s; 26 bytes per key | 1569 to 2305 | 4085 classes | `t2-final-pty-contention.log` |
| 3 | quiet | 53 of 53, 68 of 68, `ALL ROWS PASSED`, exit 0, 70 s; 26 bytes per key | 1016 to 1229 | 4091 classes | `t2-final-pty-3.log` |

The class-load count is of the real Counter launcher (start, first frame, q). Its parts are the same in the 3 runs: JDK 1672, Scala 535, Kyo 905 (kyo-ui 206, kyo-http 0, kyo-net 0), jatatui 37, tui4s core 243, examples 5. Only the lambda and `LambdaForm` classes that the JVM generates change (679 to 685 lines). The fixer's run: 4087, tui4s core 244 (`t2-fix-pty.log`).
"""
rep("\n## P-14 New sbt projects", final_runs + "\n## P-14 New sbt projects")

# --- Problems
rep("(`t2-fix-pty.log`; 243 at 490aea7). So no class",
    "(`t2-fix-pty.log`; 243 at 490aea7; in the 3 final runs 4090, 4085 and 4091, tui4s core 243: P-13). So no class")
rep("(`t2-fix-pty.log`: 1022 to 1158 ms).",
    "(`t2-fix-pty.log`: 1022 to 1158 ms; the final runs: 1016 to 1306 ms on a quiet machine, 1569 to 2305 ms under CPU contention).")
rep("\n## Questions for the user",
    "- The final runs: in the contention run of the tests (run 2) the SettleTest canary cancels itself, as in the contention runs of T1 and C4. The canary needs a second Kyo worker while a CPU-bound fiber spins; a JVM on 2 CPUs under load has only one. It is a check of the harness, not of the runner, and it cancels; it does not fail. Its two other leaves pass.\n"
    "\n## Questions for the user")

# --- Acceptance
rep("- Plan 6.0: `sbt check` green, alone, CI mode, 0 warnings: yes (P-13). The timing-sensitive suites in 3 local runs (one under CPU contention): the final-runs agent. `compat.sbt`: no change.",
    "- Plan 6.0: `sbt check` green, alone, CI mode, 0 warnings: yes (P-13, `t2-final-check.log`; also the fixer's `t2-fix-check.log`). The timing-sensitive suites green in 3 local runs in a row, one under CPU contention: yes, 3 of 3 (P-13; run 2 is the contention run, with the strict 0 of the glitch leaves; the SettleTest canary cancels itself there by design). The reviewers' findings: fixed or answered (\"Review fixes\"). `compat.sbt`: no change; the final check prints `Compatibility.None` for the three modules. The CI job `sbt`: after the push.")
rep("- From T1 on: the CI job `terminal` (`examples/ptycheck/run.sh`): green locally (53 + 68 rows, `t2-fix-pty.log`); the 3 PTY runs with one under contention: the final-runs agent; the CI runs: after the push.",
    "- From T1 on: the CI job `terminal` (`examples/ptycheck/run.sh`): green locally 3 times in a row with all rows (53 + 68 in each run), one run under CPU contention (P-13; also the fixer's `t2-fix-pty.log`). The CI runs: after the push.")
rep("one `-Xlog:class+load` count of the real Counter launcher: 4087 classes (INFO line of every kit run).",
    "one `-Xlog:class+load` count of the real Counter launcher: 4090, 4085 and 4091 classes in the 3 final runs (the INFO line of every kit run; P-13).")

# the PTY rows: the full list of the final run 1
log = open(impl + "t2-final-pty-1.log", encoding="utf-8").read().splitlines()
rows = [l for l in log if l.startswith(("PASS", "FAIL"))]
assert len(rows) == 121 and all(r.startswith("PASS") for r in rows), len(rows)
term, screen = rows[:53], rows[53:]
info = [l for l in log if l.startswith("INFO class-load (")]
def short(l):
    l = l.replace(" ms after the start command, JVM start included)", " ms)")
    return l
ab = [(" ms after the start command, JVM start included)", " ms)"),
      ("stty -a after the exit equals stty -a before the start", "stty same"),
      ("leaves the alternate screen and shows the cursor", "restored"),
      ("nothing stray after the restore (the next output is ", "nothing stray (next: "),
      ("entered the alternate screen", "alt screen"), ("first frame drawn (", "first frame (")]
def abbr(t):
    for o, n in ab:
        t = t.replace(o, n)
    return t
def group(rs):
    out = []
    for r in rs:
        name, _, text = r[5:].partition(": ")
        if out and out[-1][0] == name:
            out[-1][1].append(abbr(text))
        else:
            out.append((name, [abbr(text)]))
    return "\n".join(f"- `{n}`: " + "; ".join(t) for n, t in out)
rows_block = ("**The PTY rows** of the final run 1 (`t2-final-pty-1.log`; 71 s; `t2-final-pty-1-files/` has `session.log`, `screens.txt`, the stty files, `todo-pty.txt`, `printing.log`, `classload.log`, `launchers.log`). "
  "The full list, in the order of the output, grouped by row name. Short forms: \"stty same\" = \"stty -a after the exit equals stty -a before the start\"; \"restored\" = \"leaves the alternate screen and shows the cursor\"; "
  "\"nothing stray (next: EXIT=n)\" = \"nothing stray after the restore (the next output is EXIT=n)\"; \"alt screen\" = \"entered the alternate screen\"; the first frame is in ms after the start command. Runs 2 and 3: the same rows, all PASS.\n\n"
  "Terminal part (expect, `checklist.exp`): 53 passed, 0 failed.\n\n" + group(term) + "\n\n"
  "Screen part (vt.py, `check.py`): 68 passed, 0 failed.\n\n" + group(screen) + "\n\n"
  "Totals: 53 + 68, `ALL ROWS PASSED` (at 490aea7: 53 + 65, `t2-pty.log`; review fix R1-1 adds 3 screen checks and changes one).\n")
m = re.search(r"\*\*The PTY rows\*\* \(`t2-fix-pty\.log`.*?\nTotals: 53 of 53 terminal rows, 68 of 68 screen checks, `ALL ROWS PASSED` \(at 490aea7: 53 and 65, `t2-pty\.log`; the review fix R1-1 adds 3 screen checks and changes one\)\.\n", body, re.S)
assert m
body = body[:m.start()] + rows_block + body[m.end():]

# the grep checks
g = open(impl + "t2-final-greps.log", encoding="utf-8").read().splitlines()
i0 = g.index('$ grep -rn "System.err\\|println\\|System.out" modules/core/src/main')
hits = []
for l in g[i0 + 1:]:
    if l.startswith("== hits"):
        break
    hits.append(l)
assert len(hits) == 41, len(hits)
P = "modules/core/src/main/scala/tui4s/"
marked = [re.sub(r"^([^:]*:[^:]*:)\s+", r"\1 ", h.replace(P, "")) for h in hits if "tui4s: allowed" in h]
comments = [h for h in hits if "tui4s: allowed" not in h]
assert len(marked) == 17 and len(comments) == 24
from collections import OrderedDict
byfile = OrderedDict()
for h in comments:
    f, ln = h.split(":")[0].replace(P, ""), h.split(":")[1]
    byfile.setdefault(f, []).append(ln)
clist = "; ".join(f"`{f}` {', '.join(v)}" for f, v in byfile.items())
greps_block = ("**The grep checks** at the tip 4f174ed (`.work/impl/t2-final-greps.log`; the fixer's at the same tip: `t2-fix-greps.log`; at 490aea7: `t2-greps.log`)."
  "\n\n"
  "- `grep -rn \"System.err\\|println\\|System.out\" modules/core/src/main`: 41 hits = 17 code lines with the marker (T1's 14 and `TuiApp.scala` 195, 201, 203) + 24 comment or Scaladoc lines; 0 other lines. The 17 code lines (paths under `" + P + "`):\n\n```\n" + "\n".join(marked) + "\n```\n\n"
  "The 24 comment or Scaladoc lines are in `TuiApp.scala` (1), `TuiOptions.scala` (5), `Tui.scala` (1), `internal/OutputCapture.scala` (14), `internal/TerminalIO.scala` (2), `internal/LogSink.scala` (1); the list: `t2-final-greps.log`.\n"
  "- `grep -rn jatatui.widgets modules/core`: 0 hits (`examples/src`: 0). `grep -rn \"getDeclaredField\\|setAccessible\" modules/core/src/main`: 0 hits.\n"
  "- The private[kyo] checks as C3 did them (`c3-private-kyo-check.log`): 1. no source in package `kyo` (`^package kyo` in `modules` and `examples/src`): 0; 2. no `private[kyo]` in `modules/core/src/main` and `examples/src`: 0; 3. no reflection API: 0; "
  "4. the names of the private[kyo] members of kyo-ui RC7: only the hits of C2 and C4 (`Component.scala` 94, 95: kyo-ui's public `HtmlChildVal.lift`; `Props.scala` 172, 174: a type test of `Style.Color.Rgb` and its public fields; 184, 192: jatatui's `JColor.Rgb`; `Mount.scala` 242: a comment), none in a T2 file and none in the diff of this PR; "
  "5. the compiler enforces it: a throwaway file in `tui4s.internal` that calls `Element.withAttrs` fails with E173 \"private[kyo] method withAttrs can only be accessed from package kyo\"; the file removed, the tree clean.\n"
  "- TODO: the 4 of C2 (`Runner.scala` 105, 258, 286; `Mount.scala` 85: #8, #9); `Todo.scala` 223 is the word `TODO_FILE` in a comment.\n")
m = re.search(r"\*\*The grep checks\*\* \(`t2-fix-greps\.log` at 4f174ed.*?TODO: the 4 of C2 \(#8, #9\)\.\n", body, re.S)
assert m
body = body[:m.start()] + greps_block + body[m.end():]

# logs: the whole section, compact
m = re.search(r"## Logs \(`\.work/impl/`\)\n\n.*?\n\n(?=🤖)", body, re.S)
assert m
body = body[:m.start()] + ("## Logs (`.work/impl/`)\n\n"
  "`t2-notes.txt`. The steps (P-7): `t2-carry-verify.py`, `t2-carry-verify.log`, `t2-step2-fmt.log`, `t2-step3-warnings.log`, `t2-step4a-test-1.log`, `t2-step4a-test-2.log`, `t2-step4b-test.log`, `t2-step4c-counter-test.log`, `t2-step4c-test-1.log`, `t2-step4e-docs.log`, `t2-marker-fix-test.log`. "
  "The negative controls: `t2-stophook-negative-control.log`, `t2-shuttingdown-negative-control.log`, `t2-schedulerdefaults-negative-control.log`, `t2-pty-negative-control.log`. "
  "The PTY tries: `t2-pty-counter.log`, `t2-pty-rows-try-1.log`, `t2-pty-rows-try-2.log`, `t2-pty.log` (with `t2-pty-counter-files/`, `t2-pty-rows-files/`, `t2-pty-files/`). The check at 490aea7: `t2-check.log`, `t2-greps.log`, `t2-version-order-test.log`. "
  "The review fixes: `t2-fix-suites-1.log`, `t2-fix-suites-2.log`, `t2-fix-negative-controls.log`, `t2-fix-pty.log` (`t2-fix-pty-files/`), `t2-fix-pty-negative-control.log` (`t2-fix-pty-nc-files/`), `t2-fix-check.log`, `t2-fix-version-order-test.log`, `t2-fix-greps.log`; the skeptic K2: `t2-k2-check.log`, `t2-k2-DashboardSuite.log`, `t2-k2-pty.log` (`t2-k2-pty-files/`). "
  "The final runs: `t2-final-run-1.log`, `t2-final-run-contention.log`, `t2-final-run-3.log`, `t2-final-pty-1.log`, `t2-final-pty-contention.log`, `t2-final-pty-3.log` (each with its `-files/` directory), `t2-final-greps.log`, `t2-final-check.log`, `t2-final-version-order-test.log`. "
  "This body: `t2-pr-body.txt` (the fixer's body: `t2-pr-body-fix.txt`; at 490aea7: `t2-pr-body-490aea7.txt`).\n\n") + body[m.end():]

# problems: two duplicates
m = re.search(r"- The carried `SchedulerDefaults` Scaladoc named \"SPEC 5\.14\".*?\n", body)
assert m
body = body[:m.start()] + "- The carried `SchedulerDefaults` Scaladoc named \"SPEC 5.14\" (not in the repository) and the facades' `TuiApp` classes (none yet): review R1-5, commit 4f174ed, the Scaladoc only; the code stays as carried (RC-25). The commit is alone, so that it can be reverted (\"Review fixes\", \"Questions for the user\"). The test's Scaladoc names no SPEC either.\n" + body[m.end():]
m = re.search(r"- The DashboardSuite pause leaf \(review R1-2\) waits for the sparkline row TEXT.*?\n", body)
assert m
body = body[:m.start()] + body[m.end():]

# P-8: the carried rows with the delta command stated once (the same information, shorter)
old_head = "| New file | Source | Delta command |\n| --- | --- | --- |\n"
i = body.index(old_head)
j = body.index("| `examples/src/main/scala/examples/counter/Counter.scala` (changed, T1's file)", i)
new_rows = ("For the first nine rows the delta command is `git diff <source> 4f174ed:<new file>`.\n\n"
  "| New file | Source | Delta command |\n| --- | --- | --- |\n"
  "| `modules/core/src/main/scala/tui4s/TuiApp.scala` | `666a8e3:` the same path | `git diff <source> 4f174ed:<new file>` (+127/-57) |\n"
  "| `modules/core/src/main/scala/tui4s/spi/SchedulerDefaults.scala` | `666a8e3:` the same path | the same (+7/-3: the Scaladoc only, review R1-5 in 4f174ed; the code is unchanged: `git diff <source> 490aea7:<new file>` is empty) |\n"
  "| `modules/core/src/test/scala/tui4s/TuiAppTest.scala` | `666a8e3:` the same path | the same (+380/-157) |\n"
  "| `modules/core/src/test/scala/tui4s/SchedulerDefaultsTest.scala` | `666a8e3:` the same path | the same (+72/-7) |\n"
  "| `examples/src/main/scala/examples/twocounters/TwoCounters.scala` | `1d100ed:.work/proto/kyoui-runner/apps/Counter.scala` (the `counter` and `twoCounters` part) | the same |\n"
  "| `examples/src/main/scala/examples/counterstyles/CounterStyles.scala` | `1d100ed:.work/proto/kyoui-runner/apps/CounterStyles.scala` | the same |\n"
  "| `examples/src/main/scala/examples/dashboard/Dashboard.scala` | `1d100ed:.work/proto/kyoui-runner/apps/Dashboard.scala` | the same |\n"
  "| `examples/src/main/scala/examples/todo/Todo.scala` | `1d100ed:.work/proto/kyoui-runner/apps/Todo.scala` | the same |\n"
  "| `examples/src/main/scala/examples/widgets/Widgets.scala` | `1d100ed:.work/proto/kyoui-runner/apps/Widgets.scala` | the same |\n")
# check that the old rows hold exactly these 9 files
old = body[i:j]
for f in ["TuiApp.scala` | `666a8e3:modules/core/src/main/scala/tui4s/TuiApp.scala`", "SchedulerDefaults.scala` | `666a8e3:modules/core/src/main/scala/tui4s/spi/SchedulerDefaults.scala`", "(+127/-57)", "(+380/-157)", "(+72/-7)", "(+7/-3: the Scaladoc only", "Widgets.scala 4f174ed:examples/src/main/scala/examples/widgets/Widgets.scala`"]:
    assert f in old, f
assert old.count("\n") == 11, old.count("\n")
body = body[:i] + new_rows + body[j:]

rep("version order 55 of 55.\n\n## Problems", "version order 55 of 55. The final runs: P-13.\n\n## Problems")
rep("Under contention: the final-runs agent. |", "Under contention: green in the final run 2 (P-13). |")
open(sys.argv[1], "w", encoding="utf-8").write(body)
print(len(body.encode("utf-8")), "bytes")
