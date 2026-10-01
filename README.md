# tui4s

Declarative terminal user interfaces for Scala 3.

tui4s has a [Kyo](https://getkyo.io) core and separate integrations for
[ZIO](https://zio.dev) and [Cats Effect](https://typelevel.org/cats-effect/).
It renders with [jatatui](https://github.com/oyvindberg/jatatui), a Java port of
[ratatui](https://ratatui.rs).

> **Status:** under construction. Nothing is published yet.

## Modules

Each module is released on its own, with its own version.

| Artifact                         | For                                    |
| -------------------------------- | -------------------------------------- |
| `io.github.yuri4n::tui4s-core`   | Kyo users, and the base of the others. |
| `io.github.yuri4n::tui4s-zio`    | ZIO users.                             |
| `io.github.yuri4n::tui4s-cats`   | Cats Effect users.                     |

## Requirements

- JDK 25 (Kyo 1.0.0-RC7 needs it).
- Scala 3.9.0 or later.

## Contributing

Read [AGENTS.md](AGENTS.md). It has the commands, the code rules, and the
release process for humans and for AI agents.

## License

[Apache-2.0](LICENSE)
