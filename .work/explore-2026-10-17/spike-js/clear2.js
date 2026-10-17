// SPIKE probe: the second submit. Message 1 by typing, wait until cleared, then fill + Enter (gap ms); is the field cleared?
const { chromium } = require('playwright');
const [url] = process.argv.slice(2);
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  const res = [];
  const draft = () => page.$eval('#draft', (d) => d.value);
  for (const mode of ['fill', 'type10', 'type50']) for (const gap of [0, 50, 200]) for (let r = 0; r < 4; r++) {
    await page.goto(url, { waitUntil: 'load' }); await page.waitForSelector('#count', { timeout: 20000 });
    await page.click('#draft'); await page.keyboard.type('one', { delay: 50 }); await page.keyboard.press('Enter');
    const end = Date.now() + 3000; while ((await draft()) !== '' && Date.now() < end) await page.waitForTimeout(20);
    if (mode === 'fill') await page.fill('#draft', 'second');
    else await page.keyboard.type('second', { delay: mode === 'type10' ? 10 : 50 });
    if (gap) await page.waitForTimeout(gap);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
    const msgs = await page.$$eval('#messages li', (l) => l.map((x) => x.textContent));
    res.push({ mode, gap, r, value: await draft(), msgs });
  }
  await browser.close();
  console.log(JSON.stringify(res));
})().catch((e) => { console.error('PROBE FAILED', e); process.exit(2); });
