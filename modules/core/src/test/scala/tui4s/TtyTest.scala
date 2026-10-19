package tui4s

import kyo.test.*
import tui4s.internal.Tty

import java.lang.invoke.{MethodHandle, MethodHandles, MethodType}

/** `Tty.isatty` gives `None` ("cannot be known") when the FFM link or the call
  * of `isatty` fails, also with a `LinkageError`: a GraalVM native image
  * without the reachability metadata of the downcall throws
  * `MissingForeignRegistrationError`, a `LinkageError` that `NonFatal` does not
  * match. Before the fix it went through `Tty`, and the run ended with a stack
  * trace. A fatal error of the JVM still goes through.
  */
// claude 2026-10-19: created (C1, negative control: fails without the catch
// of LinkageError)
class TtyTest extends Test[Any]:
    /** A handle `(int)int` that throws `t`. */
    private def throwing(t: Throwable): MethodHandle =
        val thrower = MethodHandles
          .throwException(classOf[Int], t.getClass)
          .nn
          .bindTo(t)
          .nn
        MethodHandles
          .dropArguments(thrower, 0, classOf[Int])
          .nn
          .asType(MethodType.methodType(classOf[Int], classOf[Int]))
          .nn
    end throwing

    "a link that fails with a LinkageError (no native-image registration) gives None" in {
      val missing =
        new LinkageError("MissingForeignRegistrationError: (int)int")
      assert(Tty.link(() => throw missing).isEmpty)
      assert(Tty.link(() => throw IllegalStateException("no linker")).isEmpty)
    }

    "a call that fails with a LinkageError gives None" in {
      val missing =
        new LinkageError("MissingForeignRegistrationError: (int)int")
      assert(Tty.call(throwing(missing), 0).isEmpty)
      assert(Tty.call(throwing(IllegalStateException("closed")), 0).isEmpty)
    }

    "a fatal error of the JVM goes through" in {
      val through =
        try
            val _ = Tty.link(() => throw StackOverflowError())
            false
        catch case _: StackOverflowError => true
      assert(through)
    }
end TtyTest
