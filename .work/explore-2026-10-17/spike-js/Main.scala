package spikejs

import examples.spikeshared.SharedView
import examples.spikeshared.Store
import kyo.*

/** SPIKE: the Scala.js main. Mounts the shared view into `#app`. */
object Main extends KyoApp:
    run {
      for
          store <- Store.init
          _     <- UI.runMount(SharedView.view(store), "#app")
      yield ()
    }
end Main
