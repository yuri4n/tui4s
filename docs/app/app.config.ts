export default defineAppConfig({
  header: {
    title: 'tui4s',
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
  },
  github: {
    url: 'https://github.com/yuri4n/tui4s',
    branch: 'main',
    rootDir: 'docs',
  },
  socials: {
    github: 'https://github.com/yuri4n/tui4s',
  },
  toc: {
    title: 'On this page',
  },
})
