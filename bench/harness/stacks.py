"""The stacks of the bench: their language, their source files per task, and their build artifacts."""
# claude 2026-10-20: created: the stacks of the bench harness (L5)

import glob
import os
import subprocess

from term import BENCH

IMPL = os.path.join(BENCH, "impl")
TASKS = ("counter", "dashboard")

# language: the lexer of compact.py; sources: per task, relative to impl/<stack>/;
# artifact: how to measure the size of what the program needs besides its runtime (JDK, Python, Node).
STACKS = {
    "tui4s": {"language": "scala", "kind": "jvm", "sources": {"counter": ["counter/Counter.scala"],
                                                             "dashboard": ["dashboard/Dashboard.scala"]}},
    "jatatui-plain": {"language": "java", "kind": "jvm", "sources": {"counter": ["counter/Counter.java"],
                                                                     "dashboard": ["dashboard/Dashboard.java"]}},
    "tamboui": {"language": "java", "kind": "jvm", "sources": {"counter": ["counter/Counter.java"],
                                                               "dashboard": ["dashboard/Dashboard.java"]}},
    "layoutz": {"language": "scala", "kind": "jvm", "sources": {"counter": ["counter/Counter.scala"],
                                                                "dashboard": ["dashboard/Dashboard.scala"]}},
    "ratatui": {"language": "rust", "kind": "native", "sources": {"counter": ["counter/src/main.rs"],
                                                                  "dashboard": ["dashboard/src/main.rs"]}},
    "bubbletea": {"language": "go", "kind": "native", "sources": {"counter": ["counter/main.go"],
                                                                  "dashboard": ["dashboard/main.go"]}},
    "textual": {"language": "python", "kind": "python", "sources": {"counter": ["counter/app.py"],
                                                                    "dashboard": ["dashboard/app.py"]}},
    "ink": {"language": "js", "kind": "node", "sources": {"counter": ["counter/app.jsx"],
                                                          "dashboard": ["dashboard/app.jsx"]}},
}
ORDER = ["tui4s", "jatatui-plain", "tamboui", "layoutz", "ratatui", "bubbletea", "textual", "ink"]


def stack_dir(stack):
    return os.path.join(IMPL, stack)


def sources(stack, task):
    return [os.path.join(stack_dir(stack), p) for p in STACKS[stack]["sources"][task]]


def du(path):
    total = 0
    if os.path.isfile(path):
        return os.path.getsize(path)
    for root, _, files in os.walk(path):
        for f in files:
            p = os.path.join(root, f)
            if not os.path.islink(p):
                total += os.path.getsize(p)
    return total


def artifact(stack, task):
    """(bytes, what was measured)."""
    d = stack_dir(stack)
    kind = STACKS[stack]["kind"]
    if kind == "jvm":
        lib = os.path.join(d, task, "target", "universal", "stage", "lib")
        return du(lib), "the jars of the staged app (the app and its dependencies; no JDK)"
    if stack == "ratatui":
        return du(os.path.join(d, "target", "release", task)), "the release binary (stripped)"
    if stack == "bubbletea":
        return du(os.path.join(d, "bin", task)), "the binary (go build -trimpath)"
    if stack == "textual":
        site = glob.glob(os.path.join(d, ".venv", "lib", "python*", "site-packages"))[0]
        pip = glob.glob(os.path.join(site, "pip")) + glob.glob(os.path.join(site, "pip-*"))
        return du(site) - sum(du(p) for p in pip), "the packages of the virtual environment, without pip (no Python)"
    if stack == "ink":
        out = subprocess.run(["pnpm", "list", "--prod", "--depth", "Infinity", "--parseable"], cwd=d,
                             capture_output=True, text=True).stdout.split()
        dirs = sorted({p for p in out if os.path.isdir(p) and "node_modules" in p})
        total = sum(du(p) for p in dirs) + du(os.path.join(d, "dist", task))
        return total, "the production packages of node_modules and the bundle (no Node.js)"
    raise KeyError(stack)
