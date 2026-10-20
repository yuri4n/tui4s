---
title: "0010. The flagship assistant is the blueprint of a real app"
description: "One flagship example, an AI chat assistant, shows how to structure a real tui4s app: a pure domain, services as traits with interpreters, one store, Kyo layers and errors as values."
---

<!-- claude 2026-10-20: created: ADR 0010 in the docs site (L6) -->

## Status

Accepted

## Date

2026-10-17 (D3, D4). Landed on 2026-10-19 (L3).

## Context

- The user asked for "a large example that shows how to structure a real application" and for "a chat bot that can use ChatGPT or Anthropic (API key, or a subscription if it is permitted)".
- Provider rules (research of 2026-10-17): an API key is permitted for both. A claude.ai login or token reuse in a third-party app is forbidden. Driving the user's own `claude -p` is gray, and without `--bare` it runs the project's hooks and MCP servers. OpenAI "Sign in with ChatGPT" for open-source apps was a preview (search summaries only).
- The official Java SDKs are 39 MB and 69 MB core jars plus Kotlin, Jackson and OkHttp. kyo-http RC7 can stream SSE on a POST and is already on the classpath through kyo-ui.
- A tui4s handler runs on the runner fiber. A handler that waits for the network freezes input, so actions that wait must fork.

## Decision

- One flagship app: the assistant (`modules/assistant`, main `examples.assistant.AssistantApp`, `--fake` for no network).
- Providers: Anthropic and OpenAI with the user's API key, and a scripted fake provider. Codex app-server is second (not built; [#48](https://github.com/yuri4n/tui4s/issues/48)). A Claude Code CLI backend is never in published code.
- Keys come from environment variables or a file with mode 0600 (`SecretsJvm`), never from a login token.
- The shape is the blueprint for a new app: a pure domain with laws (the transcript fold, the title rule, the SSE split, the codec round trip); services as traits (`ChatProvider`, `Secrets`, `ConversationStore`, `AppLog`) with their interpreters in a `make` (JVM, browser, in memory for tests); one store for the app; the wiring with Kyo layers in the main; errors as values (`Abort` with a sealed error type).
- The HTTP seam (`KyoHttp`) follows no redirect and has a 5-minute idle limit. The review found that a redirect carried the key to the new host.
- Thin SSE wires over kyo-http, no vendor SDK.
- The app opts in to the Anthropic server-side fallbacks for its default model (header `anthropic-beta: server-side-fallback-2026-07-01`, field `fallbacks: "default"`). The user must know this.

## Alternatives considered

- **Several small apps.** Not chosen: the user asked for one large example. The small examples (counter, todo, dashboard, forms, gallery) stay for single features.
- **A chat bot in the docs only.** Not recorded.
- **The official provider SDKs.** Rejected for their size and their dependencies.
- **A subscription login.** Rejected: forbidden for Anthropic; unverified terms for OpenAI.
- **A Claude Code CLI backend.** Rejected for published code: a gray area under the provider rules, and a child process runs the hooks and MCP servers of the folder.

## Consequences

- Good: the assistant is the reference for AGENTS.md ("Follow it for a new app") and for the docs.
- Good: in-memory interpreters make 13 headless tests and 86 module tests possible with no network.
- Bad: the server-side fallbacks change which model answers when a safety rule declines. This is an opt-in in `Wires.scala`; it needs a line in the docs ([#35](https://github.com/yuri4n/tui4s/issues/35)).
- Bad: the OpenAI default model id is unchecked data; effort and thinking are not sent; the run log file has the umask mode.
- Bad: `Surface` mixes data and the key-vault capability (a deferred review finding, [#36](https://github.com/yuri4n/tui4s/issues/36)).

## References

- Roadmap: 2.5, 2.6 (the handler trap), D3, D4. Handoff: L3 ("USER MUST KNOW").
- AGENTS.md: "UI architecture and API design" (the assistant item).
- README: "A real app: the assistant".
