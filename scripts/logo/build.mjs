// The generator of the logo assets (ADR 0022).
// claude 2026-10-20: created (ADR 0022): the logotype, the isomark and their assets from one text file
//
// The source is assets/logo/tui4s.txt: the ASCII logotype of the maintainer,
// 11 lines. Do not edit it by script. Its fourth glyph, the "4", is the
// isomark: ISOMARK_COLUMNS gives its columns. This script writes:
//
// - assets/logo/isomark.txt: the columns of the "4";
// - the logotype block of README.md (under the line LOGO_MARKER);
// - docs/public/logo.svg, logo-light.svg, logo-dark.svg and isomark.svg;
// - docs/public/favicon.ico (PNG entries of 16, 32 and 48 px),
//   favicon-16x16.png, favicon-32x32.png, apple-touch-icon.png, icon-192.png,
//   icon-512.png, site.webmanifest and og-image.png.
//
// Each character is the outline of its glyph in DejaVu Sans Mono (FONT and
// GLYPHS; scripts/logo/glyphs.py reads them from the font), on the grid of a
// terminal: the advance width of the font by its ascent plus its descent. The
// SVG files are paths only, so they need no font. DejaVu Sans Mono has the
// Bitstream Vera licence (the DejaVu changes are in the public domain): it
// lets anyone use, copy and change the font. The logo holds three glyph
// outlines, not a font. Each path also has a thin stroke that does not scale
// (LINE_PX), so the lines stay visible when the logo is small.
//
// Headless Chromium (Playwright) draws the PNG files at their exact size, with
// a device scale factor of 1. The script writes the ICO file itself. The
// tagline of og-image.png is text in the system font DejaVu Sans Mono (else
// another monospace font).
//
//   node scripts/logo/build.mjs           # writes all the assets
//   node scripts/logo/build.mjs --check   # compares; starts no browser
//
// The check exits with 1 when isomark.txt, the README block, an SVG file or
// the manifest is not what this script writes for the current source, or
// when a PNG or ICO file is missing or has another pixel size. It needs
// only Node.js (22). The build also needs Playwright with Chromium, as
// scripts/web-smoke.mjs does: it finds the playwright package in
// node_modules, else in the global modules of npm. Set
// PLAYWRIGHT_BROWSERS_PATH where Chromium is installed. The outputs are
// generated files: commit them after a change of the source.
import { readFileSync, writeFileSync, existsSync, statSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// claude 2026-10-20: created (ADR 0022): all the colours of the logo, in one block
const COLORS = {
  // The "4": the default value of --tui4s-accent in tui4s-widgets
  // (Colors.defaults, Theme.terminal), Tailwind yellow-500.
  accent: '#eab308',
  // The letters on a light page (Tailwind slate-900).
  inkLight: '#0f172a',
  // The letters on a dark page (Tailwind slate-50).
  inkDark: '#f8fafc',
  // The background of the icons and of the OG image: the dark background of
  // the docs site (Nuxt UI --ui-bg in dark mode with the neutral colour zinc,
  // docs/app/app.config.ts: zinc-900). Also the theme colour of the manifest
  // and of docs/nuxt.config.ts (theme-color): change both.
  brand: '#18181b',
};

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const SOURCE = 'assets/logo/tui4s.txt';
const ISOMARK_TXT = 'assets/logo/isomark.txt';
const README = 'README.md';
const LOGO_MARKER = `<!-- logo: ${SOURCE} -->`;
const PUBLIC = 'docs/public';
// The columns of the "4" in SOURCE: from 39 (included) to 57 (excluded),
// counted from 0. The "i" ends at column 38 and the "s" starts at column 57.
const ISOMARK_COLUMNS = [39, 57];
const TAGLINE = 'A terminal runner for kyo-ui, in Scala 3';
const NAME = 'tui4s';

// The output of scripts/logo/glyphs.py for DejaVu Sans Mono 2.37 (font
// units; y goes up from the baseline).
const FONT = { name: 'DejaVu Sans Mono Version 2.37', upem: 2048, advance: 1233, ascent: 1901, descent: 483 };
const GLYPHS = {
  '/': [[889, 1493], [1079, 1493], [293, -190], [102, -190]],
  '\\': [[293, 1493], [1079, -190], [889, -190], [102, 1493]],
  '_': [[1233, -403], [1233, -483], [0, -483], [0, -403]],
};
const LINE = FONT.ascent + FONT.descent; // the height of a row, as in a terminal
const MARGIN = 400; // the margin of the SVG files, in font units (about 0.2 em)
const NOMINAL = 16 / FONT.upem; // the width and height attributes: 16 px for each em
const LINE_PX = 0.8; // the width of the stroke that does not scale, in CSS px
// The stroke of an icon of `size` px: thicker in a large icon.
const iconLine = (size) => Math.max(LINE_PX, Math.round((size / 160) * 10) / 10);

// The PNG files: [file, size, kind]. "favicon": a rounded square; "full": a
// full square (the platform cuts it); "maskable": the mark in the safe circle
// (a diameter of 80 percent).
const ICONS = [
  ['favicon-16x16.png', 16, 'favicon'],
  ['favicon-32x32.png', 32, 'favicon'],
  ['apple-touch-icon.png', 180, 'full'],
  ['icon-192.png', 192, 'maskable'],
  ['icon-512.png', 512, 'maskable'],
];
const ICO_SIZES = [16, 32, 48];
const OG = { file: 'og-image.png', width: 1200, height: 630 };
// The height of the mark as a part of the icon. For "maskable", the half
// diagonal of the box of the mark stays under 40 percent of the side.
const MARK_PART = { favicon: 0.84, full: 0.66, maskable: 0.58 };

const at = (p) => join(root, p);

function readLines(path) {
  const text = readFileSync(at(path), 'utf8');
  const lines = text.split('\n');
  if (lines.at(-1) === '') lines.pop();
  return lines;
}

// The "4": the columns ISOMARK_COLUMNS of each line, with no blank rows at the
// bottom and no blank columns on the left that all rows share.
function isomarkLines(lines) {
  const [from, to] = ISOMARK_COLUMNS;
  const cut = lines.map((l) => l.slice(from, to).padEnd(to - from));
  while (cut.length > 0 && cut.at(-1).trim() === '') cut.pop();
  const lead = Math.min(...cut.filter((l) => l.trim() !== '').map((l) => l.length - l.trimStart().length));
  return cut.map((l) => l.slice(lead));
}

// The polygons of a text grid, in font units, with y going down. Each
// polygon has the colour that `colorOf(column)` gives.
function place(lines, colorOf) {
  const shapes = [];
  lines.forEach((line, row) => {
    [...line].forEach((ch, col) => {
      if (ch === ' ') return;
      const glyph = GLYPHS[ch];
      if (!glyph) throw new Error(`logo: the character ${JSON.stringify(ch)} (line ${row + 1}) has no glyph in GLYPHS`);
      const points = glyph.map(([x, y]) => [col * FONT.advance + x, row * LINE + FONT.ascent - y]);
      shapes.push({ color: colorOf(col), points });
    });
  });
  return shapes;
}

function bounds(shapes) {
  const xs = shapes.flatMap((s) => s.points.map((p) => p[0]));
  const ys = shapes.flatMap((s) => s.points.map((p) => p[1]));
  const box = { x: Math.min(...xs), y: Math.min(...ys) };
  return { ...box, w: Math.max(...xs) - box.x, h: Math.max(...ys) - box.y };
}

// One path for each colour, moved by (dx, dy): one path has no seams between
// the glyphs that touch.
function paths(shapes, dx, dy, line) {
  const byColor = new Map();
  for (const s of shapes) {
    const d = 'M' + s.points.map(([x, y]) => `${x + dx} ${y + dy}`).join('L') + 'Z';
    byColor.set(s.color, (byColor.get(s.color) ?? '') + d);
  }
  return [...byColor].map(
    ([color, d]) =>
      `<path fill="${color}" stroke="${color}" stroke-width="${line}" stroke-linejoin="round" vector-effect="non-scaling-stroke" d="${d}"/>`,
  );
}

const GENERATED = `<!-- Generated by scripts/logo/build.mjs from ${SOURCE}. Do not edit. -->`;

// An SVG file: the shapes in a box of (w, h) font units, moved by (dx, dy).
function svgFile(shapes, w, h, dx, dy, extra = '', line = LINE_PX) {
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${Math.round(w * NOMINAL)}" height="${Math.round(h * NOMINAL)}" viewBox="0 0 ${w} ${h}" role="img" aria-label="${NAME}">`,
    GENERATED,
    `<title>${NAME}</title>`,
    ...(extra ? [extra] : []),
    ...paths(shapes, dx, dy, line),
    '</svg>',
    '',
  ].join('\n');
}

function logotype(lines, ink) {
  const [from, to] = ISOMARK_COLUMNS;
  const shapes = place(lines, (col) => (col >= from && col < to ? COLORS.accent : ink));
  const b = bounds(shapes);
  return svgFile(shapes, b.w + 2 * MARGIN, b.h + 2 * MARGIN, MARGIN - b.x, MARGIN - b.y);
}

function isomarkShapes(lines) {
  return place(isomarkLines(lines), () => COLORS.accent);
}

// The isomark in a square, at the centre.
function isomarkSvg(lines) {
  const shapes = isomarkShapes(lines);
  const b = bounds(shapes);
  const side = Math.max(b.w, b.h) + 2 * MARGIN;
  return svgFile(shapes, side, side, Math.round((side - b.w) / 2 - b.x), Math.round((side - b.h) / 2 - b.y));
}

// An icon of `size` px: the isomark on the brand colour, the height of the
// mark MARK_PART[kind] of the side.
function iconSvg(lines, size, kind) {
  const shapes = isomarkShapes(lines);
  const b = bounds(shapes);
  const side = Math.round(b.h / MARK_PART[kind]);
  const radius = kind === 'favicon' ? Math.round(side * 0.2) : 0;
  const background = `<rect width="${side}" height="${side}" rx="${radius}" fill="${COLORS.brand}"/>`;
  const dx = Math.round((side - b.w) / 2 - b.x);
  const dy = Math.round((side - b.h) / 2 - b.y);
  const svg = svgFile(shapes, side, side, dx, dy, background, iconLine(size));
  return svg.replace(/width="\d+" height="\d+"/, `width="${size}" height="${size}"`);
}

function manifest() {
  const icons = [
    { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
    { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
    { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
  ];
  const body = {
    name: NAME,
    short_name: NAME,
    description: TAGLINE,
    start_url: '/',
    display: 'browser',
    icons,
    theme_color: COLORS.brand,
    background_color: COLORS.brand,
  };
  return JSON.stringify(body, null, 2) + '\n';
}

// README.md with the block under LOGO_MARKER set to the source lines.
function readmeWith(lines) {
  const readme = readFileSync(at(README), 'utf8').split('\n');
  const start = readme.indexOf(LOGO_MARKER);
  if (start < 0 || readme[start + 1] !== '```') throw new Error(`logo: ${README} has no line "${LOGO_MARKER}" directly above a fence`);
  const end = readme.indexOf('```', start + 2);
  if (end < 0) throw new Error(`logo: the logo block of ${README} has no closing fence`);
  return [...readme.slice(0, start + 2), ...lines, ...readme.slice(end)].join('\n');
}

// The text outputs: [path, content].
function textOutputs(lines) {
  return [
    [ISOMARK_TXT, isomarkLines(lines).join('\n') + '\n'],
    [README, readmeWith(lines)],
    [`${PUBLIC}/logo.svg`, logotype(lines, COLORS.inkLight)],
    [`${PUBLIC}/logo-light.svg`, logotype(lines, COLORS.inkLight)],
    [`${PUBLIC}/logo-dark.svg`, logotype(lines, COLORS.inkDark)],
    [`${PUBLIC}/isomark.svg`, isomarkSvg(lines)],
    [`${PUBLIC}/site.webmanifest`, manifest()],
  ];
}

// The binary outputs: [path, [[width, height], ...]] (an ICO file has one
// size for each entry).
const binaryOutputs = [
  ...ICONS.map(([file, size]) => [`${PUBLIC}/${file}`, [[size, size]]]),
  [`${PUBLIC}/${OG.file}`, [[OG.width, OG.height]]],
  [`${PUBLIC}/favicon.ico`, ICO_SIZES.map((s) => [s, s])],
];

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

// The width and height of a PNG image: the IHDR chunk follows the signature.
function pngSize(bytes) {
  if (bytes.length < 24 || !bytes.subarray(0, 8).equals(PNG_SIGNATURE) || bytes.toString('latin1', 12, 16) !== 'IHDR') return null;
  return [bytes.readUInt32BE(16), bytes.readUInt32BE(20)];
}

// The sizes of the images of a file: one for a PNG file, one for each entry
// of an ICO file (each entry must be a PNG image).
function imageSizes(bytes) {
  const png = pngSize(bytes);
  if (png) return [png];
  if (bytes.length < 6 || bytes.readUInt16LE(0) !== 0 || bytes.readUInt16LE(2) !== 1) return null;
  const sizes = [];
  for (let i = 0; i < bytes.readUInt16LE(4); i++) {
    const entry = 6 + 16 * i;
    const length = bytes.readUInt32LE(entry + 8);
    const offset = bytes.readUInt32LE(entry + 12);
    const size = pngSize(bytes.subarray(offset, offset + length));
    if (!size) return null;
    sizes.push(size);
  }
  return sizes;
}

// An ICO file whose entries are PNG images: ICONDIR, one ICONDIRENTRY for
// each image, then the PNG bytes.
function ico(pngs) {
  const head = Buffer.alloc(6 + 16 * pngs.length);
  head.writeUInt16LE(0, 0); // reserved
  head.writeUInt16LE(1, 2); // type 1: icon
  head.writeUInt16LE(pngs.length, 4);
  let offset = head.length;
  pngs.forEach((png, i) => {
    const [w, h] = pngSize(png);
    const entry = 6 + 16 * i;
    head.writeUInt8(w >= 256 ? 0 : w, entry);
    head.writeUInt8(h >= 256 ? 0 : h, entry + 1);
    head.writeUInt8(0, entry + 2); // no palette
    head.writeUInt8(0, entry + 3); // reserved
    head.writeUInt16LE(1, entry + 4); // colour planes
    head.writeUInt16LE(32, entry + 6); // bits for each pixel
    head.writeUInt32LE(png.length, entry + 8);
    head.writeUInt32LE(offset, entry + 12);
    offset += png.length;
  });
  return Buffer.concat([head, ...pngs]);
}

function check(lines) {
  const problems = [];
  for (const [path, content] of textOutputs(lines)) {
    if (!existsSync(at(path))) problems.push(`${path} is missing`);
    else if (readFileSync(at(path), 'utf8') !== content) problems.push(`${path} is not the output for ${SOURCE}`);
  }
  for (const [path, expected] of binaryOutputs) {
    if (!existsSync(at(path))) {
      problems.push(`${path} is missing`);
      continue;
    }
    const sizes = imageSizes(readFileSync(at(path)));
    const show = (s) => (s ? s.map(([w, h]) => `${w}x${h}`).join(', ') : 'not a PNG or ICO image');
    if (show(sizes) !== show(expected)) problems.push(`${path}: ${show(sizes)}, not ${show(expected)}`);
  }
  if (problems.length > 0) {
    for (const p of problems) console.error(`logo: ${p}`);
    console.error('logo: run node scripts/logo/build.mjs and commit the outputs');
    process.exit(1);
  }
  console.log(`logo: the assets match ${SOURCE}`);
}

const require = createRequire(import.meta.url);
function playwright() {
  try {
    return require('playwright');
  } catch {
    const npmRoot = execSync('npm root -g').toString().trim();
    return require(join(npmRoot, 'playwright'));
  }
}

const page0 = (body, background) =>
  `<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;padding:0;background:${background};overflow:hidden}svg{display:block}</style></head><body>${body}</body></html>`;

function ogHtml(lines) {
  const svg = logotype(lines, COLORS.inkDark).replace(/width="\d+" height="\d+"/, 'width="1040" height="100%"');
  const body = `<div style="width:${OG.width}px;height:${OG.height}px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:44px">
<div style="height:290px;display:flex;align-items:center">${svg}</div>
<div style="font-family:'DejaVu Sans Mono',Menlo,Consolas,'Liberation Mono',monospace;font-size:34px;line-height:1;letter-spacing:0;color:${COLORS.inkDark};white-space:pre">${TAGLINE}</div>
</div>`;
  return page0(body, COLORS.brand);
}

async function build(lines) {
  for (const [path, content] of textOutputs(lines)) writeFileSync(at(path), content);

  const { chromium } = playwright();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ deviceScaleFactor: 1 });
  const page = await context.newPage();
  async function shot(html, width, height, transparent) {
    await page.setViewportSize({ width, height });
    await page.setContent(html, { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);
    return page.screenshot({ type: 'png', omitBackground: transparent, clip: { x: 0, y: 0, width, height } });
  }
  try {
    for (const [file, size, kind] of ICONS) {
      // Only the favicons have transparent corners.
      const transparent = kind === 'favicon';
      const html = page0(iconSvg(lines, size, kind), transparent ? 'transparent' : COLORS.brand);
      writeFileSync(at(`${PUBLIC}/${file}`), await shot(html, size, size, transparent));
    }
    const pngs = [];
    for (const size of ICO_SIZES) pngs.push(await shot(page0(iconSvg(lines, size, 'favicon'), 'transparent'), size, size, true));
    writeFileSync(at(`${PUBLIC}/favicon.ico`), ico(pngs));
    writeFileSync(at(`${PUBLIC}/${OG.file}`), await shot(ogHtml(lines), OG.width, OG.height, false));
  } finally {
    await browser.close();
  }

  for (const [path] of textOutputs(lines)) console.log(`${path}: ${statSync(at(path)).size} bytes`);
  for (const [path] of binaryOutputs) {
    const sizes = imageSizes(readFileSync(at(path)));
    console.log(`${path}: ${statSync(at(path)).size} bytes, ${sizes.map(([w, h]) => `${w}x${h}`).join(', ')}`);
  }
}

const lines = readLines(SOURCE);
if (process.argv.includes('--check')) check(lines);
else await build(lines);
