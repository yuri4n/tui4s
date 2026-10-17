# Building concurrent constructs

How to build and review low-level concurrent constructs (locks, channels,
queues, futures, timeouts, races, pools) and how to structure thread
lifetimes and cancellation. Source: Marlow, *Parallel and Concurrent
Programming in Haskell* (2013). The patterns are general; the Haskell names
(MVar, STM, async) map to other runtimes in the last section. Items marked
"(synthesis)" are not from the book. For where state lives and how
concurrency fits the architecture, see `state-concurrency-resources.md`.

## Contents

1. Principles
2. Use the highest-level construct that fits
3. Checklist for a new construct
4. Construct catalog
5. Locks or transactions
6. Cancellation and masking
7. Structured lifetimes
8. Servers and shared state
9. Parallelism
10. Anti-patterns
11. Translation to other runtimes

## 1. Principles

- **Parallelism is not concurrency.** Parallelism uses more hardware to get
  one answer sooner; keep it deterministic so you can test it sequentially.
  Concurrency structures a program around interactions whose effects
  interleave; it is nondeterministic (Marlow p. 18-19).
- **Concurrency is a modularity tool:** one thread per interaction instead of
  an event loop with callbacks and per-client state machines. It needs cheap
  threads and an I/O manager (p. 18, 139, 222-224).
- **A few primitives build everything:** fork, a one-slot synchronizing box,
  transactional variables. Build each higher abstraction once, in a library
  (p. 139-140).
- **The program ends when `main` returns;** other threads die silently.
  Ownership of threads is a service you must build (p. 144).
- **Shared state = an immutable value in one mutable cell.** Writers replace
  the whole value; readers take a snapshot and work outside the critical
  section (p. 151, 299).
- **Fairness is a property of a primitive.** FIFO wakeup, with wakeup and the
  blocked operation done in one atomic step, gives no starvation and a single
  wakeup; transactions give up both (p. 156-157, 207-208).
- **Cancellation is an exception thrown from outside,** so normal cleanup runs
  on cancel, timeouts and Ctrl+C, and third-party code can be cancelled
  without its cooperation. Pure code is safe to abort anywhere (p. 172-174).
- **Exception safety of a shared structure is a protocol property** ("every
  operation is take then put"), checked at every intermediate state, not only
  "the lock is released" (p. 176, 180).
- **Evolve a concurrency API behind stable types.** Marlow's `Async` grew error
  capture, cancel, masking, transactional waits, scoping and `Functor` without
  changing its basic signatures; each step closed a named failure mode
  (p. 219-220).

## 2. Use the highest-level construct that fits

Prefer, in order: scoped futures and symmetric combinators (`race`, both,
parallel map), queues, transactional state. Build a low-level construct only
when none fits, then follow section 3. In a managed runtime (Cats Effect,
ZIO, Kyo, Ox), use its built-in construct (section 11) instead of rebuilding
it.

## 3. Checklist for a new construct

From chapters 7-11 (p. 144-211):

- [ ] Write the state as data and the invariants in words (who points where,
      what "full" means).
- [ ] For each operation, list every intermediate state and check the
      invariants there.
- [ ] For each blocking point: what wakes it? Can the waker be blocked by me
      (deadlock)? Is wakeup FIFO or wake-all?
- [ ] For each point where cancellation can arrive: is the structure
      consistent? If not, mask, and let only the user's action and the first
      blocking acquire be interruptible.
- [ ] Does every fork start masked and end in a completion handler that
      publishes the outcome?
- [ ] Do errors reach the waiter? Can several waiters read the result
      (non-destructive read)?
- [ ] Is the lifetime of every thread bounded by a scope? Who cancels it?
- [ ] Test with capacity 1, many producers, early cancel, child failure and
      nested timeouts.

## 4. Construct catalog

| Construct | Recipe | Invariant | Exception-safety rule | Pitfalls |
|---|---|---|---|---|
| Lock with state | a one-slot box holding an immutable value; take = lock, put = unlock (p. 146-151) | every operation is take, then put; one global order for several locks | update only through a masked helper that puts the old value back on error | a read path without put-back; slow work under the lock; lock-order deadlock |
| One-place hand-off | an empty box; producer puts, consumer takes; transactional: `TVar (Maybe a)` + `retry` (p. 144, 194) | at most one value in flight | a blocked put or take is interruptible; nothing to restore | used as a queue for many producers: all block behind the consumer (p. 148) |
| Unbounded channel | a linked list of boxes with separate read and write pointers, or two lists in transactional variables (p. 151-155, 204) | the write pointer is on the hole; the read pointer is on the next item | lock version: masked read, fully masked write; transactional version: safe as written | memory grows with a slow consumer; push-back is impossible in the lock version |
| Bounded queue | an unbounded queue plus a capacity counter; write retries at zero (p. 206) | items + free capacity = bound | transactional: nothing extra | hides cyclic-wait deadlocks that appear only under load; test with capacity 1 (p. 207) |
| Future (async) | fork into a write-once result slot holding `Either error value`; wait reads without emptying it and re-throws (p. 159-168, 184) | the slot is filled exactly once on every exit path | fork inside a mask; run the action under restore; the completion handler always runs | unscoped futures leak; sequential waits detect failures late |
| Promise / write-once variable | an empty box filled once, read many times (p. 74, 160) | a second write is an error | the writer fills it on every path, or waiters hang | the promise escapes its scope; the error case is forgotten |
| Latch, barrier (synthesis) | a counter in a transactional variable; arrive decrements; await retries until zero; a barrier adds a generation number | arrivals counted once | transactional: nothing extra | a participant that fails never arrives: add a timeout or failure propagation |
| Semaphore | a counter; acquire blocks or fails at zero; release in `finally` (p. 248-249) | units held + units free = total | release through bracket; never release a unit you did not acquire | a lock-based counter becomes the bottleneck; use an atomic cell (p. 251) |
| Timeout | race against a sleep; or a watchdog that throws a unique exception (p. 180-182, 217) | exactly one outcome; the watchdog is dead when timeout returns | catch only the call's own signal | cannot interrupt a blocking native call (p. 304); a catch-all breaks nested timeouts |
| Race, both | two scoped futures plus one transactional wait (p. 215-217) | the loser is cancelled before race returns (the book's cancel only delivers the exception; current async, Cats Effect and ZIO also wait for the loser's finalizers, so require that) | the scope cancels children on every exit | left bias of `orElse`; helper-thread versions leak the losers |
| Worker pool | fork if a unit is free, else run inline; or a work-stealing pool; or N workers on a bounded queue (p. 247-255) | in-flight tasks <= bound; every error reaches someone | workers run in a scope; shutdown closes the queue or cancels the scope | static partitions; too fine grain; pools that drop task errors (p. 255) |
| Bracketed resource | acquire masked, use under restore, release on every exit (p. 178) | release runs if and only if acquire returned | at most one blocking step in acquire; nest brackets for several resources | a blocking release is interruptible; releasing by GC runs out of handles (p. 223) |

Two recipes worth knowing in full:

```haskell
-- masked take-modify-put: the cell is never left empty (p. 174-178)
modify cell f = mask $ \restore -> do
  a <- take cell                          -- interruptible only while blocked: harmless
  r <- restore (f a) `onException` put cell a
  put cell r                              -- cannot block by protocol

-- a thread born masked, whose outcome is always published (p. 184-185)
forkFinally act done = mask $ \restore -> fork (try (restore act) >>= done)
async act = do slot <- newEmptyBox; t <- forkFinally act (put slot); pure (Async t slot)
```

``fork (x `finally` y)`` is a bug pattern: cancellation can arrive before the
handler is installed (p. 185).

## 5. Locks or transactions

| Need | Lock cell (MVar) | Transactions (STM) |
|---|---|---|
| one cell, a simple lock or hand-off | good, faster per operation | fine |
| FIFO fairness, no starvation | guaranteed | no: all waiters re-run |
| single wakeup under contention | yes | no: wake all |
| atomic update over several cells | lock order; deadlock risk | any order |
| choose between blocking operations | helper threads or a redesign | `orElse` |
| cancellation safety | mask + helpers + audit | automatic abort |
| block on an arbitrary condition | no | `retry` |
| rendezvous, fair queue | possible | not compositionally |

(p. 207-211.) Rule: transactions for shared state with invariants and for
blocking structures that must compose; a lock cell when many threads block on
one resource and FIFO order or single wakeup matters, or for a one-shot
hand-off (not for a hot counter, where its fair hand-off is the bottleneck,
p. 251, 300); an atomic reference with one atomic modify for the cheapest
single-cell update (p. 299-300).

Transaction rules:

- Keep transactional operations unwrapped (in STM, not IO), so callers choose
  the atomic boundary (p. 193).
- Keep transactions short and bounded: no I/O, no heavy work, a bounded
  number of variables. Reading an unbounded number of variables is
  quadratic; long transactions can starve behind short ones (p. 210, 240).
- **Decide in the transaction, act after commit:** return the effect to run
  after the commit (p. 229-231):

  ```haskell
  loop old = join $ atomically $ do
    cur <- readTVar shared
    if cur /= old then pure (notify cur >> loop cur)
    else do msg <- readTQueue inbox       -- wakes on a change of shared OR inbox
            pure (handle msg >> loop old)
  ```

- **Wait for a change, do not signal:** `retry` until a read variable differs.
  Writers need not know their observers, and there are no lost wakeups
  (p. 195-197).
- A transaction cannot both publish "I am waiting" and block, so fair or
  rendezvous structures need the "return an effect to run after commit"
  shape, and lose composability (p. 208-209).

## 6. Cancellation and masking

| Model | Pro | Con |
|---|---|---|
| polling (cooperative): the victim checks a flag | no surprise interrupts | forgotten checks mean hung threads |
| asynchronous, with masks | always responsive | critical sections need masks |

(p. 171-172.)

- **Masking is a scoped region with a restore function,** not "interrupts off,
  interrupts on". Blocking operations stay interruptible inside the mask, but
  only while they really block (p. 174-176).
- Keep uninterruptible regions small and free of indefinite blocking; mark
  them for review. Allow interruption in the acquire step only where nothing
  was acquired yet (p. 176-178).
- Give users helpers (modify, with, bracket), not raw mask (p. 177).
- **Do not retry or loop from inside an exception handler:** in Haskell, the
  rest of the loop runs inside the handler's implicit mask, so cancellation
  reaches it only at blocking calls. In Cats Effect and ZIO, the same trap is
  a loop inside a finalizer or release step, which runs uncancelable. Return
  a value and loop outside (p. 182-184).
- **Never catch everything and continue.** Catch-all is right only to clean up
  and re-throw, or to move an exception to another thread. For timeouts and
  cancels, match only your own signal (p. 165-168, 181).
- A conditional acquisition does not fit bracket; write the mask by hand
  (p. 236-237).

## 7. Structured lifetimes

- **Every forked task has an owner and a scope.** The scope cancels the task
  on exit; `withAsync io body = bracket (async io) cancel body` (p. 213-215).
- **Prefer symmetric combinators** (`race`, `concurrently`, parallel traverse)
  to sequential waits, so the first failure ends the group: a failing parent
  cancels its children recursively; a failing child cancels its siblings
  (p. 215-217).
- **Propagate child errors to the waiter;** never let a child die with its
  result slot unfilled (p. 167).
- Fire-and-forget only under an explicit long-lived supervisor, and say who
  stops it.
- **Every loop needs a way to stop.** When a server and a worker loop run
  together, decide what ends the group. `concurrently` waits for both, so a
  worker that loops forever keeps the process alive after the server's
  graceful shutdown; `race` ends the group when either side ends. Make the
  worker observe the shutdown signal, finish or hand back its current item,
  and exit. Test the shutdown path once.
- `timeout = race against a sleep` is the simplest correct timeout; the action
  then sees a generic cancel instead of a specific timeout exception
  (p. 217-218).
- A `Functor` for futures stores "how to read the result", so futures of
  different types can be mapped to one type and waited on together
  (p. 218-219).

## 8. Servers and shared state

- **Thread per connection, guaranteed close:** accept, then fork the handler
  with a completion handler that closes the connection; closing by GC runs
  out of OS handles first (p. 221-225).
- Judge a shared-state design by **what sits under one lock, who must know
  whom, and how many threads exist only to merge event sources**. In
  Marlow's comparison of four designs, one transactional variable plus one
  receive thread per client was the simplest (p. 225-228).
- **One queue per client for all incoming events** keeps the event order
  predictable (p. 233-234).
- **Check and set in one transaction:** of two clients that kick each other,
  only one succeeds (p. 233, 238).
- **Give each external resource one owning thread;** other code sends it
  messages; close the resource in the owner's finalizer (synthesis from
  p. 146-149, 221-225).
- **Across processes or nodes:** use messages, make failure visible (monitors,
  timeouts), give each request its own reply channel, design for duplicate and
  stale messages, and restart failed parts from a known state. One sum type
  per protocol forces handling every case (p. 257-289).
- **Blocking native calls:** run them in an unscoped future and wait on it, so
  the waiter stays interruptible; say that the call itself may continue in the
  background (p. 304-305).

## 9. Parallelism

- Deterministic parallelism gives the sequential result: test and debug
  sequentially (p. 18-19).
- Make sure the work happens in the task you think it does: a task that
  computes only the outermost layer of a lazy value does nothing (p. 38, 77).
- Use dynamic partitioning (many tasks on a work-stealing pool); static
  partitions cap the speedup at the chunk count (p. 40-41).
- Granularity has a sweet spot: too few tasks gives imbalance; too many gives
  overhead and a longer sequential combine. Bound in-flight work (p. 63-64,
  247-255).
- Amdahl's law: 2.2% sequential work caps 64 cores at about 27x; measure
  speedup with wall-clock time (p. 39, 44, 61).
- Streams: keep a bounded look-ahead window of parallel tasks instead of
  forcing the whole list (p. 67-69).
- Separate algorithm from parallelism (strategies, skeletons), so the
  parallelism can be removed or swapped (p. 47-51, 90-93).
- Use threads for parallelism only when the work does I/O or needs
  nondeterminism (p. 241).

## 10. Anti-patterns

- Take without put-back on a read path; a one-slot box as a multi-producer
  queue (p. 148-150).
- Assuming `main` waits for its children (p. 144).
- Adding an operation to a lock-based structure without re-checking every
  blocking path; push-back on a channel deadlocks (p. 155).
- Fork without capturing the child's error; a destructive read in `wait`
  (p. 160, 167).
- Hand-written "interrupts off / on" instead of a scoped mask (p. 174-175).
- Two blocking acquisitions in one acquire step; locks taken in different
  orders (p. 178).
- Hand-written observer notifications (lost wakeups) (p. 197).
- Relying on `orElse` order for fairness; assuming a bounded queue never
  blocks (p. 198, 207).
- Fire-and-forget futures with sequential waits; a hand-maintained registry
  of child threads (p. 214-217).
- One connection handle shared by several threads under a lock; helper
  threads that only merge event sources (p. 226-228).
- One task per tiny work item; a lock-based counter on a hot path; a pool that
  drops task errors (p. 244-255).
- Thread-status queries for control flow; print debugging in hot paths;
  relying on runtime deadlock detection (p. 292-296).

## 11. Translation to other runtimes

(synthesis; verify names against the versions in use)

Semantic differences:

- **Cancellation:** Haskell, Cats Effect and ZIO deliver cancellation
  asynchronously at effect boundaries, with masked regions. JVM threads,
  virtual threads and Ox are cooperative: interruption arrives at blocking
  calls or explicit checks, so CPU loops must check.
- **Masking:** Cats Effect `uncancelable(poll => ...)` makes everything inside
  uncancelable unless wrapped in `poll`; `Resource.makeFull` passes `poll` so
  the acquire step can stay cancelable. ZIO: `uninterruptibleMask(restore =>
  ...)`.
- **Default fork scope:** ZIO `fork` attaches the child to the parent fiber;
  `forkDaemon` is global. Cats Effect `start` is unscoped like a raw fork; use
  `background`, `Supervisor`, or the par and race combinators for scope. Ox
  forks exist only inside a concurrency scope (`supervised`, or
  `unsupervised` for `forkUnsupervised` / `forkCancellable`).
- **Fairness:** Cats Effect and ZIO queues and semaphores are built from atomic
  references plus promises; STM versions (cats-stm, ZIO STM, kyo-stm) wake all
  waiters with no fairness.

| Construct | Cats Effect 3 | ZIO 2 | JVM direct style |
|---|---|---|---|
| atomic cell | `Ref` | `Ref` | `AtomicReference` |
| lock with effectful update | `AtomicCell`, `Mutex` | `Ref.Synchronized` | `ReentrantLock` |
| promise | `Deferred` | `Promise` | `CompletableFuture` |
| bounded queue | `Queue.bounded` (also dropping, circular buffer) | `Queue.bounded`, dropping, sliding | `ArrayBlockingQueue`; Ox `Channel.buffered` |
| broadcast | fs2 `Topic` | `Hub` | `SubmissionPublisher`; or one Ox channel per subscriber |
| semaphore | `Semaphore` (`permit` is a `Resource`) | `Semaphore` (`withPermit`) | `java.util.concurrent.Semaphore` |
| latch, barrier | `CountDownLatch`, `CyclicBarrier` (cats.effect.std) | `zio.concurrent.CountdownLatch`, `CyclicBarrier` (artifact zio-concurrent) | `CountDownLatch`, `CyclicBarrier` |
| scoped child | `background` | `forkScoped`, `fork` | Ox `supervised { fork(...) }` |
| supervisor | `Supervisor` | `Scope` with `forkIn` | Ox `supervised` |
| both, parallel map | `both`, `parTupled`, `parTraverseN` | `zipPar`, `foreachPar` + `withParallelism` | Ox `par` |
| race, timeout | `race` (first to finish), `timeout`, `timeoutTo` | `raceFirst` (first to finish; `race` returns the first success), `timeout`, `timeoutFail` | Ox `raceResult` (first to finish, like CE `race`) or `raceSuccess` (first success); `timeout` |
| mask | `uncancelable(poll => ...)` | `uninterruptibleMask(restore => ...)` | Ox `uninterruptible { ... }` (no poll/restore) |
| bracket | `Resource.make`, `guarantee`, `onCancel` | `acquireRelease`, `ensuring`, `onInterrupt` | try-with-resources; Ox scopes |
| STM | cats-stm (`STM.runtime[F]`, `TVar`, `Txn`, `commit`, `retry`, `orElse`, `check`) | `STM`, `TRef`, `retry`, `orElse` | none in java.util.concurrent (in a Kyo project: kyo-stm `STM.run`, `TRef`, `STM.retry`) |
| interruptible blocking call | `IO.interruptible` (`IO.blocking` is not) | `attemptBlockingInterrupt` | `Thread.interrupt` on interruptible calls only |
