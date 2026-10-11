import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import type { PrismaClient } from '@prisma/client';
import { hozzFixture, hozzRecord, hozzSleep } from './hozz-fixtures';

export async function testHozz(
  db: PrismaClient,
  handle: (request: Request) => Promise<Response>,
  passwordHash: (password: string) => string,
) {
  const username = 'hozz-synthetic-owner',
    password = randomBytes(24).toString('hex');
  const user = await db.user.create({ data: { username, passwordHash: passwordHash(password) } });
  const previous = process.env.BOOTSTRAP_USERNAME;
  process.env.BOOTSTRAP_USERNAME = username;
  let cookie = '';
  const request = (path: string, method = 'GET', body?: unknown, authenticated = true) =>
    new Request(process.env.APP_ORIGIN + path, {
      method,
      headers: {
        origin: process.env.APP_ORIGIN!,
        'content-type': 'application/json',
        ...(authenticated ? { authorization: 'Bearer ' + process.env.INGEST_API_KEY, cookie } : {}),
        ...(path.includes('/ingest') ? { 'hozz-format': 'json', 'hozz-schema': 'hozz' } : {}),
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
  const upload = (body: unknown) => handle(request('/api/health/ingest', 'POST', body));
  const summary = async () =>
    (await handle(request('/api/health/summary?start=2026-10-08&end=2026-10-08'))).json();
  try {
    const logged = await handle(request('/api/auth/login', 'POST', { username, password }));
    assert.equal(logged.status, 200);
    cookie = logged.headers.get('set-cookie')!.split(';')[0];
    const probe = { kind: 'hozzConnectionTest', schemaVersion: 1 };
    assert.equal((await handle(request('/api/health/ingest', 'POST', probe, false))).status, 401);
    assert.equal((await upload(probe)).status, 200);
    assert.equal(
      await db.healthRawImport.count({ where: { userId: user.id } }),
      0,
      'probe is never health data',
    );
    const first = await upload(hozzFixture);
    assert.equal(first.status, 200);
    const firstResult = await first.json();
    assert.equal(firstResult.insertedCount, 9);
    const repeats = await Promise.all([upload(hozzFixture), upload([...hozzFixture].reverse())]);
    for (const r of repeats) {
      assert.equal(r.status, 200);
      assert.equal((await r.json()).insertedCount, 0);
    }
    assert.equal(await db.healthSample.count({ where: { userId: user.id } }), 8);
    assert.equal(await db.healthSourceRecord.count({ where: { userId: user.id } }), 9);
    assert.equal(await db.workout.count({ where: { userId: user.id } }), 1);
    const initial = await summary();
    assert.equal(initial.days[0].sleepMinutes, 435);
    assert.equal(initial.sleep[0].bedtime, '23:30');
    assert.equal(initial.sleep[0].wakeTime, '07:00');
    assert.equal(initial.days[0].steps, 1000);
    assert.equal(initial.days[0].metadata.steps, 'Garmin Connect');
    assert.equal(
      initial.sync.hozzHistoryIncomplete,
      true,
      'missing coverage is not proof of completeness',
    );
    const updated = {
      ...hozzFixture[0],
      recordVersion: 2,
      quantity: { unit: 'count', value: 1200 },
    };
    assert.equal((await upload([updated])).status, 200);
    assert.equal((await summary()).days[0].steps, 1200);
    await upload([hozzFixture[0]]);
    assert.equal((await summary()).days[0].steps, 1200, 'older revisions do not overwrite newer');
    const phone = hozzRecord('phone', 'HKQuantityTypeIdentifierStepCount', 'quantity', {
      source: { name: 'iPhone', bundleIdentifier: 'test.synthetic.phone' },
      quantity: { unit: 'count', value: 9000 },
    });
    await upload([phone]);
    assert.equal((await summary()).days[0].steps, 1200, 'do not add iPhone + Garmin');
    await upload([
      hozzRecord('steps', 'HKQuantityTypeIdentifierStepCount', 'deletion', { recordVersion: 3 }),
    ]);
    await upload([updated]);
    assert.equal(
      (await db.healthSourceRecord.findUnique({
        where: {
          userId_source_recordId: {
            userId: user.id,
            source: 'Hozz',
            recordId: 'apple.healthkit:steps',
          },
        },
      }))!.deleted,
      true,
    );
    assert.equal(
      await db.healthSample.count({
        where: { userId: user.id, sourceRecordId: 'apple.healthkit:steps' },
      }),
      0,
    );
    await upload([
      hozzRecord('phone', 'HKQuantityTypeIdentifierStepCount', 'deletion', { recordVersion: 2 }),
    ]);
    assert.equal((await summary()).days[0].steps, null);
    const materialized = await db.dailyHealthSummary.findUnique({
      where: { userId_date: { userId: user.id, date: '2026-10-08' } },
    });
    assert.equal(materialized!.steps, null, 'deletions also update materialized totals');
    await upload([
      hozzRecord('core', 'HKCategoryTypeIdentifierSleepAnalysis', 'deletion', { recordVersion: 2 }),
    ]);
    assert.equal((await summary()).days[0].sleepMinutes, 60);
    await upload([hozzSleep('tail', 5, '2026-10-08T01:30:00+08:00', '2026-10-08T02:00:00+08:00')]);
    assert.equal(
      (await summary()).days[0].sleepMinutes,
      90,
      'late split batch rebuilds the session',
    );
    await upload([
      hozzRecord('deep', 'HKCategoryTypeIdentifierSleepAnalysis', 'deletion', { recordVersion: 2 }),
      hozzRecord('tail', 'HKCategoryTypeIdentifierSleepAnalysis', 'deletion', { recordVersion: 2 }),
    ]);
    assert.equal((await summary()).days[0].sleepMinutes, null, 'in-bed alone must not be sleep');
    assert.equal(
      (await handle(request('/api/health/reprocess', 'POST', { importId: firstResult.importId })))
        .status,
      200,
    );
    assert.equal(
      (await summary()).days[0].sleepMinutes,
      null,
      'manual reprocessing must not revive deleted stages',
    );
    assert.equal(
      (await summary()).days[0].steps,
      null,
      'manual reprocessing must not revive deleted quantities',
    );
    const report = {
      kind: 'typeCoverage',
      schemaVersion: 1,
      type: 'HKQuantityTypeIdentifierStepCount',
      state: 'draining',
      complete: false,
      deliveredCount: 3,
      observedAt: '2026-10-08T03:00:00Z',
    };
    await upload([report]);
    await upload([
      { ...report, state: 'anchorClosed', complete: true, observedAt: '2026-10-08T04:00:00Z' },
    ]);
    await upload([report]);
    assert.equal(
      (await summary()).sync.hozzCoverage[0].complete,
      true,
      'old coverage reports do not regress state',
    );
    assert.equal(
      (await summary()).sync.hozzHistoryIncomplete,
      true,
      'one complete type must not imply that unreported types are complete',
    );
    const rawOnly = await upload([
      hozzRecord('distance', 'HKQuantityTypeIdentifierDistanceWalkingRunning', 'quantity', {
        quantity: { unit: 'km', value: 3.2 },
      }),
    ]);
    assert.equal(rawOnly.status, 200);
    const rawResult = await rawOnly.json();
    assert.equal(rawResult.status, 'processed_with_gaps');
    assert.equal(rawResult.retainedCount, 1);
    assert.equal(
      (await upload([{ ...hozzFixture[1], quantity: { unit: 'count/min', value: 'unsafe' } }]))
        .status,
      422,
    );
    assert.equal((await upload([{ ...hozzFixture[0], schemaVersion: 2 }])).status, 202);
    assert.equal(
      (await upload([123])).status,
      422,
      'invalid records are not a future schema version',
    );
    const exported = await (await handle(request('/api/export'))).json();
    assert.ok(exported.sourceRecords.some((r: { deleted: boolean }) => r.deleted));
    assert.ok(exported.imports.every((r: { source: string }) => r.source === 'Hozz'));
    assert.equal(
      (
        await handle(
          request('/api/my-data', 'DELETE', { password, confirmation: 'DELETE_MY_HEALTH_DATA' }),
        )
      ).status,
      200,
    );
    assert.equal(await db.healthSourceRecord.count({ where: { userId: user.id } }), 0);
    assert.equal(await db.healthSample.count({ where: { userId: user.id } }), 0);
    assert.equal(await db.dailyHealthSummary.count({ where: { userId: user.id } }), 0);
    console.log(
      'PASS Hozz PostgreSQL: authenticated probe / concurrent dedup / versions / tombstones / cross-batch sleep / coverage / retained records / export & deletion',
    );
  } finally {
    process.env.BOOTSTRAP_USERNAME = previous;
  }
}
