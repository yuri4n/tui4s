#!/usr/bin/env python3
# claude 2026-10-12: created (C2 carry check, after .work/spikes/s6-flags/verify-carry.py). Whole-file carries must be
# byte-identical to the fe06611 blob. The two split files (TuiOptions, FrameStats) must hold the given line range of
# fe06611:.../Tui.scala unchanged, plus only package, import, blank and carry-mark lines.
import subprocess, sys
wt = sys.argv[1]; commit = 'fe06611'
def show(path): return subprocess.run(['git','-C',wt,'show',f'{commit}:{path}'],capture_output=True,text=True,check=True).stdout
whole = ['modules/core/src/main/scala/tui4s/internal/World.scala','modules/core/src/main/scala/tui4s/internal/Mount.scala',
  'modules/core/src/main/scala/tui4s/internal/Runner.scala','modules/core/src/main/scala/tui4s/internal/SchedulerProbe.scala',
  'modules/core/src/main/scala/tui4s/spi/Settle.scala','modules/core/src/main/scala/tui4s/testkit/Headless.scala',
  'modules/core/src/test/scala/tui4s/fixtures/Counter.scala','modules/core/src/test/scala/tui4s/fixtures/Todo.scala',
  'modules/core/src/test/scala/tui4s/fixtures/Dashboard.scala','modules/core/src/test/scala/tui4s/fixtures/Widgets.scala']
bad = 0
for p in whole:
    same = show(p) == open(f'{wt}/{p}').read()
    print(f"{'OK  ' if same else 'DIFF'} whole file {p} == {commit}:{p}")
    bad += 0 if same else 1
tui = show('modules/core/src/main/scala/tui4s/Tui.scala').split('\n')
def is_frame(l):
    s=l.strip(); return s=='' or s.startswith('package ') or s.startswith('import ') or s.startswith('// claude 2026-10-12: created (carried')
for p,(a,b) in {'modules/core/src/main/scala/tui4s/TuiOptions.scala':(14,25),'modules/core/src/main/scala/tui4s/FrameStats.scala':(27,50)}.items():
    want = [l for l in tui[a-1:b] if l.strip() != ""]  # the range may hold blank lines
    got = open(f'{wt}/{p}').read().split('\n')
    code = [l for l in got if not is_frame(l)]
    frame = [l for l in got if is_frame(l) and l.strip()!='']
    same = code == want
    print(f"{'OK  ' if same else 'DIFF'} split file {p}: {len(code)} code lines == {commit}:modules/core/src/main/scala/tui4s/Tui.scala lines {a}-{b}; frame lines added: {frame}")
    bad += 0 if same else 1
# the lines of Tui.scala that C2 does not carry (Tui.run, Tui.quit, the input fiber: T1 and C3)
rest = [i+1 for i,l in enumerate(tui) if not is_frame(l) and not (14<=i+1<=25 or 27<=i+1<=50)]
print(f"lines of {commit}:modules/core/src/main/scala/tui4s/Tui.scala NOT carried (object Tui: T1, C3): {rest[0]}-{rest[-1]} ({len(rest)} code lines)")
print('not carried from fe06611 (later PRs): tui4s/Component.scala, tui4s/Hooks.scala (C4); internal/ComponentSpec.scala (C4); internal/Dispatch.scala, internal/Keys.scala (C3, T1); internal/OutputGuard.scala (T1); test fixtures/CounterStyles.scala (C4), test AppsTest.scala (C2 writes new kyo-test suites)')
print('RESULT', 'OK' if bad==0 else f'{bad} DIFFERENCES'); sys.exit(1 if bad else 0)
