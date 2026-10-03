//> using scala 3.9.0
final class Box[S]
object A:
  def f[S](init: S, view: S => String, g: S => Seq[Box[S]] = (_: S) => Nil, o: Int = 1): Int = 0
  def h[S](init: S, view: S => String, g: S => Seq[Box[S]] = (_: S) => Seq.empty[Box[S]], o: Int = 1): Int = 0
object Use:
  final case class S(n: Int)
  val a = A.f[Int](1, _.toString)
  val b = A.f(1, (_: Int).toString)
  val c = A.f[S](init = S(0), view = _.toString)
  val d = A.f[S](S(0), _.toString)
  val e = A.h[S](init = S(0), view = _.toString)
  val g = A.h[Int](1, _.toString)
