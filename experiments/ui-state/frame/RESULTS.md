# Frame API experiment

Pinned versions: Scala 3.9.0, Kyo 1.0.0-RC7, JDK 25, sbt 1.13.0.
The build uses published upstream dependencies only.

## Evidence and reproduction

The retained native version passed all seven runnable tests and all four
expected compiler rejections after removing the prototype library dependency.
Both `sbt test` and `bash check-rejections.sh` exited 0. It exercises native
`SignalRef` writes and typed callbacks directly, with a minimal captured-origin
UI declaration. The rejection script writes logs and exit statuses under
`target/rejection-logs/`.

| Strategy | Signature under test | Evidence checked |
| --- | --- | --- |
| Contextual | `set(ref, value)(using Frame): Frame < Async` | External callers omit manual Frame arguments. Distinct calls retain distinct source lines. |
| Explicit override | The same call with `(using seed)` | The supplied origin is used, without another effect. |
| Env-backed | `set(ref, value)(using Frame): Frame < (Async & Env[Frame])` | One `Env.run(seed)` supplies the same origin to different writes. The extra effect needs handling. |
| Inline contextual | `inline def set(ref, value): Frame < Async` | External inline expansion retains distinct caller lines without an exposed contextual parameter. |
| Inline Env | `inline def set(ref, value): Frame < (Async & Env[Frame])` | External expansion supplies bootstrap Frames; the Env requirement and provided origin remain. |
| Captured origin | `class CapturedView(using val origin: Frame)(label); def ui: UI < Sync` | Rendering from another source file retains the declaration's construction origin. |

The helpers return the Frame passed to native `SignalRef.set` and `Sync.defer`.
Writes, callback laziness, handled UI callback execution, and reactive text are
checked as behavior. Metadata checks do not establish full exception-trace
behavior, diagnostic quality in every stack shape, or runtime performance.

## Expected compiler rejections

The fixtures are added separately to `Compile / unmanagedSources` by
`check-rejections.sh`; each must fail with the relevant diagnostic.

- `EnvOnly.scala`: a non-inline function in the isolated `kyo.framespike`
  namespace cannot replace its contextual Frame with `Env[Frame]`. `Env.get`
  and `map` still need a bootstrap Frame, and Kyo rejects automatic derivation
  within its own namespace: "Frame cannot be derived within the kyo package."
- `EnvUiCallback.scala`: direct `onClick(Environmental.set(...))` and the typed
  callback invocation both leave `Env[Frame]` unresolved. Their
  `Frame < (Async & Env[Frame])` values do not fit Kyo UI's `Any < Async` boundary.
  The runnable test handles the same effect at execution using `Env.run`.
  This result concerns these concrete expressions, not every untyped
  value-lifting hazard.
- `TrailingFrame.scala`: a default `UI.empty` before the contextual constructor
  parameter list cannot use that later Frame in its default-argument getter.
  This fixture deliberately uses `kyo.framespike` to expose the derivation
  restriction; ordinary external namespaces can materialize a Frame.
- `InlineLibraryCaller.scala`: expansion from an ordinary function inside
  `kyo.framespike` still cannot derive a Frame. External-call-site derivation
  does not remove the need to propagate an available Frame inside Kyo's
  namespace.

No experiment calls private Kyo APIs. The `kyo.framespike` namespace is retained
only to reproduce a historical diagnostic constraint, not as a product design.

## Decision

Use native Kyo operations and typed callbacks. Keep Frame as source metadata;
`Env[Frame]` adds a dependency effect and changes attribution rather than
removing plumbing. Ordinary external callers already get automatic derivation.
Capture a declaration's Frame once when that is the desired diagnostic origin.
Use `Env` for application dependencies, and handle those effects when deferred
callbacks execute or resolve and capture their concrete dependencies earlier.

An inline facade can hide a contextual parameter while retaining caller
positions. This experiment establishes feasibility, not a reason to add a new
facade over existing public APIs.
