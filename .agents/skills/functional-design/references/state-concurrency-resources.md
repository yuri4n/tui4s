# State, concurrency, resources and logging

Where state lives, how concurrent code stays correct, how resources are
acquired and released, and how foundational subsystems (logging, state) are
designed. Sources: Granin ch. 7-10 (main), Ghosh ch. 1-2 and 6-7 (reactive),
Normand ch. 2 and 15-18 (timelines).

## Contents

1. Classify state
2. Choose a state mechanism
3. Concurrency: why it is hard, and the rules
4. STM and transactional design
5. Actors and request-response
6. Bounded processes and thread pools
7. Timelines (Normand)
8. Reactive concerns (Ghosh)
9. Resources
10. Logging and the state subsystem
11. Anti-patterns

## 1. Classify state

State is a design category, not a coding detail (Granin ch. 7). Classify
each piece of state on two axes:

- **Lifetime:** local (one calculation), operational (lives as long as the
  application and drives its logic), external (outlives the application:
  databases, files).
- **Purity:** pure (functional imitation of mutation: argument passing, a state
  monad) or impure (real mutable cells).

Then put it in the layer that may hold it:

- local state inside a function (it may even mutate locally if the function
  stays deterministic);
- operational state in the implementation's runtime;
- external state behind a storage subsystem.

Business logic holds only pure state. Implementations may hold any state.

## 2. Choose a state mechanism

The rows follow Granin ch. 7 unless a row names another chapter.

| Need | Mechanism | Notes |
|---|---|---|
| A small value through a few steps | argument passing (`state -> (result, state')`) | simplest; wordy for deep records |
| One model updated many times in one part of the logic | a state monad over the business language, run locally | prefer one combined state type over stacked state layers |
| Fast mutation inside a pure function | local mutation (ST, a local array) hidden behind a pure signature | invisible from outside (appendix A) |
| Operational data, one thread | a plain mutable reference in the runtime | races are your responsibility |
| A simple two-thread request-response | a synchronized cell (MVar) per request | deadlock risk; does not scale (ch. 8) |
| Shared state, complex models | STM; or one atomic reference that holds the whole immutable model (separate atomic references do not compose and cannot block) | STM composes and blocks with `retry` (ch. 9) |
| The framework must count, inspect, limit, snapshot or persist state | its own state interface over STM, with a registry of variables | extra indirection, slower lookups (ch. 10) |
| Long-lived data | a storage subsystem | lifetime exceeds the application |

Run a local state computation inside a larger scenario so that the state
exists only for that part of the logic. Argument passing is the only truly
immutable mechanism; the others are built on it.

## 3. Concurrency: why it is hard, and the rules

For building or reviewing the constructs themselves (locks, queues, futures,
timeouts, races, pools, cancellation), see `concurrency-constructs.md`.

Concurrent bugs are subtle and appear after deployment, so predict them at
design time (Granin ch. 9). Review every concurrent design for:

- race conditions, deadlock, livelock, starvation;
- unclear resource management, resource leaks;
- overflow when producers are faster than consumers;
- exception handling across threads.

Rules:

- Prefer immutability, then STM or one atomic reference. Granin sees no reason
  to avoid STM except maximum performance or low-level control. Marlow adds
  FIFO fairness, single wakeup and rendezvous as reasons to choose a lock cell
  (`concurrency-constructs.md` section 5).
- Treat threads, even green threads, as a limited resource.
- Business logic never forks raw threads. Expose a bounded process
  abstraction; keep the pool, counters and cleanup in the runtime.
- Do not add operations that change shared limits at run time (a
  "set maximum count" method creates a race by design). Every new operation
  can reopen a solved problem: an "async fork" that waits for a pool slot
  brings back unbounded waiting threads.
- Document the blocking and failure semantics of every concurrent operation.
- Unprotected shared access is dangerous by default; partial writes can be
  visible in many languages.
- **Never export a mutable cell; export operations over it.** With the state
  behind one module, all changes happen in one place, and a later concurrency
  fix (a plain reference becomes an STM variable) does not touch outside code
  (Granin ch. 3 and 5).
- **Every blocking wait needs an end.** For each blocking primitive (a
  `retry`, an await, a take from an empty cell), name the event that ends the
  wait and the failure result if it never comes. An await on an absent or
  failed process must return an error; a `retry` whose variables never change
  blocks forever. Add a timeout when the event comes from outside the process.

## 4. STM and transactional design

Source: Granin ch. 9, except where marked.

- **Separate the data model from the behavior model.** First build the
  concurrent data model from transactional variables. Then write domain
  transactions as small pure functions over them. Then run them in threads.
- Small transactions compose into bigger atomic ones, unlike a concurrent
  queue plus a concurrent map used side by side.
- `retry` rolls back and blocks until a variable that was read changes. It
  replaces condition variables, polling and callbacks.
- Expose one "run atomically" operation in the business language and keep the
  transactions themselves in plain STM.
- **Fine-grained structures:** a map of independent cells
  (`Var (Map K (Var V))`) lets independent values change without rewriting the
  whole map or serializing all threads (Granin ch. 8). Outside STM, per-key
  cells give up atomic multi-key updates.
- Transactions may run several times. Keep I/O and other observable side
  effects out of them (see delayed logging, section 10).

```haskell
-- take a free ticket; with none left, block until another thread returns one
takeTicket :: TVar [Ticket] -> STM Ticket
takeTicket pool = do
  free <- readTVar pool
  case free of
    []       -> retry                  -- wakes when `pool` changes
    (t : ts) -> writeTVar pool ts >> pure t
```

## 5. Actors and request-response

Source: Granin ch. 8.

- An actor has state, a lifetime, a public interface, behavior and its own
  thread. Clients reach it only through its controller.
- **Abstract the protocol.** Requests and responses are a domain contract;
  actors do not know their transport (TCP, broker, simulator).
- **Typed request-response:** each request carries its own reply channel, typed
  by the expected result and created by the requester. A wrong reply type
  cannot be sent:

  ```haskell
  data CacheMsg
    = forall r. Ask (CacheQuery r) (MVar r)    -- the reply slot has the query's result type
    | Stop (MVar (Either StopError ()))        -- shutdown reports its failures too
  ```

- Return a result type even for "shutdown"; a unit reply hides failures.
- Give clients a **control handle** from a `start` function (thread ID plus
  request channel) and helper functions that build requests.
- Synchronized cells are like mutexes: an unpaired take or put deadlocks.
  Move to STM or a message bus as soon as the concurrent model grows
  (Granin ch. 8-9).
- **Contract checklist for components and actors:**
  - clients reach inner parts only through the top controller's protocol;
  - the protocol separates "run" (finite, returns a result) from "start" (runs
    without end, detached);
  - the contract states what types cannot: is the state in memory or external,
    and is it thread-safe? Run one shared contract-test suite against every
    implementation (real, simulator, test).

## 6. Bounded processes and thread pools

Build an implicit pool this way (after Granin ch. 9):

- Keep the client API minimal: `forkProcess` blocks when the pool is full, and
  the documentation says so.
- The runtime holds the maximum and a counter. Acquire a slot with a
  transaction that retries while the pool is full.
- Release the slot with a finalizer tied to the thread's termination, not to
  the caller's discipline.
- The increment and the fork must never desync. Acquire the slot and fork in
  one masked region: the acquire is interruptible only while it blocks, and
  the child is born masked, as with `forkFinally`. The book's version has no
  mask, so a cancel between the two steps leaks a slot. In Cats Effect or
  ZIO, use the runtime's `Semaphore` (`permit`, `withPermit`), not a
  hand-written counter. An STM counter wakes every blocked forker, in no FIFO
  order (`concurrency-constructs.md` sections 3 and 5).
- An explicit pool API (tokens, awaits, a setter for the maximum) exposes
  races and waits on dead processes by design.

## 7. Timelines (Normand)

Normand gives a method to find concurrency bugs before production
(Normand ch. 15-17). Treat a concurrency bug as a problem of order and
repetition. Do not assume that timelines coordinate by themselves, that one
step always finishes first, or that a rare ordering stays rare under
production load (Normand ch. 2).

**Timeline diagrams** (ch. 15):

1. List the actions only (calculations cannot race). A `x++` on shared state
   is a read and a write; arguments run before the call.
2. Put sequential actions on one line and possibly parallel ones (threads,
   processes, async callbacks, timers) on separate lines.
3. Simplify with the platform's model (in single-threaded JavaScript, the
   synchronous steps of one callback cannot interleave).
4. Draw two runs side by side (two fast clicks) and look for shared resources.
   Two parallel actions allow three orderings however they are drawn; the
   number of orderings explodes with timelines and actions.

Timing bugs pass tests; diagrams show them without production (ch. 16).

**Order of preference for fixes** (ch. 15-17). Each step removes orderings
that you must check:

1. Remove timelines: use fewer threads, async calls and requests.
2. Shorten the timelines that stay: turn actions into calculations, and make
   implicit inputs and outputs explicit.
3. Remove shared resources, or give each one a single owner.
4. Share what remains only through safe primitives (queues, locks).
5. Coordinate with primitives (promises, joins) that limit order and
   repetition. Treat time as a value that code can control.

The tools for each situation:

| Situation | Tool |
|---|---|
| logic inside a timeline that is not I/O | move it into a calculation |
| a global used as scratch space, or read implicitly | a local variable or an argument |
| an async result | return it as a value (future, IO, promise) so the caller chooses the effect |
| a shared resource where order matters | a queue with one worker |
| only the latest result matters | a dropping queue |
| parallel work; continue when all finish | a join ("cut", `Promise.all`, `parTraverse`) |
| an action that must run at most once | a run-once wrapper: an in-memory flag, made atomic under threads; across processes or restarts, a persistent dedup record (practice) |
| one state with many causes and effects | a cell with watchers (reactive; Normand ch. 18) |

Build such primitives once, from first-class actions, instead of ad hoc flags
(ch. 16). Normand's primitives share a counter safely only because
JavaScript runs each synchronous piece alone; with threads, use atomics,
locks or the constructs in `concurrency-constructs.md` (ch. 17).

Review rules (ch. 15 and 17):

- **The threading model decides which simplifications are valid.**
  Single-threaded asynchronous code (JavaScript) can treat the synchronous
  steps of one callback as one box. Multi-threaded code (Java, C#, and every
  JVM fiber runtime: Cats Effect, ZIO, Kyo, Ox, Futures) allows any
  interleaving; only locks, atomics or effect primitives order it.
- **Lost update across an async boundary:** reading a shared value, starting
  an async call, and writing `old + result` in the callback is wrong whenever
  the second read happens before the first write. When both calls start
  before either response, every ordering of the writes gives a wrong total;
  spaced calls hide the bug in tests. Read and write in one step after the
  response, or use an atomic modify.
- **Verify every change that adds parallelism, reordering or retries:**
  redraw the diagram with no assumptions; cut it at coordination points and
  check each section's orderings; check the speed claim (parallel waits cost
  the maximum, serial waits the sum); redo the analysis for repeated fast
  triggers.
- **Find where the complexity comes from** before you add coordination:
  moving a combination to the server only moves it.

## 8. Reactive concerns (Ghosh)

Source: Ghosh ch. 6, with other chapters where marked.

- Design for failure and load: decide which operations must not block, bound
  latency with timeouts, and handle failure in a separate, pluggable component
  (also Ghosh ch. 1-2).
- Run independent asynchronous calls in parallel before you combine their
  results; do not parallelize dependent steps. With eager futures, create
  them before the for-comprehension. With lazy effect values (IO, ZIO, Task),
  defining them first starts nothing, so use a parallel combinator
  (`parMapN`, `parTupled`, `both`, `zipPar`; see `concurrency-constructs.md`
  section 11).
- Prefer composable futures or effect values to actors for APIs. Use actors
  for guarded mutable state, supervision, and as the engine under typed
  streams; hide them behind a typed API.
- Use streams with back pressure for unbounded or spiky data, especially
  between contexts. Use bounded buffers and an explicit failure policy (skip a
  bad record or stop) (also Ghosh ch. 7).

| Situation | Choose |
|---|---|
| A few asynchronous calls whose results combine | futures or effect values |
| An unbounded or spiky flow | streams with back pressure |
| Shared mutable state that needs supervision | an actor behind a typed API |
| Communication between contexts | asynchronous messages or events |
| CPU-bound work | run it synchronously on the calling thread, not as asynchronous steps (cache locality, no scheduler overhead; Ghosh ch. 1, in a footnote). This is not the effect system's blocking operator (`IO.blocking`, `ZIO.attemptBlocking`), which is for blocking I/O |

## 9. Resources

Source: Granin ch. 10, except where marked.

- **Bracket** (functional RAII): `bracket acquire release use`. Release runs
  even when `use` fails, then the failure continues, so outer brackets release
  too.
- **One resource, one bracket; nest them.** A god-like `initAll` leaks the
  first resource when the second fails to initialize (a bracket does not
  release on a failed acquire), and a combined `deinitAll` leaves zombies when
  one release fails.
- Use the library's bracket or scoped-resource type; do not write your own in
  a language with asynchronous exceptions or laziness.
- Acquire framework resources (loggers, connections, network channels) in the
  application layer.
- Decide the connection policy explicitly: keep-alive or per query, maximum
  connections, idle timeout, reconnect, loss detection, disposal
  (Granin ch. 11).
- Consider forbidding operations that cause bugs: if the framework owns a
  lifetime, users need no "close" (Granin ch. 11).
- **"Let it crash" does not replace scoped release.** Erlang-style crashes and
  restarts assume a crash is cheaper than full reliability; Granin sees
  problems with that, especially under concurrency. With supervision or
  without it, acquire each resource in its own scope, so a crash leaves
  nothing open.

## 10. Logging and the state subsystem

Source: Granin ch. 10.

Decide these logger properties first: what one call carries (a severity, a
message, extra fields such as time, logger name and tags); which
destinations and how many implementations exist; whether a call waits until
the entry is written; and whether entries are free text or structured
records that you can query.

- Give logging its own small interface, available at every business level.
  Keep destinations, file names and library types out of it.
- Do not use a global mutable logger.
- **Structured logging:** one operation that takes a severity, a logger name, a
  message and a map of named fields with serialized values. Give each target
  service typed field constructors (`sentryTimestamp :: Time -> Field`). Keep
  the service client only in the implementation, so the service can be
  replaced.
- **Delayed logging in transactions:** a transaction cannot do I/O and may
  retry, so direct logging duplicates entries, and returning log lines from
  transactions pollutes domain results. Instead, interpret the logger inside
  the transaction into a log buffer that is part of the same transaction (a
  retry rolls it back), and flush the buffer after each successful commit.
- **A state facade over STM** with the same operations (new, read, write,
  retry). The framework then owns every variable: it can count them, track
  their creators, print snapshots and limit them. Carry optional metadata in
  the handle (a printer `a -> Maybe String`, a creation time); offer an
  untracked fast path for hot code.

## 11. Anti-patterns

- Mixed-level interfaces with logging methods in a domain language
  (Granin ch. 7).
- A raw "run any IO" operation in business logic (Granin ch. 9).
- Raw mutable references shared by parallel scripts: a race by design
  (Granin ch. 9).
- Bare threads with shared unsynchronized state: lost and double-processed
  items, unbalanced producers and consumers, memory growth (Granin ch. 9).
- Unbounded forking, or an escape hatch that reintroduces it (Granin ch. 9).
- Polling with sleeps where a blocking `retry` would wait for the change
  (Granin ch. 9).
- Unpaired take and put on synchronized cells (Granin ch. 8).
- Keeping one map cell for independent, very frequently updated values after
  profiling shows contention on it (Granin ch. 8 warns that it can be slower).
  One immutable value in one cell stays the default
  (`concurrency-constructs.md` section 1). Per-key cells give up atomic
  multi-key updates unless you use STM.
- Logging with real I/O inside a transaction (Granin ch. 10).
- One init or release function for several resources (Granin ch. 10).
