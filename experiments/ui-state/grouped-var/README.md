# Var versus reactive stores

This isolated experiment uses pinned public Kyo 1.0.0-RC7 APIs. It does not
implement a second state effect or modify pini4s. Run `sbt test` with JDK 25
from this directory. Validated on 2026-10-15 with Scala 3.9.0 and sbt 1.13.0:
**13 tests passed, 0 failed**. The suite's result count is authoritative;
Kyo's sbt bridge additionally prints a separate zero-test summary.

`Var[S]` is handler-local state for a computation. `SignalRef[S]` is an
observable shared cell. `Scope` supplies cleanup ownership; it is independent
of either choice. Kyo has no public top-level effect named `State` at this pin.

The tests compare deterministic composition and replay from explicit seeds,
discarding an unpublished transition on domain failure, cleanup independent
of the state representation, fresh per-event handlers, and pure Var programs
used as atomic signal reducers. A combined feature group contains reactive
derived values and actions assembled from Var transitions. Its native Layer
provider shares the whole instance across nested consumers and a deferred
Kyo UI callback, then releases its setup resource at owner exit.

A deterministic negative example shows a stale snapshot lost update when an
async action reads, waits, then commits its Var state. This intentionally
expects one increment to be lost: it is evidence against that implementation,
not a proposed guarantee. The combined atomic alternative tests 64 increments
from 32 concurrent two-step transactions and rejects async actions at compile
time.

## Tested combined alternative

```scala
import kyo.*
import spike.*

object CombinedExample:
  val counterLayer: Layer[ReactiveTransitions, Sync] =
    Layer(ReactiveTransitions.init(0))

  def addTwo(store: ReactiveTransitions): Unit < Sync =
    store.transact(CounterTransitions.incrementTwice)

  def program: Int < Async =
    Scope.run(Memo.run(Env.runLayer(counterLayer) {
      Env.use[ReactiveTransitions] { store =>
        addTwo(store).andThen(store.doubled.current)
      }
    }))
```

`ReactiveTransitions` and `CounterTransitions` are compiled experiment code,
not proposed pini4s APIs. The tests exercise this native composition and also
register a setup resource to test provider cleanup.
The provider must encompass actual app execution, not return a resource-owning
UI/callback and close immediately. Capture the resolved store in UI callbacks;
the UI's closed Async boundary does not carry unresolved Var or Env effects.

The pure transition is evaluated inside one native `SignalRef.updateAndGet`.
The signal publishes the final record, so `count` and `doubled` remain native
derived signals. An atomic update may retry its reducer: transitions must be
pure, short, and deterministic. This experiment excludes Async and other
effects from `transact`; it does not claim arbitrary async actions are atomic.

The scoped group test verifies one setup, exact object identity across two
consumers, survival of an inner component scope, a deferred native Kyo button
callback, and one release at owner shutdown. An independent-provider test
reuses the same Layer definition but proves fresh groups and isolated state.
Plain references can still be read after owner exit; Scope releases resources,
and does not invalidate Scala references or impose a region type system.

## Minimal additional library policy worth evaluating

The present registry caches individual string-keyed signals, not a feature
store instance. A useful store definition would cache a whole typed store per
explicit provider, with native signals, derived values and actions as ordinary
members. Its provider lifetime must own resources opened by the constructor.
Eager construction through Layer and Scope already supplies most of this.

Lazy resource construction in a registry is not automatically provider-owned:
the first caller may be inside a component's shorter Scope. Caching that result
does not move its finalizers into the parent provider. Do not promise this
lifetime without a test where the first component unmounts while a second
continues using the same store.

Do not use Var as the default backing state for a reactive app store. Pure
Var programs can be reducers, but async read/runTuple/write transactions need
an explicit concurrency policy. Native atomic SignalRef updates are sufficient
for independent actions; async actions should commit small pure patches after
their external work, with application-specific stale-result policy.

## Grouping is a separate choice

A setup factory can return any typed group of composables: state, derived
signals, actions, subscriptions, resources, or pure Var programs. Nothing in
the factory should require every member to be a state field. The comparison
uses a Counter group for a small common scenario; it is not a proposed base
class or record-only store API.

`Scope` answers who releases group resources. `Env` supplies the group to
consumers. A provider creates and shares one instance. `Var` answers how a
computation transforms local state, and can be one composable within that
group. `SignalRef` supplies shared reactive state for UI bindings. These
responsibilities compose; choosing Var does not replace lifecycle or identity.

An eager Layer graph uses native memoization while building one provider. It
does not establish an exactly-once concurrent lazy registry: `Memo` isolates
and merges cache snapshots across fibers. Repeated `Env.get` calls after
eager construction do return the same supplied object.

## Pinned source evidence

The linked tagged files match the published Maven sources byte for byte:
[kyo-prelude sources](https://repo.maven.apache.org/maven2/io/getkyo/kyo-prelude_3/1.0.0-RC7/kyo-prelude_3-1.0.0-RC7-sources.jar)
and [kyo-core sources](https://repo.maven.apache.org/maven2/io/getkyo/kyo-core_3/1.0.0-RC7/kyo-core_3-1.0.0-RC7-sources.jar).

- [Var handlers and isolation](https://github.com/getkyo/kyo/blob/v1.0.0-RC7/kyo-prelude/shared/src/main/scala/kyo/Var.scala#L144)
  keep computational state, return a final state, and explicitly update,
  merge, or discard isolated state.
- [SignalRef.updateAndGet](https://github.com/getkyo/kyo/blob/v1.0.0-RC7/kyo-core/shared/src/main/scala/kyo/Signal.scala#L623)
  is the native atomic reactive mutation operation.
- [Memo](https://github.com/getkyo/kyo/blob/v1.0.0-RC7/kyo-prelude/shared/src/main/scala/kyo/Memo.scala#L49)
  fills a cache after successful computation and merges isolated cache maps;
  [Layer](https://github.com/getkyo/kyo/blob/v1.0.0-RC7/kyo-prelude/shared/src/main/scala/kyo/Layer.scala#L292)
  uses that effect for graph reuse.
- [Scope](https://github.com/getkyo/kyo/blob/v1.0.0-RC7/kyo-core/shared/src/main/scala/kyo/Scope.scala#L123)
  handles resource lifetimes separately from state effects and signals.
