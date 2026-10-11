import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  sampleSchema,
  normalizedSchema,
  sampleIdentity,
  fingerprint,
  dayRange,
  localDay,
  defaults,
  disjoint,
  selectSource,
  bedtimeMinutes,
  settingsSchema,
} from '../src/lib/domain';
import { aggregate } from '../src/lib/analytics';
import { queryPlan, answer } from '../src/lib/assistant';
import {
  passwordHash,
  verifyPassword,
  readJson,
  enforceTransport,
  sameOrigin,
} from '../src/lib/security';
import { validateRaw } from '../src/lib/ingest';
import { fixture, sleep, steps } from './fixtures';
import type { HealthSample, SleepSession } from '@prisma/client';

test('normalized adapter rejects unknown fields and wrong units', () => {
  assert.equal(normalizedSchema.safeParse(fixture).success, true);
  assert.equal(sampleSchema.safeParse({ ...steps, unit: 'meters' }).success, false);
  assert.equal(sampleSchema.safeParse({ ...sleep, unexpected: 1 }).success, false);
  assert.equal(normalizedSchema.safeParse({ format: 'normalized-v1', samples: [] }).success, false);
});
test('sleep crosses midnight and is attributed to wake-up day in Shanghai', () => {
  assert.equal(sampleSchema.safeParse(sleep).success, true);
  assert.equal(localDay(new Date(sleep.endTime)), '2026-10-11');
  assert.equal(localDay('2026-10-10T17:00:00Z'), '2026-10-11');
  assert.equal(dayRange('2026-10-11', '2026-10-11').from.toISOString(), '2026-10-10T16:00:00.000Z');
  assert.throws(() => dayRange('2026-10-12', '2026-10-11'));
  assert.throws(() => dayRange('2020-01-01', '2026-01-01'));
});
test('cumulative metrics must be split by local day, midnight end is valid', () => {
  assert.equal(sampleSchema.safeParse(steps).success, true);
  assert.equal(
    sampleSchema.safeParse({ ...steps, endTime: '2026-10-12T01:00:00+08:00' }).success,
    false,
  );
});
test('canonical fingerprints independent of property order and UTC offsets', () => {
  assert.equal(fingerprint({ a: 1, b: 2 }), fingerprint({ b: 2, a: 1 }));
  assert.equal(
    sampleIdentity(sleep),
    sampleIdentity({
      ...sleep,
      startTime: new Date(sleep.startTime).toISOString(),
      endTime: new Date(sleep.endTime).toISOString(),
    }),
  );
});
test('multi-source totals never add iPhone and Garmin together', () => {
  const rows = fixture.samples.map((s, i) => ({
    ...s,
    id: String(i),
    userId: 'test',
    startTime: new Date(s.startTime),
    endTime: new Date(s.endTime),
    createdAt: new Date(),
    metadata: {},
    sourceRecordId: null,
    fingerprint: String(i),
  })) as HealthSample[];
  const sessions = [{ ...rows[0], durationMinutes: 480 }] as unknown as SleepSession[];
  const day = aggregate(rows, sessions, [], defaults).find((d) => d.date === '2026-10-11')!;
  assert.equal(day.date, '2026-10-11');
  assert.equal(day.steps, 5000);
  assert.equal(day.sleepMinutes, 480);
  assert.equal(day.metadata.sourceConflict, true);
  assert.equal(
    aggregate(rows, sessions, [], { ...defaults, sourcePriority: ['iPhone', 'Garmin'] }).find(
      (d) => d.date === '2026-10-11',
    )!.steps,
    6000,
  );
  assert.equal(selectSource([], defaults.sourcePriority).length, 0);
});
test('overlapping daily/hourly records are not blindly added', () => {
  const row = {
    source: 'Garmin',
    startTime: new Date(0),
    endTime: new Date(10000),
    createdAt: new Date(0),
  };
  assert.equal(disjoint([row, { ...row, endTime: new Date(5000) }]).length, 1);
  assert.equal(
    disjoint([row, { ...row, startTime: new Date(10000), endTime: new Date(20000) }]).length,
    2,
  );
  assert.equal(bedtimeMinutes('00:00') - bedtimeMinutes('23:30'), 30);
});
test('settings validate targets and opt out of AI by default', () => {
  assert.equal(defaults.aiConsent, false);
  assert.equal(settingsSchema.safeParse({ sleepMinHours: 9, sleepMaxHours: 8 }).success, false);
});
test('raw parser accepts unknown structures without inventing a mapping', () => {
  assert.deepEqual(validateRaw({ unknownVendor: { future: true } }), {
    unknownVendor: { future: true },
  });
  assert.throws(() => validateRaw({}));
  assert.throws(() => validateRaw([]));
  assert.throws(() => validateRaw('text'));
  let nested: unknown = { ok: true };
  for (let i = 0; i < 30; i++) nested = { nested };
  assert.throws(() => validateRaw(nested));
});
test('password hashing salted, verification constant-length', () => {
  const hash = passwordHash('synthetic-long-password');
  assert.equal(verifyPassword('synthetic-long-password', hash), true);
  assert.equal(verifyPassword('wrong', hash), false);
  assert.notEqual(hash, passwordHash('synthetic-long-password'));
});
test('JSON request parser rejects malformed, wrong media type and oversized body', async () => {
  const req = (
    body: string,
    headers: Record<string, string> = { 'content-type': 'application/json' },
  ) => new Request('https://test.invalid/api', { method: 'POST', headers, body });
  await assert.rejects(() => readJson(req('not-json')));
  await assert.rejects(() => readJson(req('{}', { 'content-type': 'text/plain' })));
  await assert.rejects(() => readJson(req('{"long":"1234567890"}'), 8));
  assert.deepEqual(await readJson(req('{"ok":true}')), { ok: true });
});
test('HTTPS and origin protections fail closed', () => {
  const previous = { origin: process.env.APP_ORIGIN, local: process.env.ALLOW_INSECURE_LOCAL };
  process.env.APP_ORIGIN = 'https://test.invalid';
  process.env.ALLOW_INSECURE_LOCAL = 'false';
  try {
    assert.throws(() => enforceTransport(new Request('http://test.invalid')));
    enforceTransport(new Request('https://test.invalid'));
    assert.throws(() =>
      sameOrigin(
        new Request('https://test.invalid', { headers: { origin: 'https://evil.invalid' } }),
      ),
    );
  } finally {
    process.env.APP_ORIGIN = previous.origin;
    process.env.ALLOW_INSECURE_LOCAL = previous.local;
  }
});
test('AI query functions select bounded intended metrics and wake-up date', () => {
  const now = new Date('2026-10-11T08:00:00+08:00');
  assert.deepEqual(queryPlan('我昨晚睡了多久？', now), {
    start: '2026-10-11',
    end: '2026-10-11',
    metrics: ['sleep'],
  });
  assert.deepEqual(queryPlan('我最近7天运动了几次？', now), {
    start: '2026-10-05',
    end: '2026-10-11',
    metrics: ['activity'],
  });
  assert.equal(queryPlan('这周睡眠比上周好吗？', now).start, '2026-09-28');
});
test('official OpenAI SDK uses configured model, no store, minimal supplied context', async () => {
  process.env.OPENAI_API_KEY = 'synthetic-test-key-not-real';
  process.env.OPENAI_MODEL = 'test-model';
  process.env.OPENAI_BASE_URL = 'https://untrusted.invalid/v1';
  let seen: Record<string, unknown> | undefined;
  const transport: typeof fetch = async (_url, init) => {
    assert.ok(String(_url).startsWith('https://api.openai.com/'));
    seen = JSON.parse(String(init?.body));
    return Response.json({
      id: 'resp_synthetic',
      object: 'response',
      created_at: 0,
      status: 'completed',
      model: 'test-model',
      output: [
        {
          type: 'message',
          id: 'msg_test',
          status: 'completed',
          role: 'assistant',
          content: [{ type: 'output_text', text: '暂无睡眠数据。', annotations: [] }],
        },
      ],
    });
  };
  assert.equal(await answer('昨晚睡多久？', { sleep: [] }, transport), '暂无睡眠数据。');
  assert.equal(seen?.store, false);
  assert.equal(seen?.model, 'test-model');
  delete process.env.OPENAI_API_KEY;
  delete process.env.OPENAI_MODEL;
  delete process.env.OPENAI_BASE_URL;
});
