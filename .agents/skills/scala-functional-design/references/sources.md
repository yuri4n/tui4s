# Sources

This skill gives original guidance for Scala that draws on the books below
and on The Scala Compendium. Each reference file names its main sources at
the top and cites chapters inline.

| Short name | Book | Stack | What this skill takes from it |
|---|---|---|---|
| PFP | Gabriel Volpe, *Practical FP in Scala: A Hands-On Approach*, 2nd ed. (Leanpub, 2021) | Scala 2.13, Cats Effect 3, http4s, Skunk, Redis4Cats, Ciris | tagless final (algebras, interpreters, programs), capability traits, modules, state, error handling, failure policies for a business workflow, HTTP and persistence layers, testing, assembly and operations; in the bonus chapter: streams, concurrency, finite state machines, MTL, aspects, optics |
| FEDA | Gabriel Volpe, *Functional Event-Driven Architecture: Powered by Scala 3* (Leanpub, 2023) | Scala 3, Cats Effect 3, fs2, Apache Pulsar | Scala 3 domain encodings (newtypes, Iron, enums, givens, match types), stream pipelines, FSM services, when to choose EDA, delivery and idempotency, atomicity, snapshots, schema evolution, tracing |
| EOP | Bill Frasure, Bruce Eckel, James Ward, *Effect Oriented Programming: A New Paradigm for Creating Reliable, Adaptable, Testable Systems* (Leanpub, 2024) | Scala 3, ZIO 2 | ZIO idioms: adding behavior by wrapping effects, services and layers, typed errors and defects, conversion at the boundary, `Ref`, shared resilience policies, hedging, ZIO Test |
| The Scala Compendium | <https://thescalacomp.dev>, repository `yuri4n/the-scala-compendium` (catalogue in `shared/utils/atlas.ts`, guides in `shared/data/guides.json`) | every Scala ecosystem (Typelevel, ZIO, Kyo, Akka, Pekko, Future, Ox, Turbolift, shared tools) | the library map by role and ecosystem, compatibility notes, and the bridges between ecosystems (`effect-systems.md`) |

The `functional-design` skill in the same plugin covers the
language-agnostic design principles and lists its own books (Granin,
Wlaschin, Ghosh, Crichton, Normand, Parsons, Hughes, Marlow, Skinner, Bird &
Gibbons, Okasaki, Stone). This skill cites some of
them, for example "(Granin ch. 12)".

Dated items in PFP and FEDA are listed in SKILL.md under "Dated advice".

## How citations work

- "(PFP ch. 4)" refers to a chapter of the book in the table. "(PFP ch. 1
  and 2)" names two chapters, and "(PFP ch. 6; FEDA ch. 4)" names two books.
  "(PFP bonus chapter)" is the chapter after chapter 10.
- FEDA numbers its chapters in its table of contents: 1 Event-driven
  architecture, 2 Distributed systems, 3 Stateless vs. Stateful,
  4 Functional programming in Scala 3, 5 Effectful streams, 6 to 8 Trading
  system (core services, alt services, observability).
- "(EOP § 5.1)" refers to a numbered section of *Effect Oriented
  Programming*.
- "(practice)" marks advice that does not come from the books: common
  practice, or the authors of this skill.
- When a whole section or list draws on one chapter, the citation appears
  once, at the start of the section or in its "Source" or "Sources" line.
- The chapter numbers are those of the editions in the table.

## Further reading

Other Scala books that help with depth (not summarized here):

- *Functional Programming in Scala*, 2nd ed., Michael Pilquist, Paul
  Chiusano, Rúnar Bjarnason
- *Scala with Cats*, Noel Welsh, Dave Gurnell
- *Essential Effects*, Adam Rosien (Cats Effect)
- *Functional Stream Processing in Scala*, Zainab Ali (fs2)
- *Zionomicon*, John A. De Goes, Adam Fraser, Milad Khajavi (ZIO)
- *Programming in Scala*, 5th ed., Martin Odersky, Lex Spoon, Bill Venners

## Note on copyright

This skill summarizes and cites the books; it does not reproduce them. Its
sketches are original and use other names and domains than the books'
listings, and its few quoted terms are short and attributed. Read the books
for the full treatment: the arguments and the complete examples. Do not
paste long passages from the books into code, comments or documents.
