# tui4s architecture

<!-- claude 2026-10-20: created: this file from the section "What tui4s is" of AGENTS.md, with a new intro (ADR 0021) -->
This file tells how tui4s works, and the decision records of the docs site
([`docs/content/4.decisions`](docs/content/4.decisions)) give the reasons.
A change of behaviour changes this file in the same pull request.

## What tui4s is

<!-- claude 2026-10-12: changed this section: tui4s is a terminal runner for kyo-ui (C1) -->
tui4s is a terminal runner for [kyo-ui](https://github.com/getkyo/kyo), the UI
library of [Kyo](https://getkyo.io), in Scala 3. A kyo-ui value describes the
screen. tui4s lays it out in terminal cells and paints it with
[jatatui](https://github.com/oyvindberg/jatatui), a Java port of ratatui that
replaces tui-scala. tui4s has no UI DSL of its own: the DSL is kyo-ui. The
runner is the artifact tui4s-runner, "the terminal runner for kyo-ui". The
artifact tui4s-core is the composite of tui4s-runner, tui4s-app (`TuiApp`) and
tui4s-testkit (`Headless`): one dependency gives the three (decision D25,
issue #17). All the artifacts use the same packages (`tui4s`,
`tui4s.internal`, `tui4s.spi`, `tui4s.testkit`).
<!-- claude 2026-10-19: changed: the runner artifact and the composite core (D25) -->

<!-- codex 2026-10-28: created: text updates retain layout ownership without whole-map removal. -->
Incremental layout removes obsolete region ownership before it places a new
item. This also covers text inside a new container. A text node has no child
regions, so a later text change can replace that node directly. It keeps the
same layout and paint rules without scanning every region for removal.

<!-- codex 2026-10-28: created: exact dirty-region membership preserves first-seen order. -->
The dirty region list keeps each exact path once, in first-seen order. A
parent and a child can both be marked. Membership compares path steps only
when their depths match. An already marked path keeps the current world.

<!-- codex 2026-10-22: created: the opt-in diagnostic recorder and its lifetime. -->
The JVM option `tui4s.diagnostics.file` enables a bounded recorder for a
terminal run. The input thread and runner record timestamps and counters;
they record no input text or signal values. A daemon thread outside Kyo
writes the latest snapshot, also while the runner waits in settlement.
The session's release stack stops the recorder. Diagnostics do not change
settlement decisions. Internal input-to-frame-pass timing is distinct from the
PTY test's send-to-correct-screen timing. See
[the diagnostics guide](docs/content/2.guide/14.diagnostics.md).

<!-- codex 2026-10-22: created: bounded settlement and its cooperative limits; codex 2026-10-23: changed: use bounded local completion. -->
The default is `Settle.Local`: a 16 ms active maximum, a 1 ms quiet window,
and two empty observations one scheduler hop apart. It does not probe the
global scheduler. Explicit `Settle.Quiescent` retains its scheduler rule.
Each inbox drain takes a bounded batch. One active time budget covers settlement,
verification and its re-check, including continuous notifications. A
mounting component has a separate bounded wait. Only time blocked waiting
for its messages pauses the active budget; processing those messages does
not restore spent time. The budget cannot interrupt user code or a scheduler
pause. Layout, paint and terminal output are outside this budget.
`Settle.Hops` retains its quiet-window rule without a total time limit.

<!-- codex 2026-10-23: created: background backpressure preserves room for input. -->
Input events and background notices share one bounded inbox. Background
admission waits when its occupancy reaches one quarter of the capacity,
with a maximum of 64 and a minimum of one. The runner wakes waiting
producers after removals and on shutdown. A capacity of one cannot reserve
a separate input slot. Admission and removal preserve channel order;
settlement can defer input while it batches later notifications.

<!-- codex 2026-10-23: created: signal notifications describe current state rather than historical UI values. -->
Watchers post region-change notices. The runner reads the signal's current
value when it consumes a notice. Intermediate signal states can coalesce.
Component startup results still carry their actual content. An input pass
also reconciles current reactive values, from parents to children. It takes
a snapshot of region generations so it skips descendants that a parent
remount replaced. A queued change notice cannot replay an older UI value
over this reconciliation. Same-key component state and hook scopes remain
owned by their existing instances.

<!-- codex 2026-10-22: created: settlement bounds do not guarantee input latency; codex 2026-10-23: changed: distinguish current-state reconciliation from asynchronous effects. -->
Kyo's public signal API has no event-completion barrier. Settlement therefore
batches updates on a best-effort basis. Verification and repair still recover
missed notifications. An update that arrives after the batch closes appears
in a later frame. Slow user code, rendering, terminal output and scheduler
pauses can still delay input. Input correctness, derived-region consistency
and input latency are separate checks.

<!-- cursor 2026-10-28: created: sampled reactive regions keep input off the watcher storm; cursor 2026-10-28: changed: watch two reactive regions; cursor 2026-10-28: changed: the cap is per parent and a slice is 8; cursor 2026-10-28: changed: a crowded parent keeps the input read and drops the watcher fibers; cursor 2026-10-28: changed: a span that wraps a signal counts for the element that contains the span; cursor 2026-10-28: changed: that list is crowded only past the input read. -->
An input frame reads the first 16 reactive children of each parent, every
bound region and every `Foreach`. It also reads a sampled region that holds
the focus. A parent with 2 reactive children or fewer gives each of those
children a watcher fiber. A parent with more reactive children keeps none of
those fibers. A span that only wraps a signal counts as one row of the element
that contains the span. That element is crowded when every child is one such
row and there are more than 16 rows. A shorter list, and a mixed element, keep
one watcher per span. The runner reads one slice of 8 of those regions on a
background frame, and reads their current values. The idle runner waits at most
16 ms before the next sample when sampled regions exist. A background frame
yields when input is already queued, so the input frame paints first. Sampled
regions can trail the event that changed them. See ADR 0023.

| Directory       | sbt id     | Artifact                         | UI DSL                                  | Release tag      | Published |
| --------------- | ---------- | -------------------------------- | --------------------------------------- | ---------------- | --------- |
| `modules/runner` | `runner`  | `io.github.yuri4n::tui4s-runner` | kyo-ui (the terminal runner for kyo-ui: `tui4s.internal`, `tui4s.spi`, `Tui`, the components, the screen types) | `runner-v<semver>` | yes |
| `modules/app`   | `app`      | `io.github.yuri4n::tui4s-app`    | kyo-ui (`TuiApp`, on tui4s-runner)      | `app-v<semver>`  | yes       |
| `modules/testkit` | `testkit` | `io.github.yuri4n::tui4s-testkit` | kyo-ui (`tui4s.testkit.Headless`, on tui4s-runner) | `testkit-v<semver>` | yes |
| `modules/core`  | `core`     | `io.github.yuri4n::tui4s-core`   | kyo-ui (the composite: it depends on tui4s-runner, tui4s-app and tui4s-testkit; its jar holds only the object `tui4s.Core`; its test sources are the integration suites) | `core-v<semver>` | yes |
| `modules/use`   | `use` (`useJVM`, `useJS`) | `io.github.yuri4n::tui4s-use` (JVM and Scala.js) | kyo-ui (composables) | `use-v<semver>` | yes |
| `modules/widgets` | `widgets` (`widgetsJVM`, `widgetsJS`) | `io.github.yuri4n::tui4s-widgets` (JVM and Scala.js) | kyo-ui (widgets, on tui4s-use) | `widgets-v<semver>` | yes |
| `modules/assistant` | `assistant` (`assistantJVM`, `assistantJS`) | none | kyo-ui (the flagship example app: its domain, services, store and UI in `shared`, the interpreters of the JVM in `jvm` and of the browser in `js`) | none | no |
| `examples`      | `examples` | none                             | -                                       | none             | no        |
| `examples/web`  | `assistantWeb` | none                         | kyo-ui (the assistant on the web: a Scala.js app of static files from `modules/assistant`; `scripts/web-build.sh` builds `dist/assistant`) | none | no |
| `docs`          | none       | none (Nuxt Docus site: a guide whose pages lead with examples; `pnpm run build` in `docs` builds it; `MarkdownRegionsSuite` checks each of its Scala blocks; the section "Docs" of CONTRIBUTING.md) | - | none | no |
| `bench`         | none (its own sbt build: `bench/build.sbt`) | none | kyo-ui in its tui4s stack; the other stacks use other libraries (the bench of D10) | none | no |
<!-- claude 2026-10-19: created the row of examples/web; changed: the js part of the assistant (L4) -->
<!-- claude 2026-10-19: created the rows of use and widgets (L2a); changed: the widgets row (L2b) -->
<!-- claude 2026-10-19: changed: the rows runner, app, testkit and the composite core (D25) -->
<!-- claude 2026-10-19: created the row of the assistant (L3) -->
<!-- claude 2026-10-20: changed: the row of docs (L5); changed: the section "Docs" is in CONTRIBUTING.md (ADR 0021) -->
<!-- claude 2026-10-20: created the row of bench (L5) -->
<!-- claude 2026-10-20: changed: the rows of zio and cats are removed (ADR 0014; ZIO and Cats Effect are issues #18 and #19) -->

<!-- claude 2026-10-21: created: the docs theme and the Counter recording on the home page. -->
The docs use a terminal theme in `docs/app/app.css` and the Nuxt UI settings
in `docs/app/app.config.ts`. Dark mode is the default. The reader can select
light mode, and the browser keeps that choice. The home page shows the
Counter recording through `TerminalHero` and the existing `Cast` player.
Its description stays in Markdown. The theme uses local font stacks.

<!-- claude 2026-10-19: changed: the runner, not the core (D25); 2026-10-20: changed: no zio and cats modules (ADR 0014) -->
The runner uses Kyo and kyo-ui (both at `Versions.kyo`). A ZIO and a Cats
Effect integration are planned (issues #18 and #19); no module holds them
yet. The runner renders a kyo-ui
value to cells
(`tui4s.internal.Layout`, `tui4s.internal.Paint`) and follows its signals: a
`Signal`, a `Foreach` or a bound `SignalRef` drives a region of the screen,
and each change gives one frame (`tui4s.internal.Runner`). Keys reach the UI
(`tui4s.internal.Dispatch`): `onKeyDown` handlers bubble from the focused
element up to the root (`stopPropagation` stops them); Tab and Shift-Tab move
the focus, also inside a focus trap, and the arrows move it inside a focus
group; Enter and Space press a button (a click that bubbles to the ancestors'
`onClick` and submits the button's form, as in a browser), toggle a checkbox
or a radio and cycle a select; Enter in a form submits it; a handler in its
event form (`onClick(e => ...)`, `onFocus(e => ...)`, `onSubmit(e => ...)`)
runs as its action form does; a text input edits by grapheme clusters
(Ctrl-A, E, U, K, W too); a bracketed paste is one edit; Ctrl-C and
`tui4s.Tui.quit` end the run. A textarea edits lines that wrap
(`internal.AreaText`). Alt-Enter, Ctrl-J and a reported Shift-Enter insert a
line break in it. A plain Enter submits its form. Outside a form, a plain
Enter inserts a line break. The password, number, range and dropdown inputs
have their own keys. `tui4s.maxLength` limits a text field. The terminal
cursor shows the caret (`internal.Plan.cursor`). `tui4s.Tui.run` runs a UI in
the real terminal: it enters raw mode and the alternate screen
(`internal.TerminalIO`, `internal.Session`), an input thread maps the
crossterm events to kyo-ui keys, pastes and resizes (`internal.EventMapping`),
the runner draws on a crossterm backend, and the release restores the
terminal in every way that a run ends (also from a JVM shutdown hook). The
headless test kit `tui4s.testkit.Headless` runs an app without a terminal: a
test sends keys (`press`, `key`, `char`, `typeText`, `paste`) or writes the
app's `SignalRef`s, and reads `testkit.Screen`. Components come in two styles.
Hooks (`tui4s.Hooks`): a component is a function that returns `UI < Sync` or
`UI < (Sync & Scope)`, with `Signal.initRef` (a piece of state;
`tui4s.initSig(initial)` is its short name in a terminal app), `useHold`, `useFold`,
`useEffect` and `useInterval` (these four start a fiber, but they do not
suspend: they need `Sync & Scope`, not `Async`); a component whose body waits returns
`UI < (Async & Scope)`; `component(key)(body)` is a node that the runner mounts:
the body runs at the mount, in a `Scope` that closes at the unmount, and the
mount goes on while a node of the same call site and key stays at the same
position. The class style (`tui4s.Component`): a `State` type member,
`initial`, `view(state)` and `onMount(state)`; the runner keeps the state
under the position and `stateKey` (the class name by default; override it for
one state per value), so a parent that renders again keeps the state and the
effects (`onMount` runs once per mount), and another class at that position
starts fresh. A component (hook or class) inside a `when` loses its state
when it is hidden and starts fresh when it comes back. The frame of the event
that mounts a hook component waits for its first UI at most
`TuiOptions.pendingTimeout` (2 s, in all), then comes without it; the UI
comes in a later frame. A component node is a kyo-ui `Reactive` whose signal
is in a global weak registry (`internal.ComponentSpec`): the AST of kyo-ui is
sealed.
<!-- claude 2026-10-12: changed these lines: signals drive the screen; the headless kit (C2) -->
<!-- claude 2026-10-13: changed these lines: keys reach the UI; the key methods of the kit (C3) -->
<!-- claude 2026-10-13: changed the button and the event-form sentences (PR #13 review: Codex, Dispatch.scala:180, :204, :401) -->
<!-- claude 2026-10-13: changed these lines: Tui.run in the real terminal (T1) -->
<!-- claude 2026-10-13: changed these lines: the two component styles, the state key, `when`, the pending frame (C4) -->
<!-- claude 2026-10-19: changed: the hooks need Sync & Scope (P1b, A5 e) -->
<!-- claude 2026-10-19: changed: Signal.initRef, useSignal is deprecated (P1b, A5 f); 2026-10-20: changed: useSignal is removed (ADR 0014) -->
<!-- claude 2026-10-19: changed: the textarea and the inputs (L1c) -->
<!-- claude 2026-10-20: changed: initSig, the short name of Signal.initRef (D26) -->
An element with `position` absolute or fixed is a layer out of the flex
flow. kyo-ui's `overlay` and `dropdown` are layers too. The offsets of a
layer place it in its containing block. The flow paints first. Then the
layers paint in the order of their `zIndex`. When a change is under a layer,
the runner paints its rows again, so nothing shows through the layer
(`internal.Layout`, `internal.Paint`). A modal is a layer with kyo-ui's
`focusTrap`. A new element with `focusAuto` gets the focus. With
`focusRestore`, the focus goes back when that element goes. `tui4s.Tui.size`
is the size of the terminal as a signal. A resize writes it in the frame of
the resize. `flexWrap(_.wrap)` breaks the items into lines. `displayNone`
removes an element from the layout and the paint. `Style.Color.variable`
takes its colour from the custom properties of the stylesheet of the run
(`TuiApp.stylesheet`, `internal.CssVars`). A style property that the
terminal does not show gives one warning per name in the log of the run
(`internal.Warnings`). The package Scaladoc of `tui4s` lists the honored and
the ignored properties. `StyleTableTest` keeps the list correct.
<!-- claude 2026-10-19: created this paragraph (L1b) -->
`tui4s.TuiApp` is the main class of an app (`object App extends TuiApp` with
`def ui`, and `options`, `args`, `onExit`): its constructor sets the tui4s
scheduler values (`tui4s.spi.SchedulerDefaults`) and installs the output
capture before the app's own code, and its `main` runs `ui` with `Tui.run`.
The exit code is 0 after a quit (also the key Ctrl-C) and 1 after a failure
(printed after the restore); on `kill -TERM`, `kill -INT`, `kill -HUP` or a
`System.exit`, a shutdown hook interrupts the run and waits at most 2 s for
the restore, and the JVM exits with its own code (143, 130, 129). The
examples (`examples/`) have one main per package. All are `TuiApp`s except
`counterkyo`, a `KyoApp` main of its own; `widgets` is a library with no main.
Each UI has a munit suite on `Headless`.
The flagship app `assistant` (`examples.assistant.AssistantApp`) only wires.
Its domain, services, store and UI are in the module `modules/assistant`: the
shared part uses only kyo-ui, tui4s-use and tui4s-widgets, and the `jvm` part
has the interpreters of the JVM (kyo-http, the keys, the files).
<!-- claude 2026-10-19: created this paragraph (L3) -->
<!-- claude 2026-10-13: created these lines (T2, RC-24, OS-47 part): TuiApp and the examples -->
<!-- claude 2026-10-13: changed the last sentence (T2, review R1-4): counterkyo is a KyoApp; widgets has no main -->
<!-- claude 2026-10-19: changed: kill -HUP and 129 -->
An element with an `overflow` axis that is not `visible` is a viewport
(`internal.Layout.arrangeKids`, `internal.Scroll`). A viewport clips its
content. On that axis, the content has its natural size. With `scroll` or
`auto`, the viewport scrolls with the keys of the table below. It is a tab
stop when its content does not fit and it holds no focusable element
(`Layout.isFocusable`); a focused viewport draws its track heavy. The
follow-focus rule scrolls each viewport around the focus the least distance
that shows it. The rule runs when the focus or its `aria-activedescendant`
moves, and after a key or a paste on the focused element (D13: kyo-ui's
`scrollIntoView` is private). A viewport shows a scroll indicator when its
content is taller than a port of 3 rows or more. `World.scroll` keeps the
offset of each viewport by `Loc`. Each layout clamps the offset, and an
unmount resets it. A scroll lays out the viewport in place (`World.scrolled`):
its kids move, and they are not laid out again.
The text flow (`internal.TextFlow`) honours `whiteSpace`, `textWrap` (CSS
`overflow-wrap`) and `textOverflow`. The ellipsis ("…" at the cut) needs
`overflow: hidden` on the element that holds the text. A text wraps in a
column and in a row of a definite `width`. The row wrap is a change of the
default: the old layout never wrapped a text in a row. A row lays out such a
text at its final width (`Layout.settle`), so no line is lost.
Every measure that places a glyph counts cells (`Layout.textWidth`). This
includes the caret and the scroll of a text input, the labels of the pickers
(`Paint.fit`), the wrap of a long word and the ellipsis. Thus a wide character
is never split.

| Key (no modifier) on the focused element or inside the viewport | Scroll |
| --------------------------------------------------------------- | ------ |
| ArrowUp, ArrowDown                                              | one row |
| PageUp, PageDown                                                | one port height |
| Home, End                                                       | to the top, to the bottom (to the left, to the right when only the horizontal axis scrolls) |
| ArrowLeft, ArrowRight                                           | one column (only when the viewport scrolls horizontally) |

The innermost viewport of the focus chain that can move scrolls. There is no
scroll when the focused element uses the key (a text input: the arrows, Home
and End; a select: ArrowUp and ArrowDown; a focus group: ArrowLeft and
ArrowRight) or when an element of the chain has kyo-ui's `preventScrollKeys`.
With no focus (`autoFocus = false` before the first Tab), a key scrolls
nothing.
<!-- claude 2026-10-19: created the sentences, the key table and its notes above (L1a; L1a review: short sentences, the tab stop, the row wrap, the scroll in place, no focus): scroll, text flow, wide characters -->
