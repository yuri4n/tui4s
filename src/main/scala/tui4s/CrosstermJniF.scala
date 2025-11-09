package tui4s

import cats.effect.{Async, Sync, Temporal}
import cats.syntax.all.given
import fs2.Stream
import tui.crossterm
import tui.crossterm.{Command, CrosstermJni, Xy}

import java.time.Duration
import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.given
import scala.jdk.CollectionConverters.*

final case class CrosstermJniF[F[_]](private val jni: CrosstermJni):

  def flush(using F: Sync[F]): F[Unit]                             =
    F.delay(jni.flush())
  def execute(command: Command*)(using F: Sync[F]): F[Unit]        =
    execute(command.toList)
  def execute(commands: List[Command])(using F: Sync[F]): F[Unit]  =
    F.delay(jni.execute(commands.asJava))
  def enqueue(commands: Queue[Command])(using F: Sync[F]): F[Unit] =
    F.delay(jni.enqueue(commands.asJava))
  def cursorPosition(using F: Sync[F]): F[Xy]                      =
    F.delay(jni.cursorPosition())
  def terminalSize(using F: Sync[F]): F[Xy]                        =
    F.delay(jni.terminalSize())
  def enableRawMode(using F: Async[F]): F[Unit]                    =
    F.blocking(jni.enableRawMode())
  def disableRawMode(using F: Async[F]): F[Unit]                   =
    F.blocking(jni.disableRawMode())
  def read(using F: Async[F]): F[crossterm.Event | Null]           =
    F.blocking(jni.read())
  def poll(timeout: FiniteDuration)(using F: Async[F]): F[Boolean] =
    val javaDuration = Duration.ofMillis(timeout.toMillis)
    F.blocking(
      jni.poll(
        crossterm.Duration(javaDuration.toSeconds(), javaDuration.getNano()),
      ),
    )

  def events(
      by: FiniteDuration = 100.millis,
  )(using A: Async[F], T: Temporal[F]): Stream[F, crossterm.Event] =
    Stream
      .repeatEval[F, crossterm.Event | Null](poll(by) >> read)
      .collect:
        case e if e != null => e
end CrosstermJniF

object CrosstermJniF:
// def init[F[_]](using F: Sync[F]): F[CrosstermJniF[F]] =

end CrosstermJniF
