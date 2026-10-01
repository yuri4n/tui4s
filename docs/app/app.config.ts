export default defineAppConfig({
  header: {
    title: 'tui4s',
  },
  seo: {
    title: 'tui4s',
    description:
      'Declarative terminal UIs for Scala 3. Kyo core, with ZIO and Cats Effect integrations.',
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
