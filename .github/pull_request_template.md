<!-- claude 2026-10-12: created. One short section per rule of .work/design/PLAN-kyoui.md 6.0. Keep ASD-STE100. -->
## P-1 One purpose

<!-- The title is one sentence that says what WORKS after the merge. Repeat it here. -->

## P-2 Size

<!-- Hand-written main lines added or changed (at most about 1500), with the command. Carried files and tests are extra. -->

## P-3 Working base

<!-- The base branch and the proof that it is green (CI run or `sbt check` log). -->

## P-4 Final shape first

<!-- The public types and methods that this PR adds, in their final shape. Later PRs only add members. -->

## P-5 Never port the replaced

<!-- Say that no file of this PR is replaced by a later PR of the stack. -->

## P-6 Fix with the code

<!-- Known bugs fixed here: one commit each, with a regression test and the negative control (the test fails before the fix). "None" is a valid answer. -->

## P-7 Commit map

<!-- The commits in order: (1) carry (new path and package only), (2) format and the removed `// claude <date>` marks (count before and after), (3) strict-flag fixes (changed lines), (4) one commit per behaviour change, each with its test. -->

## P-8 Delta guide

<!-- A table: new file, source `<commit>:<path>`, and the `git diff` command that shows the delta. -->

## P-9 No rewrite

<!-- Review fixes are new commits. No amend, no rebase, no force push after a review. -->

## P-10 Docs true at each merge

<!-- The AGENTS.md and README lines that this PR changes, and why they are true after the merge. -->

## P-11 WIP limit

<!-- The newest PR that the user has reviewed, and the number of PRs above it (at most 2). -->

## P-12 Not in this PR

<!-- What is NOT in this PR, and the PR that brings it. -->

## P-13 Zero warnings

<!-- "0 warnings" with the `sbt check` log (CI mode: `SBT_TPOLECAT_CI`), or the count. -->

## P-14 New sbt projects

<!-- A new project joins `root.aggregate` with `publish / skip := true`, sets no `version` and no `publishTo`; shared versions go in `project/Versions.scala`. "None" is a valid answer. -->
