const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../frontend');

async function checkMetadataRace(browser, action, staleError) {
  const page = await browser.newPage({viewport:{width:1440,height:960}});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const now = Date.now();
  let notes = action === 'delete' ? [{id:1,title:'旧备忘录',content:'原正文',tags:'旧标签',pinned:true,deleted:false,version:1,createdAt:now,updatedAt:now}] : [];
  let metaCalls = 0, releaseOldMeta, signalOldMeta;
  const oldMetaRequested = new Promise(resolve => { signalOldMeta = resolve; });
  function metadata() {
    const active = notes.filter(note => !note.deleted), tags = {};
    active.forEach(note => note.tags.split(',').filter(Boolean).forEach(tag => tags[tag] = (tags[tag] || 0) + 1));
    return {total:active.length,pinned:active.filter(note => note.pinned).length,trash:notes.length-active.length,tags};
  }
  // Route every request so this regression test uses the real frontend without network or database access.
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), pathname = url.pathname, method = route.request().method();
    if (!pathname.startsWith('/api/')) {
      const file = path.join(root, path.basename(pathname));
      if (!fs.existsSync(file) || !fs.statSync(file).isFile()) return route.fulfill({status:404,body:''});
      const contentType = pathname.endsWith('.js') ? 'application/javascript' : pathname.endsWith('.css') ? 'text/css' : pathname.endsWith('.png') ? 'image/png' : 'text/html;charset=utf-8';
      return route.fulfill({contentType,body:fs.readFileSync(file)});
    }
    let json = {};
    if (pathname === '/api/session') json = {authenticated:true,user:{id:42,role:'member'}};
    else if (pathname === '/api/report-visibility') json = {dhh:true,jd:true};
    else if (pathname === '/api/tool-visibility') json = {bidMonitor:true};
    else if (pathname === '/api/pet/config') json = {configured:false,canManage:false};
    else if (pathname === '/api/memos/meta') {
      json = metadata();
      if (++metaCalls === 1) {
        await new Promise(resolve => { releaseOldMeta = resolve; signalOldMeta(); });
        return route.fulfill({status:staleError ? 502 : 200,headers:{'x-meta-snapshot':'old'},json:staleError ? {error:'旧统计请求失败'} : json});
      }
    } else if (pathname === '/api/memos' && method === 'POST') {
      json = {...route.request().postDataJSON(),id:1,version:1,deleted:false,createdAt:now,updatedAt:Date.now()};
      notes.push(json);
    } else if (pathname === '/api/memos') {
      const items = notes.filter(note => !note.deleted).map(note => ({...note,preview:note.content}));
      json = {items,hasMore:false,nextOffset:items.length};
    } else if (pathname === '/api/memos/1') {
      if (method === 'DELETE') { notes[0].deleted = true; notes[0].version++; json = {ok:true}; }
      else json = notes[0];
    }
    return route.fulfill({json});
  });
  try {
    await page.goto('http://memo-audit.invalid/memos.html');
    await oldMetaRequested;
    await page.waitForFunction(() => !document.querySelector('#newMemo').disabled && document.querySelector('#listStatus').textContent.startsWith('已显示'));
    if (action === 'save') {
      await page.locator('#newMemo').click();
      await page.locator('#title').fill('新备忘录');
      await page.locator('#content').fill('保存后的正文');
      await page.locator('#tags').fill('新标签');
      await page.locator('#pinned').check();
      await page.locator('#save').click();
      await page.waitForFunction(() => document.querySelector('#totalCount').textContent === '1' && !document.querySelector('#newMemo').disabled);
    } else {
      await page.locator('.memo-card').click();
      await page.waitForFunction(() => !document.querySelector('#newMemo').disabled && !document.querySelector('#editor').hidden);
      page.once('dialog', dialog => dialog.accept());
      await page.locator('#remove').click();
      await page.waitForFunction(() => document.querySelector('#trashCount').textContent === '1' && !document.querySelector('#newMemo').disabled);
    }
    const expected = metadata();
    const delayedResponse = page.waitForResponse(response => response.headers()['x-meta-snapshot'] === 'old');
    releaseOldMeta();
    const response = await delayedResponse;
    await response.finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await page.locator('#totalCount').textContent(), String(expected.total), `${action}: stale metadata must not replace the current total`);
    assert.equal(await page.locator('#pinCount').textContent(), String(expected.pinned), `${action}: stale metadata must not replace the current pinned count`);
    assert.equal(await page.locator('#trashCount').textContent(), String(expected.trash), `${action}: stale metadata must not replace the current trash count`);
    const tagOptions = await page.locator('#tagFilter option').evaluateAll(options => options.map(option => [option.value, option.textContent]));
    assert.deepEqual(tagOptions, [['','全部标签'],...Object.entries(expected.tags).map(([tag,count]) => [tag,`${tag} (${count})`])], `${action}: stale metadata must not replace current tags`);
    assert.equal(await page.locator('#message').textContent(), action === 'save' ? '已保存' : '已移入回收站', `${action}: stale errors must not replace the success message`);
    assert.equal(await page.locator('.memo-card').count(), action === 'save' ? 1 : 0);
    assert.deepEqual(errors, []);
  } finally {
    releaseOldMeta?.();
    await page.close();
  }
}

(async () => {
  const browser = await chromium.launch({channel:'chrome',headless:true});
  try {
    for (const action of ['save','delete']) {
      for (const staleError of [false,true]) await checkMetadataRace(browser, action, staleError);
    }
    console.log('PASS: delayed startup metadata success/errors cannot overwrite save/delete counts, tags or success messages');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
