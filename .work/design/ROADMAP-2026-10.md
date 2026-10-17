# tui4s roadmap after the Kyo phase (draft for decisions, 2026-10-17)

This file uses ASD-STE100. It records the research of 2026-10-17 and the
decisions. STATUS (2026-10-17): the user APPROVED D1-D16 (D5, D9 and D10 as
changed by the user; the others as recommended). Open: D10 machine, D11
domain, D16 waits for the startup numbers. See DECISIONS.md, fifteenth round.
<!-- claude 2026-10-17: changed the status line after the user's approval -->

Raw research results (JSON and Markdown) are in `.work/explore-2026-10-17/`.

<!-- claude 2026-10-17: created this section -->
## 1. What the user asked for

1. A large example that shows how to structure a real application.
2. The same UI as a web application through kyo-ui.
3. Enough widgets to build most terminal applications.
4. A chat bot that can use ChatGPT or Anthropic (API key, or a subscription
   if it is permitted).
5. A "small Flutter": one widget tree that works in the terminal and on the
   web.
6. A spike for single-file components or JSX for Scala ("scalax").
7. A library that is ready to publish, with docs, live examples and
   benchmarks against Scala and other languages, and a comparison of
   expressiveness and correctness.
8. A hook library like VueUse.
9. A cost-effective functional rewrite of the core, with the user's skills
   `functional-design` and `scala-functional-design` as the rules: impure
   code only for performance, and only behind an effect.

<!-- claude 2026-10-17: created this section -->
## 2. Facts that the research proved

### 2.1 Same UI, terminal and browser (spike, measured)

- One kyo-ui value, built once from one application store, ran in the same
  JVM in the terminal (tui4s) and in headless Chromium (kyo-ui
  `UI.runHandlers` + kyo-http `HttpServer`). The state was shared live in all
  directions: browser to terminal, terminal to browser, browser to browser.
  23 of 24 checks passed; the one failure was a kyo-net WARN line after the
  restore, and `TuiOptions(logFile = ...)` or the kyo-net native jar fixes it
  (24 of 24).
- kyo-http adds no jars: kyo-ui already depends on it.
- Time from JVM start to the first terminal frame: about 1.25 s. The HTTP
  server is ready before the first frame.
- Start the server directly in `ui` under the application Scope. A forked
  fiber logs an ERROR at quit.
- The same view also ran as a Scala.js single-page application (sbt-scalajs
  1.22.0). One source file compiled in the JVM build and in the JS build
  without sbt-crossproject.
- Spike sources: `.work/explore-2026-10-17/spike-web/` and
  `.work/explore-2026-10-17/spike-js/`.
- User decision (2026-10-17): the targets must NOT communicate. The goal is
  the Flutter model: write the app once, build one terminal app and one web
  app, and run each one alone. The live sharing of the JVM spike is not a
  goal. The Scala.js spike is the proof that matters: one source file, two
  builds, a tui4s headless test and a browser test both passed.
  <!-- claude 2026-10-17: added this item from the user's answer -->

### 2.2 Web limits (measured)

- `px` is one cell in tui4s and one CSS pixel in a browser. Shared views must
  use `em` or `pct`.
- Security: the kyo-ui WebSocket accepts any Origin (cross-site WebSocket
  hijacking). There is no auth hook. A secret base path (capability URL) and a
  loopback bind are the only protection in RC7.
- `ui` in `runHandlers` is evaluated for each GET, each WebSocket session and
  each reconnect. Signals created outside that block are shared; signals
  created inside it are per session.
- Scala.js bundle: 33 MB (2.9 MB gzip) by default. The CLDR locale database
  is 61 % of it. With `locales-minimal-en_us-db` it is 7.9 MB (0.99 MB gzip)
  and mounts in 0.65 s.
- Upstream bug (JS): an input bound with `.value(SignalRef)` loses keys when
  keys come 10 ms or less apart.

### 2.3 What tui4s renders today

- tui4s honors 31 of the 76 kyo-ui Style properties and ignores 45 silently
  (Overflow, Position, ZIndex, TextWrap, WhiteSpace, TextOverflow, Display,
  FlexWrap and others). There is no scroll, no overlay, no wrap in rows and no
  ellipsis.
- Textarea is a single-line field. Mouse events are dropped. Svg and Chart
  show `[chart]`. Unsupported nodes never fail and never warn.
- Of 28 common terminal widgets, 5 work today, 22 have only a workaround and
  1 (textarea) cannot be built.
- Core features ranked by the number of widgets they unblock: viewport
  scroll/clip 12, wrap/ellipsis 7, overlay/z-order 6, native inputs 5, wide
  characters 4, multi-line input 3, terminal-size signal 2, mouse 1.

### 2.4 Code quality audit (93 impure sites)

- The verdict "whiles everywhere" is not correct: there are about 12 real
  while loops, almost all in edge files. The verdict "badly written" is
  correct for the shape of the code:
  - Edge code is written like Java: about 23 volatile or nullable fields,
    four copies of "run every step, keep the first error" with three catch
    policies, and exceptions for expected outcomes.
  - The painter writes into the jatatui buffer while it decides what to draw.
  - Runner and Mount use java.util.concurrent where Kyo has the primitives.
  - The examples teach wrong idioms. Todo's file IO is not deferred
    (`Abort.catching` does not suspend in RC7). TabsView hides global state.
  - Two latent bugs: a Panic becomes a clean quit; a `foreach` inside a
    `tbody` probably never updates.
- Classification: 29 sites stay as foreign boundaries behind one effect seam
  each, 2 kernels stay (Canvas.write, Canvas.diff), about 50 are replaced and
  13 are deleted.
- The skill review changed the plan: a synchronous release stack (one release
  for each acquire, as principle 10 needs; a JVM hook cannot run Kyo Scope
  finalizers because `Finalizer.close` only spawns a fiber), one JVM hook,
  `kyo-test-prop` for property tests (it exists for RC7), a `NoStackTrace`
  error taxonomy, final public shapes now, and less process (scalafix
  DisableSyntax only).
- Details: `.work/explore-2026-10-17/purity-audit.json` and
  `.work/explore-2026-10-17/skill-review.md`.

### 2.5 Chat providers and their rules (cited in the JSON)

- Anthropic: an API key is permitted. Max plans include monthly API credits
  for an API key. A claude.ai login or token reuse in a third-party app is
  forbidden. Driving the user's own `claude -p` is gray: the Agent SDK note
  forbids third-party products to offer claude.ai rate limits.
- OpenAI: an API key is permitted. The user's own Codex CLI signed in with
  ChatGPT (`codex app-server`) is permitted. "Sign in with ChatGPT" token
  sharing for open-source local apps is a preview (facts from search
  summaries only).
- Transport: kyo-http RC7 can stream SSE on a POST and is already on the
  classpath. The official Java SDKs are 39 MB and 69 MB core jars plus
  Kotlin, Jackson and OkHttp. Use thin SSE clients.

### 2.6 Other findings

- Hooks: most of VueUse is 1 to 3 lines of native Kyo. About 13 composables
  earn their place. Two need new core API (terminal size as a Signal; OSC 52
  writes).
- Trap: tui4s awaits each handler on the runner fiber. A handler that waits
  for a network call freezes input. Actions that wait must fork.
- Cross-target: write widgets once as plain kyo-ui values. Do not build an own
  render engine (6k to 12k lines). Fix units and theme instead.
- Templates: a `ui"""..."""` Scala 3 macro interpolator is feasible and
  runner-neutral. XML literals are a dead end (Scala 3 drops them).
- Benchmarks: tui4s will very likely lose on startup, memory and size, and can
  win on compile-time bug classes, cleanup guarantees and code size. Include a
  JVM peer (TamboUI) and a plain jatatui program to separate the JVM cost from
  the Kyo cost.
- Docs: the `docs/docus` branch is stale (it predates kyo-ui) and overclaims
  the ZIO and Cats modules. Copy only `docs/`; do not merge the branch.

<!-- claude 2026-10-17: created this section -->
## 3. The plan as a stack (dependency order)

Each layer needs the layers below it.

| # | Layer | Content | Size (estimate) |
|---|---|---|---|
| L0 | Functional rewrite | Phases P0-P6 of the skill-reviewed plan (lint, cheap wins, public shapes, Kyo state, release stack, examples, pure painter, sinks) | about 5,400 changed lines |
| L1 | Core renderer features | Viewport scroll (kyo-ui Overflow + scrollIntoView), wrap and ellipsis, overlay and z-order, native inputs, multi-line textarea, wide characters, terminal size Signal, Color.Var, one warning for each ignored property | 3k to 5k lines |
| L2 | Libraries | `tui4s-widgets` (plain kyo-ui functions, portable) and `tui4s-use` (about 13 composables, JVM and JS, no core dependency) | 3k to 5k lines |
| L3 | Flagship app | `examples.assistant`: chat with streaming, conversation list, settings, command palette, log pane; skill blueprint (pure domain, service traits, interpreters in `make`, app-scoped stores, Layer/Env wiring, in-memory test interpreters) | 2k to 3k lines |
| L4 | Web target | A standalone Scala.js build of the flagship from the shared module (static files, no server, no link to the terminal app); the same builds give the docs demos <!-- claude 2026-10-17: changed: no web mirror, per the user --> | 0.5k to 1k lines |
| L5 | Docs and benchmarks | Docus site on main, casts from the PTY kit, JS demos, compiled snippets; benchmark harness, rosetta tasks, mutant bug suite | large; partly outside Scala |
| L6 | Release 0.1 | Checklist: Scaladoc in CI, OS matrix, changelog, SECURITY.md, template, skip empty zio/cats, dry run, human approval | small |
| S1 | Templates (intent only) | Documented intent: a `ui"""..."""` Scala 3 macro interpolator that emits kyo-ui calls. No spike now (user decision, 2026-10-17: budget) <!-- claude 2026-10-17: changed: no spike --> | 0 |
| S2 | Spike: native image | GraalVM native-image for startup, for L5 | spike |

Why L0 comes first: L1 changes Layout, Paint and Runner. Scroll and overlay
are paint-time features. On a pure painter (P5) they are cheaper and testable
with properties. The lint of P0 stops new impure code during L1 and L2.

<!-- claude 2026-10-17: created this section -->
## 4. Decisions (the user answers these)

Each decision has a recommendation (R).

- D1 Order: L0 before L1. R: yes.
- D2 Rewrite details: (a) keep the `// claude <date>` marks, or move the
  history to the PR text when a phase rewrites the code; (b) accept the final
  public shapes A4/A5 (NoStackTrace TuiError, Chunk not Seq, Modifiers,
  CellColor, narrower hook effects, `FrameStats.focus: Option[String]`);
  (c) P7 (typed data model) stays out. R: (a) move to PR text for rewritten
  code, (b) yes, (c) yes.
- D3 Flagship app: one app, the AI assistant, inside the `examples` project.
  R: yes.
- D4 Providers: Anthropic API key, OpenAI API key and a scripted fake
  provider first; Codex app-server second; Claude Code CLI backend never in
  published code. Secrets from environment variables or a 0600 file. R: yes.
- D5 Web (changed 2026-10-17, the user does not want the targets to
  communicate): the web target is a standalone Scala.js app built from a
  shared module (crossProject JVM+JS, kyo-ui only). Platform services
  (storage, chat provider) are traits with one implementation for each
  platform, given through Layer. Needs sbt-scalajs in `project/`, which marks
  all modules unreleased (acceptable before 0.1). Open: how the web build of
  the chat bot gets an API key (the user's own key kept in the browser, a
  small proxy server, or the fake provider only). R: the user's own key,
  stored only in the browser, plus the fake provider for the docs demos.
  <!-- claude 2026-10-17: changed D5 from the user's answer -->
- D6 Modules: `tui4s-widgets` and `tui4s-use` as published modules in this
  repo with their own tags, cross-built JVM and JS, no dependency on core.
  R: yes.
- D7 Core scope for 0.1: L1 as listed, no mouse (D-30 stays). Newline key in
  the textarea: Alt-Enter and Ctrl-J, plus Shift-Enter where the terminal
  reports it. R: yes.
- D8 Web look: native web theme, terminal-look theme, or both. R: both, one
  Theme value rendered to CSS variables and to a terminal palette.
- D9 Templates: DECIDED (2026-10-17). Document the intent only; no spike now,
  because of the budget. If it comes back: a `ui"""..."""` interpolator,
  `${...}` holes, Metals first, in `experiments/scalax`, after L2.
  <!-- claude 2026-10-17: changed D9 from the user's answer -->
- D10 Benchmarks: place DECIDED (2026-10-17): in this repo, in `bench/`, with
  its own sbt build outside the root `build.sbt` (so no published module
  becomes unreleased), other languages in `bench/impl/<language>/`, and its
  own CI job. Stage 1 = Counter and Dashboard against ratatui, Bubble Tea,
  Textual, Ink, TamboUI, layoutz and plain jatatui; the headline is
  compactness, expressiveness, correctness and resource management; publish
  after 0.1. Open: who gives a bare-metal machine.
  <!-- claude 2026-10-17: changed D10 from the user's answer -->
- D11 Docs: copy `docs/` onto main in a new PR; domain and Vercel token
  owner. Open: domain.
- D12 Release: version 0.1.0-RC1 while Kyo is RC; skip empty zio and cats;
  Windows experimental. R: yes.
- D13 Upstream drafts (the user posts them): Origin/auth hook for
  `runHandlers`; the JS controlled-input race; a cell or ch Length unit;
  access to scrollIntoView. R: yes, drafts only.
- D14 Process: lean mode stays; a skeptic only for concurrency and
  terminal-restore code. R: yes.
