package examples.assistant

import kyo.*
import tui4s.use.{FileStorage, Storage}

import java.nio.file.Path as JPath

/** The interpreters of the JVM: the storage of the app (files in its data
  * directory), the keys ([[SecretsJvm]]) and the real providers on the HTTP
  * client of kyo-http ([[KyoHttp]]). The terminal main gives them to the shared
  * app as Kyo layers ([[Wiring]]).
  */
// claude 2026-10-19: created (L3)
object AssistantJvm:
    /** The data directory of the app: kyo-system's local data directory of the
      * platform, then `tui4s/assistant` (`~/.local/share/tui4s/assistant` on
      * Linux). One file per conversation, and one for the settings; the files
      * are for their owner only.
      */
    def dataDir: JPath = FileStorage.appDir("tui4s").resolve("assistant").nn

    /** The storage of the app: [[dataDir]]. */
    def storage(using Frame): Layer[Storage, Sync] =
      Layer(Sync.defer(FileStorage(dataDir): Storage))

    /** The keys: the environment, then the key file of the app. */
    def secrets(using Frame): Layer[Secrets, Any] =
      Layer(SecretsJvm.make(name => SecretsJvm.environment(name)))

    /** The provider `p` with its key, on the client of kyo-http. */
    def live(p: ProviderChoice, key: ApiKey): ChatProvider = p match
        case ProviderChoice.Anthropic =>
          SseProvider.make(Anthropic, key, KyoHttp.seam())
        case ProviderChoice.OpenAI    =>
          SseProvider.make(OpenAI, key, KyoHttp.seam())
        case ProviderChoice.Fake      => FakeProvider.make()
end AssistantJvm
