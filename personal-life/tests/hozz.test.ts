import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseHozz, probeSchema, sleepSessions } from '../src/lib/hozz';
import { localDay, selectSource, defaults } from '../src/lib/domain';
import { createConfig } from '../scripts/configure.mjs';
import { hozzFixture, hozzRecord, hozzSleep } from './hozz-fixtures';

test('Hozz v1 converts documented units and preserves exact source identity', () => {
  const rows = parseHozz(hozzFixture);
  assert.equal(rows.length, 9);
  assert.equal(rows[0].sample?.value, 1000);
  assert.equal(rows[0].sample?.sourceRecordId, 'apple.healthkit:steps');
  assert.equal(rows[1].sample?.unit, 'bpm');
  assert.ok(Math.abs(rows[2].sample!.value - 100) < 1e-9);
  assert.ok(Math.abs(rows[3].sample!.value - 68.0388555) < 1e-9);
  assert.equal(rows[4].workout?.durationMinutes, 30);
  assert.equal(rows[4].workout?.calories, undefined);
});
test('sleep is an interval union minus awake, not a category number or in-bed duration', () => {
  const rows = parseHozz(hozzFixture)
    .filter((r) => r.sample?.stage)
    .map((r) => ({
      startTime: r.startTime!,
      endTime: r.endTime!,
      source: r.sample!.source,
      metadata: { stage: r.sample!.stage, sourceBundle: 'synthetic' },
    }));
  const sessions = sleepSessions(rows);
  assert.equal(sessions.length, 1);
  assert.equal(sessions[0].durationMinutes, 435);
  assert.equal(localDay(sessions[0].endTime), '2026-10-08');
  assert.equal(sleepSessions(rows.filter((r) => r.metadata.stage === 'in_bed')).length, 0);
});
test('cross-batch sleep fragments can regroup without counting gaps or mixing devices', () => {
  const rows = parseHozz([
    hozzSleep('part1', 3, '2026-10-07T23:30:00+08:00', '2026-10-08T01:00:00+08:00'),
    hozzSleep('part2', 5, '2026-10-08T01:30:00+08:00', '2026-10-08T02:30:00+08:00'),
    hozzSleep('nap', 1, '2026-10-08T13:00:00+08:00', '2026-10-08T14:00:00+08:00'),
  ]).map((r) => ({
    startTime: r.startTime!,
    endTime: r.endTime!,
    source: 'Garmin',
    metadata: { stage: r.sample!.stage },
  }));
  const sessions = sleepSessions(rows);
  assert.equal(sessions.length, 2);
  assert.equal(sessions[0].durationMinutes, 150);
  assert.equal(
    sleepSessions([...rows, { ...rows[0], metadata: { stage: 'core', deviceId: 'other' } }]).length,
    3,
  );
});
test('awake intervals trim bedtime and wake time rather than only reducing duration', () => {
  const now = Date.parse('2026-10-08T00:00:00Z');
  const row = (a: number, b: number, stage: string) => ({
    startTime: new Date(now + a * 60000),
    endTime: new Date(now + b * 60000),
    source: 'Synthetic',
    metadata: { stage },
  });
  const sessions = sleepSessions([
    row(0, 120, 'asleep'),
    row(0, 10, 'awake'),
    row(110, 120, 'awake'),
  ]);
  assert.equal(sessions[0].durationMinutes, 100);
  assert.equal(+sessions[0].startTime, now + 10 * 60000);
  assert.equal(+sessions[0].endTime, now + 110 * 60000);
});
test('malformed Hozz data, substituted identity, and unrecognized schema cannot become measurements', () => {
  assert.throws(() => parseHozz([{ ...hozzFixture[0], schemaVersion: 2 }]));
  assert.throws(() => parseHozz([{ ...hozzFixture[0], canonicalId: 'apple.healthkit:other' }]));
  assert.throws(() =>
    parseHozz([{ ...hozzFixture[0], quantity: { value: '100', unit: 'count' } }]),
  );
  assert.throws(() => parseHozz([{ ...hozzFixture[0], endDate: 'not-a-date' }]));
  assert.throws(() => parseHozz([{ ...hozzFixture[0], endDate: '2020-01-01T00:00:00Z' }]));
  assert.equal(
    probeSchema.safeParse({ kind: 'hozzConnectionTest', schemaVersion: 1 }).success,
    true,
  );
  assert.equal(
    probeSchema.safeParse({ ...hozzFixture[0], kind: 'hozzConnectionTest' }).success,
    false,
  );
});
test('unmapped units/types/cross-day totals stay raw; non-Apple sleep categories are not interpreted', () => {
  const examples = [
    { ...hozzFixture[0], quantity: { unit: 'unverified-unit', value: 100 } },
    { ...hozzFixture[0], endDate: '2026-10-09T01:00:00+08:00' },
    hozzRecord('unsupported', 'HKQuantityTypeIdentifierDistanceWalkingRunning', 'quantity', {
      quantity: { unit: 'km', value: 3 },
    }),
    {
      ...hozzFixture[0],
      canonicalId: 'android.healthconnect:steps',
      sourceRecord: { store: 'android.healthconnect', id: 'steps' },
    },
  ];
  for (const input of examples) {
    const row = parseHozz([input])[0];
    assert.equal(row.retained, true);
    assert.equal(row.sample, undefined);
  }
});
test('tombstones and coverage are not numeric health samples', () => {
  const deletion = parseHozz([
    hozzRecord('steps', 'HKQuantityTypeIdentifierStepCount', 'deletion', { recordVersion: 2 }),
  ])[0];
  assert.equal(deletion.deleted, true);
  assert.equal(deletion.sample, undefined);
  const report = {
    kind: 'typeCoverage',
    schemaVersion: 1,
    type: 'HKQuantityTypeIdentifierStepCount',
    state: 'draining',
    complete: false,
    deliveredCount: 2,
    observedAt: '2026-10-08T03:00:00Z',
  };
  assert.equal(parseHozz([report])[0].coverage, true);
  assert.throws(() => parseHozz([{ ...report, complete: true }]));
});
test('Garmin Connect respects Garmin priority without summing iPhone records', () => {
  const now = new Date();
  const rows = [
    { source: 'iPhone', startTime: now, endTime: now },
    { source: 'Garmin Connect', startTime: now, endTime: now },
  ];
  assert.equal(selectSource(rows, defaults.sourcePriority)[0].source, 'Garmin Connect');
});
test('setup generates distinct strong secrets privately and refuses to overwrite config', () => {
  const directory = mkdtempSync(join(tmpdir(), 'life-config-'));
  createConfig(directory, 'https://health.synthetic.invalid');
  const env = readFileSync(join(directory, '.env'), 'utf8');
  const values = Object.fromEntries(
    env
      .split('\n')
      .filter((r) => r && !r.startsWith('#'))
      .map((r) => [r.split('=')[0], r.slice(r.indexOf('=') + 1)]),
  );
  assert.match(values.INGEST_API_KEY, /^[a-f0-9]{64}$/);
  assert.match(values.POSTGRES_PASSWORD, /^[a-f0-9]{64}$/);
  assert.ok(values.BOOTSTRAP_PASSWORD.length >= 24);
  assert.equal(
    new Set([values.INGEST_API_KEY, values.POSTGRES_PASSWORD, values.BOOTSTRAP_PASSWORD]).size,
    3,
  );
  assert.throws(() => createConfig(directory, 'https://health.synthetic.invalid'));
  assert.equal(readFileSync(join(directory, '.env'), 'utf8'), env);
  assert.throws(() => createConfig(directory, 'http://health.synthetic.invalid'));
});
