# Sources

The skill paraphrases these books. Page references such as "(Granin p. 365)"
are **PDF page numbers** (the page index of the PDF file, and the
`=== PAGE N ===` markers that `scripts/pdf2txt.swift` writes), not printed page
numbers. The last column gives printed = PDF − n, per chapter where the offset
varies.

The books are in the user's ebook library:
`~/Library/Mobile Documents/com~apple~CloudDocs/Resources/`.

| Short name | Book | Library file | Printed page |
|---|---|---|---|
| Granin | Alexander Granin, *Functional Design and Architecture: Examples in Haskell* (Manning, 2024) | `Functional Design and Architecture -- Examples in Haskell (Granin, 2024, Manning Publications).pdf` | PDF − 24 |
| Wlaschin | Scott Wlaschin, *Domain Modeling Made Functional* (Pragmatic Bookshelf, 2018), F# | `Domain modeling made functional (Wlaschin, 2018, Pragmatic Bookshelf).pdf` | PDF − 14 near the front, PDF − 7 near the end |
| Ghosh | Debasish Ghosh, *Functional and Reactive Domain Modeling* (Manning, 2016), Scala 2 | `Functional and Reactive Domain Modeling (Ghosh, 2016, Manning Publications).pdf` | PDF − 25 |
| Crichton | Will Crichton, *Typed Design Patterns for the Functional Era* (2023), Rust | `Typed Design Patterns for the Functional Era (Crichton, 2023, Self-published).pdf` | same |
| Hughes | John Hughes, *Why Functional Programming Matters* (1990) | `Why Functional Programming Matters (Hughes, 1990, Oxford University Press).pdf` | same |
| Bird & Gibbons | Richard Bird, Jeremy Gibbons, *Algorithm Design with Haskell* (Cambridge, 2020) | `Algorithm Design with Haskell (Bird, Gibbons, 2020, Cambridge University Press).pdf` | PDF − 17 |
| Pearls | Richard Bird, *Pearls of Functional Algorithm Design* (Cambridge, 2010) | `Pearls of Functional Algorithm Design (Bird, 2010, Cambridge University Press).pdf` | PDF − 14 |
| Okasaki | Chris Okasaki, *Purely Functional Data Structures* (Cambridge, 1998) | `Purely Functional Data Structures (Okasaki, 1998, Cambridge University Press).pdf` | PDF − 10 |
| Stone | John David Stone, *Algorithms for Functional Programming* (2018) | `Algorithms for Functional Programming (Stone, 2018, Franklin Beedle).pdf` | varies: PDF − 12 (ch. 1-2), − 11 (ch. 3), − 10 (ch. 4), − 9 (ch. 5), − 8 (ch. 6-7) |
| Normand | Eric Normand, *Grokking Simplicity* (Manning, 2021), JavaScript | `Grokking Simplicity -- Taming complex software with functional thinking (Normand, 2021, Manning Publications).pdf` | PDF − 30 |
| Parsons | Matt Parsons, *Production Haskell* (Leanpub, 2023) | `Production Haskell -- Succeeding in Industry with Haskell (Parsons, 2023, Leanpub).pdf` | PDF − 24 |
| Marlow | Simon Marlow, *Parallel and Concurrent Programming in Haskell* (O'Reilly, 2013) | `Parallel and Concurrent Programming in Haskell (Marlow, 2013, O'Reilly Media).pdf` | PDF − 16 |
| Skinner | Rebecca Skinner, *Effective Haskell* (Pragmatic Bookshelf, 2023) | `Effective Haskell -- Solving Real-World Problems with Strongly Typed Functional Programming (Skinner, 2023, Pragmatic Bookshelf).pdf` | varies: PDF − 22 (ch. 1-3), − 21 (ch. 4), − 20 (ch. 5-8), − 19 (ch. 9), − 18 (ch. 10), − 17 (ch. 11-13), − 16 (ch. 14-15) |

## Reading an original chapter

Many files in the library are only in iCloud; reading their data then fails
with "Resource deadlock avoided". Download one first:

```bash
brctl download "$HOME/Library/Mobile Documents/com~apple~CloudDocs/Resources/<file>.pdf"
```

Extract the text with page markers (macOS, uses PDFKit; no extra tools):

```bash
swiftc -O "$HOME/.claude/skills/functional-design/scripts/pdf2txt.swift" -o /tmp/pdf2txt
/tmp/pdf2txt "<book>.pdf" /tmp/book.txt
sed -n '/=== PAGE 365 ===/,/=== PAGE 367 ===/p' /tmp/book.txt
```

Tables can come out garbled. To check one, render the page as an image and
look at it.

## Note on copyright

The reference files paraphrase the books and use short, original sketches. Do
not paste long passages from the books into code, comments or documents.
