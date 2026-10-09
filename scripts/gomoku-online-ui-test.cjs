const {chromium} = require('playwright');
const {spawn, spawnSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

// Build first: mvn -q test-compile dependency:build-classpath
//   -Dmdep.includeScope=test -Dmdep.outputFile=.runtime/gomoku-browser-classpath.txt
// Default classpath output: .runtime/gomoku-browser-classpath.txt. Override with
// GOMOKU_TEST_CLASSPATH_FILE and JAVA_HOME when using a different local runtime.
const root = path.resolve(__dirname, '..');
const output = path.join(root, '.runtime');
const base = '/api/games/gomoku/rooms';
const classpathFile = process.env.GOMOKU_TEST_CLASSPATH_FILE
  ? path.resolve(root, process.env.GOMOKU_TEST_CLASSPATH_FILE)
  : path.join(output, 'gomoku-browser-classpath.txt');

function javaTool(tool) {
  const name = tool + (process.platform === 'win32' ? '.exe' : '');
  const candidates = [
    process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', name),
    path.join(output, 'microsoft-jdk21', 'jdk-21.0.12+8', 'bin', name),
  ].filter(Boolean);
  return candidates.find(file => fs.existsSync(file)) || tool;
}

async function startServer() {
  assert.ok(fs.existsSync(classpathFile), 'Generate a test-scope dependency classpath in .runtime/gomoku-browser-classpath.txt first');
  fs.mkdirSync(output, {recursive:true});
  const classes = path.join(output, 'gomoku-browser-harness');
  fs.mkdirSync(classes, {recursive:true});
  const deps = fs.readFileSync(classpathFile, 'utf8').trim();
  const cp = [path.join(root, 'target', 'classes'), deps].join(path.delimiter);
  const compiled = spawnSync(javaTool('javac'), ['-parameters', '-encoding', 'UTF-8', '-cp', cp, '-d', classes, path.join(__dirname, 'GomokuBrowserServer.java')], {cwd:root, encoding:'utf8'});
  assert.equal(compiled.status, 0, compiled.stderr || compiled.error?.message || 'Compile the application classes first');
  const server = spawn(javaTool('java'), ['-cp', [classes, cp].join(path.delimiter), 'com.rockorca.bi.GomokuBrowserServer'], {cwd:root, windowsHide:true});
  let logs = '';
  try {
    const url = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Java browser server did not start:\n' + logs)), 30000);
      const read = chunk => {
        logs += chunk.toString();
        const ready = /GOMOKU_BROWSER_READY (http:\/\/127\.0\.0\.1:\d+)/.exec(logs);
        if (ready) { clearTimeout(timeout);resolve(ready[1]); }
      };
      server.stdout.on('data', read);server.stderr.on('data', read);
      server.once('error', error => { clearTimeout(timeout);reject(error); });
      server.once('exit', code => { clearTimeout(timeout);reject(new Error(`Java server exited ${code}:\n${logs}`)); });
    });
    return {url, stop() { fs.writeFileSync(path.join(output, 'gomoku-browser-server.log'), logs);server.kill(); }};
  } catch (error) { server.kill();throw error; }
}

async function eventually(check, label, timeout = 10000) {
  const end = Date.now() + timeout;
  let last;
  while (Date.now() < end) {
    try { return await check(); } catch (error) { last = error; }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`${label}: ${last?.message || 'timed out'}`);
}

(async () => {
  const server = await startServer();
  let browser;
  const errors = [], clients = new Map(), calls = [];
  try {
    browser = await chromium.launch({channel:'chrome', headless:true});
    async function player(username = 'alpha', viewport = {width:1440, height:1000}) {
      const context = await browser.newContext({viewport});
      if (username === 'alpha') await context.addInitScript(() => {
        Object.defineProperty(crypto, 'randomUUID', {value:undefined, configurable:true});
      });
      await context.grantPermissions(['clipboard-read','clipboard-write'], {origin:server.url});
      const login = await context.request.get(`${server.url}/__test/login/${username}`);
      assert.equal(login.status(), 200, await login.text());
      const page = await context.newPage();
      page.on('pageerror', error => errors.push(error.message));
      page.on('request', request => {
        const pathname = new URL(request.url()).pathname;
        if (!pathname.startsWith(base)) return;
        const client = request.headers()['x-game-client'];
        if (client) clients.set(page, client);
        calls.push({page, pathname, method:request.method()});
      });
      // Pets are unrelated to game networking; game API and login remain real HTTP.
      await page.route('**/pet-loader.js*', route => route.fulfill({contentType:'application/javascript', body:''}));
      return {context, page};
    }
    const a = await player(), b = await player(), third = await player('gamma');
    const gameUrl = `${server.url}/games.html#gomoku`;
    const cell = (page, row, col) => page.locator(`.gomoku-cell[data-row="${row}"][data-col="${col}"]`);
    const stones = (page, expected) => eventually(async () => assert.equal(await page.locator('.gomoku-stone').count(), expected), `Expected ${expected} stones`);
    const bothStones = expected => Promise.all([stones(a.page, expected), stones(b.page, expected)]);
    const status = (page, pattern) => eventually(async () => assert.match(await page.locator('#gameStatus').textContent(), pattern), 'Expected game status');
    const api = async (page, method, suffix, payload) => {
      const client = clients.get(page);
      assert.ok(client, 'The page must first establish its independent game client');
      return page.evaluate(async ({method, url, client, payload}) => {
        const response = await fetch(url, {method, headers:{'X-Game-Client':client, ...(payload === undefined ? {} : {'Content-Type':'application/json'})}, ...(payload === undefined ? {} : {body:JSON.stringify(payload)})});
        return {status:response.status, data:await response.json()};
      }, {method, url:base + suffix, client, payload});
    };
    const apiResponse = (page, suffix, method = 'POST') => page.waitForResponse(response => new URL(response.url()).pathname === base + suffix && response.request().method() === method);
    const clickResponse = async (page, id, suffix) => {
      const [response] = await Promise.all([apiResponse(page, suffix), page.locator(id).click()]);
      assert.equal(response.status(), 200, await response.text());
      return response.json();
    };

    await Promise.all([a.page.goto(gameUrl), b.page.goto(gameUrl)]);
    await Promise.all([a.page.locator('html.authenticated').waitFor(), b.page.locator('html.authenticated').waitFor()]);
    assert.equal(await a.page.evaluate(() => typeof crypto.randomUUID), 'undefined');
    assert.equal(await b.page.evaluate(() => typeof crypto.randomUUID), 'undefined');
    assert.equal((await a.context.request.get(`${server.url}/api/session`).then(response => response.json())).user.id,
      (await b.context.request.get(`${server.url}/api/session`).then(response => response.json())).user.id,
      'Two independent browser contexts can use the same login account');
    await a.page.locator('#gameMode').selectOption('online');
    const created = await clickResponse(a.page, '#createRoom', '');
    const code = created.code;
    assert.match(code, /^[A-Z0-9]{6}$/);
    assert.equal(created.seat, 1);assert.equal(created.phase, 'waiting');
    assert.equal(created.game.moves.length, 0);
    await eventually(async () => assert.match(await a.page.locator('#roomInfo').textContent(), new RegExp(code)), 'Created room code is displayed');
    assert.equal(await a.page.locator('.gomoku-cell[aria-disabled="true"]').count(), 225);

    await a.page.locator('#copyRoomLink').click();
    const invitation = await a.page.evaluate(() => navigator.clipboard.readText());
    const invitedUrl = new URL(invitation);
    assert.equal(invitedUrl.origin, server.url);assert.equal(invitedUrl.pathname, '/games.html');
    assert.equal(invitedUrl.searchParams.get('room'), code);assert.equal(invitedUrl.hash, '#gomoku');
    assert.deepEqual([...invitedUrl.searchParams.keys()], ['room']);
    assert.equal(invitation.includes(clients.get(a.page)), false, 'An invite must never expose the private player client ID');

    await b.page.locator('#gameMode').selectOption('online');
    await b.page.locator('#roomCode').fill(code.toLowerCase());
    const joined = await clickResponse(b.page, '#joinRoom', `/${code}/join`);
    assert.equal(joined.seat, 2);assert.equal(joined.phase, 'playing');
    assert.notEqual(clients.get(a.page), clients.get(b.page));
    assert.match(clients.get(a.page), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    assert.match(clients.get(b.page), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    await status(a.page, /轮到你/);await status(b.page, /对方|黑方/);
    assert.match(await a.page.locator('#gameStatusHint').textContent(), /你执黑/);
    assert.match(await b.page.locator('#gameStatusHint').textContent(), /你执白/);
    assert.equal(await b.page.locator('.gomoku-cell[aria-disabled="true"]').count(), 225);
    const beforeWrongTurn = calls.length;
    await cell(b.page, 6, 6).click({force:true});await bothStones(0);
    assert.equal(calls.slice(beforeWrongTurn).filter(call => call.pathname.endsWith('/moves')).length, 0, 'An opponent turn cannot trigger a UI move');
    const wrongTurn = await api(b.page, 'POST', `/${code}/moves`, {row:6,col:6,version:joined.version});
    assert.equal(wrongTurn.status, 409);await bothStones(0);
    const longPollStarted = Date.now();
    const timedOutPoll = await api(a.page, 'GET', `/${code}?since=${joined.version}&wait=true`);
    assert.equal(timedOutPoll.status, 200, 'A real servlet long poll times out with a fresh JSON snapshot');
    assert.ok(Date.now() - longPollStarted >= 19000 && Date.now() - longPollStarted < 28000,
      'An unchanged room long poll should wait approximately 20 seconds');
    assert.equal(timedOutPoll.data.version, joined.version);
    assert.equal(timedOutPoll.data.seat, 1);assert.equal(timedOutPoll.data.game.moves.length, 0);

    await third.page.goto(invitation);
    await third.page.locator('html.authenticated').waitFor();
    await eventually(async () => assert.match(await third.page.locator('#onlineNotice').textContent(), /满|两名|两位|2.*玩家/), 'Third player receives a full-room error');
    assert.equal(await third.page.locator('.gomoku-stone').count(), 0);

    // Hold only delivery of a real Java creation response. Returning to the
    // lobby while it is pending must keep the completed room paused.
    let releaseCreation, observedCreation;
    const heldCreation = new Promise(resolve => { releaseCreation = resolve; });
    const creationObserved = new Promise(resolve => { observedCreation = resolve; });
    await third.page.route('**/api/games/gomoku/rooms', async route => {
      const response = await route.fetch();
      observedCreation(await response.json());
      await heldCreation;
      await route.fulfill({response});
    });
    const delayedCreationResponse = apiResponse(third.page, '');
    await third.page.locator('#createRoom').click();
    const delayedRoom = await creationObserved;
    await third.page.locator('#backToLobby').click();
    assert.equal(await third.page.locator('#gameLobby').isVisible(), true);
    releaseCreation();assert.equal((await delayedCreationResponse).status(), 200);
    await eventually(async () => assert.equal(await third.page.locator('#createRoom').isDisabled(), false), 'Pending creation finishes while lobby stays open');
    assert.equal(await third.page.locator('#gameLobby').isVisible(), true);
    assert.equal(calls.filter(call => call.page === third.page && call.method === 'GET' && call.pathname === `${base}/${delayedRoom.code}`).length, 0,
      'A creation completed after leaving the arena must not start background polling');
    await third.page.unroute('**/api/games/gomoku/rooms');
    assert.equal((await api(third.page, 'POST', `/${delayedRoom.code}/leave`, {})).status, 200);

    let snapshot = await clickResponse(a.page, '.gomoku-cell[data-row="7"][data-col="3"]', `/${code}/moves`);
    await bothStones(1);
    assert.equal(await cell(b.page, 7, 3).getAttribute('data-player'), '1');
    assert.equal(snapshot.seat, 1);assert.equal(snapshot.game.currentPlayer, 2);
    assert.equal((await api(b.page, 'POST', `/${code}/moves`, {row:0,col:0,version:joined.version})).status, 409, 'A stale version is rejected by the real server');
    await bothStones(1);
    snapshot = await clickResponse(b.page, '.gomoku-cell[data-row="0"][data-col="0"]', `/${code}/moves`);await bothStones(2);
    snapshot = await clickResponse(a.page, '.gomoku-cell[data-row="7"][data-col="4"]', `/${code}/moves`);await bothStones(3);
    snapshot = await clickResponse(b.page, '.gomoku-cell[data-row="0"][data-col="2"]', `/${code}/moves`);await bothStones(4);
    assert.equal((await api(a.page, 'GET', `/${code}`)).data.seat, 1);
    assert.equal((await api(b.page, 'GET', `/${code}`)).data.seat, 2);

    snapshot = await clickResponse(b.page, '#undoMove', `/${code}/requests`);
    assert.deepEqual(snapshot.request, {type:'undo', by:2});
    await a.page.locator('#roomRequest').waitFor({state:'visible'});
    assert.match(await a.page.locator('#roomRequestText').textContent(), /悔棋/);
    await eventually(async () => assert.equal(await a.page.locator('.gomoku-cell[aria-disabled="true"]').count(), 225), 'Undo approval freezes both boards');
    assert.equal(await b.page.locator('.gomoku-cell[aria-disabled="true"]').count(), 225);
    snapshot = await clickResponse(a.page, '#rejectRequest', `/${code}/requests/respond`);
    assert.equal(snapshot.request, null);await bothStones(4);
    await clickResponse(b.page, '#undoMove', `/${code}/requests`);
    await a.page.locator('#roomRequest').waitFor({state:'visible'});
    snapshot = await clickResponse(a.page, '#acceptRequest', `/${code}/requests/respond`);
    assert.equal(snapshot.request, null);assert.equal(snapshot.game.moves.length, 3);await bothStones(3);
    assert.equal(await cell(b.page, 0, 2).getAttribute('data-player'), '0');

    await clickResponse(a.page, '#newGame', `/${code}/requests`);
    await b.page.locator('#roomRequest').waitFor({state:'visible'});
    await bothStones(3);
    snapshot = await clickResponse(b.page, '#acceptRequest', `/${code}/requests/respond`);
    assert.equal(snapshot.game.moves.length, 0);assert.equal(snapshot.phase, 'playing');await bothStones(0);
    for (const [page,row,col] of [[a.page,7,3],[b.page,0,0],[a.page,7,4],[b.page,0,2],[a.page,7,5],[b.page,0,4],[a.page,7,6],[b.page,0,6],[a.page,7,7]]) {
      snapshot = await clickResponse(page, `.gomoku-cell[data-row="${row}"][data-col="${col}"]`, `/${code}/moves`);
      await bothStones(snapshot.game.moves.length);
    }
    assert.equal(snapshot.phase, 'won');assert.equal(snapshot.game.winner, 1);
    assert.equal(snapshot.game.winningLine.length, 5);
    await status(a.page, /获胜|赢/);await status(b.page, /获胜|输了|落败/);
    assert.equal(await a.page.locator('.gomoku-cell.winning').count(), 5);
    assert.equal(await b.page.locator('.gomoku-cell.winning').count(), 5);
    await cell(a.page, 14, 14).click({force:true});await bothStones(9);
    await a.page.screenshot({path:path.join(output, 'gomoku-online-desktop.png'), fullPage:true});
    await clickResponse(b.page, '#newGame', `/${code}/requests`);
    await a.page.locator('#roomRequest').waitFor({state:'visible'});
    await clickResponse(a.page, '#acceptRequest', `/${code}/requests/respond`);await bothStones(0);

    // Network disconnection must not create speculative local stones. Reconnect
    // retrieves the authoritative move and retains the original seat/client.
    const bClient = clients.get(b.page);
    await b.context.setOffline(true);
    await eventually(async () => assert.match((await b.page.locator('#onlineNotice').textContent()) + (await b.page.locator('#gameStatus').textContent()), /离线|网络|断开|重连|中断/), 'Offline status is shown');
    await clickResponse(a.page, '.gomoku-cell[data-row="7"][data-col="7"]', `/${code}/moves`);await stones(a.page, 1);
    await stones(b.page, 0);
    await b.context.setOffline(false);await bothStones(1);
    assert.equal(clients.get(b.page), bClient);
    await clickResponse(b.page, '.gomoku-cell[data-row="6"][data-col="6"]', `/${code}/moves`);await bothStones(2);
    const aClient = clients.get(a.page);
    await a.page.reload();
    await a.page.locator('html.authenticated').waitFor();await bothStones(2);
    assert.equal(await a.page.locator('#gameMode').inputValue(), 'online');
    assert.equal(clients.get(a.page), aClient, 'Reload restores the same player identity');
    assert.equal((await api(a.page, 'GET', `/${code}`)).data.seat, 1);
    await eventually(async () => assert.match(await a.page.locator('#roomInfo').textContent(), new RegExp(code)), 'Reload restores the room');

    await clickResponse(a.page, '.gomoku-cell[data-row="8"][data-col="8"]', `/${code}/moves`);await bothStones(3);
    await a.page.locator('#backToLobby').click();
    assert.equal(await a.page.locator('#gameLobby').isVisible(), true);
    await clickResponse(b.page, '.gomoku-cell[data-row="8"][data-col="9"]', `/${code}/moves`);
    await stones(b.page, 4);await stones(a.page, 3);
    await a.page.locator('[data-game="gomoku"]').click();await bothStones(4);
    assert.equal(clients.get(a.page), aClient);
    assert.equal(await cell(a.page, 8, 9).getAttribute('data-player'), '2', 'Reopening after lobby pause retrieves the opponent\'s new move');

    await a.page.locator('#gameMode').selectOption('local');await stones(a.page, 0);
    const remoteMoveCalls = calls.filter(call => call.page === a.page && call.pathname.endsWith('/moves')).length;
    await cell(a.page, 2, 2).click();await cell(a.page, 3, 3).click();await stones(a.page, 2);
    await stones(b.page, 4);
    assert.equal(calls.filter(call => call.page === a.page && call.pathname.endsWith('/moves')).length, remoteMoveCalls,
      'Local play while a room is retained cannot send remote moves');
    const unchangedRemote = await api(b.page, 'GET', `/${code}`);
    assert.equal(unchangedRemote.data.game.moves.length, 4);
    assert.equal(unchangedRemote.data.game.board[2][2], 0);assert.equal(unchangedRemote.data.game.board[3][3], 0);
    await a.page.locator('#gameMode').selectOption('online');await bothStones(4);
    assert.equal(await cell(a.page, 2, 2).getAttribute('data-player'), '0');
    assert.equal(await cell(a.page, 8, 9).getAttribute('data-player'), '2');

    await b.page.setViewportSize({width:390, height:844});
    assert.equal(await b.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    const board = await b.page.locator('#gomokuBoard').boundingBox();
    assert.ok(board.width >= 250 && Math.abs(board.width - board.height) < 1, 'Online mobile board stays usable and square');
    const roomInfo = await b.page.locator('#roomInfo').boundingBox();
    assert.ok(roomInfo.x >= 0 && roomInfo.x + roomInfo.width <= 390);
    await b.page.screenshot({path:path.join(output, 'gomoku-online-mobile.png'), fullPage:true});

    await clickResponse(a.page, '#leaveRoom', `/${code}/leave`);
    await eventually(async () => assert.match((await b.page.locator('#onlineNotice').textContent()) + (await b.page.locator('#gameStatus').textContent()), /离开|结束|关闭/), 'Opponent is notified when a player leaves');
    assert.equal(await b.page.locator('.gomoku-cell[aria-disabled="true"]').count(), 225);
    const anonymous = await browser.newContext();
    const unauthorized = await anonymous.request.post(`${server.url}${base}`, {data:{}, headers:{'X-Game-Client':'0ecdcf35-33da-4f3e-bb20-e24d1ef66b21'}});
    assert.equal(unauthorized.status(), 401, 'Game networking requires an authenticated signed session');
    await anonymous.close();
    const bRequestsBeforeSwitch = calls.filter(call => call.page === b.page).length;
    assert.equal((await b.context.request.get(`${server.url}/__test/login/beta`)).status(), 200);
    await b.page.reload();await b.page.locator('html.authenticated').waitFor();
    await b.page.locator('.gomoku-cell').first().waitFor();
    assert.equal(await b.page.locator('#gameMode').inputValue(), 'ai');
    assert.equal(await b.page.locator('#roomInfo').isVisible(), false);
    await stones(b.page, 0);
    assert.equal(calls.filter(call => call.page === b.page).length, bRequestsBeforeSwitch,
      'Switching login accounts must not resume the previous user\'s room or player client');
    assert.deepEqual(errors, []);
    assert.ok(calls.some(call => call.method === 'GET' && call.pathname === `${base}/${code}`), 'Both contexts must synchronize through real room GET requests');
    console.log('GOMOKU ONLINE UI PASS: real Java HTTP and signed sessions; UUID fallback, same-account independent seats, create/join/share, 20s long poll, pending-create pause, authoritative turns/versions/full room, synchronized win, undo/restart consent, offline reconnect, reload/lobby resume, local mode isolation, leave, account isolation, mobile');
  } finally {
    await browser?.close();server.stop();
  }
})().catch(error => {console.error(error);process.exitCode = 1;});
