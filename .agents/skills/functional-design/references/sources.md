# Sources

This skill gives original guidance that draws on the books and papers below.
Each reference file names its main sources at the top and cites chapters
inline.

| Short name | Book | Language of the examples | What this skill takes from it |
|---|---|---|---|
| Granin | Alexander Granin, *Functional Design and Architecture: Examples in Haskell* (Manning, 2024) | Haskell | the main source: the design process, layers, the application layer and the runtime, eDSLs and interpreters, the functional interfaces and how they compare, error domains, state, concurrency, resources, persistence, testable architecture |
| Wlaschin | Scott Wlaschin, *Domain Modeling Made Functional* (Pragmatic Bookshelf, 2018) | F# | domain discovery, types for the domain, workflows as pipelines, railway error handling, dependencies as parameters, serialization and persistence at the boundaries |
| Ghosh | Debasish Ghosh, *Functional and Reactive Domain Modeling* (Manning, 2016) | Scala 2 | algebra-first design, laws as part of the model, Reader and Kleisli injection, modules, event sourcing, property-based tests of algebras |
| Crichton | Will Crichton, "Typed Design Patterns for the Functional Era" (paper, FUNARCH workshop, ACM, 2023) | Rust | typed patterns: witness, state machine (typestate), parallel lists, registry |
| Hughes | John Hughes, "Why Functional Programming Matters" (paper, in *Research Topics in Functional Programming*, Addison-Wesley, 1990; first in *The Computer Journal*, 1989) | Miranda | modularity through higher-order functions and lazy evaluation as glue |
| Bird & Gibbons | Richard Bird, Jeremy Gibbons, *Algorithm Design with Haskell* (Cambridge University Press, 2020) | Haskell | specification-first algorithm design, fusion and other laws, greedy algorithms, thinning, dynamic programming, search |
| Pearls | Richard Bird, *Pearls of Functional Algorithm Design* (Cambridge University Press, 2010) | Haskell | derivation tactics, worked derivations, specifications as test oracles |
| Okasaki | Chris Okasaki, *Purely Functional Data Structures* (Cambridge University Press, 1998) | Standard ML, with Haskell versions in an appendix | persistence, amortization and laziness, a catalog of purely functional structures |
| Stone | John David Stone, *Algorithms for Functional Programming* (Springer, 2018) | Scheme | data-structure design in a functional style: lazy sequences, queues, trees and their invariants |
| Normand | Eric Normand, *Grokking Simplicity* (Manning, 2021) | JavaScript | actions, calculations and data, immutability in a mutable language, stratified design, first-class functions, timelines, onion and reactive architecture |
| Parsons | Matt Parsons, *Production Haskell* (Leanpub, 2023) | Haskell | complexity and novelty budgets, application structure, the three-layer cake, "invert your mocks", errors and exceptions, logging, databases, compile times, type-level trade-offs |
| Marlow | Simon Marlow, *Parallel and Concurrent Programming in Haskell* (O'Reilly Media, 2013) | Haskell | concurrency constructs: threads, MVars, STM, async, cancellation and masking, timeouts, parallelism |
| Skinner | Rebecca Skinner, *Effective Haskell* (Pragmatic Bookshelf, 2023) | Haskell | types as a design tool, type classes compared with records, effect stacks and mtl, efficient programs, type-level programming |

For Scala, the `scala-functional-design` skill in the same plugin summarizes
Gabriel Volpe's Scala books. This skill refers to it where Volpe is named.

## How citations work

- "(Granin ch. 13)" refers to a chapter of the book in the table.
  "(Granin ch. 1-2)" is a range, and "(Ghosh ch. 3; Wlaschin ch. 7)" names
  two books. "Preface" and "appendix A" refer to the front and back matter.
  "Part II intro" refers to the introduction of a numbered part of the book
  (Bird & Gibbons, Marlow). Parsons, "Principles" is the unnumbered opening
  chapter of his book. The chapter numbers are those of the editions in the
  table.
- "(Crichton § 2.3)" refers to a numbered section of a paper.
- "(Hughes)" refers to Hughes's paper as a whole.
- "(practice)" marks advice that does not come from the books: common
  practice, or the authors of this skill.
- When a whole section or list draws on one chapter, the citation appears
  once, at the start of the section or in its "Sources" line.

## Note on copyright

This skill summarizes and cites the books; it does not reproduce them. Its
sketches are original and use other names and domains than the books'
listings, and its few quoted terms are short and attributed. Read the books
for the full treatment: the arguments, the proofs and the complete examples.
Do not paste long passages from the books into code, comments or documents.
