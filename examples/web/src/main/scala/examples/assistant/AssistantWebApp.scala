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
  * browser, and goes only to the host of its provider. With `?demo` in the
  * address (the demo of the docs site), the app reads no key, uses only the
  * fake provider, and keeps its storage in memory ([[AssistantWeb.mode]]).
  * Build it with `scripts/web-build.sh`, and serve `dist/assistant` with any
  * static server.
  */
// claude 2026-10-19: created (L4); changed: the surface of the browser (L4
// review); 2026-10-20 changed: the start mode of the page (L5 review)
object AssistantWebApp extends KyoApp:
    // region: web
    run {
      val screen =
        UIWindow.size.map((w, h) => Surface.cells(w, h, Theme.web.font.size))
      for
          mode    <- AssistantWeb.mode
          storage <- AssistantWeb.storage(mode)
          keys     = AssistantWeb.keys(storage)
          _       <- UI.runStylesheet(Look.stylesheet(Look.Web))
          ui      <- Memo.run(
                       Env.runLayer(
                         Layer(storage),
                         Layer(keys: Secrets),
                         Wiring.conversations,
                         AssistantWeb.log,
                         Wiring.providers(
                           AssistantWeb.live,
                           FakeProvider.make(),
                           mode,
                         ),
                         AssistantWeb.surface(keys),
                       )(AssistantUi.run(mode, Kyo.unit, screen)),
                     )
          _       <- UI.runMount(ui, "#app")
      yield ()
      end for
    }
    // endregion: web
end AssistantWebApp
