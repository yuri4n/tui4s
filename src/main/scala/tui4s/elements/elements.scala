package tui4s.elements

import tui.internal.saturating.given
import tui4s.interfaces.*
import cats.effect.Sync
import tui.Style
import tui.crossterm
import tui4s.BufferF
import tui.Rect
import cats.syntax.all.given
import tui.Spans
import tui.Alignment
import tui.Borders
import tui.widgets.BlockWidget

final case class TextElement[F[_]: Sync](text: String, style: Style)
    extends StatelessElement[F]:

  def widget: StatelessWidget[F] = StatelessWidget.fromStatelessElement(this)

  override def render(
      buffer: BufferF[F],
      area: Rect,
      lastEvent: crossterm.Event,
  ): F[Unit] =
    buffer.setString(area.x, area.y, text, style).void
end TextElement

final case class ContainerElement[F[_]: Sync](
    child: Element[F],
    title: Option[Spans] = None,
    titleAlignment: Alignment = Alignment.Left,
    borders: Borders = Borders.NONE,
    borderStyle: Style = Style.DEFAULT,
    borderType: BlockWidget.BorderType = BlockWidget.BorderType.Plain,
    style: Style = Style.DEFAULT,
) extends StatelessElement[F]:

  def widget: StatelessWidget[F] = StatelessWidget.fromStatelessElement(this)

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

  override def render(
      buffer: BufferF[F],
      area: Rect,
      lastEvent: crossterm.Event,
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
        _        <- child.render(buffer, innerRect, lastEvent)
      yield ()
end ContainerElement
