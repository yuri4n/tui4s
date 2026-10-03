# Nuxt Docus research report (tui4s docs/)

Status: DONE (2026-09-30). Each claim is marked VERIFIED (seen in source, registry metadata, or live site) or UNVERIFIED.
Sources: npm registry, github.com/nuxt-content/docus at tag v5.13.0 (layer/, docs/, .starters/, cli/), nuxt/nuxt.com deploy docs, nitrojs/nitro v2.13.4, nuxt/content docs, nuxt-content/mdc, nuxt/ui v4.11.2, shiki compat table, pnpm.io v10 docs, actions/* action.yml, relaticle/flowforge + nuxt/image repos, live https://relaticle.github.io/flowforge/.

## TL;DR

- Docus latest = **5.13.0** (v5; Nuxt layer). Stack: Nuxt 4.x (latest 4.5.2), @nuxt/content ^3.15.2 (latest 3.16.1), @nuxt/ui ^4.11.0 (latest 4.11.2), Tailwind 4, peer better-sqlite3 12.x. Node: `^22.19.0 || ^24.11.0 || >=26` (from Nuxt 4.5.2 engines).
- Scaffold: `npx create-docus docs` (default dir is literally `docs`, templates `default`/`i18n`); minimal = package.json + nuxt.config.ts (`extends: ['docus']`) + content/**. `app/app.config.ts` optional but needs nuxt.config.ts to exist.
- Scala highlighting is NOT in Docus default langs: add `content.build.markdown.highlight.langs: ['scala']` (arrays merge via defu). Scala works with Shiki JS engine.
- Agent features built in: llms.txt, llms-full.txt, /raw/<path>.md (all prerendered, work on GitHub Pages), "Copy page" markdown button, Agent Skills at /.well-known/skills. MCP server (/mcp), markdown content negotiation and markdown 404 need a server (Vercel), not GitHub Pages.
- GitHub Pages under /tui4s/: `NUXT_APP_BASE_URL=/tui4s/ nuxt build --preset github_pages`, upload `docs/.output/public` with `include-hidden-files: true`; set `NUXT_SITE_URL=https://yuri4n.github.io/tui4s` (base path included, or canonical/sitemap are wrong: observed live on flowforge). Favicon needs a manual base-aware link.
- Best example to copy: relaticle/flowforge (branch 4.x, docs/). Full drop-in file set in section 8.

Sections below were appended in the order they were verified (numbering follows the task: 1 versions, 2 scaffold, 3 config, 4 authoring, 5 agent features, 6 deploy, 7 examples, 8 file set).

### 1.x Findings from npm registry (checked 2026-09-30)

- VERIFIED: `docus` dist-tags: `latest` = **5.13.0** (published 2026-08-28). Other tags: `beta` 4.0.0-beta.2, `alpha` 3.0.0-alpha.3 (stale). So the current major is **v5** (not v3/v4).
- VERIFIED: `docus@5.13.0` is a **Nuxt layer** (`"main": "./nuxt.config.ts"`, description "Nuxt layer for Docus documentation theme"). No `engines` field.
- VERIFIED: `docus@5.13.0` peerDependencies: `nuxt: 4.x`, `better-sqlite3: 12.x`.
- VERIFIED: `docus@5.13.0` dependencies include `@nuxt/content ^3.15.2`, `@nuxt/ui ^4.11.0`, `@nuxt/kit ^4.5.2`, `@nuxtjs/i18n ^10.6.0`, `@nuxtjs/mdc ^0.22.2`, `nuxt-llms ^0.2.0`, `@nuxtjs/mcp-toolkit ^0.19.0`, `nuxt-og-image ~6.7.8`, `@nuxtjs/robots ^6.2.0`, `@nuxt/image ^2.1.0`, `tailwindcss ^4.3.3`, `@shikijs/* ^4.4.3`, `ai ^7`, `@ai-sdk/*` (AI assistant), `@iconify-json/lucide`, `@iconify-json/simple-icons`, `@iconify-json/vscode-icons`.
- VERIFIED: latest related packages: `nuxt` 4.5.2 (engines node `^22.19.0 || ^24.11.0 || >=26.0.0`), `@nuxt/content` 3.16.1 (node `>= 20.19.0`), `@nuxt/ui` 4.11.2 (node `^20.19.0 || >=22.12.0`), `better-sqlite3` latest 13.0.3 but Docus peer wants **12.x** (latest 12.x = 12.11.1, node 20-26).
- VERIFIED: `create-docus@5.13.0` exists, bin `create-docus`, depends on `@nuxt/cli 3.36.1` and `citty`. Description "CLI for creating Docus documentation projects".
- Conclusion (VERIFIED from engines): effective Node requirement is Nuxt 4.5's `^22.19.0 || ^24.11.0 || >=26`. The local toolchain node 24 is fine if >= 24.11.

## 2.x Scaffolding findings

- VERIFIED (GitHub tag v5.13.0, `cli/cli.ts`): `create-docus [dir] [-t|--template default|i18n]`. Default `dir` is **`docs`**, default template `default`. It runs `nuxi init <dir> -t gh:nuxt-content/docus/.starters/<template>` (via `@nuxt/cli` runCommand). So `npx create-docus docs` / `pnpm create docus docs` / `pnpm dlx create-docus docs` all scaffold a `docs/` folder from `.starters/default` (pnpm create resolution is standard npm behavior, UNVERIFIED by run).
- VERIFIED: `.starters/default` file tree at v5.13.0:
  ```
  .gitignore  README.md  nuxt.config.ts  package.json  tsconfig.json
  public/favicon.ico  public/mountains.webp
  content/index.md
  content/1.getting-started/.navigation.yml  (title: Getting Started / icon: false)
  content/1.getting-started/{2.introduction,3.installation,4.project-structure,5.studio,6.migration,7.troubleshooting}.md
  content/2.essentials/.navigation.yml
  content/2.essentials/{1.markdown-syntax,2.code-blocks,3.components,4.images-embeds}.md
  ```
  There is NO `app.config.ts` in the starter: it is optional.
- VERIFIED: starter `package.json` = scripts `dev: nuxt dev`, `build: nuxt build`; deps `docus: latest`, `better-sqlite3: ^12.11.1`, `nuxt: ^4.4.8`.
- VERIFIED: starter `nuxt.config.ts` = `export default defineNuxtConfig({ extends: ['docus'] })`.
- VERIFIED: starter `tsconfig.json` references `./.nuxt/tsconfig.{app,server,shared,node}.json` (Nuxt 4 style).
- VERIFIED: starter `.gitignore` ignores `.output .data .nuxt .nitro .cache dist node_modules logs *.log .env .env.*` etc.
- VERIFIED: i18n starter exists at `.starters/i18n` with `content/en/**` and `content/fr/**`.

## 3.x Configuration findings (source: docus repo tag v5.13.0 `docs/content/en/2.concepts/3.configuration.md`, `layer/nuxt.schema.ts`, `layer/modules/config.ts`, `layer/app/app.config.ts`)

All VERIFIED from source at tag v5.13.0 unless marked.

- Config file: `app.config.ts` (Nuxt app config). WARNING from Docus docs: a `nuxt.config.ts` must exist, or app.config overrides are not applied. With Nuxt 4 `srcDir = app/`, so the file is `docs/app/app.config.ts` (the Docus own site uses `docs/app/app.config.ts`).
- Automatic defaults (layer/modules/config.ts):
  - `site.name` = `nuxt.config site.name` || package.json `name` || git repo name. So set `"name"` in `docs/package.json` or `site: { name: 'tui4s' }` in nuxt.config.
  - `seo.titleTemplate` = `%s - ${siteName}`; `seo.title` = siteName; `seo.description` = package.json `description`.
  - `header.title` = siteName.
  - `github` = `{ owner, name, url }` from `.git/config` remote `origin` of **rootDir** (via pkg-types `readGitConfig(dir)`) or CI env (`GITHUB_REPOSITORY`, `GITHUB_SERVER_URL`, `VERCEL_GIT_*`); `branch` from env (`GITHUB_REF_NAME`, `VERCEL_GIT_COMMIT_REF`, ...) or `git rev-parse`, else `main`.
  - Site URL inferred from env `NUXT_PUBLIC_SITE_URL` || `NUXT_SITE_URL` || `VERCEL_PROJECT_PRODUCTION_URL` || `VERCEL_BRANCH_URL` || `VERCEL_URL` || `URL` (Netlify) || `CI_PAGES_URL` || `CF_PAGES_URL`. **No GitHub Pages auto-detection**, so set `NUXT_SITE_URL` (or `site.url`) for GitHub Pages. Used for sitemap, llms.txt `domain`, OG.
  - `llms` defaults: `{ domain: siteUrl, title: siteName, description: pkg.description, full: { title, description } }`.
- `app.config.ts` keys (schema in layer/nuxt.schema.ts):
  - `seo: { titleTemplate, title, description, schema?: { type: 'SoftwareApplication'|'Product'|'Organization'|'Person', applicationCategory, operatingSystem, price, priceCurrency, sameAs[], organization{ name,url,logo,sameAs, parentOrganization } } }`
  - `header: { title, logo: { light, dark, alt, wordmark{light,dark}, class, display: 'logo'|'wordmark', favicon: '/favicon.ico', brandAssetsUrl } }`
  - `socials: Record<string,string>` keys are Simple Icons names, e.g. `{ github: '...', discord: '...', x: '...' }` (shown in footer).
  - `toc: { title: 'On this page', bottom: { title: 'Community', links: [{ icon, label, to, target }] } }`
  - `github: { url, branch: 'main', rootDir: '' }` or `github: false`. Provides header/footer GitHub icon and per-page **"Edit this page"** and **"Report an issue"** links. For a monorepo docs/ subfolder set `rootDir: 'docs'`.
  - `navigation: { sub: '' | 'header' | 'aside' }` (sub-navigation from top-level content folders).
  - `search: { fts: false }` (default Fuse.js via Nuxt UI ContentSearch; `fts: true` = SQLite FTS5 WASM, needs @nuxt/content >= 3.14).
  - `docus: { locale: 'en', colorMode: ''|'light'|'dark', shortcuts: { toggleColorMode: 'd' } }`
  - `ui: { colors: { primary: 'emerald', neutral: 'zinc' }, icons: {...} }` (layer default primary **emerald**, neutral **zinc**; any Tailwind color name).
  - `assistant: { floatingInput, explainWithAi, faqQuestions, shortcuts.focusInput, icons }` (AI assistant, needs AI gateway key; not needed for tui4s).
- SEO per page: front-matter `seo: { title, description }`; `sitemap: false` excludes a page. Sitemap auto at `/sitemap.xml` (prerendered). OG images auto via nuxt-og-image (Takumi renderer, `ogImage.zeroRuntime: true`); override with `app/components/OgImage/Docs.takumi.vue` / `Landing.takumi.vue`.
- Structured data JSON-LD (`Article`, `BreadcrumbList`, `WebSite`) emitted automatically.
- Header/footer links beyond GitHub/socials: via overriding layer components (`app/components/AppHeaderCTA.vue`, `AppHeaderLogo.vue`, `AppFooterLeft/Right.vue` etc. exist in the layer and can be shadowed by same-named files, standard Nuxt layer behavior). The Docus own site overrides `AppHeaderCTA.vue` and `AppHeaderLogo.vue`. (VERIFIED file names; shadowing = standard Nuxt layers behavior, UNVERIFIED for each component.)
- Theme (VERIFIED, docs `2.concepts/4.theme.md`): create `app/app.css` with a Tailwind v4 `@theme { --font-sans: ...; --color-green-500: ... }` block. Docus auto-imports `app/app.css`; do NOT add it to `css` and do NOT `@import "tailwindcss"` there. Color aliases primary/secondary/success/info/warning/error/neutral set via `app.config.ts` `ui.colors`. Nuxt UI component themes overridable under `ui.<component>`. Docus subcomponent variants: `ui.contentToc.defaultVariants`, `ui.contentNavigation.defaultVariants`, `ui.navigationMenu.defaultVariants`.
- Component overrides (VERIFIED, docs `2.concepts/5.customization.md`): create same-named file in `app/components/`: `AppHeaderLogo`, `AppHeaderLeft`, `AppHeaderCTA` (empty by default, use for header links like "GitHub"/"Get started"), `AppHeaderCenter` (replaces search), `AppHeaderBody`, `AppHeaderBottomRight`, `AppFooterLeft`, `AppFooterRight`, `DocsPageHeaderLinks` (the "copy page / view markdown / open in ChatGPT/Claude" dropdown), `DocsAsideRight`, `DocsAsideRightBottom`, `DocsAsideLeftTop`, `DocsAsideLeftBody`.
- Custom icons (VERIFIED): SVGs in `app/assets/icons/*.svg` are registered as `i-custom:<name>`. Built-in icon sets bundled: lucide, simple-icons, vscode-icons (from layer deps); other iconify sets fetched from Iconify API (`icon.provider: 'iconify'`).

## 4.x / 5.x Page internals (VERIFIED, `layer/app/pages/[[lang]]/[...slug].vue`, `DocsPageHeaderLinks.vue`, `templates/landing.vue` at v5.13.0)

- Edit link format: `${github.url}/edit/${github.branch}/${github.rootDir}/content/${page.stem}.${page.extension}` (empty parts dropped). So for tui4s with docs in `docs/`: `github: { url: 'https://github.com/<owner>/tui4s', branch: 'main', rootDir: 'docs' }` gives `.../edit/main/docs/content/...md`. "Report an issue" goes to `${github.url}/issues/new/choose`.
- Every docs page calls `addPrerenderPath('/raw' + route.path + '.md')`, so raw Markdown files ARE prerendered in a static `nuxt generate` build (`/raw/<path>.md`).
- Page header: renders front-matter `links` (array of `{label, icon, to, target}` UButtons, from the Docus docs collection schema) plus `DocsPageHeaderLinks`: "Copy page" button (fetches `/raw<path>.md` and copies Markdown), dropdown: copy markdown link, view markdown, open in ChatGPT, open in Claude, "Copy MCP Server URL", "Add MCP Server" (Cursor deeplink `<mcp route>/deeplink`). Links use `runtimeConfig.app.baseURL`, so they work under `/tui4s/`.
- Page title/description: `seo.title || title`, `seo.description || description`. Landing page (`content/index.md`) supports `seo.ogImage` front-matter to use a static OG image.
- Docs collection schema adds only `links` to the standard Nuxt Content `page` fields. The collection is `docs` = `content/**` except `index.md` (or `content/docs/**` with `/docs` prefix if a `content/docs/` folder exists); `landing` = `content/index.md` unless `app/pages/index.vue` exists.

## 5.x Agent-friendly features (VERIFIED from docs `docs/content/en/4.ai/{2.mcp,3.skills,4.llms}.md` at v5.13.0)

- **llms.txt / llms-full.txt**: built in via `nuxt-llms`; both generated and prerendered automatically. Defaults: domain from site URL env (`NUXT_SITE_URL`), title/description from `package.json`. Override in `nuxt.config.ts`:
  ```ts
  llms: {
    domain: 'https://<owner>.github.io/tui4s',
    title: 'tui4s', description: '...',
    full: { title: 'tui4s', description: '...' },
    sections: [{ title: 'Developer Resources', description: '...', links: [{ title: 'Source on GitHub', description: '...', href: 'https://github.com/...' }] }],
    notes: ['When to use this library: ...'],
    contentRawMarkdown: { excludeCollections: ['landing'], rewriteLLMSTxt: true }, // or false to disable /raw
  }
  ```
- **Raw Markdown routes**: `/raw/<page-path>.md` (content-type `text/markdown; charset=utf-8`), links in llms.txt rewritten to `/raw/...md`. Prerendered per page by the Docus slug page (see 4.x). Disable with `llms.contentRawMarkdown: false`.
- **"Copy page as Markdown"**: built into every docs page header (`DocsPageHeaderLinks`), plus "View as Markdown", "Open in ChatGPT", "Open in Claude".
- **Markdown content negotiation** (`Accept: text/markdown` or `curl` UA -> raw md): **Vercel only** (module `vercel-markdown-rewrite`, writes Vercel Build Output routes). Not on GitHub Pages.
- **MCP server**: built in at `/mcp` via `@nuxtjs/mcp-toolkit`, HTTP transport, tools `list-pages` and `get-page`; extend in `server/mcp/tools|resources|prompts/`. Disable: `mcp: { enabled: false }` in nuxt.config. NOTE: the MCP server is a Nitro server route, so it needs a server runtime (Node/Vercel/Netlify functions). On a pure static host (GitHub Pages) `/mcp` will NOT work (inference from architecture; UNVERIFIED by running).
- **Agent Skills**: `skills/<name>/SKILL.md` in the docs root is served at `/.well-known/skills/index.json` and `/.well-known/skills/<name>/...` (Cloudflare Agent Skills Discovery RFC). Change dir with `docus: { skills: { dir: 'agent-skills' } }`. Users install with `npx skills add https://<docs-url>`.
- **Agent-friendly 404**: markdown 404 body for non-HTML clients (server runtime only); disable with `docus: { notFound: false }`.
- NOTE (VERIFIED via GitHub compare v5.13.0...main, 12 commits ahead on 2026-09-30): unreleased `main` migrates agent features to `nuxt-agent-discovery` (#1435) and SEO to `nuxt-schema-org` (#1439). A Docus release after 5.13.0 may change these keys. Pin `docus` to `5.13.0` exactly to avoid surprises.

## 6.x Build and deploy findings

- VERIFIED (nuxt/nuxt.com `content/deploy/github-pages.md`): GitHub Pages is static only; without a custom domain set `NUXT_APP_BASE_URL=/<repository>/` for the build, e.g. `NUXT_APP_BASE_URL=/tui4s/ npx nuxt build --preset github_pages`; upload `./.output/public` with `actions/upload-pages-artifact`, deploy with `actions/deploy-pages` (needs `permissions: pages: write, id-token: write`, `environment: github-pages`).
- VERIFIED: `nuxt@4.5.2` -> `@nuxt/nitro-server 4.5.2` -> `nitropack ^2.13.4`. Nitro 2.13.4 `src/presets/_static/preset.ts`: preset `github_pages` extends `static` (output `.output/public`, `prerender.crawlLinks: true`), adds prerender routes `/` and `/404.html`, and writes `.nojekyll` into the public dir after compile. So `.well-known/` and `_nuxt/` are safe on Pages.
- VERIFIED (Docus layer nuxt.config): `nitro.prerender = { crawlLinks: true, failOnError: false, autoSubfolderIndex: false }`, plus `/` (or `/<locale>` for i18n) and `/sitemap.xml` added to prerender routes. `autoSubfolderIndex: false` means pages are emitted as `/guide/intro.html` rather than `/guide/intro/index.html`. GitHub Pages serves `/guide/intro` from `guide/intro.html` (GitHub Pages extensionless-html behavior: UNVERIFIED here but widely documented).
- VERIFIED: Docus prerenders `/raw/<path>.md` per page and `/.well-known/skills/**`, llms.txt/llms-full.txt (nuxt-llms), so all agent read-only files work on a static host. Server-only features that do NOT work statically: `/mcp`, AI assistant, markdown 404, Accept-header negotiation (Vercel only).
- VERIFIED (docs `1.getting-started/7.troubleshooting.md`): with pnpm, approve builds for `better-sqlite3` and `sharp` (`pnpm approve-builds`); if errors like `Can't resolve 'tailwindcss'` / `'@nuxt/ui'`, add `.npmrc` with `shamefully-hoist=true`. Docus docs recommend npm.
- VERIFIED (docus repo `pnpm-workspace.yaml` at v5.13.0): Docus itself uses `onlyBuiltDependencies: [better-sqlite3, sharp, ...]` and `ignoredBuiltDependencies: ['@parcel/watcher', '@tailwindcss/oxide', esbuild, unrs-resolver, vue-demi]` (plus pnpm 11 `allowBuilds`). Its own packageManager is pnpm@11.22.0; CI uses node 22.
- VERIFIED (docus repo `docs/nuxt.config.ts`): real config shape used by docus.dev: `extends: ['docus']`, `site: { name }`, `llms: { domain, title, description, full, sections, notes }`, `mcp: { name: 'Docus documentation', browserRedirect: '/en/ai/mcp' }`, `mdc.highlight.shikiEngine: 'javascript'`. `docs/app/app.config.ts` uses `github: { rootDir: 'docs' }` (url/branch inferred from git), `header.logo`, `socials`, `toc.bottom.links`, `search.fts: true`, `seo.schema`.

## 4.x Content authoring findings

- VERIFIED (docs `2.concepts/2.edition.md`): front-matter keys for docs pages: `title`, `description` (both shown in page header and used as SEO fallbacks), `navigation` (boolean, or object with `title`/`icon` as used throughout Docus own pages e.g. `navigation: { icon: i-lucide-settings, title: Components }`), `layout` (default `docs`), `seo: { title, description }` (landing also `seo.ogImage`), `links` (page header buttons, `[{label, icon, to, target}]`), `sitemap: false`.
- VERIFIED: routing is file-based; `content/index.md` = landing `/` (MDC with Nuxt UI components like `::u-page-hero`, `::u-page-section`, `::u-page-grid`, `::u-page-card`, `::u-page-feature`, `:::u-button`); `content/1.getting-started/2.introduction.md` -> `/getting-started/introduction` (numeric prefixes order items and are stripped from URLs, Nuxt Content convention; ordering behavior VERIFIED by Docus starter naming, stripping VERIFIED by Docus links like `/en/getting-started/installation` pointing at `1.getting-started/3.installation.md`).
- VERIFIED: folder metadata via `<folder>/.navigation.yml`, e.g. `title: Getting Started` + `icon: i-lucide-rocket` (or `icon: false`).
- VERIFIED (docs `3.essentials/3.components.md`, `2.code-blocks.md`): MDC components out of the box (from Nuxt UI prose): `::note`, `::tip`, `::warning`, `::caution`, `::callout{icon color to}`, `::badge`, `::card{title icon to target}` + `::card-group`, `::accordion` + `::accordion-item`, `::collapsible`, `::field` + `::field-group`, `:icon{name="i-simple-icons-scala"}`, `:kbd{value="meta"}`, `::tabs` + `::tabs-item{label icon}`, `::steps{level="4"}` (wraps headings), `::code-group` (tabs of fenced blocks, tab label = filename in brackets), `::code-tree{default-value="path"}`, `::code-preview` (with `#code` slot), `::code-collapse`. Any Nuxt UI component can be used as `::u-<name>`.
- VERIFIED: fenced code filename syntax: ```` ```ts [nuxt.config.ts] ````; icon auto-chosen by extension/name; copy button built in.
- VERIFIED (layer/nuxt.config.ts): Docus sets `content.build.markdown.highlight.langs = ['bash','diff','json','js','ts','html','css','vue','shell','mdc','md','yaml']`. **Scala is NOT in the default list** -> add it in docs/nuxt.config.ts: `content: { build: { markdown: { highlight: { langs: ['scala', 'java'] } } } }`. Nuxt layers merge with defu (c12 README: "Multiple sources merged with unjs/defu"; defu source: arrays are concatenated `[...value, ...object[key]]`), so the user list is ADDED to Docus's list, not replacing it (VERIFIED from defu/c12 source; not run).
- VERIFIED (shiki `docs/references/engine-js-compat.md`): `scala` is "OK" with Shiki's JavaScript regex engine (Docus uses `mdc.highlight.shikiEngine: 'javascript'`). Only `ahk2` is unsupported. There is no `sbt` grammar in Shiki (UNVERIFIED; use `scala` for `build.sbt`).
- VERIFIED (nuxt-content/mdc `src/runtime/parser/handlers/utils.ts`): code fence meta parses language, `{...}` line highlights (comma list with ranges) and `[...]` filename, e.g. ```` ```scala {2,4-6} [Main.scala] ````.
- VERIFIED (nuxt/content docs `1.getting-started/3.configuration.md`): `content.build.markdown.highlight.theme` (string or `{ default, dark, sepia }`) and `langs` (list of Shiki lang ids, or a parsed tmLanguage JSON for custom grammars).
- VERIFIED (nuxt/content docs `6.deploy/10.static.md`): static hosting via `nuxi generate`; content DB loads in the browser via WASM SQLite for client-side queries.

## 7.x Real-world examples

- VERIFIED **nuxt/image** (`github.com/nuxt/image/tree/main/docs`): Docus `^5.13.0` in a `docs/` subfolder of a library repo. Files: `docs/nuxt.config.ts` (`extends: ['docus']`, `site.name`, `llms.domain/description`, `mcp.name`), `docs/app/app.config.ts` (`header.title`, `ui.colors`, `github: { rootDir: 'docs' }`, `socials`, `toc.bottom.links`), `docs/app/components/{AppHeaderBody,AppHeaderCenter,AppHeaderLogo,DocsAsideLeftTop}.vue` overrides, `docs/app/components/content/*.vue` (custom MDC components), `docs/content/1.get-started/1.installation.md`, `docs/content/2.usage/...`. Uses `better-sqlite3 ^13.0.3` (outside Docus peer range 12.x: works for them but is a peer warning).
- VERIFIED **nuxt-modules/i18n** `docs/package.json`: `docus ^5.12.3`, `better-sqlite3 ^12.11.1`, `nuxt ^4.4.8`, scripts `build: nuxt build`, `generate: nuxt prepare && nuxt generate`, `preview`, `typecheck`.
- VERIFIED **nuxt-content/docus** itself: `docs/` subfolder, `github: { rootDir: 'docs' }` in `docs/app/app.config.ts`.
- VERIFIED **relaticle/flowforge** (branch `4.x`, `docs/`): **best template for tui4s**: Docus `^5.13.0` + `nuxt ^4.5.2` + `better-sqlite3 ^12.11.1` in `docs/` subfolder of a non-JS (PHP/Laravel) repo, deployed to **GitHub Pages under a project path** `/flowforge/`. Key points from its `docs/nuxt.config.ts`: `const baseURL = process.env.NUXT_APP_BASE_URL || '/'`; `app: { baseURL, buildAssetsDir: 'assets' }`; logos/favicon/og image paths manually prefixed with `baseURL`; `content.build.markdown.highlight.langs: ['php','blade']` (same pattern as scala); `llms.domain: https://relaticle.github.io${baseURL without trailing slash}`; `nitro.preset: 'github_pages'`. Its workflow `.github/workflows/deploy-docs.yml`: node 24, `npm ci` in `./docs`, `npm run generate` (= `nuxt build --preset github_pages`) with env `NUXT_APP_BASE_URL=/flowforge/`, `NUXT_SITE_URL=https://relaticle.github.io`, uploads `docs/.output/public` with `include-hidden-files: true` (keeps `.nojekyll`, `.well-known`), then pushes to a `gh-pages` branch.
- VERIFIED **meatpiHQ/wican-fw** `docs/`: GitHub Pages with `NUXT_APP_BASE_URL=/wican-fw/ npx nuxt build --preset github_pages`, but it is OLD Docus v1 (`@nuxt-themes/docus`, Nuxt 3); use only for the workflow shape.
- VERIFIED **limecloud/lime** `.github/workflows/deploy-docs.yml`: pnpm in `docs/` with `pnpm install --ignore-workspace`, `actions/configure-pages@v5`, `upload-pages-artifact@v3` path `docs/.output/public`, `deploy-pages@v4`.
- VERIFIED LIVE (curl 2026-09-30) `https://relaticle.github.io/flowforge/`: `/` 200 html; `/getting-started/installation` 200 html (extensionless works on Pages); `/raw/getting-started/installation.md` 200 `text/markdown`; `/llms.txt` + `/llms-full.txt` 200 (links point at `https://relaticle.github.io/flowforge/raw/...md`); `/sitemap.xml` 200; `/404.html` 200; `/.nojekyll` present; `/mcp` **404** (confirms MCP needs a server runtime). Asset URLs are prefixed `/flowforge/assets/...`.
- VERIFIED (layer `app/app.vue`): Docus adds `<link rel="icon" href="/favicon.ico">` WITHOUT baseURL. Under `/tui4s/` that 404s -> add `app.head.link: [{ rel: 'icon', href: '/tui4s/favicon.ico' }]` in nuxt.config (flowforge does exactly this). Risk: two icon links may coexist (browser choice UNVERIFIED).
- VERIFIED (Nuxt UI v4.11.2 `src/runtime/utils/index.ts` `resolveBaseURL`, used by `UColorModeImage` which renders the header logo): paths starting with `/` get `app.baseURL` prefixed automatically (not double-prefixed). So `header.logo.light: '/logo-light.svg'` works under `/tui4s/` without manual prefixing. (flowforge prefixes manually; harmless because resolveBaseURL skips already-prefixed paths.)
- VERIFIED LIVE (flowforge, 2026-09-30): with `NUXT_SITE_URL=https://relaticle.github.io` (no base path) the generated `sitemap.xml` `<loc>`, `<link rel="canonical">` and `og:url` are WRONG (`https://relaticle.github.io/getting-started/installation`, missing `/flowforge/`), while `og:image` and llms.txt links are correct (`.../flowforge/_og/...`, `.../flowforge/raw/...`). So for tui4s the site URL handling under a base path must be checked (see 6.y).

### 6.y Site URL under a base path (VERIFIED from source, consequences inferred)

- Docus canonical/og:url: `joinURL(site.url, route.path)` (`layer/app/composables/useSeo.ts`); `route.path` excludes `app.baseURL`.
- Docus sitemap: `inferSiteURL()` (env only: `NUXT_PUBLIC_SITE_URL`/`NUXT_SITE_URL`/...) + page path (`layer/server/routes/sitemap.xml.ts`). It ignores `site.url` in nuxt.config!
- llms `domain` default = `inferSiteURL()` (env).
- nuxt-og-image 6.7.8 builds `og:image` as `joinURL('/', app.baseURL, '/_og/...')` then absolutizes with the site URL (absolutizing code path not traced).
- => For GitHub Pages under `/tui4s/` set env **`NUXT_SITE_URL=https://<owner>.github.io/tui4s`** (base path INCLUDED) at build time. This fixes canonical, og:url, sitemap and llms links. RISK (UNVERIFIED): og:image may become `.../tui4s/tui4s/_og/...`; check `grep og:image docs/.output/public/index.html` after the first build. If doubled, set `llms.domain` explicitly and move the OG base elsewhere, or use a custom domain / Vercel (no base path, no problem).

### 6.z GitHub Actions and Vercel details

- VERIFIED latest action releases (GitHub API, 2026-09-30): `actions/checkout` v7.0.1, `actions/setup-node` v7.0.0, `actions/upload-pages-artifact` v5.0.0, `actions/deploy-pages` v5.0.1, `actions/configure-pages` v6.0.0, `pnpm/action-setup` v6.1.0.
- VERIFIED (`actions/upload-pages-artifact@v5.0.0` action.yml): hidden files are EXCLUDED by default (`--exclude=.[^/]*`). Set **`include-hidden-files: true`**, or `.well-known/skills/**` and `.nojekyll` are dropped. (flowforge sets `include-hidden-files: true` on its artifact upload.)
- VERIFIED (`pnpm/action-setup@v6.1.0` action.yml): input `package_json_file` (relative to repo root) reads `packageManager`; use `package_json_file: docs/package.json` for a subfolder project. `cache: true` + `cache_dependency_path: docs/pnpm-lock.yaml` available.
- VERIFIED (pnpm.io `versioned_docs/version-10.x/settings.md`): pnpm 10 does not run dependency lifecycle scripts unless allowed; `allowBuilds` map (added v10.26.0, replaces `onlyBuiltDependencies`/`ignoredBuiltDependencies`) and `shamefullyHoist` are valid `pnpm-workspace.yaml` settings; a per-project `pnpm-workspace.yaml` is a supported config location. Local toolchain pnpm is 10.34.4 (supports `allowBuilds`).
- Vercel alternative (VERIFIED from vercel.com/docs/monorepos and nuxt.com deploy/vercel.md): import the repo, set **Root Directory = `docs`**, Nuxt/Nitro is auto-detected (zero config, preset `vercel`), build `nuxt build`. No base path needed (served at `/`). Docus infers site URL from `VERCEL_PROJECT_PRODUCTION_URL` etc. and branch from `VERCEL_GIT_COMMIT_REF`. On Vercel ALL agent features work: `/mcp` MCP server, `Accept: text/markdown` negotiation (vercel-markdown-rewrite module), markdown 404. The automatic "skip unaffected projects" needs a JS workspace at repo root, so for the Scala repo use the "Ignored Build Step" setting (e.g. `git diff --quiet HEAD^ HEAD -- .` run inside Root Directory: UNVERIFIED exact command).

## 8. Minimal working file set for `docs/` (drop in verbatim)

Status: composed ONLY from verified sources above (Docus v5.13.0 starter, docus.dev own config, flowforge GitHub Pages config, pnpm 10 docs). NOT build-tested here (task was research only). First thing to do after dropping it in: `source .work/env.sh && cd docs && pnpm install && pnpm run generate`, then check `.output/public/{index.html,llms.txt,raw/getting-started/installation.md,sitemap.xml}`.

Repo facts used (VERIFIED): `git remote origin` = `https://github.com/yuri4n/tui4s.git`, so GitHub Pages URL = `https://yuri4n.github.io/tui4s/`. Artifact coordinates below (`io.github.yuri4n`, `tui4s-core`/`tui4s-zio`/`tui4s-cats`) are PLACEHOLDERS to align with the build plan.

Side note (VERIFIED via GitHub API 2026-09-30): `github.com/oyvindberg/tui-scala` now redirects to `oyvindberg/jatatui` ("A Java port of ratatui"). Relevant for whoever researches the wrapped library.

Tree:
~~~text
docs/
├── .gitignore
├── package.json
├── pnpm-workspace.yaml        # pnpm 10 per-project settings (build approvals)
├── nuxt.config.ts
├── tsconfig.json
├── app/
│   └── app.config.ts
├── content/
│   ├── index.md               # landing page  -> /
│   └── 1.getting-started/
│       ├── .navigation.yml
│       └── 1.installation.md  # guide page    -> /getting-started/installation
└── public/
    └── favicon.ico            # copy any .ico (the Docus starter ships one)
.github/workflows/docs.yml     # at repo root
~~~

### docs/package.json
~~~json
{
  "name": "tui4s-docs",
  "description": "Declarative terminal UIs for Scala 3. Kyo core, with ZIO and Cats Effect integrations.",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "nuxt dev",
    "build": "nuxt build",
    "generate": "nuxt generate",
    "build:pages": "nuxt build --preset github_pages",
    "preview": "nuxt preview"
  },
  "dependencies": {
    "better-sqlite3": "12.11.1",
    "docus": "5.13.0",
    "nuxt": "4.5.2"
  },
  "engines": {
    "node": "^22.19.0 || ^24.11.0 || >=26.0.0"
  },
  "packageManager": "pnpm@10.34.4"
}
~~~
Notes: versions exact (VERIFIED latest on npm 2026-09-30; `better-sqlite3` kept on 12.x because Docus peer is `12.x`). `description` feeds `seo.description` and llms.txt. npm alternative: delete `packageManager` and `pnpm-workspace.yaml`, run `npm install` (Docus docs recommend npm; flowforge uses npm).

### docs/pnpm-workspace.yaml
~~~yaml
# Standalone pnpm project settings (pnpm >= 10.26). Mirrors the Docus repo.
allowBuilds:
  better-sqlite3: true
  sharp: true
  '@parcel/watcher': false
  '@tailwindcss/oxide': false
  esbuild: false
  unrs-resolver: false
  vue-demi: false
# Docus troubleshooting: fixes "Can't resolve 'tailwindcss' / '@nuxt/ui'" with pnpm.
shamefullyHoist: true
~~~

### docs/nuxt.config.ts
~~~ts
// Base path: '/' for local dev and Vercel, '/tui4s/' on GitHub Pages (set by CI).
const baseURL = process.env.NUXT_APP_BASE_URL || '/'

export default defineNuxtConfig({
  extends: ['docus'],

  site: {
    name: 'tui4s',
  },

  app: {
    baseURL,
    head: {
      // Docus adds `/favicon.ico` without the base path, so add a correct one.
      link: [{ rel: 'icon', type: 'image/x-icon', href: `${baseURL}favicon.ico` }],
    },
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

  // llms.txt, llms-full.txt and /raw/*.md. `domain` comes from NUXT_SITE_URL.
  llms: {
    title: 'tui4s',
    description: 'Declarative terminal UIs for Scala 3. Kyo core, with ZIO and Cats Effect integrations.',
    full: {
      title: 'tui4s',
      description: 'Declarative terminal UIs for Scala 3. Kyo core, with ZIO and Cats Effect integrations.',
    },
    sections: [
      {
        title: 'Developer Resources',
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
      'When to use tui4s: you write a terminal UI in Scala 3 and want a declarative, effect-typed API over tui-scala.',
      'Pick one module: tui4s-core (Kyo), tui4s-zio (ZIO 2) or tui4s-cats (Cats Effect 3). Each integration is a separate artifact.',
      'Reading this documentation as an agent: fetch /llms-full.txt, or the raw Markdown of a page at /raw/<page-path>.md.',
    ],
  },

  // MCP server needs a server runtime. It works on Vercel, and is a 404 on GitHub Pages.
  mcp: {
    name: 'tui4s documentation',
  },
})
~~~

### docs/app/app.config.ts
~~~ts
export default defineAppConfig({
  header: {
    title: 'tui4s',
    // logo: { light: '/logo-light.svg', dark: '/logo-dark.svg', alt: 'tui4s' }, // base path added automatically
  },
  seo: {
    title: 'tui4s',
    description: 'Declarative terminal UIs for Scala 3. Kyo core, with ZIO and Cats Effect integrations.',
  },
  ui: {
    colors: {
      primary: 'red',
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
    bottom: {
      title: 'Links',
      links: [
        {
          icon: 'i-simple-icons-github',
          label: 'Source on GitHub',
          to: 'https://github.com/yuri4n/tui4s',
          target: '_blank',
        },
      ],
    },
  },
})
~~~

### docs/tsconfig.json (verbatim from Docus starter)
~~~json
{
  "files": [],
  "references": [
    { "path": "./.nuxt/tsconfig.app.json" },
    { "path": "./.nuxt/tsconfig.server.json" },
    { "path": "./.nuxt/tsconfig.shared.json" },
    { "path": "./.nuxt/tsconfig.node.json" }
  ]
}
~~~

### docs/.gitignore (from Docus starter, trimmed)
~~~gitignore
.output
.data
.nuxt
.nitro
.cache
dist
node_modules
logs
*.log
.DS_Store
.idea
.eslintcache
.env
.env.*
!.env.example
*.tgz
.tmp
~~~

### docs/content/index.md
~~~md
---
seo:
  title: tui4s - declarative terminal UIs for Scala 3
  description: Build terminal UIs in Scala 3 with a declarative API. Kyo core, with ZIO and Cats Effect integrations.
---

::u-page-hero
#title
Terminal UIs for Scala 3, declared.

#description
tui4s wraps tui-scala with a declarative, effect-typed API. Use the Kyo core, or add the ZIO or Cats Effect integration.

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
  Star on GitHub
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
  Widgets are values. State lives in Kyo effects, and the core renders each frame.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-zap
  to: /getting-started/installation
  ---
  #title
  ZIO integration

  #description
  Run the same UI inside a ZIO app with the `tui4s-zio` module.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-square-terminal
  to: /getting-started/installation
  ---
  #title
  Cats Effect integration

  #description
  Run the same UI inside an `IOApp` with the `tui4s-cats` module.
  :::
::
~~~

### docs/content/1.getting-started/.navigation.yml
~~~yaml
title: Getting Started
icon: i-lucide-rocket
~~~

### docs/content/1.getting-started/1.installation.md
~~~~md
---
title: Installation
description: Add tui4s to your Scala 3 project and draw your first frame.
navigation:
  icon: i-lucide-download
seo:
  title: Install tui4s
  description: Add the tui4s Kyo core, ZIO or Cats Effect module to an sbt or scala-cli project.
---

## Requirements

- Scala 3
- JDK 21 or later
- A terminal that supports ANSI escape codes

::note
tui4s uses the crossterm backend of tui-scala. The backend loads a native library at startup.
::

## Add the dependency

Pick **one** module. Each module is a separate artifact.

::code-group
```scala [build.sbt]
libraryDependencies += "io.github.yuri4n" %% "tui4s-core" % "<version>"
```

```scala [project.scala]
//> using dep io.github.yuri4n::tui4s-core:<version>
```
::

::tip
For ZIO use `tui4s-zio`. For Cats Effect use `tui4s-cats`. Each integration depends on `tui4s-core`.
::

## Draw your first frame

::steps{level="3"}
### Write the app

```scala [Main.scala] {3-5}
import tui4s.*

object Main extends TuiApp:
  def view(state: Int): Widget =
    Paragraph(s"Count: $state")
```

### Run it

```bash [Terminal]
sbt run
```
::

::warning
Run the app in a real terminal. An IDE run console often does not support raw mode.
::
~~~~

Notes on the content above: the Scala API in `Main.scala` is a PLACEHOLDER; replace it with the real tui4s API once it exists. MDC syntax (`::u-page-hero`, `#title` slots, `:::u-button` with YAML props, `::code-group`, `::steps{level}`, `::note/::tip/::warning`, ```` ```scala [file] {3-5} ````) is VERIFIED against the Docus v5.13.0 starter and docs, and nuxt-content/mdc fence parser. Internal links like `/getting-started/installation` go through NuxtLink so the `/tui4s/` base is added (standard Nuxt router behavior; UNVERIFIED by build).

### .github/workflows/docs.yml (repo root)
~~~yaml
name: docs

on:
  push:
    branches: [main]
    paths: ['docs/**', '.github/workflows/docs.yml']
  pull_request:
    paths: ['docs/**', '.github/workflows/docs.yml']
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: pages-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: docs
    steps:
      - uses: actions/checkout@v7
      - uses: pnpm/action-setup@v6
        with:
          package_json_file: docs/package.json
      - uses: actions/setup-node@v7
        with:
          node-version: 24
          cache: pnpm
          cache-dependency-path: docs/pnpm-lock.yaml
      - run: pnpm install --frozen-lockfile
      - run: pnpm run build:pages
        env:
          NUXT_APP_BASE_URL: /tui4s/
          NUXT_SITE_URL: https://yuri4n.github.io/tui4s
      - uses: actions/upload-pages-artifact@v5
        with:
          path: docs/.output/public
          include-hidden-files: true   # keep .nojekyll and .well-known/

  deploy:
    if: github.event_name != 'pull_request'
    needs: build
    runs-on: ubuntu-latest
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v5
~~~
One-time repo setting: Settings -> Pages -> Source = "GitHub Actions" (standard GitHub Pages requirement; UNVERIFIED here). Action major versions VERIFIED as latest releases, and the major tags `actions/checkout@v7`, `actions/setup-node@v7`, `actions/upload-pages-artifact@v5`, `actions/deploy-pages@v5`, `pnpm/action-setup@v6` VERIFIED to exist (GitHub API refs/tags).

### Local commands (for the agent)
~~~bash
source /Users/yuri4n/Developer/projects/tui4s/.work/env.sh
cd docs
pnpm install                      # writes pnpm-lock.yaml (commit it; CI uses --frozen-lockfile)
pnpm dev                          # http://localhost:3000
NUXT_APP_BASE_URL=/tui4s/ NUXT_SITE_URL=https://yuri4n.github.io/tui4s pnpm run build:pages
npx serve .output/public          # note: serves at /, so /tui4s/ paths need a base-aware server to test
~~~


## Open questions / risks

- RISK: `docus` main is 12 commits past 5.13.0 with agent/SEO refactors (`nuxt-agent-discovery`, `nuxt-schema-org`). Pin `docus` to `5.13.0` exactly; re-check keys on upgrade.
- RISK (UNVERIFIED): og:image may get a doubled base path (`/tui4s/tui4s/_og/...`) when `NUXT_SITE_URL` includes `/tui4s`. Check `grep og:image docs/.output/public/index.html` after first build.
- RISK: Docus `app.vue` hard-codes `<link rel="icon" href="/favicon.ico">` (no base). The extra base-aware link in nuxt.config is a workaround; two icon links will be present.
- RISK: On GitHub Pages the page-header dropdown still shows "Copy MCP Server URL"/"Add MCP Server", which point to a 404. Fix options: host on Vercel, or override `app/components/DocsPageHeaderLinks.vue` (copy the layer file and drop the MCP group). `mcp: { enabled: false }` exists but its effect on the dropdown/build was not verified.
- RISK: pnpm strictness. Docus troubleshooting mentions `approve-builds` for better-sqlite3/sharp and `shamefully-hoist=true`. Both handled in `docs/pnpm-workspace.yaml`; `allowBuilds` needs pnpm >= 10.26 (local is 10.34.4). npm is the zero-config fallback.
- RISK: better-sqlite3 13.x is latest but outside Docus peer range 12.x; keep 12.11.1.
- RISK (UNVERIFIED): client-side search (Fuse.js via Nuxt UI ContentSearch + Nuxt Content WASM SQLite) under a base path was not tested; flowforge site loads but search was not exercised.
- OPEN: artifact coordinates (group id, module names) in the sample pages are placeholders.
- OPEN: custom domain vs `yuri4n.github.io/tui4s` vs Vercel. Vercel gives MCP + markdown negotiation and no base-path issues; GitHub Pages keeps everything in GitHub.
- OPEN: optional `docs/skills/tui4s/SKILL.md` would publish an installable agent skill (`npx skills add https://yuri4n.github.io/tui4s`), prerendered, works statically.
- TIP: Docus ships an agent skill for authoring docs: `npx skills add https://docus.dev` (or `npx skills add nuxt-content/docus`); source in the docus repo `docs/skills/create-docs/` and `docs/skills/review-docs/` (VERIFIED files exist).
