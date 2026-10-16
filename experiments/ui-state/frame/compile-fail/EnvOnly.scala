package kyo.framespike.fail

import kyo.*

object EnvOnly:
    def set[A](ref: SignalRef[A], value: A): Unit < (Async & Env[Frame]) =
      Env.get[Frame].map { frame => ref.set(value)(using frame) }
