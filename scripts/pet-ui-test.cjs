const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const root = path.resolve(__dirname, '../frontend');
  const server = http.createServer((req, res) => {
    const name = new URL(req.url, 'http://localhost').pathname;
    if (name === '/') {
      res.setHeader('Content-Type', 'text/html;charset=utf-8');
      res.end('<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/pet.css"><script>window.context={reportType:"大航海日报",range:["2026-09-01","2026-09-07"],accountId:""};window.getPetReportContext=()=>window.context;</script><script src="/pet.js" defer></script>');
      return;
    }
    const file = path.join(root, name.replace(/^\//, ''));
    if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'application/javascript' : 'image/png');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel:'chrome',headless:true});
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const errors = [], requests = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/pet/config', route => route.fulfill({json:{configured:false,canManage:true,provider:'deepseek'}}));
    await page.route('**/api/pet/chat', route => {
      const body = route.request().postDataJSON(); requests.push(body);
      return route.fulfill({json:{mode:'local',notice:'AI 未配置，以下为规则分析',
        reply:'消耗 100 元，现金利润 30 元，现金 ROI 1.3 倍。\n下一步：核查注册成本与结算回传。',
        scope:'2026-09-01 至 2026-09-07 · 1 条匹配记录 · 张三',
        queryState:{range:body.context.range,conditions:{优化师:['张三']}},
        suggestions:['对比上期，哪些指标变化最大？','按利润给优化师排名','<img src=x onerror=alert(1)>']}});
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.locator('.data-pet-toggle').focus(); await page.keyboard.press('Enter');
    async function ask(message) {
      await page.locator('.data-pet-input').fill(message);
      await page.locator('.data-pet-send').click();
      await page.waitForFunction(() => !document.querySelector('.data-pet-send').disabled);
    }
    await ask('张三最近7天利润多少');
    assert.equal(requests[0].queryState, null);
    await ask('ROI呢');
    assert.deepEqual(requests[1].queryState.conditions,{优化师:['张三']});
    assert.equal(requests[1].history.length,2);
    assert.match(await page.locator('.data-pet-mode').textContent(),/规则分析/);
    assert.equal(await page.locator('.data-pet-quick img').count(),0);
    await page.evaluate(() => window.context.range=['2026-08-01','2026-08-31']);
    await ask('汇总');
    assert.equal(requests[2].queryState,null);
    assert.deepEqual(requests[2].history,[]);
    await page.getByRole('button',{name:'新对话',exact:true}).click();
    await ask('利润排名');
    assert.equal(requests[3].queryState,null);
    assert.deepEqual(requests[3].history,[]);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    const rect = await page.locator('.data-pet-panel').boundingBox();
    assert.ok(rect.x >= 0 && rect.x + rect.width <= 390);
    const runtime = path.resolve(__dirname,'../.runtime');
    fs.mkdirSync(runtime,{recursive:true});
    await page.screenshot({path:path.join(runtime,'pet-analysis-mobile.png')});
    assert.deepEqual(errors,[]);
    console.log('PASS: follow-up scope, page change reset, new conversation, truthful fallback, safe suggestions and mobile layout');
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode=1; });
