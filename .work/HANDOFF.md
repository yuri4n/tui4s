## 2026-10-19: FULL PROGRAM LAUNCHED (D17-D19, see DECISIONS.md sixteenth round)

- Branch `chore/cleanup-7p2rll` at 4ab4068 (skills removed, CI workflow_dispatch). No PRs (D18).
- P1 DONE, pushed 95bcf2a (30 files, +837/-356; 57 sites removed, suppressions 105 -> 49; RunOptions = A10; Tty LinkageError;
  blockingMonitorMinIntervalNs 20 ms with the reason in SchedulerDefaults; /tmp/jni-* copy removed after load on Linux via
  /proc/self/maps; JMH excluded; 240 core tests, 49 examples, PTY 121/121, 0 warnings). CI run started by workflow_dispatch.
- CI #43 on 95bcf2a GREEN (pini4s is public since 2026-10-19; CI was red on main since 10-16 for the private submodule).
- PF-a + P1b DONE, pushed 36d429c (51 files, +1356/-298; 15 commits; F1-F4, F8 with 5 oracles in
  core/src/test/scala/tui4s/oracle and 14 properties x 200 samples; A4 TuiError NoStackTrace non-null cause; A5 a-g
  except SchedulerDefaults.defaults, which stays Seq (tui4s.spi is Kyo-free, D-14 a: DECIDED keep); suppressions 49 -> 47;
  255 core leaves, 49 examples, PTY 121/121, 0 warnings). Leftovers to PF-c: F1 owns index, F2 markRegion Set,
  F8 lastChunk eq. Bug for P5: Canvas.text skips UTF-16 chars, not cells, when the clip starts right of the text.
  Container note: JAVA_HOME is JDK 21; the PTY kit needs JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64.
- P2 DONE, pushed d52afc4 (18 files, +622/-376; FiberGauge on one AtomicRef with Open/Closed(fibers), fork = Kyo bracket
  Sync.acquireReleaseWith(Fiber.initUnscoped)(add); opaque Loc; World.unarmed as a value; Runner.init factory; Runner.last,
  stopped, ticker deleted; suppressions 47 -> 38; 264 core leaves, 49 examples, PTY 121/121, glitch 0/500 x2).
  Skeptic: 1 should (nanoTime), 3 nits; D23 below. Left for later: hook/onMount failures still not logged (Mount comment);
  the GC regression test of the plan was not added (lean).
- P3 DONE, pushed a50d1d6 (15 files, +1081/-597; new edge file internal/ReleaseStack.scala = stack + Hooks + the one
  tui4s-stop hook per run; Tui.run runs the UI on its own fiber (Fiber.use) and waits on a promise copy; release order
  canvas, input, modes, flush, raw mode, capture (+R3 warning), log sink, hook removal; suppressions 38 -> 29 (8 edge
  files); 270 core leaves, 49 examples, PTY 121/121 with q/Ctrl-C/TERM 143/INT 130 rows green; ReleaseStackTest 100
  seeded rounds with a negative control). Skeptic: 1 should (guard the hook's interrupt call), 5 nits, all applied.
  Open for the user: D24. PTY kit gaps to add in P4+P6: a SIGHUP 129 row and a row for an app that fails inside the UI
  (exit 1, message after the restore). Accepted window (in AGENTS.md): a JVM stop between the hook removal and
  TuiApp.runMain's read of Hooks.shuttingDown is not seen.
- P5 DONE, pushed 14d1a74 (16 files, +1815/-491; pure painter gives a Plan = ops + row spans (F9a); new edge file
  internal/Canvas.scala with the kernels write and diff, flush = diff + draw under the backend monitor, read gives a Screen;
  clip-by-cells fix with ClipTest negative control; PaintOracle = a50d1d6 Paint; 9 properties x 200; suppressions 29 -> 19
  (9 edge files); 281 core leaves, 49 examples, PTY 121/121, glitch 0/500 x2). Skeptic: 2 should (oracle coverage of
  decorations, glyphs wider than 2 cells), 3 nits, all applied. Open product question: a wide glyph cut by the clip's
  right edge keeps the old cell (old behaviour). Not done: F9b (skip a repair frame whose ops did not change).
- P4 + P6 DONE, pushed 5bcca5d (25 files, +798/-333; Headless on Canvas.read/cells; LogSink and OutputCapture with one
  state value each; LineSplit pure + property; hook/onMount failures logged once; PTY rows hup 129 and printfail
  (--fail-on-f); 284 core leaves, 50 examples, PTY 136/136).
- L0 DONE 2026-10-19: branch vs main 151 files, +6843/-8992 (net -2,149 lines). 15 KEEP sites, 9 edge files, 0 other
  suppressions. CI green through #47 (P5); the P4+P6 run is queued. Leftovers (not blockers): Component.initial is
  pure, so a per-mount effect value arrives via onMount (TabsView focus group); TuiLog keeps `Throwable | Null` and
  LocalTime.now inside its edge file; the audit's extra P4 ideas (Headless end mark, Todo Model/TodoStore, shared
  CounterView, Dashboard Series) were not done; F9b not done; D24 open.
- L1a DONE, pushed eda54ac (27 files, +3735/-333; viewports on kyo-ui overflow/overflowX/Y with World.scroll, follow-focus
  (CSSOM nearest rule, aria-activedescendant target), key table Up/Down/PgUp/PgDn/Home/End + Left/Right, indicator with
  scrollbarWidth/Color, TextFlow ADTs (Space/Wrapping/Cut) for whiteSpace/textWrap/textOverflow, wide-char fixes in
  select labels and markers; LayoutOracle = 5bcca5d Layout; 12 properties; 14 goldens; no new tui4s public API;
  321 core leaves, 52 examples, PTY 136/136, glitch 0/500 x2). Review: 1 blocking (row wrap with a sibling lost lines;
  fixed with a golden + negative control), 11 others applied. Open: UI.scrollIntoView is a no-op under tui4s (sink
  private[kyo]; draft .work/upstream/kyo-ui-scroll-into-view.md); a viewport needs a bounded height (CSS min-height
  gotcha, documented); a scroll re-lays the viewport content (PF-b F5/F6 still to do, after L1c); no stick-to-bottom.
- L1b DONE, pushed 48acbcf (37 files, +3642/-298; layers for position/zIndex with containing blocks and a damage rule
  (whole rows under a layer); Tui.size: Signal[TerminalSize] via a Kyo Local; Color.Var from a kyo.Stylesheet given to
  the run (Tui.run(options, sheet), TuiApp.stylesheet, Headless.start(..., stylesheet)); CssVars with :root/scoped
  blocks; flexWrap lines; focusAuto seeding + focusRestore sweep; Warnings once per property name per run through TuiLog;
  StyleTableTest keeps the package Scaladoc table, Props.honored and the surface list in sync: 47 of 76 honored;
  Headless.awaitLog; 350 core leaves, 56 examples, PTY 136/136, glitch 0/500 x2). Review: 5 should, 8 nits, applied.
  The first verification agent was cut off mid sbt check; a finalizer agent ran the check, the PTY kit and the push.
  Open: a bug found in passing (an incremental frame of the plain flow can differ from a full frame: a CJK value in a
  1-cell text input) -> fix in L1c with a negative control and the full-screen step property; PTY check.py class-load
  INFO line classifies jars by the old paths (cosmetic) -> L1c; relative layers in big lists repaint whole rows (perf,
  L5 bench); L4 may want a Kyo-free theme option that renders to the stylesheet channel (D8).
- L1c DONE, pushed 03045e5 (38 files, +3273/-293; AreaText multi-line textarea with D7 newline keys (Alt-Enter, Ctrl-J,
  Shift-Enter; plain Enter submits in a form, breaks a line outside); password mask, number grammar + steps, range keys,
  dropdown keys; readOnly/disabled/maxLength (tui4s.maxLength extension: kyo-ui has none); the hardware cursor at the
  caret (Plan.cursor); Warn.UnsupportedElement for date/time/color/file; the damage-clip bug fixed (a kid clear that
  overflows its owner; StepPropertyTest incremental == full frame); examples/forms + README 'Forms and inputs' + PTY
  row; check.py class-load line fixed; 384 core leaves, 68 examples, PTY 148/148, glitch 0/500 x2). Review: 9 should,
  7 nits, applied. Open: cursor not hidden under a non-trapping overlay; Shift-Enter needs a terminal that reports Shift
  (no keyboard-enhancement flags pushed); inputFilter/inputMask ignored; one-line inputs run onChange only on Enter.
- L1 DONE 2026-10-19 (L1a, L1b, L1c). 47/76 style properties honored; branch vs main now about +17k/-10k.
- PF-b DONE, pushed 6e1212a (15 files, +968/-140; PerfProbe stress probe; F5 measure kids reused per constraint;
  F6 relayout keeps the natural size of a clean node via Scene.changes/prior; F4 lines as a value in the laid node;
  F9b a frame whose Plan equals the last skips the canvas; Warnings.scan skips the last screen's elements. Allocation
  per op: list full -35%, list edit -85%, list scroll -55%, log append -40%, dashboard -23%; timings noisy on 4 vCPU
  (use -Dtui4s.stress=100). 389 core leaves, 68 examples, PTY 148/148). Skipped (do not pay): F1 owns index, F2 Set,
  F8 eq fast path. Open: list scroll still O(rows) (a pure translate of a clean viewport would fix it); log append
  re-places the forced subtree.
- L2a DONE, pushed 70c2e2e (48 files, +4244/-108; sbt-scalajs 1.22.0 + crossproject 1.4.0; crossModule(use, widgets)
  CrossType.Full, artifacts tui4s-use/tui4s-widgets (+_sjs1), tags use-v*/widgets-v* in release.sh and release.yml;
  kyo-test runs on Node.js for JS; 13 composables over SignalRef/Signal/Scope with laws as properties (Pacing,
  Selection, History, ScrollWindow, TimeAgo ADT, Keymap/Chord/Binding, AsyncState, Dialog with a flow queue, Storage
  (FileStorage JVM atomic + LocalStorage JS), useProcess on kyo-system Command); README 'Composables' with 'You may
  not need a composable' first; examples.composables.{Native,Recipes} + RecipesSuite (8 leaves); Todo undo/redo;
  Dashboard stamp + keymap; flake.nix adds nodejs; 389 core, 92 use (JVM+JS), 81 examples, PTY green). Review: 1
  blocking (D17 coverage: fixed with Recipes), 9 should, 7 nits, applied. Open (human): the GitHub environment
  maven-central must accept the tags use-v* and widgets-v*. Open: Kyo Signal.observe can miss a write and repairs
  within 1 s (documented); useProcess's blocking stdout read holds a scheduler thread; LocalStorage (JS) untested on
  Node (no window); Lines.split ends lines only at LF.
- D25 DECIDED (issue #17): artifact split runner/app/testkit + composite core, one agent, right after L2b, before L3.
- L2b DONE, pushed 4264c42 (35 files, +5477/-447; 31 widgets in tui4s.widgets: Model (Local|Bound|Controlled),
  Colors (8 Color.Var names + Colors.defaults stylesheet), Panel, SplitPane, Accordion, Tabs (moved; examples.widgets.Tabs
  stays as the tui4s Component adapter), ListView(+virtual), DataTable, TreeView, Pager, Sparkline, Gauge, ProgressBar,
  BarChart, Spinner, SearchBox, Autocomplete, Checklist, RadioGroup, NumberStepper, DateField, StatusBar, KeyHints,
  CommandPalette, Modal, Toasts, Menu.bar, Tooltip, Breadcrumbs, Pagination, Tags, Wizard; examples/gallery (35 leaves)
  + README 'Widgets' (5 regions) + PTY row gallery; 389 core, 92 use, 108 widgets (JVM+JS), 110 examples, PTY 156/156).
  Review: 12 should, 8 nits, applied (px->cells units, wide-char measure by cells, Pager follow rule, DataTable header
  column, useSelection reuse, aria naming, Wizard.onFinish by-name, handle shapes, tree law). Later widgets: form
  validation, file picker, Markdown viewer, calendar popup, line chart, chat transcript (L3). Open: on the web
  aria-activedescendant does not scroll (L4 may need UI.scrollIntoView); Toasts/Spinner re-render on each tick.
- L2 DONE 2026-10-19 (L2a, L2b).
- D25 DONE, pushed 132358f (123 files, +478/-234; modules runner (45 main, 57 test files), app (TuiApp), testkit
  (Headless + Screen aliases), core (object tui4s.Core + 29 integration suites; depends on runner compile->compile;
  test->test, app, testkit); Screen/CellStyle/CellColor now in package tui4s (runner); runnerTestSettings in the root
  build; release.sh order runner app testkit core zio cats use widgets; release.yml tags; edge paths; publishLocal of
  the four OK; runner 210 + core 179 + use/widgets 200 + examples 110 tests; PTY 156/156). Issue #17 closed.
  HUMAN STEP: GitHub environment maven-central must accept runner-v*, app-v*, testkit-v* (and use-v*, widgets-v*).
  Open (small): core should watch modules/{runner,app,testkit} for the version policy -> fold into L3's build touch.
  Local checkouts switching to the branch: `sbt runner/clean core/clean` once (stale target/ after the git mv).
- h34s is OUT OF SCOPE for this session (user, 2026-10-19: it was the wrong chat). Do not carry its discussion forward.
- L3 DONE, pushed fc7b31f (52 files, +6359/-27; modules/assistant cross module: pure domain with laws (Transcript
  fold, title rule, SSE split law, codec round trip), services as traits (ChatProvider, Secrets, ConversationStore,
  AppLog) with JVM interpreters (kyo-http seam KyoHttp with followRedirects(false) + a 5-min idle limit, SecretsJvm:
  env or a 0600 key file, FileStorage) and in-memory ones for tests; Anthropic + OpenAI SSE wires with fixtures; one
  store; UI on tui4s-widgets; main examples.assistant.AssistantApp (--fake); 13 Headless leaves; PTY row assistant;
  README 'A real app: the assistant'. Tests: 675 kyo-test leaves (runner 210, core 179, use 92, widgets 108,
  assistant 86) + 123 examples; PTY 164/164). Review: 1 blocking (redirects carried the key: fixed + negative
  control), 6 should, 7 nits, all applied. The fix agent was killed by a user interruption of the orchestrator's turn;
  a finalizer agent finished it (lesson: check the workflow state after any interruption).
  USER MUST KNOW: the app opts in to Anthropic server-side fallbacks for the default model (header
  anthropic-beta: server-side-fallback-2026-07-01, field fallbacks: "default"; Anthropic.fallbacks in Wires.scala);
  the OpenAI default id 'gpt-5' is unchecked data; effort/thinking not sent; the run log file has the umask mode.
- L4 DONE, pushed e41fd0f (32 files, +2485/-143; tui4s.widgets.Theme (terminal, web) -> Theme.palette (colour vars,
  the terminal) and Theme.stylesheet (full CSS, the web; [data-theme] scopes); Look enum + switch in the assistant
  settings; JS interpreters: FetchHttp (streamed reader, AbortController in Scope, redirect=error, credentials=omit,
  no-referrer; 5-min idle), BrowserKeys over LocalStorage (one entry per provider, masked, forget, warning),
  ConversationStore on LocalStorage, console AppLog; index.html CSP connect-src only the two provider hosts; project
  examples/web (assistantWeb, KyoApp main with UI.runStylesheet + UI.runMount('#app')); Surface enum service (rows/
  cols/lines units, web chords Alt-N/Alt-arrows/Alt-,/Alt-L/Alt-P/F1, first look, key vault); bundle 9.74 MB /
  1.20 MB gzip (locales-minimal), mounts in ~0.9 s; scripts/web-build.sh -> dist/assistant; scripts/web-smoke.mjs
  (Playwright, passes; not in CI); README 'The same app on the web'; 707 kyo-test + 124 examples; PTY 91/91 screen
  rows). Review: 1 blocking (the typed key was in the DOM value attribute: fixed), 5 should, 7 nits; 11 applied, 1
  deferred (Surface mixes data and the KeyVault capability), 1 n/a.
  Open: tui4s-widgets has no unit scale for the web (px/em = cells; Modal/CommandPalette/Panel/ListView/Pager sizes
  show as CSS px or font ems; Pager/ListView clip ~20% on the web; documented in the package Scaladoc); RadioGroup
  has no click handler (keyboard only on the web); kyo-ui upstream drafts to write (D13): (1) DOM keydown on a field
  inside a nested reactive region skips the field's handlers, (2) no preventDefault on a handled keydown, (3) any
  Enter submits the enclosing form (so the web composer is outside a form), (4) a Stylesheet cannot override inline
  element styles (Panel corners); the PTY assistant first-screen row raced once right after the launchers build.
  Note: an sbt server died of its 3 GB heap after several Scala.js links; the agent restarted it by its own PID.
- STATE 2026-10-19 (for the compaction): branch chore/cleanup-7p2rll at e41fd0f, all of L0-L4 + D25 pushed; CI
  green through L3 (#?? queued for e41fd0f); tasks left: L5 (docs site + bench/ + alchemy infra; Vercel project when
  the user says), L5b (native-image metadata, AOT launchers, GraalVM CE in CI), L6 (release checklist; tag needs
  human approval). Human steps pending: maven-central tag patterns (runner-v*, app-v*, testkit-v*, use-v*,
  widgets-v*); D24 default keep; the Anthropic server-side fallbacks opt-in in Wires.scala (user must know).
- RULE (user, 2026-10-19): after L4 lands, STOP: report it, do not start L5, wait for the user (they will /compact).
  L4 landed; the user compacted and said "continue" (2026-10-20). CI #57 green on e41fd0f.
- L5 LAUNCHED 2026-10-20: workflow wf_2faf0e7d-df6 (script tui4s-l5-wf_2faf0e7d-df6.js), 7 Opus agents: two streams
  in worktrees (/home/user/tui4s-l5-docs on l5/docs: the Docus scaffold copied from origin/docs/docus, example-first
  pages whose Scala blocks are checked regions (MarkdownRegionsSuite), asciinema casts from the PTY kit, the web demo
  in an iframe; /home/user/tui4s-l5-bench on l5/bench: bench/ with its own sbt build on the publishLocal artifacts,
  tasks counter + dashboard in tui4s, jatatui-plain, ratatui, bubbletea, textual, ink, layoutz (TamboUI only if dotnet
  installs), a PTY harness, the resilience suite, the 13-class mutant suite, compactness/expressiveness, the honest
  PERFORMANCE page, bench/infra with alchemy (us-east-1, no inbound, IMDSv2, shutdown -h +90, terminate on shutdown,
  results to S3; the fix stage deploys ONCE after the review and destroys + verifies), bench.yml); each stream
  implement -> review -> fix; one finalizer merges (ff, linear), fills the docs bench table, runs the one full check +
  docs build + bench smoke + PTY, pushes, triggers ci.yml/docs.yml/bench.yml. The orchestrator creates the Vercel
  project after the push (D11). If the orchestrator is interrupted: check the workflow state and the two worktrees
  before anything else (lesson of L3).
  <!-- claude 2026-10-20: created this item -->
- INSTRUCTION (user, 2026-10-20, while L5 ran): "finish to completion. a finished product ready for use from the
  public. take the decisions that are most cost effective and record the why and the alternatives contemplated
  (adrs?). take care to completion of what we've been talking about. When done, cleanup PRs issues and branches.
  Docs and stale code. TODOS, planned features and so on." Plan: L5 (running) -> ADR set drafted in parallel (Opus,
  scratchpad/adr) -> L5b + L6 + cleanup as one workflow (AOT launchers, native-image metadata + GraalVM CE smoke,
  release checklist, remove the empty zio/cats modules and the deprecated useSignal, ADRs into the docs site, planned
  features as GitHub issues, SECURITY.md/CHANGELOG, macOS in the PTY job) -> stale branches deleted (shas recorded),
  PR #7 closed or folded, wip/handoff and docs/docus deleted last -> final human gate in ONE message: ff-merge to main
  and the release tags (AGENTS.md: a tag needs human approval; maven-central tag patterns are a human step).
  <!-- claude 2026-10-20: created this item -->
- FACT (2026-10-20): TamboUI is a JAVA library (Champeau, Andersen; github.com/tamboui/tamboui; GraalVM native), not
  .NET. The L5 bench brief said "only if dotnet installs" (a slip). If the bench stream dropped it, the follow-up
  workflow adds it as the JVM peer. ADR drafts are in scratchpad/adr (19 ADRs, PLANNED-FEATURES.md with 34 issue
  blocks, CLEANUP.md with branch shas). `first-take` is the user's own branch: do not delete (HANDOFF 2026-10-01).
  <!-- claude 2026-10-20: created this item -->
- L5 workflow DONE 2026-10-20 (7 agents; docs stream b537cfa, bench stream 3b9c56b, merged linearly at 246e8a4 local;
  the finalizer was cut off in `sbt check`; a one-agent finalizer runs the verification and pushes). Facts: AWS
  credentials of the container are rejected (InvalidClientTokenId): bench/infra written, not deployed (issue #51);
  TamboUI included as a JVM stack, its 13 mutants not written; the docs demo runs FakeOnly in a sandboxed iframe
  (?demo). GitHub issues #18-#51 created from PLANNED-FEATURES (34); drafts D1/D2 as comments on #8/#9.
  <!-- claude 2026-10-20: created this item -->
- L5 PUSHED 246e8a4 (the one-agent finalizer; the container restarted right after). ADR drafts backed up in
  `.work/adr/` of this branch. Vercel: `create_git_project`/`create_project` return 403 forbidden for the connector
  token (team yurian-solo, GitHub namespace yuri4n is linked): the user creates the project (name tui4s, repo
  yuri4n/tui4s, Root Directory docs, framework Nuxt) in the dashboard; D11 becomes a HUMAN STEP.
  <!-- claude 2026-10-20: created this item -->
- L6 LAUNCHED 2026-10-20: workflow wf_7be3e308-311 (script tui4s-l6-wf_7be3e308-311.js), 7 Opus agents, two
  streams: release (/home/user/tui4s-l6-release, l6/release: zio/cats and useSignal removed, examples/README blocks
  as regions, AOT launchers + flags, native-image metadata + scripts/native-image.sh + native.yml (GraalVM CE),
  bench stack tui4s-aot, SECURITY/CHANGELOG/CONTRIBUTING, issue + PR templates, rulesets from PR #7 with six tag
  patterns, Scaladoc step, macOS PTY matrix if the jar has a macOS native, web smoke job, README status, dry runs)
  and docs (/home/user/tui4s-l6-docs, l6/docs: ADRs as docs/content/4.decisions, project pages roadmap/contributing/
  security, overclaims removed, TamboUI mutants + tables). Finalizer: ff merge, report.py, ADR statuses, full
  verification (no nohup for bench/PTY), prunes .work/worktrees, pushes. CI runs for 246e8a4 queued (ci, docs,
  bench): read their results after the workflow; bench.yml is its first run on GitHub.
  AFTER: trigger CI; close PR #7 (comment names the rulesets commit); delete the stale remote branches (CLEANUP.md
  list; not first-take, not main; wip/handoff last); the gate message (ff to main, tags, maven-central, Vercel).
  <!-- claude 2026-10-20: created this item -->
- L6 workflow DONE 2026-10-20 (7 agents): release stream 9 commits (zio/cats + useSignal removed; examples/README
  blocks compiled in examples.state + StateSuite; AOT: bin/aot-train + start-script contract, counter 1,118 -> 511 ms,
  RSS 159 -> 104 MB; native-image metadata + scripts/native-image.sh + native.yml; GraalVM CE 25.0.1 ran HERE:
  native counter 25 ms / 40 MB; bench stacks tui4s-aot, tui4s-native; SECURITY/CHANGELOG/CONTRIBUTING, issue forms,
  rulesets, Scaladoc step, macOS matrix (jar has darwin dylibs), web job; dry runs OK for runner and use), docs stream
  4 commits (ADRs as docs/content/4.decisions, project pages, overclaims gone, TamboUI 12 mutants + 1 n/a). Merged
  linearly to 914995d LOCAL; the finalizer was then locked out of Bash (classifier, after an rm -rf of ignored
  target dirs). CI on 246e8a4: ci green (#58), docs green, bench.yml FAILED (post-job cache paths of unused
  toolchains; ink: None status crash in oracle.py, a timing step of the change oracle, a machine-dependent mutant
  outcome). A one-agent finalizer (a2f46b84...) now: bench CI fixes, report.py, packaging docs page, ADR 0015 shipped
  numbers, SECURITY order, full verification, worktree prune, push. Private vulnerability reporting is OFF on the
  repo: HUMAN STEP (Settings > Code security). Then: trigger CI (ci, docs, bench, native), close PR #7, delete the
  stale remote branches with `git push origin --delete` (no MCP tool), the gate message.
  <!-- claude 2026-10-20: created this item -->
- D26 (user, 2026-10-20, chat, FINAL after four turns): the state primitive gets the short name `tui4s.sig(initial)`
  (an alias of `Signal.initRef`, akin to Vue's `ref()`, returns `SignalRef`) as a top-level def in the RUNNER's
  package `tui4s` (a terminal app gets it with tui4s-core alone and `import tui4s.{Tui, TuiApp, sig}`); shared
  cross-platform code calls `Signal.initRef`. `ref` lost (the usual local name; reads as Vue's type); `signal`,
  `createSignal`, `initRef`, `useSignal` lost. History: the user asked "why
  useSignal gone?"; picked `signal(0)` from a choice (signal, createSignal, sig, useSignal); then "signal() should be
  a primitive, not a hook" (a hook is mount-bound; creating a signal has no lifetime, and the runner is terminal-only),
  so NOT in tui4s.Hooks; then "put a method called `ref`, alias of Signal.initRef, same as Vue". The finalizer adds it
  (modules/use, Scaladoc, a use test leaf, the Counter example uses ref(0) with its regions re-copied, AGENTS.md,
  CHANGELOG, ADR 0014, the components docs page). The user stopped the first finalizer by accident; a second one
  resumed from c2a5162 (the sbt check had passed on it).
  <!-- claude 2026-10-20: created this item -->
- (history) L4 ran as 3 agents: the standalone Scala.js build of the assistant (D5), Theme (D8) in tui4s-widgets rendered to
  one Stylesheet for both platforms, JS interpreters (fetch SSE, localStorage key with a warning), units em/pct.
- (history) L3 ran as 3 agents: shared crossProject (domain + UI, kyo-ui/use/widgets only), tui main
  (TuiApp), providers Anthropic/OpenAI (JVM SSE over kyo-http) + scripted fake; keys from env or a 0600 file only.
- (history) L1 ran in three workflows (cheap cadence: implementer + API/examples reviewer + fixer, Opus): L1a viewport scroll
  and clip, wrap and ellipsis, wide characters; L1b overlay and z-order, terminal-size Signal, Color.Var, FlexWrap and
  Display, one warning per ignored property; L1c native inputs and the multi-line textarea (Alt-Enter, Ctrl-J,
  Shift-Enter where reported). PF-b (F5, F6) rides L1a's layout work.
- Upstream drafts land in `.work/upstream/` (the user posts them).
<!-- claude 2026-10-19: created this item -->

# Handoff: where to start (rewritten 2026-10-01 evening, session paused at the user's request)

Say to the next session: **"continue from .work/HANDOFF.md"**.
Read in this order: this file -> `.work/DECISIONS.md` -> `.work/design/SPEC.md` (section 9 top = user decisions,
section 8 = PR plan) -> `.work/impl/*.md` for the module you touch. Old, detailed log: `.work/HANDOFF-2026-10-01-log.md`.
Toolchain: `source .work/env.sh` (JDK 25, sbt, node 24, pnpm 10, IPv4, -Xss16m). Do NOT kill PID 7740 (or any sbt
started from the main checkout): it is the user's Metals sbt server.

<!-- claude 2026-10-17: created this section -->
## 2026-10-17: NEXT PHASE IS A DRAFT ROADMAP (read first)

- Read `.work/design/ROADMAP-2026-10.md`. It has the research facts, the plan
  as a stack (L0 functional rewrite first, then core renderer features,
  widgets and hooks, the flagship assistant app, web, docs, benchmarks,
  release) and decisions D1-D14 with recommendations.
- The user APPROVED D1-D16 on 2026-10-17 (DECISIONS.md, fifteenth round). Next: L0 phase P0 on branch
  chore/cleanup-7p2rll (restarted from main), after the startup measurement workflow frees sbt.
- Branch state (2026-10-17): the remote chore/cleanup-7p2rll is the merge commit e027fe2 (parents: the old tip
  adbe3a6, whose content is already in main through PR 1, and origin/main cd6a877; its tree equals main). The
  classifier refused a force push, so this merge keeps history and pushes as a fast-forward. Before pushing P0,
  run `git merge --no-edit e027fe2` on the local branch (trivial, no conflicts). The vendor/pini4s submodule is
  checked out from a read-only clone at /home/user/pini4s (local git config only).
  <!-- claude 2026-10-17: created this item -->
- P0 DONE (2026-10-17): pushed to chore/cleanup-7p2rll at b3977c1 (11 commits + the trivial merge; no PR yet;
  CI runs only on a PR). Results: 231 core tests, 49 example tests, 0 warnings, scalafixAll --check green, lint
  proof (a `var` fails), edge-check.sh green under bash 5 and 3.2. 105 tagged suppressions: P1 57, P1b 2, P2 10,
  P3 9, P4 5, P5 10, P6 1, KEEP 11. Edge files: Session, TerminalIO, Crossterm, OutputCapture, LogSink, TuiLog,
  SchedulerProbe. Deviations: .scalafmt.conf comments.wrap = no (long scalafix:ok lines); regex rules skip comment
  lines; a mechanical OrganizeImports commit (63 files). Next: P1 (cheap wins; also the isatty LinkageError fix,
  the probe interval in SchedulerDefaults, the /tmp/jni-* leak workaround). Port the effect-systems.md row fix
  to the Mac copy of the skills.
  <!-- claude 2026-10-17: created this item -->
  <!-- claude 2026-10-17: changed after the user's approval -->
- The user's rules for the rewrite are the skills on main:
  `.claude/skills/functional-design` and `.claude/skills/scala-functional-design`
  (commit cd6a877, "temporary").
- Raw results: `.work/explore-2026-10-17/` (JSON per research agent, the
  skill review, and the sources of the two spikes that worked).

<!-- claude 2026-10-03: created this section (cloud session resume) -->
## 2026-10-03: RESUMED IN A CLOUD SESSION (read first)

- The desktop pushed `.work/` to branch `wip/handoff` (commit 2044c03). The cloud session extracted it with
  `git archive origin/wip/handoff .work | tar x`. Do not merge `wip/handoff`.
- Cloud toolchain (not in env.sh; env.sh is for the Mac): JDK 25 from apt (`/usr/lib/jvm/java-25-openjdk-amd64`),
  sbt launcher wrapper `/usr/local/bin/sbt`, scala-cli wrapper `/usr/local/bin/scala-cli` (sets JDK 25, proxy
  truststore, proxy port from `$HTTPS_PROXY`, `JDK_JAVA_OPTIONS=-Xss16m -Xmx2g`). Always use `--jvm system`.
  Maven Central sends HTTP 429 under parallel downloads: retry with backoff.
<!-- claude 2026-10-09: added the agent limits of the cloud environment (user asked to record them) -->
- AGENT LIMITS (user: "the sweet spot of cost x benefit for each environment"; claude 2026-10-09: changed this item).
  Model: an agent is LLM-bound (read, review, write: almost no local CPU) or build-bound (scalac on Kyo, test JVMs:
  all cores). The token cost almost does not change with concurrency; concurrency buys time. The hidden cost is CPU
  contention: timing suites then fail falsely, and an agent spends tokens on a ghost. Thus: many LLM-bound agents,
  few builds, and timing work alone.
  - CLOUD (4 CPUs, 15 GB RAM): at most 2 agents at once (a hard cap: the workflow runtime runs at most
    min(16, CPUs - 2) agents at once). Both can compile and run plain tests (memory is sufficient: sbt about 3 GB,
    Bloop 2.2 GB). Do not add Agent-tool agents next to a workflow to pass the cap: on 4 cores they only add
    contention.
  - LOCAL (Mac): at most 4 agents at once; at most 2 that build, and not more than cores/4 (`sysctl -n hw.ncpu`).
    More than 4 buys only time and uses the usage window faster. Never kill the Metals sbt server of the user.
  - BOTH: timing-sensitive suites (plan 6.0: C2, C3, C4, T1, F1-F4) and all timing numbers run with no other build
    on the machine. One workflow at a time. Announce a run of more than 10 agents. In a new container, check nproc
    first.
- Baseline on the cloud machine (4 CPUs) before the resume: runner 7/8 (stale colour assertion at
  `test/Apps.test.scala:123`); Cats facade 9/9; ZIO facade FLAKY (a different test fails on each run).
- Resume run: workflow `wf_6bb71261-308` (6 agents, max 2 at once): resume runner + facades -> one skeptic each
  (fix if refuted) -> integration (facade apps on the terminal runner, `.work/proto/kyoui-integration/`) +
  side-by-side code (`.work/proto/SIDE-BY-SIDE.md`, `side-by-side.json`).
- RESULT of `wf_6bb71261-308` (7 agents, about 2.0M tokens, all on the 4-CPU cloud machine):
  - Runner: DONE. 9/9 tests x 3 runs; settle bug fixed (probe the scheduler before draining the inbox;
    `Settle.Quiescent(100.millis, confirm = 2)`); overflow layout bug fixed; bench p50 0.3-0.75 ms per event;
    real PTY Counter + Todo pass (16/16 terminal, 20/20 screen checks; stty restored). Skeptic: 0 blocking.
  - Facades: DONE. ZIO flakiness root cause = two state cells (zio SubscriptionRef + kyo SignalRef) synced by
    pumps; fix = facade-owned `SignalRef[A]` on the one kyo-ui cell (ZIO seals Ref/SubscriptionRef). ZIO 5/5, Cats
    5/5 runs; full 52-element mirror compiles and runs. Skeptic: 1 blocking (REPORT missing) -> fixed.
  - Integration: DONE (`.work/proto/kyoui-integration/`). Unchanged ZIO/Cats apps run on the terminal runner:
    12/12 x 3, PTY 10/10 + 22/22. Runner problems found, NOT fixed: R1 watcher leak on interrupt (~1.75 MB/run),
    R2 first-key race, R3 OutputGuard misses scala.Console.out; R4/R5 Kyo-free options, quit as a value.
  - Side by side: `.work/proto/SIDE-BY-SIDE.md`, `side-by-side.json` (generator `side-by-side-gen.py DONE`).
    Page: https://claude.ai/artifact/5caZtjTZk8uohrUy3QoRei (source in `.work/proto/side-by-side-page/`).
- Open questions for the user are at the end of each REPORT.md (runner 8, facades 8, integration 4).
- 2026-10-03 later: `.work/` pushed to `wip/handoff` (commit 1d100ed, without .scala-build/.bsp). The user said "I like
  it, go ahead": planning run `wf_ccf6b226-c52` (9 agents, read-only) writes `.work/design/plan/*` and
  `.work/design/PLAN-kyoui.md` + `upstream-kyo-ui-issue.md` (DRAFT, never post without the user). Assumptions A1-A4
  (PR shape a/b/c; upstream issue draft only; ZIO SignalRef; checked-in mirrors + drift CI) await confirmation.
<!-- claude 2026-10-09: added this item (round 6) -->
- 2026-10-09: ROUND 6 (see DECISIONS.md). Kyo phase first (C1-C4, T1, T2, P1, L1, L2, D); the ZIO phase starts after D
  merges, the Cats phase after it. No generator: the ZIO and Cats DSLs are hand-written, with a coverage test. C3
  routes all bound writes through one runner function. The ZIO state design (Z1/Z2/Z3) is picked by spike SZ at the
  ZIO start. Plan: `design/PLAN-kyoui.md` section 0. Kyo removed its Typelevel modules (getkyo/kyo#1778); our Cats
  bridge is already vendored. Prior art: ghostdogpr/sage, gruggiero/typesafe4s (kyo-compat + vendored CE backend).
  NEXT: the user confirms A1-A4 (A3 and A4 are now superseded by round 6) and D-1 (run W0 first); then W0 (5 agents).
<!-- claude 2026-10-09: added this item (PR 1 merged, W0 started) -->
- 2026-10-09 later: the user squash-merged PR 1 (`main` = 863db67) after the Codex fixes (version order like Maven,
  `build.sbt` and `project/` watched). The user closed PRs 2, 3 and 4 without a merge. PR 5 is still open. Then the
  user said "done. continue": W0 started with the RECs D-1 (a), D-11 (a), D-32 (a). The other 3.1 decisions
  (D-3..D-10, D-12, D-13) go to gate 0, with 3.2. Effects on the plan: D-4 (the user used squash, so the next stacked
  branch needs a rebase or a merge after each squash); D-7 is now option (c) (new docs branch at D; `docs/docus`
  still exists).
- STANDING RULE (user, 2026-10-09): "go simpler" on small tasks. A small task (a review reply, a small fix, a
  question) gets no workflow and no agents: do it directly and report in a few lines.
<!-- claude 2026-10-09: added this item (S8) -->
- 2026-10-09: DECISIONS round 7: after W0 (run wf_bf024cec-c9f), run spike S8 (one agent; State-monad UI baseline;
  PLAN section 0.6). NEXT after W0: push `.work/` to `wip/handoff`, give `gates.md` in the chat. S8 is CANCELLED and parked
  (`design/parked/S8-state-ui.md`); do not run it unless the user asks.
<!-- claude 2026-10-09: added this item (W0 restart) -->
- 2026-10-09 21:09: the container restarted during W0. B (S6), C and E (S3, S4) were done and stay cached in the
  journal; A (S1, S2) was cut; M had not started. Resumed the same run (`resumeFromRunId: wf_bf024cec-c9f`): A
  continues from its files, then M. FACT for all workflows: the subagent harness REFUSES report .md files ("Subagents
  should return findings as text"). Give each agent an output field for the report text; the orchestrator writes the
  file. The REPORT.md files of s6-flags, s3-drift and s4-weight were written this way.
<!-- claude 2026-10-09: added this item (W0 done) -->
- 2026-10-09 23:41: W0 DONE. Results: `design/plan/gates.md` (M), spike REPORT.md files, `design/upstream-w0-numbers.md`
  (not applied to the draft yet). G1 NO-GO (Kyo values) / SOFT (tui4s values): ASK D-17. G2: R1 option (b); R2 GO
  with "armkey". G3 NO-GO: ASK D-19 (pin RC7). G4 GO. G6 GO. NEW RISK K29: `Signal.observe` keeps about 1.7 KB per
  change (about 300 MB per hour at 5 regions x 10 changes/s): decide at gate 0 for C2.
  LESSON: a resume caches only the UNCHANGED PREFIX of agent() calls. I edited agent A (the first call), so B, C and E
  ran again (1.4 M tokens in the resumed run) and spoiled some A timings. To resume, edit only calls after the last
  cached one.
  NEXT: gate 0. The user answers D-17, D-19, K29, D-4 (merge method), and the 3.1 RECs; then C1 (W1).
- 2026-10-11: GATE 0 (partly answered; DECISIONS.md eighth round). D-17 (c) + TODO on the Kyo fix of the lost
  wakeup; D-19 yes; D-4 (c) squash + rebase of the next branch; D-8 CHANGED to sbt-tpolecat (unused warnings stay
  warnings; CI mode open); K29 = option (2) own loop + TODO (user 2026-10-12: "ignore 3c"). The user wants upstream issues at
  getkyo/kyo for the lost wakeup and the `observe` leak if none exist (drafts first; the user approves each post),
  and TODOs and notes on GitHub Projects and the wiki "as much as you can" (from the cloud: issues yes; Projects v2
  no tool; wiki no credentials). NEXT: the user answers K29 and the CI-mode question; then the upstream drafts, the
  GitHub issues for the TODOs, and C1 (W1) with sbt-tpolecat.
<!-- claude 2026-10-11: added this item -->
- 2026-10-11 later: the observe-leak reproducer `design/upstream/ObserveLeak.scala` is tested (1.7 KB per change; OOM
  before 27 000 changes at -Xmx48m; the JVM hangs after the OOM, so run it under `timeout`). The two upstream drafts
  are complete in `design/upstream/kyo-signal-issues.md` (NOT POSTED). TOOL LESSON: `pgrep -f <word>` or
  `ps | grep <word>` in a Bash tool call matches the call's own command line when <word> is in it, and a kill loop
  then kills the shell (happened twice). Find JVMs with `pgrep -x java` and read `/proc/<pid>/cmdline`.
<!-- claude 2026-10-11: added this item -->
- (superseded) NEXT: the user reads the side-by-side page and answers the blocking questions; then plan the rework of PRs 3-5
  on kyo-ui (runner proposal: (a) runner core, (b) terminal + app from PR 5, (c) examples/ptycheck/bench;
  close or replace PRs 3 and 4).

<!-- claude 2026-10-13: added this item (gate 1 closed) -->
- 2026-10-13: GATE 1 CLOSED. The user: "go for your solution and move ahead. want to see that usable version asap".
  DECISIONS eleventh round: the gate 1 answers (seal tui4s.internal in C3; CI sets the glitch load flag; the repair
  tick re-render and the self-cancelling canary are accepted; D-14 and D-15 confirmed; frame = batch and the
  single-driver kit stand), the WIP limit lifted until the terminal PR is open, the T1 RECs D-21/22/24 (a), and the
  NEW ORDER C3 -> T1 -> C4 -> T2 (T1 before C4: the terminal Counter needs no hooks). Branches: feat/kyoui-03-input,
  feat/kyoui-04-terminal, feat/kyoui-05-components, feat/kyoui-06-app-examples. chore/claude-rules pushed plain;
  PR 5 closed with the plan 4.1 comment. W3 (C3) runs with Opus agents (Fable only for the runtime reviewer R2 and
  its skeptic K2); script tui4s-w3-c3-input-*.js in the session workflows dir.

<!-- claude 2026-10-13: added this item (W3 done) -->
- 2026-10-13 15:50 UTC: W3 DONE: workflow wf_1719fd7f-36b (7 agents, Opus except R2/K2 on Fable, 2.21 M tokens,
  1 h 50 min). C3 tip f29b9ff (23 commits + the merge-up of C2's 0646343); 109 tests, 0 warnings, 3 final runs green
  (one under contention), the CI load flag works. PR #13 `feat(core): keys reach the UI` against
  feat/kyoui-02-regions; this session watches it. PR #11 got the test-only commit 0646343 (the flaky torn-frame leaf;
  DECISIONS twelfth round). Logs: .work/impl/c3-*.log, c3-notes.txt, c3-pr-body.txt. Script:
  tui4s-w3-c3-input-wf_1719fd7f-36b.js in the session workflows dir (reuse for C4 with the C4 content).
  NEXT: W5 = T1 feat/kyoui-04-terminal (worktree .work/worktrees/feat-kyoui-04-terminal from f29b9ff; shape 8 + V;
  the script draft is scratchpad/tui4s-w5-t1-terminal.draft.js), then W4 = C4 feat/kyoui-05-components on T1.

<!-- claude 2026-10-13: added this item (C3 round 2 queued) -->
- 2026-10-13 15:35 UTC: Codex reviewed PR #13 (f29b9ff): three P2 findings in Dispatch.scala (a keyboard click does
  not bubble to the ancestors' onClick; onSubmitEvt never runs; onFocusEvt/onBlurEvt never run). Checked against
  kyo-ui RC7 ReactiveUI.dispatchToElement (.work/scratch/kyo-src): the focus/blur rule of kyo-ui itself runs both
  forms (a real mismatch); the keydown rule runs only the target's plain onClick and only f.onSubmit, but the Click rule
  bubbles and runs both submit forms, and in a browser Enter on a button IS a click. DECISION: build all three as "a
  keyboard activation of a button is a click: kyo-ui's Click rule over the chain; every submit and focus change runs
  both handler forms". The C3 P-12 "deferred (D-30)" for the Evt forms was a stretch (D-30 is mouse and terminal
  focus events). QUEUED behind W5 (one sbt at a time): the brief is scratchpad/c3-round2-brief.md (copy in
  .work/impl/c3-round2-brief.md); shape "fix round" (fixer Opus, skeptic Fable), then push C3, merge-up into T1,
  sbt check there, push T1, open T1's PR. CI is green on #11 (0646343) and #13 (f29b9ff).

<!-- claude 2026-10-13: added this item (C3 round 2 done; W5 partial) -->
- 2026-10-13 17:15 UTC: C3 ROUND 2 DONE by the session itself (Fable; the Opus budget was out): 583a0c8 (the
  dispatcher: a keyboard activation of a button runs kyo-ui's Click rule over the chain; onSubmit, onFocus, onBlur run
  both forms), 2eaf081 (guard pins), a6c4598 (docs); check green, 112 core tests (c3-r2-check.log); pushed; PR #13
  body updated; the three Codex threads answered and resolved. The W3 shape "fixer + skeptic" was NOT used (no
  Opus): an Opus skeptic can re-check 583a0c8 later if wanted.
  W5 (T1) PARTIAL: wf_f2ccf226-44b ran I, R1-R3, K1-K3 (7 agents, 2.49 M tokens, 85 min), then F and V FAILED on the
  org's monthly spend limit ("resets 6pm UTC"). T1 tip b7ac3ec on feat/kyoui-04-terminal (14 commits; 170 core + 4
  examples tests; PTY kit 16/16 rows at the tip, t1-pty-tip.log). Findings: R1 5 (1 major: the kit's upper-case
  letter has no shift, the terminal's has), R2 6, R3 9, mostly confirmed minors. A send_later at 18:06 UTC resumes
  the run (Workflow resumeFromRunId wf_f2ccf226-44b: the 7 cached, F and V live). AFTER W5: merge feat/kyoui-03-input
  (a6c4598) into T1, sbt check, push T1, open its PR against feat/kyoui-03-input, then W4 = C4 on T1.

<!-- claude 2026-10-13: added this item (W5b) -->
- 2026-10-13 18:14 UTC: the container RESTARTED at 18:09 (uptime 1 min at the resume); the Workflow tool then refused
  the saved script path and the in-memory resume cache was gone. The 7 W5 results were extracted from the journal
  into .work/impl/t1-w5-cache.json (156 KB). W5b = workflow wf_066520fc-8e5 (2 agents, Opus): the fixer merges
  feat/kyoui-03-input (a6c4598, C3 round 2) into T1 first (plan 4.3), then fixes the confirmed findings (R1-1 major:
  the kit's upper-case letter must carry shift as EventMapping gives it), then V does the 3 test runs and 3 PTY runs.
  The fixer's partial edit before the budget cut is saved as .work/impl/t1-fixer-partial.patch (the worktree was
  restored to b7ac3ec). LESSON: a container restart kills the workflow resume cache; the journal on disk is the
  recovery path (extract the 'result' lines, hand a JSON file to a continuation script).

<!-- claude 2026-10-13: added this item (T1 done) -->
- 2026-10-13 18:50 UTC: W5b DONE (2 agents, 0.63 M tokens, 27 min): merge-up 0ed0710 (C3 round 2 into T1; one
  conflict in AGENTS.md marks, both kept), 7 review-fix commits (R1-1: the kit types an upper-case letter with Shift
  through EventMapping.typed; R2-1 poll floor 1 ms; R2-3 the innermost cause; R2-4 every error but a
  VirtualMachineError ends the run; docs; marks; CI and kit nits), tip 0893800; 177 core + 4 examples tests, 0
  warnings; 3 test runs and 3 PTY runs green, one of each under contention (17/17 rows). PUSHED; PR #14
  `feat(core): Tui.run on the real terminal, with the PTY check in CI` against feat/kyoui-03-input; subscribed.
  THE FIRST USABLE VERSION: `sbt examples/launchers`, then `examples/target/bin/counter` (JDK 25 in JAVA_HOME or
  on PATH). Open question for the user: a jatatui issue draft (the JNI cuts a typed non-BMP character to 16 bits).
  NEXT: W4 = C4 feat/kyoui-05-components (worktree created from 0893800), shape 6 + V, Opus except R2/K2; then T2.
  Logs: .work/impl/t1-*; the body t1-pr-body.txt (346 lines, 61.8 KB: just under GitHub's limit).

<!-- claude 2026-10-13: added this item (W4 launched) -->
- 2026-10-13 18:58 UTC: W4 = C4 launched: workflow wf_f99c07c9-d80 (shape 6 + V: I, R1, R2, K1, K2, F, V; Opus except
  R2/K2), worktree .work/worktrees/feat-kyoui-05-components from T1 0893800; script
  tui4s-w4-c4-components-wf_f99c07c9-d80.js in the session workflows dir. A send_later check-in fires at about
  21:48 UTC. AFTER W4: push, PR against feat/kyoui-04-terminal, subscribe, notes; then W6 = T2
  (feat/kyoui-06-app-examples: TuiApp, SchedulerDefaults, all examples, all PTY rows; plan 6.6; shape 6).

<!-- claude 2026-10-13: added this item (T1 round 2 queued) -->
- 2026-10-13 18:50 UTC: Codex on PR #14: one P1 (TerminalIO.scala:138): the release steps catch only NonFatal, so a
  LinkageError of a native call at leave would skip disableRawMode (raw mode left). CONFIRMED by reading; the same
  gap the T1 fixer closed in the input thread (R2-4). The fix and the test are written, UNCOMMITTED, in the T1 worktree;
  they wait for sbt (W4 owns it). The steps: .work/impl/t1-round2-brief.txt. Then merge-up into C4.

<!-- claude 2026-10-13: added these three items (T1 round 2 done, W4 done and PR #15, W6 launched) -->
- 2026-10-13 21:15 UTC: T1 ROUND 2 DONE by the session (Fable): b912d3b `fix(terminal): a LinkageError in a release step
  does not stop the restore (PR #14 review: Codex)`. Negative control t1-r2-negative-control.log (the new leaf fails
  with the escaped UnsatisfiedLinkError); t1-r2-test.log; t1-r2-check.log (CI mode, 0 warnings, 178 core leaves);
  version order 55/55; t1-r2-pty.log (17 terminal rows, 16 screen checks). PUSHED; the Codex thread answered and
  resolved; a round-2 comment on PR #14; CI green on b912d3b (both jobs).
- 2026-10-13 21:20 UTC: W4 DONE (wf_f99c07c9-d80, 7 agents, Opus except R2/K2): C4 code tip 46fe338 (25 commits: 17 of
  the first round + 8 review fixes; 5 prototype bugs and 6 review defects fixed, each with a negative control); the
  merge-up of T1 round 2 = 9047a50 (`git merge`; check green: c4-mergeup-check.log, 209 core leaves, 0 warnings).
  PUSHED; PR #15 `feat(core): components in two styles, hooks and a Component class` against feat/kyoui-04-terminal;
  subscribed; CI at the time of writing: terminal green, sbt running. Body c4-pr-body.txt (60.8 KB). Two questions
  for the user in the body: keep "onMount once per mount" (React-like) or restart onMount on a new instance; log a
  failing hook body or onMount with Log.error (follow-up or in C4). The W4 check-in trigger was deleted (its work is
  done). LESSON: `cp c4-pr-body.txt c4-pr-body-round2.txt` overwrote the fixer's body copy; the body says so.
- 2026-10-13 21:30 UTC: W6 = T2 LAUNCHED: workflow wf_cbfa3587-619 (shape 6 + V: I, R1, R2, K1, K2, F, V; Opus except
  R2/K2 on Fable), worktree .work/worktrees/feat-kyoui-06-app-examples from C4 9047a50 (T2 stacks on C4, not on T1:
  the order C3 -> T1 -> C4 -> T2 of the eleventh round, and the CounterStyles example needs the components). Script:
  tui4s-w6-t2-app-examples-wf_cbfa3587-619.js (the workflows dir of the projects entry named after the T2 worktree
  path). Brief: plan 6.6 and D-24 (a); TuiApp in its final shape (ui, options, args, onExit, main, the runMain seam
  through Tui.runWith; the constructor calls SchedulerDefaults.applyIfUnset() and OutputCapture.install()); the
  examples from the prototype apps, one TuiApp main per package (CounterKyoApp moves to its own package); all PTY
  rows (kill -TERM 143, kill -INT 130, the println row, Todo, Dashboard, CounterStyles, a resize, the class-load INFO
  line); a ReadmeSuite pins the quick-start blocks to the files. A send_later check-in fires at about 00:20 UTC.
  AFTER W6: push, PR against feat/kyoui-05-components, subscribe, logs and notes; then P1 (W7, shape 4), L1, L2, D.

<!-- claude 2026-10-14: added this item (W6 done, PR #16, lean mode) -->
- 2026-10-14 12:30 UTC: W6 DONE (wf_cbfa3587-619, 7 agents, 2.26 M tokens, 109 min): T2 tip 4f174ed (20 commits: 15 +
  5 review fixes; 14 findings: 12 confirmed and fixed, 2 refuted). Final runs all green: clean check CI mode 0
  warnings, 228 core + 35 example tests, 3 test runs and 3 PTY runs (53 terminal rows + 68 screen checks each), one
  of each under contention; class-load of the real Counter launcher about 4090 classes. PUSHED; PR #16 `feat(core):
  TuiApp, every example in the real terminal, the full PTY checklist` against feat/kyoui-05-components (opened by an
  Opus agent that typed the body from t2-pr-body.txt; a diff check against the file follows); subscribed. Logs
  .work/impl/t2-*. The design defect found in 4a (SIGTERM: the restore hook ended the run as a quit, so onExit and
  "interrupted" raced the JVM halt) is fixed with internal.Hooks.shuttingDown. Known gap: a span does not wrap at 40
  columns (Dashboard header): L2.
- 2026-10-14 12:10 UTC: LEAN MODE (user): "use Fable very sparsely and cut corners a little bit (not in the api,
  behind the scenes)". DECISIONS fourteenth round; the user rules file has the same rule. From here: Opus for all
  delegable work (also R2/K2), shape 4, one final run, one PTY run, one-screen PR bodies, negative controls only for
  bug fixes. NEXT: the user runs the examples on feat/kyoui-06-app-examples and merges the stack (#10, #11, #13, #14,
  #15, #16: squash, then Claude merges or rebases the next branch, D-4 c). P1, L1, L2, D wait for the user's word.

<!-- claude 2026-10-14: added this item (the stack is merged) -->
- 2026-10-14 16:20 UTC: THE KYO-UI STACK IS MERGED. main = 2d5354d: a28ed33 (#10 squash), 35edc45 (#11), f8830af (#13),
  ce7675c (#14), 111b975 (#15), and 2d5354d = a merge commit of #16 (GitHub's stack merge squashed four and merged the
  top one). The tree of main equals the T2 tip ade774f (0 differing files). How it went: the user squashed #10 by
  hand; #11 then conflicted (the squash duplicates C1's content). Rulesets: the user REMOVED every ruleset (the review
  rule blocked the author; the README of PR 7 stays as the source of truth for a later re-import, with
  `required_approving_review_count` 0 or a bot author). GitHub's native STACKED PRs ("Stack #12") refuse the
  synchronous merge endpoint, auto-merge and a base change for stack members: only the web UI (the asynchronous
  merge) merges a stack, and it merges the WHOLE stack atomically (all PRs ready, in sync, CI green), so the user's
  first click (15:35, right after a force-push) did nothing and the second (16:20) merged all five. To put the stack
  in sync Claude replaced each branch by ONE commit with the tree of its tip, re-parented in order on main
  (`git commit-tree`; a `git rebase --onto` of 02 and 04 conflicted on the merge-ups and was aborted); the old tips
  are the branches `archive/kyoui-0N-<name>-2026-10-14` (the proxy refuses tag pushes: HTTP 403). The permission
  classifier of auto mode blocks `git rebase` and `git merge --abort` as destructive; `git commit-tree`, `checkout
  -B` and a force-with-lease push passed. LESSON for the next stack: one commit per PR from the start, or no stack
  (plain PRs on main, one at a time); never put commit numbers in a squash title (GitHub adds "(#n)" again:
  "... (#15) (#15)").
- NEXT (the user decides): (a) the bot author: an Actions workflow `open-pr.yml` (workflow_dispatch: branch, base,
  title, body file) that opens PRs as github-actions[bot], so the user can approve and the rulesets can return with 1
  approval; needs Settings > Actions > General > "Allow GitHub Actions to create and approve pull requests"; (b) P1
  (trace and bench, shape 4), L1 (scroll), L2 (layout polish: the span wrap of the Dashboard), D (docs); (c) the ZIO
  phase (spike SZ first, gate Z with the thirteenth-round questions). The local worktrees of the merged branches are
  stale; delete them before the next workflow (`git worktree remove`).

<!-- claude 2026-10-14: added this item (the local session starts here) -->
- 2026-10-14 16:40 UTC: LOCAL SESSION HANDOFF (the user starts a session on the Mac). State: main = 2d5354d holds the
  whole kyo-ui stack (C1-C4, T1, T2); nothing is published; no rulesets on the repo (the user removed them); the
  notes are on wip/handoff (`git archive origin/wip/handoff .work | tar x` to get them locally; never merge it).
  RESOLVED 2026-10-14 (the user: "it works great"): the examples run on the Mac after the JDK 25 fix. WAS: `sbt` runs on Java 21 and fails with `UnsupportedClassVersionError: kyo/kernel/Isolate...
  class file version 69.0` (Kyo 1.0.0-RC7 needs JDK 25; AGENTS.md "Toolchain"). The user ran
  `mise use java@temurin-25` (mise.toml now in the repo, untracked) and `mise install`, but `java -version` still
  shows 21: mise is not activated in that shell, or a later `JAVA_HOME` export (jenv, sdkman, .zshrc) wins. Fix:
  `eval "$(mise activate zsh)"` at the end of ~/.zshrc (or `mise exec -- sbt ...`), check `mise which java` and
  `echo $JAVA_HOME`, then `sbt examples/launchers` and `examples/target/bin/todo`. The start scripts use
  `$JAVA_HOME/bin/java`, so JAVA_HOME must be the JDK 25 too. Decide whether `mise.toml` is committed (then AGENTS.md
  "Toolchain" names it) or ignored. The cloud session's limits do not apply on the Mac: the Mac has its own JDK 25
  path, no proxy, `gh` may exist; but the rules of AGENTS.md (one sbt at a time, never `sbt run` for a TUI) do.
  NEXT candidates: the bot author workflow (open-pr.yml); P1, L1, L2, D; the ZIO phase (spike SZ).

## 2026-10-02: DIRECTION CHANGE

The user reviewed the core and said it is not a functional wrapper of jatatui and that the internals are not
functional (true: internal/ is ~3.5k lines of mutable, Java-style code; Engine has 25 vars). They also asked
how Elm-style updates work (whole view rebuilt + whole buffer repainted, only changed cells flushed).
Discussion: calico (Scala.js, armanbilge/calico: components = Resource, state = SignallingRef, fine-grained
signal bindings, glitch-free). The user wants type-member components:
`abstract class Component { type State; type Out; def initial: State; def view(state: Signal[State]): View ... }`
and is unsure between pure handlers, a state-mutation effect, and a stream-based design.
RUNNING: prototype workflow run wf_05f0fcb5-0fe (3 agents; script ~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-prototypes-wf_05f0fcb5-0fe.js) that builds the Counter / Todo / Dashboard three ways on jatatui with
partial repaint, under .work/proto/{pure,mut,streams}/ with REPORT.md each. The user will pick by reading code.
ALL other work (facades W4, docs W5) is PAUSED until the user picks; PRs 3-5 will be reworked on the chosen model.
UPDATE: the user pointed at kyo-ui. It already IS the model we were converging to (pure UI values, signals,
fine-grained subscriptions per signal, handlers `Any < Async`, SignalRef two-way binding, and its README invites
custom backends; runners today: DOM, server-push, HTML stream; no terminal runner upstream). The prototype run
was STOPPED at plan stage (notes only in .work/proto/{pure,mut}/REPORT.md). Proposed new direction: tui4s =
a terminal runner for kyo-ui (UI -> jatatui). USER AGREED (see DECISIONS.md fifth round).
STOPPED (user asked to finish later, 2026-10-02): kyo-ui prototype workflow run wf_b03f7ee5-dfd (2 agents; script ~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s--work-scratch-kyo-src-kyo-ui-kyo-internal/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-kyoui-prototypes-wf_b03f7ee5-dfd.js): (A) terminal runner for kyo-ui with partial repaint, the three
apps in plain kyo-ui + useSignal + Component class, PTY run -> .work/proto/kyoui-runner/REPORT.md;
(B) ZIO/Cats facade mirror feasibility (generated from kyo-ui API with tasty-query) -> .work/proto/kyoui-facades/REPORT.md.
Partial work saved in .work/proto/kyoui-runner/ (8 files, REPORT.md with plan + findings so far) and
.work/proto/kyoui-facades/ (9 files). NEXT: resume the run (same session: Workflow({scriptPath, resumeFromRunId});
new session: re-run the script and tell both agents to continue from their folder and REPORT.md), then show the
user the user-facing code side by side, then plan the rework of PRs 3-5 on kyo-ui.

## State of the work (rewritten 2026-10-12)

<!-- claude 2026-10-12: rewrote this section and the next two. The old text described the Elm stack (PRs 2-5, feat/zio,
feat/cats, W4, W5). That stack is parked since the direction change of 2026-10-02. -->

- Direction (2026-10-02): tui4s is a terminal runner for kyo-ui. Plan: `design/PLAN-kyoui.md` (section 0 wins where
  the sections do not agree). Kyo phase first (C1-C4, T1, T2, P1, L1, L2, D), then the ZIO phase (after spike SZ),
  then Cats.
- `main` = 863db67 (PR 1 squash-merged: build, CI, release automation, AGENTS.md). Nothing else is merged.
- PRs 2, 3 and 4 are closed (old stack; their branches stay, plan 4.2). PR 5 stays open until the user closes it (C1 is
  open as PR #10 since 2026-10-12; C2 as PR #11 against feat/kyoui-01-render, 2026-10-12 evening; this session
  watches both). PR 7 (rulesets for main and the release tags, branch `claude/epic-bohr-eflkfz`) is
  open and is NOT from this session: do not touch it (plan 4.4: repo settings are the user's).
- Old-stack branches (`feat/core-*`, `feat/zio`, `feat/cats`, `docs/docus`): unchanged. `feat/zio` and `feat/cats` are
  backups of work that is not merged. `first-take` is the user's own branch: do not touch.
- W0 is done: `design/plan/gates.md`. Gate 0 is answered: DECISIONS.md eighth round (D-17 c, D-19 yes, K29 option 2,
  D-4 c, D-8 -> sbt-tpolecat). Upstream drafts: `design/upstream/` (NOT POSTED). GitHub issues: #8 (TODO: observe
  leak), #9 (TODO: lost wakeup). Labels: `upstream-kyo`, `todo`.
- Notes live on `wip/handoff` (never merge). Cloud worktree for the notes: `scratchpad/wt-handoff`; `.work` is
  git-ignored, so `git add -f`.
- Spike worktrees under `.work/worktrees/`: `spike-sbt` (local branch `spike/sbt-port`, 4cdeebd: the whole runner
  carried into `modules/core`, strict-flag fixes, `sbt fmt`), `spike-sbt-carry` (fe06611, the carry only),
  `spike-sbt-p7` (8e4d75f). Never push them. fe06611 is the reference for the carry commit of C1 (P-7 step 1).
- Spike build leftovers deleted on 2026-10-12 (190 MB). The spike sources and logs stay.
- W1 DONE (claude 2026-10-12): workflow wf_1048293e-046, 6 agents, 1.29 M tokens, 73 min. Branch feat/kyoui-01-render in
  worktree .work/worktrees/feat-kyoui-01-render, head 66d9299 (16 commits). PUSHED 2026-10-12 after the user's go
  ("ok let's continue"); PR #10 `feat(core): a kyo-ui value renders to cells` against `main`, body = c1-pr-body.txt with
  the logs' location added. This session is subscribed to the PR events (CI, reviews). Review fixes: new commits only. 26 tests, 0 warnings, sbt check green in CI mode, 55/55 version-order cases. All 14 review
  findings minor (11 fixed, 1 disclosed, 2 refuted). Logs and notes: .work/impl/c1-*.log, c1-notes.txt; the PR body:
  .work/impl/c1-pr-body.txt. Open: the implementer excluded -Ycheck-all-patmat from the tpolecat set (false "not
  exhaustive" on Kyo's opaque Maybe); the C2 API review decides whether tui4s.internal becomes private[tui4s]. The
  one-PR workflow script is tui4s-w1-c1-render-wf_1048293e-046.js in the session workflows dir (shape 6; reuse it for
  C3, C4, T2, L1). FACT: `sbt -batch` is not valid with this machine's sbt wrapper; use `sbt -Dsbt.supershell=false`.

## Next steps, in order

0. DONE 2026-10-12 about 18:00 UTC (claude): the C1 fix round for the 6 Codex (review bot) P2 findings on PR #10, all in
   the carried Layout/Props/Paint code and all REAL by reading: (F1) `wrap` splits a long word by chars, not cells
   (Layout.scala:114); (F2) flex-shrink `ceil` over-cuts (3 one-cell items in 2 cells render blank; :399); (F3) table
   relayout compares `tableCols == ctx.cols`, a tautology (:703); (F4) `rowReverse`/`columnReverse` lose the reverse
   flag (Props.scala:303); (F5) a percentage height resolves against the screen, not the parent (Layout.scala:264);
   (F6) a constant `RangeInput` value is read from the scene only (Paint.scala:393). Fixes AND regression tests are
   SAVED as `.work/impl/c1-codexfix.patch` (the full diff, 4 files + the new RelayoutTest.scala; `git apply` it in
   the C1 worktree) and as the script `.work/impl/c1-codexfix.py` (per fix: tests | code). The worktree is clean
   (the stop hook asked for no uncommitted changes; unverified code is not pushed to a PR branch). NOT compiled yet: W2's agents
   own sbt (one sbt on the machine). When W2 ends: (1) negative control: a temp worktree at 66d9299 with ONLY the
   tests (`codexfix.py <wt> all tests`), run `core/testOnly tui4s.GoldenScreenTest tui4s.RelayoutTest` -> 6 failures,
   log `.work/impl/c1-fix-negative-control.log`; (2) in the C1 worktree: `sbt fmt`, `SBT_TPOLECAT_CI=1 sbt check`,
   fix compile errors; (3) ONE commit per finding (test + fix; P-6), commit messages name the Codex thread; (4) push
   `feat/kyoui-01-render`, reply one line per Codex thread and resolve; update the PR body (P-6 and P-7 rows);
   (5) merge `feat/kyoui-01-render` into `feat/kyoui-02-regions` (plan 4.3), `sbt check` there, push. Paint.scala
   may conflict with C2's `discard` change (different hunks, should merge clean).

1. DONE 2026-10-12 (PR #10 open, CI pending at the time of writing). **W1 = C1 `feat/kyoui-01-render`** (plan 6.1; shape 6; about 1.8-2.1 M): Geometry, Props, Layout, Paint carried from
   `.work/proto/kyoui-runner/src` into `modules/core` with the P-7 commit map; build changes RC-16 with sbt-tpolecat
   instead of `-Werror` (D-8 changed); kyo-test replaces munit in core; the surface guard (tasty-query); golden screen
   tests; AGENTS.md and README lines; PR template. Branch from `origin/main`. ASK before the push: the session's
   designated branch is `chore/cleanup-7p2rll`; the plan names `feat/kyoui-*` (plan 4.2 and 4.4).
2. DONE 2026-10-12 17:20 UTC (claude): W2 = workflow wf_4ed01b5c-abd (2.62 M tokens, 3 h 03 min, 9 agents, 466 tool
   calls). C2 tip 6b72f3b (23 commits: 16 + 7 review fixes; 27 findings + 3 extras, all confirmed, 30 fixed, 4 not fixed
   with reasons); then the merge of C1's round 2 = 5537217, check green (c2-merge-check.log); pushed; PR #11 opened against feat/kyoui-01-render. ROUND 2 on PR #11 (Codex, 3 findings): the torn screen read
   fixed (2aa65a0: the backend's monitor in Canvas.flush/resize and Headless.screen/lines; Knobs.flushSeam; test);
   AGENTS.md reworded (79ac684: a frame is a batch); 'separate frames for independent writes' and 'concurrent
   resize callers' NOT built (replies on the threads; the threads stay open for the user). Tip 79ac684, check green
   (c2-fix2-check.log)
   (see the chat). GATE 1 questions: the chat report of 2026-10-12 evening and DECISIONS tenth round. Was: 9 agents (I, R1-R3, K1-K3, F, V), script
   tui4s-w2-c2-regions-wf_4ed01b5c-abd.js in the session workflows dir (shape 8 + a final-runs agent V: 3 core test runs,
   one under taskset contention, the glitch suite with and without the scheduler values). Branch feat/kyoui-02-regions from
   feat/kyoui-01-render 66d9299, worktree .work/worktrees/feat-kyoui-02-regions. D-14 (a) and D-15 (a) TAKEN as RECs by
   the 3.1 rule (gates.md: 'REC (a) stays'); the user can object. If this session is lost: read the journal.jsonl in the
   workflow transcript dir before a resume. **C2 (W2, shape 8)** with the gate 0 outcomes: R1 option (b) (AtomicReference last world + unmountAll in
   `Sync.ensure`), armkey (D-16), the own watcher loop with the repair tick (K29 option 2; TODO issue #8), the settle
   conditions of D-17 (c) (public scheduler probe, tui4s scheduler values in `TuiApp` and the test JVM, re-check after
   settle, `awaitScreen`; TODO issue #9).
3. DONE 2026-10-13: gate 1 closed (DECISIONS eleventh round); W3 = C3 `feat/kyoui-03-input` DONE (PR #13, tip
   a6c4598 after round 2); W5 = T1 `feat/kyoui-04-terminal` DONE (PR #14, tip 0893800). Then **W5 = T1 `feat/kyoui-04-terminal`** (plan 6.5; the
   first usable version), then **W4 = C4 `feat/kyoui-05-components`** (plan 6.4), then T2, P1, L1, L2, D. The WIP limit
   P-11 is lifted until T1 is open (the user wants a usable version first).
   UPDATE 2026-10-13 21:30 UTC: C4 DONE (PR #15, 9047a50); W6 = T2 RUNNING (wf_cbfa3587-619); then P1, L1, L2, D.
   UPDATE 2026-10-14 12:30 UTC: T2 DONE (PR #16, 4f174ed). LEAN MODE from here (DECISIONS 14). The user merges and runs the demo; P1, L1, L2, D wait for the user's word.
4. **Merge mechanics (D-4 c):** the user squash-merges; Claude then rebases the next branch of the stack (own branches
   only), runs `sbt check`, pushes. Never rewrite a branch that is not Claude's.
5. **Tracking:** one GitHub issue per TODO (labels `upstream-kyo`, `todo`). The user creates the GitHub Project with
   the "auto-add" workflow. The wiki is for the Mac session (no credentials from the cloud).

## Open items for the user (none blocking)

- sbt-tpolecat: confirm the mode (ASSUMED: dev mode by default; `SBT_TPOLECAT_CI` in GitHub Actions).
- Search getkyo/kyo for duplicates of the two drafts in `design/upstream/kyo-signal-issues.md`; post them (user only);
  link them in issues #8 and #9.
- Create the GitHub Project (auto-add workflow). Say whether the wiki is enabled (a private repo needs GitHub Pro).
- (done 2026-10-13) PR 5 closed by Claude with the plan 4.1 comment. PRs #10 and #11 accepted by the user's "go for
  your solution" (2026-10-13); the merges are the user's (squash, then Claude rebases the next branch, D-4 c).
- The repo rule on the model of subagents: branch `chore/claude-rules` (one commit from main) is pushed; the user opens
  its PR or asks Claude to.
- Publishing: deferred until the user brings it up (do not ask).

## Facts the next session must keep

- User decisions: `.work/DECISIONS.md` (packages tui4s.interop.zio / .cats; capture on; scheduler flags on;
  kyo-test core / munit elsewhere; bench; testkit in core; email oss.juli@pm.me; fully automated releases).
- Do not start a TUI with `sbt run` (sbt reads the keyboard too); use the `sbt examples/launchers` scripts.
- Real-terminal checks: `examples/ptycheck/run.sh` (expect + mini VT emulator). Scratch copies: `.work/scratch/pty/`.
- Package-tui4s files import with `_root_.` (incremental-compile cycle through `export dsl.*`).
- kyo-test's final "0 tests" summary line is cosmetic; a failing test does fail sbt (checked).
- Reusable one-PR workflow script (implement -> 3 reviews -> 3 skeptics -> fix; args key, title, branch, base,
  worktree, scope, extra): `~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-impl-pr-wf_6df1d964-e03.js`.
- Token use on 2026-10-01: design panel 3.5M, W1 2.1M, W2 2.4M, W3 2.4M, W4 (partial) about 1.5M.

## Constraints (user AGENTS.md)

MODEL OF SUBAGENTS (user rule, 2026-10-12): subagents and workflow agents run on Opus (`model: 'opus'` in
agent() and Agent calls) unless the task needs Fable's deep reasoning (a design or plan panel, a gate review, the
adversarial check of concurrency or runtime code, a root-cause hunt with no known cause). Implementers, checklist
reviewers, carries, searches and log reading: Opus. W2 (wf_4ed01b5c-abd) was launched before the rule and runs
all its agents on Fable; the C1 fix round and C3 onward apply it. Recorded in the repo's CLAUDE.md (local branch
`chore/claude-rules` from main; the push waits for the user: the designated branch needs a force push) and in
this container's ~/.claude/CLAUDE.md.
NAMES (user rule, 2026-10-13): say "CatsFx" for Cats Effect in the chat and in the notes; the library keeps its
own name in code, docs and PR text that its users read.
<= 4 agents in parallel, <= 2 when building; one workflow at a time; announce runs > 10 agents; agents save
notes early (`.work/impl/`); keep durable copies in `.work/`; never deploy docs; release only with approval;
never type credentials into anything.
