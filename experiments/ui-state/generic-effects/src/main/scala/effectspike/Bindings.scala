package effectspike

import kyo.*

/** Compatibility aliases keep ordinary component prop annotations unchanged. */
type Emits[-E] = EffectEmits[E, Async]
type Model[A] = EffectModel[A, Async]

/** A handler retains its actual required effects until a UI boundary. */
final class EffectEmits[-E, -S] private[effectspike] (handler: E => Unit < S):
    def apply(event: E)(using Frame): Unit < (Sync & S) =
      Sync.defer(handler(event))
end EffectEmits

final class EmitsBuilder[E] private[effectspike] ():
    def apply[S](handler: E => Unit < S): EffectEmits[E, S] =
      new EffectEmits(handler)
end EmitsBuilder

def useEmits[E](handler: E => Unit < Async): Emits[E] = new EffectEmits(handler)

def useEffectEmits[E]: EmitsBuilder[E] = new EmitsBuilder

final class EffectModel[A, -S] private[effectspike] (
    val value: Signal[A],
    write: A => Unit < S,
):
    def set(next: A)(using Frame): Unit < (Sync & S) = Sync.defer(write(next))
end EffectModel

final class ModelBuilder[A] private[effectspike] ():
    def apply[S](value: Signal[A], set: A => Unit < S): EffectModel[A, S] =
      new EffectModel(value, set)

    def apply(ref: SignalRef[A])(using Frame): Model[A] =
      new EffectModel(ref, next => ref.set(next))
end ModelBuilder

object Model:
    def apply[A](value: Signal[A], set: A => Unit < Async): Model[A] =
      new EffectModel(value, set)

    def apply[A](ref: SignalRef[A])(using Frame): Model[A] =
      new EffectModel(ref, next => ref.set(next))

    def effectful[A]: ModelBuilder[A] = new ModelBuilder
end Model
