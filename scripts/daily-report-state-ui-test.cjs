const {chromium} = require('playwright');
const fs = require('node:fs'), http = require('node:http'), path = require('node:path'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../frontend');
const summary = {'消耗':100,'现金消耗':90,'预估佣金':120,'现金利润':30,'ROI':1.2,'现金ROI':1.33};
function result(filters, empty = false) {
  return {rows:empty ? 0 : 1, range:[filters.start, filters.end], summary:empty ? Object.fromEntries(Object.keys(summary).map(key => [key,0])) : summary, excludeUnknownOptimizer:true,
    by_optimizer:empty ? [] : [{优化师:'示例优化师', ...summary}], by_project:[], by_task:[], by_account:[], by_date:[], alerts:{items:[]}};
}
(async () => {
  fs.mkdirSync(path.resolve(__dirname, '../.runtime'), {recursive:true});
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost').pathname;
    const file = path.resolve(root, '.' + ({'/':'/index.html','/jd':'/jd.html'}[url] || url));
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) return res.writeHead(404).end();
    res.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html;charset=utf-8');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel:'chrome', headless:true});
    for (const pathname of ['/', '/jd']) {
      const page = await browser.newPage({viewport:{width:1440,height:1000}});
      const errors = [];
      let mode = 'error', count = 0, release;
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/pet-loader.js*', route => route.fulfill({body:''}));
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url()).pathname;
        if (url === '/api/session') return route.fulfill({json:{authenticated:true,user:{role:'member'}}});
        if (!url.endsWith('/analyze')) return route.fulfill({json:{}});
        count++;
        const filters = route.request().postDataJSON();
        if (mode === 'slow') await new Promise(resolve => { release = resolve; });
        if (mode === 'error') return route.fulfill({status:502,contentType:'text/html',body:'upstream unavailable'});
        return route.fulfill({json:result(filters, mode === 'empty')});
      });
      await page.goto(`http://127.0.0.1:${server.address().port}${pathname}`);
      await page.locator('.daily-query-state[data-state="error"]').waitFor();
      assert.equal(await page.locator('#filterPanel').isVisible(), true);
      assert.equal(await page.locator('#content').isVisible(), false);
      assert.match(await page.locator('.daily-query-state').innerText(), /HTTP 502/);
      mode = 'success';
      await page.locator('.daily-query-state button').click();
      await page.locator('#content:not(.hidden)').waitFor();
      await page.locator('.daily-query-state[data-state="ready"]').waitFor();
      const oldRows = await page.locator('#table').innerText();
      await page.locator('#start').fill('2026-09-01');
      await page.locator('#end').fill('2026-09-07');
      await page.locator('.daily-query-state[data-state="pending"]').waitFor();
      mode = 'slow';
      await page.locator('#apply').click();
      await page.locator('.daily-query-state[data-state="loading"]').waitFor();
      assert.equal(await page.locator('#table').innerText(), oldRows);
      assert.equal(await page.locator('#content').getAttribute('aria-busy'), 'true');
      const queryCount = count;
      await page.locator('#accountId').press('Enter');
      await page.locator('#accountId').press('Enter');
      await page.waitForTimeout(100);
      assert.equal(count, queryCount, 'Enter while busy must not issue duplicate requests');
      mode = 'error'; release();
      await page.locator('.daily-query-state[data-state="error"]').waitFor();
      assert.equal(await page.locator('#table').innerText(), oldRows);
      assert.match(await page.locator('.daily-query-state').innerText(), /仍保留上次结果/);
      mode = 'success';
      await page.locator('.daily-query-state button').click();
      await page.locator('.daily-query-state[data-state="ready"]').waitFor();
      assert.match(await page.locator('.daily-query-state').innerText(), /2026-09-01 至 2026-09-07/);
      mode = 'empty';
      await page.locator('#accountId').fill('123');
      await page.locator('#apply').click();
      await page.locator('.daily-query-state[data-state="empty"]').waitFor();
      assert.match(await page.locator('.daily-query-state').innerText(), /暂无数据/);
      if (pathname === '/jd') {
        mode = 'success';
        await page.locator('#reset').click();
        await page.locator('.daily-query-state[data-state="ready"]').waitFor();
      }
      // Exercise the real abort path without waiting 45 seconds in the test.
      await page.evaluate(() => {
        const original = window.setTimeout;
        window.setTimeout = (fn, ms, ...args) => original(fn, ms === 45000 ? 80 : ms, ...args);
      });
      mode = 'slow';
      await page.locator('#apply').click();
      await page.locator('.daily-query-state[data-state="error"]').waitFor();
      assert.match(await page.locator('.daily-query-state').innerText(), /45 秒/);
      assert.equal(await page.locator('#apply').isEnabled(), true);
      release();
      await page.setViewportSize({width:390,height:844});
      await page.screenshot({path:path.resolve(__dirname, `../.runtime/${pathname === '/' ? 'dhh' : 'jd'}-query-state-mobile.png`),fullPage:true});
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      assert.deepEqual(errors, []);
      await page.close();
    }
    console.log('PASS: DHH/JD member first-load failure, retry, pending filters, stale results, duplicate prevention, empty results, reset, timeout recovery and mobile layout');
  } finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => {console.error(error);process.exitCode = 1;});
