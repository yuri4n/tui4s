# UI state API experiments

The supported component design reuses native Kyo state, bindings, typed callbacks, and effects.
`TabsView` and the retained tui4s `Tabs` adapter remain in `examples.widgets`.
`pini4s` is optional application-owned keyed state. There is no separate UI-state
library. Start with the [native state and events guide](../../examples/README.md#state-and-events-with-kyo)
for supported application patterns.

These standalone experiments retain evidence behind that decision. They are
not published modules or additional supported APIs. CI does not build these
experiments; they are the evidence of the API rules of CONTRIBUTING.md ("UI
architecture and API design"). Use JDK 25, Scala 3.9.0,
sbt 1.13.0, and Kyo 1.0.0-RC7. Run one sbt process at a time, including the main
build. The `store-env` and `grouped-scope` experiments need the initialized
`pini4s` submodule.
<!-- claude 2026-10-20: changed (L6): CI does not build them; changed: CONTRIBUTING.md (ADR 0021) -->

## Findings

| Question | Evidence | Decision |
| --- | --- | --- |
| Build model/event wrappers? | Native `SignalRef`/`Signal`, input bindings, typed callbacks, and Kyo `Emit` already cover the primitives. The generic-wrapper prototype did not simplify its real UI call site. | Reuse upstream APIs; keep widget-specific behavior in widgets. |
| Contextual `Frame` or `Env[Frame]`? | Contextual calls retain distinct origins. A supplied Env Frame is reused and adds an effect. Env operations themselves require a bootstrap Frame. | Keep diagnostic Frames contextual; capture one origin on UI declarations where useful. |
| Hide Frame with an inline facade? | External inline expansion can preserve caller positions. The historical `kyo.*` diagnostic fixture still needs Frame propagation. | Do not add a facade solely to hide automatically supplied arguments. |
| Can a store come from Env? | Contextual and Env-resolved stores both work in deferred mount/event functions and retain provider isolation. | Use ordinary Kyo dependency composition; capture resolved references or handle effects at invocation. |
| Does handling construction handle later callbacks? | Unresolved Env/Abort fail at typed Async callback boundaries. Handling only a fiber's spawn also leaves join requirements. | Place handlers around execution, not only construction. |

Async callbacks still run under the renderer's event policy: tui4s awaits each
handler. A store does not supply background workers or own component scopes.
State ownership, dependency provision, and asynchronous scheduling are separate
decisions.

## Comparing application-shared groups

A store groups related hooks and composables into one typed feature: reactive
state, derived values, actions, subscriptions, and resources. Consumers in one
application should share the same whole instance, initialized once. Component
unmount must leave the group alive; closing its application provider must stop
its effects. Separate applications, tests, or server requests get separate
instances. This does not require a process-wide singleton.

The new comparison keeps the public library API unchanged while testing three
complementary building blocks:

| Approach | What it supplies | What still needs an explicit policy |
| --- | --- | --- |
| A setup function, native signals, `Layer`/`Env`, and `Scope` | An arbitrary typed group, reactive updates, dependency provision, and resource cleanup | Construct it under the application's scope before consumers run. `Memo` reuse within a dependency graph does not establish concurrent lazy initialization. |
| Native `Var[State]` | Composable computation-local transitions, deterministic replay, and final-state extraction | It does not provide shared reactive identity or subscriptions. An async read/compute/write bridge to a signal can overwrite concurrent updates. |
| Pure `Var` transitions applied inside an atomic `SignalRef` update | Domain transitions combined with shared reactive state | The reducer must be pure and safe to retry. Async work needs a separate conflict or serialization policy. Ownership still comes from the application scope. |

These roles can be combined. The initial recommendation is to use native
signals and eager application-scoped groups, with `Var` only where it helps
domain logic. The existing pini4s registry shares keyed signal references;
it does not yet memoize an entire composable group or own that group's effects.

A useful future store-definition API could add identity by definition rather
than only service type, setup-once sharing within an owner, and reliable
owner-scoped initialization. Native `Env` selects services by type; two store
definitions returning the same type need distinct keys or wrappers. Lazy
initialization would additionally need tested concurrency, failure/retry, and
dependency-cycle behavior. Those guarantees are proposals, not current features.

See [grouped-scope](grouped-scope/README.md),
[grouped-var](grouped-var/README.md), and the
[independent grouped-store review](GROUPED-STORES-REVIEW.md) for executable
examples, results, and limits. No new public store API or library name has
been selected by this comparison.

## Reproduce

Run `sbt test` from each experiment directory:

- [frame](frame/README.md) uses native signals, callbacks, and a minimal UI
  declaration. Run `bash check-rejections.sh` for four expected compiler failures.
- [store-env](store-env/README.md) tests optional pini4s provider lifetimes,
  deferred callbacks, fiber boundaries, and negative compile checks.
- [generic-effects](generic-effects/README.md) is a historical prototype of
  wrappers that were not adopted. It preserves the effect/inference comparison,
  not a recommended application API.
- [grouped-scope](grouped-scope/README.md) checks shared composable groups,
  provider ownership, concurrent actions, cleanup, and the first-consumer
  lifetime trap against native Kyo primitives.
- [grouped-var](grouped-var/README.md) compares local state effects, pure
  transitions inside reactive groups, actual deferred UI callbacks, and
  concurrent updates.

The native Frame experiment was rerun after decoupling: all 7 tests and four
expected compiler rejections passed. The store experiment retains its 13
passing checks; the historical generic-effects prototype retains its 9.
The application-group comparison passed 8 scoped-group tests and 13 Var/group
tests; the scoped build also passed all 6 unchanged pini4s registry tests.

The [independent API review](REVIEW.md) records the final upstream-reuse decision.
These experiments inspect source metadata and execute public Kyo UI callbacks;
they do not measure complete exception traces, rendering performance, browser
support, or adapter support for other effect libraries.

## Source references

The audit used the published Kyo 1.0.0-RC7 Maven sources: `kyo-data_3`
(`Frame.scala`), `kyo-prelude_3` (`Env.scala`, `Emit.scala`), `kyo-core_3`
(`Fiber.scala`, `Signal.scala`), and `kyo-ui_3` (`UI.scala`). See also the
[official Kyo repository](https://github.com/getkyo/kyo).

The isolated `kyo.framespike` namespace demonstrates a historical Frame macro
restriction using public APIs only. It is not a template for application or
third-party library packages and does not access private Kyo implementation.
