package examples.assistant

import kyo.*
import tui4s.widgets.Theme

/** The assistant in the browser: the same shared app as the terminal main
  * (`examples.assistant.AssistantApp`), with the interpreters of the browser
  * ([[AssistantWeb]]). This file only wires: it gives them as Kyo layers, puts
  * the stylesheet of the looks into the page, and mounts the UI in `#app`.
  *
  * With no key in the browser, the provider is the fake one (no network). The
  * person types a key in the settings: it stays in the `localStorage` of this
  * browser, and goes only to the host of its provider. Build it with
  * `scripts/web-build.sh`, and serve `dist/assistant` with any static server.
  */
// claude 2026-10-19: created (L4)
object AssistantWebApp extends KyoApp:
    // region: web
    run {
      val keys   = AssistantWeb.keys
      val screen =
        UIWindow.size.map((w, h) => Surface.cells(w, h, Theme.web.font.size))
      for
          _  <- UI.runStylesheet(Look.stylesheet(Look.Web))
          ui <- Memo.run(
                  Env.runLayer(
                    AssistantWeb.storage,
                    Layer(keys: Secrets),
                    Wiring.conversations,
                    AssistantWeb.log,
                    Wiring.providers(
                      AssistantWeb.live,
                      FakeProvider.make(),
                      StartMode.Detect,
                    ),
                    Layer(Surface.Browser(keys)),
                  )(AssistantUi.run(StartMode.Detect, Kyo.unit, screen)),
                )
          _  <- UI.runMount(ui, "#app")
      yield ()
      end for
    }
    // endregion: web
end AssistantWebApp
