package framespike.fail

import kyo.*
import kyo.UI.*
import kyo.framespike.Environmental

object EnvUiCallback:
    def view(ref: SignalRef[Int]): UI =
      button("write").onClick(Environmental.set(ref, 1))

    def event: UI =
      val handler: Int => Unit < Async = _ => Kyo.unit
      button("invoke").onClick(Environmental.invoke(handler, 1))
