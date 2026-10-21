export default defineAppConfig({
  header: {
    title: 'tui4s',
    // claude 2026-10-20: created (ADR 0022): the logotype in the header (scripts/logo/build.mjs
    // writes the files); h-12, because the art has 11 rows and the default h-6 makes it too small
    logo: {
      light: '/logo-light.svg',
      dark: '/logo-dark.svg',
      alt: 'tui4s',
      class: '!h-12',
      favicon: '/favicon.ico',
    },
  },
  seo: {
    title: 'tui4s',
    // claude 2026-10-20: changed: tui4s runs kyo-ui values (L5); ZIO and Cats Effect are issues, not modules (ADR 0014)
    description:
      'A terminal runner for kyo-ui, in Scala 3. A ZIO and a Cats Effect integration are not in this version (issues #18 and #19).',
  },
  ui: {
    colors: {
      primary: 'green',
      neutral: 'zinc',
    },
    // claude 2026-10-21: created: compact, square controls throughout the docs.
    button: {
      slots: { base: 'rounded-sm font-mono' },
    },
    // claude 2026-10-21: created: wrap the article's edit and issue links on narrow screens.
    separator: {
      slots: { container: '[&>div]:flex-wrap [&>div]:justify-center' },
    },
    // claude 2026-10-21: created: a stable hook for the terminal header.
    header: {
      slots: { root: 'tui-header', center: 'max-w-md' },
    },
    // claude 2026-10-21: created: a ruled footer with a stable theme hook.
    footer: {
      slots: { root: 'tui-footer border-t border-default' },
    },
    // claude 2026-10-21: created: monospace navigation with a compact active row.
    contentNavigation: {
      slots: {
        root: 'tui-navigation',
        link: 'font-mono text-xs py-2 before:rounded-sm',
        linkTitle: 'whitespace-normal leading-5',
      },
    },
    // claude 2026-10-21: created: quiet article headings.
    pageHeader: {
      slots: {
        root: 'tui-page-header',
        headline: 'font-mono text-xs font-normal',
        title: 'tracking-tight',
        description: 'text-base leading-7',
      },
    },
    // claude 2026-10-21: created: a compact, monospace table of contents label.
    contentToc: {
      slots: { root: 'tui-toc', trigger: 'font-mono text-xs', link: 'text-xs leading-6' },
    },
    // claude 2026-10-21: created: terminal frames around the existing code blocks.
    prose: {
      pre: {
        slots: {
          root: 'tui-code',
          header: 'tui-code-header rounded-t-sm font-mono',
          filename: 'text-xs',
          copy: 'z-10',
          base: 'rounded-sm text-[13px] leading-6',
        },
      },
    },
  },
  github: {
    url: 'https://github.com/yuri4n/tui4s',
    branch: 'main',
    rootDir: 'docs',
  },
  // claude 2026-10-21: changed: the repository setting already supplies the footer's GitHub link.
  socials: {},
  toc: {
    title: 'On this page',
  },
})
