# core-1 verify (lens spec)

Skeptic pass over the spec findings. Status per finding is added as it is verified.

Worktree HEAD c30282b. jatatui 0.31.0 sources read from the Coursier cache. Compiled classes in
modules/core/target (newer than the sources) were read with javap; no build was run.

## spec-3 (input hides wide grapheme at right edge): CONFIRMED
TextField("abcd日").edit(Key.Left) -> cursor 4, widths [1,1,1,1,2], before = 4, loop `4 > 5-1` is false,
start = 0. Buffer.setStringn breaks when `w > remainingWidth` (Buffer.java:188-215), so 日 is not drawn and
the cursor sits at (4,0) on a blank cell. Same for "日日日" at width 5, cursor 2.

## spec-4 (help bar drops the ellipsis at width 11-12): CONFIRMED (doc or code)
ends(1) = 11, 13 > 12 -> shown = 1 (getOrElse), cut = false -> "+ increment" with no marker. The View.Help
Scaladoc ("…" ends the bar) and the Painter doc ("first entry cut" only when it does not fit) both disagree.
PainterTest asserts it on purpose, so fixing the Scaladoc is an acceptable resolution.

## spec-5 (stateful never equal): REFUTED
javap: dsl$.stateful and the package$ export forwarder pass the caller's by-name thunk through
(aload_1 -> View$Stateful$.apply); Scala 3 eta-reduces `() => fresh`. At a call site like `stateful(0)(...)`
the thunk is a non-capturing invokedynamic (NameClashTest bytecode: `InvokeDynamic apply:()Lscala/Function0;`),
which is one constant instance per call site. So `stateful(0)(paintVal)` views ARE equal and level 2 skips.
Only a capturing `fresh` makes a new thunk, and SPEC 2.6/5.6 accept reference equality for functions
("correct, only slower").

## spec-6 (table(Nil, ...) reserves a blank header row): CONFIRMED
Painter always calls withHeader(Row.of(header)). Row.of(list) has height 1 (Row.java:32-35);
Table.layoutAreas uses header.map(Row::height) = 1 (Table.java:383-393). No way to get a headerless table.

## spec-7 (TuiTest.resize after the end garbles the screen): CONFIRMED
resize calls backend.resize before batch checks `ended`. TestBackend.resize -> Buffer.resize is a flat
System.arraycopy truncate/grow (Buffer.java:252-267); `screen` reads the live backend buffer. After
press('q'), resize(30,5) re-flows the 40-wide frame into 30 columns. Same when update throws on Resize.

## spec-1 (widget-state slot keys collide): CONFIRMED (low likelihood, SPEC-level)
"Aa".hashCode == "BB".hashCode == 2112 -> idPosition = -2114 for both; slot() checks only `tag eq ListTag`,
so both lists get one ListState. jatatui ListState.select(Some) keeps the offset; List.getItemsBounds pulls
the offset up to the selection. List 1 (selected 0) resets the offset to 0 each frame, so list 2 always
scrolls from 0: after 5x Down + 1x Up it shows item2..item4 (highlight at the bottom), not item3..item5.
Tree case traced too: row at 1, columns at 32/33, child 31 of column 32 = 992+32 = 1024 = 33*31+1 = child 0
of column 33 (needs an area of 32+ rows so list A gets a non-zero height). The code follows SPEC 5.7
("a hash of the name", `parent*31+index+1`); the defect is that formula versus 2.13 rule 15. A fix should
keep the no-allocation Long path (for example a String-keyed map for `.id` only).

## spec-2 (draw failures reported as AppFailed("paint")): REFUTED for this PR
True that drawIfNeeded maps every NonFatal from `draw` to AppFailed("paint"). But this PR is C1+C2; the only
caller is TuiTest with a TestBackend, which does no I/O. CrosstermBackend and TerminalIO handling are C5
(SPEC 8.2). The scenario cannot happen here. Note for C4/C5: split user-paint errors from backend I/O
(SPEC 5.6/5.12) when the real draw callback is added.

## Summary
Confirmed: spec-1, spec-3, spec-4, spec-6, spec-7. Refuted: spec-2, spec-5.
