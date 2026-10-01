// tui4s documentation site (Nuxt Docus). Hosted on Vercel with Root Directory = docs.
const description =
  'Declarative terminal UIs for Scala 3. Kyo core, with ZIO and Cats Effect integrations.'

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
    notes: [
      'Use tui4s to write a terminal user interface in Scala 3 with a declarative API. It renders with jatatui, a Java port of ratatui.',
      'Pick one module: tui4s-core (Kyo), tui4s-zio (ZIO 2) or tui4s-cats (Cats Effect 3). Each module is a separate artifact with its own version.',
      'tui4s needs JDK 25, because Kyo 1.0.0-RC7 needs it.',
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

  // MCP server for agents at /mcp (needs a server runtime: Vercel).
  mcp: {
    name: 'tui4s documentation',
  },
})
