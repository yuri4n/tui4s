# core-1 verify: lens api (skeptic pass)

Worktree: /Users/yuri4n/Developer/projects/tui4s/.work/worktrees/core (HEAD c30282b)
Status: in progress. Verdicts are added as each finding is checked.

## api-1: confirmed

dsl.scala:22-28 maps each String through `Line.lineOf` (Styling.scala:185-187) = `JLine.raw(s)`.
jatatui-core 0.31.0 sources, `jatatui/core/text/Line.java:46,225-246`: `raw` calls `splitToSpans`, which
splits on '\n' (and strips '\r') into spans of ONE Line. Paragraph.java:243-245 renders each Line from
`line.styledGraphemes`, so "first line\nsecond line" is one row "first linesecond line". `text("a\nb")`
goes through `JSpan.raw` (Styling.scala:178); `Span.styledGraphemes` (Span.java:104-131) drops control
graphemes, so it shows "ab". List items (Painter.scala:193), table cells (:208) and log lines (:309) use the
same `lineOf`. SPEC 2.7 says only "several lines" (one per argument) and does not define '\n'; no test feeds
a '\n'. The scenario is real. (Severity "major" is arguable; the behaviour is a silent glue, not a crash.)

## api-2: confirmed (minor; test-kit fidelity, not a SPEC violation)

TuiTest.scala:100-107 folds Mouse Down/Up through `engine.fold` with no look at `options.mouse`
(Options.scala:36 default false). Engine.scala:91-112 does not filter Mouse or FocusGained/Lost either
(it only fills tags at :103). Event.scala:84 says the runtime reports Mouse only with `TuiOptions.mouse`.
So a click test passes for an app whose real run never gets mouse events. SPEC 2.10/6.2 do not require
the check, so this is a hardening item; the doc-only minimum is cheap.

## api-3: confirmed (minor), with one correction

TuiTest.scala:85-89: '\n', '\t', ESC are 1-char graphemes, so they become `Key.Char(control)`; SPEC 2.4
maps crossterm Enter/Tab/Esc to named keys and Ctrl+letter to `Key.Ctrl(c.toLower)`. Keymap.apply
(Keymap.scala:137-148) only checks duplicates, so `bind(Key.Ctrl('S'))`/`bind('\t')` are accepted and a
`press` of the same value passes in the kit but never fires in a terminal (scenario B is a real false pass).
Correction: scenario A (`typeText("buy milk\n")`) makes the test FAIL (TextField.scala:45-46 ignores the
ISO control char; no Enter binding fires), so it is confusing, not a false pass. "\r\n" is one grapheme ->
Paste -> `singleLine` gives one space (TextField.scala:142-156), "\n" alone is ignored: the inconsistency is real.
Note: SPEC 6.2 literally says "a one-char grapheme is Key.Char"; mapping '\n' to Key.Enter is an addition
to SPEC, not a contradiction of its intent (2.4).

## api-4: confirmed (minor)

`send` (TuiTest.scala:77-79) -> `batch` -> `engine.fold`; Engine.scala:100-101 sets `resize`, :112 sets
`resized`, so a frame draws, but only `resize` (:113-116) calls `backend.resize`. The frame area stays at
the constructor size, so `update` sees 30x5 while the painter lays out 80x24. SPEC 2.10 does not forbid
`send(Resize(...))`, and `send` has no warning.

## api-5: confirmed (minor, doc vs behaviour)

View.scala:240-242 says "…" ends the bar when entries are left out. Painter.scala:326-340: the `find`
loop stops at i = 1 and `getOrElse(1)` keeps one entry; then `cut` needs `ends(1) + 2 <= max`. For Counter
("+ increment" = 11 cells) at width <= 12 no ellipsis shows; PainterTest.scala:208-209 asserts exactly
that ("+ increment" at 12, "+ increm" at 8). The Painter's own comment (:312-314) is closer to the truth.
SPEC does not define the ellipsis rule. Doc-only fix is enough.

## api-1 addendum

One example in the failure scenario is wrong: a single item with a trailing CRLF ("abc\r\n") renders as
"abc", because `splitToSpans` strips the '\r' before '\n' and drops the final empty piece. Only an
EMBEDDED line break glues text. The main scenario (paragraph, text, logPane with a multi-line message)
holds.

## api-6: confirmed (minor; low likelihood)

Painter.scala:407-408 `idPosition(name) = -(name.hashCode & 0xffffffff) - 2`; Painter.scala:155-156
walks the child at that position; `slot` (:104-113) keys by (position, tag) only, so two Items under
`.id("Aa")` and `.id("BB")` (both hashCode 2112) get one ListState, and a duplicate name in one frame does
too, with no report. SPEC 5.7 says only "a hash of the name" (no width), so a stronger hash or a
name->Long map does not contradict SPEC. Realistic distinct names rarely collide; duplicate names are the
more likely case. A dev check like Keymap's duplicate-key check fits.

## api-7: confirmed (minor)

No `CanEqual` for `View` (View.scala:22), `Focus` (Focus.scala:20) or the opaque `Color`/`Style`/`Line`/
`Line.Span` (Styling.scala:19/86/161/168); `grep CanEqual` in main finds none for them, while Event, Key,
Screen, TextField, Theme, Border, Constraint, ... derive it. Under `-language:strictEquality` there is no
`canEqualAny` fallback, and an opaque type is abstract outside its companion, so
`styleAt(x, y) == Style.bold.fg(Color.Yellow)` (the SPEC 2.10 idiom) and `t.view == v` do not compile.
SPEC 2.5/2.6/2.7 do not list CanEqual for these types either, but adding it does not contradict SPEC.

## api-8: refuted

The fact is right: target/ has `tui4s/App$package$Next$.class`, `App$package$Next$Then.class` and
`Styling$package$Color$/Style$/Line$...` (fresh class files, 11:23-11:29 today), so a file rename changes
JVM names. But the failure scenario is a hypothetical future move that no plan contains, and the existing
guard catches it: CI and `sbt check` run `versionPolicyCheck` on each PR (build.sbt:89, ci.yml:29),
which reports the missing classes before merge; the developer reverts the move. Nothing breaks silently
and no code in this PR is wrong. At most a header comment (a nit), not a defect.

## api-9: confirmed (minor, test coverage)

(a) PainterTest has no case that swaps the node kind at one position (only sweep and `.id` cases,
:93-127, and stateful kept/swept, :261-308). SPEC 5.7 requires "a slot of another node kind at that
position is replaced by a fresh state"; Painter.scala:104-113 (tag `eq`) and :148-151 (stateful keyed by
`paint.getClass`) implement it, but nothing pins it. (b) NameClashTest.scala:7-9 claims "every name of the
model and the view"; it never names `Source` (a public model type, Source.scala:15) or the testkit names.
No clash exists today (Kyo RC7 jars have no top-level `kyo.Source`/`kyo.test.Screen`; only NameClashTest
imports `kyo.*`), so (b) is a guard for future Kyo upgrades plus an overstated comment.

Status: done. confirmed: api-1..api-7 except api-8; api-9 confirmed. refuted: api-8.
