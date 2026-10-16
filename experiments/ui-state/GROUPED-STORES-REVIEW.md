# Independent review: application-shared composable groups

The useful store unit is a whole typed group of related composables: state,
derived signals, actions, subscriptions, and other resources. Consumers in one
application should receive the same group instance, initialized once, while
independent applications receive fresh instances. Component unmounting should
not end application-owned work.

The executed prototypes show that native Kyo already supports this with eager
setup under an application `Scope`, sharing through `Env`, and stable `Layer`
definitions reused within one `Memo.run`. This should be the foundation for a
small pini4s grouped-store API. `Var` can be useful inside a group for pure state
transitions; it does not replace the group's identity or lifetime.

Neither experiment implements a new supported pini4s API. The current library
still shares individual keyed `SignalRef` values. Calling that registry a
complete grouped-store implementation would overstate its behavior.

## Evidence reviewed

Both experiments use JDK 25, Scala 3.9.0, sbt 1.13.0, and published Kyo
1.0.0-RC7. The review inspected the final sources, README claims, and successful
runner output. Retained Scala sources were checked against the executed copies
and matched byte for byte. The scoped experiment was also executed from its
retained repository directory after its dependency paths were relocated.

| Experiment | Executed checks | Outcome |
| --- | --- | --- |
| [grouped-scope](grouped-scope/README.md) | 8 composable-group tests, plus the existing 6 pini4s registry tests | 14 passed, 0 failed; sbt exit 0 |
| [grouped-var](grouped-var/README.md) | 13 state-effect, native reactive, provider, and callback checks | 13 passed, 0 failed; sbt exit 0 |

These are 21 new comparison checks and 6 existing registry checks. Kyo's own
suite summaries report those results; its sbt bridge separately prints a
zero-test summary. The intentional counterexamples pass by demonstrating the
incorrect behavior of the alternative under test, not by promising that
behavior as a feature.

## Group identity and ownership

[CounterGroup.setup](grouped-scope/src/main/scala/spike/CounterGroup.scala)
combines independent counter and title composables, a derived doubled signal,
increment/rename actions, observation history, and a background observer. It
returns an ordinary typed Scala object. A setup may return another type without
extending a store base class or making every member a state field.

The tests establish exact object identity, not merely equal state. Reusing one
stable layer within one provider's `Memo.run` runs setup once and returns the
same group. Separate providers using the same layer definition produce distinct
groups and isolated state. These boundaries suit separate applications, tests,
and request-owned stores; no SSR server was exercised.

Eager setup under the application scope survives an inner component scope's
closure. The observer remains active and processes a later state change.
Normal owner exit, application failure, and owner interruption stop the observer
and run its registered finalizer. Concurrent actions on the constructed group
retain all 512 increments, and the derived signal reads 1024.

The important counterexample is also executed: initialize a memoized group
inside a component scope, close that scope, then look it up from the parent.
Memo returns the exact same object, whose observer has already stopped. Caching
an object does not transfer its resource ownership. A provider must encompass
actual application execution, not just return UI or callbacks from a scope
that then closes.

## Var is a useful composable, with different responsibilities

[CounterTransitions and ReactiveTransitions](grouped-var/src/main/scala/spike/CounterModels.scala)
give Var a useful role rather than comparing only an unsuitable replacement
for reactive state. Pure transition programs compose and replay deterministically
from explicit seeds. A domain failure can discard an unpublished transition.
Resource cleanup remains an independent `Scope` concern.

The combined variant interprets a pure `Var[CounterState]` program inside one
native `SignalRef.updateAndGet`. Its group exposes native derived signals and
actions. Thirty-two concurrent two-step transactions preserve all 64 increments.
An actual native Kyo UI button invokes a captured group action in a later fiber;
the supplied group retains exact identity and its owner controls cleanup.

This is useful for substantial deterministic domain transitions. For a simple
increment, direct `SignalRef.updateAndGet` is shorter. The bridge excludes
Async and other effects, and a compiler check verifies rejection of an async
action. Atomic updates may retry their function, so transitions must be pure,
short, and safe to repeat. The domain-failure test does not establish rollback
of external effects.

The naive async snapshot bridge is deliberately shown losing one concurrent
increment: read a signal, suspend, run Var from the stale value, then overwrite
the signal with the result. General async transactions need an explicit
concurrency/conflict policy. Scope does not supply that policy. Running a fresh
Var handler per callback also resets its computational state; it does not
provide persistent group identity.

## Limits that must remain explicit

- **Memo scope:** repeated evaluation of one stable layer within one Memo
  computation reuses its value. Repeated `Env.get` after eager provision
  retrieves the supplied object. Memo uses Var-backed cache snapshots and
  merges isolated results across fibers; neither prototype establishes an
  exactly-once concurrent lazy factory registry. The concurrency tests operate
  on groups that have already been constructed.
- **Lazy setup:** neither experiment implements provider-owned execution of
  arbitrary lazy factories. Such an API would need concurrent first-use
  coalescing, failed-setup cleanup/retry, cancellation, and dependency-cycle
  policies. Merely accepting a factory with `Scope` in its effect type is
  insufficient.
- **Observer cleanup:** the final scoped run observed one or two queued
  notification callbacks after the observer fiber had completed and its
  finalizer had run. A subsequent signal write drained them to zero without
  changing observation history. The pinned Signal implementation uses an
  uninterruptible notification promise; its waiter count includes retained
  completion callbacks. It is not a count of live observer fibers. Immediate
  removal of every retained callback is not established.
- **Failure policy:** sample readiness is signaled before the caller's observer
  callback completes. A thrown exception ends the observer and is exposed as
  `Result.Panic` when its fiber is joined. The sample supplies no automatic
  supervision, restart, or application-wide failure propagation.
- **Escaped references:** Scope releases resources; it does not revoke normal
  Scala objects or SignalRefs. Tests deliberately read or write references
  after provider exit. No region-type or use-after-close protection is claimed.
- **Identity by type:** native Env provides services by type. Multiple group
  definitions returning the same type require distinct wrappers or an explicit
  definition identity policy; the experiments do not implement keyed groups.
- **Execution boundaries:** unresolved Var or Env effects still need handling
  before native UI callbacks meet their Async boundary. Signals represent
  current state, not an event log guaranteed to retain every intermediate
  update. No browser renderer, ZIO adapter, or performance claim follows from
  these JVM checks. Component lifetimes are modeled with nested Kyo scopes;
  these suites do not drive actual tui4s mounting.

## Recommendation for pini4s

Make the next store abstraction a typed setup definition plus an explicit
application provider. Let setup compose ordinary Kyo functions and return any
typed group. Initially prefer eager provider construction, so group resources
are visibly owned by the application rather than its first consumer. A thin
API can make that policy convenient while retaining native Layer, Env, Scope,
SignalRef, and Signal underneath.

Keep keyed `useState` as a separate convenience. Let a group optionally contain
pure Var programs when they simplify domain logic. Do not require a new State
wrapper, store base class, reactive graph, event system, or lifecycle engine.
If lazy grouped initialization becomes a requirement, demonstrate its additional
guarantees separately before presenting it as equivalent to the eager design.

## Pinned source provenance

The audit used the official published Maven source archives, not an unpinned
checkout. The relevant files and symbols are:

| Published archive | Source files and evidence |
| --- | --- |
| [kyo-prelude_3 1.0.0-RC7 sources](https://repo.maven.apache.org/maven2/io/getkyo/kyo-prelude_3/1.0.0-RC7/kyo-prelude_3-1.0.0-RC7-sources.jar) | `kyo/Var.scala`: `run`, `runTuple`, isolation strategies; `kyo/Memo.scala`: Var-backed cache and merge isolation; `kyo/Layer.scala`: memoized construction; `kyo/Env.scala`: inheritable provision. |
| [kyo-core_3 1.0.0-RC7 sources](https://repo.maven.apache.org/maven2/io/getkyo/kyo-core_3/1.0.0-RC7/kyo-core_3-1.0.0-RC7-sources.jar) | `kyo/Signal.scala`: atomic references, observation scopes, notification promise; `kyo/Scope.scala`: resource handlers; `kyo/Fiber.scala`: scoped fibers and join effects; `kyo/scheduler/IOPromise.scala`: pending completion callbacks and waiter counts. |
| [kyo-ui_3 1.0.0-RC7 sources](https://repo.maven.apache.org/maven2/io/getkyo/kyo-ui_3/1.0.0-RC7/kyo-ui_3-1.0.0-RC7-sources.jar) | `kyo/UI.scala`: native reactive nodes, bindings, and Async event callbacks. |

The checked core/prelude public API names the computational state effect `Var`
and the resource effect `Scope`; no top-level `State` effect was found at this
pin. Internal source was read to explain observations, never called by the
prototypes. No upstream source cache is vendored into these experiments.
