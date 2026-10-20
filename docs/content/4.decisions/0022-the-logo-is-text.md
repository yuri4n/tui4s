---
title: "0022. The logo is text"
description: "The ASCII logotype of the maintainer is the logo of tui4s, and its 4 is the isomark. One script makes all the logo assets from the text file, and CI checks them."
---

<!-- claude 2026-10-20: created: ADR 0022 (the logo is text) -->

## Status

Accepted.

## Date

2026-10-20.

## Context

- tui4s had no logo. The header of the docs site showed the word "tui4s", and the favicon was the favicon of the Docus scaffold.
- tui4s is a terminal runner. The maintainer drew the logotype as ASCII art in the style of figlet: "tui4s" in 11 lines of 73 characters. The art uses only the characters `/`, `\` and `_`.
- A browser tab, a phone home screen and a social preview need image files of fixed sizes. A favicon needs a square mark.
- A logo file that a person draws by hand drifts from the text.

## Decision

- The logotype is `assets/logo/tui4s.txt`, the text of the maintainer. No script edits it.
- The isomark is the "4" of the logotype: the columns 39 to 56 of the text, counted from 0. In the logotype, the "4" has the accent colour.
- One script, `scripts/logo/build.mjs` (Node.js), makes all the assets from the text. It writes `assets/logo/isomark.txt` and the logotype block of `README.md`. In `docs/public`, it writes `logo.svg`, `logo-light.svg`, `logo-dark.svg`, `isomark.svg`, `favicon.ico` (PNG entries of 16, 32 and 48 px), `favicon-16x16.png`, `favicon-32x32.png`, `apple-touch-icon.png` (180 px), `icon-192.png`, `icon-512.png`, `site.webmanifest` and `og-image.png` (1200 by 630 px). The outputs are in the repository, so the site and the README need no build step.
- The script takes the outline route. Each character is the outline of its glyph in DejaVu Sans Mono 2.37. The glyphs sit on the grid of a terminal: the advance width of the font (1233 units) by its ascent plus its descent (2384 units). The SVG files hold only paths, so they need no font. `scripts/logo/glyphs.py` reads the outlines with fontTools. The generator keeps them as constants, so it needs no font file and no Python.
- DejaVu Sans Mono has the Bitstream Vera licence, and the DejaVu changes are in the public domain. The licence lets anyone use, copy and change the font. The logo holds three glyph outlines, not a font.
- Each path has a stroke of 0.8 CSS px that does not scale (`vector-effect="non-scaling-stroke"`). The lines of the art are thin: with no stroke, they almost disappear when the logo is small. An icon gets a stroke in proportion to its size.
- The light files have dark letters (`logo-light.svg`, and `logo.svg` is the same), and `logo-dark.svg` has light letters. An `<img>` cannot take the colour of the page, so each scheme has its own file.
- The icons show the isomark on the brand background. The favicons have round corners. `apple-touch-icon.png` has no transparent pixels. In `icon-192.png` and `icon-512.png`, the mark is inside the safe circle of a maskable icon (a diameter of 80 percent).
- `og-image.png` shows the logotype with light letters on the brand background, and the line "A terminal runner for kyo-ui, in Scala 3" under it. The line is text in the system font DejaVu Sans Mono.
- Headless Chromium (Playwright) draws each PNG file at its exact size, with a device scale factor of 1. The script writes the ICO file itself.
- The colours are in one block at the top of the generator:
  - the accent, `#eab308`: the default value of `--tui4s-accent` in tui4s-widgets (`Colors.defaults`, `Theme.terminal`);
  - the letters on a light page, `#0f172a`, and on a dark page, `#f8fafc`;
  - the brand background, `#18181b`: the dark background of the docs site (Nuxt UI with the neutral colour zinc). It is also the theme colour of the site and of the manifest.
- `node scripts/logo/build.mjs --check` compares the text outputs (`isomark.txt`, the README block, the SVG files and the manifest) with what the script writes for the current text. For the PNG and ICO files, it compares only the pixel sizes. It starts no browser. The CI step "Logo assets" runs it.
- The docs site shows the logotype in the header, 48 px high (`header.logo` in `docs/app/app.config.ts`). The links to the icons and to the manifest, the theme colour and the default preview image are in `app.head` of `docs/nuxt.config.ts`. The home page sets `/og-image.png` as its preview image in its front matter, and nuxt-og-image adds the URL of the site. Each other page keeps the preview image that nuxt-og-image makes from its title.
- The site serves the images as they are (`image.provider: 'none'`). The image optimizer of the site (IPX) gave the SVG logo the type `text/plain`, so the browser did not show it. Its SVGO pass also removed the stroke that does not scale.
- The social preview of the GitHub repository is a setting, not a file. A person uploads `docs/public/og-image.png` in **Settings > General > Social preview**.

## Alternatives considered

- **(a) A box-drawing wordmark that the agent draws.** Rejected by the maintainer: the figlet art is the brand.
- **(b) SVG files that a person draws by hand.** Rejected: they drift from the text.
- **(c) SVG text that uses the font of the viewer** (the text route, the fallback of the plan). Rejected: fontTools was available, so the script takes the outline route. With the text route, the shape of each glyph depends on the font of the viewer.
- **(d) No mark, only the word.** Rejected: a favicon and a social preview need a square mark.

## Consequences

- Good: a change of the art is one edit of `assets/logo/tui4s.txt` and one run of the generator.
- Good: the SVG files look the same in all browsers, because they hold no text.
- Good: CI finds an output that does not agree with the text.
- Bad: the art has 11 rows of thin lines. In the 16 px favicon, the isomark is only the yellow shape of a 4.
- Bad: the build of the PNG files needs Playwright and Chromium. The check needs only Node.js.
- Bad: on another machine, the PNG files can change by some bytes (another Chromium, or another system font for the line of the preview image). The check compares only their pixel sizes.
- Bad: the site uses no image optimizer. It has no other images, so nothing is lost now. A large image on a page later must be small before it goes in `docs/public`.

## References

- `assets/logo/tui4s.txt`, `scripts/logo/build.mjs`, `scripts/logo/glyphs.py`, `docs/app/app.config.ts`, `docs/nuxt.config.ts`, `CONTRIBUTING.md` (section "Docs").
- [ADR 0012](/decisions/0012-documentation-is-example-first).
