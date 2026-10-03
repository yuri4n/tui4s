#!/usr/bin/env python3
# claude 2026-10-03: created this generator. It copies line ranges VERBATIM (from files and from `git show`) into
# SIDE-BY-SIDE.md and side-by-side.json, and counts the lines. It reads only; it changes no source file.
# Run: python3 .work/proto/side-by-side-gen.py   (from any folder)
import json
import os
import subprocess

REPO = "/home/user/tui4s"
OUT_MD = os.path.join(REPO, ".work/proto/SIDE-BY-SIDE.md")
OUT_JSON = os.path.join(REPO, ".work/proto/side-by-side.json")
CORE = "origin/feat/core-3-terminal"
OZIO = "origin/feat/zio"
OCATS = "origin/feat/cats"
EX = "examples/src/main/scala/examples/"
RUN = ".work/proto/kyoui-runner/"
FAC = ".work/proto/kyoui-facades/"


# claude 2026-10-03: created read_lines: the lines of a file at HEAD of the work tree or of a branch
def read_lines(src, branch):
    if branch:
        text = subprocess.run(["git", "-C", REPO, "show", f"{branch}:{src}"], capture_output=True, text=True,
                              check=True).stdout
    else:
        with open(os.path.join(REPO, src), encoding="utf-8") as f:
            text = f.read()
    return text.split("\n")


# claude 2026-10-03: created count: non-blank lines, and non-blank lines that are not only a comment
def count(code):
    nonblank = 0
    code_only = 0
    in_block = False
    for line in code.split("\n"):
        s = line.strip()
        if not s:
            continue
        nonblank += 1
        if in_block:
            if "*/" in s:
                in_block = False
            continue
        if s.startswith("//"):
            continue
        if s.startswith("/*"):
            if "*/" not in s:
                in_block = True
            continue
        code_only += 1
    return nonblank, code_only


# claude 2026-10-03: created block: one verbatim range; `counted` is False for test-harness code
# claude 2026-10-03: changed block: an optional `tag` for the header (integration code, library glue)
def block(src, branch, a, b, counted=True, tag=None):
    lines = read_lines(src, branch)
    assert 1 <= a <= b <= len(lines), (src, a, b, len(lines))
    code = "\n".join(lines[a - 1:b])
    where = f"{src} @ {branch}" if branch else src
    return {"src": src, "branch": branch, "a": a, "b": b, "counted": counted, "code": code,
            "header": f"// source: {where}, lines {a}-{b}" + ("" if counted or (tag and "glue" in tag) else " (test harness, not app code)")
            + (f" ({tag})" if tag else "")}


V_CORE = "Current core (PRs 3-5, being replaced), Kyo"
V_OZIO = "Current core, old ZIO facade"
V_OCATS = "Current core, old Cats facade"
V_HOOKS = "kyo-ui + tui4s runner, hooks"
V_COMP = "kyo-ui + tui4s runner, Component class"
V_ZIO = "ZIO facade (generated mirror)"
V_CATS = "Cats facade (generated mirror)"
VARIANTS = [V_CORE, V_OZIO, V_OCATS, V_HOOKS, V_COMP, V_ZIO, V_CATS]

FACT_KEYS = [
    ("lines", "Lines of user code (non-blank; without comment-only lines in brackets)"),
    ("effects", "Effect types visible to the user"),
    ("state", "Where state lives"),
    ("event", "How an event reaches state"),
    ("view", "How the view updates"),
    ("traps", "Known traps (file evidence)"),
    ("entry", "Entry point"),
    ("checked", "Run evidence (from the REPORTs and HANDOFF; not re-run for this document)"),
]

# ---------------------------------------------------------------------------------------------------------------
# claude 2026-10-03: created the shared facts (the same text for every app of a variant)
# ---------------------------------------------------------------------------------------------------------------
CORE_VIEW = ("Whole view rebuilt. After a change the engine calls `view(state)` "
             "(modules/core/src/main/scala/tui4s/internal/Engine.scala:356 @ feat/core-3-terminal) and skips the frame "
             "when the new view equals the last one. The painter paints the whole view through `terminal.draw`; "
             "jatatui compares the buffers and writes only the changed cells (internal/RenderLoop.scala:181-185).")
CORE_STATE_HOLDER = "The engine keeps it in `var state: S` (internal/Engine.scala:53 @ feat/core-3-terminal)."
CORE_NEXT_TRAP = ("`Next[S]` is a union type. tui4s/App.scala:95 @ feat/core-3-terminal says: \"Write the result type "
                  "of `update` (`: Next[Int]`): an inferred union widens.\"")
CORE_CHECKED = ("HANDOFF: PR 5 CI green, with the Linux `terminal` job; the expect checklist passed 47/47 on macOS "
                "(desktop).")
OZIO_CHECKED = ("HANDOFF: `check` green on feat/zio; review done; 14 confirmed findings not fixed (for example: the "
                "live-kit settle hangs on tasks that sleep on zio-test's TestClock; no `args` in the ZIO `TuiApp`); "
                "no PR.")
OCATS_CHECKED = "HANDOFF: `check` green on feat/cats; review not started; no PR."
RUNNER_CHECKED = ("Runner REPORT, independent verification on this Linux machine: headless tests 9/9 in 3 of 3 runs; "
                  "PTY run of a fresh jar: 16/16 PTY checks and 20/20 screen checks (Counter and Todo only).")
RUNNER_VIEW_RULE = ("Fine-grained regions. The UI value is built once. Each `render`, `map` text child, `foreachKeyed` "
                    "and bound `value`/`checked` is a region with its own watcher fiber. A change repaints only that "
                    "region, after a settle step, and the runner flushes only the changed cells (runner REPORT, "
                    "\"Runner design\").")
RUNNER_SETTLE_TRAP = ("The settle step is \"very likely\" glitch-free, not certain (runner REPORT N2: race windows "
                      "in the Kyo scheduler probe).")
LIFT_TRAP_PLAIN = ("Lift trap of plain kyo-ui: handlers are `Any < Async` (.work/scratch/kyo-src/kyo-ui/kyo/UI.scala:771, "
                   "783). A handler `val` without that type compiles and its effects never run, with no error and no "
                   "warning (runner REPORT O5; reproduced by the verifier's LiftProbe).")
ZIO_CHECKED = ("Facades REPORT (fixer and skeptic on this machine): ZIO tests green in 3 of 3 runs (9/9 + 2/2 stress); "
               "the facades prototype itself used only its headless test runner (O8).")
CATS_CHECKED = ("Facades REPORT (fixer and skeptic on this machine): Cats tests green in 3 of 3 runs (9/9 + 2/2 "
                "stress); the facades prototype itself used only its headless test runner (O8).")
MIRROR_VIEW = ("The same kyo-ui regions as the runner (`render`, `foreachKeyed`, `when`, bound `value`/`checked`). "
               "In the facades prototype only the headless test runner shows them: it mounts with kyo-ui's own "
               "`ReactiveUI` and renders the whole page with kyo-ui's `HtmlRenderer` when the test reads the text "
               "(facades REPORT, \"Headless test runner\"; O8).")
ZIO_GUARD = ("The lift trap is closed at compile time: an `onClick` argument that is not a `ZIO[Any, Nothing, Any]` "
             "gives a tui4s error (.work/proto/kyoui-facades/zio/generated/ZioUi.scala:322; facades REPORT, "
             "\"User-facing API\").")
CATS_GUARD = ("The lift trap is closed at compile time: an `onClick` argument that is not an `F[Unit]` gives a tui4s "
              "error (.work/proto/kyoui-facades/cats/generated/CatsUi.scala:323).")
ALIAS_TRAP = ("Kyo DATA types are reachable through the aliased event and style types, for example "
              "`KeyboardEvent.targetId` gives `kyo.Maybe` (facades REPORT O2; aliases at "
              "zio/generated/ZioUi.scala:398, 407).")
CATS_REF_TRAP = ("A binding setter takes any fs2 `SignallingRef`; a ref that `useSignal` did not make compiles and "
                 "fails at run time with `IllegalArgumentException` (facades REPORT O3; cats/src/Bridge.scala:44).")


# claude 2026-10-03: created the integration constants (the folder appeared while this document was written)
INT = ".work/proto/kyoui-integration/"
INT_TAG = "integration prototype, DONE"
INT_VIEW = (" On the terminal runner: the integration prototype (DONE) runs the unchanged facade apps on the "
            "runner's headless `TestBackend`; its REPORT says that Enter on a button gives `Count: -1` with 2 cells "
            "flushed (so the same fine-grained regions as the runner).")
INT_TRAPS = (" Integration REPORT (DONE): a button in a column takes the full width; the runner has a first-key race "
             "(the first key after start can show its result about 1 s late; REPORT R2).")
INT_CHECKED = (" Integration logs (written by the integration agent, read here, not re-run): " "ZioOnTerminalSuite 6/6 and CatsOnTerminalSuite 6/6 in each of 3 runs (`logs/test-final-c-{1,2,3}.log`), on the runner's headless `TestBackend`; negative controls fail as intended. Real PTY: ZIO Counter 10/10 terminal checks and 22/22 screen checks, `stty -a` after = before (`logs/pty-checklist.log`); the Cats Counter gives the same result. Under pinned CPU contention, 1 of 2 runs failed one Cats test (the runner's first-key race, integration REPORT R2).")


def none(reason):
    return {"none": reason}


# ---------------------------------------------------------------------------------------------------------------
# claude 2026-10-03: created the Counter section data
# ---------------------------------------------------------------------------------------------------------------
COUNTER = {
    "app": "Counter",
    "intro": ("The old Counter is one number: `+` and `-` change it, `q` quits. The runner Counter has the same keys "
              "and two buttons. The facade Counter has buttons only (no key handler, no quit). The runner and facade "
              "folders also have two independent counters on one screen. The features are not the same in all "
              "variants, so compare the line counts with care."),
    "variants": [
        {"variant": V_CORE,
         "note": "The whole app. `TuiApp` gives `main`.",
         "blocks": [block(EX + "counter/CounterApp.scala", CORE, 1, 25)],
         "facts": {
             "effects": "None. The user sees `Next[Int]`, `Keymap[Int]`, `View`, `Event`. The file imports only `tui4s.*`.",
             "state": "One immutable value (`Int`). " + CORE_STATE_HOLDER,
             "event": ("The runtime gives each key as an `Event` to `update(n, event)`. The `Keymap` finds the binding and "
                       "returns `Next[Int]` (a new `Int`, or `Quit`)."),
             "view": CORE_VIEW,
             "traps": CORE_NEXT_TRAP,
             "entry": "`object CounterApp extends TuiApp[Int]`; `TuiApp.main` (tui4s/TuiApp.scala:44 @ feat/core-3-terminal).",
             "checked": CORE_CHECKED}},
        {"variant": V_OZIO,
         "note": ("The same text as the core Counter. Only the package, the import and the doc comment are different. "
                  "Here `TuiApp` is a `ZIOAppDefault` (modules/zio/src/main/scala/tui4s/interop/zio/TuiApp.scala:32 @ "
                  "feat/zio)."),
         "blocks": [block(EX + "ziocounter/CounterApp.scala", OZIO, 1, 28)],
         "facts": {
             "effects": "None in the file. The import is `tui4s.interop.zio.*`. `TuiApp` is a `ZIOAppDefault`.",
             "state": "As in the core: one immutable `Int` in the core engine (the ZIO `TuiApp` extends `tui4s.App[S]`).",
             "event": "As in the core: `update(n, event)` with a `Keymap`.",
             "view": "As in the core: whole view rebuilt; jatatui writes the changed cells.",
             "traps": (CORE_NEXT_TRAP + " A package named `zio` shadows the root `zio` (comment at "
                       "examples/ziocounter/CounterApp.scala:3 @ feat/zio)."),
             "entry": "`object CounterApp extends TuiApp[Int]` (a `ZIOAppDefault`).",
             "checked": OZIO_CHECKED}},
        {"variant": V_OCATS,
         "note": ("The same text as the core Counter. Only the package, the import and the doc comment are different. "
                  "Here `TuiApp` is an `IOApp.Simple` (modules/cats/src/main/scala/tui4s/interop/cats/TuiApp.scala:31 @ "
                  "feat/cats)."),
         "blocks": [block(EX + "catscounter/CounterApp.scala", OCATS, 1, 26)],
         "facts": {
             "effects": "None in the file. The import is `tui4s.interop.cats.*`. `TuiApp` is an `IOApp.Simple`.",
             "state": "As in the core: one immutable `Int` in the core engine.",
             "event": "As in the core: `update(n, event)` with a `Keymap`.",
             "view": "As in the core: whole view rebuilt; jatatui writes the changed cells.",
             "traps": CORE_NEXT_TRAP,
             "entry": "`object CounterApp extends TuiApp[Int]` (an `IOApp.Simple`).",
             "checked": OCATS_CHECKED}},
        {"variant": V_HOOKS,
         "note": ("Two blocks. Block 1 is the Counter in plain kyo-ui: it uses `Signal.initRef`, which is what "
                  "`useSignal` calls (.work/proto/kyoui-runner/src/tui4s/kyoui/Hooks.scala:94-95). Block 2 is the same "
                  "style with the tui4s name `useSignal`, and `component(...)`, which runs the hook at mount. The "
                  "hooks code calls only these tui4s names: `Tui.run`, `Tui.quit`, `useSignal`, `component`. Block 3 "
                  "is the main objects of block 2."),
         "extra_counts": [("Comparable part (one counter and its main: Counter.scala 1-25 and 54-55)",
                           RUN + "apps/Counter.scala", [(1, 25), (54, 55)])],
         "blocks": [block(RUN + "apps/Counter.scala", None, 1, 58),
                    block(RUN + "apps/CounterStyles.scala", None, 1, 46),
                    block(RUN + "apps/CounterStyles.scala", None, 71, 75)],
         "facts": {
             "effects": ("`UI < Sync` (the app value), Kyo `SignalRef[Int]` and `Signal`, kyo-ui handlers of type "
                         "`Any < Async`, `Tui.quit: Unit < Sync`, `KyoApp`. Imports `kyo.*` and `kyo.UI.*`."),
             "state": ("One Kyo `SignalRef[Int]` per counter. `Signal.initRef(0)` / `useSignal(0)` makes it when the "
                       "effect runs: once in the top `for`, or at each mount inside `component(...)`."),
             "event": ("The runner sends a kyo-ui `KeyboardEvent` to `onKeyDown` (it bubbles from the focused node). "
                       "Enter or Space on a focused `button` runs `onClick`. The handler writes the ref "
                       "(`count.updateAndGet(_ + 1)`)."),
             "view": RUNNER_VIEW_RULE + " Runner REPORT N4/N6: one `+` changes 1 cell and writes 26 bytes to the terminal.",
             "traps": (LIFT_TRAP_PLAIN + " The Counter writes its handlers inline, so it does not hit it. Hook state "
                       "inside a `when` is lost when the node hides (CounterStyles.scala:33-35; runner REPORT O7). "
                       + RUNNER_SETTLE_TRAP),
             "entry": "`object CounterMain extends KyoApp: run(Tui.run(Counter.app))` (Counter.scala:54-55).",
             "checked": RUNNER_CHECKED}},
        {"variant": V_COMP,
         "note": ("The same file as block 2 of the hooks variant (`apps/CounterStyles.scala`), other line ranges: the "
                  "imports, the class `CounterBox` and its main. The base class `Component` has a type member `State`, "
                  "`initial`, `view(state: SignalRef[State]): UI` and an optional `onMount` "
                  "(.work/proto/kyoui-runner/src/tui4s/kyoui/Component.scala:52-56)."),
         "blocks": [block(RUN + "apps/CounterStyles.scala", None, 1, 7),
                    block(RUN + "apps/CounterStyles.scala", None, 48, 69),
                    block(RUN + "apps/CounterStyles.scala", None, 77, 78)],
         "facts": {
             "effects": ("Kyo `SignalRef[Int]` in `view`; `view` and `app` return a plain `UI` (no `<`); handlers are "
                         "`Any < Async`; `KyoApp` and `Tui.run`. Imports `kyo.*` and `kyo.UI.*`."),
             "state": ("One `SignalRef[State]` per component instance. The runner makes it at mount with `initial`, "
                       "keeps it while a component of the same class stays at the same position, and drops it at "
                       "unmount (Component.scala:41-43)."),
             "event": "As in the hooks variant: a handler in `view` writes the ref.",
             "view": RUNNER_VIEW_RULE,
             "traps": ("Handlers have the same type `Any < Async` as in the hooks variant. A component node is a "
                       "`Reactive` that the runner finds in a weak global map, because kyo-ui's AST is sealed "
                       "(Component.scala:17-19; runner REPORT N9). In another kyo-ui runner the state is made once "
                       "per node value (Component.scala:58-60). " + RUNNER_SETTLE_TRAP),
             "entry": ("`object CounterComponentsMain extends KyoApp: run(Tui.run(CounterComponents.app))` "
                       "(CounterStyles.scala:77-78)."),
             "checked": (RUNNER_CHECKED + " A test checks that `CounterBox` state survives a parent re-render "
                         "(test/Apps.test.scala:90). The verifier found no use of `Component.onMount` (refutation 9).")}},
        {"variant": V_ZIO,
         "note": ("Hooks style (`Counter`) and class style (`CounterComponent`) in one file (block 1). The facades "
                  "prototype has no terminal entry point. The integration prototype adds one (block 2). "
                  "Blocks 3 and 4 show how the facade test starts the app on the headless test runner."),
         "blocks": [block(FAC + "zio/apps/Counter.scala", None, 1, 41),
                    block(INT + "mains/Mains.scala", None, 11, 12, tag=INT_TAG),
                    block(FAC + "zio/test/ZioFacadeSuite.test.scala", None, 15, 16, counted=False),
                    block(FAC + "zio/test/ZioFacadeSuite.test.scala", None, 24, 33, counted=False)],
         "facts": {
             "effects": ("`ZIO[Scope, Nothing, HtmlNode]`, `ZIO[Scope, Nothing, UI]`, and `UIO` through `count.update` "
                         "/ `count.set`; the tui4s `SignalRef[Int]`; `HtmlNode` and `UI` are opaque mirror types. "
                         "Imports `tui4s.interop.zio.ui.*` and `zio.*`. No Kyo import."),
             "state": ("A tui4s `SignalRef[Int]` from `useSignal(0)`. Its state IS the Kyo cell that kyo-ui binds "
                       "(facades REPORT, \"State (one source of truth)\"). `CounterComponent.mount` calls "
                       "`useSignal(initial)` when the `for` runs (zio/src/Hooks.scala, `mount`), not by position in a "
                       "runner."),
             "event": ("kyo-ui dispatches the click. The mirror's `onClick(action: ZIO[Any, Nothing, Any])` "
                       "(zio/generated/ZioUi.scala:246-247) wraps the ZIO with `Bridge.action`, which runs it on the "
                       "runtime of the fiber that mounted the UI. The ZIO writes the ref."),
             "view": MIRROR_VIEW + INT_VIEW,
             "traps": ZIO_GUARD + " " + ALIAS_TRAP + INT_TRAPS,
             "entry": ("`CounterApp.app: ZIO[Scope, Nothing, UI]`. Terminal: `object ZioCounterMain extends "
                       "ZIOAppDefault: def run = tui4s.interop.zio.ui.Tui.run(apps.zio.CounterApp.app)` "
                       "(kyoui-integration/mains/Mains.scala:11-12). Facade tests: "
                       "`Headless.mount(CounterApp.app)` (ZioFacadeSuite.test.scala:26)."),
             "checked": ZIO_CHECKED + INT_CHECKED}},
        {"variant": V_CATS,
         "note": ("Tagless final: `F[_]: Async`. Hooks style and class style in one file (block 1). The integration "
                  "prototype adds the terminal entry point (block 2). Blocks 3 and 4 show how the facade "
                  "test starts the app."),
         "blocks": [block(FAC + "cats/apps/Counter.scala", None, 1, 41),
                    block(INT + "mains/Mains.scala", None, 18, 19, tag=INT_TAG),
                    block(FAC + "cats/test/CatsFacadeSuite.test.scala", None, 17, 17, counted=False),
                    block(FAC + "cats/test/CatsFacadeSuite.test.scala", None, 24, 32, counted=False)],
         "facts": {
             "effects": ("`Resource[F, HtmlNode]`, `Resource[IO, UI]`, `F[Unit]` through `count.update` / `count.set`, "
                         "`Async[F]`, `fs2.concurrent.SignallingRef[F, Int]`, `IO`. Imports `cats.effect.*`, "
                         "`fs2.concurrent.SignallingRef`, `tui4s.interop.cats.ui.*`. No Kyo import."),
             "state": ("An fs2 `SignallingRef[F, Int]` from `useSignal[F](0)`. It is a `KyoSignallingRef` that "
                       "extends `SignallingRef` over the Kyo cell (facades REPORT, \"State\")."),
             "event": ("The mirror's `onClick[F[_]](action: F[Unit])(using Async[F])` "
                       "(cats/generated/CatsUi.scala:247-248) runs the `F` through the caller's `Dispatcher[F]`."),
             "view": MIRROR_VIEW + INT_VIEW,
             "traps": CATS_GUARD + " " + CATS_REF_TRAP + (" `scala.concurrent.duration.*` brings a `span` that shadows "
                                                          "the mirror's `span` (comment at "
                                                          "cats/test/CatsFacadeSuite.test.scala:11).") + INT_TRAPS,
             "entry": ("`CounterApp.app: Resource[IO, UI]`. Terminal: `object CatsCounterMain extends IOApp.Simple: "
                       "def run: IO[Unit] = tui4s.interop.cats.ui.Tui.run(apps.cats.CounterApp.app)` "
                       "(kyoui-integration/mains/Mains.scala:18-19). Facade tests: "
                       "`Headless.mount(CounterApp.app).use { h => ... }` (CatsFacadeSuite.test.scala:24)."),
             "checked": CATS_CHECKED + INT_CHECKED}},
    ],
}

# ---------------------------------------------------------------------------------------------------------------
# claude 2026-10-03: created the Todo section data
# ---------------------------------------------------------------------------------------------------------------
TODO = {
    "app": "Todo",
    "intro": ("The old Todo and the runner Todo both have a text input, a list with a cursor, toggle, delete, file "
              "load and Ctrl-S save. The facade Todo has no file, no cursor and no quit key: it has an Add button, a "
              "checkbox and a delete button per row, and a \"Clear done\" button. So the facade line counts are for "
              "less work."),
    "variants": [
        {"variant": V_CORE,
         "note": "The whole app: the state, a file store in plain Kyo, the keymaps per pane, the view.",
         "blocks": [block(EX + "todo/TodoApp.scala", CORE, 1, 176)],
         "facts": {
             "effects": ("Kyo `Sync`, `Abort[IOException]`, `Result`, `Frame` (in `TodoStore` and in the folds "
                         "`loaded` / `saved`). From tui4s: `Next[Todo]`, `Source[Todo]`, `Keymap[Todo]`, `View`, "
                         "`TextField`, `Focus[Pane]`, `Line`. Imports `kyo.*` and `tui4s.*`."),
             "state": ("One immutable case class `Todo` (text field, items, selection, focus, ready, status). "
                       + CORE_STATE_HOLDER),
             "event": ("`update(t, event)` picks the keymap of the focused pane; a key with no binding goes to the "
                       "text field (`t.typed(e)`). The load is a `source.task` that `sources` declares while `ready` "
                       "is false. Ctrl-S returns `withTask(...)`. A task result is folded by `_.loaded(_)` / "
                       "`_.saved(_)`."),
             "view": CORE_VIEW,
             "traps": (CORE_NEXT_TRAP + " The text field must give the same object back when a key does nothing; "
                       "else a frame is drawn (comment at TodoApp.scala:57)."),
             "entry": "`object TodoApp extends TuiApp[Todo]`.",
             "checked": CORE_CHECKED}},
        {"variant": V_OZIO, **none("No version: feat/zio has no ZIO Todo (only ziocounter and ziodashboard).")},
        {"variant": V_OCATS, **none("No version: feat/cats has no Cats Todo (only catscounter and catsdashboard).")},
        {"variant": V_HOOKS,
         "note": ("Plain kyo-ui. The state is made with `Signal.initRef` in one `for` (the hooks style). Components "
                  "are plain functions of signals and actions."),
         "blocks": [block(RUN + "apps/Todo.scala", None, 1, 114)],
         "facts": {
             "effects": ("`UI < (Async & Scope)`, `Chunk[Item] < Sync`, `Unit < (Sync & Abort[IOException])`, Kyo "
                         "`SignalRef`, `Signal`, `AtomicInt`, `Result`, `Frame`, and handlers typed `Any < Async` "
                         "(written out at Todo.scala:94). Imports `kyo.*` and `kyo.UI.*`."),
             "state": ("Four Kyo refs (`items`, `draft`, `cursor`, `status`) and an `AtomicInt` (`nextId`), made in "
                       "one `for` at the start (Todo.scala:68-73). Derived signals: `rows` (`combineLatest`) and "
                       "`count` (`map`)."),
             "event": ("Typing into the focused `input` writes `draft` (two-way binding `.value(draft)`). Enter in "
                       "the `form` runs `onSubmit(add)`. Keys on the focused list go to `listKeys`. Ctrl-S and Esc "
                       "bubble to `main.onKeyDown`. Each handler is a Kyo effect that writes the refs."),
             "view": (RUNNER_VIEW_RULE + " `rows.foreachKeyed` renders again only new or changed rows. Runner "
                      "REPORT N4: toggle 0.34 / 0.76 ms (p50 / p99)."),
             "traps": ("The lift trap is in this file: `listKeys` needs the type `: Any < Async` "
                       "(Todo.scala:85-94). Without it the effects never run, with no error and no warning (runner "
                       "REPORT O5). Lengths are cells: `width(40.px)` is 40 cells (N7). No scrolling: a list taller "
                       "than the screen is clipped (N7). " + RUNNER_SETTLE_TRAP),
             "entry": ("`object TodoMain extends KyoApp: run(Tui.run(Todo.app(sys.env.getOrElse(\"TODO_FILE\", "
                       "\"todos.txt\"))))` (Todo.scala:113-114)."),
             "checked": RUNNER_CHECKED + " PTY: the saved file holds `[x] buy milk`."}},
        {"variant": V_COMP, **none("No version: the runner folder has the Component class only in the Counter "
                                   "(`CounterBox`).")},
        {"variant": V_ZIO,
         "note": ("Hooks style (block 1). Block 2 is the terminal entry point of the integration prototype. "
                  "Block 3 shows how the facade test starts the app."),
         "blocks": [block(FAC + "zio/apps/Todo.scala", None, 1, 47),
                    block(INT + "mains/Mains.scala", None, 14, 15, tag=INT_TAG),
                    block(FAC + "zio/test/ZioFacadeSuite.test.scala", None, 41, 56, counted=False)],
         "facts": {
             "effects": ("`ZIO[Scope, Nothing, HtmlNode]`, `UIO[Unit]`, a ZIO `Ref`, the tui4s `SignalRef`. "
                         "`KeyboardEvent` and `Keyboard` are aliases of kyo-ui data types "
                         "(zio/generated/ZioUi.scala:398, 407). No Kyo import."),
             "state": "`todos` and `draft`: tui4s `SignalRef`s from `useSignal`. `nextId`: a ZIO `Ref`.",
             "event": ("kyo-ui writes the typed text into `draft` (bound by `.value(draft)`) before `onKeyDown` runs. "
                       "`onKeyDown(e => ZIO.when(e.key == Keyboard.Enter)(add))`, `onClick(add)` and "
                       "`checkbox.onChange(d => toggle(t.id, d))` run ZIO values that write the refs."),
             "view": MIRROR_VIEW + INT_VIEW,
             "traps": ZIO_GUARD + " " + ALIAS_TRAP + INT_TRAPS,
             "entry": ("`TodoApp.app: ZIO[Scope, Nothing, HtmlNode]`. Terminal: `object ZioTodoMain extends "
                       "ZIOAppDefault` (kyoui-integration/mains/Mains.scala:14-15). Facade tests: "
                       "`Headless.mount(TodoApp.app)` (ZioFacadeSuite.test.scala:43)."),
             "checked": ZIO_CHECKED + " Stress: 0 of 150 failures, 0 wrong items per run." + INT_CHECKED}},
        {"variant": V_CATS,
         "note": ("Hooks style, tagless final (block 1). Block 2 is the terminal entry point of the integration "
                  "prototype. Block 3 shows how the facade test starts the app."),
         "blocks": [block(FAC + "cats/apps/Todo.scala", None, 1, 45),
                    block(INT + "mains/Mains.scala", None, 21, 22, tag=INT_TAG),
                    block(FAC + "cats/test/CatsFacadeSuite.test.scala", None, 40, 54, counted=False)],
         "facts": {
             "effects": ("`Resource[F, HtmlNode]`, `F[Unit]`, `Async[F]`, a Cats `Ref` (`Resource.eval(Ref.of[F, "
                         "Int](1))`), fs2 `SignallingRef` (from `useSignal`), `IO` in the test. Imports "
                         "`cats.effect.*`, `cats.syntax.all.*`, `tui4s.interop.cats.ui.*`. No Kyo import."),
             "state": "`todos` and `draft`: fs2 `SignallingRef`s from `useSignal[F]`. `nextId`: a Cats `Ref`.",
             "event": ("As in the ZIO facade, with `F[Unit]` handlers: `onKeyDown(e => add.whenA(e.key == "
                       "Keyboard.Enter))`, `onClick(add)`, `onChange(d => toggle(t.id, d))`."),
             "view": MIRROR_VIEW + INT_VIEW,
             "traps": CATS_GUARD + " " + CATS_REF_TRAP + " " + ALIAS_TRAP.replace("zio/generated/ZioUi.scala:398, 407",
                                                                               "cats/generated/CatsUi.scala:399, 408") + INT_TRAPS,
             "entry": ("`TodoApp[IO]: Resource[IO, HtmlNode]`. Terminal: `object CatsTodoMain extends IOApp.Simple` "
                       "(kyoui-integration/mains/Mains.scala:21-22). Facade tests: "
                       "`Headless.mount(TodoApp[IO]).use { h => ... }` (CatsFacadeSuite.test.scala:40)."),
             "checked": CATS_CHECKED + " Stress: 0 of 150 failures, 0 wrong items per run." + INT_CHECKED}},
    ],
}

# ---------------------------------------------------------------------------------------------------------------
# claude 2026-10-03: created the Dashboard section data
# ---------------------------------------------------------------------------------------------------------------
DASHBOARD = {
    "app": "Dashboard",
    "intro": ("The old Dashboard has 2 tabs (Metrics, Requests), a gauge and a sparkline fed by a poller every "
              "250 ms, a log pane fed by a stream, `p` pauses the poller, `q` quits. The runner Dashboard has 3 tabs "
              "(CPU, Memory, Network), the same gauge, sparkline, log pane and keys. The old core draws the gauge, "
              "sparkline and tabs with jatatui widgets; the runner app draws them as text in `Widgets.scala` (plain "
              "kyo-ui), which is included and counted."),
    "variants": [
        {"variant": V_CORE,
         "note": "The whole app. `sources(d)` is a function of the state.",
         "blocks": [block(EX + "dashboard/DashboardApp.scala", CORE, 1, 78)],
         "facts": {
             "effects": ("Kyo `Stream[String, Async]`, `Async.sleep`, `Random.nextDouble`, `Present`, `millis`. From "
                         "tui4s: `Source[Dash]` (`source.stream`, `source.poll`), `Next[Dash]`, `View`. Imports "
                         "`kyo.*` and `tui4s.*`."),
             "state": "One immutable case class `Dash` (tab, cpu, history, logs, paused). " + CORE_STATE_HOLDER,
             "event": ("Keys: `update(d, event) = keys(d, event)`. Background: the engine compares `sources(d)` by "
                       "id after each change. `p` removes the `cpu` poller, so its fiber stops; the `logs` stream "
                       "goes on. Each value is folded by `_.sample(_)` or `_.log(_)`."),
             "view": CORE_VIEW + " This happens at each sample (250 ms) and at each log line.",
             "traps": ("`Stream.unfold` needs `chunkSize = 1`: with the default (4096) it buffers lines before they "
                       "emit (comment at DashboardApp.scala:30-31). " + CORE_NEXT_TRAP),
             "entry": "`object DashboardApp extends TuiApp[Dash]`.",
             "checked": CORE_CHECKED}},
        {"variant": V_OZIO,
         "note": ("The same model, keymap and view as the core. The log stream is a `ZStream` and the poller runs a "
                  "ZIO `Random.nextDouble`."),
         "blocks": [block(EX + "ziodashboard/DashboardApp.scala", OZIO, 1, 76)],
         "facts": {
             "effects": ("ZIO `ZStream[Any, Nothing, String]`, `Schedule`, `Random.nextDouble`, `700.millis` (ZIO "
                         "`Duration`). `source.poll` takes a `ZIO[Any, Throwable, A]` "
                         "(modules/zio/src/main/scala/tui4s/interop/zio/Source.scala:89 @ feat/zio). Imports "
                         "`tui4s.interop.zio.*`, `zio.*`, `zio.stream.ZStream`."),
             "state": "As in the core: one immutable `Dash` in the core engine.",
             "event": "As in the core. Pausing interrupts the poller's ZIO (comment at DashboardApp.scala:49-50).",
             "view": "As in the core: whole view rebuilt; jatatui writes the changed cells.",
             "traps": CORE_NEXT_TRAP + " Open finding in HANDOFF: the live-kit settle hangs on tasks that sleep on "
                                       "zio-test's TestClock.",
             "entry": "`object DashboardApp extends TuiApp[Dash]` (a `ZIOAppDefault`).",
             "checked": OZIO_CHECKED}},
        {"variant": V_OCATS,
         "note": ("The same model, keymap and view as the core. The log stream is an fs2 `Stream[IO, String]` and the "
                  "poller runs an `IO[Double]`."),
         "blocks": [block(EX + "catsdashboard/DashboardApp.scala", OCATS, 1, 80)],
         "facts": {
             "effects": ("`IO[Double]`, fs2 `Stream[IO, String]`, `scala.concurrent.duration` (`700.millis`). "
                         "`source.poll` takes an `F[A]` (modules/cats/src/main/scala/tui4s/interop/cats/Source.scala:79 "
                         "@ feat/cats). Imports `cats.effect.IO`, `fs2.Stream`, `scala.concurrent.duration.*`, "
                         "`tui4s.interop.cats.*`."),
             "state": "As in the core: one immutable `Dash` in the core engine.",
             "event": "As in the core. Pausing cancels the poller's IO (comment at DashboardApp.scala:54-55).",
             "view": "As in the core: whole view rebuilt; jatatui writes the changed cells.",
             "traps": (CORE_NEXT_TRAP + " `import fs2.*` makes `text(...)` ambiguous (`fs2.text`); import fs2 names one "
                       "by one (modules/cats/src/main/scala/tui4s/interop/cats/exports.scala:44-45 @ feat/cats)."),
             "entry": "`object DashboardApp extends TuiApp[Dash]` (an `IOApp.Simple`).",
             "checked": OCATS_CHECKED}},
        {"variant": V_HOOKS,
         "note": ("Plain kyo-ui. Block 1 is the app. Block 2 is `Widgets.scala`: the gauge, sparkline and tabs, "
                  "written as text spans in plain kyo-ui (they also render in a browser)."),
         "blocks": [block(RUN + "apps/Dashboard.scala", None, 1, 98),
                    block(RUN + "apps/Widgets.scala", None, 1, 40)],
         "facts": {
             "effects": ("`UI < (Async & Scope)`, `State < Sync`, `Unit < Async`, `Stream[String, Async]`, "
                         "`Fiber.init`, `Loop`, `Async.sleep`, `Random`, `Emit`, `Kyo.foreach`, Kyo `SignalRef`, "
                         "`Signal`, `Chunk`, `Frame`; handlers `Any < Async`. Imports `kyo.*` and `kyo.UI.*`."),
             "state": ("Four Kyo refs in a `State` case class (`tab`, `metrics`, `paused`, `logs`), made by `init` "
                       "(Dashboard.scala:27-33)."),
             "event": ("Keys bubble to `div.onKeyDown` and write `tab` or `paused`. The sampler is a fiber "
                       "(`Fiber.init(sampler(s))`) that writes `metrics` every 250 ms; while paused it skips the "
                       "write (it does not stop). The log stream runs in a second fiber and writes `logs`."),
             "view": (RUNNER_VIEW_RULE + " The gauge and the sparkline are `render` regions over `metrics.map(...)`; "
                      "the log list is `foreachKeyed`; `s.tab.render` rebuilds the overview box when the tab "
                      "changes. Runner REPORT N4: tick 0.33 / 0.62 ms (p50 / p99)."),
             "traps": ("No built-in gauge, sparkline or tabs: the app draws them as text (Widgets.scala). The PTY run "
                       "did not cover the Dashboard (runner REPORT status line). The verifier did not check whether "
                       "the 1-update Dashboard frames are split frames (refutation 2). " + RUNNER_SETTLE_TRAP),
             "entry": "`object DashboardMain extends KyoApp: run(Tui.run(Dashboard.app))` (Dashboard.scala:97-98).",
             "checked": ("Runner REPORT: headless tests use `Dashboard.init`, `tick` and `view` "
                         "(test/Apps.test.scala); no PTY run of the Dashboard.")}},
        {"variant": V_COMP, **none("No version: the runner folder has the Component class only in the Counter.")},
        {"variant": V_ZIO, **none("No version: the facades prototype has Counter and Todo only "
                                  "(`zio/apps/`).")},
        {"variant": V_CATS, **none("No version: the facades prototype has Counter and Todo only "
                                   "(`cats/apps/`).")},
    ],
}

APPS = [COUNTER, TODO, DASHBOARD]

# ---------------------------------------------------------------------------------------------------------------
# claude 2026-10-03: created the integration section data (entry-point glue of the facade apps on the terminal)
# ---------------------------------------------------------------------------------------------------------------
INTEGRATION = {
    "title": "Entry point on the terminal for the facade apps (integration prototype, DONE)",
    "intro": ("The folder `.work/proto/kyoui-integration/` holds the integration prototype. Its REPORT says DONE. "
              "It reuses the runner and facade sources with no change "
              "(`project.scala`: `//> using files`). It adds one `Tui` object per ecosystem (the glue), the mains and "
              "tests. A ZIO or Cats user starts a facade app with one call, `Tui.run(app)` (block 1). Blocks 2 and 3 "
              "are library glue, not app code: they show which types the user meets at the entry point."),
    "blocks": [block(INT + "mains/Mains.scala", None, 1, 22, tag=INT_TAG),
               block(INT + "glue/zio/Tui.scala", None, 32, 77, counted=False,
                     tag="library glue, not app code; " + INT_TAG),
               block(INT + "glue/cats/Tui.scala", None, 35, 62, counted=False,
                     tag="library glue, not app code; " + INT_TAG)],
    "facts": [
        ("What the user calls",
         "ZIO: `Tui.run(app: ZIO[Scope, Nothing, UI]): UIO[Unit]` and `Tui.quit: UIO[Unit]` (glue/zio/Tui.scala:45, "
         "60). Cats: `Tui.run[F[_]](app: Resource[F, UI])(using Async[F]): F[Unit]` and `Tui.runWithQuit[F[_]](app: "
         "F[Unit] => Resource[F, UI]): F[Unit]` (glue/cats/Tui.scala:39, 45). The mains extend `ZIOAppDefault` or "
         "`IOApp.Simple`. Mains.scala has no Kyo import."),
        ("How a handler runs",
         "ZIO: the whole run is inside `AmbientZIOs.run(KTui.run(...))`, so a ZIO handler runs on the runtime of the "
         "caller of `Tui.run` (glue/zio/Tui.scala:52). Cats: a `Dispatcher.parallel[F](await = false)` goes into the "
         "facade's `AmbientDispatcher` (glue/cats/Tui.scala:47-51)."),
        ("Quit",
         "ZIO: `Tui.quit` is one global value; it finds the active run through a `FiberRef` (glue/zio/Tui.scala:35-38, "
         "60-64). Cats: the app gets an `F[Unit]` that quits, as the parameter of `runWithQuit`. Both start one Kyo "
         "fiber that waits for the signal and then calls the runner's `Tui.quit` (`withQuit`). The facade apps have "
         "no quit key; Ctrl-C quits (comment at Mains.scala:8)."),
        ("Run evidence",
         "Logs of the integration agent (read here, not re-run): ZioOnTerminalSuite 6/6 and CatsOnTerminalSuite 6/6 in each of 3 runs (`logs/test-final-c-{1,2,3}.log`), on the runner's headless `TestBackend`; negative controls fail as intended. Real PTY: ZIO Counter 10/10 terminal checks and 22/22 screen checks, `stty -a` after = before (`logs/pty-checklist.log`); the Cats Counter gives the same result. Under pinned CPU contention, 1 of 2 runs failed one Cats test (the runner's first-key race, integration REPORT R2)."),
        ("Known issues (integration REPORT)",
         "A button in a column takes the full width (100 cells). Runner problems found by the integration (not fixed): "
         "R1 an interrupted run leaks about 1.75 MB (region watchers), R2 a first-key race, R3 the output guard misses "
         "scala.Console.out (a println corrupts the screen)."),
    ],
}


# claude 2026-10-03: created fill_counts: the "lines" fact from the counted blocks
def fill_counts(app):
    for v in app["variants"]:
        if "none" in v:
            continue
        parts = []
        total_nb = total_co = 0
        for b in v["blocks"]:
            if not b["counted"]:
                continue
            nb, co = count(b["code"])
            b["nonblank"], b["code_only"] = nb, co
            total_nb += nb
            total_co += co
            parts.append(f"{os.path.basename(b['src'])} {b['a']}-{b['b']}: {nb} ({co})")
        harness = [b for b in v["blocks"] if not b["counted"]]
        text = f"{total_nb} ({total_co})"
        if len(parts) > 1:
            text += ". Per block: " + "; ".join(parts)
        if harness:
            text += ". Test-harness blocks not counted"
        # claude 2026-10-03: added the optional "comparable part" count (a sub-range that matches the old app)
        for label, src, ranges in v.get("extra_counts", []):
            lines = read_lines(src, None)
            code = "\n".join("\n".join(lines[a - 1:b]) for a, b in ranges)
            nb, co = count(code)
            text += f". {label}: {nb} ({co})"
        v["facts"]["lines"] = text + "."


for _app in APPS:
    fill_counts(_app)


# claude 2026-10-03: created md_cell: one table cell (no new line, no bare pipe)
def md_cell(s):
    return s.replace("|", "\\|").replace("\n", " ")


# claude 2026-10-03: created md_app: one app section (variants, code blocks, facts table)
def md_app(app):
    out = [f"## {app['app']}", "", app["intro"], ""]
    for v in app["variants"]:
        out.append(f"### {app['app']}: {v['variant']}")
        out.append("")
        if "none" in v:
            out.append(v["none"])
            out.append("")
            continue
        out.append(v["note"])
        out.append("")
        for b in v["blocks"]:
            out.append("```scala")
            out.append(b["header"])
            out.append(b["code"])
            out.append("```")
            out.append("")
    out.append(f"### {app['app']}: facts")
    out.append("")
    out.append("| Fact | " + " | ".join(VARIANTS) + " |")
    out.append("| --- " * (len(VARIANTS) + 1) + "|")
    by_name = {v["variant"]: v for v in app["variants"]}
    for key, label in FACT_KEYS:
        cells = []
        for name in VARIANTS:
            v = by_name[name]
            cells.append("no version" if "none" in v else md_cell(v["facts"][key]))
        out.append(f"| {label} | " + " | ".join(cells) + " |")
    out.append("")
    return out


# claude 2026-10-03: created md_integration: the entry-point glue section
def md_integration():
    i = INTEGRATION
    out = [f"## {i['title']}", "", i["intro"], ""]
    for b in i["blocks"]:
        out += ["```scala", b["header"], b["code"], "```", ""]
    nb, co = count(i["blocks"][0]["code"])
    out += [f"Block 1 has {nb} non-blank lines ({co} without comment-only lines), for four mains.", ""]
    out += ["| Fact | Integration glue |", "| --- | --- |"]
    for k, v in i["facts"]:
        out.append(f"| {k} | {md_cell(v)} |")
    out.append("")
    return out


HEAD = """<!-- claude 2026-10-03: created this document (side-by-side user-facing code; read only, nothing built or run) -->
<!-- claude 2026-10-03: generated by .work/proto/side-by-side-gen.py; edit the generator, not this file -->
# Side by side: the user-facing code of each design

Status: {status} (2026-10-03). This document is for the user's choice. It gives code and facts. It gives no
recommendation. The code blocks are verbatim copies; the generator `.work/proto/side-by-side-gen.py` copies each line
range from the file (or from `git show <branch>:<path>`) and counts the lines. Nothing was built or run for this
document. Run results come from the REPORTs and from HANDOFF.md, as each table says.

## How to read this document

- One section per app: Counter, Todo, Dashboard. In each section, one subsection per variant. "No version" means
  that the variant has no code for that app.
- The first line of each code block is a comment with the source path, the branch (for git sources) and the line
  range. That comment is not part of the source.
- A block marked "test harness" is test code. It shows how a prototype starts the app when there is no terminal
  entry point. It is not counted as app code.
- Line counts: non-blank lines of the counted blocks. The number in brackets leaves out lines that are only a
  comment (`//`, `/* ... */`, Scaladoc). The apps do not have the same features in all variants: each app section
  says what is different.
- Paths without a branch are relative to the repository root `/home/user/tui4s`. Short paths in the tables
  (for example `Engine.scala:356`) belong to the source folder of that column.

## Variants

| Variant | Source | Model |
| --- | --- | --- |
| Current core (PRs 3-5, being replaced), Kyo | `examples/` @ `origin/feat/core-3-terminal` | Elm style: `init`, `update(state, event): Next[S]`, `view(state): View`, `Keymap`, `sources(state)`. |
| Current core, old ZIO facade | `examples/` @ `origin/feat/zio` | The same model; `TuiApp` is a `ZIOAppDefault`; sources take ZIO values. |
| Current core, old Cats facade | `examples/` @ `origin/feat/cats` | The same model; `TuiApp` is an `IOApp.Simple`; sources take `F` values. |
| kyo-ui + tui4s runner, hooks | `.work/proto/kyoui-runner/apps/` | kyo-ui UI values; state from `Signal.initRef` / `useSignal` in a `for`; handlers write refs. |
| kyo-ui + tui4s runner, Component class | `.work/proto/kyoui-runner/apps/CounterStyles.scala` | A subclass of `Component` with a type member `State`, an `initial` value and `view(state: SignalRef[State]): UI` (`.work/proto/kyoui-runner/src/tui4s/kyoui/Component.scala:52-56`). |
| ZIO facade (generated mirror) | `.work/proto/kyoui-facades/zio/apps/` | A generated copy of the kyo-ui DSL with ZIO types at the effect points. |
| Cats facade (generated mirror) | `.work/proto/kyoui-facades/cats/apps/` | A generated copy of the kyo-ui DSL with `F[_]` types at the effect points. |
| Integration glue (entry point only, DONE) | `.work/proto/kyoui-integration/` | `Tui.run(app)` starts an unchanged facade app on the terminal runner. No app code of its own: its mains appear in the facade subsections, its glue in its own section before "Differences". |

Integration: the folder `.work/proto/kyoui-integration/` did not exist at the start of this work. It appeared while
this document was written. Its REPORT now says DONE. The orchestrator ran the generator again after the integration
finished (about 16:00 UTC) and updated the integration facts (see "Progress log").

Context, for the history only: `.work/proto/pure/REPORT.md` and `.work/proto/mut/REPORT.md` are plans of two
pre-kyo-ui prototypes (own `Signal`, pure handlers or a `Mut[S]` effect). They stopped at the plan stage and have no
app code, so they have no column here.
"""

DIFFS = """## Differences that matter for the decision

Facts only. Each item names its evidence.

1. What the user writes.
   - Current core: one immutable state, `update(state, event): Next[S]`, `view(state): View`, a `Keymap`, and
     `sources(state)` for background work. No component abstraction: the Counter has one number.
   - kyo-ui runner: UI values with signals. State is one or more Kyo refs. Handlers write the refs. A component is a
     function (hooks) or a `Component` subclass. Two independent counters are two calls (`counter("Left")`,
     `counter("Right")`) or two values (`CounterBox("Left")`, `CounterBox("Right")`).
   - Facades: the same kyo-ui model with ZIO or `F[_]` values in the handlers and in the hooks.
2. Effect types in the user's code.
   - Current core Counter (all three ecosystems): no effect type. The Todo and Dashboard of the core use Kyo types
     (`Sync`, `Abort`, `Stream`, `Async`) only for background work.
   - kyo-ui runner: Kyo types in every app, also in the Counter (`UI < Sync`, `SignalRef`, `KyoApp`), and handlers
     typed `Any < Async`.
   - Facades: no Kyo import and no Kyo effect type. Kyo DATA types stay reachable through aliases (facades REPORT
     O2).
3. Where state lives and who owns it.
   - Current core: one value in the engine. Each frame shows one state value.
   - kyo-ui runner: many refs. Hooks: made when the effect runs (top `for`) or at mount (`component(...)`).
     Component class: the runner makes one ref per instance at mount and keeps it by position and class.
   - Facades: refs from `useSignal` that are the same Kyo cell that kyo-ui binds. The facade `Component.mount`
     makes the ref when the app `for` runs; it is not kept by position in a runner.
4. How the screen changes.
   - Current core: the whole view is rebuilt and painted after each change; jatatui writes only the changed cells
     (RenderLoop.scala:181-185 @ feat/core-3-terminal).
   - kyo-ui runner: only the changed regions are repainted. One `+` writes 1 cell, 26 bytes (runner REPORT N4,
     N6). A settle step makes a frame wait for all consequences of an event; it is "very likely" glitch-free, not
     certain (N2). An exact fix needs a change in Kyo (runner REPORT, upstream proposal 1).
   - Latency numbers exist for both, from different machines: old core bench B4 p50 1.56 ms, p99 3.91 ms (macOS);
     runner Counter p50 0.63 ms, p99 1.24 ms (Linux container). The runner REPORT says that this is not a direct
     comparison.
5. Traps.
   - Current core: `Next[S]` is a union, so the result type of `update` must be written (App.scala:95); the Kyo
     `Stream.unfold` chunk size (DashboardApp.scala:30-31); the `fs2.*` import clash (Cats).
   - kyo-ui runner: the lift trap. A handler without the type `Any < Async` compiles and never runs, with no error
     and no warning (Todo.scala:85-94; runner REPORT O5; reproduced by the verifier).
   - Facades: the lift trap fails at compile time with a tui4s message (generated `compiletime.error` overloads).
     Cats only: a foreign `SignallingRef` in a binding setter fails at run time (O3).
6. Widgets and layout.
   - Current core: jatatui widgets (gauge, sparkline, tabs, list, table) through its own `View` DSL.
   - kyo-ui runner: kyo-ui HTML elements with flexbox layout in cells. No built-in gauge or sparkline (the Dashboard
     draws them as text). No scrolling, overlays, mouse or `onKeyUp`; 1 px = 1 cell (runner REPORT N7, N8).
7. Facade parity.
   - Old facades: the Counter text is the same in Kyo, ZIO and Cats except the package and the import (the branch
     also has `examples/src/parity/CounterBody.scala.txt` and `ParitySuite`). The old facades have no Todo.
   - New facades: the ZIO and Cats apps are near copies of each other. They are not the same text as the runner
     apps (other features, ids for the test runner, `update` instead of `updateAndGet`). The new facades have no
     Dashboard.
8. How far each variant was run.
   - Current core: CI green on PR 5 with a Linux terminal job; the macOS expect checklist passed 47/47 (HANDOFF).
     Old ZIO facade: 14 confirmed findings not fixed. Old Cats facade: review not started.
   - kyo-ui runner: headless tests and a real PTY run (Counter and Todo) green on this Linux machine (verifier).
     The Dashboard has no PTY run.
   - Facades: in the facades prototype, a headless test runner only (kyo-ui's own HTML renderer). The integration
     prototype (DONE) runs the unchanged ZIO and Cats Counter and Todo on the terminal runner's headless
     `TestBackend`: 6/6 and 6/6 in each of 3 runs, and a real PTY run of the ZIO and Cats Counter passes.
9. Coverage of the effect points (facades). Drag and drop has no ZIO or Cats form yet: 9 setters are skipped
   (facades REPORT O1). So "ZIO / F[_] types at every effect point" is not yet true.
10. Entry points.
   - Current core: `object X extends TuiApp[S]`. `TuiApp` is a Kyo main, a `ZIOAppDefault` (ZIO) or an
     `IOApp.Simple` (Cats).
   - kyo-ui runner: `object X extends KyoApp: run(Tui.run(app))`.
   - Facades (integration prototype): `object X extends ZIOAppDefault: def run = Tui.run(app)` or
     `object X extends IOApp.Simple: def run: IO[Unit] = Tui.run(app)`. To quit from a handler, ZIO uses the
     global `Tui.quit`; Cats uses the `F[Unit]` that `Tui.runWithQuit` gives to the app.
"""


# claude 2026-10-03: created PROGRESS: the work log of this document (kept by the generator)
PROGRESS = """## Progress log of this document

- 2026-10-03: read HANDOFF.md (top), DECISIONS.md (fifth round), proto/pure/REPORT.md, proto/mut/REPORT.md.
- 2026-10-03: read proto/kyoui-runner (apps, REPORT.md "User-facing API", Hooks.scala, Component.scala) and
  proto/kyoui-facades (zio/apps, cats/apps, Hooks.scala, Headless.scala, REPORT.md). The folder
  proto/kyoui-integration/ did not exist.
- 2026-10-03: read the old examples with `git show` on origin/feat/core-3-terminal (Counter, Todo, Dashboard),
  origin/feat/zio (ziocounter, ziodashboard) and origin/feat/cats (catscounter, catsdashboard). The old ZIO and
  Cats facades have no Todo. The new facades have no Dashboard. The Component class style has only a Counter.
- 2026-10-03: wrote `.work/proto/side-by-side-gen.py`. It copies each range verbatim and counts the lines. It
  writes this file and `side-by-side.json`. Python only; no Scala build, no app run.
- 2026-10-03: an independent check parsed every code block of this file and of the JSON, read the same range
  again with `sed -n` / `git show | sed -n`, and compared the bytes: all blocks equal. A `grep -cv '^\s*$'` spot
  check gave the same line counts.
- 2026-10-03 (about 15:14 UTC): the folder `.work/proto/kyoui-integration/` appeared (the other agent writes it).
  Added its mains to the ZIO and Cats subsections and its glue as its own section. Read its REPORT and logs; I did
  not run its tests.
- 2026-10-03 (15:16 UTC): final run of the generator with status DONE, then the byte check again: all blocks equal
  to the current files, so the integration files had not changed since the copy. The integration REPORT still says
  IN PROGRESS; a later change there is not in this document (run the generator again to refresh it).
- 2026-10-03 (about 16:00 UTC, orchestrator): the integration prototype finished (REPORT: DONE). The line ranges of
  its mains and glue were checked against the final files (unchanged); the integration status and run evidence were
  updated to the final REPORT, and the retracted "empty input gets 0 rows" item was removed.
"""


# claude 2026-10-03: created main: writes SIDE-BY-SIDE.md and side-by-side.json
def main():
    import sys
    status = sys.argv[1] if len(sys.argv) > 1 else "IN PROGRESS"
    md = [HEAD.replace("{status}", status)]
    for app in APPS:
        md.extend(md_app(app))
    md.extend(md_integration())
    md.append(DIFFS)
    md.append(PROGRESS)
    with open(OUT_MD, "w", encoding="utf-8") as f:
        f.write("\n".join(md))

    data = {
        "generated_by": ".work/proto/side-by-side-gen.py",
        "date": "2026-10-03",
        "notes": ("Per variant: `source`, `lines` and `code` hold ALL blocks of the variant (several sources and "
                  "ranges are joined with '; '), and `code` starts each block with the same source comment line as "
                  "SIDE-BY-SIDE.md. `blocks` holds each block alone, with `code` verbatim and no comment line. A "
                  "variant without code has `source`, `lines` and `code` null and a `none` text."),
        "variant_order": VARIANTS,
        "fact_labels": {k: label for k, label in FACT_KEYS},
        "apps": [],
    }
    for app in APPS:
        entry = {"app": app["app"], "intro": app["intro"], "variants": []}
        for v in app["variants"]:
            if "none" in v:
                entry["variants"].append({"variant": v["variant"], "source": None, "lines": None, "code": None,
                                          "none": v["none"], "facts": {}})
                continue
            blocks = []
            for b in v["blocks"]:
                blocks.append({"source": b["src"] + (f"@{b['branch']}" if b["branch"] else ""),
                               "lines": f"{b['a']}-{b['b']}", "counted": b["counted"],
                               "nonblank": b.get("nonblank"), "code_only": b.get("code_only"),
                               "code": b["code"]})
            entry["variants"].append({
                "variant": v["variant"],
                "note": v["note"],
                "source": "; ".join(dict.fromkeys(x["source"] for x in blocks)),
                "lines": "; ".join(f"{x['source']}: {x['lines']}" for x in blocks) if len(blocks) > 1
                else blocks[0]["lines"],
                "code": "\n\n".join(b["header"] + "\n" + b["code"] for b in v["blocks"]),
                "blocks": blocks,
                "facts": {k: v["facts"][k] for k, _ in FACT_KEYS},
            })
        data["apps"].append(entry)
    # claude 2026-10-03: added the integration glue to the JSON
    data["integration"] = {
        "title": INTEGRATION["title"], "status": "DONE", "intro": INTEGRATION["intro"],
        "blocks": [{"source": b["src"], "lines": f"{b['a']}-{b['b']}", "header": b["header"], "code": b["code"]}
                   for b in INTEGRATION["blocks"]],
        "facts": {k: v for k, v in INTEGRATION["facts"]},
    }
    data["differences"] = DIFFS
    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
