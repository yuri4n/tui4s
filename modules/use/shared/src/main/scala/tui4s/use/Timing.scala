package tui4s.use

import kyo.*

/** A signal that follows `source` after it is quiet for `quiet`: a value shows
  * when no other value comes within `quiet` after it. A burst of values gives
  * one value, the last one. It starts with the current value of `source`.
  *
  * The law: two values of the result are at least `quiet` apart, and the last
  * value of `source` always shows, `quiet` after it came. The timer is on Kyo's
  * `Clock`. The watch of `source` is a fiber that ends when the caller's
  * `Scope` closes. `Signal.observe` gives the values of `source`: a value that
  * a later write replaced at once is not seen, and a write that lands while the
  * watch arms is seen at Kyo's next repair (at most 1 s later).
  *
  * {{{
  * for
  *     query  <- Signal.initRef("")
  *     search <- debounced(query, 300.millis)
  * yield input.value(query) // `search` changes 300 ms after the last key
  * }}}
  */
// claude 2026-10-19: created (L2a)
def debounced[A](source: Signal[A], quiet: Duration)(using
    CanEqual[A, A],
    Frame,
): Signal[A] < (Sync & Scope) =
  Pacing.run(source, Pacing.Debounce.start[A])(
    (_, a, now) => Pacing.Debounce.write(a, now, quiet),
    Pacing.Debounce.wake,
  )

/** A signal that follows `source` at most once per `every`: the first value
  * shows at once and opens a window of `every`; the last value that comes in
  * the window shows at its end and opens the next window. It starts with the
  * current value of `source`.
  *
  * The law: two values of the result are at least `every` apart, and the last
  * value of `source` always shows, at most `every` after it came. Use it for a
  * value that changes faster than the screen needs (a progress, a sampler). The
  * timer is on Kyo's `Clock`; the watch of `source` is a fiber that ends when
  * the caller's `Scope` closes. As in [[debounced]], a write that lands while
  * the watch arms is seen at Kyo's next repair (at most 1 s later).
  *
  * {{{
  * for shown <- throttled(progress, 100.millis)
  * yield span(shown.map(p => s"$p %"))
  * }}}
  */
// claude 2026-10-19: created (L2a)
def throttled[A](source: Signal[A], every: Duration)(using
    CanEqual[A, A],
    Frame,
): Signal[A] < (Sync & Scope) =
  Pacing.run(source, Pacing.Throttle.start[A])(
    (s, a, now) => Pacing.Throttle.write(s, a, now, every),
    (s, now) => Pacing.Throttle.wake(s, now, every),
  )

/** A function that runs `f` with its last argument when it is not called for
  * `quiet`. Each call starts the wait again. A call while `f` runs interrupts
  * that run: the newest call wins (for example a search as the user types). The
  * function itself returns at once, so a key handler can call it.
  *
  * {{{
  * for search <- useDebouncedFn[String](300.millis)(q => results.set(find(q)))
  * yield input.value(query).onInput(search)
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useDebouncedFn[A](quiet: Duration)(f: A => Unit < Async)(using
    Frame,
): (A => Unit < Sync) < (Sync & Scope) =
  for
      calls   <- Signal.initRef(0L)
      latest  <- AtomicRef.init(Maybe.empty[A])
      settled <- debounced(calls, quiet)
      _       <- Fiber.init(
                   settled.observe(n =>
                     if n == 0L then Kyo.unit
                     else
                         latest.get.map {
                           case Present(a) => Fiber.init(f(a)).unit
                           case Absent     => Kyo.unit
                         },
                   ),
                 )
  yield (a: A) => latest.set(Present(a)).andThen(calls.updateAndGet(_ + 1).unit)

/** The pure timing rules of [[debounced]] and [[throttled]], and their shell. A
  * rule takes the writes of the source with their times and the wake-ups that
  * it asked for; each step gives the next state, a value to show, and the time
  * of the next wake-up.
  */
// claude 2026-10-19: created (L2a)
private[use] object Pacing:

    /** One step of a rule. */
    final case class Step[S, A](state: S, emit: Maybe[A], wake: Maybe[Instant])

    object Debounce:
        /** The value that waits and the time it shows. */
        final case class State[A](pending: Maybe[(A, Instant)])

        def start[A]: State[A] = State(Absent)

        /** A write waits `quiet`; it replaces the value that waited. */
        def write[A](a: A, now: Instant, quiet: Duration): Step[State[A], A] =
            val due = now + quiet
            Step(State(Present((a, due))), Absent, Present(due))

        /** A wake-up at or after the due time shows the value. */
        def wake[A](s: State[A], now: Instant): Step[State[A], A] =
          s.pending match
              case Present((a, due)) if due <= now =>
                Step(start, Present(a), Absent)
              case Present((_, due))               => Step(s, Absent, Present(due))
              case Absent                          => Step(s, Absent, Absent)
    end Debounce

    object Throttle:
        /** The end of the open window, and the value that waits for it. */
        final case class State[A](windowEnd: Maybe[Instant], pending: Maybe[A])

        def start[A]: State[A] = State(Absent, Absent)

        /** With no open window a write shows at once and opens one; in a window
          * it waits for the end (the newest value wins).
          */
        def write[A](
            s: State[A],
            a: A,
            now: Instant,
            every: Duration,
        ): Step[State[A], A] =
          s.windowEnd match
              case Present(end) if now < end =>
                Step(State(Present(end), Present(a)), Absent, Present(end))
              case _                         =>
                Step(State(Present(now + every), Absent), Present(a), Absent)

        /** At the end of a window the value that waits shows and opens the next
          * window.
          */
        def wake[A](
            s: State[A],
            now: Instant,
            every: Duration,
        ): Step[State[A], A] =
          (s.windowEnd, s.pending) match
              case (Present(end), Present(a)) if end <= now =>
                Step(State(Present(now + every), Absent), Present(a), Absent)
              case (Present(end), Present(_))               =>
                Step(s, Absent, Present(end))
              case _                                        => Step(s, Absent, Absent)
    end Throttle

    /** The shell of a rule: `Signal.observe` gives the writes of `source`, and
      * a fiber in the scope of each value sleeps until a wake-up. A new value
      * closes that scope, so the wake-up of the value before it stops; the step
      * of the new value asks for the next one.
      */
    def run[S, A](source: Signal[A], start: S)(
        onWrite: (S, A, Instant) => Step[S, A],
        onWake: (S, Instant) => Step[S, A],
    )(using CanEqual[A, A], Frame): Signal[A] < (Sync & Scope) =
      for
          first <- source.current
          out   <- Signal.initRef(first)
          state <- AtomicRef.init(start)
          _     <- Fiber.init(source.observe { a =>
                     def apply(step: Step[S, A]): Maybe[Instant] < Sync =
                       (step.emit match
                           case Present(v) => out.set(v)
                           case Absent     => Kyo.unit
                       ).andThen(state.set(step.state)).andThen(step.wake)
                     def sleeper(at: Instant): Unit < Async             =
                       Loop(at) { next =>
                         for
                             now0 <- Clock.now
                             _    <- Async.sleep(next.minusOrZero(now0))
                             now  <- Clock.now
                             s    <- state.get
                             more <- apply(onWake(s, now))
                         yield more match
                             case Present(t) => Loop.continue(t)
                             case Absent     => Loop.done
                       }
                     for
                         now  <- Clock.now
                         s    <- state.get
                         wake <- apply(onWrite(s, a, now))
                         _    <- wake match
                                     case Present(at) => Fiber.init(sleeper(at)).unit
                                     case Absent      => Kyo.unit
                     yield ()
                     end for
                   })
      yield out
end Pacing
