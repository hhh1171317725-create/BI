import { z } from 'zod';
import { fingerprint, localDay, sampleSchema, workoutSchema, type SampleInput } from './domain';

// Hozz schema v1, documented at docs/delivery-schema.md, reference commit c5baa3b.
// Unknown types remain raw; never infer a measurement from a category or coverage report.
const text = z.string().min(1).max(200);
const instant = z.iso.datetime({ offset: true });
const base = z
  .object({
    schemaVersion: z.literal(1),
    kind: text,
    type: text,
    id: text,
    canonicalId: z.string().min(1).max(400).optional(),
    recordVersion: z.number().int().min(1).max(2147483647).default(1),
    sourceRecord: z
      .object({ store: text, id: text.optional(), type: text.optional() })
      .passthrough()
      .optional(),
  })
  .passthrough();
const span = z
  .object({ startDate: instant, endDate: instant })
  .refine((v) => Date.parse(v.endDate) >= Date.parse(v.startDate));
const source = z.object({ name: z.string().min(1).max(100), bundleIdentifier: text }).passthrough();
const coverage = z
  .object({
    schemaVersion: z.literal(1),
    kind: z.literal('typeCoverage'),
    type: text,
    state: text,
    complete: z.boolean(),
    deliveredCount: z.number().int().nonnegative(),
    observedAt: instant,
    primedFrom: instant.optional(),
    primedThrough: instant.optional(),
  })
  .passthrough()
  .refine((v) => !v.complete || v.state === 'anchorClosed');
export const probeSchema = z
  .object({ kind: z.literal('hozzConnectionTest'), schemaVersion: z.literal(1) })
  .strict();
export function isHozz(payload: unknown) {
  const rows = Array.isArray(payload) ? payload : [payload];
  return rows.some((v) => v && typeof v === 'object' && 'schemaVersion' in v && 'kind' in v);
}
export type HozzProjection = {
  key: string;
  version: number;
  deleted: boolean;
  raw: Record<string, unknown>;
  startTime?: Date;
  endTime?: Date;
  observedAt?: Date;
  sample?: SampleInput;
  workout?: z.infer<typeof workoutSchema>;
  retained: boolean;
  coverage: boolean;
};
const quantities: Record<
  string,
  { metric: SampleInput['metricType']; unit: string; factors: Record<string, number> }
> = {
  HKQuantityTypeIdentifierStepCount: { metric: 'steps', unit: 'count', factors: { count: 1 } },
  HKQuantityTypeIdentifierHeartRate: {
    metric: 'heart_rate',
    unit: 'bpm',
    factors: { 'count/min': 1, bpm: 1 },
  },
  HKQuantityTypeIdentifierRestingHeartRate: {
    metric: 'resting_heart_rate',
    unit: 'bpm',
    factors: { 'count/min': 1, bpm: 1 },
  },
  HKQuantityTypeIdentifierActiveEnergyBurned: {
    metric: 'active_energy',
    unit: 'kcal',
    factors: { kcal: 1, kJ: 1 / 4.184, J: 1 / 4184 },
  },
  HKQuantityTypeIdentifierBodyMass: {
    metric: 'weight',
    unit: 'kg',
    factors: { kg: 1, g: 0.001, lb: 0.45359237, st: 6.35029318 },
  },
};
const stages = ['in_bed', 'asleep', 'awake', 'core', 'deep', 'rem'] as const;
const workoutTypes: Record<number, string> = {
  13: '骑行',
  20: '功能性力量训练',
  24: '徒步',
  37: '跑步',
  46: '游泳',
  50: '传统力量训练',
  52: '步行',
  57: '瑜伽',
  63: '高强度间歇训练',
};

export function parseHozz(payload: unknown): HozzProjection[] {
  const rows = z.array(z.record(z.string(), z.unknown())).min(1).max(5000).parse(payload);
  return rows.map((raw) => {
    if (raw.kind === 'typeCoverage') {
      const v = coverage.parse(raw);
      return {
        key: 'coverage:' + v.type,
        version: 0,
        deleted: false,
        raw,
        observedAt: new Date(v.observedAt),
        retained: false,
        coverage: true,
      };
    }
    const v = base.parse(raw),
      store = v.sourceRecord?.store || 'apple.healthkit';
    // This adapter only interprets Apple HealthKit records, not Android category enumerations.
    const key = store + ':' + v.id;
    if (v.canonicalId && v.canonicalId !== key) throw Error('HOZZ_IDENTITY');
    const result: HozzProjection = {
      key,
      version: v.recordVersion,
      deleted: v.kind === 'deletion',
      raw,
      retained: false,
      coverage: false,
    };
    if (result.deleted) return result;
    const timed = span.parse(raw);
    result.startTime = new Date(timed.startDate);
    result.endTime = new Date(timed.endDate);
    if (store !== 'apple.healthkit' || !['quantity', 'category', 'workout'].includes(v.kind)) {
      result.retained = true;
      return result;
    }
    const origin = source.parse(raw.source);
    const device =
      raw.device && typeof raw.device === 'object' ? (raw.device as Record<string, unknown>) : {};
    const deviceId =
      typeof device.localIdentifier === 'string' ? text.parse(device.localIdentifier) : undefined;
    const shared = {
      source: origin.name,
      sourceRecordId: key,
      deviceId,
      startTime: timed.startDate,
      endTime: timed.endDate,
    };
    if (v.kind === 'quantity') {
      const mapping = quantities[v.type];
      if (!mapping) {
        result.retained = true;
        return result;
      }
      const q = z
        .object({ value: z.number().finite().nonnegative(), unit: text })
        .passthrough()
        .parse(raw.quantity);
      const factor = mapping.factors[q.unit];
      // Unsupported units/cross-midnight totals are archived, not guessed or proportionally split.
      if (
        factor === undefined ||
        (['steps', 'active_energy'].includes(mapping.metric) &&
          localDay(result.startTime) !==
            localDay(new Date(Math.max(+result.startTime, +result.endTime - 1))))
      ) {
        result.retained = true;
        return result;
      }
      result.sample = sampleSchema.parse({
        ...shared,
        metricType: mapping.metric,
        unit: mapping.unit,
        value: q.value * factor,
      });
    } else if (v.kind === 'category' && v.type === 'HKCategoryTypeIdentifierSleepAnalysis') {
      const n = z.number().int().min(0).max(5).parse(raw.value);
      result.sample = sampleSchema.parse({
        ...shared,
        metricType: 'sleep_stage',
        unit: 'min',
        value: (+result.endTime - +result.startTime) / 60000,
        stage: stages[n],
      });
    } else if (v.kind === 'workout') {
      const w = z
        .object({
          activityType: z.number().int().nonnegative(),
          duration: z.number().finite().nonnegative(),
        })
        .parse(raw);
      result.workout = workoutSchema.parse({
        ...shared,
        workoutType: workoutTypes[w.activityType] || '运动类型 ' + w.activityType,
        durationMinutes: w.duration / 60,
      });
      // No invented workout calories: retain the full original record for future verified mapping.
    } else result.retained = true;
    return result;
  });
}
export const hozzIdentity = (key: string, entity: 'sample' | 'workout') =>
  fingerprint({ adapter: 'Hozz', key, entity });

export type SleepStageInterval = {
  startTime: Date;
  endTime: Date;
  source: string;
  metadata: unknown;
};
function union(intervals: [number, number][]) {
  const out: [number, number][] = [];
  for (const [start, end] of intervals.sort((a, b) => a[0] - b[0])) {
    const last = out.at(-1);
    if (last && start <= last[1]) last[1] = Math.max(last[1], end);
    else out.push([start, end]);
  }
  return out;
}
export function sleepSessions(rows: SleepStageInterval[]) {
  const groups = new Map<string, SleepStageInterval[]>();
  for (const row of rows) {
    const m = row.metadata as Record<string, unknown>;
    const key = fingerprint([row.source, m.sourceBundle ?? '', m.deviceId ?? '']);
    groups.set(key, [...(groups.get(key) || []), row]);
  }
  const sessions: {
    startTime: Date;
    endTime: Date;
    durationMinutes: number;
    source: string;
    fingerprint: string;
    metadata: { adapter: string; method: string };
  }[] = [];
  for (const [groupKey, group] of groups) {
    const sleeping = union(
      group
        .filter((r) =>
          ['asleep', 'core', 'deep', 'rem'].includes(
            String((r.metadata as Record<string, unknown>).stage),
          ),
        )
        .map((r) => [+r.startTime, +r.endTime]),
    );
    const awake = union(
      group
        .filter((r) => (r.metadata as Record<string, unknown>).stage === 'awake')
        .map((r) => [+r.startTime, +r.endTime]),
    );
    const effective: [number, number][] = [];
    for (const [a, b] of sleeping) {
      let cursor = a;
      for (const [c, d] of awake) {
        if (d <= cursor || c >= b) continue;
        if (c > cursor) effective.push([cursor, Math.min(c, b)]);
        cursor = Math.max(cursor, d);
        if (cursor >= b) break;
      }
      if (cursor < b) effective.push([cursor, b]);
    }
    const episodes: [number, number][][] = [];
    for (const interval of effective) {
      const last = episodes.at(-1);
      // Explicit grouping rule: stage gaps <=90 min stay in one session, but gaps are NOT sleep.
      if (last && interval[0] - last.at(-1)![1] <= 90 * 60000) last.push(interval);
      else episodes.push([interval]);
    }
    for (const episode of episodes) {
      const start = episode[0][0],
        end = episode.at(-1)![1];
      const asleepMs = episode.reduce((sum, [a, b]) => sum + (b - a), 0);
      if (asleepMs <= 0 || end - start > 86400000) continue;
      sessions.push({
        startTime: new Date(start),
        endTime: new Date(end),
        durationMinutes: asleepMs / 60000,
        source: group[0].source,
        fingerprint: fingerprint(['hozz-sleep', groupKey, start, end]),
        metadata: { adapter: 'Hozz', method: 'asleep-union-minus-awake;gap<=90min' },
      });
    }
  }
  return sessions;
}
