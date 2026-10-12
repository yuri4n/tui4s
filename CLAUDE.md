@AGENTS.md

# Rules for Claude Code sessions in this repo

<!-- claude 2026-10-12: created. AGENTS.md is for every agent and for humans; this
file holds what is specific to Claude Code. -->

- **Model of subagents.** Run subagents and workflow agents on Opus
  (`model: "opus"`) unless the task needs the deep reasoning of Fable, the
  session model. Use Fable only for: a design or plan panel, a gate review, the
  adversarial check of concurrency or runtime code (the runner, the settle, the
  watchers), and a root-cause hunt with no known cause. Use Opus for: the
  implementation of a specified change, a review against a checklist, a carry
  or a format commit, a search, and the reading of logs. When in doubt, use
  Opus. (User rule, 2026-10-12.)
