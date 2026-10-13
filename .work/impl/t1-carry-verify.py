#!/usr/bin/env python3
# claude 2026-10-13: created (T1 carry check, after c2-carry-verify.py). Each carried file must be byte-identical to
# its source blob, except InputPort.scala, whose only change is the package line (tui4s.spi -> tui4s.internal).
# Usage: t1-carry-verify.py <worktree>
import subprocess, sys
wt = sys.argv[1]
def show(ref, path):
    return subprocess.run(['git', '-C', wt, 'show', f'{ref}:{path}'], capture_output=True, check=True).stdout
def mode(ref, path):
    out = subprocess.run(['git', '-C', wt, 'ls-tree', ref, path], capture_output=True, text=True, check=True).stdout
    return out.split()[0] if out else '-'
M = 'modules/core/src/main/scala/tui4s'
T = 'modules/core/src/test/scala/tui4s'
PR5 = '666a8e3'
PROTO = '1d100ed'
rows = []  # (new path, source ref, source path)
for f in ['TerminalIO', 'Crossterm', 'Session', 'EventMapping', 'OutputCapture', 'LogSink', 'TuiLog']:
    rows.append((f'{M}/internal/{f}.scala', PR5, f'{M}/internal/{f}.scala'))
for f in ['TerminalIOTest', 'SessionTest', 'EventMappingTest', 'OutputCaptureTest', 'NativeTerminalTest', 'FakeCrossterm']:
    rows.append((f'{T}/{f}.scala', PR5, f'{T}/{f}.scala'))
for f in ['RecordingControl', 'ScriptedInput']:
    rows.append((f'{T}/testkit/{f}.scala', PR5, f'{M}/testkit/{f}.scala'))
for f in ['vt.py', 'check.py', 'checklist.exp', 'run.sh']:
    rows.append((f'examples/ptycheck/{f}', PROTO, f'.work/proto/kyoui-runner/pty/{f}'))
rows.append(('examples/README.md', PR5, 'examples/README.md'))
bad = 0
total_lines = 0
for new, ref, src in rows:
    want = show(ref, src)
    got = open(f'{wt}/{new}', 'rb').read()
    same = want == got
    n = got.count(b'\n')
    total_lines += n
    marks = got.decode('utf-8').count('claude 20')
    print(f"{'OK  ' if same else 'DIFF'} {new} == {ref}:{src} ({len(got)} bytes, {n} lines, {marks} 'claude <date>' marks; mode at the source {mode(ref, src)})")
    bad += 0 if same else 1
# InputPort: only the package line differs
new = f'{M}/internal/InputPort.scala'
want = show(PR5, f'{M}/spi/InputPort.scala').decode('utf-8').split('\n')
got = open(f'{wt}/{new}').read().split('\n')
diff = [(i + 1, a, b) for i, (a, b) in enumerate(zip(want, got)) if a != b]
ok = len(want) == len(got) and diff == [(1, 'package tui4s.spi', 'package tui4s.internal')]
total_lines += len(got) - 1
print(f"{'OK  ' if ok else 'DIFF'} {new} == {PR5}:{M}/spi/InputPort.scala except the package line: {diff} ({len(got) - 1} lines)")
bad += 0 if ok else 1
print(f'carried lines in all: {total_lines}')
print('not carried (P-5 and the plan 6.5 list): internal/CountingBackend.scala (its close() guard moves into Canvas), '
      'internal/TuiRun.scala (merges into Tui.run), spi/Headless.scala, spi/SchedulerDefaults.scala (T2), TuiApp.scala (T2), '
      'Tui.scala and Options.scala (references only: the error channel, the restore order, TuiError and the option set), '
      'examples/ptycheck/analyze.py (vt.py and check.py replace it), the prototype pty/run-all.sh and pty/cpu.sh, '
      'examples/src/main/scala/examples/counter/CounterApp.scala (the old Elm Counter)')
print('RESULT', 'OK' if bad == 0 else f'{bad} DIFFERENCES')
sys.exit(1 if bad else 0)
