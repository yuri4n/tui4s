package tui4s.interfaces

import cats.Monad
import cats.data.StateT
import cats.effect.Ref
import cats.effect.kernel.Sync
import cats.syntax.all.given
import fs2.concurrent.Signal
import tui.crossterm.Event
import tui.internal.saturating.given
import tui.widgets.BlockWidget
import tui.{Alignment, Borders, Rect, Spans, Style, crossterm}
import tui4s.BufferF

// I think I need a writer for writing to terminal.

// Stream of terminal events.

// Better to give it to the user as an argument.
// and as a signal.

// type UI[F[_]] = Signal[F, Event] => StateT[F, S, Widget[F, S]]

// or in the case of a stateless widget...
// type UI[F[_]] = Signal[F, Event] => F[Widget[F]]

// but I need a place where to update the state.

// type ?? = Signal[F, Event] => StateT[F, S, Unit]

// in that case, should I really want to give the ui the signal and the posibility to update the state?

// type UI[F[_]] = S => F[Widget[F, S]]


sealed abstract class Element[F[_]]:
  def render(
      buffer: BufferF[F],
      area: Rect,
      eventSignal: Signal[F, Option[crossterm.Event]],
  ): F[Unit]

final case class TextElement[F[_]: Sync](text: String, style: Style)
    extends Element[F]:
  def render(
      buffer: BufferF[F],
      area: Rect,
      eventSignal: Signal[F, Option[crossterm.Event]],
  ): F[Unit] =
    buffer.setString(area.x, area.y, text, style).void

final case class ContainerElement[F[_]: {Sync}](        
        child: Element[F],
        title: Option[Spans] = None,
        titleAlignment: Alignment = Alignment.Left,
        borders: Borders = Borders.NONE,
        borderStyle: Style = Style.DEFAULT,
        borderType: BlockWidget.BorderType = BlockWidget.BorderType.Plain,
        style: Style = Style.DEFAULT,
) extends Element[F]:

  private def inner(area: Rect): Rect =
    var inner = area
    if borders.intersects(Borders.LEFT) then
      inner = inner.copy(
        x = inner.x.saturating_add(1).min(inner.right),
        width = inner.width.saturating_sub_unsigned(1),
      )
    if borders.intersects(Borders.TOP) || title.isDefined then
      inner = inner.copy(
        y = inner.y.saturating_add(1).min(inner.bottom),
        height = inner.height.saturating_sub_unsigned(1),
      )
    if borders.intersects(Borders.RIGHT) then
      inner = inner.copy(width = inner.width.saturating_sub_unsigned(1))
    if borders.intersects(Borders.BOTTOM) then
      inner = inner.copy(height = inner.height.saturating_sub_unsigned(1))
    inner
  end inner

  def render(
      buffer: BufferF[F],
      area: Rect,
      eventSignal: Signal[F, Option[crossterm.Event]],
  ): F[Unit] = 
    Sync[F].whenA(area.area > 0):
      for
        _      <- buffer.setStyle(area, style)
        symbols = BlockWidget.BorderType.lineSymbols(borderType)

        // Sides
        _ <- Sync[F].whenA(borders.intersects(Borders.LEFT)):
                (area.top until area.bottom).toList.traverse_ { y =>
                  buffer.setCell(
                    area.left,
                    y,
                    tui.Cell.default
                      .setSymbol(symbols.vertical)
                      .setStyle(borderStyle),
                  )
                }

        _ <- Sync[F].whenA(borders.intersects(Borders.TOP)):
                (area.left until area.right).toList.traverse_ { x =>
                  buffer.setCell(
                    x,
                    area.top,
                    tui.Cell.default
                      .setSymbol(symbols.horizontal)
                      .setStyle(borderStyle),
                  )
                }

        _ <- Sync[F].whenA(borders.intersects(Borders.RIGHT)):
                val x = area.right - 1
                (area.top until area.bottom).toList.traverse_ { y =>
                  buffer.setCell(
                    x,
                    y,
                    tui.Cell.default
                      .setSymbol(symbols.vertical)
                      .setStyle(borderStyle),
                  )
                }

        _ <- Sync[F].whenA(borders.intersects(Borders.BOTTOM)):
                val y = area.bottom - 1
                (area.left until area.right).toList.traverse_ { x =>
                  buffer.setCell(
                    x,
                    y,
                    tui.Cell.default
                      .setSymbol(symbols.horizontal)
                      .setStyle(borderStyle),
                  )
                }

        // Corners
        _ <-
          Sync[F].whenA(borders.contains(Borders.RIGHT | Borders.BOTTOM)):
            buffer.setCell(
              area.right - 1,
              area.bottom - 1,
              tui.Cell.default
                .setSymbol(symbols.bottomRight)
                .setStyle(borderStyle),
            )

        _ <- Sync[F].whenA(borders.contains(Borders.RIGHT | Borders.TOP)):
                buffer.setCell(
                  area.right - 1,
                  area.top,
                  tui.Cell.default
                    .setSymbol(symbols.topRight)
                    .setStyle(borderStyle),
                )

        _ <- Sync[F].whenA(borders.contains(Borders.LEFT | Borders.BOTTOM)):
                buffer.setCell(
                  area.left,
                  area.bottom - 1,
                  tui.Cell.default
                    .setSymbol(symbols.bottomLeft)
                    .setStyle(borderStyle),
                )

        _ <- Sync[F].whenA(borders.contains(Borders.LEFT | Borders.TOP)):
                buffer.setCell(
                  area.left,
                  area.top,
                  tui.Cell.default
                    .setSymbol(symbols.topLeft)
                    .setStyle(borderStyle),
                )

        // Title
        _ <- title.fold(Sync[F].unit) { title =>
                val leftBorderDx   =
                  if borders.intersects(Borders.LEFT) then 1 else 0
                val rightBorderDx  =
                  if borders.intersects(Borders.RIGHT) then 1 else 0
                val titleAreaWidth =
                  area.width - leftBorderDx - rightBorderDx

                val titleDx = titleAlignment match
                  case Alignment.Left   => leftBorderDx
                  case Alignment.Center => (area.width - title.width) / 2
                  case Alignment.Right  =>
                    area.width - title.width - rightBorderDx

                val titleX = area.left + titleDx
                val titleY = area.top

                buffer.setSpans(titleX, titleY, title, titleAreaWidth).void
              }

        innerRect = inner(area)
        _        <- child.render(buffer, innerRect, eventSignal)
      yield ()

abstract class ComponentElement[F[_]: Monad] extends Element[F]:
  type W <: Widget[F]

  def widget: W
  def render(
      buffer: BufferF[F],
      area: Rect,
      eventSignal: Signal[F, Option[crossterm.Event]],
  ): F[Unit] = 
    widget.createElement.flatMap(_.render(buffer, area, eventSignal))

class StatelessElement[F[_]: Monad](val widget: StatelessWidget[F]) extends ComponentElement[F]:
  type W = StatelessWidget[F]

  override def render(
      buffer: BufferF[F],
      area: Rect,
      eventSignal: Signal[F, Option[crossterm.Event]],
  ): F[Unit] =
    widget.build(BuildContext(buffer, area, eventSignal)).void

object StatelessElement:
  def fromElement[F[_]: Monad, E >: Element[F] <: Element[F]](element: Element[F], widget: StatelessWidget[F]): StatelessElement[F] =
    new StatelessElement[F](widget):
      override def render(
          buffer: BufferF[F],
          area: Rect,
          eventSignal: Signal[F, Option[crossterm.Event]],
      ): F[Unit] = element.render(buffer, area, eventSignal)
  // def apply[F[_]](statelessWidget: StatelessWidget[F]) = ???
  // def fromWidget[F[_]: Sync](widget: Widget[F]): StatelessElement[F] =
  //   new ComponentElement[F](widget).asInstanceOf[StatelessElement[F]]
  // def fromWidget[F[_]: Sync](widget: Widget[F]): F[StatelessElement[F]] =
  //   new ComponentElement[F](widget).widen

// NOTE: StatefulElement will be introduced later when we formalize StatefulWidget rendering.
// For now it's intentionally omitted to keep the API compiling and focused on Stateless widgets.

class StatefulElement[F[_]: Monad](val widget: StatefulWidget[F], val stateWidget: widget.State, val currentStateRef: Ref[F, widget.TState]) extends ComponentElement[F]:
  type W = StatefulWidget[F]
  override def render(buffer: BufferF[F], area: Rect, eventSignal: Signal[F, Option[Event]]): F[Unit] =
    for 
      currentState <- currentStateRef.get
      (resultState, resultWidget) <- stateWidget.build(BuildContext(buffer, area, eventSignal)).run(currentState)
      resultElement <- resultWidget.createElement
      _ <- resultElement.render(buffer, area, eventSignal)
      _ <- currentStateRef.set(resultState)
    yield ()

object StatefulElement:
  def of[F[_]: {Ref.Make, Monad}](widget: StatefulWidget[F]): F[StatefulElement[F]] = 
    for
      stateWidget <- widget.createState
      currentStateRef <- Ref.of(stateWidget.initialState)
    yield StatefulElement(widget, stateWidget, currentStateRef)



final case class BuildContext[F[_]](
  buffer: BufferF[F],
  area: Rect,
  eventSignal: Signal[F, Option[tui.crossterm.Event]],
)

sealed abstract class Widget[F[_]]:
  type WElement <: Element[F]
  def createElement: F[WElement]

abstract class StatelessWidget[F[_]: Monad] extends Widget[F]:
  type WElement = StatelessElement[F]
  override def createElement: F[WElement] = new StatelessElement(this).pure

  def build(buildContext: BuildContext[F]): F[Widget[F]]

object StatelessWidget:
  // def fromElement[F[_]: Monad](element: Element[F]): StatelessWidget[F] =
  //   new StatelessWidget[F]:
  //     override def createElement: F[WElement] = StatelessElement.fromElement(element, this)
  //     def build(buildContext: BuildContext[F]): F[Widget[F]] = 
  //       element.render(buildContext.buffer, buildContext.area, buildContext.eventSignal).as(this)

  def of[F[_]: Monad](
      buildFunc: BuildContext[F] => F[Widget[F]],
  ): StatelessWidget[F] =
    new StatelessWidget[F]:
      def build(buildContext: BuildContext[F]): F[Widget[F]] =
        buildFunc(buildContext)

  def of[F[_]: Monad](
      buildFunc: => F[Widget[F]],
  ): StatelessWidget[F] =
    new StatelessWidget[F]:
      def build(buildContext: BuildContext[F]): F[Widget[F]] =
        buildFunc


class StatelessElementWidget[F[_]: Monad](element: => Element[F]) extends StatelessWidget[F]: 

  override def createElement: F[WElement] = (new StatelessElement[F](this):
    override def render(
        buffer: BufferF[F],
        area: Rect,
        eventSignal: Signal[F, Option[crossterm.Event]],
    ): F[Unit] = element.render(buffer, area, eventSignal)
  ).pure

  def build(buildContext: BuildContext[F]): F[Widget[F]] = this.pure[F]


// class StatefulElementWidget[F[_]: {Monad, Ref.Make}] extends StatefulWid


abstract class StatefulWidget[F[_]: {Monad, Ref.Make}] extends Widget[F]:
  type WElement = StatefulElement[F]
  def createElement: F[WElement] = StatefulElement.of(this)

  type TState
  def createState: F[this.State]

  abstract class State(val initialState: TState):
    // def processEvents(eventSignal: Signal[F, Option[crossterm.Event]]): StateT[F, S, Unit]

    def build(buildContext: BuildContext[F]): StateT[F, TState, Widget[F]]

// object StatefulWidget:
  // SW is the concrete widget type (not a type constructor)

trait widgets:

  final case class Tabs[F[_]: {Monad, Ref.Make, Sync}](
      tabs: Array[Tabs.Tab[F]],
      selected: Int = 0,
  ) extends StatefulWidget[F]:
    private lazy val tabsLength = tabs.length

    type TState = Int

    final case class TabsState(initialSelected: Int, tabsLength: Int) extends this.State(initialSelected):

      def build(buildContext: BuildContext[F]): StateT[F, Int, Widget[F]] =       
        StateT: current =>
          val getNewCurrent =
            buildContext.eventSignal.get.map:
              case Some(key: crossterm.Event.Key) =>
                key.keyEvent.code match
                  case _: tui.crossterm.KeyCode.Right => current + 1 % tabsLength
                  case _: tui.crossterm.KeyCode.Left  =>
                    val currentMinusOne = current - 1
                    if currentMinusOne < 0 then tabsLength - 1
                    else currentMinusOne % tabsLength
                  case _                              => current

              case _ => current

          for newCurrent <- getNewCurrent
            // _          <- tabs(newCurrent).child.render(buffer, area)
          yield (newCurrent, Text(s"$newCurrent"))

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
      override def createElement: F[StatelessElement[F]] = child.createElement.map { childElement =>
        StatelessElement.fromElement(ContainerElement(
          child = childElement,
          title = title,
          titleAlignment = titleAlignment,
          borders = borders,
          borderStyle = borderStyle,
          borderType = borderType,
          style = style,
        ),this
        )
      }

      def build(buildContext: BuildContext[F]): F[Widget[F]] = this.pure
end widgets

object widgets extends widgets
