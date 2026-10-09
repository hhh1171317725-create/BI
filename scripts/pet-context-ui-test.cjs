const {chromium} = require('playwright');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../frontend');
const summary = {'消耗':100,'现金消耗':90,'预估佣金':120,'现金利润':30,'现金ROI':1.33,'预估ROI':1.2,'实际ROI':1.1};
function report(filters) {
  return {
    rows:1, range:[filters.start, filters.end], summary,
    excludeUnknownOptimizer:filters.excludeUnknownOptimizer ?? true,
    by_optimizer:[], by_project:[], by_task:[], by_account:[], by_date:[],
    by_media:[], by_promoter:[], alerts:{items:[]},
  };
}

(async () => {
  const server = http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    const file = path.resolve(root, '.' + ({'/':'/index.html','/jd':'/jd.html'}[pathname] || pathname));
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404).end(); return;
    }
    res.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : file.endsWith('.png') ? 'image/png' : 'text/html;charset=utf-8');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel:'chrome', headless:true});
    for (const pathname of ['/', '/jd']) {
      const page = await browser.newPage({viewport:{width:1440,height:1000}});
      const errors = [], queries = [], chats = [];
      let queryMode = 'hold', release;
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url()).pathname;
        if (url === '/api/session') return route.fulfill({json:{authenticated:true,user:{role:'member'}}});
        if (url === '/api/pet/config') return route.fulfill({json:{configured:false,canManage:false}});
        if (url === '/api/pet/chat') {
          const body = route.request().postDataJSON();
          chats.push(body);
          return route.fulfill({json:{mode:'local',reply:'当前报表分析',scope:body.context.accountId || '全部账户'}});
        }
        if (url.endsWith('/analyze')) {
          const filters = route.request().postDataJSON();
          queries.push(filters);
          if (queryMode === 'hold') await new Promise(resolve => { release = resolve; });
          if (queryMode === 'error') return route.fulfill({status:502,json:{error:'模拟查询失败'}});
          return route.fulfill({json:report(filters)});
        }
        return route.fulfill({json:{}});
      });

      const context = () => page.evaluate(() => window.getPetReportContext());
      const apply = async () => {
        await page.locator('#apply').click();
        await page.waitForFunction(() => !document.querySelector('#apply').disabled);
      };
      const ask = async message => {
        const count = chats.length;
        await page.locator('.data-pet-input').fill(message);
        await page.locator('.data-pet-send').click();
        await page.waitForFunction(() => !document.querySelector('.data-pet-send').disabled);
        assert.equal(chats.length, count + 1, `${pathname}: question must use the real assistant request`);
        return chats.at(-1).context;
      };

      await page.goto(`http://127.0.0.1:${server.address().port}${pathname}`, {waitUntil:'domcontentloaded'});
      await page.waitForFunction(() => typeof window.getPetReportContext === 'function');
      await page.locator('.daily-query-state[data-state="loading"]').waitFor();
      const initial = await context();
      assert.equal(initial.loaded, false, `${pathname}: initial request is not a loaded report`);
      assert.deepEqual(initial.range, ['-','-']);
      assert.equal(initial.accountId, '');
      assert.deepEqual(initial.summary, {});
      await page.locator('#accountId').fill('draft-before-load');
      assert.equal((await context()).accountId, '', `${pathname}: initial account draft is not applied`);
      queryMode = 'success'; release();
      await page.waitForFunction(() => window.getPetReportContext().loaded);
      await page.waitForFunction(() => !document.querySelector('#apply').disabled);
      const loadedRange = queries[0] && [queries[0].start, queries[0].end];
      assert.equal((await context()).accountId, '', `${pathname}: successful initial query retains its captured filters`);
      assert.deepEqual((await context()).range, loadedRange);

      await page.locator('.data-pet-toggle').waitFor();
      await page.locator('.data-pet-toggle').focus();
      await page.keyboard.press('Enter');
      const sent = await ask('总结当前已加载报表');
      assert.equal(sent.accountId, '');
      assert.equal('summary' in sent, false, 'daily request does not resend authoritative server metrics');
      assert.equal('topOptimizers' in sent, false, 'daily request only sends applied scope');

      await page.locator('#accountId').fill('123');
      await apply();
      assert.equal((await context()).accountId, '123');
      assert.equal((await ask('分析账户123')).accountId, '123');

      await page.locator('#accountId').fill('456');
      await page.locator('#start').fill('2026-09-01');
      await page.locator('#end').fill('2026-09-07');
      const draft = await ask('分析当前页面范围');
      assert.equal(draft.accountId, '123', `${pathname}: pending account edits do not change AI scope`);
      assert.deepEqual(draft.range, loadedRange, `${pathname}: pending dates do not change AI scope`);

      await apply();
      const applied = await ask('分析已应用的新范围');
      assert.equal(applied.accountId, '456');
      assert.deepEqual(applied.range, ['2026-09-01','2026-09-07']);

      await page.locator('#accountId').fill('789');
      queryMode = 'hold';
      await page.locator('#apply').click();
      await page.locator('.daily-query-state[data-state="loading"]').waitFor();
      assert.equal((await context()).accountId, '456', `${pathname}: in-flight query retains displayed scope`);
      queryMode = 'error'; release();
      await page.locator('.daily-query-state[data-state="error"]').waitFor();
      await page.waitForFunction(() => !document.querySelector('#apply').disabled);
      const afterFailure = await ask('查询失败后分析保留的报表');
      assert.equal(afterFailure.loaded, true);
      assert.equal(afterFailure.accountId, '456', `${pathname}: failed query cannot become applied scope`);
      assert.deepEqual(afterFailure.range, applied.range);

      await page.locator('#accountId').fill('');
      assert.equal((await context()).accountId, '456', `${pathname}: clearing draft alone retains applied scope`);
      queryMode = 'success';
      await apply();
      assert.equal(queries.at(-1).accountId, '');
      assert.equal((await ask('分析清空账户后的全部报表')).accountId, '');
      assert.deepEqual(errors, [], `${pathname}: real report and assistant scripts must have no page errors`);
      await page.close();
    }
    console.log('PASS: DHH/JD assistant context follows successful applied filters through initial loading, drafts, application, failure and account clearing');
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
