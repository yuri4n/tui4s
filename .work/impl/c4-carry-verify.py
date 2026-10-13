#!/usr/bin/env python3
# claude 2026-10-13: created (C4 carry check, after c2-carry-verify.py and c3-carry-verify.py). Each carried file must be
# byte-identical to its fe06611 blob, and line-identical to the prototype except: the package line, the carry header
# (one comment line and one blank line), the tui4s imports (tui4s.kyoui -> tui4s; ComponentSpec now lives in
# tui4s.internal, so Component.scala imports it and ComponentSpec.scala imports Component), and the split of the
# prototype's Component.scala into Component.scala (lines 39-74) and internal/ComponentSpec.scala (lines 7-37).
import subprocess, sys, difflib
wt = sys.argv[1]; commit = 'fe06611'
proto = '/home/user/tui4s/.work/proto/kyoui-runner'
files = {
  'modules/core/src/main/scala/tui4s/Component.scala': (f'{proto}/src/tui4s/kyoui/Component.scala', 39, 74),
  'modules/core/src/main/scala/tui4s/internal/ComponentSpec.scala': (f'{proto}/src/tui4s/kyoui/Component.scala', 7, 37),
  'modules/core/src/main/scala/tui4s/Hooks.scala': (f'{proto}/src/tui4s/kyoui/Hooks.scala', None, None),
  'modules/core/src/test/scala/tui4s/fixtures/CounterStyles.scala': (f'{proto}/apps/CounterStyles.scala', None, None),
}
def frame(l):
    s = l.strip()
    return s == '' or s.startswith('package ') or s.startswith('import ') or s.startswith('// claude 2026-10-09: created (carried')
bad = 0
for path, (src, a, b) in files.items():
    blob = subprocess.run(['git', '-C', wt, 'show', f'{commit}:{path}'], capture_output=True, text=True, check=True).stdout
    tree = open(f'{wt}/{path}').read()
    same = blob == tree
    print(f"{'OK  ' if same else 'DIFF'} whole file {path} == {commit}:{path} ({len(tree.splitlines())} lines)")
    bad += 0 if same else 1
    p = open(src).read().splitlines()
    if a is not None: p = p[a-1:b]
    t = tree.splitlines()
    code_p = [l for l in p if not frame(l)]
    code_t = [l for l in t if not frame(l)]
    ok = code_p == code_t
    rng = f' lines {a}-{b}' if a is not None else ''
    print(f"{'OK  ' if ok else 'DIFF'}   code lines (no package, import, blank or carry-header line): {src}{rng}: {len(code_p)}; carried: {len(code_t)}")
    bad += 0 if ok else 1
    for l in difflib.unified_diff(p, t, lineterm='', n=0):
        if l[:1] in '+-' and not l.startswith(('+++', '---')):
            kind = 'frame' if frame(l[1:]) else 'CODE'
            print(f"       {kind:5} {l}")
            bad += 0 if kind == 'frame' else 1
    marks_p = sum('claude 20' in l for l in p); marks_t = sum('claude 20' in l for l in t)
    print(f"       marks (claude 20..): prototype{rng} {marks_p}, carried {marks_t}")
print('RESULT', 'OK' if bad == 0 else f'{bad} DIFFERENCES'); sys.exit(1 if bad else 0)
