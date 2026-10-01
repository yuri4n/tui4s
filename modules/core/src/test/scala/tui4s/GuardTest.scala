package tui4s

import kyo.*
import kyo.test.*

/** Compile-time contracts: `NotForeign` (G6), `CallSite`, and the keymap
  * duplicate check (SPEC 6.3 GuardTest).
  */
class GuardTest extends Test[Any]:
    "a ZIO value given to source.task does not compile" in {
      typeCheckFailure(
        """tui4s.Source.For[Int].task(new zio.ZIO[Any, Nothing, Int])((s, _) => s)""",
      )("A Kyo app runs Kyo computations only")
    }

    "the message names the type and the fix" in {
      typeCheckFailure(
        """tui4s.Source.For[Int].task(new zio.ZIO[Any, Nothing, Int])((s, _) => s)""",
      )("In a ZIO app import tui4s.interop.zio.*")
      typeCheckFailure(
        """tui4s.Source.task[Int, zio.ZIO[Any, Nothing, Int]](new zio.ZIO[Any, Nothing, Int])((s, _) => s)""",
      )("source.task got a zio.ZIO")
    }

    "a Future given to poll does not compile" in {
      typeCheckFailure(
        """tui4s.Source.For[Int].poll(1.second)(scala.concurrent.Future.successful(1))((s, _) => s)""",
      )("Async.fromFuture")
    }

    "Kyo computations and plain values compile" in {
      typeCheck("""tui4s.Source.For[Int].task(Kyo.lift(1))((s, a) => s + a)""")
      typeCheck(
        """tui4s.Source.For[Int].task(Abort.run[java.io.IOException](Kyo.lift(1)))((s, _) => s)""",
      )
      typeCheck(
        """tui4s.Source.For[Int].poll(1.second)(Random.nextDouble)((s, _) => s)""",
      )
    }

    "Keymap rejects a key bound twice" in {
      val e = intercept[IllegalArgumentException](
        Keymap[Int](
          Bind[Int]('a', "x")(identity),
          Bind[Int]('a', "y")(identity),
        ),
      )
      assert(e.getMessage.contains("'a'"))
    }

    "CallSite and source labels are the caller's file and line" in {
      val here  = summon[CallSite]
      val label = Source.For[Int].every(1.second)(identity).label
      assert(here.file == "GuardTest.scala" && here.line > 0)
      assert(here.show == s"GuardTest.scala:${here.line}")
      assert(label == s"GuardTest.scala:${here.line + 1}")
    }
end GuardTest
