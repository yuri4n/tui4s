# tui4s: rules for AI agents

<!-- claude 2026-10-20: changed: this paragraph, the two imports and their note: the rules moved to CONTRIBUTING.md, how tui4s works to ARCHITECTURE.md (ADR 0021) -->
This file is for AI agents that work on tui4s.
[CONTRIBUTING.md](CONTRIBUTING.md) is the rulebook, for humans and for agents.
[ARCHITECTURE.md](ARCHITECTURE.md) tells how the runner works. Read both
files before a change.

@CONTRIBUTING.md

@ARCHITECTURE.md

Claude Code reads `CLAUDE.md`, which imports this file, and it imports the
two files above. Another agent reads the two files itself.

## Rules for agents only

<!-- claude 2026-10-20: created: this section (ADR 0021); changed: the sbt item, vendor/pini4s is not a separate root (review fix); changed: one sbt process in the repository, the trailer exception (review) -->
- Mark each unit that you create or change, at the smallest unit: a list
  item, a paragraph, a table row, a function, a step. A mark gives the date,
  `created` or `changed`, and what changed:
  `<agent> <YYYY-MM-DD>: created|changed: <what>`. Use the name of the agent
  that made the change, for example `codex`. Write it in the comment syntax
  of the file: `<!-- ... -->` in Markdown, `// ...` in Scala, sbt and
  JavaScript, `# ...` in shell, Python, YAML and HOCON (`.conf`).
  <!-- codex 2026-10-23: changed: attribute each change to its actual agent. -->
- Put one mark on a unit. When the unit has a mark already, add the new
  change to that line (`...; <agent> <YYYY-MM-DD>: changed: <what>`). Keep
  the names and dates of earlier authors. Do not stack a second mark line.
  <!-- codex 2026-10-23: changed: preserve earlier authors when agents differ. -->
- Never mark a whole file as changed: mark the units. A new file gets one
  mark line under its title. For a new file of moved text, that line tells
  where the text comes from, and the moved blocks keep their marks.
- Never start a terminal UI in your own shell. Only the PTY kit
  (`examples/ptycheck`) runs one.
- Run only one sbt process in the repository at a time, also across its
  build roots: the root build also compiles `vendor/pini4s`, and
  `bench/run.sh` runs the root build before the build of `bench/`.
- A release publishes a public artifact to Maven Central. You can start a
  dry run at any time: `scripts/release.sh <module|all> <version> --dry-run`.
  A real release needs a human: start it only when a human asks for it in
  the chat. The maintainer approves the deployment in GitHub (the
  environment `maven-central`). Never push a release tag, and never go
  around that gate.
- Never force-push. Never rewrite history that is pushed (no rebase and no
  amend of a pushed commit).
- Never write the name of a model in a file, a commit message or a docs
  page. The attribution trailer of a commit (`Co-Authored-By`) is the one
  exception.
- Write all prose in ASD-STE100: short sentences, one idea in each, the
  active voice.
- A change of behaviour updates ARCHITECTURE.md in the same pull request. A
  change of process updates CONTRIBUTING.md in the same pull request.
- Read the local skills before a change of code:
  [functional-design](.agents/skills/functional-design/SKILL.md) and
  [scala-functional-design](.agents/skills/scala-functional-design/SKILL.md).
  CONTRIBUTING.md, "Purity", gives their source and update rule.
  <!-- codex 2026-10-22: changed: use the local copies of the design skills. -->
