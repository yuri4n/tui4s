# Store dependency experiments

These files test optional application dependencies alongside native Kyo state
and callbacks, not an alternative implementation of `Frame`. They read pini4s
production sources from the existing checkout
and compile them into this spike's own target directory.

Run with JDK 25 and the configured environment, after other sbt processes
finish:

```sh
cd experiments/ui-state/store-env
sbt test > test-run.log 2>&1
```

Result: 13 tests passed with Scala 3.9.0, JDK 25, and Kyo 1.0.0-RC7. The
successful command output is in `test-run.log`. The suite tests contextual
`StateStore` and `Env[StateStore]` at ordinary app helpers, child fibers,
delayed mount functions, and Kyo UI event callbacks. Negative compile checks
cover unresolved `Env` and `Abort` at the typed `Async` event boundary.

Source references: Kyo 1.0.0-RC7 `Env.scala` from the published
`kyo-prelude_3` source jar and `Fiber.scala` from `kyo-core_3`. `Env.runAll`
handles its context as inheritable.
A thunk or callback returned by a handled computation still has its own
effect type; resolve and capture the dependency, or handle its effect at
invocation.

## Observed boundaries

- Ordinary consumer helpers compile without declaring `(using Frame)` in
  both dependency styles. The public Kyo macro supplies a frame. This does
  not prove that its diagnostic location equals an explicitly forwarded
  caller frame; that is a separate experiment.
- Both lexical `using StateStore` and `Env.use[StateStore]` can resolve a
  store and capture it in deferred mount and event functions. The functions
  work when invoked later in new fibers, after their construction handler
  has completed. Two providers retain independent state.
- A real Kyo UI `onClick` handler runs when it captures a resolved reference
  and has an explicit `Unit < Async` action type.
- Passing `Unit < (Sync & Env[StateStore])` directly to public `onClick`
  fails compilation. Kyo reports `Cannot lift ... effect type mismatch`.
  The suite retains a negative compile check for this case. This shape does
  not silently lift the
  unresolved computation. Widening a callback's result to plain `Any` is a
  separate existing Kyo hazard and is not tested here.
- A callback declared `Int => Unit < Async` rejects unresolved `Env` and
  `Abort`. These requirements need handling before entering the callback
  boundary.
- `Env.run(store)(Fiber.init(lookup).map(_.get))` works. So does
  `Fiber.init(Env.run(store)(lookup))` followed by a join outside the handler.
  `Env.run(store)(Fiber.init(lookup))` alone leaves `Env[StateStore]` in the
  returned fiber's `get` effect; the compile check rejects an outside join
  that promises only `Async & Scope`.

## API recommendation

Use native local state by default; an ordinary class can group a feature's
references and actions. Keep the explicit `StateStore` only when keyed shared
references help an application. See the
[native guide](../../../examples/README.md#state-and-events-with-kyo).
`Env[StateStore]` is a useful optional application dependency API, especially
when applications already use Kyo layers. Resolve the named store during UI
setup and capture its references/actions in callbacks. A small app can keep
the simpler contextual helper; converting all store access to an `Env`
effect makes callback boundaries more complex without solving source-frame
boilerplate.

A future Vue-style provider API must account for deferred mount and event
execution. Wrapping only UI construction in `Env.run` does not erase effects
inside returned thunks. A provider needs to capture resolved dependencies or
explicitly handle them where callbacks execute. Do not add unhandled
application effects to the renderer's `Async` event signatures implicitly.
