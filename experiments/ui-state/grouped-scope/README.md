# Application-scoped composable groups

This experiment groups native Kyo composables into an ordinary typed Scala
object. It adds no store runtime and does not propose a supported public API.
Compare it with [grouped-var](../grouped-var/README.md), which uses native
`Var` for state transitions within a similarly shared group.

`CounterGroup.setup` composes a counter hook, a title hook, a derived signal,
actions, and a background observer. It returns a `CounterGroup`; another setup
could return any other Scala type without extending a store base class. The
composables use only Kyo public APIs, with no renderer or tui4s hook dependency.

## Run

Initialize the `vendor/pini4s` submodule from the checkout root, then use JDK 25:

```sh
git submodule update --init --recursive
cd experiments/ui-state/grouped-scope
sbt test
```

The build pins Scala 3.9.0, sbt 1.13.0, and Kyo 1.0.0-RC7. It compiles the
current pini4s sources and runs their six registry tests alongside this
experiment's eight group tests. Run only one sbt process at a time across the
checkout. No downloaded upstream source or generated build output is required.

Validated on 2026-10-15 with JDK 25: **8 group tests and 6 registry tests
passed, 0 failed**. The Kyo suites report these counts; their sbt bridge also
prints a separate zero-test summary.

## What the native approach supplies

Construct the group eagerly under the application's `Scope`, then provide the
resolved object through `Env` while the application runs. A stable `Layer`
reused within one `Memo.run` also reuses the exact group instance:

```scala
import kyo.*
import spike.CounterGroup

def program: Unit < Async =
  Scope.run(Memo.run {
    val counter = Layer(CounterGroup.setup((), ()))
    Env.runLayer(counter) {
      Env.use[CounterGroup] { group =>
        // Keep the application and its consumers inside this scope.
        group.increment
      }
    }
  })
```

The tests establish these boundaries:

- Reusing one layer within one provider runs setup once and returns the same
  object, including its independent composables, actions, and derived values.
- Separate providers using the same layer definition create separate groups.
- Closing a component's child scope leaves an eagerly provided group and its
  observer alive. Closing the application scope ends the observer and runs
  its finalizer once, including on application failure or interruption.
- Sixteen concurrent consumers perform 512 atomic increments without losing
  updates; the derived value is 1024.
- A layer first initialized inside a component scope can cache an already
  disposed group. Its subsequent lookup returns that same disposed object.
  Memoization does not transfer resource ownership to the application.
- An exception thrown by the observer callback is exposed as `Result.Panic`
  by joining its fiber. Setup's readiness signal precedes this callback, so
  that exception does not leave setup waiting forever.

The application scope must enclose the application's execution, not only its
factory call. Returning a group from `Scope.run(setup)` also returns references
whose background work has already stopped. Scope does not invalidate escaped
`SignalRef` values; consumers can still read or write them after shutdown.

The concurrency test concerns actions on an already constructed group. It
does **not** establish concurrent lazy setup-once semantics. Native `Memo`
state follows Kyo's computation and fork/join rules; it is not a global
concurrent initialization registry. Native `Env` identifies services by type,
so different definitions returning the same type need distinct wrappers or
another explicit identity mechanism.

## Cleanup and failure limits

The cleanup assertions check completed observer fibers, finalizers, and the
absence of later callback updates after writing the escaped signal. With the
pinned Kyo version, a canceled observer can leave notification callbacks queued
on the signal's current next-value promise. A later write drains those waiters.
`SignalRef.waiters` therefore does not prove how many observers are still
running, and these tests do not claim immediate removal of every retained
callback after shutdown. No private Kyo API is used to alter that behavior.

Readiness in this sample means the observer has begun handling its initial
value; it does not mean the caller's callback succeeded. A failing callback
ends the native observer fiber. Joining that fiber exposes the failure. An
application still needs an explicit supervision policy if a background
failure should stop the app or restart the work; this sample supplies neither.

## Implications for a store API

Native signals, `Scope`, `Layer`, and `Env` already support an eagerly provided
group of hooks with application ownership. Native `Var` can complement this
group with deterministic state transition programs; it does not itself provide
reactive subscriptions, shared instance identity, or resource ownership.

A small store helper would add useful policy only if it consistently provides
one whole group per definition and application owner, with setup performed
under that owner's lifetime. The existing pini4s keyed registry shares signal
references, not whole groups or their resources. Merely caching a lazy scoped
factory under whichever component first requests it reproduces the failing
lifetime pattern tested here. Lazy owner execution would additionally need
explicit concurrent initialization, failure/retry, partial cleanup, and
dependency-cycle rules; this experiment does not implement that runtime.
