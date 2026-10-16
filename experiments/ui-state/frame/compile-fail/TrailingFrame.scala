package kyo.framespike.fail

import kyo.*

final class TrailingFrame(heading: UI = UI.empty)(using Frame):
    def view: UI = heading
