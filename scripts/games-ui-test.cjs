const {chromium} = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../frontend');
const output = path.resolve(__dirname, '../.runtime');

(async () => {
  const server = http.createServer((request, response) => {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    const file = path.join(root, path.basename(pathname));
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
      response.writeHead(404).end();
      return;
    }
    response.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html;charset=utf-8');
    response.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel:'chrome', headless:true});
    const page = await browser.newPage({viewport:{width:1440, height:1000}});
    const errors = [], requests = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/pet-loader.js*', route => route.fulfill({contentType:'application/javascript', body:''}));
    await page.route('**/api/**', route => {
      const pathname = new URL(route.request().url()).pathname;
      requests.push({pathname, method:route.request().method()});
      const data = pathname === '/api/session'
        ? {authenticated:true, user:{id:'games-member', role:'member'}}
        : pathname === '/api/report-visibility'
          ? {dhh:true, jd:false, jdLowActivity:false, adpflux:false}
          : pathname === '/api/tool-visibility' ? {bidMonitor:false} : {};
      return route.fulfill({json:data});
    });
    // Pause the browser clock after loading: AI cancellation checks must not race
    // a slow machine, and advancing 300 ms verifies the actual delayed callback.
    const startTime = new Date('2026-10-09T00:00:00Z');
    await page.clock.install({time:startTime});
    const url = `http://127.0.0.1:${server.address().port}/games.html`;
    await page.goto(url);
    await page.locator('html.authenticated').waitFor();
    await page.locator('.game-card').first().waitFor();
    assert.equal(await page.locator('.game-card').count(), 2);
    assert.deepEqual(await page.locator('.game-card h3').allTextContents(), ['五子棋','围棋']);
    assert.equal(await page.locator('.app-sidebar-link[aria-current="page"]').getAttribute('data-module'), 'tools');
    assert.equal(await page.locator('body').getAttribute('data-route'), '/games');
    fs.mkdirSync(output, {recursive:true});
    await page.screenshot({path:path.join(output, 'games-lobby-desktop.png'), fullPage:true});
    await page.clock.pauseAt(new Date('2026-10-09T00:01:00Z'));

    const cell = (row, col) => page.locator(`.gomoku-cell[data-row="${row}"][data-col="${col}"]`);
    const stones = () => page.locator('.gomoku-stone').count();
    const count = async expected => assert.equal(await stones(), expected);
    const status = async expected => assert.equal(await page.locator('#gameStatus').textContent(), expected);
    const computerTurn = () => page.clock.runFor(300);
    const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);

    await page.locator('[data-game="gomoku"]').click();
    assert.equal(await page.locator('#gomokuArena').isVisible(), true);
    assert.equal(await page.locator('#gameLobby').isVisible(), false);
    assert.ok(page.url().endsWith('#gomoku'));
    assert.equal(await page.getByRole('gridcell').count(), 225);
    assert.equal(await page.locator('.app-sidebar-link[aria-current="page"]').getAttribute('data-module'), 'tools');

    await page.locator('#gameMode').selectOption('local');
    assert.equal(await page.locator('#humanSideControl').isVisible(), false);
    await count(0);await status('轮到黑方');
    await cell(7, 3).click();await count(1);await status('轮到白方');
    assert.equal(await cell(7, 3).getAttribute('data-player'), '1');
    // aria-disabled blocks normal Playwright actions; a pointer click must also
    // remain harmless when a player tries an occupied or finished intersection.
    await cell(7, 3).click({force:true});await count(1);await status('轮到白方');
    await cell(0, 0).click();await count(2);await status('轮到黑方');
    assert.equal(await cell(0, 0).getAttribute('data-player'), '2');
    for (const [row, col] of [[7,4],[0,2],[7,5],[0,4],[7,6],[0,6],[7,7]]) await cell(row, col).click();
    await count(9);await status('黑方获胜');
    assert.equal(await page.locator('.gomoku-cell.winning').count(), 5);
    assert.equal(await page.locator('.gomoku-cell[aria-disabled="true"]').count(), 225);
    await cell(14, 14).click({force:true});await count(9);
    await page.screenshot({path:path.join(output, 'gomoku-desktop.png'), fullPage:true});
    await page.locator('#undoMove').click();await count(8);await status('轮到黑方');
    assert.equal(await page.locator('.gomoku-cell.winning').count(), 0);
    assert.equal(await cell(7, 7).getAttribute('data-player'), '0');
    await cell(14, 14).click();await count(9);await status('轮到白方');

    await page.locator('#gameMode').selectOption('ai');
    assert.equal(await page.locator('#humanSideControl').isVisible(), true);
    await count(0);await status('轮到你 · 执黑');
    await cell(7, 7).click();await count(1);await status('电脑思考中…');
    await computerTurn();await count(2);await status('轮到你 · 执黑');
    assert.equal(await page.locator('.gomoku-cell[data-player="1"]').count(), 1);
    assert.equal(await page.locator('.gomoku-cell[data-player="2"]').count(), 1);
    await page.locator('#undoMove').click();await count(0);
    await computerTurn();await count(0);
    assert.equal(await page.locator('#moveHistory li').count(), 0);

    await cell(7, 7).click();await status('电脑思考中…');
    await page.locator('#undoMove').click();await count(0);
    await computerTurn();await count(0);await status('轮到你 · 执黑');

    await cell(6, 6).click();await status('电脑思考中…');
    await page.locator('#gameMode').selectOption('local');
    await computerTurn();await count(0);await status('轮到黑方');
    await page.locator('#gameMode').selectOption('ai');
    await cell(7, 7).click();await status('电脑思考中…');
    await page.locator('#newGame').click();
    await computerTurn();await count(0);await status('轮到你 · 执黑');

    await page.locator('#humanSide').selectOption('2');
    await count(0);await status('电脑思考中…');
    assert.equal(await page.locator('#undoMove').isDisabled(), true);
    await computerTurn();await count(1);await status('轮到你 · 执白');
    assert.equal(await cell(7, 7).getAttribute('data-player'), '1');
    await cell(6, 6).click();await count(2);await status('电脑思考中…');
    await computerTurn();await count(3);await status('轮到你 · 执白');
    await page.locator('#undoMove').click();await count(1);
    assert.equal(await cell(7, 7).getAttribute('data-player'), '1');
    assert.equal(await page.locator('.gomoku-cell[data-player="2"]').count(), 0);
    await cell(6, 6).click();await status('电脑思考中…');
    await page.locator('#humanSide').selectOption('1');
    await computerTurn();await count(0);await status('轮到你 · 执黑');

    await cell(7, 7).click();await count(1);await status('电脑思考中…');
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', {persisted:true})));
    await computerTurn();await count(1);
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted:true})));
    await computerTurn();await count(2);await status('轮到你 · 执黑');
    await page.locator('#newGame').click();await count(0);

    await cell(7, 7).click();await count(1);await status('电脑思考中…');
    await page.locator('#backToLobby').click();
    assert.equal(await page.locator('#gameLobby').isVisible(), true);
    assert.equal(await page.locator('#gomokuArena').isVisible(), false);
    assert.equal(new URL(page.url()).hash, '');
    await computerTurn();await count(1);
    assert.equal(await page.locator('[data-game="gomoku"]').textContent(), '继续对局');
    await page.locator('[data-game="gomoku"]').click();
    await count(1);await status('电脑思考中…');
    await computerTurn();await count(2);await status('轮到你 · 执黑');
    await page.locator('#backToLobby').click();await computerTurn();await count(2);
    await page.locator('[data-game="gomoku"]').click();await computerTurn();await count(2);

    await page.locator('#gameMode').selectOption('local');
    await cell(7, 7).focus();
    await page.keyboard.press('ArrowRight');await page.keyboard.press('ArrowDown');
    assert.deepEqual(await page.evaluate(() => [document.activeElement.dataset.row, document.activeElement.dataset.col]), ['8','8']);
    await page.keyboard.press('Enter');await count(1);
    assert.equal(await cell(8, 8).getAttribute('data-player'), '1');
    await page.keyboard.press('ArrowLeft');await page.keyboard.press('ArrowUp');
    assert.deepEqual(await page.evaluate(() => [document.activeElement.dataset.row, document.activeElement.dataset.col]), ['7','7']);
    await page.keyboard.press('Space');await count(2);
    assert.equal(await cell(7, 7).getAttribute('data-player'), '2');

    await page.setViewportSize({width:390, height:844});
    await noOverflow();
    const boardBox = await page.locator('#gomokuBoard').boundingBox();
    assert.ok(boardBox.width >= 250 && Math.abs(boardBox.width - boardBox.height) < 1, 'mobile board must stay usable and square');
    await page.screenshot({path:path.join(output, 'gomoku-mobile.png'), fullPage:true});
    await page.locator('#backToLobby').click();await noOverflow();

    assert.equal(requests.some(request => request.pathname.includes('/chat')), false, 'AI opponent must not call a chat service');
    const allowedApis = new Set(['/api/session','/api/report-visibility','/api/tool-visibility']);
    assert.ok(requests.every(request => request.method === 'GET' && allowedApis.has(request.pathname)), 'game state must stay in the browser');

    const loggedOut = await browser.newPage();
    loggedOut.on('pageerror', error => errors.push(error.message));
    await loggedOut.route('**/pet-loader.js*', route => route.fulfill({contentType:'application/javascript', body:''}));
    await loggedOut.route('**/api/**', route => route.fulfill({json:{authenticated:false}}));
    await loggedOut.route('**/login', route => route.fulfill({contentType:'text/html;charset=utf-8', body:'<!doctype html><html lang="zh-CN"><body><h1>登录</h1></body></html>'}));
    await loggedOut.goto(url);
    await loggedOut.waitForURL('**/login');
    assert.equal(await loggedOut.getByRole('heading', {name:'登录', exact:true}).isVisible(), true);
    assert.deepEqual(errors, []);
    console.log('GAMES UI PASS: lobby, local/AI play and undo, delayed move cancellation, keyboard, mobile square/width, login redirect');
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => {console.error(error);process.exitCode = 1;});
