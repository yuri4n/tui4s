package kyo.tui4sproto

import kyo.*
import kyo.internal.HtmlRenderer
import kyo.internal.KeyboardEventData
import kyo.internal.MouseEventData
import kyo.internal.ReactiveRegion
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange

/** A headless kyo-ui runner, for tests of the facades. It is a stand-in for the tui4s terminal runner.
  *
  * It uses kyo-ui's own machinery, so the facades are tested against the real semantics:
  *   - the UI value is mounted ONCE with kyo-ui's `ReactiveUI.normalize` + `ReactiveUI.subscribe` (the same calls as
  *     `UI.runRender` and the WebSocket runner);
  *   - events go through kyo-ui's own dispatcher (`Subscription.handle`): bubbling, two-way binding of a `SignalRef`
  *     on input, Enter on a button = click, and so on;
  *   - the screen is kyo-ui's own HTML render of the CURRENT signal values (`HtmlRenderer.render`), reduced to text.
  *
  * Like a terminal runner, ONE runner fiber dispatches all events, in order. That fiber is started by `mount`, so it
  * inherits the Kyo `Local`s of the caller of `mount` (the facades keep the caller's ZIO runtime / Cats Dispatcher
  * there).
  *
  * It lives in package `kyo` because the runner API of kyo-ui (`ReactiveUI`, `UIEvent`, `HtmlRenderer`) is
  * `private[kyo]`. Frames come from the caller: `Frame.derive` refuses to run inside package `kyo`.
  */
final class Headless private (ui: UI, commands: Channel[Headless.Command]):

    /** kyo-ui's HTML for the current signal values. */
    def html(using Frame): String < Sync = HtmlRenderer.render(ui, Seq.empty)

    /** The screen as plain text: one line per block element, `[value]` for a text input, `[x]` / `[ ]` for a
      * checkbox, `(label)` for a button.
      */
    def text(using Frame): String < Sync = html.map(Headless.toText)

    /** Sends one event to the runner fiber and waits until kyo-ui has dispatched it (the handlers have run). */
    def send(event: Headless.Event)(using Frame): Unit < Async =
        Promise.initWith[Unit, Any] { done =>
            commands.put(Headless.Command(event, done)).andThen(done.get)
        }.handle(Abort.run[Closed]).unit
end Headless

object Headless:

    /** A user event on the element with the given `id`. */
    enum Event derives CanEqual:
        case Click(id: String)
        case Input(id: String, value: String)
        case KeyDown(id: String, key: String)
        case Checked(id: String, checked: Boolean)

        def id: String
    end Event

    final private case class Command(event: Event, done: Fiber.Promise[Unit, Any])

    /** Mounts `ui` (once) and starts the runner fiber. Both end when the enclosing `Scope` closes. */
    def mount(ui: UI)(using Frame): Headless < (Async & Scope) =
        // The headless runner renders on demand, so it ignores the change notifications.
        val exchange = new UIExchange:
            def onChange(
                region: ReactiveRegion,
                path: Seq[String],
                contentContext: ReactiveRegion.RegionIdentity,
                parentContext: ReactiveRegion.ParentContext,
                previous: Maybe[UI],
                changedUI: UI
            )(using Frame): Unit < Async = ()
        for
            root     <- ReactiveUI.normalize(ui, Seq.empty)
            sub      <- ReactiveUI.subscribe(root, exchange)
            commands <- Channel.init[Command](64)
            headless = new Headless(ui, commands)
            _ <- Fiber.init(Loop.forever(commands.take.map(cmd => dispatch(headless, sub, cmd))))
        yield headless
        end for
    end mount

    private def dispatch(headless: Headless, sub: ReactiveUI.Subscription, cmd: Command)(using Frame): Unit < Async =
        headless.html.map { html =>
            pathOf(html, cmd.event.id) match
                case Absent =>
                    Abort.panic(new NoSuchElementException(s"no element with id '${cmd.event.id}' in:\n$html"))
                case Present(path) =>
                    sub.handle(path, toUIEvent(path, cmd.event)).unit
        }.handle(Abort.run[Nothing]).map(_ => cmd.done.completeUnitDiscard)

    private def toUIEvent(path: Seq[String], event: Event): UIEvent = event match
        case Event.Click(id)       => UIEvent.Click(path, MouseEventData(UI.Modifiers.none, Present(id)))
        case Event.Input(_, value) => UIEvent.Input(path, value)
        case Event.KeyDown(id, k)  => UIEvent.KeyDown(path, KeyboardEventData(k, UI.Modifiers.none, Present(id)))
        case Event.Checked(_, c)   => UIEvent.ChangeChecked(path, c)

    /** The `data-kyo-path` of the element whose `id` attribute is `id`, from kyo-ui's HTML. */
    private[tui4sproto] def pathOf(html: String, id: String): Maybe[Seq[String]] =
        val tag = ("""<[a-z0-9]+ data-kyo-path="([^"]*)"[^>]*\bid="""" + java.util.regex.Pattern.quote(id) + "\"").r
        Maybe.fromOption(tag.findFirstMatchIn(html).map(_.group(1).split('.').toSeq.filter(_.nonEmpty)))

    /** A small HTML-to-text reduction for assertions. */
    def toText(html: String): String =
        val steps: List[String => String] = List(
            _.replaceAll("""<input[^>]*type="checkbox"[^>]*\bchecked\b[^>]*>""", "[x]"),
            _.replaceAll("""<input[^>]*type="checkbox"[^>]*>""", "[ ]"),
            _.replaceAll("""<input[^>]*\bvalue="([^"]*)"[^>]*>""", "[$1]"),
            _.replaceAll("""<input[^>]*>""", "[]"),
            _.replaceAll("""<button[^>]*>""", "("),
            _.replaceAll("""</button>""", ")"),
            _.replaceAll("""</(div|p|h1|h2|li|ul|section)>""", "\n"),
            _.replaceAll("""<[^>]+>""", ""),
            _.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&"),
            _.linesIterator.map(_.trim).filter(_.nonEmpty).mkString("\n")
        )
        steps.foldLeft(html)((acc, f) => f(acc))
    end toText
end Headless
