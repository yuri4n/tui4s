package tui4s.spi

import jatatui.core.backend.Backend
import kyo.*
import tui4s.App
import tui4s.TuiError
import tui4s.TuiHandle
import tui4s.internal.HeadlessControl
import tui4s.internal.Runner

/** The real loop (input thread, supervisor, source fibers, frame pacing) on any
  * jatatui `Backend` and any [[InputPort]], with no raw mode and no alternate
  * screen: for facade tests and benchmarks. A `Resize` event resizes a
  * `TestBackend`.
  *
  * Under `Clock.withTimeControl`, set `TuiOptions.fps` to 0: the frame pacing
  * sleeps, and a controlled clock would hold the next frame back.
  */
object Headless:
    /** Runs `app` until it quits; returns the last state. */
    def run[S](app: App[S], backend: Backend, input: InputPort)(using
        Frame,
    ): S < (Async & Abort[TuiError]) =
      Runner.run(app, HeadlessControl(backend), Some(input))

    /** Starts `app` on its own fiber; the end of the scope stops it. The handle
      * comes after the first frame. When the app fails before its first frame
      * (`init`, `sources` or the first paint threw), there is no state for a
      * handle: the start fails with that [[TuiError]] as a panic.
      */
    def start[S](app: App[S], backend: Backend, input: InputPort)(using
        Frame,
    ): TuiHandle[S] < (Async & Scope) =
      Runner.start(app, HeadlessControl(backend), Some(input))
end Headless
