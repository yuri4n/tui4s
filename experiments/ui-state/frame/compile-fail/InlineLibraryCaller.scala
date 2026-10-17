package kyo.framespike.fail

import kyo.*
import kyo.framespike.InlineContextual

object InlineLibraryCaller:
    def set(ref: SignalRef[Int]): Frame < Async =
      InlineContextual.set(ref, 1)
