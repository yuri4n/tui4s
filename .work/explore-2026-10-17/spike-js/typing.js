// SPIKE probe: lost keystrokes in a SignalRef-bound input, by typing delay; click latency; clear-after-submit race.
const { chromium } = require('playwright');
const [url] = process.argv.slice(2);
const TEXT = 'the quick brown fox';
(async () => {
  const browser = await chromium.launch({ headless: true });
  const out = { url, typing: [], clickLatencyMs: [], clearRace: [] };
  const page = await browser.newPage();
  const load = async () => { await page.goto(url, { waitUntil: 'load' }); await page.waitForSelector('#count', { timeout: 20000 }); };
  const draft = () => page.$eval('#draft', (d) => d.value);
  for (const delay of [0, 10, 30, 50, 80, 120]) {
    for (let r = 0; r < 3; r++) {
      await load();
      await page.click('#draft');
      await page.keyboard.type(TEXT, { delay });
      await page.waitForTimeout(400);
      const v = await draft();
      out.typing.push({ delay, run: r, ok: v === TEXT, got: v });
    }
  }
  await load();
  for (let i = 0; i < 10; i++) {
    const ms = await page.evaluate(() => new Promise((res) => {
      const c = document.querySelector('#count'); const before = c.textContent; const t0 = performance.now();
      const obs = new MutationObserver(() => { if (document.querySelector('#count').textContent !== before) { obs.disconnect(); res(performance.now() - t0); } });
      obs.observe(document.querySelector('#app'), { subtree: true, childList: true, characterData: true });
      document.querySelector('#inc').click();
    }));
    out.clickLatencyMs.push(Math.round(ms * 10) / 10);
  }
  // Clear race: one input event (fill) then Enter after a gap; is the field cleared?
  for (const gap of [0, 20, 50, 100, 200]) {
    for (let r = 0; r < 3; r++) {
      await load();
      await page.fill('#draft', 'abc');
      if (gap) await page.waitForTimeout(gap);
      await page.press('#draft', 'Enter');
      await page.waitForTimeout(400);
      const v = await draft();
      const n = await page.$$eval('#messages li', (l) => l.length);
      out.clearRace.push({ gap, run: r, cleared: v === '', value: v, added: n });
    }
  }
  await browser.close();
  console.log(JSON.stringify(out));
})().catch((e) => { console.error('PROBE FAILED', e); process.exit(2); });
