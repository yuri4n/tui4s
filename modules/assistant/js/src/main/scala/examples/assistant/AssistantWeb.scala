package examples.assistant

import kyo.*
import org.scalajs.dom
import tui4s.use.{LocalStorage, Storage}

/** The interpreters of the browser: the start mode of the page ([[mode]]), the
  * storage of the app (the `localStorage` of the browser, with the prefix
  * [[prefix]]), the keys that the person types ([[BrowserKeys]]), the real
  * providers on `fetch` ([[FetchHttp]]), and the log of the app (in memory, and
  * each line to `console.debug`). The web main gives them to the shared app as
  * Kyo layers ([[Wiring]]).
  */
// claude 2026-10-19: created (L4); 2026-10-20 changed: the start mode, and
// the storage in memory of the demo (L5 review)
object AssistantWeb:
    /** The prefix of the entries of the app in `localStorage`. */
    val prefix: String = "tui4s-assistant/"

    /** The start mode of the page: [[StartMode.FakeOnly]] when its address has
      * the parameter `demo` (`index.html?demo`, the demo of the docs site),
      * else [[StartMode.Detect]].
      */
    def mode(using Frame): StartMode < Sync =
      Sync.defer(
        if dom.URLSearchParams(dom.window.location.search).has("demo") then
            StartMode.FakeOnly
        else StartMode.Detect,
      )

    /** The storage of the app for `mode`: with [[StartMode.Detect]], the
      * `localStorage` of the browser; with [[StartMode.FakeOnly]], a storage in
      * memory, so the page keeps nothing in the storage of its site (a key
      * typed into the demo is gone at the next load).
      */
    def storage(mode: StartMode)(using Frame): Storage < Sync = mode match
        case StartMode.Detect   => Sync.defer(LocalStorage(prefix): Storage)
        case StartMode.FakeOnly => Storage.inMemory

    /** The keys on `storage`, the storage of the app; a change reloads the
      * page.
      */
    def keys(storage: Storage)(using Frame): BrowserKeys =
      BrowserKeys(storage, Sync.defer(dom.window.location.reload()))

    /** The surface of this browser: `keys`, and the system of the browser
      * ([[Surface.Os.of]] its `navigator.platform`), for the chords.
      */
    // claude 2026-10-19: created (L4 review)
    def surface(keys: KeyVault)(using Frame): Layer[Surface, Sync] =
        val os = Sync.defer(Surface.Os.of(dom.window.navigator.platform))
        Layer(os.map(o => Surface.Browser(keys, o): Surface))

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
