package spike

import kyo.*

final case class CounterState(count: Int) derives CanEqual

/** Composable, deterministic transitions with no shared state or resources. */
object CounterTransitions:
    def increment: Unit < Var[CounterState] =
      Var.updateDiscard[CounterState](s => s.copy(count = s.count + 1))

    def doubled: Int < Var[CounterState] =
      Var.use[CounterState](s => s.count * 2)

    def incrementTwice: Unit < Var[CounterState] =
      increment.andThen(increment)
end CounterTransitions

/** A combined alternative: pure Var transitions atomically commit to one
  * native signal. The action type deliberately excludes Async, Env and IO.
  * AtomicRef may retry the reducer, so it must have no external side effects.
  */
final class ReactiveTransitions(private val ref: SignalRef[CounterState]):
    val count: Signal[Int] = ref.map(_.count)
    val doubled: Signal[Int] = ref.map(_.count * 2)

    def snapshot: CounterState < Sync = ref.get

    def transact(action: Unit < Var[CounterState]): Unit < Sync =
      ref.updateAndGet(current => Var.runTuple(current)(action).eval._1).unit

    def increment: Unit < Sync = transact(CounterTransitions.increment)
end ReactiveTransitions

object ReactiveTransitions:
    def init(initial: Int): ReactiveTransitions < Sync =
      Signal.initRef(CounterState(initial)).map(new ReactiveTransitions(_))
end ReactiveTransitions
