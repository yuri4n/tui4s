# Independent API ergonomics review

The final recommendation is to use native Kyo primitives first. The initial
review compared wrappers before auditing the full upstream UI surface. That
was too narrow: proving a wrapper can work does not establish that it is
needed. This review supersedes that earlier recommendation.

Keep widget behavior in `examples.widgets.TabsView` and terminal mount retention
in `examples.widgets.Tabs`. Keep pini4s only for optional named state shared
within an explicit store instance. Do not build another model, emitter, signal,
or dependency system. The [native state and events guide](../../examples/README.md#state-and-events-with-kyo)
documents the supported patterns and runnable examples.

## What upstream already provides

| Need | Existing API | Remaining application responsibility |
| --- | --- | --- |
| Mutable reactive state | `Signal.initRef` and `SignalRef` | Choose its owner and allocation lifetime. |
| Read-only or derived state | `Signal`, including `map` and combinations | Decide what children may change. |
| Input bindings | `.value(ref)` and `.checked(ref)` | Add validation or application event behavior as needed. |
| Component events | Typed Scala callbacks; Kyo `Emit` when a separate event effect is useful | Define event meaning and handle remaining effects before the UI boundary. |
| Grouped state and actions | An ordinary Scala class | Expose the references/actions appropriate to the feature. |
| Application dependencies | Kyo `Env` and `Layer` | Resolve or handle dependencies at the execution boundary. |
| Optional keyed sharing | pini4s `StateStore` and `useState` | Own the store instance; avoid accidental global state. |

Native input bindings write their reference before the user event handler runs.
A reusable controlled widget has a different contract: read the parent's
authoritative signal and send a typed change request. The parent can accept or
reject that request. This behavior needs a signal plus a callback, not a new
model wrapper.

## Representative application choices

### Local tabs

A local `Tabs` declaration owns selection by default. The terminal adapter
allocates and retains its state per mounted position. Re-rendering a parent
retains that state; unmounting ends that ownership. The same component value
mounted twice must still have independent state and focus identity.

The renderer-independent `TabsView` uses public Kyo UI and signals. Evaluating
its allocating UI computation creates fresh state; ordinary UI construction
does not imply a renderer's mount lifetime. A shared store would obscure this
distinction rather than solve it.

### Controlled tabs

A parent allocates `SignalRef[Int]`, gives the child a read-only view, and
supplies an `Int => Unit < Async` selection callback. Writing the reference
accepts a request; leaving it unchanged rejects it. External writes drive the
same child through the native reactive graph. An event describing a selection
request must not be documented as proof that the signal changed.

These responsibilities fit ordinary typed props. Native inputs can use their
existing two-way bindings directly. Slots remain function parameters returning
UI, with typed arguments when a slot needs the selected index.

### Shared state with asynchronous work

A feature class can group selection, status, derived signals, and actions.
Use optional pini4s lookup only when stable keys and an explicit shared-store
lifetime help the application. A service can come from `Env` during setup,
then be captured together with references in an event callback.

Keep a domain action's full type, such as
`Int => Unit < (Async & Env[Audit] & Abort[SaveError])`, until the boundary that
provides Audit and turns a typed failure into application state. Its resulting
UI callback must meet Kyo UI's `Async` boundary. A plain function preserves
these effects without an `EffectModel` or `EffectEmits` container.

Handling UI construction alone does not handle an effect inside a returned
callback. Resolve and capture the dependency, or run the handler when invoking
that callback. The same rule applies when using native Kyo `Emit` for component
logic: handle the event effect within the UI action that executes the logic.

The terminal runner awaits event handlers. An asynchronous network call in a
callback therefore delays subsequent input processing. Background workers need
an application-owned scope and an explicit cancellation/stale-result policy;
that lifecycle should not be hidden in a state store or event wrapper.

## Frame and component authors

Frame is source metadata, not an application service. Ordinary external Kyo
call sites can omit explicit Frame arguments because the macro supplies one.
A reusable helper can forward a contextual Frame when preserving its caller's
origin matters. A UI declaration can capture that origin once and reuse it
during later rendering, matching the intent of Kyo UI nodes.

Replacing contextual Frame with `Env[Frame]` changes attribution and introduces
an effect. Calls under one provider use the same supplied location. Public Env
operations also need their own bootstrap Frame. An inline facade can hide the
contextual parameter at external call sites without Env, but its maintenance
cost is not justified merely to conceal an automatically supplied argument.

The isolated `kyo.framespike` sources retain a historical diagnostic experiment:
Kyo deliberately rejects automatic Frame derivation within its own namespace.
They do not access private APIs, and no product/library packaging decision
should copy that namespace to evade upstream boundaries.

## Experimental evidence and limits

- The native Frame experiment passed seven runnable checks and four expected
  compiler rejections after decoupling. Distinct contextual calls retained distinct lines; one
  Env provider supplied one location; a captured declaration origin survived
  rendering from a different file. The retained version replaces removed
  wrappers with native signals, typed callbacks, and a minimal UI declaration.
  See [its result record](frame/RESULTS.md).
- The store experiment passed 13 checks. Both contextual and Env-resolved
  dependencies survived deferred mount/event execution with provider isolation.
  Unresolved Env and Abort were rejected at typed Async boundaries; the actual
  `onClick` Env expression was rejected too. This does not claim every untyped
  value-lifting shape is safe.
- Handling `Env.run(store)(Fiber.init(lookup))` alone leaves the later join's
  Env requirement. Handling spawn and join together, or handling the action
  inside the fiber before joining it later, worked. Dependency capture remains
  the simpler UI boundary in these cases.
- The historical generic-effects prototype passed nine checks: deferred
  invocation, async completion, typed rejection, dependency isolation, and panic
  identity through actual public Kyo UI callbacks. Same-name generic factory
  overloads broke existing inference; separate advanced factories worked.
  The generic model then needed another handled wrapper at the UI boundary.
  Its ordinary typed-function comparison achieved the same behavior with fewer
  layers. These tests demonstrate feasibility, not a reason to ship wrappers.

The spikes inspect public Frame metadata and execute effects. They do not
establish complete exception traces, performance, browser compatibility, or a
new lifecycle model. Historical passing counts are not a substitute for reruns
when sources change.

## Decision

Reuse Kyo's public APIs and document them as the first-class approach. Retain
the Tabs widget because it adds selection, focus, slots, and mount behavior.
Retain optional pini4s because keyed references add a distinct sharing policy.
Remove the separate UI-state library and keep its generic-wrapper exploration
as explicitly historical evidence. Require a concrete missing upstream
behavior and a simpler real application call site before adding another layer.
