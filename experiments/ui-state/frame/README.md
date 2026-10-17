# Frame API spike

This standalone experiment uses published Kyo 1.0.0-RC7 `SignalRef`, typed Scala
callbacks, and native Kyo UI. It has no dependency on a separate UI-state
library. A minimal `CapturedView` demonstrates declaration origin; it is not a
widget or a proposed abstraction.

Use JDK 25 and run `sbt test` from this directory. Run one sbt process at a time
across the repository. `bash check-rejections.sh` checks four intentionally
invalid sources excluded from the ordinary build.

`Contextual` forwards the macro-generated caller Frame. `Environmental` uses
`Env[Frame]`, while retaining the bootstrap contextual Frame required by Env's
public API. `InlineEnvironmental` tests whether external inline expansion can
supply that bootstrap without an explicit parameter. `InlineContextual` tests
an inline facade without Env. `CapturedView` retains construction origin when
another source file calls its `ui` method.

The helpers return the public Frame passed to `SignalRef.set` or `Sync.defer`.
Tests inspect that metadata, execute native signal writes, verify deferred
callback execution, and invoke a handled callback through a public Kyo UI
button. These checks do not measure full exception traces or performance.

The isolated `kyo.framespike` namespace intentionally reproduces the diagnostic
macro restriction encountered by an earlier library prototype. It calls public
Kyo APIs only. This historical namespace experiment is not a recommendation to
put application or third-party library code in `kyo`, and does not bypass any
private access restriction. External consumer code lives in `framespike`.

See [RESULTS.md](RESULTS.md) for the evidence and its limits, and the
[native state and events guide](../../../examples/README.md#state-and-events-with-kyo)
for application usage.
