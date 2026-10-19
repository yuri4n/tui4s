package examples.assistant

import kyo.*
import org.scalajs.dom
import tui4s.use.{LocalStorage, Storage}

/** The interpreters of the browser: the storage of the app (the `localStorage`
  * of the browser, with the prefix [[prefix]]), the keys that the person types
  * ([[BrowserKeys]]), the real providers on `fetch` ([[FetchHttp]]), and the
  * log of the app (in memory, and each line to `console.debug`). The web main
  * gives them to the shared app as Kyo layers ([[Wiring]]).
  */
// claude 2026-10-19: created (L4)
object AssistantWeb:
    /** The prefix of the entries of the app in `localStorage`. */
    val prefix: String = "tui4s-assistant/"

    /** The storage of the app. */
    def storage(using Frame): Layer[Storage, Sync] =
      Layer(Sync.defer(LocalStorage(prefix): Storage))

    /** The keys on the storage of the app; a change reloads the page. */
    def keys(using Frame): BrowserKeys =
      BrowserKeys(
        LocalStorage(prefix),
        Sync.defer(dom.window.location.reload()),
      )

    /** The wire of Anthropic from a page: with [[Anthropic.browserAccess]]. */
    val anthropic: Wire =
      Wire.withHeaders(Anthropic, Chunk(Anthropic.browserAccess))

    /** The provider `p` with its key, on the `fetch` of the browser. */
    def live(p: ProviderChoice, key: ApiKey): ChatProvider = p match
        case ProviderChoice.Anthropic =>
          SseProvider.make(anthropic, key, FetchHttp.seam())
        case ProviderChoice.OpenAI    =>
          SseProvider.make(OpenAI, key, FetchHttp.seam())
        case ProviderChoice.Fake      => FakeProvider.make()

    /** The log of the app: [[AppLog.make]], and each line also to
      * `console.debug` (the developer tools of the browser).
      */
    def log(using Frame): Layer[AppLog, Sync] =
      Layer(AppLog.make().map(console))

    /** `base`, and each line also to `console.debug`. */
    def console(base: AppLog)(using Frame): AppLog = new AppLog:
        val lines: Signal[Chunk[String]] = base.lines

        def info(message: String)(using Frame): Unit < Sync =
          base.info(message).andThen(debug(s"info $message"))

        def warn(message: String)(using Frame): Unit < Sync =
          base.warn(message).andThen(debug(s"warn $message"))

        private def debug(line: String): Unit < Sync =
          Sync.defer(dom.console.debug(line))
end AssistantWeb
