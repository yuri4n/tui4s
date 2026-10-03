# jatatui 0.31.0 — research report (tui4s backend)

Status: COMPLETE (2026-09-30). Author: research subagent (topic JATATUI). Builds on `.work/reports/tui-scala.md` and `kyo.md` (not repeated here).
Legend: VERIFIED = compiled/ran/saw in source; UNVERIFIED = inferred, not proven.
Scratch: .work/scratch/jatatui/

## 0. Summary (read this first)
1. **Runtime = 4 jars, zero third-party deps, MIT**: `com.olvind.jatatui:crossterm` (Java 17 bytecode, JNI + 5 natives, Rust crossterm 0.29), `jatatui-core` (no deps), `jatatui-widgets`, `jatatui-crossterm` (Java 21 bytecode). No module-info. jatatui-react/-components are optional PoC layers. VERIFIED.
2. **JEP 472 on JDK 25**: loading `CrosstermJni` prints 4 `WARNING:` lines to stderr (from `System.loadLibrary` in `tui.crossterm.NativeLoader`); `--enable-native-access=ALL-UNNAMED` (or `Enable-Native-Access: ALL-UNNAMED` in an executable jar manifest, or `JDK_JAVA_OPTIONS`) silences it; `--illegal-native-access=deny` (the future default) makes class init fail with `IllegalCallerException`. VERIFIED by probe.
3. **The tui-scala hazards are mostly fixed**: Layout cache is a bounded (500) LRU behind one global lock (no leak, no lost updates; 8-thread smoke test clean); `Borders.remove` fixed; `TestBackend` is published and copies cells; widgets/text/styles are immutable with structural equality (`List.copyOf`). Still present: `Buffer`/`Cell` mutable, `Buffer.diff` returns live cell refs, `Layout.split` returns the shared cached `Rect[]`, widget states (`ListState`, `TableState`, `ScrollbarState`) are mutable and written by `render` (their `withX` methods mutate too). VERIFIED.
4. **Event model** = tui-scala 0.0.10's `tui.crossterm` plus `MouseEventKind.ScrollLeft/Right`, `Command.SetCursorStyle`, fixed `KeyEventState` bits and fixed native `KeyCode.Modifier`; `KeyCode.Char(char)` is still truncated from a Rust `char` with `as u16` (non-BMP keys break). Same package `tui.crossterm` as tui-scala -> never both on one classpath. VERIFIED.
5. **`Jatatui.init/runIo` is not suitable** for tui4s: it replaces the JVM-global default uncaught-exception handler and adds a shutdown hook. tui4s should do its own Scope-based raw-mode/alt-screen acquire/release. VERIFIED (source).
6. **Perf (2014 i7, JDK 25, loaded machine)**: typical 120x40 frame (Block + Paragraph 1100 chars + List 100 items) ~2.1 ms and ~1.7 MiB allocated; an unchanged frame costs the same and yields 0 changed cells; `Terminal.draw` fixed overhead ~0.6 ms (diff + reset). Allocation comes from `Cell.setStyle` (full-area style passes) and text segmentation; cell access is allocation-free after JIT. -> render only when dirty. VERIFIED.
7. **Headless probe passes** (Block + Paragraph + List with selection + Gauge at 40x12 through `Terminal(TestBackend)`), plus a second probe covering Table, Tabs, LineGauge, Sparkline, Scrollbar, TextInput, Clear, BarChart, Chart, Canvas, Calendar and the Inline viewport with `insertBefore`. VERIFIED.
8. **Scala 3.9 interop is smooth**: `-Yexplicit-nulls` needs zero `.nn` (flexible types; jatatui never returns null); Scala 3.9 **does** exhaustivity-check Java sealed interfaces and enums; Java records work with universal apply and paren-less accessors but **cannot be used as extractors**; traps: `Optional[Int]` vs `Optional[Integer]`, `Style.fg()` is a record accessor (use `withFg`), class-name clashes (`List`, `Line`, `Block`, `Map`, `Color`). VERIFIED by compiling.
9. **jatatui-react** (PoC): immediate-mode element tree, fiber identity with type-change unmount + sweep, paint-order z-layered hit-testing, focused-chain key bubbling, portals, event-driven dirty redraw, `Renderer` vs `ReactApp` vs `TestHarness` split. Weak spots for us: untyped positional hooks, effects run synchronously during render with no user cleanup, `Object` key matchers, global daemon executors, opinionated runner. Inspiration only.

## 1. Modules, artifacts, deps, JVM target, native loading, JEP 472

### 1.1 Artifacts and transitive deps (VERIFIED: POMs from repo1.maven.org, 2026-09-30; bleep.yaml at tag v0.31.0)
| artifact (`com.olvind.jatatui:*:0.31.0`) | jar | compile deps (POM) | bytecode | packages |
|---|---|---|---|---|
| `crossterm` | 1.6 MB | none | major 61 (`--release 17`) | `tui.crossterm` (23 classes + 5 natives) |
| `jatatui-core` | 193 KB | **none** | major 65 (`--release 21`) | `jatatui.core.{backend,buffer,internal,layout,layout.solver,style,style.palette,symbols,terminal,text,widgets}` |
| `jatatui-widgets` | 219 KB | jatatui-core | 65 | `jatatui.widgets.{.,barchart,block,calendar,canvas,chart,gauge,input,list,paragraph,reflow,scrollbar,sparkline,table,tabs}` |
| `jatatui-crossterm` | 15 KB | crossterm, jatatui-core | 65 | `jatatui.crossterm` |
| `jatatui-react` | 68 KB | core, widgets, jatatui-crossterm | 65 | `jatatui.react` |
| `jatatui-components` | 105 KB | core, react, widgets | 65 | `jatatui.components.*` |

- **Zero third-party dependencies** in all six POMs (no JSpecify/JetBrains annotations, no ICU4J, no Guava). The whole runtime stack for tui4s-core is 4 jars: `crossterm`, `jatatui-core`, `jatatui-widgets`, `jatatui-crossterm`. VERIFIED.
- License **MIT** (all POMs). Compatible with tui4s Apache-2.0 (keep MIT notice if we vendor any code). VERIFIED.
- No `module-info.class`, no `Automatic-Module-Name` in any manifest (`Created-By: Bleep/1.0.0-M9`). Classpath only; on the module path they become automatic modules named from the jar file (`jatatui.core`, `crossterm`...). VERIFIED.
- POM `<description>` of every module is copy-pasted "JNI binding to the Rust crossterm crate..." (cosmetic). VERIFIED.
- Built with bleep on `graalvm-community:25.0.1` but `--release 21` (`--release 17` for crossterm). So the **minimum runtime is JDK 21**; JDK 25 is fine. VERIFIED (bleep.yaml + class major versions counted with python over every .class).
- Release cadence: 0.30.0 (2026-05-17, first release, ratatui 0.30 port), 0.31.0 (2026-08-22: native-image JNI config for every tui.crossterm class, `bleep fmt`, react `useFrame` + full mouse hover/drag). The version "tracks the upstream ratatui release" per README (0.31 is still the ratatui-0.30 port). Repo `oyvindberg/jatatui`, 288 stars, not archived, last push 2026-08-22. VERIFIED (gh api).
- `crossterm` jar also ships `META-INF/native-image/com.olvind.jatatui/crossterm/jni-config.json` (GraalVM native-image). Not needed on HotSpot. VERIFIED.
- `jatatui-widgets` has resources under `jatatui/widgets/canvas/` (world-map data for Canvas `Map` shape). VERIFIED (repo tree).

### 1.2 Native library (VERIFIED: unzip -l, Cargo.toml at v0.31.0)
- Same 5 natives as tui-scala 0.0.10: `libnative-{arm64,x86_64}-darwin-crossterm.dylib`, `libnative-{x86_64,arm64}-linux-crossterm.so`, `native-x86_64-windows-crossterm.dll`. No Windows arm64, no musl.
- Rust side: crate `crossterm = "0.29"`, `jni = "0.20.0"` (tui-scala 0.0.10 used crossterm 0.25).
- `tui.crossterm.NativeLoader` is **unchanged in behaviour** from tui-scala 0.0.10 (diffed): static init of `CrosstermJni` -> `System.loadLibrary("crossterm")` (java.library.path) -> on `UnsatisfiedLinkError` extract `/` + `System.mapLibraryName("native-<platform>-crossterm")` from the classpath into `Files.createTempDirectory("jni-")` and `System.load` it. Temp dir is never deleted. Platform override env var is still **`TUI_SCALA_PLATFORM`**. Error message still links the tui-scala issue tracker.
- Errors: every native failure is thrown as `java.lang.RuntimeException("IO error: ...")` / `"Error from JNI: ..."` (Rust `jvm_unwrap`). `crossterm/cargo/src/jvm_unwrapper.rs`. VERIFIED.

### 1.3 JEP 472 (restricted JNI) on JDK 25 — VERIFIED by probe
Probe: `.work/scratch/jatatui/jep472/LoadProbe.java` (plain Java, `new CrosstermJni()` then `terminalSize()`, `poll(Duration(0,0))`, `enableRawMode()`), Temurin 25.0.4.1 x86_64 macOS, stdin `/dev/null`, no TTY.

| run | result |
|---|---|
| A. `java -cp crossterm-0.31.0.jar LoadProbe.java` (no flag) | loads, but prints 4 lines to **stderr**: `WARNING: A restricted method in java.lang.System has been called` / `WARNING: java.lang.System::loadLibrary has been called by tui.crossterm.NativeLoader in an unnamed module (file:.../crossterm-0.31.0.jar)` / `WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module` / `WARNING: Restricted methods will be blocked in a future release unless native access is enabled` |
| B. `java --enable-native-access=ALL-UNNAMED ...` | loads, **no warning** |
| C. `java --illegal-native-access=deny ...` | `ExceptionInInitializerError` caused by `IllegalCallerException: Illegal native access from an unnamed module` at `System.loadLibrary` <- `NativeLoader.load` <- `CrosstermJni.<clinit>`. This is the future default. |
| D. `java -jar probe.jar` with manifest `Enable-Native-Access: ALL-UNNAMED` (main jar) | loads, no warning |
| E. env `JDK_JAVA_OPTIONS=--enable-native-access=ALL-UNNAMED` | loads, no warning (prints `NOTE: Picked up JDK_JAVA_OPTIONS` on stderr) |
| F. env `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED` | loads, no warning (prints `Picked up JAVA_TOOL_OPTIONS` on stderr) |

- The warning is triggered by the first restricted call, `System.loadLibrary` (the java.library.path attempt), before the fallback `System.load` of the extracted file. It is printed once per module.
- Loading takes ~65-135 ms (extract 0.8 MB dylib to `$TMPDIR/jni-*` + dlopen). Each JVM leaves one `jni-*` temp dir behind.
- Without a TTY: `terminalSize()` -> `RuntimeException: IO error: Os { code: 35, kind: WouldBlock ... }`; `poll` -> `RuntimeException: IO error: Custom { kind: Other, error: "Failed to initialize input reader" }`; `enableRawMode()` -> `RuntimeException: IO error: Os { code: 6, kind: Uncategorized, message: "Device not configured" }`. Same as tui-scala 0.0.10.
- **Hazard for tui4s**: the warning goes to stderr = the terminal. If `CrosstermJni` is first touched after the alternate screen is entered, the 4 lines corrupt the screen. tui4s must (a) load `CrosstermJni` eagerly before any terminal setup, and (b) document `--enable-native-access=ALL-UNNAMED` for users: sbt `run / javaOptions += "--enable-native-access=ALL-UNNAMED"` (needs `fork := true`), scala-cli `//> using javaOpt --enable-native-access=ALL-UNNAMED`, fat jars `Enable-Native-Access: ALL-UNNAMED` in the manifest. On the module path the flag must name the module (`--enable-native-access=crossterm`, automatic module name, UNVERIFIED).
- A library cannot grant itself native access; only the launcher can. tui4s could detect it at startup with `getClass.getModule.isNativeAccessEnabled` (JDK 22+ API, UNVERIFIED call here) and print a clear message before the TUI starts.

## 2. jatatui-core API

Source: `jatatui-core-0.31.0-sources.jar` unpacked in `.work/scratch/jatatui/src/jatatui-core/` (all signatures below VERIFIED by reading source unless marked). Scala call forms marked (P) were compiled in the probe (section 6).

### 2.1 Buffer (`jatatui.core.buffer.Buffer`, final class, MUTABLE)
```java
public final class Buffer {
  public Rect area;            // public mutable field
  public Cell[] content;       // public mutable field, row-major, length = area.area()
  public static Buffer empty(Rect area);
  public static Buffer filled(Rect area, Cell cell);           // copies cell per slot
  public static Buffer withLines(String... lines);             // test helper, width = widest line
  public static Buffer withLineObjects(Line... lines);  public static Buffer withLineObjects(Iterable<Line> lines);
  public Cell[] content();  public Rect area();
  public int indexOf(int x, int y);                            // throws IndexOutOfBoundsException outside area
  public Position posOf(int index);
  public Optional<Cell> cell(Position p);  public Optional<Cell> cell(int x, int y);   // safe get
  public Cell cellAt(Position p);          public Cell cellAt(int x, int y);           // "get": live ref, throws IOOBE
  public void setString(int x, int y, String s, Style style);
  public Position setStringn(int x, int y, String s, int maxWidth, Style style);      // returns end position
  public Position setLine(int x, int y, Line line, int maxWidth);
  public Position setSpan(int x, int y, Span span, int maxWidth);
  public void setStyle(Rect target, Style style);              // clipped to area
  public void resize(Rect area);                               // truncates/grows content, does NOT clear
  public void reset();                                         // resets every cell in place
  public void merge(Buffer other);                             // grows to union, copies other's cells
  public List<BufferUpdate> diff(Buffer other);                // this = previous, other = next
  // equals/hashCode structural (area + every cell); toString = ratatui-style debug dump
}
public record BufferUpdate(int x, int y, Cell cell) {}         // cell = LIVE reference into `other`
```
- There is no `get(x,y)`/`set(x,y,cell)`: ratatui's `buf[(x,y)]` is `cellAt(x,y)` (mutate the returned cell) and the safe variant is `cell(x,y): Optional<Cell>`.
- `setStringn` segments graphemes with `java.text.BreakIterator.getCharacterInstance(Locale.getDefault())` (new instance per call), skips control chars and zero-width graphemes, uses `Wcwidth.width` for display width, and resets the trailing cells of wide graphemes. It does not bounds-check `y` or a negative `x`: `cellAt` throws `IndexOutOfBoundsException` (tui-scala threw `RuntimeException("assertion failed...")`).
- **`diff` returns live `Cell` refs** (same hazard as tui-scala). The published `TestBackend.draw` copies the fields, so it does NOT have the tui-scala aliasing bug. A custom recording backend must copy (`cell.copy()`).
- `cellAt` -> `indexOf` -> `indexOfOpt(new Position(x,y))` returns `Optional<Integer>` (boxed): each cell access allocates a `Position` and an `Optional` unless C2 escape analysis removes them (see 5.3 numbers).

### 2.2 Cell (`jatatui.core.buffer.Cell`, final class, MUTABLE)
```java
public final class Cell {
  private Optional<String> symbol;                  // empty = " "
  public Color fg; public Color bg; public Color underlineColor; public Modifier modifier; public boolean skip;   // public mutable fields
  public static Cell empty(); public static Cell of(String symbol); public static Cell ofChar(char ch); public static Cell ofCodePoint(int cp);
  public Cell copy();
  public String symbol();  public Optional<String> rawSymbol();
  public Cell setSymbol(String s); public Cell setChar(char c); public Cell mergeSymbol(String s, Merge.MergeStrategy st);
  public Cell setFg(Color c); public Cell setBg(Color c); public Cell setUnderlineColor(Color c);
  public Cell setStyle(Style style);                // patches fg/bg/ul if present; modifier.insert(add).remove(sub)
  public Style style(); public Cell setSkip(boolean b); public void reset();
  // equals/hashCode structural
}
```
- Setters mutate and return `this`. `setStyle` allocates up to 2 `Modifier` records per call. Cells are reused across frames (`Buffer.reset()` / `Terminal.swapBuffers()` reset in place). Never hand a `Cell` or a `Buffer` to user code as if it were a value.

### 2.3 Geometry (`jatatui.core.layout`, all immutable records)
```java
public record Rect(int x, int y, int width, int height) {     // canonical ctor does NOT clamp
  static Rect of(int x, int y, int w, int h);                  // clamps area to u16 max (65535 cells), ratatui `Rect::new`
  static Rect fromPositionAndSize(Position, Size); static Rect fromSize(Size);
  long area(); boolean isEmpty(); int left(); int right(); int top(); int bottom();
  Rect inner(Margin m); Rect outer(Margin m); Rect offset(Offset o); Rect plus(Offset); Rect minus(Offset); Rect resize(Size);
  Rect union(Rect); Rect intersection(Rect); boolean intersects(Rect); boolean contains(Position); Rect clamp(Rect);
  Position asPosition(); Size asSize();
  Rect centeredHorizontally(Constraint); Rect centeredVertically(Constraint); Rect centered(Constraint h, Constraint v);
  Rect[] layout(Layout l); Rect[] layout(Layout l, int expected); List<Rect> layoutVec(Layout l); Either<String, Rect[]> tryLayout(Layout l, int expected);
  Rows rows(); Columns columns(); Positions positions();      // java.util.Iterator<Rect>/<Position>
}
public record Position(int x, int y) { U16_MAX = 65_535; ORIGIN; MIN; MAX; of; fromRect; offset; plus; minus }
public record Size(int width, int height) { ZERO; MIN; MAX; of; fromRect; long area() }
public record Margin(int horizontal, int vertical) { of }
public record Offset(int x, int y) { ZERO; MIN; MAX; of; negate }
public enum Direction { Horizontal, Vertical; perpendicular() }
public enum Flex { Legacy, Start, End, Center, SpaceBetween, SpaceEvenly, SpaceAround }
public sealed interface Spacing permits Spacing.Space, Spacing.Overlap { record Space(int cells); record Overlap(int cells); DEFAULT = Space(0); space(int); overlap(int); fromSigned(int) }
public enum HorizontalAlignment { Left, Center, Right }   public enum VerticalAlignment { Top, Center, Bottom }
public enum Corner { ... }
```
- u16 semantics are emulated with `int` + saturating arithmetic. Negative values are not rejected by the canonical record constructors.

### 2.4 Constraint and Layout
```java
public sealed interface Constraint permits Min, Max, Length, Percentage, Ratio, Fill {
  record Min(int v); record Max(int v); record Length(int v); record Percentage(int v); record Ratio(int numerator, int denominator); record Fill(int v);
  default int apply(int length);
  static List<Constraint> fromLengths(int...); fromRatios(int[][]); fromPercentages(int...); fromMaxes(int...); fromMins(int...); fromFills(int...);
}
public final class Layout {                                   // immutable, structural equals/hashCode, constraints = List.copyOf
  static final int DEFAULT_CACHE_SIZE = 500;
  static Layout empty();                                      // Vertical, no constraints, Margin(0,0), Flex.Start, Space(0)
  static Layout of(Direction, List<Constraint>); static Layout of(Direction, Constraint...);
  static Layout vertical(List<Constraint>); static Layout vertical(Constraint...); static Layout horizontal(List<Constraint>); static Layout horizontal(Constraint...);
  Direction direction(); List<Constraint> constraints(); Margin margin(); Flex flex(); Spacing spacing();
  Layout withDirection(Direction); withConstraints(List<Constraint>); withConstraints(Constraint...); withMargin(int); withHorizontalMargin(int); withVerticalMargin(int); withFlex(Flex); withSpacing(Spacing); withSpacing(int);
  Rect[] split(Rect area); List<Rect> splitVec(Rect area); SplitResult splitWithSpacers(Rect area);
  Rect[] areas(Rect area, int expected);  /* IllegalArgumentException on count mismatch */  Either<String, Rect[]> tryAreas(Rect, int); Rect[] spacers(Rect, int);
  static void initCache(int cacheSize); static int cacheCapacity();
  record SplitResult(Rect[] segments, Rect[] spacers) {}
}
```
- No validation in `Constraint` records (tui-scala threw for `Percentage` outside 0..100). Solver is a Java port of kasuari (`jatatui.core.layout.solver.*`).
- **Layout cache (VERIFIED in `Layout.java` lines 216-300): global, static, BOUNDED and thread-safe.** `private static LinkedHashMap<CacheKey(Rect area, Layout layout), SplitResult>` in access order with `removeEldestEntry -> size() > cacheCapacity` (LRU, default 500, `Layout.initCache(n)` replaces it), every get/put inside `synchronized (CACHE_LOCK)` (one global monitor). The solve itself runs outside the lock (two threads may solve the same key; `putIfAbsent` keeps the first). The key uses structural equality (`Layout.equals` compares direction, constraints list, margin, flex, spacing), so rebuilding an equal `Layout` every frame hits the cache. So the tui-scala leak (one entry per frame, lost updates) is FIXED.
- Remaining hazard: `split`/`areas` return the **cached `Rect[]` array itself** (no defensive copy). If a caller writes into the returned array, every later caller with the same key sees it. Wrap with `IArray.unsafeFromArray(arr.clone())` or copy into a Scala collection. Also contention: one global lock for all threads (fine for one render thread).
- jatatui-core and widgets never call `Layout` through a non-cached path, and the solver `Variable` ids come from a global `AtomicInteger` (thread-safe, wraps after 2^31 variables; UNVERIFIED whether wrap matters).

### 2.5 Style, Color, Modifier, Stylize (`jatatui.core.style`)
```java
public sealed interface Color permits Reset, Black, Red, Green, Yellow, Blue, Magenta, Cyan, Gray, DarkGray, LightRed, LightGreen, LightYellow, LightBlue, LightMagenta, LightCyan, White, Rgb, Indexed {
  record Reset() ... record White();  record Rgb(int r, int g, int b); record Indexed(int i);      // nullary records: new Color.Red().equals(Color.RED) is true
  Color RESET, BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, GRAY, DARK_GRAY, LIGHT_RED, ..., WHITE;   // interface constants
  static Color fromU32(int); static Color fromRgbArray(int[]); static Color fromRgbaArray(int[]);
  static Either<ParseColorError, Color> fromString(String);        // jatatui.core.layout.solver.Either (own Either type!)
}
public record Modifier(int bits) { BOLD, DIM, ITALIC, UNDERLINED, SLOW_BLINK, RAPID_BLINK, REVERSED, HIDDEN, CROSSED_OUT, EMPTY, ALL;
  isEmpty(); contains(Modifier); intersects(Modifier); insert; remove; or; minus; difference; union; intersection }
public record Style(Optional<Color> fg, Optional<Color> bg, Optional<Color> underlineColor, Modifier addModifier, Modifier subModifier) {
  DEFAULT; RESET; static empty(); static reset();
  withFg(Color); withBg(Color); withUnderlineColor(Color); withAddModifier(Modifier); withRemoveModifier(Modifier); hasModifier(Modifier); patch(Style);
  static fromFg(Color); fromFgBg(Color, Color); fromModifier(Modifier); fromModifierModifier(Modifier, Modifier); fromFgModifier; fromFgBgModifier; fromFgBgModifierModifier;
  black() onBlack() red() onRed() ... white() onWhite(); bold() notBold() dim() notDim() italic() notItalic() underlined() notUnderlined() slowBlink() ... crossedOut() notCrossedOut();   // shorthands, return new Style
}
public interface Styled<Self> { Style style(); Self setStyle(Style style); }       // setStyle returns a NEW object for text/widgets
public interface Stylize<Self extends Stylize<Self>> extends Styled<Self> { default Self bg(Color); fg(Color); underlineColor(Color); addModifier(Modifier); removeModifier(Modifier); reset(); black(); onBlack(); ... crossedOut(); notCrossedOut(); static StylizedStyle of(Style) }
// palettes: jatatui.core.style.palette.Tailwind / Material (Color constants)
```
- All immutable. **Gotcha (VERIFIED by compile error in probe run 1)**: `Style` is a record, so `style.fg()` is the record accessor returning `Optional<Color>`; `Style` does NOT implement `Stylize`, so `Style.empty().fg(Color.RED)` does not compile ("too many arguments for method fg: (): Optional[Color]"). Use `withFg`/`withBg` or the colour shorthands (`.yellow()`, `.onBlue()`). On `Span`/`Line`/`Text`/widgets, `fg(Color)` (Stylize) does exist.
- `Rgb` components are not range-checked (`fromU32` masks).

### 2.6 Text, Line, Span (`jatatui.core.text`) — immutable, structural equality
```java
public final class Span implements Stylize<Span> { public final String content; public final Style style;
  static empty(); raw(String); styled(String, Style); from(String);  withContent; withStyle; patchStyle(Style); resetStyle(); int width();
  List<StyledGrapheme> styledGraphemes(Style base); Line intoLeftAlignedLine(); intoCenteredLine(); intoRightAlignedLine(); Line plus(Span) }
public final class Line implements Stylize<Line>, Iterable<Span> { public final List<Span> spans; public final Style style; public final Optional<HorizontalAlignment> alignment;
  static empty(); raw(String); styled(String, Style); from(String); from(Span); from(Span...); fromSpans(List<Span>); fromIter(Iterable<String>);
  String toContentString(); withSpans; withStyle; withAlignment(HorizontalAlignment); leftAligned(); centered(); rightAligned(); patchStyle; resetStyle;
  withPushedSpan(Span|String); extended(Iterable<Span>); int width(); Line plus(Span); Text plus(Line) }
public final class Text implements Stylize<Text>, Iterable<Line> { public final List<Line> lines; public final Style style; public final Optional<HorizontalAlignment> alignment;
  static empty(); raw(String) /* splits on \n */; styled(String, Style); from(String|Span|Line|Line...|Masked); fromLines(List<Line>); fromIter(Iterable<String>);
  int width(); int height(); withStyle; patchStyle; resetStyle; withAlignment; leftAligned(); centered(); rightAligned();
  withPushedLine(Line|Span|String); withPushedSpan(Span|String); extended(Iterable<Line>); extendedStrings(Iterable<String>); Text plus(Line); Text plus(Text) }
public final class Masked implements Stylize<Masked> { static of(String s, char mask); value(); inner(); maskChar() }   // password-style text
public final class StyledGrapheme implements Stylize<StyledGrapheme> { public final String symbol; public final Style style; isWhitespace() }
```
- Lists are `List.copyOf` (immutable). `Line.alignment` is `Optional<HorizontalAlignment>` (empty = inherit from Paragraph/Text). Width uses `Wcwidth.width(String)` (port of unicode-width).

### 2.7 Widget / StatefulWidget / Frame (`jatatui.core.widgets`, `jatatui.core.terminal`)
```java
@FunctionalInterface public interface Widget { void render(Rect area, Buffer buf); static void renderString(String, Rect, Buffer); static void renderOptional(Optional<? extends Widget>, Rect, Buffer); }
@FunctionalInterface public interface StatefulWidget<State> { void render(Rect area, Buffer buf, State state); }
public final class Frame {                         // package-private constructor; only Terminal creates it
  Rect area();                                     // the viewport area
  void renderWidget(Widget widget, Rect area);
  <S> void renderStatefulWidget(StatefulWidget<S> widget, Rect area, S state);
  void setCursorPosition(Position p); void setCursorPosition(int x, int y);   // otherwise the cursor is hidden after draw
  Buffer bufferMut();  int count();                // count = frame number
}
public record CompletedFrame(Buffer buffer, Rect area, int count) {}   // buffer is reset in place by the NEXT draw
```
- Both widget interfaces are SAM types: a Scala lambda `(area, buf) => ...` typed as `Widget` compiles (P). Widgets take `Buffer` + `Rect` directly, so a tui4s widget can render into a private `Buffer` for tests without any Terminal.

### 2.8 Terminal and Viewport (`jatatui.core.terminal`)
```java
public final class Terminal<B extends Backend> implements AutoCloseable {
  static <B extends Backend> Terminal<B> create(B backend) throws IOException;                        // Fullscreen
  static <B extends Backend> Terminal<B> withOptions(B backend, TerminalOptions options) throws IOException;
  Frame getFrame(); Buffer currentBufferMut(); B backend();
  CompletedFrame draw(Consumer<Frame> renderCallback) throws IOException;   // autoresize -> callback -> flush(diff) -> hide/show cursor -> swapBuffers -> backend.flush
  void flush(); void resize(Rect area); void autoresize(); void swapBuffers();
  void hideCursor(); void showCursor(); Position getCursorPosition(); void setCursorPosition(Position);
  void clear();                                    // Fullscreen: clearRegion(All); Inline: clear after viewport start; Fixed: per row
  Size size();
  void insertBefore(int height, Consumer<Buffer> drawFn) throws IOException;   // ONLY for Viewport.Inline, silently no-op otherwise; no scrolling-region variant
  void close();                                    // only re-shows the cursor if hidden (does NOT leave raw mode / alt screen)
}
public sealed interface Viewport permits Fullscreen, Inline, Fixed { record Fullscreen(); record Inline(int height); record Fixed(Rect area); FULLSCREEN; fullscreen(); inline(int); fixed(Rect) }
public record TerminalOptions(Viewport viewport) { static fullscreen() }
```
- No `try_draw` equivalent; the callback cannot return a value (it is a `Consumer`). Exceptions thrown inside the callback propagate out of `draw` after the frame was partially rendered into the back buffer; the next `draw` starts from the reset buffer anyway.
- Per-frame backend calls on a `CrosstermBackend`: `size()` (JNI `terminalSize`, in `autoresize`), `draw` (one JNI `enqueue` of all commands), `hideCursor()` (JNI `execute(Hide)` = write + flush) or `showCursor()+setCursorPosition`, `flush()` (JNI). So 4 JNI calls per frame.
- `Inline` viewport needs `backend.getCursorPosition()` (JNI `cursorPosition`, which queries the terminal) at creation and resize.

### 2.9 Backend and TestBackend (`jatatui.core.backend`)
```java
public interface Backend {      // every method `throws IOException`
  void draw(Iterable<BufferUpdate> content); default void appendLines(int n) {}
  void hideCursor(); void showCursor(); Position getCursorPosition(); void setCursorPosition(Position);
  void clear(); void clearRegion(ClearType); Size size(); WindowSize windowSize(); void flush();
  void scrollRegionUp(int start, int end, int lines); void scrollRegionDown(int start, int end, int lines);
}
public enum ClearType { All, AfterCursor, BeforeCursor, CurrentLine, UntilNewLine }
public record WindowSize(Size columns, Size pixels) {}
public final class TestBackend implements Backend {
  public TestBackend(int width, int height); static TestBackend withLines(String... lines);
  Buffer buffer(); Buffer scrollback(); void resize(int w, int h); boolean cursorShown(); Position pos();
  static String bufferView(Buffer buf);   // "\"line\"\n" per row, marks cells hidden by wide glyphs; toString() = bufferView(buffer())
  // equals/hashCode structural; full scrollRegionUp/Down + appendLines emulation with scrollback
}
```
- `TestBackend.draw` copies symbol/fg/bg/underline/modifier/skip into its own cells (no aliasing; tui-scala's unpublished one aliased). VERIFIED in source and by probe (second frame rendered correctly).
- How to assert: (a) `backend.buffer()` equals `Buffer.withLines("...", ...)` (structural, includes styles, so only for unstyled output); (b) compare rows of `cellAt(x,y).symbol()` strings (probe); (c) check styles on single cells (`cell.fg`, `cell.modifier.contains(...)`); (d) upstream tests use `BufferAssertions.assertBufferEq(actual, expected)` (in the unpublished `jatatui-tests` module: diff-based message) — tui4s should write its own munit/zio-test helper on the same idea.

## 3. jatatui-widgets API

Source: `jatatui-widgets-0.31.0-sources.jar` (`.work/scratch/jatatui/src/jatatui-widgets/`). All VERIFIED by reading source; widgets used in the probe are marked (P).

### 3.0 General shape (VERIFIED)
- Every widget is an **immutable value with `withX(...)` "wither" builders** (each returns a new instance). Collections are defensively copied with `List.copyOf` (Block titles, List items, Table rows/widths, Row cells, Tabs titles, Chart datasets, Axis labels, Dataset data, BarChart/BarGroup, Sparkline). `Table`, `Row`, `TableCell` are Java **records**; the rest are final classes with public final fields and structural `equals`/`hashCode` (so memoisation "skip render if props equal" works, unlike tui-scala's Array-based case classes).
- Exceptions: `Canvas` keeps `double[]` bounds and an opaque `Consumer<Context>`; `canvas.Points(Coord[] coords, Color)` is a record over an array (reference equality); `CalendarEventStore` wraps a mutable `HashMap` with `add(...)`.
- Stateful widgets implement `StatefulWidget<S>`; their **state objects are MUTABLE** and are mutated by `render` (offset clamping, selection clamping). Their `withX` methods on states MUTATE and return `this` (naming trap).
- Most widgets implement `Stylize<Self>` (fg/bg/bold/... helpers return a new widget).
- Throwing preconditions: `Gauge.withPercent(p)` (p not in 0..100) and `Gauge.withRatio(r)` / `LineGauge.withRatio(r)` (r not in 0..1) -> `IllegalArgumentException`; `Table` canonical constructor -> `IllegalArgumentException` when a width is `Percentage(>100)`; `canvas.Context` bounds length != 2 -> IAE; `Layout.areas(area, n)` count mismatch -> IAE.
- Name clashes for Scala: `jatatui.widgets.list.List` (vs `scala.List`/`java.util.List`), `jatatui.widgets.canvas.Map`/`Line`/`Rectangle` (vs `scala.collection.Map`, `jatatui.core.text.Line`), `jatatui.core.symbols.Line`/`Block`/`Bar` (vs widgets), `jatatui.widgets.barchart.Bar` vs `jatatui.core.symbols.Bar`. Use renaming imports.

### 3.1 Borders, Block (`jatatui.widgets.Borders`, `jatatui.widgets.block.*`) (P)
```java
public record Borders(int bits) { NONE, TOP=1, RIGHT=2, BOTTOM=4, LEFT=8, ALL;
  isEmpty(); isAll(); contains(Borders); intersects(Borders); insert(Borders); remove(Borders) /* bits & ~other: tui-scala bug FIXED */; or; minus; union; intersection; difference }
public final class Block implements Widget, Stylize<Block> {
  public record BlockTitle(Optional<TitlePosition> position, Line line) {}
  public final List<BlockTitle> titles; public final Style titlesStyle; public final HorizontalAlignment titlesAlignment; public final TitlePosition titlesPosition;
  public final Borders borders; public final Style borderStyle; public final Border.Set borderSet; public final Style style; public final Padding padding; public final Merge.MergeStrategy mergeBorders;
  static Block empty(); static Block bordered();
  Block withTitle(Line|String);  withTitleTop(Line|String);  withTitleBottom(Line|String);    // titles accumulate
  withTitleStyle(Style); withTitleAlignment(HorizontalAlignment); withTitlePosition(TitlePosition);
  withBorderStyle(Style); withStyle(Style); withBorders(Borders); withBorderType(BorderType); withBorderSet(Border.Set); withPadding(Padding); withMergeBorders(Merge.MergeStrategy);
  Rect inner(Rect area);  SpacePair horizontalSpace(); SpacePair verticalSpace();  void render(Rect, Buffer);
}
public enum BorderType { Plain, Rounded, Double, Thick, LightDoubleDashed, HeavyDoubleDashed, LightTripleDashed, HeavyTripleDashed, LightQuadrupleDashed, HeavyQuadrupleDashed, QuadrantInside, QuadrantOutside; Border.Set toBorderSet() }
public record Padding(int left, int right, int top, int bottom) { ZERO; zero(); horizontal(int); vertical(int); uniform(int); proportional(int); symmetric(int x, int y); left/right/top/bottom(int) }
public enum TitlePosition { Top, Bottom }
```
- Per-title alignment = the `Line`'s own alignment (`Line.from("x").centered()`), falling back to `titlesAlignment`. Border merging (`Merge.MergeStrategy` Replace/Exact/Fuzzy) is the ratatui 0.30 "collapsed borders" feature.

### 3.2 Paragraph (`jatatui.widgets.paragraph.*`) (P)
```java
public final class Paragraph implements Widget, Stylize<Paragraph> {
  public final Optional<Block> block; public final Style style; public final Optional<Wrap> wrap; public final Text text; public final Scroll scroll; public final HorizontalAlignment alignment;
  static Paragraph of(Text|String|Line|List<Line>);
  withBlock(Block); withStyle(Style); withWrap(Wrap); withScroll(Scroll); withAlignment(HorizontalAlignment); leftAligned(); centered(); rightAligned();
  int lineCount(int width); int lineWidth(); void render(Rect, Buffer);
}
public record Wrap(boolean trim) {}          // no wrap unless withWrap(...)
public record Scroll(int y, int x) { ZERO }  // NOTE order: (y, x) like ratatui's (u16,u16) tuple
```

### 3.3 List (`jatatui.widgets.list.*`) (P)
```java
public final class List implements Widget, StatefulWidget<ListState>, Stylize<List> {
  public final Optional<Block> block; public final java.util.List<ListItem> items; public final Style style; public final ListDirection direction;
  public final Style highlightStyle; public final Optional<Line> highlightSymbol; public final boolean repeatHighlightSymbol; public final HighlightSpacing highlightSpacing; public final int scrollPadding;
  static List empty(); static List of(Collection<ListItem>); static List of(ListItem...); static List ofStrings(String...);
  withItems(Collection<ListItem>); withBlock(Block); withStyle(Style); withHighlightSymbol(Line|String); withHighlightStyle(Style); withRepeatHighlightSymbol(boolean);
  withHighlightSpacing(HighlightSpacing); withDirection(ListDirection); withScrollPadding(int); int len(); boolean isEmpty();
  void render(Rect, Buffer);                 // uses a fresh ListState
  void render(Rect, Buffer, ListState);      // MUTATES state: clamps selected to items.size()-1 (clears it if empty), sets offset
}
public final class ListItem implements Stylize<ListItem> { public final Text content; public final Style style; static of(Text|String|Span|Line); static ofLines(List<Line>); withStyle; withContent; height(); width() }
public final class ListState {               // MUTABLE
  static ListState empty(); static ListState of(int offset, Optional<Integer> selected);
  ListState withOffset(int) /* mutates, returns this */; ListState withSelected(Optional<Integer>) /* mutates */;
  int offset(); void setOffset(int); Optional<Integer> selected(); void select(Optional<Integer>) /* empty also resets offset to 0 */;
  void selectNext(); selectPrevious(); selectFirst(); selectLast() /* = Integer.MAX_VALUE, clamped at render */; scrollDownBy(int); scrollUpBy(int);
}
public enum ListDirection { TopToBottom, BottomToTop }   public enum HighlightSpacing { Always, WhenSelected, Never }
```

### 3.4 Table (`jatatui.widgets.table.*`)
```java
public record Table(List<Row> rows, Optional<Row> header, Optional<Row> footer, List<Constraint> widths, int columnSpacing, Optional<Block> block, Style style,
                    Style rowHighlightStyle, Style columnHighlightStyle, Style cellHighlightStyle, Text highlightSymbol, HighlightSpacing highlightSpacing, Flex flex)
    implements Widget, StatefulWidget<TableState>, Stylize<Table> {
  static Table empty(); static Table of(List<Row> rows, List<Constraint> widths);
  withRows; withHeader(Row); withFooter(Row); withWidths(List<Constraint>); withColumnSpacing(int); withBlock(Block); withStyle(Style); withRowHighlightStyle; withColumnHighlightStyle; withCellHighlightStyle;
  withHighlightSymbol(Text|String); withHighlightSpacing(HighlightSpacing); withFlex(Flex); render(Rect, Buffer); render(Rect, Buffer, TableState);
  List<XAndWidth> getColumnWidths(int maxWidth, int selectionWidth, int colCount); int columnCount();
}
public record Row(List<TableCell> cells, int height, int topMargin, int bottomMargin, Style style) { empty(); of(List<TableCell>|TableCell...); ofStrings(List<String>|String...); withCells; withHeight; withTopMargin; withBottomMargin; withStyle }
public record TableCell(Text content, Style style) { empty(); of(Text|String); withContent(Text|String); withStyle }
public final class TableState {   // MUTABLE; row + column + cell selection (ratatui 0.30)
  public TableState(); MAX_INDEX; withOffset(int); withSelected(Optional<Integer>|int); withSelectedColumn(Optional<Integer>|int); withSelectedCell(Optional<RowAndColumn>|int,int)   // all mutate + return this
  offset(); selected(); selectedColumn(); selectedCell(); setOffset; setSelected; setSelectedColumn; select(Optional<Integer>|int); selectColumn(...); selectCell(...);
  selectNext/Previous/First/Last(); selectNext/Previous/First/LastColumn(); scrollDownBy; scrollUpBy; scrollRightBy; scrollLeftBy;  record RowAndColumn(int row, int column)
}
```
- Column widths are computed through `Layout` (cached, see 2.4); `Table` builds an equal `Layout` each render, so it hits the LRU cache instead of leaking (tui-scala leaked here).

### 3.5 Tabs, Gauge, LineGauge, Sparkline (P for Gauge)
```java
public final class Tabs implements Widget, Stylize<Tabs> { DEFAULT_HIGHLIGHT_STYLE = REVERSED; static of(List<Line>); ofStrings(String...); empty();
  withTitles(List<Line>); withTitlesStrings(String...); withBlock; withSelected(int); withoutSelected(); withStyle; withHighlightStyle; withDivider(Span|String);
  withPadding(Line,Line | String,String); withPaddingLeft(Line|String); withPaddingRight(Line|String); titles(); Optional<Integer> selected(); width(); render }
public final class Gauge implements Widget, Stylize<Gauge> { static empty(); withBlock; withPercent(int) /* IAE */; withRatio(double) /* IAE */; withLabel(Span|String); withStyle; withGaugeStyle; withUseUnicode(boolean); double ratio(); render }
public final class LineGauge implements Widget, Stylize<LineGauge> { static empty(); withBlock; withRatio(double) /* IAE */; withFilledSymbol(String); withUnfilledSymbol(String); withLabel(Line|String); withStyle; withFilledStyle; withUnfilledStyle; render }
public final class Sparkline implements Widget, Stylize<Sparkline> { static empty(); withBlock; withStyle; withAbsentValueStyle; withAbsentValueSymbol(String); withData(List<SparklineBar>); withDataLongs(long...);
  withDataOptionals(List<Optional<Long>>); withMax(long); withoutMax(); withBarSet(symbols.Bar.Set); withDirection(RenderDirection); render }
public final class SparklineBar { public final Optional<Long> value; public final Optional<Style> style; of(long); of(Optional<Long>); absent(); withStyle(Style|Optional<Style>) }
public enum RenderDirection { LeftToRight, RightToLeft }
```

### 3.6 BarChart, Chart, Canvas, Calendar
```java
public final class BarChart implements Widget, Stylize<BarChart> { empty(); of(List<Bar>|Bar...); vertical(List<Bar>); horizontal(List<Bar>); grouped(List<BarGroup>);
  withGroup(BarGroup); withData(List<LabelledValue>|LabelledValue...); withBlock; withMax(long); withBarStyle; withBarWidth(int); withBarGap(int); withBarSet(symbols.Bar.Set);
  withValueStyle; withLabelStyle; withGroupGap(int); withStyle; withDirection(Direction) }
public final class Bar { long value; Optional<Line> label; Style style; Style valueStyle; Optional<String> textValue; empty(); of(long); withLabel(String|Line, long); withValue; withLabel; withStyle; withValueStyle; withTextValue }
public final class BarGroup { Optional<Line> label; List<Bar> bars; empty(); of(List<Bar>|Bar...); withLabel(String|Line, List<Bar>); fromPairs(List<LabelledValue>|LabelledValue...); record LabelledValue(String label, long value) }
public final class Chart implements Widget, Stylize<Chart> { empty(); of(List<Dataset>|Dataset...); withBlock; withStyle; withXAxis(Axis); withYAxis(Axis); withHiddenLegendConstraints(HiddenLegendConstraints);
  withLegendPosition(Optional<LegendPosition>|LegendPosition); Optional<ChartLayout> layout(Rect) }
public final class Dataset { Optional<Line> name; List<Point> data; Marker marker; GraphType graphType; Style style; empty(); withName(Line|String); withData(List<Point>|Point...); withMarker(Marker); withGraphType(GraphType); withStyle }
public final class Axis { record Bounds(double lo, double hi); Optional<Line> title; Bounds bounds; List<Line> labels; Style style; HorizontalAlignment labelsAlignment;
  empty(); withTitle(Line|String); withBounds(Bounds | double lo, double hi); withLabels(List<Line>|String...|Line...); withStyle; withLabelsAlignment }
public record Point(double x, double y) { fromCoord(Coord) }   public enum GraphType { Scatter, Line, Bar }
public enum LegendPosition { Top, TopRight, TopLeft, Left, Right, Bottom, ... }   public record HiddenLegendConstraints(Constraint width, Constraint height)
public final class Canvas implements Widget { empty(); withBlock; withXBounds(double[]); withYBounds(double[]); withPaintFn(Consumer<Context>); withBackgroundColor(Color); withMarker(symbols.Marker) }
public final class Context { Context(int w, int h, double[] xBounds, double[] yBounds, Marker); marker(Marker); draw(Shape); layer(); print(double x, double y, Line|String) }
public interface Shape { void draw(Painter painter); }     // Painter: getPoint(double,double) -> Optional<GridPoint>, paint(int x, int y, Color), resolution(), bounds()
// shapes (records): Circle(x,y,radius,Color); canvas.Line(x1,y1,x2,y2,Color); Points(Coord[] coords, Color); Rectangle(x,y,width,height,Color); canvas.Map(MapResolution, Color); Label(x,y,Line)
public enum MapResolution { Low, High }   // world map data loaded lazily from classpath resources (static volatile cache in WorldMap)
public enum symbols.Marker { Dot, Block, Bar, Braille, HalfBlock, Quadrant, Sextant, Octant }
public final class Calendar implements Widget { static of(java.time.LocalDate displayDate, DateStyler events); withShowSurrounding(Style); withShowWeekdaysHeader(Style); withShowMonthHeader(Style); withDefaultStyle(Style); withBlock; width(); height() }
@FunctionalInterface public interface DateStyler { Style getStyle(LocalDate date); }   // SAM: Scala lambda works
public final class CalendarEventStore implements DateStyler { public final Map<LocalDate, Style> events /* mutable HashMap */; empty(); today(Style); add(LocalDate, Style); lookupStyle; getStyle }
```

### 3.7 Scrollbar, Clear, TextInput, extras
```java
public final class Scrollbar implements StatefulWidget<ScrollbarState> {    // stateful ONLY (no Widget overload)
  static of(ScrollbarOrientation); empty(); withOrientation; withOrientationAndSymbol(ScrollbarOrientation, symbols.Scrollbar.Set); withThumbSymbol(String); withThumbStyle;
  withTrackSymbol(Optional<String>); withTrackStyle; withBeginSymbol(Optional<String>); withBeginStyle; withEndSymbol(Optional<String>); withEndStyle; withSymbols(Set); withStyle; render(Rect, Buffer, ScrollbarState) }
public final class ScrollbarState { public int contentLength; public int position; public int viewportContentLength;   // public mutable fields
  of(int contentLength); empty(); withPosition(int); withContentLength(int); withViewportContentLength(int) /* mutate + return this */; prev(); next(); first(); last(); scroll(ScrollDirection); getPosition() }
public enum ScrollbarOrientation { VerticalRight, VerticalLeft, HorizontalBottom, HorizontalTop }   public enum ScrollDirection { Forward, Backward }
public final class Clear implements Widget { INSTANCE; instance(); new Clear(); render }      // resets every cell in area (for popups)
public final class TextInput implements Widget {   // jatatui ADDITION (not in ratatui); stateless "controlled" widget
  public final String value; int cursorPos; int scrollOffset; boolean focused; String placeholder; Style style, focusedStyle, placeholderStyle, cursorStyle;
  static of(String value); withValue; withCursorPos(int); withScrollOffset(int); withFocused(boolean); withPlaceholder(String); withStyle; withFocusedStyle; withPlaceholderStyle; withCursorStyle;
  static int scrollFor(int cursorPos, int currentScroll, int width);
  record TextResult(String value, int cursorPos); static TextResult insertAt(String, int, char); insertStringAt(String, int, String); backspaceAt; deleteAt; moveLeft; moveRight; moveHome; moveEnd
}
// also: JatatuiLogo, JatatuiMascot (fun widgets)
```
- **TextInput is not stateful**: state (value, cursor, scroll, focus) lives with the caller; the static helpers are pure transitions. Cursor positions are **UTF-16 `char` indexes** (`substring`, `charAt`, insert `char`): surrogate pairs (emoji) and wide CJK glyphs are not handled (cursor can split a surrogate pair; width assumes 1 column per char). tui4s should implement its own grapheme-aware text input (or post-process) rather than wrap this one.

### 3.8 Second probe: every other widget from Scala (VERIFIED, compiled and ran)
`.work/scratch/jatatui/probe-widgets.scala` (Scala 3.9.0, JDK 25, headless): Table+TableState (header, block, highlight symbol), Tabs, LineGauge, Sparkline, Scrollbar+ScrollbarState, TextInput (+ pure `insertAt`), Clear, BarChart (`LabelledValue`), Chart (Braille line dataset + axes), Canvas (`withPaintFn` as a Scala lambda, `Circle` + canvas `Line`), Calendar (`DateStyler` as a Scala lambda), Block padding, and the **Inline viewport with `insertBefore`** on a `TestBackend` (works headless; the viewport moved below the inserted line). Output (`probe-widgets-output.txt`):
```
--- Table (selected=1)
|┌table───────────────────────┐|
|│  #   name                  │|
|│  1   alpha                 │|
|│> 2   beta                  │|
|│  3   gamma                 │|
|└────────────────────────────┘|
table state offset=0 selected=Optional[1]
--- Tabs / LineGauge / Sparkline+Scrollbar / TextInput
| one │ two │ three            |
|50% ──────────────────────────|
|▁▃▅█▅▃▁   ◄════════███═══════►|
|héllo                         |
|                              |
TextInput.insertAt -> aXb cursor=2
--- BarChart
|    ███             |
|    ███             |
|▅▅▅ ███             |
|█3█ █7█             |
| a   b              |
--- Chart
|5│            ⡠⠔⠑⠢⢄⡀          |
| │         ⢀⡠⠊     ⠈⠑⠢⢄⡀      |
| │       ⢀⠔⠁           ⠈⠑⠢⢄⡀  |
| │     ⡠⠊⠁                 ⠈⠑⠢|
| │  ⢀⠔⠉                       |
|0│⡠⠒⠁                         |
| └────────────────────────────|
| 0                          10|
--- Canvas
|        ⣀⡤⢤⣀     ⡠⠔⠊|
|      ⣠⠏⠁  ⠈⠹⣄⠤⠒⠉   |
|      ⡇   ⣀⠔⠊⢹      |
|      ⣇⡠⠔⠉   ⢸      |
|   ⣀⠤⠒⠙⣆⡀  ⢀⣰⠋      |
|⡠⠔⠊     ⠉⠓⠚⠉        |
--- Calendar
|    September 2026    |
| Su Mo Tu We Th Fr Sa |
|        1  2  3  4  5 |
|  6  7  8  9 10 11 12 |
| 13 14 15 16 17 18 19 |
| 20 21 22 23 24 25 26 |
| 27 28 29 30          |
|                      |
--- Inline viewport after insertBefore (TestBackend screen)
|                    |
|                    |
|log line 1          |
|┌inline────────────┐|
|└──────────────────┘|
|                    |
scrollback rows=0
```

## 4. jatatui-crossterm + tui.crossterm event model

Sources: `crossterm-0.31.0-sources.jar` (`tui.crossterm`), `jatatui-crossterm-0.31.0-sources.jar`, Rust `crossterm/cargo/src/*.rs` at tag v0.31.0 (fetched with `gh api` into `.work/scratch/jatatui/cargo/`). All VERIFIED by reading source unless marked.

### 4.1 `tui.crossterm.CrosstermJni` (unchanged API vs tui-scala 0.0.10)
```java
public class CrosstermJni {                       // static init loads the native lib (see 1.2/1.3); instances are stateless handles
  public native void flush();                     // flush Rust stdout
  public native boolean poll(Duration timeout);   // tui.crossterm.Duration(long secs, int nanos), NOT java.time.Duration
  public native Event read();                     // blocks until an event
  public native Xy terminalSize();                // record Xy(int x, int y) = (columns, rows)
  public native Xy cursorPosition();              // queries the terminal (writes ESC[6n, reads reply)
  public native void disableRawMode();  public native void enableRawMode();
  public native void enqueue(List<Command> commands);  public final void enqueue(Command... commands);   // queue to Rust stdout buffer
  public native void execute(List<Command> commands);  public final void execute(Command... commands);   // queue + flush
}
```
Scala: `jni.execute(Command.EnterAlternateScreen(), Command.EnableMouseCapture())` (Java varargs), `jni.poll(tui.crossterm.Duration(0, 50_000_000))`. All failures are `RuntimeException("IO error: ...")`.

### 4.2 Event model (`tui.crossterm`, sealed interfaces + records + enums)
```java
public sealed interface Event permits FocusGained, FocusLost, Key, Mouse, Paste, Resize {
  record FocusGained(); record FocusLost();        // only after Command.EnableFocusChange
  record Key(KeyEvent keyEvent); record Mouse(MouseEvent mouseEvent);
  record Paste(String string);                     // only after Command.EnableBracketedPaste
  record Resize(int columns, int rows);
}
public record KeyEvent(KeyCode code, KeyModifiers modifiers, KeyEventKind kind, KeyEventState state) {}
public sealed interface KeyCode permits Backspace, Enter, Left, Right, Up, Down, Home, End, PageUp, PageDown, Tab, BackTab, Delete, Insert,
    F, Char, Null, Esc, CapsLock, ScrollLock, NumLock, PrintScreen, Pause, Menu, KeypadBegin, Media, Modifier {
  record F(int num); record Char(char c); record Media(MediaKeyCode mediaKeyCode); record Modifier(ModifierKeyCode modifierKeyCode); /* others nullary records */ }
public record KeyModifiers(int bits) { static final int SHIFT=1, CONTROL=2, ALT=4, SUPER=8, HYPER=16, META=32, NONE=0; }   // ints, no contains() helper
public enum KeyEventKind { Press, Repeat, Release }
public record KeyEventState(int bits) { KEYPAD=1, CAPS_LOCK=2, NUM_LOCK=4, NONE=0 }      // FIXED (0.0.10 had CAPS_LOCK == NUM_LOCK == 8)
public record MouseEvent(MouseEventKind kind, int column, int row, KeyModifiers modifiers) {}
public sealed interface MouseEventKind permits Down, Up, Drag, Moved, ScrollDown, ScrollUp, ScrollLeft, ScrollRight {
  record Down(MouseButton mouseButton); record Up(MouseButton mouseButton); record Drag(MouseButton mouseButton); record Moved(); record ScrollDown(); record ScrollUp(); record ScrollLeft(); record ScrollRight(); }   // ScrollLeft/Right NEW
public enum MouseButton { Left, Right, Middle }
public enum ModifierKeyCode { LeftShift, LeftControl, LeftAlt, LeftSuper, LeftHyper, LeftMeta, RightShift, RightControl, RightAlt, RightSuper, RightHyper, RightMeta, IsoLevel3Shift, IsoLevel5Shift }
public enum MediaKeyCode { Play, Pause, PlayPause, Reverse, Stop, FastForward, Rewind, TrackNext, TrackPrevious, Record, LowerVolume, RaiseVolume, MuteVolume }
public record KeyboardEnhancementFlags(int bits) { DISAMBIGUATE_ESCAPE_CODES=1, REPORT_EVENT_TYPES=2, REPORT_ALTERNATE_KEYS=4, REPORT_ALL_KEYS_AS_ESCAPE_CODES=8, REPORT_ASSOCIATED_TEXT=16 }
```
(Record component names VERIFIED in 0.31 source.)

### 4.3 Commands (`tui.crossterm.Command`, sealed interface of records)
`MoveTo(int x, int y)`, `MoveToNextLine(int num_lines)`, `MoveToPreviousLine(int num_lines)`, `MoveToColumn(int column)`, `MoveToRow(int row)`, `MoveUp(int num_rows)`, `MoveRight(int num_columns)`, `MoveDown(int num_rows)`, `MoveLeft(int num_columns)`, `SavePosition()`, `RestorePosition()`, `Hide()`, `Show()`, `EnableBlinking()`, `DisableBlinking()`, `SetCursorShape(CursorShape)` (legacy, mapped to steady SetCursorStyle), **`SetCursorStyle(CursorStyle)` (NEW: DefaultUserShape, BlinkingBlock, SteadyBlock, BlinkingUnderScore, SteadyUnderScore, BlinkingBar, SteadyBar)**, `EnableMouseCapture()`, `DisableMouseCapture()`, `PushKeyboardEnhancementFlags(KeyboardEnhancementFlags flags)`, `PopKeyboardEnhancementFlags()`, `EnableFocusChange()`, `DisableFocusChange()`, `EnableBracketedPaste()`, `DisableBracketedPaste()`, `SetForegroundColor(Color)`, `SetBackgroundColor(Color)`, `SetUnderlineColor(Color)`, `SetColors(Optional<Color> fg, Optional<Color> bg)`, `SetAttribute(Attribute)`, `SetAttributes(List<Attribute>)`, `SetStyle(Optional<Color>, Optional<Color>, Optional<Color>, List<Attribute>)`, `ResetColor()`, `DisableLineWrap()`, `EnableLineWrap()`, `EnterAlternateScreen()`, `LeaveAlternateScreen()`, `ScrollUp(int)`, `ScrollDown(int)`, `Clear(ClearType)`, `SetSize(int columns, int rows)`, `Print(String value)`.
`tui.crossterm.Color` = crossterm colours (`Reset, Black, DarkGrey, Red, DarkRed, ..., Grey, Rgb(r,g,b), AnsiValue(int)`), distinct from `jatatui.core.style.Color` (`CrosstermColorConv` maps them).

- **How the native side decodes commands**: for every command object it calls Java `toString()` (record toString, e.g. `Print[value=...]`), splits on `[` to get the class name, then reads fields by name with JNI (`env.get_field(obj, "x", "I")`). So each command costs a JVM up-call that builds a String containing the whole payload, plus one JNI field access per component. Fine for a few hundred changed cells; this is where per-changed-cell cost goes (one `Print` per changed cell, see `CrosstermBackend.draw`).
- A Rust `panic!` exists for an unknown enum name in decoding (6 sites); it can only fire if Java and native disagree on names. A panic across `extern "system"` aborts the JVM (UNVERIFIED for this build; Rust >= 1.81 aborts on unwinding out of `extern "C"`).

### 4.4 Differences vs tui-scala 0.0.10's crossterm (VERIFIED by `diff -r` of the Java sources and reading the Rust sources)
| item | 0.0.10 | 0.31.0 |
|---|---|---|
| Rust crossterm crate | 0.25 | **0.29** |
| `Command.SetCursorStyle` + `CursorStyle` enum | no | **yes** |
| `MouseEventKind.ScrollLeft/ScrollRight` | no | **yes** |
| `KeyEventState` CAPS_LOCK/NUM_LOCK | both 8 (bug) | 2 / 4 |
| native `KeyCode::Modifier` -> Java | `nullary(...)` (wrong ctor, would fail) | builds `KeyCode.Modifier(ModifierKeyCode)` (fixed) |
| native `KeyCode::Char(c)` | `c as u16 // todo` | **unchanged: `c as u16 // todo`** -> non-BMP chars (emoji) typed by the user are truncated to a wrong `char` |
| NativeLoader | `getResourceAsStream` null check | same logic via `Optional`; still env `TUI_SCALA_PLATFORM`, still leaks a temp dir |
| GraalVM `jni-config.json` | partial | every `tui.crossterm` class registered (0.31.0 release note) |
| Java package | `tui.crossterm` | `tui.crossterm` (**same package -> never mix the two artifacts**) |

Hazards still present: `Char(char)` cannot carry a non-BMP code point (pasted emoji arrive whole through `Event.Paste` only when bracketed paste is enabled); key Release/Repeat events arrive on Windows always and on Unix only with `PushKeyboardEnhancementFlags(REPORT_EVENT_TYPES)` in kitty-protocol terminals (crossterm behaviour, UNVERIFIED here) -> filter `kind == Press`. `KeyModifiers`/`KeyEventState`/`KeyboardEnhancementFlags` are int-bit records with no helper methods.

### 4.5 `jatatui.crossterm.CrosstermBackend` and `Jatatui`
```java
public final class CrosstermBackend implements Backend {
  public CrosstermBackend(CrosstermJni writer); public CrosstermJni writer();
  // draw: one ArrayList<Command> per frame: MoveTo when not adjacent, ModifierDiff attrs, SetForeground/Background/UnderlineColor on change, Print(symbol) per cell,
  //       then 4 reset commands, then ONE jni.enqueue(list). Wraps every native RuntimeException in IOException.
  // size()/windowSize(): JNI terminalSize (pixels reported as 0x0); getCursorPosition: JNI cursorPosition; scrollRegionUp/Down: raw CSI via Print.
}
final class CrosstermColorConv / CrosstermModifierConv / CrosstermStyleConv / ContentStyle   // conversion glue
public final class Jatatui {
  private static final CrosstermJni JNI = new CrosstermJni();          // loads the native lib on first use of Jatatui
  public static Terminal<CrosstermBackend> init() throws IOException;  // enableRawMode + EnterAlternateScreen + installPanicHook + Terminal.create
  public static void restore();                                        // LeaveAlternateScreen + disableRawMode; errors printed to System.err
  public static void runIo(IoConsumer body) throws IOException;        // init (outside try) -> body -> finally { terminal.close(); restore(); }
  @FunctionalInterface public interface IoConsumer { void accept(Terminal<CrosstermBackend> t) throws IOException; }
}
```
- `installPanicHook()` (once per JVM, synchronized): **replaces the JVM-global `Thread.setDefaultUncaughtExceptionHandler`** with one that calls `restore()` then delegates to the previous handler, and **adds a JVM shutdown hook** that calls `restore()` (so restore also runs at every JVM exit, even long after the TUI ended, and runs twice after `runIo`).
- Not enabled by `init`: mouse capture, bracketed paste, focus change, keyboard enhancement flags. No inline-viewport helper.
- Recommendation for tui4s: do NOT use `Jatatui.init/runIo` (global handler mutation fights ZIO/CE/Kyo's own handlers and is not resource-safe). Implement acquire/release with Kyo `Scope` (and ZIO `Scope` / CE `Resource`): load `CrosstermJni` first (so the JEP 472 warning happens before raw mode), then `enableRawMode`, `execute(EnterAlternateScreen, Hide, optional EnableMouseCapture/EnableBracketedPaste/EnableFocusChange/PushKeyboardEnhancementFlags)`, and release in reverse order; optionally add our own shutdown hook guarded by an `AtomicBoolean`.

## 5. Threading and performance

### 5.1 Global / static mutable state (VERIFIED: grep over all four runtime source jars for non-final statics and static collections)
| where | what | thread-safe? |
|---|---|---|
| `jatatui.core.layout.Layout` | `static LinkedHashMap<CacheKey, SplitResult> cache` (LRU, cap 500) + `static int cacheCapacity` | yes, one global `synchronized (CACHE_LOCK)`; bounded. Returned `Rect[]` are shared (do not mutate). |
| `jatatui.core.layout.solver.Variable` | `static final AtomicInteger VARIABLE_ID` | yes |
| `jatatui.widgets.canvas.WorldMap` | `static volatile Optional<Coord[]> cachedLow` (lazy world-map data) | yes (benign race) |
| `jatatui.core.symbols.Merge` | `static final HashMap`s filled in `<clinit>`, read-only after | yes |
| `jatatui.core.symbols.Braille.BRAILLE`, `Pixel.QUADRANTS/SEXTANTS/OCTANTS` | **public static final arrays** | mutable by any caller (do not touch) |
| `jatatui.crossterm.Jatatui` | `static final CrosstermJni JNI`, `static volatile boolean panicHookInstalled`; installs a JVM-global uncaught-exception handler + shutdown hook | handler swap is global state; avoid |
| `tui.crossterm.NativeLoader` | loads the dylib once per class loader; temp dir per JVM | yes |
| Rust side | crossterm's process-global stdout writer and global event reader (`event::read/poll`) | stdout writes are serialized per call by Rust's `Stdout` lock; two threads calling `read/poll` contend on crossterm's internal reader mutex (UNVERIFIED) |

No other static mutable state: widgets, text, styles and geometry are immutable values; `Buffer`, `Cell`, `Terminal`, `Frame`, `ListState`, `TableState`, `ScrollbarState`, `CalendarEventStore` are mutable instances with no synchronization.

### 5.2 Threading rules for tui4s (VERIFIED from source; recommendations are mine)
- **One render thread/fiber at a time** for a given `Terminal`: `Terminal.draw` mutates two buffers, swaps an index, and talks to the backend; `Frame` wraps the live back buffer and must not escape the callback.
- Rendering into a private `Buffer` from several threads is safe now (the Layout cache is synchronized and bounded), as long as states (`ListState`...) are not shared. Contention on the single Layout lock is possible but cheap (cache hit 2.7-4.5 us incl. building the Layout).
- Widget state objects are mutated by `render` (List/Table clamp selection and set offset). Treat them as render outputs: keep an immutable model state, build a fresh jatatui state per render, read `offset()` back (pattern from the tui-scala report still applies).
- Input: keep `poll(timeout)`/`read()` on one dedicated blocking fiber; never call `read()` without a prior `poll` that returned true if you need interruption (a thread inside JNI ignores `Thread.interrupt`, see kyo.md 4.5). Output (`enqueue/execute/flush`) from the render fiber only.
- Logging: anything written to `System.out/err` while the TUI is up corrupts the screen (Rust writes to the same fd 1). This includes the JEP 472 warning (1.3) and `Jatatui.restore()`'s error printing.

### 5.3 Measurements (VERIFIED, rough) — `.work/scratch/jatatui/probe-perf.scala`
Machine: Intel i7-4980HQ (2014, x86_64 macOS, 8 logical CPUs), Temurin 25.0.4.1, `-Xmx512m`, default G1; load average 5-8 from other agents compiling at the same time. 300 warm-up frames then 500 measured frames per row; allocation = `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` of the render thread. Typical frame = rounded full-screen Block + `Layout.horizontal(Percentage(60), Fill(1))` + bordered Paragraph (~1100 chars, wrapped) + bordered List (100 items, highlight). Output (`.work/scratch/jatatui/perf-output.txt`):
```
java.version=25.0.4.1 cpus=8 os.arch=x86_64
A Terminal(TestBackend) 120x40, selection moves                  2657.9 us/frame    1741.2 KiB/frame
B Terminal(no-op) 120x40, identical frames                       2122.0 us/frame    1725.1 KiB/frame
   changed cells in an identical frame: 0
C Terminal(no-op) 120x40, selection moves + items rebuilt        2093.4 us/frame    1753.0 KiB/frame
   changed cells when selection moves 49->50 style: 158
D widgets only (render into the back buffer, no diff/reset)      1227.3 us/frame    1736.1 KiB/frame
0 Terminal.draw with an empty callback (diff+reset baseline)      600.2 us/frame       0.2 KiB/frame
1 Block.bordered full 120x40                                      228.6 us/frame     332.8 KiB/frame
2 Paragraph ~1100 chars wrapped, 70x38                            463.2 us/frame     581.5 KiB/frame
3 Paragraph ~1100 chars NOT wrapped, 70x38                        294.1 us/frame     476.2 KiB/frame
4 List 100 items (prebuilt), 48x38, stateful                      419.3 us/frame     534.2 KiB/frame
E Buffer.diff 120x40 identical buffers                            306.2 us/frame       0.0 KiB/frame
F Buffer.reset 120x40                                             345.5 us/frame       0.0 KiB/frame
G Buffer.setStyle full 120x40 (Style.empty)                       253.1 us/frame     300.0 KiB/frame
H cellAt over all 4800 cells                                       27.3 us/frame       0.0 KiB/frame
I setString 100 ASCII chars                                        12.6 us/frame      12.5 KiB/frame
J Layout.split cache hit (equal Layout rebuilt per call)            2.7 us/frame       0.3 KiB/frame
K Layout.split cache miss (new area every call, 3 constraints)    167.7 us/frame      36.9 KiB/frame
L concurrent Layout.split: errors=0 wrongResults=0 cacheCapacity=500
```
Reading:
- **Typical 120x40 frame: ~2.1 ms and ~1.7 MiB allocated per frame** (no-op backend), ~2.7 ms with `TestBackend`. Unchanged frames still cost the full ~2.1 ms (render + diff + reset), and produce 0 changed cells. At 60 fps that is ~100 MiB/s of short-lived garbage: fine for G1/ZGC, but wasteful; render only when the model changed (or on resize/tick).
- Fixed per-frame overhead of `Terminal.draw` at 120x40 is ~0.6 ms (diff ~0.3 ms + reset ~0.33 ms, both 0 alloc). `Buffer.diff` calls `Wcwidth.width(symbol)` twice per cell and `Cell.equals` each frame: O(cells), ~60 ns/cell on this CPU.
- Allocation comes from styling, not from cell access: `Buffer.setStyle` over 4800 cells = 300 KiB (`Cell.setStyle` builds 2 `Modifier` records + `Optional.ifPresent` lambdas per cell); every Block/Paragraph/List does a full-area `setStyle` first. A full-screen Block = 333 KiB (tui-scala measured 329 KiB for 200x50). `cellAt` (boxed `Optional<Integer>` + `Position` in source) shows **0 bytes**: C2 escape analysis removes them.
- Paragraph re-segments the whole text every frame (BreakIterator + StyledGrapheme per grapheme): ~0.3-0.46 ms and 0.5-0.6 MiB for 1100 chars. The List renders ~0.42 ms / 534 KiB for 100 prebuilt items.
- Rebuilding widgets (new `ListItem`s each frame) adds almost nothing (C vs B: +28 KiB): widget construction is cheap, rendering is the cost.
- Layout: cache hit 2.7-4.5 us including building the `Layout`; cache miss (kasuari solve, 3 constraints) ~170-215 us and 37 KiB.
- Concurrency smoke test: 8 threads x 5000 `Layout.split` with overlapping keys: 0 exceptions, 0 wrong results (VERIFIED).
- Compared with tui-scala 0.0.10 on the same machine (tui-scala.md section 6, 200x50, JDK 21): same order of magnitude per cell; the big differences are correctness (bounded cache, no aliasing in TestBackend), not speed.

## 6. Headless probe (code + output)

VERIFIED: compiled and ran with Scala 3.9.0, scala-cli 1.9.1, Temurin 25.0.4.1 (x86_64 macOS), no TTY, `--server=false` (no bloop daemon). Only `jatatui-core` + `jatatui-widgets` are on the classpath, so the native library is never loaded. Compile+run ~17 s wall.

Run: `source .work/env.sh && scala-cli run --server=false .work/scratch/jatatui/probe.scala`. Output saved in `.work/scratch/jatatui/probe-output.txt`.

What it proves: Block (rounded, centered top title, bottom title) + Paragraph (wrap) + List with `ListState` selection (render mutates offset 0 -> 1, and back to 0 after `select(0)`) + Gauge, rendered through `Terminal.create(TestBackend(40, 12))` and `terminal.draw(f => ...)`, two frames; a Scala lambda used as a `Widget`; `Buffer.withLines` equality; `Buffer.diff` live refs; `Layout.split` returns the same cached array for an equal Layout; out-of-area `setString` throws `IndexOutOfBoundsException`.

Output:
```
java.version=25.0.4.1 scala=3.9.0
|╭─────────── jatatui probe ────────────*|
|│Hello from Scala 3.9 on JDK 25,       │|
|│wrapped by jatatui.                   │|
|│                                      │|
|│   item 1                             │|
|│   item 2                             │|
|│   item 3                             │|
|│   item 4                             │|
|│   item 5                             │|
|│>> item 6                             │|
|│████████████████ 42%                  │|
|╰ q to quit ───────────────────────────╯|
[OK  ] rendered lines equal expected
[OK  ] ListState.offset was mutated by render (0 -> 1)
[OK  ] selected still 6
[OK  ] selected row is REVERSED
[OK  ] 'Hello' is bold yellow
[OK  ] CompletedFrame.count == 0
[OK  ] after select(0): first list row is '>> item 0'
[OK  ] offset reset to 0
[OK  ] Buffer.withLines equality
[OK  ] Buffer.diff returns live Cell refs (update sees later mutation)
[OK  ] Layout.split returns the cached array instance for an equal Layout
  setString outside area -> java.lang.IndexOutOfBoundsException
ALL CHECKS PASSED
```

Code (`.work/scratch/jatatui/probe.scala`):
```scala
//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using option -deprecation -feature -Wunused:imports

// Headless jatatui probe for tui4s (no TTY, no native library).
// Run: source .work/env.sh && scala-cli run --server=false .work/scratch/jatatui/probe.scala

import jatatui.core.backend.TestBackend
import jatatui.core.buffer.Buffer
import jatatui.core.layout.{Constraint, Layout, Rect}
import jatatui.core.style.{Color, Modifier, Style}
import jatatui.core.terminal.{Frame, Terminal}
import jatatui.core.text.{Line, Span, Text}
import jatatui.core.widgets.Widget
import jatatui.widgets.block.{Block, BorderType}
import jatatui.widgets.gauge.Gauge
import jatatui.widgets.list.{ListItem, ListState, List as JList}
import jatatui.widgets.paragraph.{Paragraph, Wrap}

import java.util.Optional
import scala.jdk.CollectionConverters.*

object Probe:
  def lines(buf: Buffer): Vector[String] =
    val a = buf.area()
    Vector.tabulate(a.height()) { y =>
      (0 until a.width()).map(x => buf.cellAt(a.x() + x, a.y() + y).symbol()).mkString
    }

  var failures = 0
  def check(name: String, ok: Boolean): Unit =
    println(s"[${if ok then "OK  " else "FAIL"}] $name")
    if !ok then failures += 1

  def ui(frame: Frame, state: ListState, percent: Int): Unit =
    val outer = Block
      .bordered()
      .withBorderType(BorderType.Rounded)
      .withTitle(Line.from(" jatatui probe ").centered())
      .withTitleBottom(" q to quit ")
    val inner = outer.inner(frame.area())
    frame.renderWidget(outer, frame.area())

    // Java varargs: pass records built with Scala 3 universal apply (no `new`)
    val Array(top, mid, bottom) =
      Layout.vertical(Constraint.Length(3), Constraint.Fill(1), Constraint.Length(1)).areas(inner, 3): @unchecked

    val para = Paragraph
      .of(Text.from(Line.from(Span.styled("Hello", Style.empty().bold().withFg(Color.YELLOW)), Span.raw(" from Scala 3.9 on JDK 25, wrapped by jatatui."))))
      .withWrap(Wrap(true))
    frame.renderWidget(para, top)

    val items = (0 until 8).map(i => ListItem.of(s"item $i")).asJava
    val list = JList
      .of(items)
      .withHighlightSymbol(">> ")
      .withHighlightStyle(Style.empty().withAddModifier(Modifier.REVERSED))
    frame.renderStatefulWidget(list, mid, state)

    val gauge = Gauge.empty().withPercent(percent).withLabel(s"$percent%").withGaugeStyle(Style.empty().withFg(Color.GREEN))
    frame.renderWidget(gauge, bottom)

    // A Scala lambda is a jatatui Widget (SAM conversion of @FunctionalInterface)
    val corner: Widget = (area, buf) => buf.setString(area.right() - 1, area.y(), "*", Style.empty())
    frame.renderWidget(corner, frame.area())

  def main(args: Array[String]): Unit =
    println(s"java.version=${System.getProperty("java.version")} scala=${scala.util.Properties.versionNumberString}")
    val backend = TestBackend(40, 12)
    val terminal = Terminal.create(backend)
    val state = ListState.of(0, Optional.of[Integer](6)) // Optional<Integer> needs an explicit [Integer]

    val completed = terminal.draw(f => ui(f, state, 42))
    val out = lines(backend.buffer())
    out.foreach(l => println(s"|$l|"))

    val expected = Vector(
      "╭─────────── jatatui probe ────────────*",
      "│Hello from Scala 3.9 on JDK 25,       │",
      "│wrapped by jatatui.                   │",
      "│                                      │",
      "│   item 1                             │",
      "│   item 2                             │",
      "│   item 3                             │",
      "│   item 4                             │",
      "│   item 5                             │",
      "│>> item 6                             │",
      "│████████████████ 42%                  │",
      "╰ q to quit ───────────────────────────╯"
    )
    check("rendered lines equal expected", out == expected)
    if out != expected then
      out.zip(expected).zipWithIndex.filter((p, _) => p._1 != p._2).foreach { case ((a, e), i) =>
        println(s"  row $i\n    got: |$a|\n    exp: |$e|")
      }
    check("ListState.offset was mutated by render (0 -> 1)", state.offset() == 1)
    check("selected still 6", state.selected() == Optional.of[Integer](6))
    val sel = backend.buffer().cellAt(1, 9)
    check("selected row is REVERSED", sel.modifier.contains(Modifier.REVERSED))
    check("'Hello' is bold yellow", {
      val c = backend.buffer().cellAt(1, 1); c.fg == Color.YELLOW && c.modifier.contains(Modifier.BOLD)
    })
    check("CompletedFrame.count == 0", completed.count() == 0)

    // Second frame: select item 0 -> offset back to 0; count updates through TestBackend
    state.select(Optional.of[Integer](0))
    terminal.draw(f => ui(f, state, 43))
    val out2 = lines(backend.buffer())
    check("after select(0): first list row is '>> item 0'", out2(4).startsWith("│>> item 0"))
    check("offset reset to 0", state.offset() == 0)

    // Buffer equality helper (content+styles): Buffer.withLines builds an unstyled expected buffer
    val plain = Buffer.empty(Rect(0, 0, 10, 1))
    plain.setString(0, 0, "abc", Style.empty())
    check("Buffer.withLines equality", plain == Buffer.withLines("abc       "))

    // Buffer.diff returns LIVE cell references
    val prev = Buffer.empty(Rect(0, 0, 3, 1))
    val next = Buffer.empty(Rect(0, 0, 3, 1))
    next.setString(0, 0, "x", Style.empty())
    val upd = prev.diff(next).get(0)
    next.cellAt(0, 0).setSymbol("y")
    check("Buffer.diff returns live Cell refs (update sees later mutation)", upd.cell().symbol() == "y")

    // Layout cache returns the same Rect[] instance
    val l = Layout.horizontal(Constraint.Percentage(50), Constraint.Percentage(50))
    val r = Rect(0, 0, 10, 1)
    val a1 = l.split(r)
    val a2 = Layout.horizontal(Constraint.Percentage(50), Constraint.Percentage(50)).split(r)
    check("Layout.split returns the cached array instance for an equal Layout", a1 eq a2)

    // Out-of-bounds write
    val oob = scala.util.Try(Buffer.empty(Rect(0, 0, 2, 1)).setString(0, 5, "x", Style.empty()))
    println(s"  setString outside area -> ${oob.failed.map(_.getClass.getName).getOrElse("no exception")}")

    println(if failures == 0 then "ALL CHECKS PASSED" else s"$failures CHECK(S) FAILED")
    if failures != 0 then sys.exit(1)
```

## 7. jatatui-react / jatatui-components (prior art)

Sources: `jatatui-react-0.31.0-sources.jar` (19 files, 2371 lines), `jatatui-components-0.31.0-sources.jar`, `jatatui-react/DESIGN.md` and README at v0.31.0. The README marks both modules **proof-of-concept, expect sweeping API changes**; `DESIGN.md` is an older sketch (its file map names `Memo.java`/`PureComponent.java`, which are now `Intrinsics` props). VERIFIED by reading source unless marked. tui4s will not depend on these modules.

### 7.1 Component model (what it is)
- **Element tree, immediate mode, no virtual DOM**: `sealed interface Element permits Of, Host, Sized` with `void render(RenderContext ctx, Rect area)`. `Of<P>(Component<P> type, P props, Optional<String> key)` = component application; `Host(Intrinsic)` = leaf that paints into the buffer; `Sized(Constraint, Element)` = per-child layout constraint for the parent `column`/`row`. The tree is rebuilt every render and painted straight into the jatatui `Buffer`; there is no diff of element trees (jatatui's buffer diff is the only diff). Rationale (DESIGN.md): VDOM diffing is "overkill for terminal".
- **Components are functions**: `@FunctionalInterface interface Component<P> { Element apply(P props, RenderContext ctx); }`, plus `Components.component(ctx -> ...)` for prop-less closures. Built-ins (`Components.*`): `text`, `paragraph`, `button`, `box`, `column`, `row`, `stack`, `tabs/tab`, `forEach`, `when`, `ifElse`, `empty`, `widget(Widget)` (escape hatch to any jatatui widget), `portal(child, Rect)`, `provide(Context, value, child)`, sizing `length/fill/min/max/percent/ratio/sized`, memo `memo(deps, body)` / `pureComponent(props, body)`.
- **Hooks** on `RenderContext`, stored in a `HookStore` keyed by `HookKey(Fiber fiber, int index)` (call order matters, like React): `useState(Supplier<T>) -> State<T>` (`get`, `latest`, `set` (no-op if `Objects.equals`), `update(UnaryOperator)`); `useRef`; `useEffect(Runnable, Object... deps)` (VERIFIED in source: the effect runs **synchronously during render**, not after commit, and the public signature gives user code no way to return a cleanup; only internal hooks like `useTimeout` register cleanups in `HookStore.cleanups`); `useTimeout(ms, cb, deps...)`; `useFrame(hz, Consumer<Long> elapsedMs)` (0.31.0: shared tick source at the max requested hz); `useContext(Context<T>)`; `useFocus(Optional<String> id, boolean autoFocus) -> boolean`; imperative `focus(id)` / `blur()`; `area()`.
- **Identity / reconciliation**: `Fiber(parent, key)` where key = child index or explicit string key (`renderChild(String key, ...)`, `Element.Of.withKey`). If the component *type* at a fiber changes between renders, the old subtree is unmounted (state dropped, cleanups run). After each render a **sweep** drops every fiber that was not visited (unmount) and runs its effect cleanups. Keyed children keep state across reorders. Type identity for `component(...)` lambdas = the lambda's class (call site).
- **Events**: handlers are registered *during render* (`onClick`, `onScroll`, `onHover`, `onDrag`, `onDragEnd`, `onKey(Object keyMatcher, ...)`, `onGlobalKey(...)`; key matcher is a `KeyCode` or a `Predicate<KeyCode>` such as `ANY_KEY`/`ANY_CHAR`, typed as `Object`). Per-frame `EventRegistry` records each fiber's bounds in paint order with a layer `z` (portals get higher z) and sequence number; mouse hit-test picks the max `(z, seq)` bound and **bubbles** to ancestors; keys go from the **focused fiber up to the root**, then global handlers; `stopPropagation()` on jatatui-react's own `KeyEvent`/`MouseEvent` wrappers.
- **Focus**: Ink-style. Focusables register in render order; Tab/Shift-Tab (and BackTab) cycle; `autoFocus` claims focus on mount; explicit ids allow `focus(id)`.
- **Portals**: `portal(child, area)` queues the child; after the main pass portals are drained (recursively) on higher paint layers, so modals/dropdowns paint on top and win hit-tests. Pair with `Clear` for opaque overlays.
- **Re-render strategy**: strictly **event-driven dirty flag**. `State.set` (and `requestRerender()`, which is thread-safe) mark dirty; the loop redraws only when dirty. `useTimeout`/`useFrame` use a shared daemon `ScheduledExecutorService` that only calls `requestRerender`; callbacks run on the loop thread during the next render.
- **Runners**: `ReactApp.run(Element)` = `Jatatui.runIo` + `EnableMouseCapture` + loop { if dirty: `terminal.draw(frame -> renderer.render(frame, root))`; `poll(100 ms, or 8 ms while useFrame active)`; `read`; `handle` } with fixed policy: Ctrl-C quits, unhandled Esc quits, Tab/Shift-Tab cycle focus, only `KeyEventKind.Press` keys are dispatched, Resize -> rerender, ScrollLeft/Right ignored. `Renderer` = the embeddable engine (`render(frame, root)`, `dispatchKey`, `dispatchMouse`, `tab`, `shiftTab`, `takeDirty`, `requestRerender`, `frameActive`); `TestHarness(width, height)` = Renderer + TestBackend + Terminal for tests. Threading: render and dispatch must run on one thread; `requestRerender` is thread-safe.
- **jatatui-components** (built on react): `textInput`/`titledTextInput` (uses the UTF-16 `TextInput` widget), `dropdown`, `picker` (fuzzy search + ranked list, `FuzzyMatch`), `selectableList`, `button`, `backButton`, `screenFrame`, `link`, `confirmDialog`, `modal`, `scrollable`, `list`, `table`, `gauge`, `lineGauge`, `barChart`, `sparkline`, `scrollbar`, context providers `toastsProvider`/`useToasts`, `formProvider`/`useForm`/`useField`, `router`/`useRouter`, `themeProvider`/`useTheme` (theme is not auto-consumed).

### 7.2 Good ideas to take into a Scala/Kyo design
1. Immediate-mode element tree over the buffer, no VDOM: cheap and simple; jatatui's own buffer diff already minimizes terminal output.
2. Fiber identity = (parent, index | key) with **type-change unmount + sweep of unvisited fibers**: a clear, testable rule for state lifetime (also the right rule for Kyo `Scope`-backed resources per component: close the scope when the fiber is swept).
3. Handlers registered during render, bound to **areas recorded in paint order with z-layers**: correct hit-testing for overlapping widgets and portals. Keys routed via the focused chain with explicit stop-propagation; global keys last.
4. Event-driven redraw (dirty flag) + an opt-in frame clock (`useFrame`) instead of a fixed tick. Matches the perf finding in 5.3 (an unchanged frame still costs ~2 ms).
5. `Renderer` separated from the opinionated `ReactApp` loop, and a `TestHarness` that drives Renderer + TestBackend: tui4s should ship the same split (pure render/dispatch core + effectful runner per effect system + headless test kit).
6. Portals + `Clear` for overlays; context providers for theme/router/toasts; per-child layout constraints as a wrapper (`Sized`).

### 7.3 What is bad / does not fit Scala + Kyo
1. Hooks are call-order-indexed mutable slots (`HashMap<HookKey, Object>` + unchecked casts): the usual hook rules (no hooks in conditionals/loops) are unenforced and untyped. In Scala this can be typed (e.g. state declared as typed fields of a component class, or Kyo `Var`/`SignalRef` per component instance), avoiding positional hooks.
2. Side effects everywhere: `State.set` mutates shared maps and flips a dirty flag; handlers are `Runnable`/`Consumer`; effects run as `Runnable`. Nothing is suspended, cancellable or typed. A Kyo design wants handlers that return `Unit < (Sync & Abort[E] & Var[S] ...)` and runs them in a fiber with interruption.
3. Global/opaque threading: shared daemon `ScheduledExecutorService` threads for timers/frames, `requestRerender` from any thread; in Kyo these should be fibers (`Clock`, `Async.sleep`, `Signal`) in the app's `Scope`.
4. Memoization is weak: bodies always run (auto-memo was removed because handler registration is a side effect of running the body); `memo(deps, body)` uses `Object[]` deps. A Scala design can make props case classes with `CanEqual`/structural equality and memoize safely if handlers are returned as data (part of the element tree) instead of registered as side effects.
5. Weak typing at the edges: `onKey(Object keyMatcher, ...)`, `forEach` with fixed row height, `Optional<String>` ids, string ids for focus.
6. Opinionated runner (Esc quits, Ctrl-C quits, mouse capture always on, `Jatatui.runIo` with global uncaught handler). tui4s should make these explicit options.
7. `useEffect` runs synchronously during render and cannot return a cleanup (only internal hooks register cleanups); handler registration is also a render side effect, which is why auto-memoisation had to be removed.
8. PoC status + Java-only API churn: the README warns of breaking changes per minor version; depending on it would couple tui4s to an unstable API. Inspiration only (as decided).

## 8. Scala 3.9 interop gotchas

Probes (all compiled with Scala 3.9.0 + scala-cli 1.9.1 on JDK 25, logs next to them): `.work/scratch/jatatui/interop/interop.scala` (compiles with 3 intended warnings, runs), `interop/neg.scala` + `interop/neg2/neg2.scala` (intended errors), `interop/nulls/nulls.scala` (`-Yexplicit-nulls`, compiles and runs), `interop/nulls2/nulls2.scala` (proves the flag is active).

### 8.1 Nullability and `-Yexplicit-nulls` — VERIFIED: painless
- With `//> using options -Yexplicit-nulls`, a realistic wrapper snippet (Scala `Backend` implementation, `Terminal.create`, `draw` lambda, `Layout...areas` destructuring, `Paragraph`/`List`/`ListState`, reading `Cell` public fields, `Text.lines.get(0).spans.get(0).content`, `Optional` <-> `Option`, `Event` matching) **compiled with zero `.nn` calls and zero errors** (`nulls/nulls.scala`, ran and printed the expected rows). Reason: Scala 3.9 types Java members with **flexible types** (`T?`), which conform to both `T` and `T | Null`.
- The flag was really active: `val proof: String = null` and `(x: String | Null).length` were rejected in `nulls2.scala` with the explicit-nulls messages.
- Cost: flexible types give no safety at the boundary (`val s: String = System.getenv("X")` compiles and can be null). For jatatui this is acceptable: its public API never returns `null` (only the private `Color.parseHexColor` does; 67 public signatures use `Optional` instead), and native failures surface as exceptions, not nulls. So: **enable `-Yexplicit-nulls` in tui4s** if the team wants it; the jatatui boundary does not make it painful. Overriding Java methods with non-null parameter types (`def draw(content: java.lang.Iterable[BufferUpdate])`) is accepted.

### 8.2 Optional
- jatatui uses `java.util.Optional` everywhere (`Style.fg/bg`, `Line.alignment`, `Block` title position, `ListState.selected: Optional<Integer>`, `Paragraph.block`, `Table.header/footer`...). Convert with `scala.jdk.OptionConverters` (`.toScala`, `.toJava`).
- `Optional<Integer>` trap: `ListState.of(0, Optional.of(3))` compiles (expected type drives `Optional.of[Integer]` + `int2Integer`), but `val o = Optional.of(3); ListState.of(0, o)` fails: `Found: Optional[Int], Required: Optional[Integer]` (neg2). Use `Optional.of[Integer](3)`, and read with `st.selected.toScala.map(_.intValue)`. `val d: Int = st.selected.get` compiles (unboxing).

### 8.3 Java varargs and overloads — VERIFIED
- `Layout.vertical(Constraint.Length(1), Constraint.Fill(1))` and `Layout.vertical(cs*)` with `cs: Seq[Constraint]` both work; `Layout.vertical(cs.asJava)` picks the `List<Constraint>` overload. `Buffer.withLines(seq*)`, `jni.execute(Command.Hide(), Command.MoveTo(1, 2))` fine.
- `Paragraph.of(String|Line|Text|java.util.List[Line])` overloads resolve; results are structurally equal.
- Scala lambdas SAM-convert to `Widget`, `StatefulWidget`, `Consumer<Frame>` (`terminal.draw(f => ...)`), `Consumer<Buffer>` (`insertBefore`), `DateStyler`, `Consumer<Context>` (Canvas).
- Checked `IOException` on `Terminal`/`Backend` methods: invisible to Scala (no `throws` needed when implementing `Backend`), so the wrapper must remember to catch/translate it (`Abort[TerminalError]`).

### 8.4 Records — VERIFIED
- Scala 3 universal apply works on Java records/classes: `Constraint.Length(3)`, `Rect(0, 0, 40, 12)`, `KeyCode.Char('q')`, `Event.Resize(120, 40)`, `TestBackend(40, 12)` (no `new`).
- Accessors can be called without parentheses (`r.columns`, `k.keyEvent.code`, `rect.width`) — Java methods allow omitting `()` in Scala 3.
- **No extractor patterns**: `case Event.Resize(c, r) =>` fails with `E127 ... cannot be used as an extractor in a pattern because it lacks an unapply` (neg). Use type patterns (`case r: Event.Resize => r.columns`) or tui4s's own Scala ADT.
- Record `equals` is structural (`KeyCode.Char('q') == KeyCode.Char('q')`, `Color.Red() == Color.RED`: true).
- Record accessor vs builder clash: `Style.fg()` is the accessor (`Optional<Color>`), so `Style.empty().fg(Color.RED)` does not compile (Style is not `Stylize`); `Span/Line/Text/widgets.fg(Color)` do. Use `Style.withFg`.

### 8.5 Sealed interfaces and enums: exhaustivity — VERIFIED
- Scala 3.9 **does exhaustivity-check Java sealed interfaces** (reads `PermittedSubclasses`): an incomplete match on `tui.crossterm.Event` warned `E029 match may not be exhaustive. It would fail on pattern case: _: Event.Resize, _: Event.Paste, _: Event.FocusLost, _: Event.FocusGained`; same for nested `MouseEventKind`. A complete type-pattern match over `Event` compiled without a warning.
- Java enums are checked too (`KeyEventKind` missing `Release` -> warning).
- So a wrapper can match jatatui/crossterm ADTs directly and get warnings when jatatui adds variants (as 0.31 did with `ScrollLeft/Right`). Make these warnings errors in tui4s (`-Werror` or `-Wconf:id=E029:e`).

### 8.6 Names and keywords — VERIFIED
- `Viewport.inline(5)` compiles (soft keyword as a member name is fine). No other jatatui API member is a Scala keyword (grep over all public signatures).
- Clashes: `jatatui.widgets.list.List` -> `import jatatui.widgets.list.{List as JList}`; `jatatui.core.text.Line` vs `jatatui.widgets.canvas.Line` vs `jatatui.core.symbols.Line`; `canvas.Map`; `symbols.Block` vs `widgets.block.Block`; `tui.crossterm.Color/Event/Command` vs `jatatui.core.style.Color`. Rename on import in the wrapper.
- Interface constants are accessed as companion members: `Color.RED`, `Viewport.FULLSCREEN`, `Spacing.DEFAULT`.
- jatatui has its own `jatatui.core.layout.solver.Either` (used by `Color.fromString`, `Rect.tryLayout`, `Layout.tryAreas`) — not `scala.util.Either`; convert at the boundary.

### 8.7 Scala 3.9 call-form cheat sheet (each line compiled in probe.scala, probe-widgets.scala, interop.scala or nulls.scala)
```scala
import jatatui.widgets.list.{ListItem, ListState, List as JList}
val term   = Terminal.create(TestBackend(40, 12))                       // Terminal[TestBackend]
val inline = Terminal.withOptions(be, TerminalOptions(Viewport.inline(2)))
term.draw(f => f.renderWidget(Paragraph.of("hi"), f.area))              // Consumer[Frame] from a lambda; returns CompletedFrame
val Array(top, rest) = Layout.vertical(Constraint.Length(3), Constraint.Fill(1)).areas(area, 2): @unchecked
val block  = Block.bordered().withBorderType(BorderType.Rounded).withTitle(Line.from(" t ").centered()).withTitleBottom("b")
val style  = Style.empty().withFg(Color.YELLOW).bold()                  // NOT Style.empty().fg(...)
val span   = Span.styled("x", style); val span2 = Span.raw("y").fg(Color.RED)   // Stylize on Span
val list   = JList.of(items.asJava).withHighlightSymbol(">> ").withHighlightStyle(Style.empty().withAddModifier(Modifier.REVERSED))
val st     = ListState.of(0, Optional.of[Integer](6)); f.renderStatefulWidget(list, area, st); st.offset   // offset written by render
val sel: Option[Int] = st.selected.toScala.map(_.intValue)
val table  = Table.of(rows.asJava, List[Constraint](Constraint.Length(3), Constraint.Fill(1)).asJava).withHeader(Row.ofStrings("#", "name"))
table.render(area, buf, TableState().withSelected(1))
val w: Widget = (area, buf) => buf.setString(area.x, area.y, "*", Style.empty())   // custom widget = lambda
Canvas.empty().withXBounds(Array(0.0, 20.0)).withYBounds(Array(0.0, 10.0)).withPaintFn((ctx: Context) => ctx.draw(Circle(10, 5, 4, Color.YELLOW)))
val styler: DateStyler = d => Style.empty(); Calendar.of(LocalDate.of(2026, 9, 30), styler)   // DateStyler lambda
e match { case k: Event.Key => k.keyEvent.code match { case c: KeyCode.Char => c.c; case _ => ' ' }; case _ => ' ' }
(mods.bits & KeyModifiers.CONTROL) != 0
val cmds: java.util.List[Command] = Seq(Command.Hide(), Command.MoveTo(1, 2)).asJava
// jni.execute(Command.EnterAlternateScreen(), Command.Hide())  -- same varargs shape, NOT compiled/run here (needs a TTY)
```

## 9. Risks and design implications

### 9.1 Risks
- **Single-maintainer, young 0.x library** (first release 2026-05, second 2026-08). Core APIs may still change between minors; pin exact versions and wrap everything behind tui4s types so upgrades touch one module. (Assessment, UNVERIFIED as a prediction.)
- **Native access will become an error** in a future JDK (`--illegal-native-access=deny` already breaks loading). Every tui4s app needs the flag/manifest entry; forgetting it today only prints a warning that can corrupt the first frame.
- **Native coverage**: no Windows arm64, no musl/Alpine; glibc >= 2.34 (from the tui-scala report, same build pipeline, UNVERIFIED for 0.31 binaries); one leaked `$TMPDIR/jni-*` dir per JVM; override env var still named `TUI_SCALA_PLATFORM`.
- **Input fidelity**: `KeyCode.Char(char)` truncates non-BMP code points (Rust `c as u16 // todo`); `TextInput` widget is UTF-16-index based (not grapheme/width aware).
- **Blocking JNI**: `read()` cannot be interrupted; must be guarded by `poll(timeout)` in a dedicated fiber (kyo.md 4.5).
- **Shared stdout**: Rust writes to fd 1; any JVM stdout/stderr output (loggers, warnings, `Jatatui.restore` error prints) corrupts the screen.
- **Mutable leaks through the API**: `Buffer` public fields, `Cell` public fields, `BufferUpdate.cell` live refs, `CompletedFrame.buffer` reset by the next draw, shared cached `Rect[]`, public static symbol arrays, mutable widget states. A pure Scala API must never expose these directly.
- **Rust `panic!`** on unknown enum names in command decoding would abort the JVM (only on Java/native mismatch).
- **Allocation-heavy rendering** (~1.7 MiB per 120x40 frame) if tui4s redraws on a fixed tick.
- **Classpath clash** with `com.olvind.tui:crossterm` (same `tui.crossterm` package); add a build-time guard (e.g. sbt `excludeDependencies` or an eviction check) in tui4s.

### 9.2 Design implications for tui4s
1. Module split: a **pure render layer** that depends only on `jatatui-core` + `jatatui-widgets` (no native lib; fully testable headless with `TestBackend`) and a **terminal runtime** that adds `jatatui-crossterm` + `crossterm`. tui4s-zio / tui4s-cats only add effect bridges.
2. Terminal lifecycle as a Kyo `Scope` resource (ZIO `Scope`, CE `Resource` in the bridges): force-load `CrosstermJni` first (JEP 472 warning before raw mode; check `Module.isNativeAccessEnabled` and fail fast with a clear message, UNVERIFIED API call), then `enableRawMode`, `EnterAlternateScreen`, `Hide`, optional mouse/paste/focus/keyboard-enhancement flags; release in reverse order. Do not call `Jatatui.init/runIo`; optionally install our own idempotent shutdown hook.
3. Concurrency model: one render fiber owns `Terminal` and all jatatui mutable objects; one input fiber loops `poll(10-50 ms)` + `read()` and publishes typed events to a Kyo `Channel`/`Signal`; render on model change (dirty), on resize, and on an opt-in frame clock.
4. Own immutable Scala model (case classes / enums with `CanEqual`) for widgets, text and styles, converted to jatatui objects at render time; jatatui widget values are cheap to build (measured +28 KiB/frame for rebuilding 100 list items), so no caching of jatatui objects is needed. Widget states: keep immutable `ListState(offset, selected)` in the model, create a fresh jatatui state per render, read `offset` back.
5. Own Scala event ADT (`enum Key`, `enum MouseKind`, ...) converted from `tui.crossterm.Event` with exhaustive type-pattern matches; turn E029 exhaustivity warnings into errors so new upstream variants are caught at compile time.
6. Error model: map `IOException` (Terminal/Backend) and `RuntimeException("IO error: ...")` (CrosstermJni) to a typed `TerminalError` via `Abort`; smart constructors for throwing preconditions (`Gauge` percent/ratio, `Table` percentage widths, `Layout.areas` count).
7. Layout: call `Layout` directly (cache is safe now) but copy results (`IArray.unsafeFromArray(arr.clone())` or a Scala collection) before exposing them.
8. Test kit: ship a headless `TestTerminal` (jatatui `TestBackend`) + `bufferView`/`assertBuffer` helpers (diff-based, like upstream `BufferAssertions`) + a fake event source. CI never touches `CrosstermJni`.
9. Build settings for users and examples: `fork := true`, `run / connectInput := true`, `javaOptions += "--enable-native-access=ALL-UNNAMED"` (sbt settings UNVERIFIED here; the flag itself VERIFIED); scala-cli `//> using javaOpt --enable-native-access=ALL-UNNAMED`; `-Yexplicit-nulls` is affordable.
10. Text input and any key-to-text handling must be grapheme-aware in tui4s itself (do not wrap `TextInput`); handle emoji via `Event.Paste` (bracketed paste) where possible.
11. Declarative layer (design panel input): take jatatui-react's fiber identity + sweep, paint-order hit-testing with portal layers, focus chain, Renderer/Runner/TestHarness split; replace positional hooks and Runnable effects with typed, effectful handlers and Kyo `Scope`s per component.

## Files produced
- Report: `/Users/yuri4n/Developer/projects/tui4s/.work/reports/jatatui.md` (this file).
- Probes (all in `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/jatatui/`): `probe.scala` (+ `probe-output.txt`), `probe-widgets.scala` (+ `probe-widgets-output.txt`), `probe-perf.scala` (+ `perf-output.txt`), `jep472/LoadProbe.java`, `interop/{interop.scala, neg.scala, neg2/neg2.scala, nulls/nulls.scala, nulls2/nulls2.scala}` with `*.log`.
- Sources: `src/<module>/` (unpacked -sources.jars of all six modules), `cargo/` (Rust JNI sources at v0.31.0), `repo-readme.md`, `repo-bleep.yaml`, `repo-jatatui-react_DESIGN.md`, `repo/BufferAssertions.java`, `repo-tree.txt`, POMs and jars.
- All scala-cli runs used `--server=false` (no bloop daemon was started).
