#!/usr/bin/env python3
# claude 2026-10-13: created (T2 carry check, after c2-, c4- and t1-carry-verify.py). Each carried file is compared with
# its source line by line, ignoring blank, package, import and mark lines; a carried file that is byte-identical to its
# source is also said so. Sources: 666a8e3 (PR 5 head) for TuiApp, SchedulerDefaults and their tests; 1d100ed (the
# prototype on origin/wip/handoff, byte-identical to the local .work/proto copy) for the apps.
# Usage: t2-carry-verify.py <worktree> [<commit or 'tree'>]: 'tree' reads the working tree, a commit reads its blobs.
import subprocess, sys, difflib
wt = sys.argv[1]; at = sys.argv[2] if len(sys.argv) > 2 else 'tree'
P = '.work/proto/kyoui-runner/apps'
files = {
  'modules/core/src/main/scala/tui4s/TuiApp.scala': ('666a8e3', 'modules/core/src/main/scala/tui4s/TuiApp.scala'),
  'modules/core/src/main/scala/tui4s/spi/SchedulerDefaults.scala': ('666a8e3', 'modules/core/src/main/scala/tui4s/spi/SchedulerDefaults.scala'),
  'modules/core/src/test/scala/tui4s/TuiAppTest.scala': ('666a8e3', 'modules/core/src/test/scala/tui4s/TuiAppTest.scala'),
  'modules/core/src/test/scala/tui4s/SchedulerDefaultsTest.scala': ('666a8e3', 'modules/core/src/test/scala/tui4s/SchedulerDefaultsTest.scala'),
  'examples/src/main/scala/examples/twocounters/TwoCounters.scala': ('1d100ed', f'{P}/Counter.scala'),
  'examples/src/main/scala/examples/counterstyles/CounterStyles.scala': ('1d100ed', f'{P}/CounterStyles.scala'),
  'examples/src/main/scala/examples/dashboard/Dashboard.scala': ('1d100ed', f'{P}/Dashboard.scala'),
  'examples/src/main/scala/examples/todo/Todo.scala': ('1d100ed', f'{P}/Todo.scala'),
  'examples/src/main/scala/examples/widgets/Widgets.scala': ('1d100ed', f'{P}/Widgets.scala'),
}
def show(commit, path):
    return subprocess.run(['git', '-C', wt, 'show', f'{commit}:{path}'], capture_output=True, text=True, check=True).stdout
def frame(l):
    s = l.strip()
    return s == '' or s.startswith('package ') or s.startswith('import ') or s.startswith('// claude 20')
bad = 0; total = 0
for path, (commit, src) in files.items():
    s = show(commit, src)
    t = open(f'{wt}/{path}').read() if at == 'tree' else show(at, path)
    total += len(t.splitlines())
    same = s == t
    print(f"{'SAME' if same else 'NEW '} {path} vs {commit}:{src}: {'byte-identical' if same else 'differs'} ({len(s.splitlines())} -> {len(t.splitlines())} lines)")
    ps, ts = s.splitlines(), t.splitlines()
    cp = [l for l in ps if not frame(l)]; ct = [l for l in ts if not frame(l)]
    ok = cp == ct
    print(f"  {'OK  ' if ok else 'DIFF'} code lines (no blank, package, import or mark line): source {len(cp)}, carried {len(ct)}")
    bad += 0 if ok else 1
    for l in difflib.unified_diff(ps, ts, lineterm='', n=0):
        if l[:1] in '+-' and not l.startswith(('+++', '---')):
            kind = 'frame' if frame(l[1:]) else 'CODE'
            print(f"       {kind:5} {l}")
    print(f"       marks (claude 20..): source {sum('claude 20' in l for l in ps)}, carried {sum('claude 20' in l for l in ts)}")
print(f'carried lines in all: {total}')
print('RESULT', 'OK' if bad == 0 else f'{bad} DIFFERENCES'); sys.exit(1 if bad else 0)
