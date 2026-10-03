package examples.counter

import tui4s.*

/** SPEC 3.1: "A plain match is equally valid when an app has no help bar". */
object CounterMatchApp extends TuiApp[Int]:
  def init: Int = 0

  def update(n: Int, event: Event): Next[Int] = event match
    case Key.Char('+') => n + 1
    case Key.Char('-') => n - 1
    case Key.Char('q') => Quit
    case _             => n

  def view(n: Int): View = text("Count: ", n.toString.bold.yellow).centered
