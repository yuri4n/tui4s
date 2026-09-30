import cats.effect.IO
import weaver.SimpleIOSuite

object MySuite extends SimpleIOSuite:
  test("xd"):
    IO(success)
