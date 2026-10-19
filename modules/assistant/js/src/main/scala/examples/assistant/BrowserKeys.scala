package examples.assistant

import kyo.*
import tui4s.use.Storage

/** The keys of the web: [[Secrets]] and [[KeyVault]] on a `Storage` of
  * tui4s-use (in the browser: `LocalStorage`, with the prefix of the app). The
  * key of each real provider is one entry ([[BrowserKeys.entry]]); a forgotten
  * key is an empty entry. [[keep]] and [[forget]] run `restart` after the write
  * (the web main gives a reload of the page), so the app makes its providers
  * again.
  *
  * The key never leaves the storage except in the header of a call to the host
  * of its provider; the app never shows it and never logs it ([[ApiKey]]).
  * Anyone with the profile of the browser can read the storage: the settings
  * say so.
  */
// claude 2026-10-19: created (L4)
final class BrowserKeys(storage: Storage, restart: Unit < Sync)
    extends Secrets,
      KeyVault:

    def apiKey(provider: ProviderChoice)(using
        Frame,
    ): Maybe[ApiKey] < (Sync & Abort[SecretsError]) =
      BrowserKeys.entry(provider) match
          case Absent     => Absent
          case Present(e) =>
            storage.get(e).map(_.flatMap(ApiKey.parse(_, KeySource.Browser)))

    def keep(provider: ProviderChoice, key: ApiKey)(using Frame): Unit < Sync =
      write(provider, key.reveal)

    def forget(provider: ProviderChoice)(using Frame): Unit < Sync =
      write(provider, "")

    private def write(provider: ProviderChoice, text: String)(using
        Frame,
    ): Unit < Sync =
      BrowserKeys
        .entry(provider)
        .fold(Kyo.unit)(e => storage.set(e, text).andThen(restart))
end BrowserKeys

/** The entries of the keys. */
// claude 2026-10-19: created (L4)
object BrowserKeys:
    /** The entry of the key of `p` in the storage; Absent for the fake. */
    def entry(p: ProviderChoice): Maybe[String] = p match
        case ProviderChoice.Anthropic => Present("key-anthropic")
        case ProviderChoice.OpenAI    => Present("key-openai")
        case ProviderChoice.Fake      => Absent
