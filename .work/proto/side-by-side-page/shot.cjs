const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim() + '/playwright');
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const errs = [];
  for (const [name, w, h, scheme] of [['desk-light', 1440, 1100, 'light'], ['phone-dark', 400, 900, 'dark']]) {
    const p = await b.newPage({ viewport: { width: w, height: h }, colorScheme: scheme });
    p.on('console', m => { if (m.type() === 'error') errs.push(name + ': ' + m.text()); });
    p.on('pageerror', e => errs.push(name + ' pageerror: ' + e.message));
    const html = require('fs').readFileSync('tui4s-side-by-side.html', 'utf8');
    await p.setContent('<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>' + html + '</body></html>', { waitUntil: 'networkidle' });
    await p.waitForTimeout(800);
    const ov = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    console.log(name, 'hljs:', await p.evaluate(() => !!window.hljs), 'marked:', await p.evaluate(() => !!window.marked), 'h-overflow px:', ov, 'cols:', await p.evaluate(() => document.querySelectorAll('.col').length));
    await p.screenshot({ path: name + '.png' });
  }
  console.log('errors:', errs);
  await b.close();
})();
