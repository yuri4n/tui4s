export default defineAppConfig({
  header: {
    title: 'tui4s',
  },
  seo: {
    title: 'tui4s',
    // claude 2026-10-20: changed: tui4s runs kyo-ui values; ZIO and Cats Effect are planned (L5)
    description:
      'A terminal runner for kyo-ui, in Scala 3. ZIO and Cats Effect modules are planned.',
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
