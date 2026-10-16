# Historical generic callback effects prototype

This standalone spike tested effect-polymorphic event/model wrappers during an
earlier design. Those wrappers were not adopted. The final API uses native Kyo
signals and typed callbacks; see the [native guide](../../../examples/README.md#state-and-events-with-kyo).
All wrapper and widget sources here are isolated historical prototypes, not
product dependencies or a proposed application API.

Run with JDK 25 and `sbt test`. The build uses Scala 3.9.0, sbt 1.13.0, Kyo
1.0.0-RC7, and the libraries' strict warning flags. On 2026-10-14, all nine
tests passed. `Tabs.scala` preserves the earlier prototype widget with imports
changed to experimental bindings, so tests invoke real public Kyo UI
button callbacks without depending on a terminal renderer.

## What compiles and runs

The experiment retains the earlier prototype's factories and type annotations:

```scala
val emit: Emits[TabsEvent] = useEmits[TabsEvent](handleAsync)
val model: Model[Int] = Model[Int](selected, next => selected.set(next))
val direct: Model[Int] = Model(selected)
```

`Emits[E]` and `Model[A]` are Async aliases for the new generic classes.
Additional factories retain the actual required effects:

```scala
type Work = Async & Env[Audit] & Abort[SaveError]

val emit: EffectEmits[TabsEvent, Work] = useEffectEmits[TabsEvent](save)
val model = Model.effectful[Int](selected, next =>
  save(TabsEvent.Change(next)).andThen(selected.set(next))
)
```

The additional factories also infer the value/event type when the callback
supplies it. Both generic wrappers defer the callback until effect execution.
They await asynchronous work and retain dependency/failure effects in the
returned type. Assigning an unresolved `Env` or `Abort` wrapper to an Async
alias is rejected by the compiler.

## The actual UI boundary

Kyo UI event attributes accept `Any < Async`; this prototype's Tabs props
use `Unit < Async`. A generic wrapper still needs ordinary Kyo handlers before
it can be used as a UI callback. The tested app-specific adapter provides Audit, sends expected
SaveError failures to a signal, and rethrows unexpected panics:

```scala
def forUi[E](
    handler: E => Unit < Work,
    audit: Audit,
    errors: SignalRef[Vector[SaveError]],
): E => Unit < Async =
  event =>
    Abort.run[SaveError](Env.run(audit)(Sync.defer(handler(event)))).map {
      case Result.Success(_)     => ()
      case Result.Failure(error) => errors.updateAndGet(_ :+ error).unit
      case Result.Panic(error)   => throw error
    }
```

The callback can capture an Audit resolved during component setup and run
after the setup's `Env.run` has returned. The test verifies separate apps keep
separate Audit instances. The adapter also preserves the exact unexpected
exception raised after an asynchronous suspension.

The generic Model integration requires wrapping the generic model again:

```scala
val effectModel = Model.effectful[Int](selected, saveSelection)
val uiModel = Model[Int](
  effectModel.value,
  forUi(next => effectModel.set(next), audit, errors),
)
```

Keeping the typed domain action until the UI boundary is shorter and has the
same tested behavior with the prototype's fixed-Async API:

```scala
val uiModel = Model[Int](selected, forUi(saveSelection, audit, errors))
```

Both tests click the actual Tabs buttons, accept one save, reject another, and
keep the supplied model signal authoritative. The generic variant does not
reduce the handler boilerplate in these call sites.

## Constructor experiments that failed

The first version tried to keep every constructor spelling under the existing
names using partially applied builders plus inferred overloads:

```scala
def useEmits[E]: EmitsBuilder[E]
def useEmits[E, S](handler: E => Unit < S): EffectEmits[E, S]

object Model:
  def apply[A]: ModelBuilder[A]
  def apply[A, S](value: Signal[A], set: A => Unit < S): EffectModel[A, S]
```

With the unchanged Tabs source, Scala reported an ambiguous `Model(...)`
overload and could not infer the existing `useEmits(onEvent)` call. Removing
the fully inferred overloads fixed the top-level event helper, but the unchanged
`Model(signal, setter)` call still failed with "method apply in object Model
does not take parameters". Moving the ref overload into ModelBuilder did not
resolve that failure. These diagnostics were from Scala 3.9.0, not an assumed
limitation of Scala.

The passing version avoids the conflict by leaving existing factory names
alone and adding the two explicit advanced factories. This preserves ordinary
source spelling at the cost of more public types, builders, and entry points.

## Judgment

Use native signals and typed callbacks rather than either wrapper design.
The generic wrappers correctly preserve domain effects, but typed ordinary
functions already preserve those effects until the same UI handler boundary.
No tested call site became simpler, and changing the prototype factories
introduced real inference problems. The final upstream API audit also found
existing native input bindings and Kyo Emit; a parallel wrapper layer was not
justified.

Retain this executable evidence if a future concrete component-logic use case
reveals behavior missing from typed functions and upstream effects. Such a case
would need to establish value beyond a function plus the same final UI adapter.
This history is independent of using `Env` for application services or capturing
a diagnostic `Frame` at component construction.
