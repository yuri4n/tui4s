package tui4s

import cats.data.StateT
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import tui.widgets.BlockWidget
import tui.{Alignment, Borders, Spans, Style, crossterm}
import tui4s.elements.*
import tui4s.interfaces.*

trait widgets:

  final case class Tabs[F[_]: {Ref.Make, Sync}](
      tabs: Array[Tabs.Tab[F]],
      selected: Int = 0,
  ) extends StatefulWidget[F]:
    private lazy val tabsLength = tabs.length

    type TState = Int

    final case class TabsState(initialSelected: Int, tabsLength: Int) extends this.State(initialSelected):

      def build(buildContext: BuildContext[F]): StateT[F, Int, Widget[F]] =       
        StateT: current =>
          val newCurrent =
            buildContext.lastEvent match
              case key: crossterm.Event.Key =>
                key.keyEvent.code match
                  case _: tui.crossterm.KeyCode.Right => current + 1 % tabsLength
                  case _: tui.crossterm.KeyCode.Left  =>
                    val currentMinusOne = current - 1
                    if currentMinusOne < 0 then tabsLength - 1
                    else currentMinusOne % tabsLength
                  case _                              => current

              case _ => current

          (newCurrent, Text(s"$newCurrent")).pure

    def createState: F[State] = 
      TabsState(selected, tabsLength).pure
  end Tabs

  object Tabs:
    final case class Tab[F[_]]()// title: Spans) // , child: Widget[F])

      
    /* case class TabsWidget( block: Option[BlockWidget] = None, titles:
     * Array[Spans], selected: Int = 0, style: Style = Style.DEFAULT,
     * highlightStyle: Style = Style.DEFAULT, divider: Span =
     * Span.nostyle(symbols.line.VERTICAL) ) extends Widget {
     *
     * def render(area: Rect, buf: Buffer): Unit = { buf.setStyle(area, style)
     * val tabs_area = block match { case Some(b) => val inner_area =
     * b.inner(area) b.render(area, buf) inner_area case None => area }
     *
     * if (tabs_area.height < 1) { return }
     *
     * var x = tabs_area.left val titles_length = titles.length ranges.range(0,
     * titles_length) { i => val title = titles(i) val last_title =
     * titles_length - 1 == i x = x.saturating_add(1) val remaining_width =
     * tabs_area.right.saturating_sub_unsigned(x) if (remaining_width == 0) { ()
     * } else { val pos = buf.setSpans(x, tabs_area.top, title, remaining_width)
     * if (i == selected) { buf.setStyle( Rect( x, y = tabs_area.top, width =
     * pos._1.saturating_sub_unsigned(x), height = 1 ), highlightStyle ) } x =
     * pos._1.saturating_add(1) val remaining_width1 =
     * tabs_area.right.saturating_sub_unsigned(x) if (remaining_width1 == 0 ||
     * last_title) { () } else { val pos = buf.setSpan(x, tabs_area.top,
     * divider, remaining_width1) x = pos._1 } } } } } */


  object Text:
    def apply[F[_]: Sync](
        text: String,
        style: Style = Style.DEFAULT,
    ): StatelessWidget[F] = new StatelessElementWidget[F](
          TextElement(text, style)
        )
      



  object Container:
    def apply [F[_]: Sync](
      child: Widget[F],
      title: Option[Spans] = None,
      titleAlignment: Alignment = Alignment.Left,
      borders: Borders = Borders.NONE,
      borderStyle: Style = Style.DEFAULT,
      borderType: BlockWidget.BorderType = BlockWidget.BorderType.Plain,
      style: Style = Style.DEFAULT,
  ): StatelessWidget[F] = new StatelessWidget[F]:
      override def createElement: F[StatelessElement[F]] =
        child.createElement.map { childElement =>
          ContainerElement(
            child = childElement,
            title = title,
            titleAlignment = titleAlignment,
            borders = borders,
            borderStyle = borderStyle,
            borderType = borderType,
            style = style,
          )
        }

      def build(buildContext: BuildContext[F]): F[Widget[F]] = this.pure

  // object App:
  //   def apply[F[_]: {Monad, Ref.Make, Sync}](
  //       child: Widget[F],
  //   ): StatelessWidget[F] = new StatelessWidget[F]:
  //     override def createElement: F[StatelessElement[F]] = child.createElement.map { childElement =>
  //       StatelessElement.fromElement(AppElement(childElement), this)
  //     }

  //     def build(buildContext: BuildContext[F]): F[Widget[F]] = this.pure
end widgets

object widgets extends widgets
