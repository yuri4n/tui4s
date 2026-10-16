package framespike

import kyo.*
import kyo.framespike.CapturedView

object CapturedCaller:
    def render(widget: CapturedView): (UI, Frame, Frame) < Sync =
      val invocation = summon[Frame]
      widget.ui.map(ui => (ui, widget.origin, invocation))
end CapturedCaller
