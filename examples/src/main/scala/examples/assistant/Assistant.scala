package examples.assistant

import kyo.*
import tui4s.{Tui, TuiApp, TuiOptions}

/** The assistant in the real terminal: a chat with a language model. This file
  * only wires: it gives the shared app (the module `modules/assistant`) the
  * interpreters of the JVM as Kyo layers, and the size of the terminal, then
  * runs its UI.
  *
  * The provider comes from the stored settings when it has a key, else from the
  * environment (`ANTHROPIC_API_KEY`, then `OPENAI_API_KEY`, or the key file of
  * the app), else it is the fake provider. With `--fake`, the app reads no key
  * and takes the fake provider. The conversations and the settings are files in
  * the data directory of the app (`AssistantJvm.dataDir`), and so is the log of
  * the run. Start it with its start script:
  * {{{
  * sbt examples/launchers
  * examples/target/bin/assistant          # a real provider, when a key is set
  * examples/target/bin/assistant --fake   # the scripted provider: no network
  * }}}
  */
// claude 2026-10-19: created (L3)
object AssistantApp extends TuiApp:
    /** The colours of the looks of the app (the settings switch them). */
    // claude 2026-10-19: changed: the looks (L4)
    override def stylesheet: Stylesheet = Look.palette

    /** The log of the run goes to `assistant.log` in the data directory of the
      * app, so no warning comes on the terminal after the run.
      */
    override def options: TuiOptions =
      TuiOptions(logFile = AssistantJvm.logFile)

    // claude 2026-10-19: changed: the surface of the terminal (L4)
    // region: main
    def ui: UI < (Async & Scope) =
        val mode =
          if args.contains("--fake") then StartMode.FakeOnly
          else StartMode.Detect
        Tui.size.map(size =>
          Memo.run(
            Env.runLayer(
              AssistantJvm.storage,
              AssistantJvm.secrets,
              Wiring.conversations,
              Wiring.log,
              Wiring.providers(AssistantJvm.live, FakeProvider.make(), mode),
              Layer(Surface.Terminal),
            )(
              AssistantUi.run(
                mode,
                Tui.quit,
                size.map(t => ScreenSize(t.width, t.height)),
              ),
            ),
          ),
        )
    end ui
    // endregion: main
end AssistantApp
