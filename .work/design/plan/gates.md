<!-- claude 2026-10-09: created (agent M, W0). The orchestrator writes this text to .work/design/plan/gates.md -->
# W0 gates: the results for gate 0

Agent M, 2026-10-09. This file uses ASD-STE100. GO, SOFT and NO-GO are the gate words of `PLAN-kyoui.md` section 5.2.
All log paths start at `.work/spikes/`.

## How M measured

- M ran the timing gates G1, G2 and G4 alone, 22:49-23:39 UTC, on the cloud machine (4 CPUs, JDK 25). The load
  watchers (`others.log` in each run folder) show 0 JVMs of other agents.
- M used the re-run commands of the spike agents and did not change their code.
- Commands: `bash .work/spikes/m-rerun/run-m.sh` (S1, S2, S4, the S6 JUnit loop; about 41 min), then
  `bash .work/spikes/m-rerun/run-m2.sh` (S6 `rerun.sh`, extra S1 checks, S4 again; about 8 min). The uptime and nproc
  of each step are in `m-rerun/logs/driver.log`.
- Some steps start with a load average of 3 to 8. This is the lagging average of the step before (3 JVMs on 2 CPUs).
  The steps did not overlap.
- G5 is void, because the plan dropped S5 (plan 0.2). G7 moved to SZ (plan 0.2).

## Results

| Gate | Verdict | Ask the user | Main evidence |
| --- | --- | --- | --- |
| G1 settle (S1) | NO-GO with the Kyo default scheduler values; the tui4s values meet the SOFT words | Yes: D-17 (d) | `s1-settle/logs/m1/glitch-summary.txt`, `run5-quiet/glitch-summary.txt`, `m1-tests/`, `tests5-quiet/` |
| G2 R1 (S2 part 1) | NO-GO for option (a); the plan fallback, option (b), passes all R1 checks | No | `s2-r1r2/logs/m1/`, `run2-quiet/` |
| G2 R2 (S2 parts 2, 3) | GO, with a changed D-16 (a) ("armkey") | No (D-16 is a gate-0 decision) | `s2-r1r2/logs/m1/`, `run2-quiet/` |
| G3 drift (S3) | NO-GO | Yes: D-19 (b) | `s3-drift/diffs/rules.*.txt`, `guard/out/check-*.txt`, `guard/out/coverage.txt` |
| G4 weight (S4) | GO | No | `s4-weight/logs/run-232628/s4-summary.log`, `logs/s4-summary.log` |
| G6 flags, core (S6) | GO | No | `s6-flags/logs/rerun-1009-2331-summary.log`, `junit-ab-1009-2327-summary.log` |
| New risk K29 (observe leak) | No gate; see the proposal | Decide at gate 0 (C2) | `s2-r1r2/logs/observe-bound-*.log`, `s1-settle/logs/m1-extra/*.gc` |

## G1: settle (S1)

The `status()` probe, `Settle.Quiescent(100 ms, confirm = 2)`, 20 000 events per cell and pull mode. The table adds
two quiet runs (`run5-quiet` of agent A and `m1` of agent M). The values in brackets are the two runs.

| Load | Kyo default values | tui4s values |
| --- | --- | --- |
| none | 0 in 80 000 | 0 in 80 000 |
| 2x2 | 5 in 80 000 = 0.62 per 10 000 (1.25; 0) | 2 in 80 000 = 0.25 (0.50; 0) |
| ci3 (3 JVMs on 2 CPUs) | 36 in 240 000 = 1.50 per 10 000 (0.42; 2.58) | 16 in 240 000 = 0.67 (0.58; 0.75) |

- The other probes are not better. With ci3, the reflection probe gives 5.58 per 10 000 with the Kyo values and 0.71
  with the tui4s values. `mounted` gives 5.29 and 0.21.
- Cost: with no load, the settle p50 with `status()` is 37 to 68 us more than with the reflection probe (m1 alone:
  +55 to +68). The GO limit is +50 us. One call takes 11-12 us, because `Worker.status()` calls
  `Thread.getStackTrace()`.
- The 9 tests under the CI model: `status()` 10 of 10 with the Kyo values and 10 of 10 with the tui4s values. The
  reflection probe: 8 of 10.
- The pull check removes all bound and `Foreach` splits in both runs. `Reactive` splits stay.
- Settle timeouts (m1): 0 with no load; 0-6 per 20 000 under 2x2; 8-23 per 60 000 under ci3.
- Why GO is not possible on Kyo RC7: under load, 92 of the 147 listed split frames in m1 show only the
  `combineLatest` region with an intermediate value, with 6 updates and no timeout (with `status()`: 18 of 25). This
  is a lost wakeup in `Signal.combineLatest`. Nothing is pending, so no probe can see it. Other causes are the gaps
  in `Worker.load()` and the settle timeouts.
- New (M): under 2x2 with a GC log, G1 pauses reach 107-140 ms, which is more than the settle max of 100 ms. The live
  heap grows to about 480 MB in 40 000 events because of the observe leak (K29) (`s1-settle/logs/m1-extra/*.gc`).
- Gate words: GO fails (splits under load, and the cost). With the Kyo values, ci3 is above 1 per 10 000, thus
  NO-GO. With the tui4s values, every run meets SOFT: 0 splits with no load, at most 0.75 per 10 000 under load, and
  tests 20 of 20.

What changes: ASK THE USER (D-17 d). See "Ask the user".

## G2: R1 and R2 (S2)

R1. The numbers are m1, with run2-quiet in brackets:

- Leak per interrupted run (300 runs): prototype 1763.0 KB (1762.9); option (a) 0.6 KB (0.1); option (b) -0.7 KB
  (0.3).
- Live fibers after an interrupt: prototype 6; (a) 0; (b) 0.
- End order: 0 flushes after the canvas closes, in all 18 cases. With a 1.5 s CPU-bound watcher, option (a) waits
  1395-1401 ms. Option (b) waits 0 ms on an interrupt and 101 ms on a quit, which is the same as the prototype.
- Growth in one run (20 000 keys): (a) 15 100 B/key; (b) 10 655 B/key; prototype 10 330 B/key.
- Result: option (a) breaks the end order, thus NO-GO on R1. Option (b) passes all R1 checks.

R2:

- Seam reproducer (300 ms; 10 rounds per mode and run). With no fix, the key frame is right in 0 of 10 in both runs
  (right only at 1302-1306 ms). D-16 (a) as written ("step"): 7, 3 and 4 of 10 in three runs. armkey: 10 of 10 in
  both runs.
- External write in the window: armkey is right at 200-202 ms. With no fix: 1202-1204 ms.
- Frames in 500 ms after one key (m1): armed 213, armmsg 373, armkey 1.
- First-key splits (plain Kyo, ZIO glue, Cats glue), two runs added together. With no fix: 6 in 8000 starts with no
  load and 39 in 4800 under 2x2. armkey: 0 and 0.
- Steady keys: armkey and step each had 1 split in 1000 (ZIO, no load, m1), with a delay of less than 200 ms. This is
  a K1 settle split, not R2.
- The 9 tests under the CI model (this copy uses the reflection probe): armkey 7 of 10, no fix 8 of 10. The failures
  are `glitch-free` and `dashboard` (K1).

What changes:

- C2 and C4: RC-8 with option (b), not option (a). Keep the last world in an `AtomicReference`, and run
  `unmountAll` in `Sync.ensure`. T1: the `Session` release first restores the terminal, then runs `unmountAll`. It
  does not wait for watchers.
- C2: RC-9 with armkey (a changed D-16 a). After each settle, verify the regions mounted in this step or the step
  before. In an Input step, also verify the regions whose watcher has not returned from its first callback. Verify
  one time when a watcher posts `Msg.Armed`. Keep the seam test (red in 10 of 10 rounds without the fix, in 3 runs).
  Add a cascade test: one key in the seam window gives at most 2 frames.
- The split and leak probes become ignored stress suites, as the plan says.

## G3: kyo-ui drift (S3)

- This is not a timing gate, so M did not run it again. Agents C and E each made their results again, byte-identical
  (`s3-drift/logs/verify-rerun-20261009T213254Z.log`, `logs/e2-guard-verify-20261009T213534Z.log`).
- RC5 to RC6: a dispatch rule changed (`stopPropagation`, `keepBubbling`). The change is opt-in. RC6 to RC7: a region
  rule changed (`walkStatic`: a bound element at the root of a `Reactive` or `Foreach` content becomes its own
  region). This is a new default. M read the `walkStatic` diff (`diffs/rules.RC6-RC7.txt`, lines 73-103): the claim
  is correct.
- Names on the guard surface: 11 changed (RC5 to RC6) and 16 changed (RC6 to RC7). All are additions. No name that
  the runner uses is removed or renamed.
- The guard (shape hash, with the literal fix of agent E) finds both rule changes, 27 of 27 names, 17 of 17 impact
  rows and 14 of 14 mutants, with 0 false alarms.
- Verdict: NO-GO by the gate words. The runner prototype already has the RC7 region rule. The plan already finishes
  the stack on RC7 (K13).

What changes: ASK THE USER (D-19 b). For C1-C3, in all cases: the guard uses the shape hash (a raw TASTy hash gives
8 false alarms) and the fixed `Constant` case. C1 adds a negative-control test that changes only a literal (P-6).
The checked-in list is `guard/out/kyo-ui-surface.txt`, made with the fixed hash. 5 opt-in RC6 features need runner
work for parity: the Form submit with `stopPropagation`, `focusAuto`, `focusRestore`, `inputFilter` and `inputMask`.
Put them in L2, or list them as "not supported".

## G4: kyo-ui weight (S4)

- Median first frame of 10 runs, for the assembly / the full class path / the class path without kyo-http. M run 1:
  869 / 883 / 918 ms. M run 2: 866 / 890 / 919 ms. Agent E, set 6: 868 / 918 / 905 ms. The limit is 1.0 s.
- Classes of kyo-http, kyo-net or kyo-ffi that load: 0 of 3549-3551. No kyo-http or kyo-net thread starts. The only
  native library that is not from the JDK is crossterm. Without the 6 jars, the threads, the native libraries and the
  sockets are the same.
- Without kyo-http, `UI.Href.Absolute` fails with `NoClassDefFoundError: kyo/HttpUrl$`. Thus D-18 (b) is not safe.
- Verdict: GO.

What changes: D-18 (a). The docs give the size: kyo-ui adds 7 jars and 15.0 MB (this corrects RC 3.1, which says 8
jars and 15.2 MB). Inputs for T1 and the docs:

- A JDK 25 AOT cache gives a 437 ms first frame (`s4-weight/logs/e2-startcost/summary.log`). Do not use AppCDS,
  because it writes an error line into the terminal.
- crossterm leaves one `/tmp/jni-<n>/` folder after each start. Now there are 378 of these folders.

## G6: strict flags, core part (S6)

- Alone, M got these results. The carry `fe06611` has 8 warnings (2 main, 6 test) and 0 errors. `sbt check` on
  `4cdeebd` exits 0: format check, `-Werror` compile with 0 warnings, 9 of 9 tests, `versionPolicyCheck`.
  `sbt core/test` is green in 3 of 3 runs. With a direct JVM, the port is green in 20 of 20 runs, and the prototype
  copy in 18 of 20.
- Strict-flag fixes: 22 changed lines. There is 0 `.nn`.
- Agent B gave SOFT in pass 2 because of failures under the load of other agents (17%; the prototype failed the same
  way). Alone, the port is green in 24 of 24 runs. Verdict: GO.

What changes:

- RC-18 as written.
- An AGENTS.md rule for C1 (from agent B):
  - `-Wnonunit-statement` does not see a Java call in statement position.
  - `-Wvalue-discard` sees a Java result only in a Unit position.
  - `-Yexplicit-nulls` gives flexible types to Java results.
  - Check for null where the Java source or the JNI contract allows null (the crossterm records in `EventMapping`,
    T1).
- Size input (K24): scalafmt grows the runner main code from 2313 to 3569 lines (+54%). The P-8 delta guide uses
  `git diff -w`, or starts at the format commit.
- The fs2 part of S6 is in SZ.

## New risk K29: `Signal.observe` keeps memory (Kyo RC7)

- One `observe` watcher keeps about 1.7 KB per value change until its fiber ends. With `-Xmx48m`, it stops with
  `OutOfMemoryError` after 20 000 to 30 000 writes (`s2-r1r2/logs/observe-bound-observe-100000-48m.log`).
- The cause is one `Async.race` per value in a fiber that lives long. Each race keeps 540-720 B
  (`observe-bound-race-100000-48m.log`).
- A runner watcher lives for the whole run. With 5 regions that change 10 times per second, the run keeps about
  300 MB per hour.
- K29 also feeds K1, through the GC pauses in G1 above.
- Proposal: add K29 to section 8 (L: H, I: H). C2 chooses one of these:
  1. An upstream fix.
  2. A runner watcher loop without `Async.race` (`currentWith`/`nextWith`), plus a repair tick in the runner.
     Without the tick, a write in the window is lost. Check how this works with armkey.
  3. Accept the leak, and write the limit in the docs.
- M's note: use (2) or (3) for the first release, and put (1) in the upstream draft.

## Ask the user

1. D-17 (G1 is NO-GO). Choose one:
   - (c, with conditions) Accept the SOFT level:
     - C2 uses the public `Scheduler.status()` probe with `confirm = 2` and no reflection.
     - `TuiApp` and the forked test JVM always set the tui4s scheduler values. The docs say that `Tui.run` without
       these values gives more split frames.
     - C2 adds the pull check for bound and `Foreach` regions.
     - The kit stays strict and gets `awaitScreen(predicate, timeout)`.
     - The cost is about +55 to +70 us per frame.
   - (d) A frame contract that is "eventually consistent within `max`", with auto-wait kits. This reverses the
     strict kit.
   - Pin Kyo, with the upstream change as a blocker for 1.0.
   - Facts: with the tui4s values, there are 0 splits with no load and 0.25-0.67 per 10 000 under load. The tests
     pass in 20 of 20 runs under the CI model. Most remaining splits are the Kyo `combineLatest` lost wakeup.
2. D-19 (G3 is NO-GO). Do you want to pin Kyo and kyo-ui to 1.0.0-RC7 for the first release, and move the runner SPI
   ahead of the change notice in the upstream draft? Facts: all public changes are additions. The runner already has
   the RC7 rules. The guard finds each rule change.

## The decisions of section 3.2 and the numbers

| Decision | What W0 shows | Effect on the REC |
| --- | --- | --- |
| D-14 duration type | No W0 data | REC (a) stays |
| D-15 quit | No W0 data | REC (a) stays |
| D-16 R2 fix | (a) as written fails the seam test (3-7 of 10). armkey passes (10 of 10, and 0 first-key splits) | REC: (a) in the armkey form. (b) is not necessary |
| D-17 settle | G1 is NO-GO with the Kyo values. The tui4s values meet the SOFT words | Ask the user (above) |
| D-18 jars | 0 classes load. `Href.Absolute` needs kyo-http | REC (a) stays. (b) is not safe |
| D-19 drift | G3 is NO-GO | Ask the user (above) |
| D-20 facade components | Not measured (S7 moved to SZ) | Decide at gate Z |

From section 3.1, the numbers support D-9 (one test JVM at a time): 3 JVMs on 2 CPUs raise the split rate from 0 to
0.67-1.50 per 10 000.

## Corrections to the spike reports (skeptic pass)

- S1: agent A gave this NO-GO reason: "2x2, Kyo values, 1.25 per 10 000". In the M run, this cell has 0. The NO-GO
  reason is now ci3 with the Kyo values (m1 2.58; both runs 1.50). The verdict does not change.
- S1: the pull-on phase is always the second phase in the JVM. M ran each pull mode alone (`m1-extra/`): 0-3 splits
  per 20 000, and no order effect. But the counts are too small to compare the `Reactive` splits between the two
  modes. Only one result is firm: the pull check removes the bound and `Foreach` splits.
- S1: `status()` was the best probe under load. Its slow call (11-12 us) can be a part of the reason. In C2, test a
  fast probe with a short pause between the confirm reads.
- S2: agent A wrote "armkey and step: 0 in all 12 split cases". The M run has 1 steady-key split for each mode (a K1
  split). The first-key result (0) is correct.
- S6: the REPORT.md on disk is pass 1 (GO). Pass 2 said SOFT. The M run alone gives GO. Two claims in pass 1 are
  wrong (agent B): `-Wvalue-discard` does flag a discarded Java result in a Unit position, and the tests also failed
  without settle timeouts.
- S4: the REPORT.md on disk has only sets 1-3, which are provisional. Use sets 4-6 of agent E and the two M runs.
- Each number in this file names its log. S4 set 1 of agent E has no log, and this file does not use it.

## Run it again (alone on the machine)

- All gates: `bash .work/spikes/m-rerun/run-m.sh`, then `bash .work/spikes/m-rerun/run-m2.sh`. Read the `others.log`
  files first.
- One gate:
  - `RUN=<name> bash .work/spikes/s1-settle/scripts/gate.sh`
  - `RUN=<name> bash .work/spikes/s2-r1r2/scripts/run-all.sh`
  - `bash .work/spikes/s4-weight/run-s4.sh 10`
  - `bash .work/spikes/s6-flags/rerun.sh 3`
- While `rerun.sh` or `junit-loop.sh` runs, no other command line may contain the text `sbt-launch` or
  `scala-cli.bin`. Their busy check uses `pgrep -f` and finds that text.
