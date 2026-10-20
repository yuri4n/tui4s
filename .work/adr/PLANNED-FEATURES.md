# Planned and deferred features (GitHub issue drafts)

Each block below is one GitHub issue for yuri4n/tui4s: a title (at most 70 characters), the labels, and the body. The text uses ASD-STE100. The source ids (D-n, Dn, L1c, ...) are the ids of the working notes, which leave the repository; each body says what the notes said. Existing issues: #8 and #9 (Kyo signal TODOs, open), #17 (closed).

Labels used: `enhancement`, `upstream-kyo`, `docs`, `bench`, `packaging`, `help wanted`.

## Part 1: the items that the session asked for

### 1. Add a ZIO DSL module (tui4s-zio) over kyo-ui

Labels: `enhancement`, `help wanted`

```
ZIO users must write a tui4s app with no Kyo type in their code.
The module tui4s-zio was removed before 0.1 because it had no source (ADR 0014).
Design (decision log, sixth round): a hand-written ZIO DSL that mirrors the kyo-ui DSL, with ZIO types at every effect point; no code generator.
A spike picks the state design first: Z1 own ref type, Z2 SubscriptionRef with redirected writes, or Z3 SubscriptionRef with a one-way copy.
Prior work on the old DSL (not kyo-ui): branch feat/zio at 0c8c00d (AmbientZIOs for the runtime problem F12, LiveTuiTest).
Done when: tui4s-zio depends on tui4s-runner; a coverage test fails when kyo-ui has a setter that the DSL lacks; the ZIO Counter and Dashboard run in the PTY kit; the release script and release.yml accept zio-v*.
```

### 2. Add a Cats Effect DSL module (tui4s-cats) over kyo-ui

Labels: `enhancement`, `help wanted`

```
Cats Effect users must write a tui4s app with no Kyo type in their code.
The module tui4s-cats was removed before 0.1 because it had no source (ADR 0014).
Design (sixth round): a hand-written F[_] DSL in the shape that the ZIO spike picks; it starts after the ZIO module.
Kyo removed its Typelevel modules (getkyo/kyo#1778). tui4s vendors the Apache-2.0 kyo-cats bridge of RC5, adapted to RC7, with attribution.
Open question (thirteenth round, parked): bridge through ZIO and zio-interop-cats instead of a vendored bridge.
Prior work on the old DSL: branch feat/cats at 9f4cc05 (CatsBridge and its suite; AmbientDispatcher: a Cats Effect Dispatcher must use await = false).
Done when: tui4s-cats depends on tui4s-runner; a coverage test as in the ZIO module; a Cats Counter gives the same screen as the Kyo Counter for the same keys.
```

### 3. Mouse input (click, wheel) as an opt-in

Labels: `enhancement`

```
tui4s drops kyo-ui mouse events today (decision D-30: no mouse for 0.1).
Reason: mouse capture stops the text selection of the terminal; the research ranked mouse last (1 of 28 common widgets needs it).
Done when: an app can opt in to mouse capture; a click runs onClick on the element under the cell; the wheel scrolls the viewport under the pointer; text selection still works when the app does not opt in; PTY rows check it.
```

### 4. A ui"""...""" template interpolator (intent only)

Labels: `enhancement`, `help wanted`

```
Decision D9: the template layer is intent only; there is no spike now (budget).
Design if it comes back: a Scala 3 quotes macro that parses the literal at compile time and expands to kyo-ui builder calls, with ${...} holes, errors at the right position, Metals support first, in experiments/scalax.
XML literals are a dead end: Scala 3 drops them.
Open: the output is runner-neutral kyo-ui, so the layer may belong upstream or in its own repository.
Done when: a spike shows the Counter and the Todo as templates, with compile errors at the hole position, and a decision on where the layer lives.
```

### 5. Benchmarks stage 2: Form, Todo, Log viewer, Chat, Cells

Labels: `bench`, `help wanted`

```
Stage 1 of bench/ is Counter (T1) and Dashboard (T4) (ADR 0013). Stage 2 adds five rosetta tasks:
T2 Form with validation (5 fields, errors after blur or submit, a 1 KB paste cut to 40 chars).
T3 Todo with persistence (atomic debounced save, a corrupt file kept as .bak, SIGTERM flushes the pending save).
T5 Log viewer with 100 000 lines (2% CJK or emoji, search, level filters, follow at 1000 lines/s).
T6 Chat with streaming tokens (mock SSE server at 50 tokens/s; Ctrl-C cancels; disconnect within 500 ms).
T7 Cells (7GUIs: formulas, ranges, #CYCLE).
Done when: each task has a JSON acceptance scenario, a tui4s implementation, and at least the Stage 1 competitors that can do it, with a change request measured per task.
```

### 6. Kitty keyboard protocol, so Shift-Enter is reported

Labels: `enhancement`

```
A textarea takes Alt-Enter and Ctrl-J for a new line; Shift-Enter works only where the terminal reports Shift (decision D7).
Most terminals report Shift with Enter only after the keyboard-enhancement flags are pushed; tui4s pushes no flags (L1c open item; deferred in L5b).
Done when: the runner pushes the flags where the terminal supports them, pops them in the release stack on every exit path, and the PTY kit checks Shift-Enter in a terminal that reports it.
```

### 7. A supported Windows port

Labels: `enhancement`, `help wanted`

```
Windows is experimental (decision D12). The crossterm jar ships a Windows x86_64 library, but CI and the PTY kit run only on Linux, and the Nix shell covers Linux and macOS.
On Windows the JNI copy of jatatui cannot be deleted while it is loaded, so one folder stays in the temp directory per start.
Done when: CI runs the test suites on Windows; a real-terminal check runs on Windows (PROPOSAL: ConPTY); the start scripts work in PowerShell and cmd.
```

### 8. A unit scale for tui4s-widgets on the web

Labels: `enhancement`

```
The widgets give sizes in em (rows, cells) and gaps in px. The terminal gives one cell to each em and each px.
On the web 1em is the font size, a line is about 1.2em and a cell about 0.6em, so a ListView or a Pager clips about one line in five, and a 1px gap is one CSS pixel (L4 open item; tui4s.widgets package Scaladoc).
Done when: a widget takes its sizes through one unit scale (PROPOSAL: a Surface-like value with rows(n) and cols(n), as the assistant does), and the gallery looks the same in the terminal and on the web with no clipped line.
```

### 9. RadioGroup: a click handler on the web

Labels: `enhancement`

```
tui4s.widgets.RadioGroup has no click handler, so on the web it works only with the keyboard (L4 open item).
Done when: a click on an option selects it on the web, the terminal behaviour does not change, and a JS test covers it.
```

### 10. Upstream (kyo-ui JS): keydown on a field in a nested region is lost

Labels: `upstream-kyo`

```
Found in L4 (the web assistant). Draft to post at getkyo/kyo; the user posts it after a duplicate search.
Workaround in tui4s: none recorded; the README lists the effect (Esc does not close the rename prompt while its field has focus).
Done when: the issue is posted and linked here; the workaround goes away when kyo-ui fixes it.
```

Draft to post (the notes give only a one-line finding; write the reproducer before posting):

```
Title: kyo-ui (Scala.js): a keydown on a field inside a nested reactive region does not run the field's handlers

Version: io.getkyo::kyo-ui 1.0.0-RC7, Scala.js, UI.runMount.

What happens: a DOM keydown on a field (an input or a textarea) that is inside a nested reactive region does not run the onKeyDown handler of that field. The same field outside the nested region runs its handler.

Effect in our app: a rename prompt closes on Esc through the onKeyDown of its field. When the field has the focus, Esc does nothing.

Expected: the handlers of the field run, then the event bubbles, as for a field outside a nested region.

Reproducer: TO WRITE. Suggested shape: an input with onKeyDown inside a Reactive region that is itself inside another Reactive region, mounted with UI.runMount; type Esc in the input.
```

### 11. Upstream (kyo-ui JS): a handled keydown cannot prevent the default

Labels: `upstream-kyo`

```
Found in L4. Draft to post at getkyo/kyo.
Workaround in tui4s: the web assistant gives every command an Alt chord, because the browser keeps Ctrl-S, Ctrl-P, Ctrl-R, Ctrl-J and F1 (README "The same app on the web").
Done when: the issue is posted and linked here.
```

Draft to post:

```
Title: kyo-ui (Scala.js): no way to call preventDefault on a handled keydown

Version: io.getkyo::kyo-ui 1.0.0-RC7, Scala.js.

What happens: an onKeyDown handler runs, but kyo-ui does not call preventDefault on the DOM event. So the browser also acts on the key: Ctrl-S saves the page, Ctrl-P prints, Ctrl-R loads the page again, Ctrl-J opens the downloads, F1 opens the help (Windows and Linux).

Effect: an app cannot give a command a Ctrl chord on the web, also when its handler handles the key.

Request (any one): a declarative attribute that prevents the default of the keys that a region handles (as preventScrollKeys does for the navigation keys); or a member of KeyboardEvent that a handler calls; or a handler result that says "handled".
```

### 12. Upstream (kyo-ui JS): every Enter submits the enclosing form

Labels: `upstream-kyo`

```
Found in L4. Draft to post at getkyo/kyo.
Workaround in tui4s: the web composer of the assistant is outside a form; Enter runs send, and Shift-Enter keeps the line break of the browser (AssistantUi.scala, the composer Scaladoc).
Done when: the issue is posted and linked here.
```

Draft to post:

```
Title: kyo-ui (Scala.js): Enter in a textarea (also Shift-Enter) submits the enclosing form

Version: io.getkyo::kyo-ui 1.0.0-RC7, Scala.js.

What happens: kyo-ui submits a form on each Enter in a field of the form, also on Enter and Shift-Enter in a textarea.

Expected (as in a browser): Enter in a textarea inserts a line break and does not submit the form. Enter in a one-line input submits it.

Effect: a chat composer (a textarea) cannot be in a form.

Reproducer: TO WRITE. Suggested shape: form.onSubmit(...)(textarea.value(ref)); type a line, then Shift-Enter; the submit handler runs.
```

### 13. Upstream (kyo-ui): a Stylesheet cannot override inline styles

Labels: `upstream-kyo`

```
Found in L4 (the corners of tui4s.widgets.Panel on the web). Draft to post at getkyo/kyo.
Workaround in tui4s: not recorded in the notes.
Done when: the issue is posted and linked here.
```

Draft to post (the notes give only a one-line finding; confirm the cause before posting):

```
Title: kyo-ui: a Stylesheet rule cannot override the style that an element sets with .style(...)

Version: io.getkyo::kyo-ui 1.0.0-RC7 (Scala.js; UI.runStylesheet).

What happens: an element's .style(...) goes into the DOM as an inline style. A rule of a Stylesheet that selects the same element cannot change that property, because an inline style wins over a rule.

Effect: a theme (one Stylesheet) cannot restyle a widget that sets a default with .style(...), for example the corners of a box.

Request (any one): render .style(...) as a generated class, not inline; or let a Stylesheet entry be important; or document the rule and a pattern for themable defaults.
```

### 14. The terminal cursor shows under a non-trapping overlay

Labels: `enhancement`

```
The hardware cursor follows the caret of the focused input (Plan.cursor). When a layer without focusTrap covers that input, the cursor still shows on top of the layer (L1c open item).
Done when: the cursor hides when a layer covers the caret cell, and a golden test plus a PTY row check it.
```

### 15. Honour inputFilter and inputMask on text inputs

Labels: `enhancement`

```
kyo-ui RC6 added inputFilter and inputMask (opt-in). tui4s ignores both today (spike S3; L1c open item).
Done when: a typed or pasted character that the filter rejects is not inserted; a mask places its literal characters; the package Scaladoc table and StyleTableTest list them as honoured.
```

### 16. One-line inputs: onChange on each edit, not only on Enter

Labels: `enhancement`

```
A one-line text input runs onChange only on Enter today (L1c open item). Check the rule of kyo-ui's DOM runner for onChange and onInput first.
Done when: tui4s runs the same handlers at the same moments as kyo-ui's DOM runner, with a test for each handler form.
```

### 17. List scroll is O(rows): translate a clean viewport

Labels: `enhancement`

```
A scroll of a long list still costs O(rows) per frame (PF-b open item). A pure translate of a clean viewport would fix it. A log append also re-places the forced subtree.
Measure with the PerfProbe stress probe before and after (list of 2,000 rows; log).
Done when: a scroll of a clean viewport moves its laid-out rows without a new layout of its kids, the oracles agree, and PerfProbe shows the gain.
```

### 18. Toasts and Spinner render again on each tick

Labels: `enhancement`

```
tui4s.widgets.Toasts and Spinner render again on each tick, also when nothing visible changes (L2b open item).
Done when: a tick that changes no visible state gives no frame (FrameStats), with a test for each widget.
```

### 19. Bench: a person of each ecosystem reviews each implementation

Labels: `bench`, `help wanted`

```
The comparison is run by the tui4s authors (vendor bias). Competitor code written by people outside an ecosystem can be unidiomatic (bench research of 2026-10-17).
Done when: a practitioner or maintainer of each competitor (ratatui, Bubble Tea, Textual, Ink, TamboUI, layoutz) reviewed its implementation, or the results page says that it was not reviewed; outside pull requests are welcome; competitor facts from search summaries are checked before they are published.
```

### 20. Docs: document the provider defaults of the assistant

Labels: `docs`

```
The assistant opts in to the Anthropic server-side fallbacks for its default model (header anthropic-beta: server-side-fallback-2026-07-01, field fallbacks: "default"; Anthropic.fallbacks in Wires.scala). When a safety rule declines a request, the API runs it again on another model in the same stream.
The default model ids (Models in Settings.scala) are data that nobody checked against the providers; effort and thinking are not sent; the run log file gets the umask mode.
Done when: the docs page of the assistant states each choice and how to change it.
```

### 21. Assistant: split Surface into data and a KeyVault capability

Labels: `enhancement`

```
The service Surface mixes data (units, chords, the first look) and a capability (the key vault of the browser). A review of L4 deferred this finding.
Done when: Surface holds only data, and the key vault is its own service, given through a layer on the web and absent in the terminal.
```

## Part 2: other open items found in the notes

### 22. Upstream (kyo-ui JS): bound inputs lose keys typed fast

Labels: `upstream-kyo`

```
Decision D13 (draft only). Research of 2026-10-17 (Scala.js, headless Chromium): an input bound with .value(SignalRef) loses keystrokes when keys come 10 ms or less apart, mostly in the first seconds after load; at 25 ms or more there were no losses. Rarely, the field keeps its text after Enter when the submit comes about 0 ms after the input event.
The spike page and its Playwright probe are copied in Appendix E.
Workaround: fewer bound round trips (read the text on submit, or debounce).
Done when: a draft with this reproducer is posted and linked here.
```

### 23. Upstream (kyo-ui JS): a bound value is in the DOM value attribute

Labels: `upstream-kyo`

```
Found by the L4 review (it was blocking): kyo-ui writes the value of a field bound with .value(ref) into the value attribute of the DOM, in clear text. For a password or an API key this exposes the secret to anything that reads the DOM.
Workaround in tui4s: the key field of the web assistant is not bound; its input events write a draft.
Done when: a draft is posted and linked here.
```

### 24. Upstream (kyo-ui): a cell or ch length unit

Labels: `upstream-kyo`

```
Decision D13 (draft only). px is one cell in tui4s and one CSS pixel on the web; kyo-ui has no ch, lh or cell Length and no Length variable.
Workaround: em and pct in shared views; Surface.rows and Surface.cols in the assistant.
Done when: a draft is posted and linked here.
```

### 25. Post the upstream drafts kept in the notes

Labels: `upstream-kyo`

```
Three drafts are written and not posted. Their full text is in Appendix A, B and C of this file.
A: kyo-kernel 1.0.0-RC7 has JMH in compile scope (getkyo/kyo). Workaround: the JMH exclusion in every module.
B: a runner outside kyo cannot honour UI.scrollIntoView (getkyo/kyo). Workaround: the own follow-focus rule.
C: jatatui NativeLoader leaves one /tmp/jni-* folder per start (oyvindberg/jatatui). Workaround: internal.NativeCopy (Linux only).
Also: add the two drafts of Appendix D as comments to #8 and #9, so they survive the removal of the notes.
Done when: each draft is posted by the user and linked here, or closed as a duplicate.
```

### 26. Viewports: stick to the bottom

Labels: `enhancement`

```
A viewport has no stick-to-bottom mode (L1a open item). A log or a chat transcript must scroll by hand to the newest line.
Done when: a viewport that shows its last row keeps showing it when content grows, and stops following when the user scrolls up, with a test.
```

### 27. A wide glyph cut by the right edge of a clip

Labels: `enhancement`

```
P5 open product question: a wide glyph (2 cells) cut by the right edge of a clip keeps the old cell, as the old painter did.
Done when: the rule is decided (blank the half cell, or keep it) and pinned by a golden test and the CanvasPropertyTest law.
```

### 28. useProcess holds a scheduler thread while it reads

Labels: `enhancement`

```
tui4s.use.useProcess reads the stdout of the child with a blocking read, which holds a Kyo scheduler thread (L2a open item). Also: LocalStorage (JS) is untested on Node (no window), and Lines.split ends lines only at LF.
Done when: the read does not hold a scheduler worker; LocalStorage has a test with a fake window; Lines.split handles CRLF, each with a test.
```

### 29. Tui.run: drop the promise copy of the run result

Labels: `enhancement`

```
D24 (open for the user): the AGENTS.md rule "Never join the run fiber from an interruptible wait" rests on a premise that Kyo RC7 no longer has (IOTask gives up a join on an interrupt). Tui.run keeps a promise copy of the run result only as a second guard; no test needs it.
Done when: the user decides; then either the copy goes and Tui.run uses fiber.get with the rule reworded, or the rule and the copy stay with this reason in the Scaladoc.
```

### 30. More widgets: form validation, file picker, Markdown, calendar

Labels: `enhancement`, `help wanted`

```
L2b listed later widgets: form validation, a file picker, a Markdown viewer, a calendar popup, a line chart, a chat transcript (the assistant has one of its own).
Done when: each widget follows the widget rules of AGENTS.md (typed props, Model, Colors, a mark, module tests on the JVM and Node.js) and has a place in the gallery and the README.
```

### 31. Web: aria-activedescendant does not scroll the list

Labels: `enhancement`

```
In the terminal, a viewport follows the element named by aria-activedescendant. On the web it does not scroll (L2b open item); the fix may need UI.scrollIntoView (see item 25, draft B).
Done when: a ListView on the web keeps its selection in view, with a JS test or a smoke check.
```

### 32. Run the web smoke test in CI

Labels: `enhancement`

```
scripts/web-smoke.mjs (Playwright, headless Chromium) passes locally but is not in CI (L4).
Done when: a CI job links the web assistant and runs the smoke test (a send, the streamed reply, Esc, the look switch).
```

### 33. Codex app-server as a provider of the assistant

Labels: `enhancement`

```
Decision D4: the providers are Anthropic and OpenAI API keys and a fake provider first; the user's own Codex CLI signed in with ChatGPT (codex app-server) second. It is not built.
The rules of OpenAI for this path came from search summaries only; check them before the work starts.
Done when: a ChatProvider interpreter drives codex app-server, with the same tests as the other providers (in-memory fixtures).
```

### 34. Inline viewport (Viewport.Inline)

Labels: `enhancement`

```
Decision D-22 (a): the inline viewport is deferred. It needs new Canvas code (the runner draws on the Backend, not on a jatatui Terminal), and no user asked for it.
Done when: an app can run in N rows below the prompt with no alternate screen, and the restore leaves the last frame in the scrollback, with PTY rows.
```

---

## Appendix A: draft, kyo-kernel JMH in compile scope (getkyo/kyo)

```
Title: kyo-kernel 1.0.0-RC7 has JMH in compile scope

Summary
The POM of io.getkyo:kyo-kernel_3:1.0.0-RC7 lists three JMH artifacts as compile dependencies (no <scope>). So every user of Kyo gets JMH and its dependencies on the runtime classpath, about 3.0 MB that no run loads. The POMs of RC5 and RC6 have no JMH dependency, so this looks like a regression of the RC7 build.

The dependencies (from kyo-kernel_3-1.0.0-RC7.pom)
org.openjdk.jmh:jmh-core:1.37, org.openjdk.jmh:jmh-generator-bytecode:1.37, org.openjdk.jmh:jmh-generator-reflection:1.37.
Through them come jmh-generator-asm:1.37, commons-math3:3.6.1, asm:9.0 and jopt-simple:5.0.4.

| Jar | Bytes |
| --- | ---: |
| jmh-core-1.37 | 552,986 |
| jmh-generator-bytecode-1.37 | 18,517 |
| jmh-generator-reflection-1.37 | 24,518 |
| jmh-generator-asm-1.37 | 31,641 |
| commons-math3-3.6.1 | 2,213,560 |
| asm-9.0 | 121,783 |
| jopt-simple-5.0.4 | 78,146 |
| Total | 3,041,151 |

Evidence that nothing needs them at run time
- No class in kyo-kernel_3-1.0.0-RC7.jar refers to org/openjdk/jmh (a byte search of the extracted jar finds nothing).
- A class-load log (-Xlog:class+load) of two kyo-ui apps (a counter and a dashboard, about 4,100 to 4,500 classes each) shows no class from these seven jars.
- tui4s excludes org.openjdk.jmh from kyo-core and kyo-ui (excludeAll(ExclusionRule("org.openjdk.jmh"))): the build compiles, and the test suites of the library and of its example apps pass.

Proposed fix
In the build of kyo-kernel, put the JMH dependencies in Test (or Provided) scope, or move them to the benchmark project only. Users can then drop their exclusions.
```

## Appendix B: draft, a runner outside kyo cannot honour UI.scrollIntoView (getkyo/kyo)

```
Title: A runner outside kyo cannot honour UI.scrollIntoView

tui4s is a terminal runner for kyo-ui: it lays out a UI value in terminal cells and dispatches keys.

Summary
UI.scrollIntoView(id) is public, but the only way to receive it is the session sink kyo.internal.UICommands.scrollSink, which is private[kyo]. A runner that is not in package kyo (a terminal runner, a test kit, a native UI) cannot install a sink, so a handler that calls UI.scrollIntoView is a no-op there, with no error. We ask for a public way to receive the request, or for a declarative attribute that a runner can read.

Version: io.getkyo::kyo-ui:1.0.0-RC7 (JVM and Scala.js)

What happens (from the sources of 1.0.0-RC7)
1. UI.scrollIntoView(id) calls UICommands.scrollIntoView(id).
2. UICommands is private[kyo] object UICommands, and its scrollSink: Local[Maybe[String => Unit < Async]] is private[kyo].
3. UIServer and DomBackend (both in kyo.internal) bind the sink for their session; outside a session the call does nothing.
4. A runner outside kyo cannot bind scrollSink: the compiler rejects the access, and the only way around it (a source file in package kyo, or reflection) is not an option for a library that uses only public APIs.

What tui4s does today
tui4s implements the browser's focus rule itself (the CSSOM "scroll into view" with block: nearest):
- An element with overflow (or overflowX, overflowY) scroll or auto is a viewport that scrolls; hidden clips and does not scroll.
- When the focus moves, each viewport that holds the focused element scrolls the least distance that shows the element's border box, the innermost first. A box that fits shows whole; a box larger than the viewport covers it.
- After a key or a paste on the focused element (a text input that the user types in), the same rule keeps it in view.
- A list that keeps the focus and moves a selection names the selected element with aria("activedescendant", id); the viewport follows that element. This is the WAI-ARIA pattern, and it is the only declarative way today for an app to say "show this element" to tui4s.
So an app can show the focused element, but it cannot ask for any other element: UI.scrollIntoView("row-42") from a handler has no effect in the terminal, while the same code scrolls in the browser.

What would help (any one of these)
1. A public sink: for example UI.withScrollSink(sink: String => Unit < Async)(v: A < S): A < S, or a public Local that a runner binds around the handlers of its session. The rest of UICommands can stay internal.
2. A public command stream for the custom transports that UI.runRender already serves ("useful for testing, SSR, export, or custom transports"): the imperative commands of a session (scrollIntoView today) as values that a runner reads.
3. A declarative attribute or style that a runner can read from the AST, for example scrollIntoViewOnRender or scrollTarget(true): "keep this element in view when it renders". A runner then needs no sink, and the request is visible in the AST (Attrs already has focusAuto for the same kind of one-shot intent).
With 1 or 2, tui4s would call its follow rule with the element that has the id; with 3, it would treat the attribute as one more scroll target, next to the focus and aria-activedescendant.

Related
preventScrollKeys (data-kyo-scroll-keys) is readable through Element.attrs, and tui4s honours it: the navigation keys do not scroll in such a region. That works because it is declarative; scrollIntoView is not.
```

## Appendix C: draft, jatatui NativeLoader leaves a /tmp/jni-* folder per start (oyvindberg/jatatui)

```
Title: NativeLoader leaves one /tmp/jni-* directory on each start

(The loader's own error message points to tui-scala; the class is in the crossterm jar of jatatui.)

Summary
tui.crossterm.NativeLoader copies the crossterm library out of the jar into a new temporary directory on each start of the JVM (or of a GraalVM native image), and nothing deletes it. Each run of an app leaves one directory of about 900 KB in java.io.tmpdir.

Version
- com.olvind.jatatui:crossterm:0.31.0 (and jatatui-crossterm:0.31.0)
- JDK 25 (Linux x86_64); also seen with a GraalVM 25 native image

What happens (from the bytecode of NativeLoader)
1. CrosstermJni's static initializer calls NativeLoader.load("crossterm").
2. load calls System.loadLibrary("crossterm"). The library is not on java.library.path, so this fails with UnsatisfiedLinkError.
3. loadPackaged reads the resource /libnative-<platform>-crossterm.so (.dylib, .dll), calls Files.createTempDirectory("jni-"), copies the resource into it, and calls System.load on the copy.
4. Nothing deletes the file or the directory: no deleteOnExit, no cleanup.

Measured
- One new directory per start: 904 KB (918,000 bytes for libnative-x86_64-linux-crossterm.so), with the JVM and with a native image.
- On one development machine: 753 directories; 532 of them came from one afternoon of start-up measurements (about 470 MB).
- In one native-image session, the count went from 169 to 244.

Reproduction
  ls -d /tmp/jni-* 2>/dev/null | wc -l     # n
  # start and quit any app that uses jatatui-crossterm, e.g. a jatatui demo
  ls -d /tmp/jni-* 2>/dev/null | wc -l     # n + 1

Proposed fix (one of)
1. Stable cache path. Extract once to a path keyed by the content, for example ${XDG_CACHE_HOME:-~/.cache}/jatatui/<version>-<sha256 prefix>/ or ${java.io.tmpdir}/jatatui-<user>-<sha256 prefix>/. Create the directory with owner-only permissions, write to a temporary file in it and rename it atomically, and reuse the file when its hash matches. Fall back to the current behavior when the directory is not writable.
2. Delete after the load (POSIX). On Linux and macOS, a loaded library stays mapped after its file is deleted. Delete the copy and its directory right after System.load returns. On Windows a loaded DLL cannot be deleted; there deleteOnExit on the file and the directory is the smallest fix, or option 1.
3. A system property (for example tui.crossterm.libpath) that names a library file to System.load, checked before the extraction, so that packagers can ship the library outside the jar.

Workaround in tui4s
tui4s (tui4s.internal.NativeCopy) does option 2 from the outside on Linux: after the load it finds the copy that the process loaded in /proc/self/maps and deletes it and its directory. On macOS and Windows the copy still stays.
```

## Appendix D: drafts for #8 and #9 (Kyo Signal, getkyo/kyo)

Post each as a comment on the tui4s issue first, so the text survives the removal of the notes. The user posts them upstream after a duplicate search (getkyo/kyo issues: "observe memory leak race", "combineLatest lost wakeup nextWith"). Check the newest Kyo release first; the numbers are from Kyo 1.0.0-RC7 on JDK 25 (Linux, 4 CPUs).

### D1 (for #8): Signal.observe retains about 1.7 KB per value change

```
Title: Signal.observe retains ~1.7 KB per value change until the observing fiber ends (OOM in a long-lived watcher)

Version: kyo-core 1.0.0-RC7, Scala 3.9.0, JDK 25 (Linux x86_64).

What happens. A single ref.observe(f) watcher that runs for a long time keeps memory on every value change. The growth is linear, about 1.7 KB per change, and it is only released when the observing fiber ends. With -Xmx48m, one watcher dies with OutOfMemoryError after 20 000 to 30 000 changes.

What I expected. The Scaladoc of observe says: "no waiter or fiber accumulates across changes". A watcher that lives for the whole life of an application (a UI binding, a metrics exporter) should run at a flat heap.

Reproducer (scala-cli; one ref, one watcher; each write waits until the watcher saw it, then pauses 200 µs so the watcher re-arms before the next write):

//> using scala 3.9.0
//> using dep io.getkyo::kyo-core:1.0.0-RC7
import kyo.*

object ObserveLeak:
    def usedKb(): Long =
        val rt = java.lang.Runtime.getRuntime
        (1 to 3).foreach(_ => { java.lang.System.gc(); Thread.sleep(50) })
        (rt.totalMemory - rt.freeMemory) / 1024

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val n = args.lift(0).map(_.toInt).getOrElse(30000)
        val r = Abort.run[Any](Sync.Unsafe.run(KyoApp.runAndBlock(1.hour) {
            for
                ref  <- Signal.initRef(0)
                seen <- AtomicInt.init(0)
                _    <- Fiber.initUnscoped(ref.observe(v => seen.set(v)))
                k0   <- Sync.defer(usedKb())
                _ <- Kyo.foreachDiscard(1 to n) { i =>
                    ref.set(i)
                        .andThen(Loop.foreach(seen.get.map(s => if s >= i then Loop.done(()) else Async.sleep(20.micros).andThen(Loop.continue))))
                        .andThen(Async.sleep(200.micros))
                        .andThen(
                            if i % (n / 10) == 0 then
                                Sync.defer {
                                    val k = usedKb()
                                    println(f"writes=$i heapKB=$k bytesPerWrite=${(k - k0) * 1024.0 / i}%.0f")
                                }
                            else Kyo.unit
                        )
                }
            yield ()
        })).eval
        println(s"end: $r")
        java.lang.System.exit(0)
end ObserveLeak

Run: timeout 300 scala-cli run --jvm system ObserveLeak.scala -J -Xmx48m -- 30000

Output (Linux x86_64, 4 CPUs, JDK 25; the heap is read after three System.gc() calls):

writes=3000  heapKB=9523  bytesPerWrite=1666
writes=6000  heapKB=14743 bytesPerWrite=1724
writes=9000  heapKB=19707 bytesPerWrite=1714
writes=12000 heapKB=24861 bytesPerWrite=1725
writes=15000 heapKB=29715 bytesPerWrite=1712
writes=18000 heapKB=34626 bytesPerWrite=1706
writes=21000 heapKB=39472 bytesPerWrite=1698
writes=24000 heapKB=44307 bytesPerWrite=1692

Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "kyo-scheduler-worker-2"
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "kyo-scheduler-worker-1"

The growth is linear (about 1.7 KB per change) and the watcher dies between 24 000 and 27 000 changes. After the OutOfMemoryError the JVM does not exit (the writer waits for a watcher that is gone), so run it under timeout.

Where the memory goes. observe holds each value with holdUntilChanged, which runs one Async.race(Seq(nextWith(_ => ()), Async.sleep(repairInterval))) per change (Signal.scala, the observe(repairInterval) overload). Two other measurements point at the race:
- One fiber that runs Async.race(Seq(Async.sleep(Duration.Zero), Async.sleep(1.second))) 100 000 times keeps 540 to 720 B per race while it lives, and the heap returns to the baseline when the fiber ends.
- The same watcher written as a currentWith / nextWith loop with no race stays flat: at most 14 B per change over 100 000 changes.
So each race leaves something behind in the long-lived fiber (a finished-sleep entry or the loser's waiter) until the fiber ends, and the Scope per value adds the rest.

Why it matters. A UI with 5 bound regions that each change 10 times per second keeps about 300 MB per hour. The GC pauses that this causes also make the lost-wakeup window of observe (the other issue) visible more often.

Possible fix. A race should release its entries when it ends, or holdUntilChanged should not allocate a race per change: for example re-arm nextWith once and use one timer fiber for the repair.
```

### D2 (for #9): a write in the read-then-arm window waits for the repair

```
Title: Signal.observe / combineLatest: a write in the read-then-arm window is not delivered until repairInterval (1 s); request for a lost-wakeup-free nextWith

Version: kyo-core 1.0.0-RC7, Scala 3.9.0, JDK 25 (Linux x86_64, 4 CPUs).

What happens. observe reads current, runs f, and then registers nextWith. The Scaladoc documents that a write in the window between the read and the registration is missed by the immediate wakeup and repaired when the repair timer fires (default repairInterval = 1 s). combineLatest makes the window wider: its nextWith is Signal.awaitAny(Seq(self, other)).andThen(self.currentWith(a => other.currentWith(b => ...))), so a watcher that wakes for a reads a and b, and a change of b that lands after that read and before the next registration is not seen until the repair.

In a terminal UI (6 bound regions, one key handler that writes 4 refs, under CPU load from other processes) this shows as a frame where every region updated except the a.combineLatest(b) region, which shows an intermediate pair. Nothing is pending in the scheduler at that moment, so no "settle" probe can see that a frame is stale. The screen stays wrong until the next event or the 1 s repair.

Numbers (20 000 events per run, scheduler with cycleIntervalNs = 10 ms; 2 CPUs held busy by another process):
- 92 of 147 stale frames across the runs are this case: all regions updated, only the combineLatest region holds an intermediate value, and no timeout or pending work.
- With Kyo's default scheduler values the count is higher, and the other stale frames come from worker-stall gaps.
- With no competing load: 0 in 20 000.

What I expected. A watcher that is registered before the write, or a nextWith that cannot miss a write that happens between current and its registration. Then the worst case is one extra wakeup, not one repairInterval.

Proposal. Give the raw signal protocol a version (a monotonic change counter):
- currentWith also provides the version it read.
- nextWith(sinceVersion) fires at once when the version already moved, else it waits.
observe then re-arms with the version of the value it delivered, and the window closes. combineLatest can wait on the pair of versions. The repair timer stays as a safety net, but correctness no longer depends on it. A smaller step: let observe(repairInterval) accept a much smaller interval without the per-change allocation of the other issue, so that a UI can repair within one frame.

If the Kyo team asks for a reproducer: two refs, a.combineLatest(b).observe(record), a writer that sets a then b in one handler under load, and a check that the recorded pair shows b's new value within 10 ms.
```

## Appendix E: the reproducer of the controlled-input race (kyo-ui JS)

The spike of 2026-10-17 (Scala.js 1.22.0, kyo-ui 1.0.0-RC7). Build `Main.scala` and `SharedView.scala` with sbt-scalajs (`fastLinkJS`), serve `index.html` with the linked script, then run `node typing.js <url>` (Playwright). The probe types "the quick brown fox" with delays of 0, 10, 30, 50, 80 and 120 ms, three runs each, and prints whether the bound field holds the full text.

`SharedView.scala`:

```scala
package examples.spikeshared

import kyo.*
import kyo.UI.*

/** SPIKE: one platform-free kyo-ui view and its store. Only kyo-ui and Kyo
  * types: no tui4s, no java.nio, no DOM. The same file compiles in the JVM
  * tui4s build (examples) and in the Scala.js build (spike-js).
  */
final class Store(
    val count: SignalRef[Int],
    val messages: SignalRef[Chunk[String]],
    val draft: SignalRef[String],
):
    def inc(using Frame): Unit < Sync = count.updateAndGet(_ + 1).unit

    def add(using Frame): Unit < Sync =
      draft.get.map { text =>
        if text.trim.isEmpty then ()
        else messages.updateAndGet(_ :+ text.trim).andThen(draft.set(""))
      }
end Store

object Store:
    def init(using Frame): Store < Sync =
      for
          count    <- Signal.initRef(0)
          messages <- Signal.initRef(Chunk.empty[String])
          draft    <- Signal.initRef("")
      yield Store(count, messages, draft)
end Store

object SharedView:

    /** The shared view: a pure value over the store. */
    def view(store: Store)(using Frame): UI =
      div(
        h1("Spike: one UI, two surfaces"),
        span("Count: ", store.count.map(_.toString)).id("count"),
        form.onSubmit(store.add)(
          div.style(_.row.gap(1.em))(
            span("Message:"),
            input
              .id("draft")
              .placeholder("type, Enter adds")
              .value(store.draft)
              // em: cells in tui4s, font units in a browser
              .style(_.width(30.em)),
          ),
        ),
        button("+1").id("inc").onClick(store.inc),
        span("Messages:"),
        ul.id("messages")(
          store.messages.foreachIndexed((i, m) => li(s"${i + 1}. $m")),
        ),
      )
end SharedView
```

`Main.scala`:

```scala
package spikejs

import examples.spikeshared.SharedView
import examples.spikeshared.Store
import kyo.*

/** SPIKE: the Scala.js main. Mounts the shared view into `#app`. */
object Main extends KyoApp:
    run {
      for
          store <- Store.init
          _     <- UI.runMount(SharedView.view(store), "#app")
      yield ()
    }
end Main
```

`index.html`:

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>spike-js</title>
</head>
<body>
<div id="app"></div>
<script src="BUNDLE"></script>
</body>
</html>
```

`typing.js`:

```js
// SPIKE probe: lost keystrokes in a SignalRef-bound input, by typing delay; click latency; clear-after-submit race.
const { chromium } = require('playwright');
const [url] = process.argv.slice(2);
const TEXT = 'the quick brown fox';
(async () => {
  const browser = await chromium.launch({ headless: true });
  const out = { url, typing: [], clickLatencyMs: [], clearRace: [] };
  const page = await browser.newPage();
  const load = async () => { await page.goto(url, { waitUntil: 'load' }); await page.waitForSelector('#count', { timeout: 20000 }); };
  const draft = () => page.$eval('#draft', (d) => d.value);
  for (const delay of [0, 10, 30, 50, 80, 120]) {
    for (let r = 0; r < 3; r++) {
      await load();
      await page.click('#draft');
      await page.keyboard.type(TEXT, { delay });
      await page.waitForTimeout(400);
      const v = await draft();
      out.typing.push({ delay, run: r, ok: v === TEXT, got: v });
    }
  }
  await load();
  for (let i = 0; i < 10; i++) {
    const ms = await page.evaluate(() => new Promise((res) => {
      const c = document.querySelector('#count'); const before = c.textContent; const t0 = performance.now();
      const obs = new MutationObserver(() => { if (document.querySelector('#count').textContent !== before) { obs.disconnect(); res(performance.now() - t0); } });
      obs.observe(document.querySelector('#app'), { subtree: true, childList: true, characterData: true });
      document.querySelector('#inc').click();
    }));
    out.clickLatencyMs.push(Math.round(ms * 10) / 10);
  }
  // Clear race: one input event (fill) then Enter after a gap; is the field cleared?
  for (const gap of [0, 20, 50, 100, 200]) {
    for (let r = 0; r < 3; r++) {
      await load();
      await page.fill('#draft', 'abc');
      if (gap) await page.waitForTimeout(gap);
      await page.press('#draft', 'Enter');
      await page.waitForTimeout(400);
      const v = await draft();
      const n = await page.$$eval('#messages li', (l) => l.length);
      out.clearRace.push({ gap, run: r, cleared: v === '', value: v, added: n });
    }
  }
  await browser.close();
  console.log(JSON.stringify(out));
})().catch((e) => { console.error('PROBE FAILED', e); process.exit(2); });
```
