<!-- claude 2026-10-09: created this file from the structured outputs of W0 (wf_bf024cec-c9f); the subagent harness refused report files -->
# W0 spike report: s4-weight

The spike agents could not write this file (the subagent harness refused report files). The orchestrator wrote it from their structured outputs, without changes to the content.

## S4 kyo-ui weight (K6), agent E (agent E)

- Verdict: **GO**
- Re-run: `bash /home/user/tui4s/.work/spikes/s4-weight/run-s4.sh 10   (run it ALONE on the machine; it builds, does the probe runs, 10 interleaved timed runs per configuration and the link probes; it moves the logs of the run before into logs/run-<time>/)`
- Ask the user: none

### Numbers and findings

- Verdict GO. The class, thread and native part is final. The time part is PROVISIONAL: other agents ran JVMs (load1 2.6-5.0), and agent M must re-run it alone.
- Classes: 0 classes of kyo-http, kyo-net or kyo-ffi load in the Counter run (3548 classes in total; kyo-ui 214, the first at 0.80 s; kyo-core 305; scala-library 525; JDK 1606). Method: an assembly jar, -Xlog:class+load, and an index from class name to jar made from the scala-cli class path (logs/classes-per-jar.tsv, logs/classes-per-jar.log, logs/classload.log).
- Native code: the only native library that is not from the JDK is crossterm (/tmp/jni-<n>/libnative-x86_64-linux-crossterm.so). The JDK loads libnet and libnio at 0.69 s when crossterm unpacks its library with a FileChannel (logs/library.log).
- Threads after the first frame: 31-32. Kyo threads: kyo-scheduler-w (3-4 workers), kyo-scheduler-t (4), kyo-scheduler-c (1), kyo-core-clock (1). There is no thread of kyo-http or kyo-net. File descriptors: 2 unix sockets (a socket pair) and 1 epoll; no tcp or udp socket (logs/snapshot-probe-assembly-1.log).
- Without kyo-http, kyo-net, kyo-ffi, kyo-system, kyo-schema and kyo-schema-json on the class path, the thread names, native libraries, fd kinds and socket kinds are the SAME. So the socket pair and the epoll do not come from kyo-net (probably crossterm; I did not verify this with a jatatui-only app) (logs/s4-summary.log, logs/snapshot-probe-nohttp-1.log).
- First frame (ms from the start command to the first 'Count:' in a 100x30 PTY; the method of the prototype; 10 runs per configuration, interleaved). Set 2 (load1 mean 4.9-5.0; logs/run-210130/s4-summary.log): assembly median 1015 (930-1525), full class path 1089, class path without kyo-http 1130. Set 3 (load1 mean 3.5-3.7; logs/s4-summary.log): assembly median 1020 (800-1286), full class path 992, without kyo-http 902. Set 1 (load1 mean 2.6-2.8): assembly 918 (791-1266), full class path 897, without kyo-http 916; the second run overwrote the set 1 logs, so these numbers are only in this output. The GO limit is 1.0 s: the result is near the limit and depends on the load. Agent M must decide it on a quiet machine.
- Cost of kyo-http on the class path: the median difference 'full class path minus class path without kyo-http' is -20 ms (set 1), -40 ms (set 2) and +90 ms (set 3). The sign changes, so the difference is noise. No class of these jars loads, so there is no load cost to measure.
- Link probes (RC 3.1 confirmed for the API, not for the start path): the runner and the apps compile WITHOUT kyo-http (exit 0), and the Counter runs without the 6 jars (exit 0, first frame drawn). A probe that names UI.Href.Absolute does not compile without kyo-http ('Not found: HttpUrl'). Compiled with kyo-http and run without it, it fails with NoClassDefFoundError: kyo/HttpUrl$. UI.Href.Path works without kyo-http. So D-18 (b) stays 'not safe', and an upstream module split is possible for runners (logs/nohttp-compile.log, logs/probe-compile-nohttp.log, logs/probe-*.log).
- Weight: assembly 39.3 MB. Full class path: 30 jars, 38.3 MB. Without the 6 jars that only kyo-http brings: 24 jars, 29.0 MB (-9.3 MB: kyo-http 2.79, kyo-schema 2.39, kyo-net 1.76, kyo-system 1.68, kyo-ffi 0.34, kyo-schema-json 0.33).
- Correction to RC 3.1: kyo-config comes with kyo-core (kyo-core -> kyo-scheduler -> kyo-stats-registry -> kyo-config, RC7 POMs), so kyo-ui adds 7 jars (15.0 MB with kyo-ui 5.69 MB), not 8 (15.2 MB). Also, kyo-kernel RC7 puts jmh-core, jmh-generator-bytecode, jmh-generator-reflection, jmh-generator-asm, jopt-simple, commons-math3 and asm (3.0 MB) on the run-time class path of every Kyo app (compile scope in its POM). This is not caused by kyo-ui.
- Start profile: the alternate screen comes at a median of 644-774 ms, and the first frame comes about 250-350 ms later. The app wrote nothing to stdout or stderr (tui4s.log empty or absent; logs/s4-summary.log).
- Build: scala-cli assembly of a copy of the prototype runner (.work/spikes/s4-weight/runner/; no prototype file changed), 12 s. The jar (39 MB) is in .work/spikes/s4-weight/build/ (git-excluded) (logs/build.log).

### Problems (agent E)

- I did not write REPORT.md in .work/spikes/s3-drift/ or .work/spikes/s4-weight/. The subagent rules of this session forbid report .md files, and agent C got the same block. The report content is in this output. The orchestrator or agent M must write both REPORT.md files from it. The evidence files (logs, TSV tables, check outputs, scripts) are on disk.
- The timing numbers are provisional. Other agents ran JVMs during my runs (load1 2.6-5.0 in S4, 5-9 in the guard runs; nproc 4). The S4 first-frame median is near the 1.0 s GO limit (918, 1015 and 1020 ms in 3 sets). Agent M must run 'bash /home/user/tui4s/.work/spikes/s4-weight/run-s4.sh 10' alone.
- The set 1 S4 timing logs are lost: the second run of run-s4.sh overwrote them before I added the archive step. The set 1 numbers are only in this output. Sets 2 and 3 have logs (logs/run-210130/ and logs/).
- Maven Central sent HTTP 429 many times. The class path of RC5 needed 4 tries. run-guard.sh now retries up to 8 times with a back-off, and it skips a version (it says SKIPPED) if all tries fail. It never runs the guard on a partial class path.
- Extra scope: I also downloaded the RC5 binary jar (SHA-1 OK) and ran the guard on RC5->RC6, because the dispatch-rule change of the gate is in that step.
- Finding outside the scope: crossterm (jatatui 0.31.0) unpacks its native library to /tmp/jni-<n>/libnative-x86_64-linux-crossterm.so (918 KB) at each start and does not delete it. /tmp now holds 120 such folders (about 108 MB) from my runs and the runs of other agents. This affects T1 (Session, Crossterm) and the docs. I did not delete them, because other agents may still use some of them.
- The 2 unix sockets and the epoll fd in the Counter process are the same without kyo-net. I think that crossterm makes them, but I did not verify this with a jatatui-only app.
- The guard rule list (20 names) is a choice. safeDispatch is in 'code', not in 'rule'. All methods of ReactiveUI, EventHandler, ReactiveRegion and UIExchange are in the check, so the guard still fails closed.
- This clone has no origin/main ref. HEAD is adbe3a6 on chore/cleanup-7p2rll, as agent C also noted. S3 part 2 and S4 do not depend on it.

