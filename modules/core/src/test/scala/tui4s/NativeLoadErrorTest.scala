package tui4s

import kyo.test.*
import tui4s.internal.NativeCrossterm

/** The error of a native library that does not load (the review thread
  * Crossterm.scala:103 of PR 5): only a JVM that denies native access gives
  * `NativeAccessDenied`, whose message names `--enable-native-access`; a
  * library that cannot be found or linked gives `NativeLoadFailed` with its
  * cause. The library loads in a static initializer, so its error arrives in an
  * `ExceptionInInitializerError`, and a second try gives a
  * `NoClassDefFoundError` (whose cause is the first error).
  */
// claude 2026-10-13: created (T1, the bot fix of the review thread
// Crossterm.scala:103). The leaves name the case by its class name, so that
// they also compile on the code before the fix (the negative control).
// claude 2026-10-13: changed (T1, review R2-3): the leaf of the root cause
class NativeLoadErrorTest extends Test[Any]:

    private def kind(t: Throwable): String = t.getClass.getSimpleName

    private def secondTry(first: Throwable): Throwable =
        val e = NoClassDefFoundError(
          "Could not initialize class tui.crossterm.CrosstermJni",
        )
        val _ = e.initCause(ExceptionInInitializerError(first))
        e

    "a library that cannot be found or linked is NativeLoadFailed, with its cause" in {
      val link     = UnsatisfiedLinkError(
        "Native library libnative-x86_64-linux-crossterm.so cannot be found",
      )
      val platform = RuntimeException(
        "Platform detection does not understand os.name = Plan9",
      )
      val inputs   = Seq[Throwable](
        link,
        ExceptionInInitializerError(link),
        ExceptionInInitializerError(platform),
        secondTry(link),
      )
      val wrong    = inputs.flatMap { in =>
        val out = NativeCrossterm.loadError(in)
        Option.when(
          kind(out) != "NativeLoadFailed" || !(out.getCause eq in) ||
            out.getMessage.contains("--enable-native-access"),
        )(s"$in -> ${kind(out)}: ${out.getMessage}")
      }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    "a JVM that denies native access is NativeAccessDenied, and its message names the flag" in {
      val denied = IllegalCallerException(
        "Illegal native access from: unnamed module @1b6d3586",
      )
      val inputs = Seq[Throwable](
        denied,
        ExceptionInInitializerError(denied),
        secondTry(denied),
      )
      val wrong  = inputs.flatMap { in =>
        val out = NativeCrossterm.loadError(in)
        Option.when(
          !out.isInstanceOf[TuiError.NativeAccessDenied] ||
            !out.getMessage.contains("--enable-native-access=ALL-UNNAMED"),
        )(s"$in -> ${kind(out)}: ${out.getMessage}")
      }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    // claude 2026-10-13: created (T1, review R2-3): the message named only the
    // wrapper (an ExceptionInInitializerError, or the NoClassDefFoundError of
    // a second try), not the error that says what failed
    "the message of NativeLoadFailed names the root cause of a wrapped error" in {
      val platform = RuntimeException(
        "Platform detection does not understand os.name = Plan9",
      )
      val link     = UnsatisfiedLinkError(
        "Native library libnative-x86_64-linux-crossterm.so cannot be found",
      )
      // The shape of a second try on JDK 25: the cause is an
      // ExceptionInInitializerError with the first error's text and no cause.
      val second   = NoClassDefFoundError(
        "Could not initialize class tui.crossterm.CrosstermJni",
      )
      val _        = second.initCause(
        ExceptionInInitializerError(
          "Exception java.lang.UnsatisfiedLinkError: Native library libnative-x86_64-linux-crossterm.so cannot be found [in thread \"main\"]",
        ),
      )
      val cases    = Seq[(Throwable, String)](
        ExceptionInInitializerError(platform) -> "os.name = Plan9",
        secondTry(link)                       -> "crossterm.so cannot be found",
        second                                -> "crossterm.so cannot be found",
        link                                  -> "crossterm.so cannot be found",
      )
      val wrong    = cases.flatMap { (in, text) =>
        val out = NativeCrossterm.loadError(in)
        Option.when(!out.getMessage.contains(text))(
          s"$in -> ${kind(out)}: ${out.getMessage}",
        )
      }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    "a fatal error of the JVM stays as it is" in {
      val oom = OutOfMemoryError("Java heap space")
      assert(NativeCrossterm.loadError(oom) eq oom)
    }
    // claude 2026-10-19: created (P1b, A4)
    "each TuiError keeps its native cause and has no stack trace of its own" in {
      val io     = java.io.IOException("broken pipe")
      val errors = Seq[TuiError](
        TuiError.NotATerminal(io),
        TuiError.NativeAccessDenied(io),
        TuiError.NativeLoadFailed(io),
        TuiError.TerminalIO(io),
      )
      assert(errors.forall(e => (e.getCause eq io) && (TuiError.causeOf(e) eq io)))
      assert(errors.forall(_.getStackTrace.isEmpty))
      assert(io.getStackTrace.nonEmpty)
    }
end NativeLoadErrorTest
