package tui4s.interfaces

import cats.Monad
import cats.data.StateT
import cats.effect.Ref
import cats.syntax.all.given
import tui.crossterm.Event
import tui.{Rect, crossterm}
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
      lastEvent: crossterm.Event,
  ): F[Unit]

abstract class ComponentElement[F[_]: Monad] extends Element[F]:
  type W <: Widget[F]

  def widget: W
  def render(
      buffer: BufferF[F],
      area: Rect,
      lastEvent: crossterm.Event,
  ): F[Unit] = 
    widget.createElement.flatMap(_.render(buffer, area, lastEvent))

abstract class StatelessElement[F[_]: Monad] extends ComponentElement[F]:
  type W = StatelessWidget[F]
  def widget: StatelessWidget[F]

object StatelessElement:
  def apply[F[_]: Monad](widget: StatelessWidget[F]): StatelessElement[F] =
    val _widget = widget
    new StatelessElement[F] {
    def widget: StatelessWidget[F] = _widget
    }


  def fromElement[F[_]: Monad](statelessElement: StatelessElement[F]): StatelessWidget[F] =
    new StatelessWidget[F]:
      override def createElement: F[WElement] = statelessElement.pure
      def build(buildContext: BuildContext[F]): F[Widget[F]] =
        statelessElement.widget.build(buildContext)
  // def apply[F[_]](statelessWidget: StatelessWidget[F]) = ???
  // def fromWidget[F[_]: Sync](widget: Widget[F]): StatelessElement[F] =
  //   new ComponentElement[F](widget).asInstanceOf[StatelessElement[F]]
  // def fromWidget[F[_]: Sync](widget: Widget[F]): F[StatelessElement[F]] =
  //   new ComponentElement[F](widget).widen

// NOTE: StatefulElement will be introduced later when we formalize StatefulWidget rendering.
// For now it's intentionally omitted to keep the API compiling and focused on Stateless widgets.

class StatefulElement[F[_]: Monad](val widget: StatefulWidget[F], val stateWidget: widget.State, val currentStateRef: Ref[F, widget.TState]) extends ComponentElement[F]:
  type W = StatefulWidget[F]
  override def render(buffer: BufferF[F], area: Rect, lastEvent: crossterm.Event): F[Unit] =
    for 
      currentState <- currentStateRef.get
      (resultState, resultWidget) <- stateWidget.build(BuildContext(buffer, area, lastEvent)).run(currentState)
      resultElement <- resultWidget.createElement
      _ <- resultElement.render(buffer, area, lastEvent)
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
  lastEvent: crossterm.Event,
)

sealed abstract class Widget[F[_]]:
  type WElement <: Element[F]
  def createElement: F[WElement]

abstract class StatelessWidget[F[_]: Monad] extends Widget[F]:
  type WElement = StatelessElement[F]
  override def createElement: F[WElement] = StatelessElement[F](this).pure

  def build(buildContext: BuildContext[F]): F[Widget[F]]

object StatelessWidget:
  def fromStatelessElement[F[_]: Monad](element: StatelessElement[F]): StatelessWidget[F] =
    new StatelessWidget[F]:
      override def createElement: F[WElement] = element.pure
      def build(buildContext: BuildContext[F]): F[Widget[F]] = this.pure
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

  override def createElement: F[WElement] =
    val self = this
    (new StatelessElement[F]:
      def widget: StatelessWidget[F] = self
      override def render(
          buffer: BufferF[F],
          area: Rect,
          lastEvent: crossterm.Event,
      ): F[Unit] = element.render(buffer, area, lastEvent)
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


// type A[F[_]] = Pull[F, Nothing, Nothing]

// would be better if we ask the user to provide something like this:
// type B[F[_]] = Pipe[F, Frame[F], Widget[F]]

// no...

// something like
