//> using scala 3.9.0
//> using jar /Users/yuri4n/Developer/projects/tui4s/.work/scratch/design-validate/out/tui4s-core.jar
//> using dep io.getkyo::kyo-core:1.0.0-RC7
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
package facade

export tui4s.{App, Next, Quit, Key, Event, Resize}

object Use:
  def f(e: Event): Next[Int] = e match
    case Key.Char('q') => Quit
    case Resize(w, _)  => w
    case _             => 0
  val a: App[Int] = App[Int](0, _ => tui4s.dsl.empty, (s, _) => s)
