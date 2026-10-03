# core-3: terminal, entry points, Kyo examples, CI checks and bench (C5 + C6 + C7) - implementation notes

Worktree: .work/worktrees/core, branch feat/core-3-terminal (on top of feat/core-2-engine 5a441a5).
Agent: implementer. Started 2026-10-01.

## Plan

Priority: C5 complete, then C6 complete, then C7 (minimal but working if context gets tight).

### C5 terminal and entry points (modules/core)
- spi/SchedulerDefaults.scala: no Kyo import; `applyIfUnset()` + a testable `applyTo(get, env, set)`.
- internal/EventMapping.scala: crossterm records -> tui4s events (SPEC 2.4 table), type patterns.
- internal/Crossterm.scala: a small `Crossterm` trait over the JNI calls (load, raw mode, execute, flush,
  poll, read, backend) so that the terminal control can be tested with a fake; `NativeCrossterm` = CrosstermJni.
  CrosstermInput(crossterm): InputPort = poll(ms) then EventMapping(read()).
- internal/LogSink.scala (file or memory ring of 200 lines; finish prints the ring to the real stderr),
  internal/TuiLog.scala (Log.Unsafe into the sink), internal/OutputCapture.scala (forwarding streams for
  System.out/err; target switches to the sink while the UI is up; restored on release and on enter failure).
- internal/TerminalIO.scala: the crossterm TerminalControl. enter = SPEC 5.9 open steps 1-4 (undo on failure);
  Session.open does 5 (Terminal.create) and 6 (input thread, from control.input()) and 7 (control.opened(
  terminal, release) registers the shutdown hook). leave = close steps 3-6.
- Seam changes (internal/Session.scala): TerminalControl gets `input()` and `opened(terminal, release)`;
  Session.close is synchronized (hook vs fiber); a failure after the session is built closes the session.
  CountingBackend.close(): no draw after the release started (hook race).
- Trace file warning: TraceWriter.open/write failure -> warn through the current Kyo Log (TuiLog under Tui.run).
- Tui.run / Tui.start public (TuiLog.let + TerminalIO); internal TuiRun for tests with a fake crossterm.
- TuiApp: constructor applies SchedulerDefaults + installs the output forwarders; main = runMain seam
  (fiber + shutdown hook + KyoApp.Unsafe.runAndBlock); exit code 1 with the message on failure.
- Tests: EventMappingTest (every key code / modifier / kind), SchedulerDefaultsTest, OutputCaptureTest,
  TerminalIOTest (fake crossterm: open/close order, undo on failure, NotATerminal / NativeAccessDenied mapping,
  hook added/removed, inline newline, capture printed after restore, TuiLog lines), TuiAppTest (runMain exit
  codes and messages), TuiHandle on Headless (update, send, states, stop), a guarded real-JNI NotATerminal test
  (runs only when /dev/tty cannot be opened).

### C6 Kyo examples (examples/)
- examples/src/main/scala/examples/{counter,todo,dashboard}/*.scala exactly as SPEC 3.1-3.3.
- munit tests (SPEC 6.3) + munit Test dep, Test / fork.
- examples/README.md: the real-TTY checklist of SPEC 8.2 for a human.
- README.md Quick start (Counter + JVM flags).

### C7 CI checks and bench
- Kyo-free shared surface test (tasty-query if it reads Scala 3.9 TASTy, else scala3-tasty-inspector).
- Facade-parity source generator (project/FacadeParity.scala; examples generates parity/<eco>/CounterParity
  from examples/src/parity/CounterBody.scala.txt; Kyo variant now, zio/cats added by Z1/K1).
- bench project (sbt-jmh): EngineBench, PaintBench, probes Burst/Latency/Idle/Dashboard/Quit/Soak;
  bench/BASELINE.md with one measurement of 7.3.

## Progress
- 16:40 C5 main written and compiling (0 warnings): spi/SchedulerDefaults, internal/{EventMapping, Crossterm
  (+NativeCrossterm, CrosstermInput), LogSink, TuiLog, OutputCapture, TerminalIO (+Hooks), TuiRun}, TuiApp,
  Tui.run/start public. Seam: TerminalControl.input() + opened(terminal, release); Session.close synchronized,
  seals the CountingBackend; a failure after the session is built closes the session. Trace-file failures warn
  through the current Kyo Log (TraceWriter.open(path, warn)).
  Tests: EventMappingTest 7, SchedulerDefaultsTest 6, OutputCaptureTest 7, TerminalIOTest 13 (fake crossterm),
  TuiAppTest 4 (runMain seam), NativeTerminalTest 1 (real JNI; no /dev/tty here -> it RAN and got
  NotATerminal), SessionTest +1. First run: 1 failure (enter undo order) -> fixed (leave commands registered
  before the enter execute). Log .work/logs/core-3-c5-1.log.
- 16:55 core/test green (235 leaves). COMMITS 1778e89 (internal terminal: TerminalIO, Crossterm, EventMapping,
  LogSink/TuiLog/OutputCapture, seam, trace warning, TuiRun + tests) and 83a0673 (Tui.run/start, TuiApp,
  SchedulerDefaults + tests). Incremental-compile check (TuiApp, Tui, Options, package+TuiApp, package+Tui+dsl
  touched): all pass (log .work/logs/core-3-incr.log). NOTE: a first incremental-check loop used
  `git checkout` on uncommitted files and reverted Tui.scala/Options.scala; re-applied before the commits.
  Next: C6 examples.
- 17:08 C6 COMMIT 67ac92c: examples (Counter, Todo, Dashboard as TuiApp, SPEC 3.1-3.3), munit suites (13 tests:
  6.3 + tab switch + failed load + live pause), examples/README.md (real-TTY checklist), README Quick start.
  Found: at 48 columns the Todo help bar cuts "d delete" (the SPEC test uses 80x24; the example test does too).
  Manual no-TTY run of examples.counter.CounterApp: "stdin/stdout is not a terminal" + cause, exit 1.
  Next: C7 (tasty-query check, parity generator, bench).
- 17:14 C7 part 1 COMMIT: tasty-query 1.9.0 (Test) SurfaceCheck + KyoFreeSurfaceTest (2 leaves, green). The
  probe without exclusions listed exactly the SPEC 2.2 Kyo-typed members plus CallSite's synthetic inline
  accessor (skipped as not API). Next: facade-parity generator, then bench.
- 17:26 C7 COMMITS 802fe7f (facade-parity generator: project/FacadeParity.scala, template
  examples/src/parity/CounterBody.scala.txt, ParitySuite; examples 15 tests green) and the bench project
  (sbt-jmh 0.4.8; EngineBench, PaintBench, Burst/Latency/Idle/Dashboard/Quit/Soak probes). Smoke: BurstProbe
  ~13 us/event at load 7.5; EngineBench equalView 2.9 us, changedView 1.7 ms. Next: measure all budgets once
  (alone), write bench/BASELINE.md, AGENTS.md commands, then fmt + check.
- 17:33 bench measured once (load 4-6, other work on the machine): JMH EngineBench equalView 2.25 us (B2 ok),
  PaintBench counter 0.86 / todo 1.86 / dashboard 1.61 ms, alloc 0.35 / 1.47 / 0.56 MiB per frame (B3: counter
  and dashboard over time budget, alloc ok); Burst 12.1 us/event (B1 miss, budget 10); Latency p50 1.56 ms, p99
  3.91 ms (B4: p50 ok with one paint 0.86 ms, p99 marginal miss); Quit p50 34 ms max 36 ms (B7 ok); Idle: Kyo
  defaults 11.0%, Kyo with tui4s flags 2.57%, Counter defaults 11.1%, Counter flags 2.77% (B5 ok); Dashboard
  5.7 points above idle (B6 miss, budget 2), 3.6 MiB/s (ok). Log .work/logs/core-3-bench-run.log. Soak 600 s
  running in background (log core-3-soak.log).
- 17:45 Soak 600 s: heap 7.00 MB mid and end (+0.09%), 18-19 threads, 3 sources, max 1 task, 6027 tasks done,
  0 dropped (B8 ok). bench/BASELINE.md written. TuiApp refactor (block helper) + one more TerminalIOTest leaf
  (app failure restores the streams) pending compile. Next: fmt + check, commit.
- 17:52 `sbt -batch fmt` + `sbt -batch check` GREEN (core 238 kyo-test leaves, examples 15 munit tests,
  versionPolicyCheck ok; log .work/logs/core-3-check-1.log). core/doc ok. No-TTY run of the Dashboard example
  after the TuiApp refactor: message + exit 1. DONE.

## Commits on feat/core-3-terminal
- 1778e89 core: the real terminal: crossterm control, input, output capture, run log
- 83a0673 core: Tui.run, Tui.start, TuiApp and the scheduler defaults
- 67ac92c examples: the Kyo Counter, Todo and Dashboard with munit tests (C6)
- 71c3049 test(core): the shared surface names no Kyo type (tasty-query)
- 802fe7f build: facade-parity source generator for the examples
- 657643f bench: an unpublished bench project with JMH and the probes of SPEC 7.4
- 6b12f8b core: TuiApp.main blocks through one helper; more release tests
- f5d7cb0 bench: first baseline of the SPEC 7.3 budgets; bench commands in AGENTS.md

## Deviations from SPEC (also in the structured result)
- Seam: TerminalControl gets input() (the control's own input, asked after enter) and opened(terminal,
  release) (the crossterm control registers the shutdown hook with the session release). The crossterm
  control is internal.TerminalIO over a small internal.Crossterm trait (NativeCrossterm = CrosstermJni), so
  the open/close order is tested with a fake. CrosstermInput takes the Crossterm, not the JNI object.
- Session.close is synchronized (a second caller waits) and seals the CountingBackend first (no draw after
  the release started: the hook can release while the render fiber runs). A failure after the session is
  built (opened throws) closes the whole session (input thread included).
- The enter undo registers the leave commands before the enter execute (a half-done command list is left).
- The first restore error is printed by TerminalIO.leave after the streams are restored, not by the app class.
- Output capture: forwarding streams installed as System.out/err; TuiApp installs them in its constructor so
  scala.Console binds to them (Scala println captured); with Tui.run only if scala.Console was not used
  before. One capture at a time (a nested start captures nothing).
- LogSink: the ring keeps only the lines printed after the restore (captured output + log lines at warn or
  above), newest 200, with "N earlier output lines are not shown"; a log file is appended to; with a log
  file nothing is printed after the restore; a log file that cannot be opened falls back to the ring + note.
- TuiLog level = Kyo's process level (Log.live.level: -Dkyo.Log.defaultLevel, default warn).
- Trace file failures (open or write) are a warn line in the current Kyo log (TuiLog under Tui.run).
- EventMapping: F(n) with modifiers is Key.Modified(Key.F(n), mods); SUPER/HYPER/META ignored; Ctrl wins over
  Alt for a character; Repeat kept. NativeAccessDenied for IllegalCallerException / UnsatisfiedLinkError /
  NoClassDefFoundError (also inside ExceptionInInitializerError); other load errors are TerminalIO.
- TuiApp exit report: the message; AppFailed/SourceFailed add the cause's stack trace; other TuiErrors add
  "  cause: <cause>"; an interrupted run (SIGTERM hook) prints "interrupted", exit code 1. runMain is a
  private[tui4s] seam returning the exit code; main calls System.exit only for a non-zero code.
- Parity: packages parity.on<name>; the template is an App[Int] (TuiApp differs per ecosystem); generated
  registry parity.Parity.apps; FacadeParity.ecosystems setting (Kyo only now).
- Surface check: tasty-query 1.9.0; public + protected members and parents of all main classes in tui4s and
  tui4s.testkit; compiler-made inline accessors (CallSite.inline$fromFrame) skipped.
- bench also depends on examples (the sample apps); SoakProbe allows the thread count to vary by <= 4.
- Example Todo tests use 80x24 (at 48 columns the help bar cuts "d delete").
