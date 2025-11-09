package tui4s

import tui.Rect

final case class CompletedFrameF[F[_]](
    buffer: BufferF[F],
    area: Rect,
)
