const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../frontend');

(async () => {
  const server = http.createServer((request, response) => {
    const file = path.join(root, path.basename(new URL(request.url, 'http://localhost').pathname));
    if (!fs.existsSync(file)) {
      response.writeHead(404);
      response.end();
      return;
    }
    response.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html;charset=utf-8');
    response.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));

  let browser;
  try {
    browser = await chromium.launch({ headless: true, channel: 'chrome' });
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/session', route => route.fulfill({ json: { authenticated: true, user: { role: 'admin' } } }));
    await page.route('**/api/tool-visibility', route => route.fulfill({ json: {
      todo: true,
      accountVault: true,
      adpfluxHelper: true,
      mailDingtalk: true,
      chat: true,
      deeplink: true,
      deeplinkAccount: true,
      jdImages: true,
      bidMonitor: true
    } }));

    await page.goto(`http://127.0.0.1:${server.address().port}/tools.html`);
    await page.locator('html.authenticated').waitFor();
    await page.waitForFunction(() => [...document.querySelectorAll('[data-tool]')].every(card => card.dataset.allowed));
    assert.equal(await page.locator('.tool:visible').count(), 9);
    assert.equal(await page.locator('[data-tool="terminal"]').count(), 0);

    await page.locator('#toolSearch').fill('出价');
    assert.equal(await page.locator('.tool:visible').count(), 1);
    assert.equal(await page.locator('.tool:visible h3').textContent(), '出价监测');

    await page.locator('#toolSearch').fill('');
    await page.locator('#toolCategory').selectOption('automation');
    assert.equal(await page.locator('.tool:visible').count(), 2);

    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    assert.deepEqual(errors, []);
    console.log('TOOLS UI PASS: permissions, search, categories, disabled terminal entry, mobile width');
  } finally {
    await browser?.close();
    server.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
