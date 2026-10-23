# Purely functional data structures

How to choose, use and design immutable data structures. Sources: Okasaki,
*Purely Functional Data Structures* (1998, Standard ML with Haskell
appendix); Stone, *Algorithms for Functional Programming* (2018, Scheme).
Items marked (practice) are general practice, not from the books. W = worst
case, A = amortized.

## Contents

1. Persistence and its cost
2. Amortization, persistence and laziness
3. Choosing a structure
4. Catalog
5. Library defaults
6. Laziness: benefits and costs
7. Local mutation behind a pure interface
8. Designing a new structure
9. Review checklist

## 1. Persistence and its cost

Source: Okasaki ch. 2, except where marked.

- Every purely functional structure is **persistent**: old versions stay
  usable after an update (ch. 1).
- The mechanism is **path copying**: copy the nodes on the path to the change
  and share everything else. List append copies the whole first list; a
  balanced-tree insert copies O(log n) nodes.
- Return the original node when nothing changes, to avoid copying the path.
- Equal subtrees can be one shared pointer: a complete tree of depth d with
  one repeated element costs O(d).
- Use a smart constructor that restores the invariant locally (`balance` in a
  red-black tree, `makeT` in a leftist heap), so no other function needs
  invariant checks (ch. 3).
- Parameterize ordered structures by the ordering (an `Ord` instance or a
  comparator), not by hard-coded comparisons.

## 2. Amortization, persistence and laziness

This is the most important and most often misunderstood point.

- **Amortization bounds the total cost of a sequence of operations**, not each
  operation. It allows simpler, faster designs (Okasaki ch. 5).
- **Classic amortized structures assume single-threaded use.** If one version
  is reused (it has several "logical futures", in Okasaki's term), the
  expensive operation can be repeated from each future. Example: a two-list
  queue whose rear list holds n - 1 elements; taking `tail` of that same
  version n times costs O(n²), not O(n) (ch. 5). Typical triggers:
  backtracking search, undo, snapshots, retries, values shared between threads
  or requests, property-test shrinking.
- **Memoized laziness fixes it.** Put the expensive work into a memoized
  suspension stored inside the structure: every future that forces it shares
  one result. Create the suspension early, so cheap operations pay for it
  before anyone forces it (ch. 6). The thunk **must be memoized**
  (call-by-need: `lazy val`, `Eval.later`, Haskell thunks); a re-evaluated
  thunk (`() => A`, `Eval.always`) gives no protection.
- **When worst-case bounds matter** (Okasaki ch. 7, after Raman): real-time
  systems, lockstep parallel systems, interactive systems where users prefer
  consistent latency. Otherwise choose by overall speed and simplicity.
- **Scheduling** turns a lazy amortized structure into a worst-case one: make
  each suspension incremental (O(1) intrinsic cost), keep a schedule of
  unevaluated suspensions in the structure, and let each operation force a
  few of them (ch. 7).
- Designing amortized first and then adding a schedule is often easier than
  designing worst-case from scratch.
- In a fully lazy language, nominally worst-case code becomes amortized,
  because work is suspended and paid later in one burst. Worst-case structures
  need strictness where it matters.
- **Rebalancing strategies** (ch. 8):

  | Strategy | Bounds | Persistent-safe | Complexity |
  |---|---|---|---|
  | local rebalancing on every update (red-black, AVL) | W | yes | moderate |
  | batched rebuilding (let balance degrade, then rebuild once) | A | no | low |
  | global rebuilding (rebuild a copy incrementally) | W | yes | high |
  | lazy rebuilding (memoized rebuild) | A | yes | low |
  | lazy rebuilding + scheduling | W | yes | moderate |

  Batched rebuilding needs "weak" updates: deletion by marking (tombstones)
  is weak; insertion is not.

## 3. Choosing a structure

1. Write the operation profile: which operations, how often, which must be
   fast, and the size range.
2. Decide whether the bound must hold per operation (latency) or in total
   (throughput).
3. Ask the persistence question: will any version be used again after an
   update?
4. Pick from the tables below, and state the bound with its kind: "O(1)
   amortized, persistent-safe", "O(log n) worst case".
5. Hide the choice behind a small interface (trait, type class, module
   signature), so a batched variant can become a real-time variant after
   measurement (Okasaki ch. 2 and the appendix).

| Usage | Latency matters? | Choose |
|---|---|---|
| single-threaded (each version used once) | no | ephemeral amortized structures: simplest and fastest |
| persistent (versions reused) | no | lazy amortized structures with memoization |
| any | yes | worst-case structures (scheduled or strict) |
| strict language without memoized laziness | yes, or persistent | strict worst-case structures |

Heaps (from Okasaki's hints to practitioners, ch. 3-10):

| Need | Choose |
|---|---|
| ephemeral, no merge | splay heap with a cached minimum |
| ephemeral, with merge | pairing heap |
| persistent, simple, O(log n) for everything | leftist heap or binomial heap |
| persistent, O(1) insert | lazy binomial (A) or skew binomial (W) heap |
| persistent, O(1) merge and find-min | bootstrapped skew binomial heap |

Queues and sequences:

| Need | Choose |
|---|---|
| FIFO, single-threaded | two-list (batched) queue |
| FIFO, persistent, amortized | banker's or physicist's queue |
| FIFO, worst-case | real-time queue (lazy) or Hood-Melville queue (strict) |
| deque | banker's deque (A) or real-time deque (W) |
| list with fast append | catenable list (a tree of queues) |
| list with random access | skew binary random-access list |
| general persistent sequence | finger tree (Haskell `Data.Sequence`) (practice) |

## 4. Catalog

Grouped by kind, simplest first. The last column gives the Okasaki chapter.

| Kind | Structure | Operations and bounds | Persistent-safe | Choose it when | Ch. |
|---|---|---|---|---|---|
| sequence | cons list | cons, head, tail O(1); append O(n of first list); index O(i) | yes | stack use, front traversal | 2 |
| sequence | skew binary random-access list | cons, head, tail O(1) W; lookup, update O(min(i, log n)) W | yes | list operations plus indexing | 9 |
| sequence | catenable list | head O(1) W; cons, snoc, append, tail O(1) A | yes | many appends of persistent lists | 10 |
| sequence | catenable deque | all O(1) A including append | yes | heavy catenation at both ends; complex code | 11 |
| queue | two-list queue | snoc, head O(1) W; tail O(1) A, O(n) W | no | single-threaded FIFO, where it is hard to beat | 5 |
| queue | banker's queue | snoc, head, tail O(1) A | yes | persistent FIFO | 6 |
| queue | bootstrapped queue | head O(1) W; snoc, tail O(log* n) A | yes | fast persistent queue | 10 |
| queue | real-time queue | all O(1) W | yes | latency bounds or heavy persistence | 7 |
| queue | Hood-Melville queue | all O(1) W, strict code | yes | worst-case without laziness | 8 |
| deque | banker's deque | all six end operations O(1) A; reverse O(1) | yes | persistent deque | 8 |
| deque | real-time deque | all O(1) W | yes | latency-bounded deque | 8 |
| heap | leftist heap | find-min O(1); insert, merge, delete-min O(log n) W | yes | simple mergeable heap | 3 |
| heap | binomial heap | all O(log n) W; insert O(1) A single-threaded | W bounds yes | base for faster variants | 3 |
| heap | lazy binomial heap | insert O(1) A; others O(log n) A | yes | persistent heap | 6 |
| heap | skew binomial heap | insert O(1) W; others O(log n) W | yes | many inserts, worst-case | 9 |
| heap | bootstrapped heap | find-min, insert, merge O(1) W; delete-min O(log n) W | yes | many merges | 10 |
| heap | splay heap | insert, delete-min O(log n) A; merge O(n) | no | ephemeral heap without merge | 5 |
| heap | pairing heap | find-min, insert, merge O(1) W; delete-min O(log n) A | no | ephemeral heap with merge | 5 |
| set, map | red-black tree | member, insert O(log n) W | yes | default ordered set or map; among the fastest balanced trees in practice | 3 |
| set, map | trie | lookup, insert O(key length × edge-map cost) | yes | string or sequence keys, prefix queries, many failed lookups | 10 |

Design techniques behind the catalog (each a reusable recipe), simplest
first:

- **Cache a summary at the root** (Okasaki ch. 3): wrap a structure and store
  its minimum or size, so the query is O(1).
- **Build in bulk** (ch. 3): pairwise merging builds a heap in O(n); a sorted
  list builds a balanced tree in O(n). Prefer `fromList` to n inserts.
- **Numerical representations** (ch. 9): a container of size n mirrors a
  representation of n. Insert is increment, delete is decrement, merge is
  addition. Pick a number system in which the needed arithmetic is O(1)
  (skew binary for increment and decrement; zeroless digits for O(1) head),
  then a tree family whose sizes match the digit weights.
- **Structural decomposition** (ch. 10): build an unbounded structure from
  bounded ones, often with a nested type such as
  `data Seq a = Nil | Cons a (Seq (a, a))`, which needs polymorphic recursion
  and an explicit type signature.
- **Structural abstraction** (ch. 10): get an efficient join from an
  efficient insert with a collection of collections:
  `data B a = E | B a (C (B a))`, and join = insert one collection into the
  other.
- **Tries over an edge map** (ch. 10): `Trie k v = Trie (Maybe v) (Map k
  (Trie k v))`; the edge map is a parameter. A failed lookup can stop after a
  few characters, while a hash table must hash the whole key.
- **Implicit recursive slowdown** (ch. 11): small digits at the ends and a
  suspended middle structure of pairs; each recursion leaves a "safe" digit,
  so two operations never both recurse at one level.

## 5. Library defaults

Use the language's standard persistent collections first; write a custom
structure only when the operation profile is not served and measurement shows
a need (practice).

| Structure | Where | Main bounds |
|---|---|---|
| radix-balanced vector trie | Scala `Vector`, Clojure vectors | index, update, append "effectively constant" (log base 32) |
| hash trie (HAMT / CHAMP) | Scala `HashMap`/`HashSet`, Clojure maps, Haskell `unordered-containers` | effectively constant, as above |
| red-black tree | Scala `TreeMap`/`TreeSet` | O(log n) W |
| weight-balanced tree | Haskell `Data.Map`/`Data.Set` | O(log n) W |
| Patricia trie on integer keys | Haskell `Data.IntMap` | O(min(n, word size)) |
| finger tree | Haskell `Data.Sequence` | ends O(1) A; index and split O(log(min(i, n−i))) |
| two-list queue | Scala `immutable.Queue` | amortized; single-threaded |
| memoized lazy list | Scala `LazyList`, Haskell lists | memoized, so persistent reuse is safe; holding the head retains memory |

Cost traps of a cons list (practice): append, `last`, `length`, indexing and
`contains` are O(n). Do not use a list for indexed access, appending at the
end in a loop, or membership tests. By profile: stack or front traversal, a
list; indexed access or end-append, a vector or finger tree; key lookup, a
hash trie (or a balanced tree for order and ranges); FIFO, a library queue;
priorities, a heap or an ordered map keyed by (priority, id).

## 6. Laziness: benefits and costs

Source: Okasaki ch. 4, except where marked.

- Use laziness for one of three reasons only: incremental consumption
  (streams, `take n`), sharing an expensive result between futures, or avoiding
  work that may never be needed. Otherwise prefer strict code: every
  suspension costs time and memory (also ch. 6).
- **Monolithic vs incremental:** a monolithic lazy computation runs to the end
  once forced (`reverse`); an incremental one yields one piece per force.
  Laziness pays only for incremental use; for monolithic work, one suspension
  around a strict result is cheaper.
- **Do not force too early:** a function that pattern-matches its suspended
  arguments at entry forces them at call time.
- **Cascading forces:** left-nested lazy appends `((a ++ b) ++ c) ++ d`
  re-traverse `a` for each append and force a chain at once (ch. 7, 10). Use
  a builder, a difference list, a catenable sequence, or right association.
- **Space leaks** (practice): lazy accumulators (use strict folds such as
  `foldl'`), repeatedly updated lazy map values (strict maps, strict fields),
  and holding the head of a memoized lazy list during traversal.
- **Stone's single-use rule:** for unmemoized lazy sequences and two-list
  queues, never consume the same value twice; continue with the returned
  "rest" (Stone ch. 3). Memoized designs remove this need.
- **"Lazier is better" optimizations can break bounds:** delaying a force or
  skipping a rebuild may let a reused version redo work (Okasaki ch. 6).

## 7. Local mutation behind a pure interface

- Use local mutation only when measurement shows the persistent version is the
  bottleneck (practice).
- Keep it unobservable: create, mutate and freeze inside one function. Haskell
  `ST` with `runST`; Scala local builders (`ListBuffer`, `ArrayBuffer`, an
  `Array` or a `var` that never escapes); Clojure transients (practice).
- Never mutate a structure that a caller may still hold; never share a builder
  between threads or effects; return an immutable value.
- Typical fits: building a large collection once, sorting a private array,
  dynamic-programming tables, union-find inside one algorithm run, O(1)
  visited marks in graph algorithms. Bird & Gibbons ch. 9 notes that the best
  known pure union-find seems asymptotically worse than the imperative one,
  because path compression needs constant-time array updates.

## 8. Designing a new structure

1. Write a simple list-based reference model first, with constructors,
   selectors, a validity predicate for the invariants, and equality on
   abstract contents (Stone ch. 3).
2. Try Okasaki's recipes in order of simplicity: path copying with a smart
   constructor; batched rebuilding if single-threaded; lazy rebuilding with
   memoized suspensions if persistent; scheduling if worst-case is needed;
   numerical representations for sequences and heaps; structural abstraction
   to add a join; tries for aggregate keys; implicit recursive slowdown for
   deques with catenation.
3. Encode invariants in types where the language allows (nested types,
   smart constructors, private constructors); otherwise check them with the
   validity predicate in tests.
4. Define equality, printing and hashing on abstract contents, never on the
   internal representation: two queues with different splits are equal
   (Stone ch. 3).
5. Property-test the efficient structure against the reference model.
6. Prefer the simplest design that meets the bound you need. Worst-case
   catenable lists and deques (Kaplan and Tarjan) and a direct functional
   port of Brodal's heap are complex and, for the heap, slow. Okasaki's
   bootstrapped catenable list and his catenable deques by implicit recursive
   slowdown are simpler, though amortized and, for the deques, still not
   small. His bootstrapped skew binomial heap is both simple and worst-case
   (O(1) insert, merge and find-min) (Okasaki ch. 10-11).
7. Choose the loosest balance invariant that still bounds the worst case:
   strict balance makes search fast but repair slow (Stone ch. 4).
8. Test equality last in tree searches (Andersson's trick): at most d + 1
   comparisons for depth d (Okasaki ch. 2).

## 9. Review checklist

- Is a list used for indexing, end-append, `length` or membership in a loop?
- Is there a left-nested `++` chain or repeated append at the end?
- Is an amortized structure reused from an old version (search, retries,
  snapshots)? Is a bound labeled "O(1)" without "amortized, single-threaded"?
- Is a lazy value consumed twice without memoization, or does a lazy
  accumulator build thunks?
- Is a whole collection sorted to get its minimum or k-th element? (Use a heap
  or quickselect.)
- Is an expensive comparison (strings, trees) repeated where a trie or a hash
  would avoid it?
- Is any mutation visible outside the function that performs it?
- Are splay trees used for sets or maps in pure code? (Queries must
  restructure; splay trees fit only the heap interface; Okasaki ch. 5.)
- Is the top k taken with `take k . sort`? The cost depends on the sort. A
  lazy insertion sort gives O(n·k) (Okasaki ch. 4); a lazy merge sort such as
  GHC's gives about O(n + k log n) (practice); a strict-language sort is
  O(n log n) for any k (practice). For small k on large input in strict code,
  use a bounded heap or a partial sort.
- Does a fold over an unordered collection (hash set, hash map, parallel
  reduce) use a combiner whose result depends on order? It must not
  (Stone ch. 3).
- Is an immutable structure held in an atomic reference updated with
  compare-and-swap or in STM? A retried update can repeat an amortized
  structure's expensive step; keep updates cheap or use a worst-case structure
  (practice).
- Does a layout share prefixes when it should share suffixes? Sharing makes
  all suffixes of a list O(n) to produce (Okasaki ch. 2); n related prefixes
  cannot share and cost O(n²) in total (practice).
