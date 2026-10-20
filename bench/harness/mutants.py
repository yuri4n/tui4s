"""The bug classes of the mutant suite (bench/mutants/classes.md) and, per stack, the classes that do not apply.

A patch bench/mutants/<stack>/<class>.patch is one small mutant of the counter. A class in NOT_APPLICABLE has no
patch, with the reason. A class with no patch and no entry here is "not-written". NOTES gives an explanation of a
result; it never decides the outcome: the defuse probes of harness/bench.py (PROBES) decide "defused".
"""
# claude 2026-10-20: created (L5): the classes of the mutant suite

CLASSES = [
    "no-restore-on-exception",
    "wrong-thread",
    "no-resize",
    "off-by-one",
    "blocking-handler",
    "leaked-timer",
    "non-exhaustive-key",
    "null-read",
    "stale-closure",
    "wrong-unit",
    "stdout-write",
    "missing-binding",
    "lost-update",
]

NOT_APPLICABLE = {
    "tui4s": {
        "no-restore-on-exception": "the runner owns the restore (a release stack and a shutdown hook); "
        "a key handler has no way to skip it",
    },
    "jatatui-plain": {},
    "ratatui": {},
    "bubbletea": {
        "non-exhaustive-key": "a Go switch on the key string needs no default; an unmatched key does nothing",
        "stale-closure": "Update gets the current model as a value; no closure keeps the state",
    },
    "textual": {
        "no-restore-on-exception": "Textual catches the exception of an action, ends the app and restores the "
        "terminal; the app has no switch to turn this off",
        "no-resize": "the CSS layout follows each resize; an app keeps no size of its own (a fixed size is the "
        "wrong-unit class)",
        "non-exhaustive-key": "bindings map keys to actions; an unbound key does nothing",
        "stale-closure": "the actions read the reactive attribute; no closure keeps the state",
    },
    "ink": {
        "no-restore-on-exception": "ink restores the terminal on exit through signal-exit; the app has no switch "
        "to turn this off",
        "wrong-thread": "JavaScript runs the handlers on one thread",
        "non-exhaustive-key": "an if-else chain on the input; an unmatched key does nothing",
        "wrong-unit": "ink sizes are cells or percent strings, and the counter has no size to mix up",
    },
    "layoutz": {
        "no-restore-on-exception": "the runtime catches the exceptions of update and keeps the terminal",
        "wrong-thread": "update runs under the lock of the runtime; the app has no thread of its own",
        "no-resize": "layoutz has no resize event; the unchanged program already fails the resize step",
        "wrong-unit": "the counter has no size or duration in layoutz",
        "stale-closure": "update gets the current state as a value (Elm architecture)",
        "lost-update": "the runtime applies each message under its lock, one at a time",
    },
}

# claude 2026-10-20: changed: notes only (review fix: the old DEFUSED list decided the outcome after the results)
NOTES = {
    "tui4s": {
        "stdout-write": "TuiApp captures stdout while the UI runs and prints it after the restore",
        "non-exhaustive-key": "the runner catches the MatchError of the handler, logs it and goes on",
        "wrong-thread": "a SignalRef is an atomic reference, and the runner draws the change on its own fiber",
    },
    "ratatui": {"leaked-timer": "the thread ends with the process when main returns"},
    "bubbletea": {"leaked-timer": "the goroutine ends with the process when main returns"},
    "textual": {
        "stdout-write": "Textual captures print() while the app runs",
        "lost-update": "Textual handles one message at a time and awaits its action",
        "wrong-thread": "it works here, but Textual asks for call_from_thread",
    },
    "ink": {"stdout-write": "ink patches console.log while it renders (patchConsole)"},
    "layoutz": {"stdout-write": "the next frame (at most 50 ms later) overwrites the text"},
}


def not_applicable(stack):
    return NOT_APPLICABLE.get(stack, {})


def notes(stack):
    return NOTES.get(stack, {})
