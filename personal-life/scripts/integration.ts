import EmbeddedPostgres from 'embedded-postgres';
import { mkdtempSync, mkdirSync, cpSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { spawnSync, spawn } from 'node:child_process';
import { createServer } from 'node:net';
import assert from 'node:assert/strict';
import { fixture, sleep } from '../tests/fixtures';

async function freePort() {
  const server = createServer();
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const address = server.address();
  if (!address || typeof address === 'string') throw Error('port');
  const port = address.port;
  await new Promise<void>((r) => server.close(() => r()));
  return port;
}
mkdirSync('.test-runtime', { recursive: true });
const directory = mkdtempSync(resolve('.test-runtime/pg-')),
  port = await freePort(),
  password = randomBytes(24).toString('hex');
const pg = new EmbeddedPostgres({
  databaseDir: directory,
  port,
  user: 'test_owner',
  password,
  persistent: true,
  authMethod: 'scram-sha-256',
  postgresFlags: ['-c', 'lc_messages=C', '-c', 'listen_addresses=127.0.0.1'],
  onLog: (message) => {
    if (process.env.TEST_DEBUG === 'true') console.log(message);
  },
  onError: () => console.error('Test PostgreSQL process error'),
});
const windowsPgCtl =
  process.platform === 'win32'
    ? resolve('node_modules/@embedded-postgres/windows-x64/native/bin/pg_ctl.exe')
    : null;
let windowsStarted = false;
let disconnect: (() => Promise<void>) | undefined;
let web: ReturnType<typeof spawn> | undefined;
try {
  console.log('Starting isolated PostgreSQL test cluster…');
  await pg.initialise();
  // pg_ctl on Windows creates a restricted process token; postgres refuses an administrator token.
  if (windowsPgCtl) {
    const started = spawnSync(
      windowsPgCtl,
      [
        'start',
        '-D',
        directory,
        '-l',
        resolve(directory, 'test-server.log'),
        '-w',
        '-o',
        `-p ${port} -c lc_messages=C -c listen_addresses=127.0.0.1`,
      ],
      { encoding: 'utf8', windowsHide: true, timeout: 30000 },
    );
    assert.equal(started.status, 0, 'Isolated pg_ctl startup failed');
    windowsStarted = true;
  } else await pg.start();
  if (windowsPgCtl) {
    const client = pg.getPgClient();
    await client.connect();
    try {
      await client.query('CREATE DATABASE life_test');
    } finally {
      await client.end();
    }
  } else await pg.createDatabase('life_test');
  process.env.DATABASE_URL = `postgresql://test_owner:${password}@127.0.0.1:${port}/life_test`;
  process.env.APP_ORIGIN = 'http://127.0.0.1:3100';
  process.env.ALLOW_INSECURE_LOCAL = 'true';
  process.env.BOOTSTRAP_USERNAME = 'synthetic-owner';
  process.env.INGEST_API_KEY = randomBytes(32).toString('hex');
  process.env.ALLOW_NORMALIZED_INGEST = 'true';
  const migration = spawnSync(
    process.execPath,
    ['node_modules/prisma/build/index.js', 'migrate', 'deploy'],
    { env: process.env, encoding: 'utf8' },
  );
  assert.equal(migration.status, 0, 'Prisma migration failed');
  console.log('PASS PostgreSQL migration');
  const { db } = await import('../src/lib/db');
  disconnect = () => db.$disconnect();
  const { passwordHash, hash, rateLimit } = await import('../src/lib/security');
  const { handle } = await import('../src/lib/api');
  const { healthContext } = await import('../src/lib/assistant');
  const loginPassword = randomBytes(24).toString('hex');
  const user = await db.user.create({
    data: { username: 'synthetic-owner', passwordHash: passwordHash(loginPassword) },
  });
  const other = await db.user.create({
    data: {
      username: 'other-synthetic-user',
      passwordHash: passwordHash(randomBytes(24).toString('hex')),
    },
  });
  const request = (
    path: string,
    method = 'GET',
    body?: unknown,
    headers: Record<string, string> = {},
  ) =>
    new Request(process.env.APP_ORIGIN + path, {
      method,
      headers: {
        origin: process.env.APP_ORIGIN!,
        ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
        ...headers,
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
  assert.equal((await handle(request('/api/health/summary'))).status, 401);
  assert.equal((await handle(request('/api/assistant/history'))).status, 401);
  assert.equal((await handle(request('/api/health/ingest', 'POST', fixture))).status, 401);
  assert.equal(
    (
      await handle(
        request('/api/auth/login', 'POST', { username: 'synthetic-owner', password: 'wrong' }),
      )
    ).status,
    401,
  );
  const login = await handle(
    request('/api/auth/login', 'POST', { username: 'synthetic-owner', password: loginPassword }),
  );
  assert.equal(login.status, 200);
  const cookie = login.headers.get('set-cookie')!.split(';')[0];
  const session = { cookie };
  const ingestHeaders = { 'x-api-key': process.env.INGEST_API_KEY! };
  assert.equal(
    (
      await handle(
        request('/api/settings', 'PUT', {}, { ...session, origin: 'https://wrong.invalid' }),
      )
    ).status,
    403,
  );
  console.log('PASS authentication / CSRF');
  await rateLimit('synthetic-limit-check', 2);
  await rateLimit('synthetic-limit-check', 2);
  await assert.rejects(() => rateLimit('synthetic-limit-check', 2));
  const raw = { synthetic_unknown_shape: { version: 99, content: ['mock-only'] } };
  assert.equal(
    (await handle(request('/api/health/ingest', 'POST', raw, ingestHeaders))).status,
    202,
  );
  assert.equal(
    (await handle(request('/api/health/ingest', 'POST', raw, ingestHeaders))).status,
    202,
  );
  assert.equal(await db.healthRawImport.count({ where: { userId: user.id } }), 1);
  assert.equal(await db.healthSample.count({ where: { userId: user.id } }), 0);
  assert.equal(
    (
      await handle(
        request('/api/health/ingest', 'POST', raw, {
          authorization: 'Bearer ' + process.env.INGEST_API_KEY,
        }),
      )
    ).status,
    202,
  );
  const rawImport = await db.healthRawImport.findFirstOrThrow({ where: { userId: user.id } });
  assert.equal(
    (await handle(request('/api/health/reprocess', 'POST', { importId: rawImport.id }, session)))
      .status,
    202,
  );
  const otherToken = randomBytes(32).toString('hex');
  await db.session.create({
    data: {
      tokenHash: hash(otherToken),
      userId: other.id,
      expiresAt: new Date(Date.now() + 60000),
    },
  });
  assert.equal(
    (
      await handle(
        request(
          '/api/health/reprocess',
          'POST',
          { importId: rawImport.id },
          { cookie: 'life_session=' + otherToken },
        ),
      )
    ).status,
    404,
  );
  assert.equal(
    (
      await handle(
        new Request(process.env.APP_ORIGIN + '/api/health/ingest', {
          method: 'POST',
          headers: {
            ...ingestHeaders,
            'content-type': 'application/json',
            'content-length': '6000000',
          },
          body: '{}',
        }),
      )
    ).status,
    413,
  );
  assert.equal(
    (await handle(request('/api/health/ingest', 'POST', {}, ingestHeaders))).status,
    400,
  );
  assert.equal(
    (await handle(request('/api/health/ingest', 'POST', [], ingestHeaders))).status,
    400,
  );
  assert.equal(
    (
      await handle(
        new Request(process.env.APP_ORIGIN + '/api/health/ingest', {
          method: 'POST',
          headers: { ...ingestHeaders, 'content-type': 'application/json' },
          body: '{broken',
        }),
      )
    ).status,
    400,
  );
  assert.equal(
    (
      await handle(
        request(
          '/api/health/ingest',
          'POST',
          { format: 'normalized-v1', samples: [{ bad: true }] },
          ingestHeaders,
        ),
      )
    ).status,
    422,
  );
  process.env.ALLOW_NORMALIZED_INGEST = 'false';
  assert.equal(
    (await handle(request('/api/health/ingest', 'POST', fixture, ingestHeaders))).status,
    422,
  );
  process.env.ALLOW_NORMALIZED_INGEST = 'true';
  const initial = await handle(request('/api/health/ingest', 'POST', fixture, ingestHeaders));
  assert.equal(initial.status, 200);
  assert.equal((await initial.json()).insertedCount, 7);
  const duplicate = await handle(request('/api/health/ingest', 'POST', fixture, ingestHeaders));
  assert.equal((await duplicate.json()).duplicateCount, 7);
  const concurrent = await Promise.all([
    handle(request('/api/health/ingest', 'POST', fixture, ingestHeaders)),
    handle(request('/api/health/ingest', 'POST', fixture, ingestHeaders)),
  ]);
  assert.ok(concurrent.every((r) => r.status === 200));
  assert.equal(await db.healthSample.count({ where: { userId: user.id } }), 6);
  assert.equal(await db.workout.count({ where: { userId: user.id } }), 1);
  assert.equal(await db.sleepSession.count({ where: { userId: user.id } }), 1);
  console.log('PASS raw isolation / empty & invalid inputs / repeat & concurrent dedup');
  const summaryResponse = await handle(
    request('/api/health/summary?start=2026-10-11&end=2026-10-11', 'GET', undefined, session),
  );
  assert.equal(summaryResponse.status, 200);
  const summary = await summaryResponse.json();
  assert.equal(summary.days.length, 1);
  assert.equal(
    summary.recentWeek.days.length,
    7,
    'recent-week cards remain a full seven-day query',
  );
  assert.equal(summary.days[0].steps, 5000);
  assert.equal(summary.days[0].sleepMinutes, 480);
  assert.equal(summary.sleep[0].bedtime, '23:30');
  assert.equal(summary.sleep[0].wakeTime, '07:30');
  assert.equal(summary.sleep[0].date, '2026-10-11');
  assert.equal(summary.workouts.length, 1);
  assert.equal(summary.weight.length, 1);
  assert.equal(
    summary.stages.length,
    1,
    'sleep stage before midnight remains attached to last night',
  );
  assert.equal(
    (
      await db.dailyHealthSummary.findUniqueOrThrow({
        where: { userId_date: { userId: user.id, date: '2026-10-11' } },
      })
    ).steps,
    5000,
  );
  const imported = await db.healthSample.findFirstOrThrow({
    where: { userId: user.id, metricType: 'sleep_duration' },
  });
  assert.equal(imported.startTime.toISOString(), '2026-10-10T15:30:00.000Z');
  assert.equal(
    (await handle(request('/api/health/sleep?start=bad', 'GET', undefined, session))).status,
    400,
  );
  for (const endpoint of ['sleep', 'activity', 'workouts', 'weight', 'sync-status'])
    assert.equal(
      (await handle(request('/api/health/' + endpoint, 'GET', undefined, session))).status,
      200,
    );
  const historic = {
    format: 'normalized-v1',
    samples: [
      {
        ...sleep,
        startTime: '2026-10-01T23:30:00+08:00',
        endTime: '2026-10-02T07:30:00+08:00',
        sourceRecordId: 'synthetic-backfill',
      },
    ],
  };
  assert.equal(
    (await handle(request('/api/health/ingest', 'POST', historic, ingestHeaders))).status,
    200,
  );
  console.log(
    'PASS UTC / midnight sleep / daily midnight steps / source priority / historical backfill / materialized summaries',
  );
  const settings = { ...summary.settings, sourcePriority: ['iPhone', 'Garmin'] };
  assert.equal((await handle(request('/api/settings', 'PUT', settings, session))).status, 200);
  assert.equal(
    (
      await db.dailyHealthSummary.findUniqueOrThrow({
        where: { userId_date: { userId: user.id, date: '2026-10-11' } },
      })
    ).steps,
    6000,
  );
  await db.aIConversation.create({
    data: { userId: other.id, role: 'user', content: 'OTHER_USER_PRIVATE_SYNTHETIC' },
  });
  const context = await healthContext(user.id, '我昨晚睡了多久？', settings);
  assert.deepEqual(context.metrics, ['sleep']);
  assert.ok(!('activity' in context));
  assert.ok(!JSON.stringify(context).includes('sourceRecordId'));
  assert.ok(!JSON.stringify(context).includes(user.id));
  await assert.rejects(() => healthContext(user.id, '睡眠2020-01-01到2026-01-01', settings));
  assert.equal(
    (
      await handle(
        request('/api/assistant/chat', 'POST', { message: '睡眠', consent: true }, session),
      )
    ).status,
    403,
  );
  settings.aiConsent = true;
  await handle(request('/api/settings', 'PUT', settings, session));
  delete process.env.OPENAI_API_KEY;
  assert.equal(
    (
      await handle(
        request('/api/assistant/chat', 'POST', { message: '睡眠', consent: true }, session),
      )
    ).status,
    503,
  );
  assert.deepEqual(
    (await (await handle(request('/api/assistant/history', 'GET', undefined, session))).json())
      .messages,
    [],
  );
  console.log('PASS AI controlled query / consent / missing key / private history');
  const originalFetch = globalThis.fetch;
  process.env.OPENAI_API_KEY = 'synthetic-only-never-a-real-key';
  process.env.OPENAI_MODEL = 'synthetic-model';
  globalThis.fetch = async (input, init) => {
    assert.ok(String(input).startsWith('https://api.openai.com/'));
    const body = JSON.parse(String(init?.body));
    assert.equal(body.store, false);
    assert.ok(!JSON.stringify(body.input).includes('sourceRecordId'));
    return Response.json({
      id: 'resp_synthetic',
      object: 'response',
      created_at: 0,
      status: 'completed',
      model: 'synthetic-model',
      output: [
        {
          type: 'message',
          id: 'msg_synthetic',
          status: 'completed',
          role: 'assistant',
          content: [
            { type: 'output_text', text: '该测试日期睡眠为8小时，属于测试数据。', annotations: [] },
          ],
        },
      ],
    });
  };
  try {
    assert.equal(
      (
        await handle(
          request(
            '/api/assistant/chat',
            'POST',
            { message: '2026-10-11睡了多久？', consent: true },
            session,
          ),
        )
      ).status,
      200,
    );
  } finally {
    globalThis.fetch = originalFetch;
    delete process.env.OPENAI_API_KEY;
    delete process.env.OPENAI_MODEL;
  }
  const history = await (
    await handle(request('/api/assistant/history', 'GET', undefined, session))
  ).json();
  assert.deepEqual(
    history.messages.map((m: { role: string }) => m.role),
    ['user', 'assistant'],
  );
  console.log(
    'PASS AI chat endpoint / SDK mock / chronological private history (no external request)',
  );
  const exported = await (await handle(request('/api/export', 'GET', undefined, session))).json();
  assert.equal(exported.samples.length, 7);
  assert.ok(!JSON.stringify(exported).includes('passwordHash'));
  assert.ok(!JSON.stringify(exported).includes('OTHER_USER_PRIVATE_SYNTHETIC'));
  assert.equal(
    (
      await handle(
        request(
          '/api/my-data',
          'DELETE',
          { password: 'wrong', confirmation: 'DELETE_MY_HEALTH_DATA' },
          session,
        ),
      )
    ).status,
    403,
  );
  assert.equal(
    (
      await handle(
        request(
          '/api/my-data',
          'DELETE',
          { password: loginPassword, confirmation: 'DELETE_MY_HEALTH_DATA' },
          session,
        ),
      )
    ).status,
    200,
  );
  assert.equal(await db.healthRawImport.count({ where: { userId: user.id } }), 0);
  assert.equal(await db.healthSample.count({ where: { userId: user.id } }), 0);
  assert.equal(await db.workout.count({ where: { userId: user.id } }), 0);
  assert.equal(await db.sleepSession.count({ where: { userId: user.id } }), 0);
  assert.equal(await db.dailyHealthSummary.count({ where: { userId: user.id } }), 0);
  assert.equal(await db.syncLog.count({ where: { userId: user.id } }), 0);
  assert.equal(await db.session.count({ where: { userId: user.id } }), 0);
  assert.equal(await db.aIConversation.count({ where: { userId: other.id } }), 1);
  assert.equal(
    (await handle(request('/api/health/summary', 'GET', undefined, session))).status,
    401,
  );
  assert.equal((await handle(request('/api/healthz'))).status, 200);
  await assert.rejects(() =>
    db.user.create({ data: { username: 'synthetic-owner', passwordHash: 'not-used' } }),
  );
  console.log('PASS export / scoped deletion / revoked sessions / database constraints');
  // Optional production server HTTP and browser QA, against this isolated empty database.
  if (process.env.TEST_HTTP === 'true') {
    const webPort = await freePort();
    process.env.APP_ORIGIN = `http://127.0.0.1:${webPort}`;
    cpSync('.next/static', '.next/standalone/.next/static', { recursive: true });
    web = spawn(process.execPath, ['.next/standalone/server.js'], {
      env: { ...process.env, PORT: String(webPort), HOSTNAME: '127.0.0.1' },
      stdio: 'ignore',
    });
    let ready = false;
    for (let i = 0; i < 60; i++) {
      try {
        const r = await fetch(process.env.APP_ORIGIN + '/api/healthz');
        if (r.ok) {
          ready = true;
          break;
        }
      } catch {}
      await new Promise((r) => setTimeout(r, 500));
    }
    assert.ok(ready, 'Next HTTP startup');
    assert.equal((await fetch(process.env.APP_ORIGIN + '/api/health/summary')).status, 401);
    const redirected = await fetch(process.env.APP_ORIGIN + '/', { redirect: 'manual' });
    assert.equal(redirected.status, 307);
    const logged = await fetch(process.env.APP_ORIGIN + '/api/auth/login', {
      method: 'POST',
      headers: { origin: process.env.APP_ORIGIN, 'content-type': 'application/json' },
      body: JSON.stringify({ username: 'synthetic-owner', password: loginPassword }),
    });
    assert.equal(logged.status, 200);
    const httpCookie = logged.headers.get('set-cookie')!.split(';')[0];
    const syntheticUpload = await fetch(process.env.APP_ORIGIN + '/api/health/ingest', {
      method: 'POST',
      headers: { 'x-api-key': process.env.INGEST_API_KEY!, 'content-type': 'application/json' },
      body: JSON.stringify(fixture),
    });
    assert.equal(syntheticUpload.status, 200);
    for (const path of ['/', '/sleep', '/fitness', '/assistant', '/settings', '/settings/sync']) {
      const pageResponse: Response = await fetch(process.env.APP_ORIGIN + path, {
        headers: { cookie: httpCookie },
      });
      assert.equal(pageResponse.status, 200);
      assert.ok((await pageResponse.text()).includes('PERSONAL LIFE MANAGEMENT'));
    }
    console.log('PASS production Next HTTP / private redirects / all six pages');
    if (process.env.TEST_BROWSER === 'true') {
      // Uses an explicitly configured existing Playwright installation, never production data.
      const moduleName = process.env.PLAYWRIGHT_MODULE || 'playwright';
      const { chromium } = await import(moduleName);
      const browser = await chromium.launch({
        headless: true,
        channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome',
      });
      try {
        const browserContext = await browser.newContext();
        await browserContext.addCookies([
          { name: 'life_session', value: httpCookie.slice(13), url: process.env.APP_ORIGIN },
        ]);
        const page = await browserContext.newPage();
        const errors: string[] = [];
        page.on('pageerror', (e: Error) => errors.push(e.message));
        await page.goto(process.env.APP_ORIGIN + '/settings');
        const priorityInput = page.getByLabel('来源优先级（精确名称，逗号分隔）');
        await priorityInput.fill('Garmin');
        await priorityInput.pressSequentially(', iPhone');
        assert.equal(await priorityInput.inputValue(), 'Garmin, iPhone');
        await page.getByRole('button', { name: '保存设置', exact: true }).click();
        await page.getByText('设置已保存', { exact: true }).waitFor();
        for (const viewport of [
          { width: 1440, height: 1000 },
          { width: 390, height: 844 },
        ]) {
          await page.setViewportSize(viewport);
          for (const path of [
            '/',
            '/sleep',
            '/fitness',
            '/assistant',
            '/settings/sync',
            '/settings',
          ]) {
            await page.goto(process.env.APP_ORIGIN + path);
            await page.getByRole('heading', { name: navTitle(path), exact: true }).waitFor();
            await page.waitForTimeout(400);
            assert.equal(
              await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
              true,
              'mobile overflow ' + path,
            );
          }
          await page.goto(process.env.APP_ORIGIN + '/');
          await page.locator('input[type="date"]').first().fill('2026-10-11');
          await page.locator('input[type="date"]').nth(1).fill('2026-10-11');
          await page.getByRole('button', { name: '查询', exact: true }).click();
          const stepCard = page.locator('.stat').filter({ hasText: '当日步数' });
          await stepCard.filter({ hasText: '5,000' }).waitFor();
          assert.ok((await stepCard.textContent()).includes('Garmin'));
          await page.waitForTimeout(300);
          await page.screenshot({
            path: resolve('.test-runtime/dashboard-' + viewport.width + '.png'),
            fullPage: true,
          });
        }
        assert.deepEqual(errors, []);
        console.log('PASS browser desktop/mobile / all screens / no runtime errors');
      } finally {
        await browser.close();
      }
    }
  }
  console.log('ALL INTEGRATION CHECKS PASSED (synthetic database only)');
} finally {
  if (web) {
    web.kill();
    await new Promise((r) => web!.once('exit', r));
  }
  await disconnect?.();
  if (windowsPgCtl && windowsStarted)
    spawnSync(windowsPgCtl, ['stop', '-D', directory, '-m', 'fast', '-w'], {
      windowsHide: true,
      timeout: 30000,
    });
  else await pg.stop();
}
function navTitle(path: string) {
  return (
    {
      '/': '健康概览',
      '/sleep': '睡眠分析',
      '/fitness': '运动分析',
      '/assistant': '生活助手',
      '/settings': '个人设置',
      '/settings/sync': '数据同步',
    } as Record<string, string>
  )[path];
}
