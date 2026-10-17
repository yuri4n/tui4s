// Line protocol over stdin/stdout. Each command line is JSON; each answer is one line "RESULT <json>".
const { chromium } = require('playwright');
const readline = require('readline');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 900, height: 700 } });
  const pages = {};
  const consoleLines = [];
  const reply = (o) => process.stdout.write('RESULT ' + JSON.stringify(o) + '\n');
  const state = async (p) => p.evaluate(() => {
    const c = document.querySelector('#count');
    const d = document.querySelector('#draft');
    return {
      count: c ? c.textContent : null,
      messages: Array.from(document.querySelectorAll('#messages li')).map((li) => li.textContent),
      draft: d ? d.value : null,
    };
  });
  const rl = readline.createInterface({ input: process.stdin });
  for await (const line of rl) {
    let cmd;
    try { cmd = JSON.parse(line); } catch (e) { reply({ ok: false, error: 'bad json' }); continue; }
    const t0 = Date.now();
    try {
      if (cmd.op === 'open') {
        const p = await context.newPage();
        p.on('console', (m) => consoleLines.push(`${cmd.name}: ${m.type()}: ${m.text()}`));
        p.on('pageerror', (e) => consoleLines.push(`${cmd.name}: pageerror: ${e}`));
        const res = await p.goto(cmd.url, { waitUntil: 'load' });
        await p.waitForSelector('#count', { timeout: 10000 });
        pages[cmd.name] = p;
        reply({ ok: true, status: res.status(), ms: Date.now() - t0, state: await state(p) });
      } else if (cmd.op === 'click') {
        const p = pages[cmd.name];
        for (let i = 0; i < cmd.n; i++) { await p.click(cmd.selector); await p.waitForTimeout(cmd.gap || 150); }
        reply({ ok: true, ms: Date.now() - t0, state: await state(p) });
      } else if (cmd.op === 'type') {
        const p = pages[cmd.name];
        await p.click(cmd.selector);
        await p.keyboard.type(cmd.text, { delay: 20 });
        await p.waitForTimeout(200);
        await p.keyboard.press('Enter');
        reply({ ok: true, ms: Date.now() - t0, state: await state(p) });
      } else if (cmd.op === 'waitFor') {
        // wait until the JS predicate over state is true
        const p = pages[cmd.name];
        const deadline = Date.now() + (cmd.timeout || 5000);
        let s = await state(p);
        const pred = new Function('s', 'return ' + cmd.pred);
        while (!pred(s) && Date.now() < deadline) { await p.waitForTimeout(50); s = await state(p); }
        reply({ ok: pred(s), ms: Date.now() - t0, state: s });
      } else if (cmd.op === 'state') {
        reply({ ok: true, state: await state(pages[cmd.name]) });
      } else if (cmd.op === 'screenshot') {
        await pages[cmd.name].screenshot({ path: cmd.path, fullPage: true });
        reply({ ok: true });
      } else if (cmd.op === 'html') {
        const html = await pages[cmd.name].content();
        require('fs').writeFileSync(cmd.path, html);
        reply({ ok: true, bytes: html.length });
      } else if (cmd.op === 'computed') {
        const p = pages[cmd.name];
        const r = await p.evaluate((sels) => sels.map((s) => {
          const e = document.querySelector(s);
          if (!e) return [s, null];
          const cs = getComputedStyle(e);
          return [s, { display: cs.display, font: cs.fontFamily, size: cs.fontSize, color: cs.color, bg: cs.backgroundColor, border: cs.border, width: e.getBoundingClientRect().width }];
        }), cmd.selectors);
        reply({ ok: true, computed: r });
      } else if (cmd.op === 'console') {
        reply({ ok: true, lines: consoleLines });
      } else if (cmd.op === 'quit') {
        reply({ ok: true });
        await browser.close();
        process.exit(0);
      } else {
        reply({ ok: false, error: 'unknown op ' + cmd.op });
      }
    } catch (e) {
      reply({ ok: false, error: String(e), ms: Date.now() - t0 });
    }
  }
})();
