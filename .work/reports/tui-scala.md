# tui-scala research report (for tui4s)

Date: 2026-09-30. Author: research subagent.
Legend: VERIFIED = seen in source / Maven metadata / compiled and ran. UNVERIFIED = from memory or a secondary source.

## TL;DR (read this first)
1. Latest `com.olvind.tui` artifacts: **0.0.10** (2026-03-26) for `tui_3`, `tui_2.13`, `crossterm`, `cassowary_3`. Needs Scala >= 3.7.4 (pom), JDK >= 17 (crossterm class files are Java 17). VERIFIED.
2. **The upstream repo was renamed to `oyvindberg/jatatui`**, a Java port of ratatui 0.30, published as `com.olvind.jatatui:*:0.31.0` (2026-08-22). Release notes call it "the successor to tui-scala". A user decision is needed on which library tui4s wraps. VERIFIED.
3. `crossterm-0.0.10.jar` bundles JNI libs for macOS arm64/x86_64, Linux x86_64/arm64 (glibc >= 2.34), Windows x86_64. Loaded by extracting to a temp dir; no GraalVM, no `java.library.path` needed. Override platform with env `TUI_SCALA_PLATFORM`. VERIFIED.
4. Local `tui/src/scala/tui/**` and `src/main/scala/tui4s/internal/**` are **byte-identical to the published tui_3 0.0.7 sources**. Deleting them loses no user work. The `tui4s/*F.scala`, `interfaces.scala`, `widgets.scala`, `elements/` files ARE user work. VERIFIED.
5. Headless rendering works without a TTY (probe prints the Block+Paragraph buffer). No TestBackend is published; upstream's unpublished one has a cell-aliasing bug. VERIFIED.
6. Hazards: global unbounded unsynchronized `Layout.LAYOUT_CACHE` (leaks one entry per frame for per-frame layouts and for EVERY `TableWidget` render; lost updates under concurrency); `Buffer.diff` returns live cell refs; widgets mutate `State.offset`; `Borders.remove` bug; heavy allocation (e.g. 329 KiB/frame just for a full-screen Block). VERIFIED by probe.

## 1. Versions, maintenance, native libs

### 1.1 Published artifacts (VERIFIED, Maven Central metadata, fetched 2026-09-30)
Group `com.olvind.tui` has five artifact dirs: `tui_3`, `tui_2.13`, `crossterm` (plain Java, no Scala suffix), `cassowary_3`, `cassowary_2.13`.
- Latest for all: **0.0.10** (published 2026-03-26). Earlier: 0.0.1..0.0.7 (2022-12 .. 2023-05), then 0.0.8 (2026-01-27), 0.0.9 (2026-02-05), 0.0.10 (2026-03-26).
- `tui_3-0.0.10.pom` deps: `cassowary_3:0.0.10`, `crossterm:0.0.10`, `scala-library:2.13.10`, `scala3-library_3:3.7.4`. So consumers need Scala **>= 3.7.x** on the 3 line (TASTy from 3.7.4). Cross-built for Scala 2.13 too.
- `crossterm-0.0.10.pom`: no dependencies. Manifest says `Created-By: Bleep/0.0.13` (built with bleep, not sbt).
- Class file versions: `tui/Buffer.class` major 52 (Java 8 bytecode); `tui/crossterm/*.class` major **61 (Java 17)** because it uses records and sealed interfaces. So runtime JDK **>= 17** is required. JDK 21 is fine.

### 1.2 IMPORTANT: tui-scala is superseded by "jatatui" (VERIFIED, GitHub API + Maven Central)
- GitHub `oyvindberg/tui-scala` now redirects (HTTP 301, repo id 574261934) to **`oyvindberg/jatatui`**, description "A Java port of ratatui - build rich terminal UIs from Java". Not archived. 288 stars, last push 2026-08-22.
- Release `v0.30.0` (2026-05-17) notes say: jatatui is "the successor to `tui-scala` (which ported the much older 2022-era tui-rs). Same heritage, fresh codebase, current upstream." It is a port of **ratatui v0.30.0**. JVM target is **Java 21**.
- New coordinates: group **`com.olvind.jatatui`**, artifacts `jatatui-core`, `jatatui-widgets`, `jatatui-crossterm`, `crossterm`, `jatatui-react`, `jatatui-components`. Versions 0.30.0 (2026-05-17) and **0.31.0 (2026-08-22)**.
- jatatui widgets (per release notes, UNVERIFIED against source yet): Block, Paragraph, List, Table, Tabs, BarChart, Chart, Calendar, Canvas, Gauge, Sparkline, Scrollbar, Clear, TextInput, plus Text/Line/Span (ratatui naming, not Spans).
- Maintenance verdict: the Scala artifacts `com.olvind.tui:tui_3` got maintenance releases in 2026 (0.0.8-0.0.10) but active development moved to the Java library jatatui. tui-scala 0.0.10 is a tui-rs (2022) API: `Spans`, no `Line`, no Scrollbar, no Calendar.
- DECISION INPUT for the orchestrator: a new Scala 3 wrapper started in 2026-09 should seriously consider wrapping **jatatui** (Java API, ratatui 0.30 semantics, maintained) instead of `com.olvind.tui:tui_3` 0.0.10. The user said "wrapper of the scala terminal library"; the upstream author now treats tui-scala as legacy. This needs a user decision. The rest of this report covers tui-scala 0.0.10 as asked.

### 1.3 Native libraries in `crossterm-0.0.10.jar` (VERIFIED, unzip -l + file(1))
Jar size 1.6 MB. It bundles 5 natives at the jar root:
- `libnative-arm64-darwin-crossterm.dylib` (Mach-O arm64, minos 11.0)
- `libnative-x86_64-darwin-crossterm.dylib` (Mach-O x86_64)
- `libnative-x86_64-linux-crossterm.so` (ELF x86-64)
- `libnative-arm64-linux-crossterm.so` (ELF aarch64)
- `native-x86_64-windows-crossterm.dll` (PE32+)
- No Windows arm64, no musl/Alpine build.
- Built from Rust crates `crossterm-0.25.0` and `jni-0.20.0` (strings in the dylib). Crossterm 0.25 is old (2022).
- Linux glibc: x86_64 `.so` has hard (non-weak) refs up to `GLIBC_2.34` (pthread_key_create etc.) and **weak** refs to `GLIBC_2.39` (pidfd_spawnp, pidfd_getpid) (VERIFIED with objdump -T). So glibc >= 2.34 is required; whether older loaders accept the weak 2.39 version need is UNVERIFIED. arm64 `.so` max ref is GLIBC_2.34.
- `META-INF/native-image/org.scala-lang/scala-lang/jni-config.json` is present (GraalVM native-image JNI config). GraalVM is NOT required on a normal JVM.

### 1.4 How the native library is loaded (VERIFIED, `tui/crossterm/NativeLoader.java`)
- `CrosstermJni` has a `static {}` initializer that calls `NativeLoader.load("crossterm")`. The first `new CrosstermJni()` triggers it.
- `load` first tries `System.loadLibrary("crossterm")` (i.e. `java.library.path`). On `UnsatisfiedLinkError` it calls `loadPackaged`: resource `/` + `System.mapLibraryName("native-<platform>-crossterm")`, copies it to `Files.createTempDirectory("jni-")` and calls `System.load(path)`.
- Platform detection from `os.arch` + `os.name`; override with env var **`TUI_SCALA_PLATFORM`** (`x86_64-windows`, `x86_64-linux`, `x86_64-darwin`, `arm64-darwin`, `arm64-linux`).
- Hazards: (a) a temp dir `jni-*` is created per JVM start and never deleted (no deleteOnExit). (b) If the classpath has a non-jar layout the resource lookup still works. (c) Loading `CrosstermJni` in a JVM with no TTY is fine for class-load, but `enableRawMode()` fails with no TTY (see section 4).
- No `--enable-native-access` flag is needed on JDK 21 (classic JNI; JDK 24+ prints warnings for JNI unless `--enable-native-access=ALL-UNNAMED`, UNVERIFIED for this lib).

### 1.5 jatatui 0.31.0 quick facts (VERIFIED from Maven Central jars + javap, 2026-09-30)
- `com.olvind.jatatui:jatatui-core:0.31.0` (193 KB), `jatatui-widgets` (219 KB, depends on core), `jatatui-crossterm` (15 KB, depends on `crossterm` + core), `crossterm` (1.6 MB, 5 natives: same platform set as tui-scala, no Windows arm64).
- Class files major 65 = **Java 21 required**. Plain Java, so usable from Scala 3 without cross-version issues.
- Native crate is **crossterm-0.29.0** (vs 0.25.0 in tui-scala), jni-0.20.0.
- **Package clash**: jatatui's `crossterm` artifact uses the same Java package `tui.crossterm` and the same `CrosstermJni` method signatures as `com.olvind.tui:crossterm`. The two artifacts have different coordinates, so dependency eviction will not remove one: never put both on one classpath.
- jatatui-core **publishes a `jatatui.core.backend.TestBackend`** (tui-scala does not).
- jatatui widget packages: barchart, block, calendar, canvas, chart, gauge, input, list, paragraph, reflow, scrollbar, sparkline, table, tabs.
- `jatatui.core.buffer.Cell` is also mutable (public fields `fg`, `bg`, `underlineColor`, `modifier`, `skip`; `setX` methods; `copy()`), so the mutability advice in section 7 applies to both libraries. Whether jatatui has the same Layout-cache / allocation issues is UNVERIFIED (not investigated).

### 1.6 sbt fork / JDK 21 notes
- UNVERIFIED (memory + general knowledge): run TUI apps with `fork := true`, `run / connectInput := true`, and `outputStrategy := Some(StdoutOutput)` in sbt, because a non-forked `sbt run` shares sbt's own terminal handling (sbt 1.4+ uses JLine and its own thin client), which fights raw mode. The simplest reliable way is to launch from a real terminal via `sbt stage`/`scala-cli run` or `java -cp`.
- JDK 21: works (tui4s's own env uses JDK 21; see section 4 probe, which ran under JDK 21).

## 2. Public API a wrapper needs (tui_3 0.0.10 + crossterm 0.0.10)

All of section 2 is VERIFIED by reading `tui_3-0.0.10-sources.jar` and `crossterm-0.0.10-sources.jar` (unzipped in `.work/scratch/tui-src/` and `.work/scratch/crossterm-src/`). Package `tui` (Scala), package `tui.crossterm` (Java 17 records/sealed interfaces).

### 2.1 Buffer / Cell / Grapheme
```scala
case class Buffer(var area: Rect, var content: mutable.ArraySeq[Cell]) {
  def get(x: Int, y: Int): Cell                 // returns the LIVE mutable cell
  def set(x: Int, y: Int, cell: Cell): Unit
  def indexOf(x: Int, y: Int): Int              // global coords; throws RuntimeException (sys.error) when out of area
  def posOf(i: Int): (Int, Int)
  def setString(x: Int, y: Int, string: String, style: Style): (Int, Int)            // = setStringn(.., Int.MaxValue, ..)
  def setStringn(x: Int, y: Int, string: String, width: Int, style: Style): (Int, Int) // returns (xAfter, y)
  def setSpans(x: Int, y: Int, spans: Spans, width: Int): (Int, Int)
  def setSpan(x: Int, y: Int, span: Span, width: Int): (Int, Int)
  def setStyle(area: Rect, style: Style): Unit
  def resize(area: Rect): Unit                  // reallocates content
  def reset(): Unit                             // resets every cell in place
  def merge(other: Buffer): Unit                // union area, clones cells
  def diff(other: Buffer): Array[(Int, Int, Cell)] // (x, y) are BUFFER-LOCAL (i % width, i / width), NOT offset by area.x/y; Cell refs point INTO `other`
}
object Buffer { def empty(area: Rect): Buffer; def filled(area: Rect, cell: Cell): Buffer; def withLines(lines: String*): Buffer }

case class Cell(var symbol: Grapheme, var fg: Color, var bg: Color, var modifier: Modifier) {
  override def clone(): Cell
  def setSymbol(symbol: String): this.type; def setSymbol(symbol: Grapheme): this.type; def setChar(ch: Char): this.type
  def setFg(color: Color): this.type; def setBg(color: Color): this.type
  def setStyle(style: Style): this.type   // fg/bg if Some, modifier = modifier.insert(add).remove(sub)
  def style: Style; def reset(): Unit
}
object Cell { def default: Cell }      // " ", Reset, Reset, EMPTY (new instance per call)
case class Grapheme(str: String) { lazy val width: Int }   // width via internal.Wcwidth over code points
object Grapheme { val Empty: Grapheme = Grapheme(" ") }
```
Note: there is no `Buffer.cell(x,y): Option`, no bounds-safe get. `withLines` is the test helper (like ratatui `Buffer::with_lines`).

### 2.2 Geometry and layout
```scala
case class Rect(x: Int, y: Int, width: Int, height: Int) {
  def area: Int; def left: Int; def right: Int; def top: Int; def bottom: Int
  def inner(margin: Margin): Rect; def union(other: Rect): Rect; def intersection(other: Rect): Rect; def intersects(other: Rect): Boolean
}
object Rect { val default: Rect }   // (0,0,0,0)
case class Margin(vertical: Int, horizontal: Int)   // NOTE order: vertical first
object Margin { def apply(value: Int): Margin }
sealed trait Direction; object Direction { case object Horizontal; case object Vertical }
sealed trait Constraint { def apply(length: Int): Int }
object Constraint { case class Percentage(p: Int) /* require 0..100 */; case class Ratio(num: Int, den: Int); case class Length(l: Int); case class Max(m: Int); case class Min(m: Int) }
case class Layout(direction: Direction = Direction.Vertical, margin: Margin = Margin(0, 0),
                  constraints: Array[Constraint] = Array.empty, expandToFill: Boolean = true) {
  def split(area: Rect): Array[Rect]   // uses Layout.LAYOUT_CACHE
}
object Layout { val LAYOUT_CACHE: mutable.HashMap[(Rect, Layout), Array[Rect]]; def split(area: Rect, layout: Layout): Array[Rect] }
case class Point(x: Double, y: Double); object Point { val Zero }
sealed trait Corner; object Corner { TopLeft, TopRight, BottomRight, BottomLeft }
case class Viewport(var area: Rect, resizeBehavior: ResizeBehavior); object Viewport { def fixed(area: Rect): Viewport }
sealed trait ResizeBehavior; object ResizeBehavior { Fixed; Auto }
case class TerminalOptions(viewport: Viewport)
```
Layout uses the Cassowary solver from `com.olvind.tui:cassowary_3` (package `tui.cassowary`).

### 2.3 Style / Color / Modifier / Borders / Alignment
```scala
case class Style(fg: Option[Color] = None, bg: Option[Color] = None, addModifier: Modifier = Modifier.EMPTY, subModifier: Modifier = Modifier.EMPTY) {
  def fg(color: Color): Style; def bg(color: Color): Style
  def addModifier(m: Modifier): Style; def removeModifier(m: Modifier): Style; def patch(other: Style): Style
}
object Style { val DEFAULT: Style; val RESET: Style }
sealed trait Color
object Color { Reset, Black, Red, Green, Yellow, Blue, Magenta, Cyan, Gray, DarkGray, LightRed, LightGreen, LightYellow, LightBlue, LightMagenta, LightCyan, White (case objects)
               case class Rgb(r: Int, g: Int, b: Int); case class Indexed(index: Int) }
case class Modifier(bits: Int) { def contains(o: Modifier): Boolean; def insert(o): Modifier; def remove(o): Modifier; def |(o); def -(o) }
object Modifier { BOLD, DIM, ITALIC, UNDERLINED, SLOW_BLINK, RAPID_BLINK, REVERSED, HIDDEN, CROSSED_OUT, EMPTY, ALL }
case class Borders(bits: Int) { def contains(o): Boolean; def intersects(o): Boolean; def insert(o); def remove(o); def |(o); def -(o) }
object Borders { NONE (= 1<<0, not 0!), TOP, RIGHT, BOTTOM, LEFT, EMPTY (= 0), ALL }
sealed trait Alignment; object Alignment { Left; Center; Right }
```
Upstream bug (VERIFIED by reading, probe in section 4): `Borders.remove(other) = copy(bits = bits & Integer.reverse(other.bits))` - it uses bit REVERSAL, not bitwise NOT. So `Borders.ALL - Borders.TOP` is wrong. A wrapper must not expose `-`/`remove` as-is; compute `Borders(a.bits & ~b.bits)` itself.
Color mapping in CrosstermBackend: tui `Red` -> crossterm `DarkRed`, tui `LightRed` -> crossterm `Red`, `Gray` -> `Grey`, `DarkGray` -> `DarkGrey` (ANSI 0-7 = "dark" in crossterm naming).

### 2.4 Text
```scala
case class Span(content: String, style: Style) { def width: Int; def styledGraphemes(baseStyle: Style): Array[StyledGrapheme] }
object Span { def nostyle(content: String): Span; def styled(content: String, style: Style): Span }
case class Spans(spans: Array[Span]) { def width: Int }   // one line (ratatui later renamed it Line)
object Spans { def nostyle(s: String): Spans; def styled(s: String, style: Style): Spans; def from(spans: Span*): Spans }
case class Text(lines: Array[Spans]) { def width: Int; def height: Int; def overwrittenStyle(style: Style): Text }
object Text { def nostyle(content: String): Text; def from(span: Span): Text; def from(spans: Span*): Text; def from(spans: Spans): Text; def fromSpans(spans: Spans*): Text }
case class StyledGrapheme(symbol: Grapheme, style: Style)
```
All collections are `Array` (mutable, reference equality in case-class `==`/`hashCode`).

### 2.5 Widget traits and Frame
```scala
trait Widget { def render(area: Rect, buf: Buffer): Unit }
trait StatefulWidget { type State; def render(area: Rect, buf: Buffer, state: State): Unit }
case class Frame(buffer: Buffer, size: Rect, var cursorPosition: Option[(Int, Int)]) {
  def renderWidget(widget: Widget, area: Rect): Unit
  def renderStatefulWidget[W <: StatefulWidget](widget: W, area: Rect)(state: widget.State): Unit
  def setCursor(x: Int, y: Int): Unit
}
case class CompletedFrame(buffer: Buffer, area: Rect)   // valid only until next draw
```

### 2.6 Widgets (package `tui.widgets`, all case classes, all params shown with defaults)
```scala
case class BlockWidget(title: Option[Spans] = None, titleAlignment: Alignment = Alignment.Left, borders: Borders = Borders.NONE,
    borderStyle: Style = Style.DEFAULT, borderType: BlockWidget.BorderType = BlockWidget.BorderType.Plain, style: Style = Style.DEFAULT) extends Widget {
  def inner(area: Rect): Rect }
object BlockWidget { sealed trait BorderType; object BorderType { Plain; Rounded; Double; Thick; def lineSymbols(bt): symbols.line.Set } }

case class ParagraphWidget(text: Text, block: Option[BlockWidget] = None, style: Style = Style.DEFAULT,
    wrap: Option[ParagraphWidget.Wrap] = None, scroll: (Int, Int) = (0, 0) /* (y, x) */, alignment: Alignment = Alignment.Left) extends Widget
object ParagraphWidget { case class Wrap(trim: Boolean); def getLineOffset(lineWidth: Int, textAreaWidth: Int, alignment: Alignment): Int }

case class ListWidget(block: Option[BlockWidget] = None, items: Array[ListWidget.Item], style: Style = Style.DEFAULT,
    startCorner: Corner = Corner.TopLeft, highlightStyle: Style = Style.DEFAULT, highlightSymbol: Option[String] = None,
    repeatHightlightSymbol: Boolean = false /* sic, typo */) extends Widget with StatefulWidget { type State = ListWidget.State }
object ListWidget { case class State(var offset: Int = 0, var selected: Option[Int] = None) { def select(index: Option[Int]): Unit }
                    case class Item(content: Text, style: Style = Style.DEFAULT) { def height: Int } }

case class TableWidget(block: Option[BlockWidget] = None, style: Style = Style.DEFAULT, widths: Array[Constraint] = Array.empty,
    columnSpacing: Int = 1, highlightStyle: Style = Style.DEFAULT, highlightSymbol: Option[String] = None,
    header: Option[TableWidget.Row] = None, rows: Array[TableWidget.Row]) extends StatefulWidget with Widget { type State = TableWidget.State }
object TableWidget { case class Cell(content: Text, style: Style = Style.DEFAULT)
                     case class Row(cells: Array[TableWidget.Cell], height: Int = 1, style: Style = Style.DEFAULT, bottomMargin: Int = 0) { val totalHeight: Int }
                     case class State(var offset: Int = 0, var selected: Option[Int] = None) { def select(index: Option[Int]): Unit } }

case class TabsWidget(block: Option[BlockWidget] = None, titles: Array[Spans], selected: Int = 0, style: Style = Style.DEFAULT,
    highlightStyle: Style = Style.DEFAULT, divider: Span = Span.nostyle(symbols.line.VERTICAL)) extends Widget

case class GaugeWidget(block: Option[BlockWidget] = None, ratio: GaugeWidget.Ratio = GaugeWidget.Ratio.Zero, label: Option[Span] = None,
    useUnicode: Boolean = false, style: Style = Style.DEFAULT, gaugeStyle: Style = Style.DEFAULT) extends Widget
object GaugeWidget { case class Ratio(value: Double) /* require 0..1, throws IllegalArgumentException */; object Ratio { val Zero; def percent(value: Double): Ratio } }

case class LineGaugeWidget(block: Option[BlockWidget] = None, ratio: GaugeWidget.Ratio = GaugeWidget.Ratio.Zero, label: Option[Spans] = None,
    lineSet: symbols.line.Set = symbols.line.NORMAL, style: Style = Style.DEFAULT, gaugeStyle: Style = Style.DEFAULT) extends Widget

case class SparklineWidget(block: Option[BlockWidget] = None, style: Style = Style.DEFAULT, data: collection.Seq[Int] = Nil,
    max: Option[Int] = None, barSet: symbols.bar.Set = symbols.bar.NINE_LEVELS) extends Widget

case class BarChartWidget(block: Option[BlockWidget] = None, barWidth: Int = 1, barGap: Int = 1, barSet: symbols.bar.Set = symbols.bar.NINE_LEVELS,
    barStyle: Style = Style.DEFAULT, valueStyle: Style = Style.DEFAULT, labelStyle: Style = Style.DEFAULT, style: Style = Style.DEFAULT,
    data: Array[(String, Int)] = Array.empty, max: Option[Int] = None) extends Widget

case class ChartWidget(datasets: Array[ChartWidget.Dataset], block: Option[BlockWidget] = None, xAxis: ChartWidget.Axis = ChartWidget.Axis.default,
    yAxis: ChartWidget.Axis = ChartWidget.Axis.default, style: Style = Style.DEFAULT,
    hiddenLegendConstraints: (Constraint, Constraint) = (Constraint.Ratio(1, 4), Constraint.Ratio(1, 4))) extends Widget
object ChartWidget {
  case class Axis(title: Option[Spans] = None, bounds: Point = Point.Zero /* (min, max) */, labels: Option[Array[Span]] = None, style: Style = Style.DEFAULT, labelsAlignment: Alignment = Alignment.Left)
  sealed trait GraphType; object GraphType { Scatter; Line }
  case class Dataset(name: String = "", data: Array[Point] = Array.empty, marker: symbols.Marker = symbols.Marker.Dot, graphType: GraphType = GraphType.Scatter, style: Style = Style.DEFAULT) }

case object ClearWidget extends Widget   // resets every cell in area (for popups)

// package tui.widgets.canvas
case class CanvasWidget(block: Option[BlockWidget] = None, xBounds: Point = Point.Zero, yBounds: Point = Point.Zero,
    backgroundColor: Color = Color.Reset, marker: symbols.Marker = symbols.Marker.Braille)(painter: Context => Unit) extends Widget
case class Context(xBounds: Point, yBounds: Point, grid: Grid, var dirty: Boolean, layers: mutable.ArrayBuffer[Layer], labels: mutable.ArrayBuffer[Label]) {
  def draw(shape: Shape): Unit; def layer(): Unit; def print(x: Double, y: Double, spans: Spans): Unit; def finish(): Unit }
trait Shape { def draw(painter: Painter): Unit }
case class Line(x1: Double, y1: Double, x2: Double, y2: Double, color: Color) extends Shape
case class Rectangle(x: Double, y: Double, width: Double, height: Double, color: Color) extends Shape
case class Points(coords: Array[Point] = Array.empty, color: Color = Color.Reset) extends Shape
case class WorldMap(resolution: MapResolution = MapResolution.Low, color: Color = Color.Reset) extends Shape
sealed trait MapResolution; object MapResolution { Low; High }
case class Label(x: Double, y: Double, spans: Spans)
case class Painter(context: Context, resolution: Point) { def getPoint(x: Double, y: Double): Option[(Int, Int)]; def paint(x: Int, y: Int, color: Color): Unit }
```
Missing vs modern ratatui (so a wrapper would have to build them itself): Scrollbar, Calendar, TextInput, Line (as a type), Padding, Block titles on bottom / multiple titles, Constraint.Fill, Flex layout.

### 2.7 symbols
`symbols.block` (FULL..ONE_EIGHTH, `case class Set(full, sevenEighths, threeQuarters, fiveEighths, half, threeEights, oneQuarter, oneEighth, empty)`, `THREE_LEVELS`, `NINE_LEVELS`), `symbols.bar` (same shape), `symbols.line` (VERTICAL, HORIZONTAL, ROUNDED_*, DOUBLE_*, THICK_*, `case class Set(vertical, horizontal, topRight, topLeft, bottomRight, bottomLeft, verticalLeft, verticalRight, horizontalDown, horizontalUp, cross)`, `NORMAL`, `ROUNDED`, `DOUBLE`, `THICK`), `symbols.DOT.value`, `symbols.braille.{BLANK, DOTS}`, `sealed trait symbols.Marker { Dot; Block; Braille }`.

### 2.8 Terminal / Backend / withTerminal
```scala
case class Terminal private (backend: Backend, buffers: Array[Buffer], var current: Int, var hiddenCursor: Boolean, viewport: Viewport) {
  def drop(): Unit                      // shows cursor if hidden
  def getFrame(): Frame; def currentBufferMut(): Buffer
  def flush(): Unit                     // previous.diff(current) -> backend.draw
  def resize(area: Rect): Unit          // resizes both buffers, clears screen
  def autoresize(): Unit                // only if ResizeBehavior.Auto
  def draw(f: Frame => Unit): CompletedFrame
  def hideCursor(): Unit; def showCursor(): Unit; def getCursor(): (Int, Int); def setCursor(x: Int, y: Int): Unit
  def clear(): Unit; def size(): Rect
}
object Terminal { def init(backend: Backend): Terminal; def withOptions(backend: Backend, options: TerminalOptions): Terminal }
trait Backend { def draw(content: Array[(Int, Int, Cell)]): Unit; def hideCursor(): Unit; def showCursor(): Unit; def getCursor(): (Int, Int)
                def setCursor(x: Int, y: Int): Unit; def clear(): Unit; def size(): Rect; def flush(): Unit }
class CrosstermBackend(buffer: CrosstermJni) extends Backend
object CrosstermBackend { def apply(buffer: CrosstermJni); def from(color: Color): crossterm.Color; case class ModifierDiff(from: Modifier, to: Modifier) { def queue(commands: java.util.ArrayList[Command]): Unit } }
object withTerminal { def apply[T](f: (CrosstermJni, Terminal) => T): T }
```
`Terminal.draw` sequence: `autoresize()` -> `getFrame()` -> `f(frame)` -> `flush()` (diff + `backend.draw` = one `jni.enqueue(list)`) -> `hideCursor()` (a JNI `execute(Hide)`) or `showCursor()+setCursor` -> reset back buffer -> swap index -> `backend.flush()`.
Because `Backend` is a plain trait, a wrapper can supply its own backend (test backend, or a backend that records updates).

### 2.9 tui.crossterm (Java)
```java
public class CrosstermJni {            // static init loads the native lib
  public native void flush();
  public native boolean poll(Duration timeout);   // tui.crossterm.Duration(long secs, int nanos), NOT java.time.Duration
  public native Event read();                     // blocks until an event
  public native Xy terminalSize();                // record Xy(int x, int y) = (columns, rows)
  public native Xy cursorPosition();
  public native void disableRawMode();
  public native void enableRawMode();
  public native void enqueue(List<Command> commands);  public final void enqueue(Command... commands);
  public native void execute(List<Command> commands);  public final void execute(Command... commands);
}
sealed interface Event { record FocusGained(); record FocusLost(); record Key(KeyEvent keyEvent); record Mouse(MouseEvent mouseEvent); record Paste(String string); record Resize(int columns, int rows); }
record KeyEvent(KeyCode code, KeyModifiers modifiers, KeyEventKind kind, KeyEventState state)
sealed interface KeyCode { Backspace(); Enter(); Left(); Right(); Up(); Down(); Home(); End(); PageUp(); PageDown(); Tab(); BackTab(); Delete(); Insert();
  F(int num); Char(char c); Null(); Esc(); CapsLock(); ScrollLock(); NumLock(); PrintScreen(); Pause(); Menu(); KeypadBegin();
  Media(MediaKeyCode mediaKeyCode); Modifier(ModifierKeyCode modifierKeyCode) }
record KeyModifiers(int bits) { SHIFT=1, CONTROL=2, ALT=4, SUPER=8, HYPER=16, META=32, NONE=0 }
enum KeyEventKind { Press, Repeat, Release }
record KeyEventState(int bits) { KEYPAD=1, CAPS_LOCK=8, NUM_LOCK=8 /* same value: copied crossterm bug */, NONE=0 }
record MouseEvent(MouseEventKind kind, int column, int row, KeyModifiers modifiers)
sealed interface MouseEventKind { Down(MouseButton); Up(MouseButton); Drag(MouseButton); Moved(); ScrollDown(); ScrollUp(); }
enum MouseButton { Left, Right, Middle }
enum ModifierKeyCode { LeftShift, LeftControl, LeftAlt, LeftSuper, LeftHyper, LeftMeta, RightShift, RightControl, RightAlt, RightSuper, RightHyper, RightMeta, IsoLevel3Shift, IsoLevel5Shift }
enum MediaKeyCode { Play, Pause, PlayPause, Reverse, Stop, FastForward, Rewind, TrackNext, TrackPrevious, Record, LowerVolume, RaiseVolume, MuteVolume }
record KeyboardEnhancementFlags(int bits) { DISAMBIGUATE_ESCAPE_CODES=1, REPORT_EVENT_TYPES=2, REPORT_ALTERNATE_KEYS=4, REPORT_ALL_KEYS_AS_ESCAPE_CODES=8, REPORT_ASSOCIATED_TEXT=16 }
sealed interface Command { MoveTo(int x, int y); MoveToNextLine(int num_lines); MoveToPreviousLine(int num_lines); MoveToColumn(int column); MoveToRow(int row);
  MoveUp(int num_rows); MoveRight(int num_columns); MoveDown(int num_rows); MoveLeft(int num_columns); SavePosition(); RestorePosition(); Hide(); Show();
  EnableBlinking(); DisableBlinking(); SetCursorShape(CursorShape cursor_shape); EnableMouseCapture(); DisableMouseCapture();
  PushKeyboardEnhancementFlags(KeyboardEnhancementFlags flags); PopKeyboardEnhancementFlags(); EnableFocusChange(); DisableFocusChange();
  EnableBracketedPaste(); DisableBracketedPaste(); SetForegroundColor(Color color); SetBackgroundColor(Color color); SetUnderlineColor(Color color);
  SetColors(Optional<Color> foreground, Optional<Color> background); SetAttribute(Attribute attribute); SetAttributes(List<Attribute> attributes);
  SetStyle(Optional<Color> foreground_color, Optional<Color> background_color, Optional<Color> underline_color, List<Attribute> attributes);
  ResetColor(); DisableLineWrap(); EnableLineWrap(); EnterAlternateScreen(); LeaveAlternateScreen(); ScrollUp(int num_rows); ScrollDown(int num_rows);
  Clear(ClearType clear_type); SetSize(int columns, int rows); Print(String value) }
sealed interface Color { Reset(); Black(); DarkGrey(); Red(); DarkRed(); Green(); DarkGreen(); Yellow(); DarkYellow(); Blue(); DarkBlue(); Magenta(); DarkMagenta(); Cyan(); DarkCyan(); White(); Grey(); Rgb(int r, int g, int b); AnsiValue(int color) }
enum Attribute { Reset, Bold, Dim, Italic, Underlined, DoubleUnderlined, Undercurled, Underdotted, Underdashed, SlowBlink, RapidBlink, Reverse, Hidden, CrossedOut, Fraktur, NoBold, NormalIntensity, NoItalic, NoUnderline, NoBlink, NoReverse, NoHidden, NotCrossedOut, Framed, Encircled, OverLined, NotFramedOrEncircled, NotOverLined }
enum ClearType { All, Purge, FromCursorDown, FromCursorUp, CurrentLine, UntilNewLine }
enum CursorShape { UnderScore, Line, Block }
record Attributes(List<Attribute> attributes); record Xy(int x, int y); record Duration(long secs, int nanos)
```
Hazards for the wrapper: `KeyCode.Char(char c)` is a UTF-16 `char`, so a non-BMP key (emoji typed or IME) cannot be represented faithfully (UNVERIFIED what native side does). No `Event` for key release unless `PushKeyboardEnhancementFlags(REPORT_EVENT_TYPES)` is sent. `KeyEventKind` exists, so on Windows each key produces Press AND Release events (crossterm behaviour, UNVERIFIED for 0.25 on Windows); filter on `kind == Press`.

## 3. App loop (withTerminal, demos)

Sources (VERIFIED): `tui/withTerminal.scala` in the 0.0.10 sources jar; demos fetched from `raw.githubusercontent.com/oyvindberg/jatatui/v0.0.10/demo/...` into `.work/scratch/upstream-v0.0.10/`.

### 3.1 `withTerminal` (bracket)
```scala
object withTerminal {
  def apply[T](f: (CrosstermJni, Terminal) => T): T = {
    val jni = new CrosstermJni                 // loads native lib
    jni.enableRawMode()
    jni.execute(new Command.EnterAlternateScreen(), new Command.EnableMouseCapture())
    val backend = new CrosstermBackend(jni)
    val terminal = Terminal.init(backend)      // queries terminalSize(), 2 empty buffers, ResizeBehavior.Auto
    try f(jni, terminal)
    finally { jni.disableRawMode(); jni.execute(new Command.LeaveAlternateScreen(), new Command.DisableMouseCapture()); backend.showCursor() }
  }
}
```
Gaps a wrapper should fix: no JVM shutdown hook (SIGTERM / `System.exit` inside `f` leaves the terminal in raw mode + alt screen); no panic hook equivalent; always enables mouse capture (not configurable); if `enableRawMode()` succeeds but `execute(...)` throws, the `finally` is not reached (setup is outside `try`); `terminal.drop()` is never called. An effect-system `Resource`/`Scope` should acquire raw mode, alt screen, mouse capture, bracketed paste, focus change as separate, individually released steps.

### 3.2 Recommended loop (from `demo/Demo.scala`, the "tick + poll" pattern)
```scala
var last_tick = Instant.now()
while (true) {
  terminal.draw(f => ui.draw(f, app))                               // 1. render full UI from app state
  val timeout = tick_rate.minus(elapsed)                            // 2. time left until next tick
  if (jni.poll(new tui.crossterm.Duration(timeout.toSeconds, timeout.getNano))) {
    jni.read() match {                                              // 3. read exactly ONE event (non-blocking now)
      case key: tui.crossterm.Event.Key => key.keyEvent.code match {
        case c: KeyCode.Char => app.on_key(c.c()); case _: KeyCode.Left => app.on_left(); ... }
      case _ => ()
    }
  }
  if (elapsed >= tick_rate) { app.on_tick(); last_tick = Instant.now() } // 4. tick
  if (app.should_quit) return                                       // 5. quit check
}
```
Simpler "event-driven" variant (`UserInputExample`): `while (true) { terminal.draw(f => ui(f, app)); jni.read() match { ... } }` i.e. block on `read()` with no tick.
Properties of the upstream pattern:
- Immediate mode: every frame rebuilds all widgets; `Terminal.draw` diffs against the previous frame so only changed cells are sent.
- Single thread: draw, poll and read all on the main thread. App state is mutable (`case class App(var ...)`).
- One event per loop iteration, then a full redraw. Under bursts (paste, mouse move, key repeat) this redraws once per event. A better loop drains all ready events (`while (jni.poll(Duration(0,0))) handle(jni.read())`) and then draws once.
- Resize: `Terminal.draw` calls `autoresize()` itself (queries `terminalSize()` every frame, one JNI call), so `Event.Resize` does not need handling beyond triggering a redraw.
- Demos build `Layout(..., constraints = Array(...))` inside the draw closure every frame. See section 6: this leaks memory through `Layout.LAYOUT_CACHE`.

### 3.3 Recommended loop for an effect wrapper (design input, not upstream)
- One dedicated "terminal fiber/thread" owns `Terminal` + `CrosstermJni` output calls. It is the only caller of `draw`, `execute`, `enqueue`, `flush`.
- One "input fiber" runs blocking `poll(timeout)` / `read()` on a blocking thread pool (`ZIO.attemptBlocking`, `IO.blocking`/`IO.interruptible`, Kyo `Sync.defer` on an blocking executor), and pushes events into a queue/channel/hub. Do not use `read()` without `poll()` if you need interruption: a thread blocked in native `read()` cannot be interrupted by `Thread.interrupt` (JNI native code ignores interrupts, UNVERIFIED for crossterm but standard JVM behaviour). Poll with a short timeout (for example 50-100 ms) in a loop and check for cancellation between polls.
- The render loop merges: input events, ticks (`Schedule`/`Stream.tick`), and app messages; it folds them into an immutable model (Elm/TEA style) and draws when the model changed (or on a frame cap such as 60 fps).
- Do not call `cursorPosition()` while another thread is in `read()`/`poll()`: in crossterm the position query reads the reply through the same global event reader (UNVERIFIED for 0.25 internals; known crossterm behaviour).

## 4. Headless rendering / tests

### 4.1 Is there a TestBackend? (VERIFIED)
- **Not in any published artifact.** `tui_3-0.0.10.jar` / sources jar has no `TestBackend`. The upstream repo has one only in the unpublished `tests` project: `tests/src/scala/tui/TestBackend.scala` (tag v0.0.10), plus `TuiTest.assertBuffer` and `bufferView` helpers (ScalaTest). Copies are in `.work/scratch/upstream-v0.0.10/tests/`.
- `Backend` is a public 8-method trait, so a wrapper can ship its own `TestBackend`. `Buffer.withLines(...)` is public and is the upstream way to build expected buffers.
- **Upstream TestBackend has an aliasing bug** (VERIFIED by probe B1): `draw` does `buffer.set(x, y, c)` with the Cell reference that `Buffer.diff` returns. That reference points into the Terminal's current buffer, which `Terminal.draw` later `reset()`s in place when it swaps buffers. After 3 frames, the backend buffer showed `|frame-      |` instead of `|frame-2 BBB |`. Fix: `buffer.set(x, y, c.clone())` (probe B2 shows the right output).

### 4.2 Probe: headless rendering works (VERIFIED, compiled and ran)
File: `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/tui-probe.sc` (`//> using scala 3.7.4`, `//> using dep com.olvind.tui::tui:0.0.10`). Run with `source .work/env.sh && scala-cli run --server=false .work/scratch/tui-probe.sc`. Output saved at `.work/scratch/probe-output.txt`. JDK 21.0.8 (Temurin, x86_64), scala-cli 1.9.1, Scala 3.7.4. No TTY.
```
--- A. Block+Paragraph into Buffer.empty(Rect(0,0,20,5)) (Rect(0,0,20,5))
|╭Hello─────────────╮|
|│tui-scala renders │|
|│without a TTY     │|
|│                  │|
|╰──────────────────╯|
A. equals Buffer.withLines(...) via diff: true
```
- Pure rendering (`Widget.render(area, buf)`, `Terminal.draw` with an in-memory `Backend`) needs no TTY and does NOT load the native library (`tui.*` classes do not touch `CrosstermJni` unless you instantiate it or `withTerminal`).
- Recommended unit-test shape for tui4s: render into `Buffer.empty(rect)` and compare with `Buffer.withLines(...)` via `expected.diff(actual).isEmpty`, or compare `lines(buf)` strings (snapshot tests). For loop-level tests, use a `TestBackend` that clones cells, and feed events from a fake input source (a queue), never from `CrosstermJni`.

### 4.3 Native calls without a TTY (VERIFIED, probe K)
- `new CrosstermJni()` succeeds without a TTY (extracts `libnative-x86_64-darwin-crossterm.dylib` into `$TMPDIR/jni-<random>/`; the dir is left behind).
- `terminalSize()` -> `java.lang.RuntimeException: IO error: Os { code: 35, kind: WouldBlock, message: "Resource temporarily unavailable" }`.
- `poll(Duration(0,0))` -> `java.lang.RuntimeException: IO error: Custom { kind: Other, error: "Failed to initialize input reader" }`.
- So all native errors surface as plain `RuntimeException` with a Rust debug string (Rust `jvm_unwrap` -> `throw_new("java/lang/RuntimeException", ...)`, VERIFIED in `crossterm/cargo/src/jvm_unwrapper.rs`). A wrapper should map them to a typed error (e.g. `TerminalError.NoTty`). CI tests must never call `CrosstermJni` methods.

## 5. Local copies vs upstream (diff)

Method: downloaded `tui_3-<v>-sources.jar` for 0.0.7, 0.0.8, 0.0.9, 0.0.10 from repo1.maven.org, unzipped into `.work/scratch/tui-src-<v>/` (0.0.10 is in `.work/scratch/tui-src/`), ran `diff -rq` and `diff -u`.

### 5.1 `tui/src/scala/tui/**` (64 files) (VERIFIED)
- `diff -rq tui/src/scala/tui .work/scratch/tui-src-0.0.7/tui` -> **no output, exit 0**. The local copy is **byte-identical to the published tui_3 0.0.7 sources jar**. Same 64 files, no extra files, no missing files.
- Against 0.0.10 it differs in exactly 4 files, and every difference is an upstream change made after 0.0.7 (not a local edit):
  - `Color.scala`: 0.0.10 has `Rgb(r: Int, g: Int, b: Int)` and `Indexed(index: Int)`; local (0.0.7) has `Rgb(r: Byte, g: Byte, b: Byte)` and `Indexed(byte: Byte)`.
  - `CrosstermBackend.scala`: 0.0.10 maps `Indexed(i)` to `AnsiValue(i & 0xff)`.
  - `Span.scala`: 0.0.10 `width` = grapheme display width sum; 0.0.7 `width` = `content.length` (bug for wide chars).
  - `internal/Wcwidth.scala`: 0.0.10 adds emoji ranges (width 2).
- Local files have mtime 2025-10-28. The modified (uncommitted) `.gitignore` lists every file under `tui/src/scala/tui/` (line 163+), so git does not track them. `tui/target/` only has sbt `streams`/`update` caches (scala-2.13).
- **Verdict: deleting `tui/` loses NO user work.**

### 5.2 `src/main/scala/tui4s/internal/**` (8 files) (VERIFIED)
- All 8 files are **byte-identical to tui_3 0.0.7 `tui/internal/*`**, including the line `package tui.internal` (they were never renamed to `tui4s.internal`). They are tracked in git (commit 05599dd).
- Against 0.0.10 only `Wcwidth.scala` differs (the upstream emoji-range addition above).
- Hazard: because they declare `package tui.internal`, they would clash (split package / duplicate classes) with `tui_3` on the classpath once tui4s depends on the published jar.
- **Verdict: deleting `internal/` loses NO user work.** If tui4s needs `Wcwidth`/`UnicodeSegmentation`, call `tui.internal.*` from the jar (they are public objects, VERIFIED package-level `object`s) or better use `Span.width`/`Grapheme.width`.

### 5.3 Other `src/main/scala/tui4s/*.scala` (context, VERIFIED by diff line counts vs 0.0.7)
`BufferF`, `FrameF`, `TerminalF`, `CrosstermBackendF`, `CompletedFrameF`, `BackendF`, `withTerminal`, `CrosstermJniF`, `interfaces`, `widgets`, `elements/elements.scala`, `Demo.scala` are **heavily rewritten** (e.g. BufferF 60 lines vs 305 changed lines; TerminalF 188 lines vs 305 changed lines). These ARE user work (F[_]-style rewrites of upstream classes). The cleanup must not delete them without the user's decision, or it must save their ideas first.
- State at end of this research (VERIFIED `git log`): another process committed these files as `48a6dd9 wip: snapshot of first-take experiments (Flutter-style widgets, CE prototype)` and the current branch is `chore/cleanup`. So the user work is now preserved in git history. `tui/` stays untracked (its sources are git-ignored by 64 `.gitignore` lines; only `tui/.DS_Store` and `tui/target/` exist besides them).

## 6. Performance characteristics

All numbers below are VERIFIED measurements from `.work/scratch/tui-probe.sc` (section I) and `.work/scratch/tui-perf.sc` (output `.work/scratch/perf-output.txt`), after JIT warm-up, single run. Machine: **Intel i7-4980HQ (2014 Haswell), macOS, JDK 21.0.8 x86_64**, shared with other work. Treat as indicative, not a benchmark. Allocation = `ThreadMXBean.getThreadAllocatedBytes`.

| Scenario (200x50 terminal, no-op backend) | time/frame | alloc/frame | changed cells |
|---|---|---|---|
| empty `Terminal.draw` (autoresize + diff + reset) | 527 us | 0.2 KiB | 0 |
| BlockWidget ALL borders, full screen | 2327 us | 329 KiB | 0 |
| Paragraph ~1080 chars, wrap, 100x50 | 2502 us | 861 KiB | 0 |
| Paragraph ~1080 chars, no wrap, 100x50 | 2220 us | 861 KiB | 0 |
| List 50 items, 100x25, moving selection | 889 us | 238 KiB | 17 |
| Table 100 rows, 100x25, moving selection | 971-1450 us | 317 KiB | 21 |
| Gauge full width | 464 us | 137 KiB | 1 |
| combined (paragraph+block+list+table, probe I) | 5219 us | 3848 KiB | 37 |
| `Buffer.diff` alone, 10000 cells, no changes | 303 us | 66 B | - |
| `Buffer.setString`, 200 ASCII chars | 59 us | 74 KB (371 B/char) | - |

### 6.1 Where the cost comes from (VERIFIED by reading source, consistent with numbers)
- **Cell is mutable** (`case class Cell(var symbol, var fg, var bg, var modifier)`). The two buffers are reused across frames (no per-frame Buffer allocation), and `reset()` mutates cells in place.
- **`Cell.setStyle` allocates**: `modifier.insert(...).remove(...)` creates 2 new `Modifier` case-class instances per cell (`Modifier(bits: Int)` is a boxed case class, not a value class / opaque type). `Buffer.setStyle(area, style)` over a full 200x50 screen = 20000 allocations. Every widget calls `buf.setStyle(area, style)` on its whole area first, even with `Style.DEFAULT`. This is why a plain full-screen Block costs 329 KiB and 2.3 ms.
- **Text rendering allocates per grapheme**: `UnicodeSegmentation.graphemes` creates a new `java.text.BreakIterator` per call, a `substring` and a `Grapheme` per grapheme; `Span.styledGraphemes` creates a `StyledGrapheme` + patched `Style` per grapheme; `Grapheme.width` is a `lazy val` computed via `codePoints().map(Wcwidth.of).sum()` (a stream). Paragraph segments the WHOLE text every frame, even lines scrolled out of view.
- **`Buffer.get/indexOf` always runs `debug_assert(pred, msg, x, y, area)`** (not elided in release; varargs `Any*` built eagerly), so each cell access may allocate a Seq and box ints unless escape analysis removes it (UNVERIFIED whether C2 removes it).
- **`Buffer.diff`** is O(width*height) every frame, compares cells with case-class `==` (Grapheme string equality + Color + Modifier equals). 303 us for 10000 unchanged cells (~30 ns/cell). Output is `Array[(Int, Int, Cell)]` = 1 Tuple3 + boxed Integers per changed cell.
- **CrosstermBackend.draw**: per changed cell it allocates `new Command.Print(String)`, optional `MoveTo`, `SetForegroundColor(new crossterm.Color.X())`, etc., into a `java.util.ArrayList`, then makes ONE `jni.enqueue(list)` call. On the Rust side (`jni_from_jvm.rs`) each command is decoded by JNI reflection (`call_method("name")`, `get_field` by name per object) - several JNI up-calls per command. Cost scales with changed cells, which is usually small.
- **Per frame fixed JNI calls**: `terminalSize()` (autoresize), `enqueue(list)`, `execute(Hide)` (flushes stdout), `flush()`.
- **Layout**: Cassowary solve per distinct `(Rect, Layout)` key, cached in `Layout.LAYOUT_CACHE`. See 7.2 for the leak.

### 6.2 Implications for the wrapper
- The upstream renderer is fast enough for normal TUIs (a few ms per frame for a busy 200x50 screen on 2014 hardware), but it is allocation-heavy (hundreds of KiB to MiB per frame). A 60 fps loop at 4 MiB/frame is ~240 MiB/s of garbage: fine for G1/ZGC, but pointless when nothing changed.
- Best wins for tui4s without forking upstream: (1) **only redraw when the model changed** (or on resize/tick), (2) render at a capped frame rate and coalesce input bursts, (3) keep widget trees small (avoid full-screen `setStyle` with default styles), (4) reuse `Array[Constraint]` instances, (5) cache immutable `Text` values instead of rebuilding strings every frame.
- Thread-safety: see section 7. Everything (Terminal, Buffer, Cell, Layout cache, widget State) is unsynchronized mutable state. **Drawing must happen on one thread at a time.**

## 7. Mutability hazards for effect wrappers

Each item VERIFIED by source reading; items marked (probe X) were also demonstrated by `.work/scratch/tui-probe.sc`.

### 7.1 Mutable data structures
- `Buffer(var area, var content: mutable.ArraySeq[Cell])`, `Cell(var ...)`, `Viewport(var area, ...)`, `Terminal(var current, var hiddenCursor, ...)`, `Frame(var cursorPosition)`, `canvas.Context(var dirty, ArrayBuffer ...)`.
- `Buffer.get(x, y)` returns the live Cell; setters return `this.type` and mutate.
- **`Buffer.diff` returns references into the next buffer** (probe F: changing the next buffer after `diff` changed the update already returned). Any backend or recorder that keeps them must `clone()` (see 4.1 TestBackend bug, probe B1).
- `CompletedFrame(buffer, area)` returned by `Terminal.draw` points at the buffer that will be `reset()` and reused on the next draw; it is only valid until the next `draw`. Never expose it as an immutable value.
- `Text`, `Spans`, `Table.Row.cells`, `ListWidget.items`, `Layout.constraints`, `Dataset.data`, `BarChart.data` are `Array`s: mutable, and case-class `==`/`hashCode` use reference identity for them. Two structurally equal widgets are not `==`. This matters for memoization ("skip render if widget equals previous") - it will not work on upstream types; the wrapper needs its own immutable model (e.g. `IArray`/`Chunk`/`Vector`) with structural equality, converted to upstream arrays only at render time.

### 7.2 Global mutable state
- **`Layout.LAYOUT_CACHE`: a global, unbounded, unsynchronized `scala.collection.mutable.HashMap[(Rect, Layout), Array[Rect]]`.**
  - Unbounded growth (probe D): 1000 `Layout(constraints = Array(...)).split(r)` calls with a fresh (but equal) Array produced **1000 cache entries**; reusing the same Array instance produced 1. The upstream demos build Layouts inside the draw closure, so they leak one entry per frame per layout.
  - **`TableWidget` leaks on every render** (perf probe): `getColumnsWidths` builds `Layout(..., constraints = constraints.toArray, ...)` with a new Array each time. After 6000 table renders `LAYOUT_CACHE.size == 6000`, even with a stable `widths` array. A long-running app with a table grows memory without bound. Mitigation: call `Layout.LAYOUT_CACHE.clear()` on the render thread (for example every N frames or when `size > limit`), or implement tui4s's own layout (cached with a bounded LRU keyed on immutable values).
  - Returned arrays are shared (probe E): `split` returns the cached `Array[Rect]` itself; if a caller mutates it, later calls see the change. A wrapper must copy (`.toVector`/`IArray.from`) before handing results to users.
  - Data race (probe J): 8 threads x 20000 concurrent `split` calls with distinct keys gave no exception in this run but `cacheSize=159724` of 160000 inserted keys, i.e. **lost updates**. `mutable.HashMap` is not thread-safe; concurrent use can also corrupt it (UNVERIFIED beyond lost updates). So `Layout.split` must run on one thread (the render thread) or behind a lock.
- `NativeLoader` side effects: temp dir per JVM, never deleted.
- Rust `std::io::stdout()` is the process-global stdout of the native side. Anything the JVM writes to `System.out` or `System.err` (loggers, `println`, default ZIO/CE/Kyo console loggers, stack traces) goes to the same terminal and corrupts the TUI screen. Log to a file while the TUI runs.

### 7.3 Widgets that mutate their inputs
- `ListWidget.render(area, buf, state)` sets `state.offset` (probe G: `State(0, Some(40))` became `State(36, Some(40))` after render in a 5-row area). `TableWidget.render` does the same (VERIFIED: `state.offset = start` at `TableWidget.scala:151`). The `Widget.render(area, buf)` overload creates a fresh `State()` each time, so scrolling position is lost if the stateless overload is used.
- Implication for a pure API: treat `State` as an output of rendering too. Pattern: keep an immutable `ListState(offset, selected)` in the model; at render time create `ListWidget.State(offset, selected)`, render, read back `offset`, and write it into the model (`Ref`/`Var`/`AtomicRef` update or return it from `view`). Do not share one upstream `State` object between fibers.
- `Terminal.draw(f)` runs `f` synchronously with a `Frame` that wraps the live back buffer; the closure must be synchronous and must not escape the `Frame` (no forking fibers that write to it later).

### 7.4 Exceptions and preconditions (throwing constructors)
- `Buffer.get/set/indexOf` outside the area -> `java.lang.RuntimeException("assertion failed: Trying to access position outside the buffer ...")` (probe H). Custom widgets must clip to `area` themselves.
- `GaugeWidget.Ratio(value)` -> `IllegalArgumentException` if not in [0, 1]; `Constraint.Percentage(p)` -> `IllegalArgumentException` if not in [0, 100]. A wrapper should clamp or offer smart constructors returning `Either`/`Result`.
- `Layout.split` -> `sys.error` (RuntimeException) if Cassowary fails to add constraints.
- All `CrosstermJni` failures -> `RuntimeException` with a Rust debug string.

### 7.5 Upstream bugs a wrapper should work around
- `Borders.remove` / `-` uses `Integer.reverse` instead of `~` (probe C): `Borders.ALL - Borders.TOP` gives `bits=0` (no borders at all) instead of `11100`.
- Native `KeyCode::Char(c)` is converted with `c as u16` and a `// todo` comment (VERIFIED in `crossterm/cargo/src/jni_to_jvm.rs` at tag v0.0.10): non-BMP characters (emoji) typed by the user are truncated to a wrong char.
- Native `KeyCode::Modifier(_)` is mapped with `nullary(env, "Modifier")` although the Java record `KeyCode.Modifier(ModifierKeyCode)` has one component: the constructor lookup would fail (only reachable with `REPORT_ALL_KEYS_AS_ESCAPE_CODES`; consequence UNVERIFIED).
- `KeyEventState.CAPS_LOCK == NUM_LOCK == 8` (copied from crossterm 0.25).
- `withTerminal` setup is outside the `try`; no shutdown hook.
- Native crossterm is 0.25 (2022); newer crossterm fixes (e.g. kitty keyboard, Windows input) are missing.

### 7.6 What is safe from multiple fibers (summary)
- Safe: constructing immutable-looking values that contain no arrays you mutate later (`Style`, `Rect`, `Color`, `Modifier`, `Span`), rendering into a **private** `Buffer` (one buffer per fiber) **except** that any widget or code path that calls `Layout.split` (TableWidget, user layouts) touches the global cache - so parallel rendering is NOT safe unless you avoid Layout/Table or lock around it.
- Not safe: sharing a `Terminal`, `Frame`, `Buffer`, widget `State`, or calling `Layout.split` concurrently. `CrosstermJni` output (`enqueue`/`execute`/`flush`) from two threads can interleave escape sequences (Rust stdout locks per write call, not per command list; UNVERIFIED in practice). `read()`/`poll()` from two threads contend on crossterm's global event reader (UNVERIFIED for 0.25).
- Recommended model: one render fiber pinned to a single thread (or a `Semaphore(1)`/`Mutex` around all terminal + layout work), one blocking input fiber that only calls `poll`/`read`, communicating by a queue.

## Files produced
- Report: `/Users/yuri4n/Developer/projects/tui4s/.work/reports/tui-scala.md`
- Probe: `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/tui-probe.sc` (output `probe-output.txt`)
- Perf probe: `/Users/yuri4n/Developer/projects/tui4s/.work/scratch/tui-perf.sc` (output `perf-output.txt`)
- Upstream sources: `.work/scratch/tui-src/` (0.0.10), `.work/scratch/tui-src-0.0.7|0.0.8|0.0.9/`, `.work/scratch/crossterm-src/`, `.work/scratch/cassowary-src/`
- Upstream repo files at tag v0.0.10 (tests/TestBackend, demos, Rust JNI): `.work/scratch/upstream-v0.0.10/`
- jatatui 0.31.0 jars: `.work/scratch/*-0.31.0.jar`
