// tui4s documentation site (Nuxt Docus). Hosted on Vercel with Root Directory = docs.
// claude 2026-10-20: changed: tui4s runs kyo-ui values (L5); ZIO and Cats Effect are issues, not modules (ADR 0014)
const description =
  'A terminal runner for kyo-ui, in Scala 3. A ZIO and a Cats Effect integration are not in this version (issues #18 and #19).'

// The absolute URL of the site, for llms.txt links. Vercel sets VERCEL_PROJECT_PRODUCTION_URL.
// Without it (local and CI builds) nuxt-llms skips llms.txt, so fall back to localhost.
const vercelUrl = process.env.VERCEL_PROJECT_PRODUCTION_URL
const siteUrl =
  process.env.NUXT_SITE_URL ?? (vercelUrl ? `https://${vercelUrl}` : 'http://localhost:3000')

export default defineNuxtConfig({
  extends: ['docus'],

  site: {
    name: 'tui4s',
  },

  content: {
    build: {
      markdown: {
        highlight: {
          // Added to the Docus defaults (bash, diff, json, js, ts, html, css, vue, shell, mdc, md, yaml).
          // claude 2026-10-20: created this note (L5): a build.sbt block is an `sbt` block; it
          // shows as plain text, and only a `scala` block must be a marked region
          // (MarkdownRegionsSuite).
          langs: ['scala', 'java'],
        },
      },
    },
  },

  // llms.txt and /raw/<page>.md. llms-full.txt comes from server/routes/llms-full.txt.get.ts
  // (the nuxt-llms version crashes on bold text), so `full` is off here.
  llms: {
    domain: siteUrl,
    title: 'tui4s',
    description,
    full: false,
    sections: [
      {
        title: 'Documentation sets',
        description: 'All pages in one file.',
        links: [
          {
            title: 'tui4s (all pages)',
            description,
            href: `${siteUrl}/llms-full.txt`,
          },
        ],
      },
      {
        title: 'Developer resources',
        description: 'Machine-readable entry points for this documentation.',
        links: [
          {
            title: 'tui4s on GitHub',
            description: 'Source code, issues and releases.',
            href: 'https://github.com/yuri4n/tui4s',
          },
        ],
      },
    ],
    // claude 2026-10-20: changed: the notes for kyo-ui and the split artifacts; no version is published (L5); ZIO and Cats Effect are issues, not modules (ADR 0014)
    notes: [
      'tui4s runs a kyo-ui value in the terminal, in Scala 3. kyo-ui is the UI DSL; tui4s has no DSL of its own. It paints with jatatui, a Java port of ratatui.',
      'No version of tui4s is published yet. An app uses tui4s-core (tui4s-runner, tui4s-app and tui4s-testkit in one dependency). tui4s-use (composables) and tui4s-widgets (widgets) build for the JVM and Scala.js. Each module has its own version.',
      'tui4s has no ZIO or Cats Effect module. Issues #18 and #19 (https://github.com/yuri4n/tui4s/issues) track them. Mouse input is not supported. Windows is experimental.',
      'tui4s needs JDK 25, because Kyo 1.0.0-RC7 needs it.',
      'Every Scala block of this documentation is the text of a region of a source file of the repository, and a test checks it.',
      'To read this documentation as an agent, fetch /llms-full.txt, or the raw Markdown of one page at /raw/<page-path>.md.',
    ],
  },

  nitro: {
    prerender: { routes: ['/llms-full.txt'] },
  },

  docus: {
    // Off on purpose. Docus turns its AI assistant on by itself when it finds a
    // Vercel OIDC token, and then the chat uses (and bills) the Vercel AI Gateway.
    assistant: { enabled: false },
  },

  // claude 2026-10-20: created: no font provider fetches from the network at build time, so the
  // build is the same offline and in CI (the site uses the system font stacks of Nuxt UI) (L5)
  fonts: {
    providers: {
      adobe: false,
      bunny: false,
      fontshare: false,
      fontsource: false,
      google: false,
      googleicons: false,
    },
  },

  // claude 2026-10-20: created: the icons of the pages, the navigation and the code blocks are
  // in the client bundle, so no icon comes from the Iconify API at build time or in the browser (L5);
  // changed: the icon of the section Decisions (L6)
  icon: {
    clientBundle: {
      icons: [
        'lucide:arrow-right',
        'lucide:blocks',
        'lucide:book-open',
        'lucide:bot',
        'lucide:component',
        'lucide:download',
        'lucide:flask-conical',
        'lucide:gauge',
        'lucide:globe',
        'lucide:layers',
        'lucide:layout-template',
        'lucide:message-square',
        'lucide:package',
        'lucide:palette',
        'lucide:play',
        'lucide:puzzle',
        'lucide:rocket',
        'lucide:scale',
        'lucide:scroll-text',
        'lucide:shield-check',
        'lucide:square-terminal',
        'lucide:text-cursor-input',
        'simple-icons:github',
        'vscode-icons:file-type-scala',
      ],
    },
  },

  // MCP server for agents at /mcp (needs a server runtime: Vercel).
  mcp: {
    name: 'tui4s documentation',
  },
})
