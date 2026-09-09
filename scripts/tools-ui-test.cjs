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
    await page.route('**/pet-loader.js*',route=>route.fulfill({contentType:'application/javascript',body:''}));
    await page.route('**/api/session', route => route.fulfill({ json: { authenticated: true, user: {id:'test-user-1',role: 'admin' } } }));
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
    await page.getByRole('button',{name:'收藏：出价监测',exact:true}).click();
    await page.locator('#favoriteToolsOnly').click();assert.equal(await page.locator('.tool:visible').count(),1);
    assert.equal(await page.locator('.tool:visible h3').textContent(),'出价监测');
    await page.reload();await page.getByRole('button',{name:'取消收藏：出价监测',exact:true}).waitFor();
    await page.locator('#favoriteToolsOnly').click();assert.equal(await page.locator('.tool:visible').count(),1);
    await page.locator('#resetToolFilters').click();assert.equal(await page.locator('.tool:visible').count(),9);

    await page.locator('#toolSearch').fill('出价');
    assert.equal(await page.locator('.tool:visible').count(), 1);
    assert.equal(await page.locator('.tool:visible h3').textContent(), '出价监测');

    await page.locator('#toolSearch').fill('');
    await page.locator('#toolCategory').selectOption('automation');
    assert.equal(await page.locator('.tool:visible').count(), 2);
    await page.waitForFunction(()=>document.getElementById('toolResultCount').textContent==='显示 2 / 9 项工具');
    await page.locator('#resetToolFilters').click();
    assert.equal(await page.locator('.tool:visible').count(),9);
    await page.locator('#addTool').click();await page.locator('#toolName').fill('演示工具');await page.locator('#toolUrl').fill('/tools');
    await page.locator('#toolForm button[type=submit]').click();
    await page.locator('#toolCategory').selectOption('custom');assert.equal(await page.locator('.tool:visible').count(),1);
    await page.getByRole('button',{name:'收藏：演示工具',exact:true}).click();
    await page.locator('#customTools .delete').click();assert.equal(await page.locator('#emptyTools').isVisible(),true);
    await page.locator('#resetToolFilters').click();
    fs.mkdirSync(path.resolve(__dirname,'../.runtime'),{recursive:true});
    await page.locator('#toolSearch').blur();
    await page.screenshot({path:path.resolve(__dirname,'../.runtime/tools-polished-desktop.png'),fullPage:true});

    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({path:path.resolve(__dirname,'../.runtime/tools-polished-mobile.png'),fullPage:true});
    await page.route('**/api/tool-visibility',route=>route.fulfill({json:{bidMonitor:true}}));
    await page.reload();await page.waitForFunction(()=>document.getElementById('toolResultCount')?.textContent==='显示 1 / 1 项工具');
    assert.equal(await page.locator('.tool:visible').count(),1);
    await page.route('**/api/tool-visibility',route=>route.fulfill({json:{todo:true}}));
    await page.reload();await page.waitForFunction(()=>document.getElementById('toolResultCount')?.textContent==='显示 1 / 1 项工具');
    await page.locator('#favoriteToolsOnly').click();assert.equal(await page.locator('.tool:visible').count(),0);
    await page.route('**/api/session',route=>route.fulfill({json:{authenticated:true,user:{id:'test-user-2',role:'member'}}}));
    await page.reload();await page.waitForFunction(()=>document.getElementById('favoriteToolsOnly')?.textContent==='只看收藏（0）');
    await page.getByRole('button',{name:'收藏：Todo 任务',exact:true}).click();
    assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('marketing-tool-favorites-v1:test-user-1')).includes('builtin:todo')),false);
    await page.locator('.uc-more-filters').click();
    const filters=page.locator('#unifiedControlsDialog');
    await filters.locator('[data-original="toolSearch"]').fill('无匹配工具');
    await filters.locator('.uc-cancel').click();assert.equal(await page.locator('#toolSearch').inputValue(),'');
    await page.locator('.uc-more-filters').click();
    await filters.locator('[data-original="toolSearch"]').fill('无匹配工具');await filters.locator('.uc-apply').click();
    assert.equal(await page.locator('.tool:visible').count(),0);
    await page.locator('.uc-more-filters').click();await filters.locator('.uc-reset').click();await filters.locator('.uc-apply').click();
    assert.equal(await page.locator('.tool:visible').count(),1);
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
