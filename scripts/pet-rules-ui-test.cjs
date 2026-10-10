const { chromium } = require('playwright');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../frontend');
const runtime = path.resolve(__dirname, '../.runtime');
const clone = value => JSON.parse(JSON.stringify(value));
const fixture = () => ({
  version: 3, updatedAt: 1791511200000, canManage: true,
  rules: [
    { id: 'b829909a-f079-4a8a-bd8c-c8156c682801', title: '说明分析顺序', scope: 'all', enabled: true, type: 'text', dimension: 'summary', content: '先报告实际数据，再给建议。', conditions: [] },
    { id: 'b829909a-f079-4a8a-bd8c-c8156c682802', title: '关注现金利润', scope: 'jd', enabled: false, type: 'condition', dimension: 'account', content: '检查当前账户的现金利润。', conditions: [{ metric: '现金利润', operator: 'lt', value: 0 }] },
  ],
});
const allMetrics = ['消耗', '现金消耗', '预估佣金', '佣金', '现金利润', '预估利润', '实际利润', 'ROI', '现金ROI', '预估ROI', '实际ROI', '注册数', '注册成本', '转化数', '计划累计转化数', '有效订单数', '结算数', '当前出价', 'gap', '结算单价', '实际单价', '预估赔付', '预估eCPM'];

(async () => {
  const server = http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/rules-test') {
      res.setHeader('Content-Type', 'text/html;charset=utf-8');
      res.end('<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/pet-rules.css"></head><body style="margin:0;background:#f4f7fb;font-family:sans-serif"><button id="openRules" onclick="window.PetRules.open()">打开分析规则</button><script src="/pet-rules.js"></script></body></html>');
      return;
    }
    const file = path.resolve(root, '.' + pathname);
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404).end(); return; }
    res.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html;charset=utf-8');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  let browser;
  try {
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const errors = [];
    async function setup(canManage = true) {
      const page = await browser.newPage({ viewport: { width: 1440, height: 960 } });
      page.on('pageerror', error => errors.push(error.message));
      const mock = { config: fixture(), gets: 0, posts: [], failLoad: false, failSave: false, conflict: false, holdSave: false, releaseSave: null, delayLoad: 0, delaySave: 0, events: [] };
      mock.config.canManage = canManage;
      await page.addInitScript(() => {
        const originalTimeout = AbortSignal.timeout.bind(AbortSignal);
        const originalFetch = window.fetch.bind(window);
        window.rulesRequestedTimeouts = [];
        window.rulesRequestOptions = [];
        AbortSignal.timeout = milliseconds => {
          window.rulesRequestedTimeouts.push(milliseconds);
          return originalTimeout(window.rulesTestTimeoutMs || milliseconds);
        };
        window.fetch = (url, options) => {
          window.rulesRequestOptions.push({ method: options.method, cache: options.cache, credentials: options.credentials, hasSignal: options.signal instanceof AbortSignal });
          return originalFetch(url, options);
        };
      });
      await page.context().addCookies([{ name: 'bi-session', value: 'authenticated-test-session', url: base }]);
      await page.route('**/api/**', async route => {
        const request = route.request();
        const pathname = new URL(request.url()).pathname;
        assert.equal(pathname, '/api/pet/rules', 'test must not call production report or AI APIs');
        assert.match(request.headers().cookie || '', /bi-session=authenticated-test-session/, 'rules use the authenticated session cookie');
        if (request.method() === 'GET') {
          mock.gets++;
          if (mock.delayLoad) await new Promise(resolve => setTimeout(resolve, mock.delayLoad));
          return route.fulfill(mock.failLoad ? { status: 503, json: { error: '模拟加载失败，请重试' } } : { json: clone(mock.config) });
        }
        assert.equal(request.method(), 'POST');
        const body = request.postDataJSON(); mock.posts.push(body);
        assert.deepEqual(Object.keys(body).sort(), ['rules', 'version']);
        assert.equal('apiKey' in body, false);
        if (mock.holdSave) await new Promise(resolve => { mock.releaseSave = resolve; });
        if (mock.conflict) return route.fulfill({ status: 409, json: { error: '配置版本已变化' } });
        if (mock.failSave) return route.fulfill({ status: 503, json: { error: '模拟保存失败，请重试' } });
        mock.config = { ...mock.config, version: body.version + 1, updatedAt: Date.now(), rules: clone(body.rules) };
        if (mock.delaySave) await new Promise(resolve => setTimeout(resolve, mock.delaySave));
        return route.fulfill({ json: clone(mock.config) });
      });
      await page.goto(base + '/rules-test');
      await page.evaluate(() => {
        window.savedRulesEvents = [];
        window.addEventListener('pet:rules-saved', event => window.savedRulesEvents.push(event.detail));
      });
      assert.equal(mock.gets, 0, 'rules do not load when the module initializes');
      await page.addScriptTag({ url: base + '/pet-rules.js' });
      assert.equal(mock.gets, 0, 'reloading the standalone module has no network side effect');
      return { page, mock };
    }
    const loaded = page => page.waitForFunction(() => document.querySelector('.pet-rules-save-state').textContent === '已与网站同步');
    const open = async page => { await page.locator('#openRules').click(); await loaded(page); };
    const save = async page => { await page.locator('.pet-rules-save').click(); await page.locator('.pet-rules-message[data-tone="success"]').waitFor(); };

    const { page, mock } = await setup();
    await open(page);
    assert.equal(mock.gets, 1);
    assert.deepEqual(await page.evaluate(() => window.rulesRequestOptions[0]), { method: 'GET', cache: 'no-store', credentials: 'same-origin', hasSignal: true });
    assert.match(await page.locator('.pet-rules-order-hint').textContent(), /从上到下执行，冲突时前面的优先/);
    assert.equal(await page.locator('.pet-rules-dialog').count(), 1, 'module initializes once');
    assert.equal(await page.locator('.pet-rules-card').count(), 2);
    assert.equal(await page.locator('.pet-rules-save').isDisabled(), true);
    assert.equal(await page.locator('.pet-rules-notice').isVisible(), false);
    assert.deepEqual(await page.getByLabel('适用报表').locator('option').evaluateAll(options => options.map(option => [option.value, option.textContent])), [['all', '全部报表'], ['dhh', '大航海'], ['jd', '京东日报'], ['bid', '出价监测']]);

    // Natural-language rules are escaped text and keep drafts through Escape/reopen.
    await page.locator('[data-add="text"]').click();
    const textTitle = '<img src=x onerror="window.rulesInjected=true">';
    await page.getByLabel('规则名称').fill(textTitle);
    await page.getByLabel('分析指令', { exact: true }).fill('<script>window.rulesInjected=true</script>先核对现金利润。');
    await page.getByLabel('适用报表').selectOption('dhh');
    await page.getByLabel('启用规则').check();
    assert.equal(await page.locator('.pet-rules-card').last().locator('strong').textContent(), textTitle);
    assert.equal(await page.locator('.pet-rules-list img, .pet-rules-list script').count(), 0);
    assert.equal(await page.evaluate(() => window.rulesInjected), undefined);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.pet-rules-dialog').evaluate(dialog => dialog.open), false);
    assert.equal(await page.locator('#openRules').evaluate(button => document.activeElement === button), true, 'closing restores focus');
    await page.locator('#openRules').click();
    assert.equal(mock.gets, 1, 'reopening unsaved drafts does not fetch or replace them');
    assert.equal(await page.getByLabel('规则名称').inputValue(), textTitle);

    // Numerical rules cover all scopes/dimensions, AND conditions, units, order and removal.
    await page.locator('[data-add="condition"]').click();
    await page.getByLabel('规则名称').fill('出价与 gap 预警');
    await page.getByLabel('触发后的建议').fill('关注计划出价与 gap，同时核对实际转化。');
    await page.getByLabel('适用报表').selectOption('bid');
    assert.deepEqual(await page.getByLabel('匹配维度').locator('option').evaluateAll(options => options.map(option => [option.value, option.textContent])), [['summary', '汇总'], ['account', '账户'], ['task', '任务'], ['optimizer', '优化师'], ['plan', '计划']]);
    await page.getByLabel('匹配维度').selectOption('plan');
    assert.deepEqual(await page.getByLabel('条件 1 指标').locator('option').evaluateAll(options => options.map(option => option.value)), allMetrics);
    await page.getByLabel('条件 1 指标').selectOption('当前出价');
    await page.getByLabel('条件 1 比较方式').selectOption('gte');
    await page.getByLabel('条件 1 数值').fill('1.25');
    await page.locator('.pet-rules-add-condition').click();
    await page.getByLabel('条件 2 指标').selectOption('gap');
    await page.getByLabel('条件 2 比较方式').selectOption('lt');
    await page.getByLabel('条件 2 数值').fill('0.1');
    await page.getByLabel('启用规则').check();
    assert.match(await page.locator('.pet-rules-conditions').textContent(), /全部满足（AND）/);
    assert.match(await page.locator('.pet-rules-conditions').textContent(), /1 表示 1 倍/);
    assert.match(await page.locator('.pet-rules-conditions').textContent(), /0.1 表示 10%/);
    for (let index = 0; index < 3; index++) await page.locator('.pet-rules-add-condition').click();
    assert.equal(await page.locator('.pet-rules-condition').count(), 5);
    assert.equal(await page.locator('.pet-rules-add-condition').isDisabled(), true);
    for (let index = 5; index >= 3; index--) await page.getByLabel(`删除条件 ${index}`).click();
    await page.getByRole('button', { name: '上移', exact: true }).click();
    assert.equal(await page.locator('.pet-rules-card').nth(2).locator('strong').textContent(), '出价与 gap 预警');
    await page.getByRole('button', { name: '下移', exact: true }).click();
    await page.locator('.pet-rules-card').nth(1).click();
    await page.getByRole('button', { name: '删除', exact: true }).click();
    assert.equal(await page.locator('.pet-rules-card').count(), 3);
    await page.locator('.pet-rules-template').click();
    assert.equal(await page.getByLabel('规则名称').inputValue(), '高消耗低 ROI');
    assert.equal(await page.getByLabel('启用规则').isChecked(), false, 'sample is OFF until intentionally enabled');
    assert.equal(await page.getByLabel('适用报表').inputValue(), 'jd');
    assert.equal(await page.getByLabel('匹配维度').inputValue(), 'account');
    assert.equal(await page.getByLabel('条件 1 数值').inputValue(), '200');
    assert.equal(await page.getByLabel('条件 2 指标').inputValue(), '预估ROI');
    assert.equal(await page.getByLabel('条件 2 数值').inputValue(), '1');
    await save(page);
    const firstPost = mock.posts[0];
    assert.equal(firstPost.version, 3);
    assert.deepEqual(firstPost.rules.map(rule => rule.scope), ['all', 'dhh', 'bid', 'jd']);
    assert.deepEqual(firstPost.rules.map(rule => rule.type), ['text', 'text', 'condition', 'condition']);
    assert.equal(firstPost.rules[1].dimension, 'summary');
    assert.deepEqual(firstPost.rules[1].conditions, []);
    assert.deepEqual(firstPost.rules[2].conditions, [{ metric: '当前出价', operator: 'gte', value: 1.25 }, { metric: 'gap', operator: 'lt', value: 0.1 }]);
    assert.equal(firstPost.rules[3].enabled, false);
    assert.deepEqual(await page.evaluate(() => window.savedRulesEvents), [{ version: 4, enabledCount: 3 }]);

    // Saving locks the editor and submits only once; failures retain editable drafts.
    await page.getByLabel('规则名称').fill('高消耗低 ROI · 草稿');
    mock.holdSave = true;
    await page.locator('.pet-rules-save').click();
    await page.waitForFunction(() => document.querySelector('.pet-rules-save').textContent === '正在保存…');
    assert.equal(await page.getByLabel('规则名称').isDisabled(), true);
    assert.equal(await page.locator('.pet-rules-save').isDisabled(), true);
    await page.keyboard.press('Control+s');
    await page.waitForTimeout(30);
    assert.equal(mock.posts.length, 2, 'double save and keyboard shortcut cannot duplicate requests');
    mock.holdSave = false; mock.releaseSave();
    await page.locator('.pet-rules-message[data-tone="success"]').waitFor();
    await page.getByLabel('规则名称').fill('保存失败时保留草稿');
    mock.failSave = true;
    await page.locator('.pet-rules-save').click();
    await page.locator('.pet-rules-message[data-tone="error"]').waitFor();
    assert.equal(await page.getByLabel('规则名称').inputValue(), '保存失败时保留草稿');
    assert.equal(await page.locator('.pet-rules-save').isEnabled(), true);
    mock.failSave = false; await save(page);

    // Version conflicts are never silently overwritten; explicit reload discards the draft.
    await page.getByLabel('规则名称').fill('版本冲突时保留草稿');
    mock.conflict = true;
    await page.locator('.pet-rules-save').click();
    await page.locator('.pet-rules-message[data-tone="conflict"]').waitFor();
    assert.equal(await page.getByLabel('规则名称').inputValue(), '版本冲突时保留草稿');
    assert.equal(await page.locator('.pet-rules-save').isDisabled(), true);
    assert.match(await page.locator('.pet-rules-reload').textContent(), /放弃草稿/);
    await page.keyboard.press('Escape'); await page.locator('#openRules').click();
    assert.equal(await page.getByLabel('规则名称').inputValue(), '版本冲突时保留草稿');
    assert.equal(mock.gets, 1);
    mock.conflict = false; mock.config.version++;
    mock.config.rules.at(-1).title = '另一位管理员的新规则';
    await page.locator('.pet-rules-reload').click(); await loaded(page);
    assert.equal(await page.getByLabel('规则名称').inputValue(), '另一位管理员的新规则');
    assert.equal(mock.gets, 2);

    // Numeric and aggregate limits are validated before any POST.
    await page.getByLabel('条件 1 数值').fill('');
    const postsBeforeValidation = mock.posts.length;
    await page.locator('.pet-rules-save').click();
    assert.match(await page.locator('.pet-rules-message').textContent(), /有效数值/);
    assert.equal(mock.posts.length, postsBeforeValidation);
    await page.getByLabel('条件 1 数值').fill('200');
    await page.getByLabel('规则名称').fill('');
    await page.locator('.pet-rules-save').click();
    assert.match(await page.locator('.pet-rules-message').textContent(), /规则名称/);
    await page.getByLabel('规则名称').fill('另一位管理员的新规则');
    await page.getByLabel('触发后的建议').fill('移动端检查：规则根据当前数据判断。');
    fs.mkdirSync(runtime, { recursive: true });
    await page.screenshot({ path: path.join(runtime, 'pet-rules-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.pet-rules-workspace').evaluate(node => { node.scrollTop = node.scrollHeight; });
    await page.screenshot({ path: path.join(runtime, 'pet-rules-mobile.png'), fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    assert.equal(await page.locator('.pet-rules-dialog').evaluate(node => node.scrollWidth <= node.clientWidth), true, 'mobile dialog has no horizontal overflow');
    assert.equal(await page.locator('.pet-rules-detail').evaluate(node => node.scrollWidth <= node.clientWidth), true, 'mobile editor fits');
    assert.equal(await page.locator('.pet-rules-save').isVisible(), true);
    await page.close();

    const { page: readonly, mock: readonlyMock } = await setup(false);
    await open(readonly);
    assert.equal(await readonly.locator('.pet-rules-notice').isVisible(), true);
    assert.equal(await readonly.locator('[data-add="text"]').isDisabled(), true);
    assert.equal(await readonly.locator('[data-add="condition"]').isDisabled(), true);
    assert.equal(await readonly.getByLabel('规则名称').isDisabled(), true);
    await readonly.locator('.pet-rules-card').nth(1).click();
    assert.equal(await readonly.getByLabel('条件 1 数值').isDisabled(), true);
    assert.equal(await readonly.getByLabel('适用报表').isDisabled(), true);
    assert.equal(await readonly.getByRole('button', { name: '删除', exact: true }).isDisabled(), true);
    assert.equal(await readonly.locator('.pet-rules-save').isVisible(), false);
    assert.equal(readonlyMock.posts.length, 0);
    await readonly.close();

    const { page: failed, mock: failedMock } = await setup();
    failedMock.failLoad = true;
    await failed.locator('#openRules').click();
    await failed.locator('.pet-rules-message[data-tone="error"]').waitFor();
    assert.equal(await failed.locator('[data-add="text"]').isDisabled(), true);
    assert.match(await failed.locator('.pet-rules-reload').textContent(), /重试加载/);
    failedMock.failLoad = false;
    await failed.locator('.pet-rules-reload').click(); await loaded(failed);
    assert.equal(failedMock.gets, 2);
    await failed.keyboard.press('Escape'); await open(failed);
    assert.equal(failedMock.gets, 3, 'clean reopen refreshes the site configuration');
    await failed.close();

    // Timeouts end the busy state. A POST may already have committed, so require reconciliation.
    const { page: timeoutPage, mock: timeoutMock } = await setup();
    await timeoutPage.evaluate(() => { window.rulesTestTimeoutMs = 30; });
    timeoutMock.delayLoad = 120;
    await timeoutPage.locator('#openRules').click();
    await timeoutPage.locator('.pet-rules-message[data-tone="error"]').waitFor();
    assert.match(await timeoutPage.locator('.pet-rules-message').textContent(), /加载规则超时/);
    assert.equal(await timeoutPage.locator('.pet-rules-reload').isEnabled(), true);
    await timeoutPage.waitForTimeout(140);
    timeoutMock.delayLoad = 0;
    await timeoutPage.locator('.pet-rules-reload').click(); await loaded(timeoutPage);
    await timeoutPage.getByLabel('规则名称').fill('超时前服务器已提交');
    timeoutMock.delaySave = 120;
    await timeoutPage.locator('.pet-rules-save').click();
    await timeoutPage.locator('.pet-rules-message[data-tone="error"]').waitFor();
    assert.match(await timeoutPage.locator('.pet-rules-message').textContent(), /提交结果未知/);
    assert.match(await timeoutPage.locator('.pet-rules-message').textContent(), /先刷新核对/);
    assert.equal(await timeoutPage.getByLabel('规则名称').inputValue(), '超时前服务器已提交');
    assert.equal(await timeoutPage.getByLabel('规则名称').isEnabled(), true);
    assert.equal(await timeoutPage.locator('.pet-rules-save').isDisabled(), true);
    assert.equal(timeoutMock.config.version, 4, 'the simulated server committed before its response timed out');
    await timeoutPage.getByLabel('规则名称').fill('超时后的本地草稿');
    assert.match(await timeoutPage.locator('.pet-rules-message').textContent(), /提交结果未知/, 'editing cannot hide the unknown submission state');
    await timeoutPage.keyboard.press('Control+s');
    assert.equal(timeoutMock.posts.length, 1, 'unknown submissions cannot be blindly repeated');
    await timeoutPage.keyboard.press('Escape'); await timeoutPage.locator('#openRules').click();
    assert.equal(await timeoutPage.getByLabel('规则名称').inputValue(), '超时后的本地草稿');
    assert.equal(timeoutMock.gets, 2, 'unknown submission draft is retained on reopen');
    await timeoutPage.waitForTimeout(140);
    timeoutMock.delaySave = 0;
    await timeoutPage.locator('.pet-rules-reload').click(); await loaded(timeoutPage);
    assert.equal(await timeoutPage.getByLabel('规则名称').inputValue(), '超时前服务器已提交');
    await timeoutPage.getByLabel('规则名称').fill('核对后的新修改');
    await save(timeoutPage);
    assert.equal(timeoutMock.posts.at(-1).version, 4, 'reconciled editing uses the updated server version');
    assert.equal(await timeoutPage.evaluate(() => window.rulesRequestedTimeouts.every(value => value === 15000)), true);
    assert.equal(await timeoutPage.evaluate(() => window.rulesRequestOptions.every(options => options.hasSignal && (options.method !== 'GET' || options.cache === 'no-store'))), true);
    await timeoutPage.close();

    const { page: limitPage, mock: limitMock } = await setup();
    limitMock.config.rules = Array.from({ length: 20 }, (_, index) => ({ ...fixture().rules[0], id: `b829909a-f079-4a8a-bd8c-c8156c68${String(index).padStart(4, '0')}`, title: `规则 ${index + 1}`, content: index < 7 ? '文'.repeat(2000) : '简短内容' }));
    await open(limitPage);
    assert.equal(await limitPage.locator('[data-add="text"]').isDisabled(), true);
    assert.equal(await limitPage.locator('[data-add="condition"]').isDisabled(), true);
    await limitPage.getByLabel('规则名称').fill('修改第一条名称');
    await limitPage.locator('.pet-rules-save').click();
    assert.match(await limitPage.locator('.pet-rules-message').textContent(), /合计最多 12000/);
    assert.equal(limitMock.posts.length, 0);
    await limitPage.close();
    assert.deepEqual(errors, [], 'all rule editor scenarios must run without page errors');
    console.log('PASS: lazy authenticated uncached loading, request timeouts/unknown-submit reconciliation, rule order, text/condition rules, scopes/dimensions, AND/units, create/enable/order/delete/save, locked save, draft preservation, conflict reload, readonly, retries, limits, safe text and 390px layout');
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
