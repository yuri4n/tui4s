package tui4s.internal

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal
import tui4s.*

/** Where the engine writes its trace lines (SPEC 5.16): one JSON object per
  * line. Called on the render fiber (or the test thread) only.
  */
private[tui4s] trait Tracer:
    /** Appends one line (without the line break). */
    def line(json: String): Unit

    /** Writes out buffered lines. */
    def flush(): Unit = ()

    /** Writes out buffered lines and releases the file. Never throws. */
    def close(): Unit = ()
end Tracer

/** The trace of a test driver: the newest `limit` lines in memory. */
private[tui4s] final class MemoryTrace(limit: Int) extends Tracer:
    private val buf = scala.collection.mutable.ArrayDeque.empty[String]

    def line(json: String): Unit =
        if buf.size >= limit then discard(buf.removeHead())
        buf += json

    /** The lines, oldest first. */
    def lines: Seq[String] = buf.toVector
end MemoryTrace

/** The trace file of `TuiOptions.trace`: UTF-8 JSON lines, buffered. A line is
  * written out when 100 ms passed since the last write-out, when the render
  * fiber goes idle (see [[flush]]), and at the release. An I/O error stops the
  * trace and never the app; `warn` reports it once.
  */
private[tui4s] final class TraceWriter private (
    out: java.io.BufferedWriter,
    path: Path,
    warn: (String, Throwable) => Unit,
) extends Tracer:
    private var lastFlush = java.lang.System.nanoTime()
    private var dirty     = false
    private var broken    = false

    def line(json: String): Unit =
      if !broken then
          try
              out.write(json)
              out.write('\n')
              dirty = true
              val now = java.lang.System.nanoTime()
              if now - lastFlush >= TraceWriter.FlushNanos then flush()
          catch case NonFatal(e) => stop(e)

    override def flush(): Unit =
      if dirty && !broken then
          try
              out.flush()
              dirty = false
              lastFlush = java.lang.System.nanoTime()
          catch case NonFatal(e) => stop(e)

    private def stop(e: Throwable): Unit =
        broken = true
        try
            warn(
              s"tui4s: writing the trace file $path failed; the trace stops",
              e,
            )
        catch case NonFatal(_) => ()
    end stop

    override def close(): Unit =
        flush()
        try out.close()
        catch case NonFatal(_) => ()
end TraceWriter

private[tui4s] object TraceWriter:
    private val FlushNanos = 100_000_000L

    /** A writer that truncates `path`; `null` when the file cannot be opened:
      * then `warn` gets the reason and the app runs without a trace.
      */
    def open(
        path: Path,
        warn: (String, Throwable) => Unit,
    ): TraceWriter | Null =
      try
          new TraceWriter(
            Files.newBufferedWriter(path, StandardCharsets.UTF_8),
            path,
            warn,
          )
      catch
          case NonFatal(e) =>
            try
                warn(
                  s"tui4s: the trace file $path cannot be written; the app runs without a trace",
                  e,
                )
            catch case NonFatal(_) => ()
            null
end TraceWriter

/** JSON text for trace lines. */
private[tui4s] object Json:
    /** `s` as a JSON string, with quotes. */
    def str(s: String): String =
        val b = new java.lang.StringBuilder(s.length + 2)
        discard(b.append('"'))
        var i = 0
        while i < s.length do
            val c = s.charAt(i)
            c match
                case '"'           => discard(b.append("\\\""))
                case '\\'          => discard(b.append("\\\\"))
                case '\n'          => discard(b.append("\\n"))
                case '\r'          => discard(b.append("\\r"))
                case '\t'          => discard(b.append("\\t"))
                case c if c < 0x20 =>
                  discard(b.append(f"\\u${c.toInt}%04x"))
                case c             => discard(b.append(c))
            end match
            i += 1
        end while
        discard(b.append('"'))
        b.toString
    end str

    /** A JSON array of strings. */
    def arr(items: Iterable[String]): String =
      items.iterator.map(str).mkString("[", ",", "]")

    /** Seconds with three decimals, without the locale: "12.481". */
    def seconds(nanos: Long): String =
        val ms = (nanos / 1_000_000L).max(0L)
        val r  = (ms % 1000).toString
        s"${ms / 1000}.${"0" * (3 - r.length)}$r"

    /** The strings of the array field `name` of a trace line; `None` when the
      * line has no such field.
      */
    def strings(line: String, name: String): Option[Vector[String]] =
        val key = "\"" + name + "\":["
        val at  = line.indexOf(key)
        if at < 0 then None
        else
            val out = Vector.newBuilder[String]
            var i   = at + key.length
            var end = false
            while !end && i < line.length do
                line.charAt(i) match
                    case ']' => end = true
                    case '"' =>
                      val b = new java.lang.StringBuilder
                      i += 1
                      while i < line.length && line.charAt(i) != '"' do
                          val c = line.charAt(i)
                          if c == '\\' && i + 1 < line.length then
                              i += 1
                              line.charAt(i) match
                                  case 'n'                        => discard(b.append('\n'))
                                  case 'r'                        => discard(b.append('\r'))
                                  case 't'                        => discard(b.append('\t'))
                                  case 'u' if i + 4 < line.length =>
                                    val hex = line.substring(i + 1, i + 5)
                                    discard(
                                      b.append(Integer.parseInt(hex, 16).toChar),
                                    )
                                    i += 4
                                  case other                      => discard(b.append(other))
                              end match
                          else discard(b.append(c))
                          end if
                          i += 1
                      end while
                      out += b.toString
                    case _   => ()
                end match
                i += 1
            end while
            Some(out.result())
        end if
    end strings

    /** The string field `name` of a trace line. */
    def string(line: String, name: String): Option[String] =
        val key = "\"" + name + "\":\""
        val at  = line.indexOf(key)
        if at < 0 then None
        else
            val from = at + key.length
            val to   = line.indexOf('"', from)
            if to < 0 then None else Some(line.substring(from, to))
    end string
end Json

/** The event codes of traces and messages: "key:ctrl+s", "resize:80x24",
  * "mouse:down:left:3,4", "paste:<base64>", "focus:gained".
  */
private[tui4s] object EventCode:
    /** The code of `e`. */
    def apply(e: Event): String = e match
        case k: Key                  => "key:" + k.show
        case Paste(t)                =>
          "paste:" + java.util.Base64.getEncoder
            .encodeToString(t.getBytes(StandardCharsets.UTF_8))
        case Resize(w, h)            => s"resize:${w}x$h"
        case Mouse(kind, x, y, _, _) => s"mouse:${mouse(kind)}:$x,$y"
        case FocusGained             => "focus:gained"
        case FocusLost               => "focus:lost"

    private def mouse(kind: MouseKind): String = kind match
        case MouseKind.Down(b)     => "down:" + button(b)
        case MouseKind.Up(b)       => "up:" + button(b)
        case MouseKind.Drag(b)     => "drag:" + button(b)
        case MouseKind.Moved       => "moved"
        case MouseKind.ScrollUp    => "scroll-up"
        case MouseKind.ScrollDown  => "scroll-down"
        case MouseKind.ScrollLeft  => "scroll-left"
        case MouseKind.ScrollRight => "scroll-right"

    private def button(b: Button): String = b.toString.toLowerCase

    private val named: Map[String, Key] = Map(
      "enter"     -> Key.Enter,
      "esc"       -> Key.Esc,
      "tab"       -> Key.Tab,
      "shift+tab" -> Key.BackTab,
      "backspace" -> Key.Backspace,
      "del"       -> Key.Delete,
      "ins"       -> Key.Insert,
      "↑"         -> Key.Up,
      "↓"         -> Key.Down,
      "←"         -> Key.Left,
      "→"         -> Key.Right,
      "home"      -> Key.Home,
      "end"       -> Key.End,
      "pgup"      -> Key.PageUp,
      "pgdn"      -> Key.PageDown,
    )

    private val buttons: Map[String, Button] =
      Button.values.iterator.map(b => button(b) -> b).toMap

    /** The event of a code; `None` for a code that is not an event code. A key
      * code reads back as the key it shows ("↑" is `Key.Up`, "shift+tab" is
      * `Key.BackTab`).
      */
    def decode(code: String): Option[Event] =
        val colon = code.indexOf(':')
        if colon < 0 then None
        else
            val rest = code.substring(colon + 1)
            code.substring(0, colon) match
                case "key"    => key(rest)
                case "paste"  =>
                  try
                      Some(
                        Paste(
                          String(
                            java.util.Base64.getDecoder.decode(rest),
                            StandardCharsets.UTF_8,
                          ),
                        ),
                      )
                  catch case NonFatal(_) => None
                case "resize" =>
                  rest.split('x') match
                      case Array(w, h) =>
                        w.toIntOption.zip(h.toIntOption).map(Resize(_, _))
                      case _           => None
                case "focus"  =>
                  rest match
                      case "gained" => Some(FocusGained)
                      case "lost"   => Some(FocusLost)
                      case _        => None
                case "mouse"  => mouseEvent(rest)
                case _        => None
            end match
        end if
    end decode

    private def char(s: String): Option[Char] =
      if s == "space" then Some(' ')
      else if s.length == 1 then Some(s(0))
      else None

    private def key(s: String): Option[Key] =
      namedOrF(s).orElse(char(s).map(Key.Char(_))).orElse(modified(s))

    private def namedOrF(s: String): Option[Key] =
      named.get(s).orElse {
        if s.length > 1 && s(0) == 'f' && s.drop(1).forall(_.isDigit) then
            s.drop(1).toIntOption.map(Key.F(_))
        else None
      }

    private def modified(s: String): Option[Key] =
        var rest  = s
        var mods  = Mods()
        var going = true
        while going do
            if rest.startsWith("ctrl+") && rest.length > 5 then
                mods = mods.copy(ctrl = true)
                rest = rest.drop(5)
            else if rest.startsWith("alt+") && rest.length > 4 then
                mods = mods.copy(alt = true)
                rest = rest.drop(4)
            else if rest.startsWith("shift+") && rest.length > 6 then
                mods = mods.copy(shift = true)
                rest = rest.drop(6)
            else going = false
        end while
        if mods == Mods() then None
        else
            namedOrF(rest) match
                case Some(k) => Some(Key.Modified(k, mods))
                case None    =>
                  char(rest) match
                      case Some(c) if mods == Mods(ctrl = true) =>
                        Some(Key.Ctrl(c))
                      case Some(c) if mods == Mods(alt = true)  =>
                        Some(Key.Alt(c))
                      case _                                    => None
        end if
    end modified

    private def mouseEvent(s: String): Option[Event] =
        val parts                             = s.split(':')
        def at(p: String): Option[(Int, Int)] = p.split(',') match
            case Array(x, y) => x.toIntOption.zip(y.toIntOption)
            case _           => None
        val kind: Option[(MouseKind, String)] = parts match
            case Array("down", b, p)      =>
              buttons.get(b).map(x => (MouseKind.Down(x), p))
            case Array("up", b, p)        =>
              buttons.get(b).map(x => (MouseKind.Up(x), p))
            case Array("drag", b, p)      =>
              buttons.get(b).map(x => (MouseKind.Drag(x), p))
            case Array("moved", p)        => Some((MouseKind.Moved, p))
            case Array("scroll-up", p)    => Some((MouseKind.ScrollUp, p))
            case Array("scroll-down", p)  => Some((MouseKind.ScrollDown, p))
            case Array("scroll-left", p)  => Some((MouseKind.ScrollLeft, p))
            case Array("scroll-right", p) => Some((MouseKind.ScrollRight, p))
            case _                        => None
        kind.flatMap((k, p) => at(p).map((x, y) => Mouse(k, x, y, Mods(), Nil)))
    end mouseEvent
end EventCode
