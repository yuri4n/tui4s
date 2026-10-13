#!/usr/bin/env python3
# claude 2026-10-13: created (C3 carry check, after c2-carry-verify.py). The carried Dispatch.scala must be
# byte-identical to the fe06611 blob, and line-identical to the prototype except: the package line, the carry header
# (one comment line and one blank line), and the Scaladoc link of Tui4sQuit (tui4s.kyoui.Tui.quit -> tui4s.Tui.quit).
import subprocess, sys, difflib
wt = sys.argv[1]; commit = 'fe06611'
path = 'modules/core/src/main/scala/tui4s/internal/Dispatch.scala'
proto = '/home/user/tui4s/.work/proto/kyoui-runner/src/tui4s/kyoui/internal/Dispatch.scala'
blob = subprocess.run(['git', '-C', wt, 'show', f'{commit}:{path}'], capture_output=True, text=True, check=True).stdout
tree = open(f'{wt}/{path}').read()
bad = 0
same = blob == tree
print(f"{'OK  ' if same else 'DIFF'} whole file {path} == {commit}:{path} ({len(tree.splitlines())} lines)")
bad += 0 if same else 1
p = open(proto).read().splitlines(); t = tree.splitlines()
print(f"prototype {proto}: {len(p)} lines; carried: {len(t)} lines")
allowed = {
  ('-', 'package tui4s.kyoui.internal'), ('+', 'package tui4s.internal'), ('+', ''),
  ('+', '// claude 2026-10-09: created (carried from .work/proto/kyoui-runner/src/tui4s/kyoui/internal/Dispatch.scala; new path and package only)'),
  ('-', '/** The quit action of the running app, for [[tui4s.kyoui.Tui.quit]]. A `Local`, so it also reaches fibers forked by a'),
  ('+', '/** The quit action of the running app, for [[tui4s.Tui.quit]]. A `Local`, so it also reaches fibers forked by a'),
}
diff = [l for l in difflib.unified_diff(p, t, lineterm='', n=0) if l[:1] in '+-' and not l.startswith(('+++', '---'))]
for l in diff:
    ok = (l[0], l[1:]) in allowed
    print(f"{'allowed ' if ok else 'UNEXPECTED'} {l}")
    bad += 0 if ok else 1
code_p = [l for l in p if not l.startswith('package ')]
code_t = [l for l in t if not l.startswith('package ') and not l.startswith('// claude 2026-10-09: created (carried')]
print(f"code lines (no package, no carry header): prototype {len(code_p)}, carried {len(code_t)}")
print('RESULT', 'OK' if bad == 0 else f'{bad} DIFFERENCES'); sys.exit(1 if bad else 0)
