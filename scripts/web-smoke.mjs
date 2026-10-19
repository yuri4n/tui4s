// The smoke test of the assistant on the web (L4). It serves dist/assistant
// (bash scripts/web-build.sh makes it) from a static server on the loopback
// interface, opens it in headless Chromium (Playwright), and checks: the app
// mounts with no page error; "hi" and Enter send a message; the reply of the
// fake provider streams in; the composer is empty again (no line break of the
// browser stays); Shift-Enter starts a new line; Esc closes the settings; a
// key typed in the settings is not in the DOM (no value attribute, not in the
// HTML of the page); the settings switch the look (data-theme and the font of
// the page change); the page asks no host but its own (no key: no provider
// call). It exits with 1 on the first failure, after a screenshot in
// dist/web-smoke.png.
//
//   node scripts/web-smoke.mjs [dist/assistant]
//
// It needs Node.js and Playwright with Chromium. It finds the playwright
// package in node_modules, else in the global modules of npm. Set
// PLAYWRIGHT_BROWSERS_PATH where Chromium is installed.
// claude 2026-10-19: created (L4); changed: the step of the key field (L4
// review)
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { extname, join, resolve } from 'node:path';

const require = createRequire(import.meta.url);
function playwright() {
  try {
    return require('playwright');
  } catch {
    const root = execSync('npm root -g').toString().trim();
    return require(join(root, 'playwright'));
  }
}

const dir = resolve(process.argv[2] ?? 'dist/assistant');
const types = { '.html': 'text/html', '.js': 'text/javascript' };
const server = createServer(async (req, res) => {
  const path = new URL(req.url, 'http://x').pathname;
  const file = join(dir, path === '/' ? 'index.html' : path);
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
const origin = `http://127.0.0.1:${server.address().port}`;

const { chromium } = playwright();
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const errors = [];
const foreign = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('request', (r) => {
  if (!r.url().startsWith(origin)) foreign.push(r.url());
});

let step = 'start';
const check = (ok, what) => {
  if (!ok) throw new Error(what);
  console.log(`web-smoke: ok: ${what}`);
};
const theme = () => page.$eval('[data-theme]', (e) => e.getAttribute('data-theme'));
const font = () => page.$eval('[data-theme]', (e) => getComputedStyle(e).fontFamily);

try {
  step = 'load';
  const t0 = Date.now();
  await page.goto(origin, { waitUntil: 'load' });
  await page.waitForSelector('textarea', { timeout: 30000 });
  check(true, `the app mounts (${Date.now() - t0} ms)`);
  check((await theme()) === 'web', 'the first look is the native web look');
  const webFont = await font();

  step = 'send';
  await page.click('textarea');
  await page.keyboard.type('hi', { delay: 50 });
  await page.keyboard.press('Enter');
  await page.waitForFunction(
    () => document.body.innerText.includes('You said: "hi".'),
    null,
    { timeout: 20000 },
  );
  check(true, 'the reply of the fake provider streams in');
  await page.waitForFunction(
    () => /\d+ in, [1-9]\d* out/.test(document.body.innerText),
    null,
    { timeout: 20000 },
  );
  check(true, 'the reply ends: the status bar has its tokens');
  check((await page.$eval('textarea', (t) => t.value)) === '', 'the composer is empty');

  step = 'shift-enter';
  const sent = () => page.$$eval('[role=log] span', (s) => s.filter((x) => x.textContent === 'You').length);
  const before = await sent();
  await page.keyboard.type('a', { delay: 50 });
  await page.keyboard.press('Shift+Enter');
  await page.keyboard.type('b', { delay: 50 });
  await page.waitForFunction(() => document.querySelector('textarea').value === 'a\nb', null, { timeout: 5000 });
  check((await sent()) === before, 'Shift-Enter starts a new line and does not send');

  step = 'escape';
  await page.keyboard.press('Alt+,');
  await page.waitForSelector('[role=dialog]', { timeout: 10000 });
  await page.keyboard.press('Escape');
  await page.waitForSelector('[role=dialog]', { state: 'detached', timeout: 10000 });
  check(true, 'Alt-, opens the settings; Esc closes them');

  step = 'key';
  const dummy = 'sk-ant-smoke-0123456789';
  await page.keyboard.press('Alt+,');
  await page.waitForSelector('[role=dialog]', { timeout: 10000 });
  const field = page.locator('[role=dialog] input[type=password]').first();
  await field.click();
  await page.keyboard.type(dummy, { delay: 20 });
  await page.waitForTimeout(300);
  check((await field.inputValue()) === dummy, 'the key field takes the typed key');
  const attrs = await page.$$eval('input[type=password]', (xs) => xs.map((x) => x.getAttribute('value')));
  const html = await page.evaluate(() => document.documentElement.outerHTML);
  check(
    attrs.every((v) => v === null || v === '') && !html.includes(dummy),
    'the typed key is not in the DOM (no value attribute, not in the HTML)',
  );
  await page.keyboard.press('Escape');
  await page.waitForSelector('[role=dialog]', { state: 'detached', timeout: 10000 });

  step = 'look';
  await page.keyboard.press('Alt+,');
  await page.waitForSelector('[role=dialog]', { timeout: 10000 });
  const look = page.locator('[role=dialog] [role=radiogroup]').nth(1);
  await look.focus();
  await page.keyboard.press('ArrowDown');
  await page.waitForFunction(
    () => document.querySelector('[role=dialog]').innerText.includes('(•) terminal look'),
    null,
    { timeout: 10000 },
  );
  await page.click('[role=dialog] button:has-text("Save")');
  await page.waitForFunction(
    () => document.querySelector('[data-theme]')?.getAttribute('data-theme') === 'terminal',
    null,
    { timeout: 10000 },
  );
  const termFont = await font();
  check(termFont !== webFont && /monospace/.test(termFont), `the look switches (${termFont})`);

  step = 'end';
  check(errors.length === 0, `no page error ${errors.join('; ')}`);
  check(foreign.length === 0, `no request to another host ${foreign.join(' ')}`);
  console.log('web-smoke: passed');
} catch (e) {
  console.error(`web-smoke: FAILED at ${step}: ${e.message}`);
  if (errors.length) console.error(`web-smoke: page errors: ${errors.join('; ')}`);
  await page.screenshot({ path: join(dir, '..', 'web-smoke.png') }).catch(() => {});
  process.exitCode = 1;
} finally {
  await browser.close();
  server.close();
}
