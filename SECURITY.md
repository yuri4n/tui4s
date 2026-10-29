<!-- claude 2026-10-20: created (L6, ADR 0014); changed: the e-mail address of the POM is the first path, because the private vulnerability reporting of the repository is off today (L6 loose ends) -->
# Security policy

## Report a problem

Do not open a public issue for a security problem. Report it privately:

1. Send an e-mail to the maintainer at <oss.juli@pm.me> (the developer
   address in the POM of each artifact).
2. When the private vulnerability reporting of this repository is turned on,
   you can also use it: the tab **Security**, then **Report a
   vulnerability** (<https://github.com/yuri4n/tui4s/security/advisories/new>).

Give the version (the artifact and its version, or the commit), the JDK, the
operating system and the terminal, the steps that show the problem, and what
an attacker can do with it. You get an answer in about 14 days. There is no
bounty.

## Supported versions

tui4s has no release yet. The first release candidate is 0.1.0-RC1 (planned).
Until 1.0, only the last release of each module gets fixes.

## Scope

In scope:

- The libraries: `tui4s-runner`, `tui4s-testkit` and `tui4s-core` (the core
  jar also holds `TuiApp`, the composables and the widgets). For example: the
  restore of the terminal, the output capture and the log file of a run
  (`TuiOptions.logFile`).
  <!-- cursor 2026-10-29: changed: the published libraries are runner, testkit and core (ADR 0024) -->
- The API keys of the example assistant (`examples.assistant`,
  `modules/assistant`):
  - the environment variables `ANTHROPIC_API_KEY` and `OPENAI_API_KEY`;
  - the key file of the terminal app (`keys` in `tui4s/assistant` of the
    config directory). The app reads it only when it is a regular file for
    its owner only (mode 0600);
  - the keys of the web app in the `localStorage` of the browser.
  A key must go only to the host of its provider (the HTTP client follows no
  redirect), and it must never appear on the screen, in a log or in a file
  of the conversations.

Out of scope:

- A problem in Kyo, kyo-ui, jatatui or the JDK. Report it to that project.
  Tell us too when tui4s must change because of it.
- The bench (`bench/`), the experiments (`experiments/`) and the docs site,
  unless they publish a secret.
