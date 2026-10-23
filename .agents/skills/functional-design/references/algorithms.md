# Functional algorithm design

How to design, derive and check efficient algorithms in a functional style.
Sources: Hughes, *Why Functional Programming Matters* (1990); Bird & Gibbons,
*Algorithm Design with Haskell* (2020); Bird, *Pearls of Functional Algorithm
Design* (2010). Items marked (practice) are general practice that the books do
not cover. Sketches are Haskell-like: map `foldr`/`foldl` to the project's
fold or reduce, `scanl` to scan, `unfoldr`/`iterate` to unfold or generate,
and `concatMap` to `flatMap`.

## Contents

1. Modularity and glue (Hughes)
2. The workflow: specification, derivation, oracle
3. Folds, unfolds and scans
4. Laws and their side conditions
5. Choosing a strategy
6. Divide and conquer
7. Greedy algorithms
8. Thinning
9. Dynamic programming
10. Exhaustive search
11. Laziness and space
12. Derivation tactics (from the Pearls)
13. Review checklist

## 1. Modularity and glue (Hughes)

Source: Hughes.

- The real benefit of functional programming is **modularity**: small, general
  parts that are quick to write, reusable and testable alone.
- You can split a problem only in the ways you can glue the parts back
  together. The two kinds of glue are **higher-order functions** (glue
  functions) and **lazy evaluation** (glue whole programs).
- **For every recursive data type, write its fold (and map)** in the same
  module, and make callers use them instead of repeating the recursion.
- **Separate the producer from the consumer:** a generator of candidates or
  approximations (possibly infinite), optional pruning or transforming stages,
  and a selector (first match, best by cost, within tolerance). Swap the
  selector without touching the generator.
- **Optimize by inserting stages** (sort, take, prune), not by rewriting a
  monolith. In Hughes's game-tree search, depth limits, move ordering and a
  top-n cut are separate stages, and alpha-beta comes from a change to the
  backup stage alone.
- **Stages with the same input and output type compose with themselves**: a
  sequence-to-sequence improver can be applied repeatedly.
- **Do not mix laziness with effects.** When producing a candidate does I/O,
  use an explicit effectful stream with explicit demand and resource scoping.
- Hughes's call for laziness everywhere is a 1990 position, with no cost
  model. In strict languages, get the same producer/consumer split with
  explicit pull-based streams, iterators, views or lazy lists, and keep demand
  explicit. Do not make a whole program lazy.

## 2. The workflow: specification, derivation, oracle

Bird and Gibbons's method: write a clear specification from standard parts,
then fuse it into an efficient program with laws (Bird & Gibbons, preface;
ch. 1).

0. **Library first.** Most professional programmers take algorithms from a
   library (preface). Look for a library function or a reduction to a
   standard problem (sort, select, shortest path, longest increasing
   subsequence, edit distance, union-find). Derive a custom algorithm only
   when the specification matches none of them.
1. **Write the obvious version first**, often
   `best . filter valid . candidates`. Make it correct before making it fast.
   State which inputs are valid. Handle the degenerate inputs that the data
   allows (an interval with start == end, a zero weight); reject only inputs
   that break the specification (start > end), at the boundary, with an
   error that names the bad element (`Either`, not `Maybe` or a silent
   filter), and test the rejection (practice).
2. **Keep it in the code base** as the reference (test-only or a `spec`
   module). It is the oracle. Do not delete it when the fast version lands.
3. **Decide whether to optimize.** If the clear version meets real input sizes
   and latency budgets, stop. Tupling and other clutter come last (ch. 1).
4. **Optimize in small steps, one law per step:** fusion, tupling,
   accumulating parameter, scan lemma, change of representation, change of
   strategy. Run the property test after each step.
5. **Compare the fast version with the oracle** using property-based tests on
   small random inputs and degenerate inputs: empty, one element, duplicates,
   all equal, sorted, reverse sorted, negatives, ties, collinear points. In
   the convex-hull pearl, this kind of test found a real bug (Pearls ch. 23).
6. **Write each side condition** you rely on (associativity, monotone cost,
   non-negative weights, finite input) as a comment, and as a property test
   when it can be checked.
7. **Record the complexity:** the recurrence, time and space, worst, average
   or amortized, and what the data structures assume. Validate it with a
   doubling experiment: time ×2 means linear, ×4 quadratic, ×8 cubic
   (Bird & Gibbons ch. 2).

```haskell
referenceBest xs = minimumBy (comparing cost) (filter valid (candidates xs))  -- slow, obvious
best xs          = fastVersion xs                                            -- derived
-- property: for small xs, cost (best xs) == cost (referenceBest xs) && valid (best xs)
```

When several optima exist, compare **costs and validity**, not exact outputs:
the fast version may break ties differently (Bird & Gibbons ch. 7).

- **If users care which optimum they get** (earliest, fewest changes, stable
  order, visual balance), put that preference into the cost as a
  lexicographic second component, or add a documented tie-break, and test it.
  Bird & Gibbons show both effects: an optimal schedule that acts earlier
  than users expect, and two line-breaking folds with equal cost but
  different layouts (ch. 12).
- **Never make an optimization correct by relying on the order in which a
  generator emits candidates.** Keep the generator independent of the
  selection; if the result must depend on order, put the order in the
  specification and test with candidates in different orders. A generator
  need not list every candidate, only every candidate that may turn out to be
  best (ch. 7).
- **Measure pruning and memoization.** In the Countdown pearl, stronger
  legality tests cut the expressions examined about 100 times and the run
  time about 40 times, a further
  dominance step cost more than it saved, memoizing full expressions did not
  pay, and memoizing compact skeleton trees (operators inserted later) about
  halved the run time, mostly because garbage collection fell from about half
  of the run to about 5% (Pearls ch. 20). Measure at realistic sizes, include
  allocation and GC time, memoize compact values or shapes rather than large
  ones, and prefer cheap local pruning at generation time to global dominance
  checks.

## 3. Folds, unfolds and scans

- "Combine each element into an accumulator" is a fold; "produce a sequence
  from a seed" is an unfold or `iterate`; "running totals or running state" is
  a scan (Hughes).
- **Fold direction matters.** Right folds nest appends to the right
  (`concat = foldr (++) []` is linear). `foldl (++) []` and `acc ++ [x]` in a
  loop are quadratic; Bird & Gibbons measure a gap of about 100 times on one
  input (Bird & Gibbons ch. 1-2).
- **Scan lemma:** use `scanl`/`scanr` instead of mapping a fold over every
  prefix or suffix: O(n) instead of O(n²).
- **Tupling:** two results from one list (min and max, sum and count) come from
  one fold with a pair accumulator, not two passes (ch. 6).
- **Online algorithms** process a stream and answer for every prefix: left to
  right, usually with `scanl` (ch. 1).
- **Different definitions give different algorithms.** An inductive `perms`
  (insert each element) leads to insertion sort; a recursive one (pick an
  element, permute the rest) leads to selection sort. Divide and conquer is
  usually recursive; greedy and thinning are usually inductive (ch. 1).
- When a computation is a state machine with several modes, model the state as
  a small record with a `step` function, then fold `step` (Pearls ch. 14, 17).

```haskell
data Stats = Stats !Int !Int !Int !Int  -- strict fields: no thunk chain per component
stats   = foldl' step (Stats 0 0 maxBound minBound)
  where step (Stats n s lo hi) x = Stats (n + 1) (s + x) (min lo x) (max hi x)
running = scanl (+) 0           -- all prefix sums in one pass
```

## 4. Laws and their side conditions

Most of these laws, and how to use them, come from Bird & Gibbons ch. 1.

| Law | Statement | Condition |
|---|---|---|
| map fusion | `map f . map g = map (f . g)` | none |
| fold-map fusion | `foldr f e . map g = foldr (f . g) e` | none |
| foldr fusion | `h . foldr f e = foldr g (h e)` | `h (f x y) = g x (h y)`; finite lists, or `h` strict for infinite or partial lists; may hold only "in context" (for `y` built by the same fold) |
| foldl fusion | `h (foldl f e xs) = foldl g (h e) xs` | `h (f y x) = g (h y) x` |
| tupling | `(foldr f1 e1 xs, foldr f2 e2 xs) = foldr f (e1, e2) xs` with `f x (y, z) = (f1 x y, f2 x z)` | none |
| scan lemma | `map (foldl f e) . inits = scanl f e`; `map (foldr f e) . tails = scanr f e` | none |
| filter distribution | `filter p . concatMap g = concatMap (filter p . g)` | none |
| append | `foldr f e (xs ++ ys) = foldr f (foldr f e ys) xs` | none |
| accumulating function | build `(xs ++) . (ys ++)` and apply it to `[]` at the end | turns left-nested appends into right-nested ones |
| min distribution | `minWith f (concat xss) = minWith f (map (minWith f) xss)` | finite, non-empty lists |
| monotone min | `minimum . map f = f . minimum` | `f` monotone, non-empty list |

How to use foldr fusion: to fuse a post-processing `h` into a producer built
with `foldr f e`, look for a `g` that satisfies the fusion condition. If no
such `g` exists using only `x` and `h y`, you need more information: tuple it,
or strengthen the result.

## 5. Choosing a strategy

Bird & Gibbons organize their book around five strategies (preface); each is
a different way to fuse "generate candidates, then select the best". For an
optimization problem, try greedy before thinning (ch. 12), then choose
thinning or dynamic programming by the shape of the generator, then search
(see the decision flow below).

| Situation | Strategy | Condition that makes it correct | Program shape |
|---|---|---|---|
| Independent subproblems, each a constant fraction of n, a cheap combine | divide and conquer | independence and size fraction | split, recurse, combine; or build a tree and flatten it |
| Extending a best partial candidate never loses to extending a worse one | greedy | monotone cost under extension, or an exchange argument | `foldr gstep c0`, or `until final gstep s0`, often with a priority queue |
| Fold-shaped generation; greedy fails; a dominance order exists | thinning | extension-monotone preorder | `minWith cost . foldr tstep [start]`, with a small sorted frontier |
| A recursion with overlapping subproblems and a polynomial key space | dynamic programming | monotonicity gives optimal substructure | lazy table, row-by-row fold, or memo table |
| None of the above | exhaustive search | prefix-closed validity for pruning; admissible heuristic for A* | generator with pruning; BFS or DFS with a visited set; A* |

Rule of thumb: if the recursion is inductive (one element at a time), thinning
fits; if it splits in the middle or chooses a split point, dynamic
programming fits (Part V intro). Reductions are valid designs too (longest
common subsequence via longest increasing subsequence).

Quick decision flow:

1. Write the specification. Fast enough? Stop.
2. Streaming or huge input? Make producers lazy or streaming, consumers
   bounded, and producers free of effects.
3. "Choose the best of generated candidates"? Try greedy; check monotonicity
   or exchange; test against the oracle.
4. Greedy fails? If generation is fold-shaped, try thinning.
5. Overlapping subproblems? Dynamic programming.
6. Still nothing? Search with pruning, canonical states, a visited set and a
   heuristic; check feasibility first.

## 6. Divide and conquer

- Real divide and conquer needs subproblems that are a **fraction** of n (not
  n-1) and **independent** (no shared sub-subproblems). Overlap means dynamic
  programming (Bird & Gibbons, Part II intro).
- Recurrences: `T(n) = T(n/2) + Θ(1)` is Θ(log n); `T(n/2) + Θ(n)` is Θ(n);
  `2T(n/2) + Θ(n)` is Θ(n log n) (ch. 4-6).
  `T(n) = T(n-1) + Θ(n)` is Θ(n²) (ch. 2).
- **Binary search as "find the boundary of a monotone predicate":** first bound
  the answer by doubling, then find the smallest `x` with `t <= f x`, one
  comparison per step. Watch fixed-width integer overflow (ch. 4).
- **Saddleback search** for a function increasing in two arguments: each probe
  discards a row or a column; ask "what does one probe let me throw away?"
  (ch. 4).
- **Sorting as build-then-flatten:** `sort = flatten . mkTree`, and the tree
  chooses the algorithm: a search tree gives quicksort, a balanced leaf tree
  gives mergesort, a heap gives heapsort, buckets give radix sort (ch. 5).
  Sorting qualities: fast, smooth (faster on sorted input), stable, compact.
  Use the library sort unless you need a specific guarantee.
- **Selection** (k-th smallest): derive from `sort xs !! (k - 1)` (k counts
  from 1) with three-way partitioning; median of medians guarantees O(n)
  (ch. 6).
- **The data structure decides whether the split pays off:** splitting a list
  costs linear time, so a logarithmic algorithm needs arrays or size-annotated
  trees (ch. 6).
- **Generalize the function** (an extra interval or invariant parameter) when
  the recursion needs it, for example "smallest free number from `a`"
  (ch. 6).

## 7. Greedy algorithms

- Greedy keeps one best partial candidate per step. It is correct only if a
  locally best step never rules out a global optimum (Bird & Gibbons,
  Part III intro).
- **Justify it** in one of these ways (ch. 7-8):
  - **monotonicity:** `cost c <= cost c' => cost (step x c) <= cost (step x c')`;
  - **exchange argument:** any optimal solution can be rearranged to begin
    with the greedy choice (Huffman, Kruskal, Prim);
  - a known theorem for the structure.
- **Ties break proofs.** Argue about "some optimal solution", or use a finer
  (lexicographic) cost with a unique minimum (ch. 7).
- **When the natural cost fails**, try a finer cost with the same optimum
  before you give up on greedy (sorting, coins, minimum-height trees, line
  breaking).
- **Safe cases:** insertion and selection sort; Huffman coding (merge the two
  lightest); minimum spanning trees (Kruskal, Prim); shortest paths with
  non-negative weights (Dijkstra); canonical coin systems such as UK and US
  decimal coins (verify each system); fractional knapsack; line breaking when
  cost = (number of lines, width of the last line).
- **Unsafe cases:** coin changing with arbitrary denominations (`[4, 3, 1]`
  for 6 gives 4+1+1, not 3+3); 0/1 and integer knapsack; line breaking with
  squared waste; shortest paths with negative edges; longest increasing
  subsequence; matrix-chain bracketing (ch. 7, 10, 12).
- **Procedure:** write the exhaustive specification; propose the greedy step;
  state the monotonicity or exchange claim; **test greedy against the
  exhaustive oracle** on small random and adversarial inputs (ties, `[4, 3, 1]`,
  zeros); if it fails, refine the cost; then move to thinning or DP.
- **Implementation:** `foldr gstep c0` over components or `until done gstep s0`
  over a state; use a priority queue for repeated extraction of the best
  candidate; check whether a two-queue trick applies when new priorities are
  monotone (Huffman becomes linear after sorting) (ch. 8).

## 8. Thinning

Bird & Gibbons's own strategy, between greedy (one candidate) and exhaustive
search (all candidates): keep a small set of partial candidates and drop any
that can never become the best (Bird & Gibbons, Part IV intro). In general
terms it is **dominance pruning**: keep a Pareto frontier of partial
solutions. "Thinning" is their name; such problems are usually solved by
dynamic programming, and their claim that thinning is often more effective
is their judgement, supported by their examples. Choose between thinning and
dynamic programming by the shape of the generator (one element at a time, or
splits), not by the name of the problem (Part V intro; ch. 14).

- Define a **dominance preorder** `x <= y` ("x is at least as good now and no
  worse in any future"). It need not be total, so it can combine criteria
  (ch. 10).
- **Recipe** (ch. 10):
  1. Fuse the validity filter into the generator, if a bad candidate has no
     good extension.
  2. Introduce thinning with `x <= y => cost x <= cost y`.
  3. Prove extension monotonicity: anything `y` can grow into, a dominating `x`
     can grow into something at least as good.
  4. Keep candidates sorted by a key that puts dominated candidates next to
     each other, so one linear scan removes them.
- **Try greedy first:** it may work with a refined cost (ch. 12).
- Examples and their preorders: change-making for any denominations (same
  residue, fewer coins; O(n²k)); 0/1 knapsack (more value and less weight; with
  integer weights at most W+1 survivors, O(nW)); longest increasing
  subsequence; longest common subsequence; layered shortest paths (one path
  per vertex); line breaking with any admissible cost (ch. 10-12).
- Thinning needs no integer assumption for correctness, only for its
  worst-case bound. With real-valued weights the frontier can stay exponential
  (ch. 10).

## 9. Dynamic programming

- Use it when subproblems **overlap** (the call graph is a DAG with shared
  nodes), optimal solutions are built from optimal sub-solutions (state the
  monotonicity condition), and the number of distinct subproblems is
  polynomial (Bird & Gibbons, Part V intro).

| Style | Use when | Cost |
|---|---|---|
| memoized recursion (top-down) | only a sparse subset of subproblems is reached; arguments encode to keys | an extra map, key encoding, recursion depth; closest to the specification |
| tabulation (bottom-up) | dense, regular dependencies (grids, intervals, layers) | computes unused entries; restructures code |
| rolling rows or a small state | each step depends on a few previous values | O(row) space; store choices to reconstruct the answer |

- **Know the cost of an "update" before you port an array algorithm:** a
  copy-on-update immutable array is O(n) per update (Bird & Gibbons ch. 3); a
  tree-backed persistent vector (Scala `Vector`, Haskell `Data.Sequence`) is
  O(log n) (practice); local mutation behind a pure interface is O(1)
  (practice). Array tricks such as binary-search LIS and path-compressed
  union-find lose their bounds with copying updates (ch. 9, 11). Prefer bulk
  builds and divide and conquer to per-element updates.
- **In a pure language**, do not port an in-place table fill (each update
  copies). Use a lazy array defined in terms of itself (needs lazy elements), a
  fold over rows, `scanl`/`iterate` for linear recurrences, or a local mutable
  array behind a pure interface when profiling demands it (ch. 13).

  ```haskell
  -- a, b :: Array Int Char, indexed from 1, e.g. listArray (1, length s) s
  editDistance a b = table ! (m, n)
    where
      (_, m) = bounds a
      (_, n) = bounds b
      table = listArray ((0, 0), (m, n)) [f i j | i <- [0 .. m], j <- [0 .. n]]
      f 0 j = j
      f i 0 = i
      f i j = minimum [ table ! (i - 1, j) + 1, table ! (i, j - 1) + 1
                      , table ! (i - 1, j - 1) + fromEnum (a ! i /= b ! j) ]
  ```

- With memo helpers (a hash map cache), keep the recursive function pure apart
  from the memo; make keys canonical, small and hashable; bound the cache when
  inputs are unbounded (practice).
- **Reconstruct the answer** by storing the choice (or the partial solution)
  with each value (ch. 14).
- Before tabulating an interval recursion, look for structure: some costs have
  a closed form or a greedy solution; the quadrangle inequality with monotone
  weights limits split points and gives O(n²) instead of O(n³) (ch. 14).
- **Shared structure instead of a table:** when a recursion is a fold after an
  unfold with overlapping calls, build the shared call structure bottom-up
  (Bird's "nexus") (Pearls ch. 21).
- Knapsack DP is O(nW): pseudo-polynomial; it needs integer capacities.

## 10. Exhaustive search

- **List of successes:** `solutions = filter good . candidates`. Laziness
  computes only what is needed, but the **generation order** decides
  time-to-first-answer (n-queens: same answers, very different first-answer
  time) (Bird & Gibbons ch. 15).
- **Backtracking:** fuse the test into the generator when validity is
  **prefix-closed** (a bad partial candidate has no good extension); check only
  the new part of each extension (Bird & Gibbons ch. 15).
- **Pair candidates with their values** and compute a child's value from its
  parent's value. **Prune with a weaker necessary condition** (`ok`) that
  every good candidate satisfies and that holds for prefixes (Pearls ch. 6).
- **Canonicalize** symmetric candidates (order commutative operands, skip
  identities) and **memoize** shared subresults (Pearls ch. 20).
- **State-space search:** pure `moves`, `move`, `solved`. DFS (stack frontier)
  for low memory or any solution; BFS (queue frontier) for fewest moves;
  A* for weighted shortest paths. Keep a **visited set of canonical states**
  in a hashed or ordered set, never a list; use a real queue for BFS
  (Bird & Gibbons ch. 15).
- **A\*:** priority = cost so far + estimate. Needs **positive edge costs**
  and an **admissible** heuristic (never overestimates). The visited-set
  variant needs a **consistent** (monotone) heuristic; otherwise keep the best
  known cost per state and allow re-expansion. With `h = 0`, A* is Dijkstra
  (Bird & Gibbons ch. 16).
- **Check feasibility invariants first** (parity, counting): half of all
  8-puzzle grids are unsolvable, and search never ends on them (ch. 16).
- **Constraint puzzles:** propagate constraints to a fixed point, then branch on
  the variable with the fewest options (Pearls ch. 19).
- **Measured effect of the strategy** (Bird & Gibbons ch. 15-16):

  | Need | Use | Avoid |
  |---|---|---|
  | fewest moves, unit costs | BFS with a visited set | DFS (on Rush Hour puzzles it returned 812 to 15,542 moves where BFS found 18 to 93) |
  | cheapest path, weighted | A* with the tightest admissible heuristic (consistent if you use a visited set) | priority by the heuristic alone |
  | any solution fast | forward planning or DFS, then shorten | reporting the result as optimal (planning found 27 moves where 18 was optimal) |

  On an 8-puzzle with a 24-move solution, BFS took about 920 s; A* with the
  out-of-place heuristic 3.4 s; with the Manhattan heuristic 0.4 s. Make the
  heuristic tighter before you micro-optimize the queue.

## 11. Laziness and space

The books analyze time under eager evaluation and mostly ignore space. Add
these rules:

- **Cheap to build, expensive to keep:** `length (inits xs)` is quadratic in
  time and space (chains of unevaluated `map`s); `length (tails xs)` is linear
  (Bird & Gibbons ch. 2).
- **Cost depends on the consumer** under laziness. Say which consumer a
  complexity claim assumes.
- **Lazy accumulators build thunk chains.** Use strict left folds and strict
  fields for counters, sums and maps that every step touches (practice).
- **Do not hold the head of a stream** that you consume; in JVM languages this
  includes memoizing lazy lists. Use iterators or pull-based streams for
  single-pass data (practice).
- **In strict languages, the same mistake is eager materialization:** a chain
  of `map`/`filter` over millions of elements builds intermediate
  collections. Use views, iterators or streams, or fuse into one pass
  (practice).
- **Deep right folds overflow the stack** in strict languages; use a
  stack-safe fold or a loop (practice).
- Infinite producers need a bounded consumer: `take`, `takeWhile`, `find`, a
  budget or a tolerance (Hughes).
- **Amortized bounds can fail when a version of a structure is reused**
  (persistence). Prefer persistent-safe structures there: memoized lazy
  amortized structures when only throughput matters, worst-case structures
  when latency matters (see `data-structures.md` sections 2-3).
- Measure memory on realistic sizes for anything claimed to stream.

## 12. Derivation tactics (from the Pearls)

When a clear function is too slow, try these moves, and test against the
original after each one (Pearls, preface and the pearls themselves):

1. **Generalize the function** with an extra parameter or invariant so the
   recursion has the right shape (`minFrom a`, a search interval; ch. 1).
2. **Strengthen the result** to a summary that composes when no combine step
   exists for the plain result (a table instead of a count; ranks instead of a
   sorted list).
3. **Tuple and cache** values, costs and sizes with the candidates.
4. **Use the scan and tupling kit** for prefix and suffix problems.
5. **Three-way case analysis** to throw one side away each time.
6. **Change the representation** with an abstraction function `abs`, state
   `op . abs = abs . op'`, and calculate `op'` (lists to array slices, stacks
   stored in a graph).
7. **Choose a different definition of an ingredient** to get a different
   algorithm (matching right-to-left gives Boyer-Moore; with `tails` it gives
   KMP; ch. 16-17).
8. **Transfer proofs by symmetry** (rows to columns and boxes in Sudoku;
   ch. 19).
9. **Reduce to a known problem or a duality theorem**, and use it as a cross
   check.
10. **Build shared structure** for overlapping subproblems.
11. **Derive a bounded-precision version from an exact one** and prove the
    invariant that links them (arithmetic coding; ch. 24-25).
12. **Warn when the result is opaque.** Keep the specification next to the
    optimized code; the derivation is the documentation.
13. **Maximum marking problems:** to choose the best subset of a sequence under
    a regular constraint (not a segment, a segment of length at least k, no
    two adjacent elements), write the valid markings as a regular expression,
    build its small automaton, and fold left keeping the best value per
    automaton state: O(n × states) (ch. 11).

    ```haskell
    -- no two adjacent elements: state = (best with last taken, best with last free)
    best []     = 0
    best (x:xs) = uncurry max (foldl' step (x, 0) xs)
      where step (!taken, !free) y = (free + y, max taken free)
    ```

14. **Streaming a fold-then-unfold** (encoders, decoders, base conversions):
    `unfoldr produce . foldl consume s` can emit output before all input is
    consumed when output already determined never changes because of later
    input. State that condition and test it as a property (the streamed
    result equals the batch result; an emitted prefix never changes)
    (ch. 24).
15. **Loopless generators** (constant work per item) are worth it only when
    per-item delay is the requirement; Bird cites Knuth's warning that the
    extra machinery usually makes the total run time longer (ch. 28).
    Otherwise write generators as `unfoldr` over a small explicit state.
16. **Change a low-level representation in safe steps:** make each
    intermediate version runnable, test each against the previous one on the
    full observable state (including inputs that must be restored), and keep
    the first version as the oracle (Schorr-Waite, ch. 26).

## 13. Review checklist

Flag these in review:

- Quadratic list operations in loops: append at the end, `last`, `init`,
  `length` per step, membership tests on lists for visited sets.
- Costs, sums or lengths recomputed inside accumulators or comparators.
- Greedy code with no proof sketch and no oracle test; DP code with no
  recurrence in a comment; "optimized" code with no reference version.
- A fold in the wrong direction for the cost model; a lazy left fold over large
  input.
- All candidates materialized before filtering.
- Fixed-width integer arithmetic on bounds or "infinity" (`INF + d`) with no
  overflow guard; `==` on floating-point values in approximation loops.
- Deep recursion without stack safety.
- Memo tables keyed by non-canonical or mutable keys; unbounded caches.
- Search with no visited set, no termination argument, or on instances that
  are provably unsolvable.
- Premature fusion that destroys the staged structure; fuse only for a
  measured need, and keep the staged version as the oracle (Hughes).
- Asymptotic claims with no cost model, no case (worst, average, amortized)
  and no data-structure assumptions (Bird & Gibbons ch. 2).
