---
seo:
  title: tui4s - declarative terminal UIs for Scala 3
  description: Build terminal user interfaces in Scala 3 with a declarative API. Kyo core, with ZIO and Cats Effect integrations.
---

::u-page-hero
#title
Terminal UIs for Scala 3, declared.

#description
tui4s gives you a declarative, effect-typed API for terminal user interfaces. It renders with [jatatui](https://github.com/oyvindberg/jatatui), a Java port of [ratatui](https://ratatui.rs). Use the Kyo core, or the ZIO or Cats Effect integration.

#links
  :::u-button
  ---
  color: neutral
  size: xl
  to: /getting-started/installation
  trailing-icon: i-lucide-arrow-right
  ---
  Get started
  :::

  :::u-button
  ---
  color: neutral
  icon: i-simple-icons-github
  size: xl
  to: https://github.com/yuri4n/tui4s
  variant: outline
  ---
  Source on GitHub
  :::
::

::u-page-section
#title
One model, three effect systems

#features
  :::u-page-feature
  ---
  icon: i-lucide-layers
  to: /getting-started/installation
  ---
  #title
  Kyo core

  #description
  `tui4s-core` is built on [Kyo](https://getkyo.io). State, input and rendering are Kyo effects.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-zap
  to: /getting-started/installation
  ---
  #title
  ZIO integration

  #description
  `tui4s-zio` lets you write the same UI in a ZIO app, with no Kyo types in your code.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-square-terminal
  to: /getting-started/installation
  ---
  #title
  Cats Effect integration

  #description
  `tui4s-cats` lets you write the same UI in an `IOApp`, with no Kyo types in your code.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-gauge
  to: /getting-started/installation
  ---
  #title
  Draws only what changed

  #description
  tui4s draws a frame only when your state changes, and jatatui sends only the changed cells to the terminal.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-flask-conical
  to: /getting-started/installation
  ---
  #title
  Testable without a terminal

  #description
  Render into an in-memory backend and check the screen in a plain unit test.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-bot
  to: /ai/agents
  ---
  #title
  Ready for AI agents

  #description
  Every page is available as Markdown, with `llms.txt` and an MCP server.
  :::
::
