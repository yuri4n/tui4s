package kyo.framespike

import kyo.*

/** Isolated diagnostic experiment, not a library namespace or product API.
  * The kyo package exercises its Frame derivation restriction using public APIs only.
  */
object Contextual:
    def set[A](ref: SignalRef[A], value: A)(using frame: Frame): Frame < Async =
      ref.set(value).andThen(frame)

    def invoke[E](handler: E => Unit < Async, event: E)(using frame: Frame): Frame < Async =
      Sync.defer(handler(event)).andThen(frame)
end Contextual

/** Inline facade without an exposed contextual parameter or Env requirement. */
object InlineContextual:
    inline def set[A](ref: SignalRef[A], value: A): Frame < Async =
      val frame = summon[Frame]
      ref.set(value)(using frame).andThen(frame)(using frame)

    inline def view(label: String): UI < Sync =
      new CapturedView(label).ui
end InlineContextual

/** Env itself needs a Frame before it can retrieve the requested Frame. */
object Environmental:
    def set[A](ref: SignalRef[A], value: A)(using Frame): Frame < (Async & Env[Frame]) =
      Env.get[Frame].map { frame =>
        ref.set(value)(using frame).andThen(frame)(using frame)
      }

    def invoke[E](handler: E => Unit < Async, event: E)(using Frame): Frame < (Async & Env[Frame]) =
      Env.get[Frame].map { frame =>
        Sync.defer(handler(event))(using frame).andThen(frame)(using frame)
      }

    def view(label: String)(using Frame): UI < (Sync & Env[Frame]) =
      Env.get[Frame].map { frame =>
        new CapturedView(using frame)(label).ui
      }
end Environmental

/** Inline expansion can obtain the operation's bootstrap Frame at an app call site. */
object InlineEnvironmental:
    inline def set[A](ref: SignalRef[A], value: A): Frame < (Async & Env[Frame]) =
      Env.get[Frame].map { frame =>
        ref.set(value)(using frame).andThen(frame)(using frame)
      }

    inline def view(label: String): UI < (Sync & Env[Frame]) =
      Env.get[Frame].map { frame =>
        new CapturedView(using frame)(label).ui
      }
end InlineEnvironmental

/** Minimal declaration that retains its origin when constructing native reactive UI. */
final class CapturedView(using val origin: Frame)(label: String):
    def ui: UI < Sync = Signal.initRef(label).map(value => UI.span(value))
end CapturedView
