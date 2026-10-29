#!/usr/bin/env bash
# cursor 2026-10-29: created: check three extra deadline attempts at the 300 ms gate.
# The functions under test are the settlement gate in .github/workflows/ci.yml.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
workflow="$root/.github/workflows/ci.yml"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

python3 - "$workflow" "$work/funcs.sh" <<'PY'
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
marker = text.index("settlement_deadline_only() {")
start = text.rfind("\n", 0, marker) + 1
end = text.index("\n          JAVA_OPTS=", marker)
lines = text[start:end].splitlines()
base = min(len(line) - len(line.lstrip(" ")) for line in lines if line.strip())
script = "\n".join(line[base:] if line.startswith(" " * base) else line for line in lines)
if text.count("--deadline-ms 300") != 4 or "--deadline-ms 250" in text:
    sys.exit("the settlement gate must use --deadline-ms 300 on four commands")
if '[ "$attempt" -le 4 ]' not in script:
    sys.exit("the settlement gate must stop after 4 attempts")
Path(sys.argv[2]).write_text(script + "\n")
PY

bash -n "$work/funcs.sh"
# shellcheck disable=SC1091
source "$work/funcs.sh"

fail=0
check() {
  local name="$1" want_rc="$2" want_calls="$3"
  local dir="$work/$name"
  mkdir -p "$dir"
  python3 - "$dir/out.json" "$4" <<'PY'
import json, sys
path, kind = sys.argv[1], sys.argv[2]
report = {
    "ok": False,
    "failures": ["6 visible keys exceeded the planned-send deadline"],
    "missing_count": 0,
    "incorrect_count": 0,
    "final_text_matches": True,
    "shutdown": {"graceful": True, "terminal_restored": True, "errors": []},
}
if kind == "missing":
    report["failures"] = ["2 keys have no correct screen effect"]
    report["missing_count"] = 2
elif kind == "incorrect":
    report["failures"] = ["1 incorrect input states were observed"]
    report["incorrect_count"] = 1
elif kind == "final-text":
    report["failures"] = ["the final input field differs from the sent text"]
    report["final_text_matches"] = False
elif kind == "shutdown":
    report["failures"] = ["shutdown: Ctrl-C did not give a normal exit without forced cleanup"]
    report["shutdown"] = {"graceful": False, "terminal_restored": True, "errors": []}
json.dump(report, open(path, "w"))
PY
  cat > "$dir/stub.sh" <<EOF
#!/bin/bash
echo call >> "$dir/calls"
n=\$(wc -l < "$dir/calls")
pass_at=$5
if [ "\$pass_at" != never ] && [ "\$n" -ge "\$pass_at" ]; then
  exit 0
fi
exit 1
EOF
  chmod +x "$dir/stub.sh"
  set +e
  settlement_gate "$dir/out.json" "$dir/stub.sh" >/dev/null
  local rc=$?
  set -e
  local calls
  calls=$(wc -l < "$dir/calls" | tr -d ' ')
  if [ "$rc" -eq "$want_rc" ] && [ "$calls" -eq "$want_calls" ]; then
    echo "OK $name: rc=$rc calls=$calls"
  else
    echo "FAIL $name: rc=$rc calls=$calls want rc=$want_rc calls=$want_calls" >&2
    fail=1
  fi
}

# pass_at is the attempt number that exits 0. never keeps failing.
check second-try 0 2 deadline 2
check fourth-try 0 4 deadline 4
check fifth-miss 1 4 deadline never
check missing-keys 1 1 missing never
check incorrect-text 1 1 incorrect never
check final-text 1 1 final-text never
check bad-shutdown 1 1 shutdown never

exit "$fail"
