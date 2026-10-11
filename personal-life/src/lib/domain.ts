import { z } from 'zod';
import { createHash } from 'node:crypto';
import { formatInTimeZone, fromZonedTime } from 'date-fns-tz';
export const TZ = 'Asia/Shanghai';
export const localDay = (date: Date | string) => formatInTimeZone(date, TZ, 'yyyy-MM-dd');
export const clock = (date: Date | string) => formatInTimeZone(date, TZ, 'HH:mm');
const time = z.iso.datetime({ offset: true });
export const metric = z.enum([
  'sleep_duration',
  'sleep_stage',
  'heart_rate',
  'resting_heart_rate',
  'steps',
  'active_energy',
  'weight',
]);
const units = {
  sleep_duration: 'min',
  sleep_stage: 'min',
  heart_rate: 'bpm',
  resting_heart_rate: 'bpm',
  steps: 'count',
  active_energy: 'kcal',
  weight: 'kg',
} as const;
export const sampleSchema = z
  .object({
    metricType: metric,
    value: z.number().finite().nonnegative(),
    unit: z.string().max(20),
    startTime: time,
    endTime: time,
    source: z.string().trim().min(1).max(100),
    sourceRecordId: z.string().min(1).max(200).optional(),
    deviceId: z.string().max(200).optional(),
    stage: z.enum(['awake', 'core', 'deep', 'rem', 'asleep', 'in_bed']).optional(),
  })
  .strict()
  .superRefine((v, ctx) => {
    if (v.unit !== units[v.metricType]) ctx.addIssue({ code: 'custom', message: '不支持的单位' });
    if (Date.parse(v.endTime) < Date.parse(v.startTime))
      ctx.addIssue({ code: 'custom', message: '时间区间无效' });
    if (v.metricType === 'sleep_stage' && !v.stage)
      ctx.addIssue({ code: 'custom', message: '缺少睡眠阶段' });
    if (
      v.metricType.startsWith('sleep') &&
      (v.value > (Date.parse(v.endTime) - Date.parse(v.startTime)) / 60000 + 0.01 || v.value > 1440)
    )
      ctx.addIssue({ code: 'custom', message: '睡眠时长超出区间' });
    if (
      ['steps', 'active_energy'].includes(v.metricType) &&
      localDay(v.startTime) !==
        localDay(new Date(Math.max(Date.parse(v.startTime), Date.parse(v.endTime) - 1)))
    )
      ctx.addIssue({ code: 'custom', message: '累计指标需按本地日拆分，避免错误分摊' });
  });
export const workoutSchema = z
  .object({
    workoutType: z.string().min(1).max(100),
    startTime: time,
    endTime: time,
    durationMinutes: z.number().finite().nonnegative().max(1440),
    calories: z.number().finite().nonnegative().optional(),
    source: z.string().min(1).max(100),
    sourceRecordId: z.string().max(200).optional(),
    deviceId: z.string().max(200).optional(),
  })
  .strict()
  .refine(
    (v) =>
      Date.parse(v.endTime) >= Date.parse(v.startTime) &&
      v.durationMinutes <= (Date.parse(v.endTime) - Date.parse(v.startTime)) / 60000 + 0.01,
  );
// Our explicit adapter contract. This does NOT claim to be Health Auto Export's wire format.
export const normalizedSchema = z
  .object({
    format: z.literal('normalized-v1'),
    samples: z.array(sampleSchema).max(2000).default([]),
    workouts: z.array(workoutSchema).max(500).default([]),
  })
  .strict()
  .refine((v) => v.samples.length + v.workouts.length > 0, '没有记录');
export type SampleInput = z.infer<typeof sampleSchema>;
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object')
    return (
      '{' +
      Object.entries(value)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([k, v]) => JSON.stringify(k) + ':' + canonical(v))
        .join(',') +
      '}'
    );
  return JSON.stringify(value) ?? 'null';
}
export const fingerprint = (value: unknown) =>
  createHash('sha256').update(canonical(value)).digest('hex');
export function sampleIdentity(v: SampleInput) {
  return fingerprint({
    ...v,
    startTime: new Date(v.startTime).toISOString(),
    endTime: new Date(v.endTime).toISOString(),
  });
}
export const settingsSchema = z
  .object({
    bedtime: z
      .string()
      .regex(/^([01]\d|2[0-3]):[0-5]\d$/)
      .default('23:30'),
    bedtimeLatest: z
      .string()
      .regex(/^([01]\d|2[0-3]):[0-5]\d$/)
      .default('00:00'),
    wakeTime: z
      .string()
      .regex(/^([01]\d|2[0-3]):[0-5]\d$/)
      .default('07:30'),
    sleepMinHours: z.number().min(4).max(12).default(7.5),
    sleepMaxHours: z.number().min(4).max(14).default(8.5),
    heightCm: z.number().min(80).max(250).default(168),
    referenceWeightKg: z.number().min(20).max(300).default(67),
    goal: z.string().max(100).default('全身减脂塑形'),
    preference: z.string().max(100).default('居家训练'),
    sourcePriority: z
      .array(z.string().min(1).max(100))
      .max(20)
      .default(['Garmin', 'Apple Health', 'iPhone']),
    aiConsent: z.boolean().default(false),
  })
  .strict()
  .refine((v) => v.sleepMinHours <= v.sleepMaxHours);
export const defaults = settingsSchema.parse({});
export function dayRange(start: string, end: string) {
  const valid = z.iso.date();
  valid.parse(start);
  valid.parse(end);
  const from = fromZonedTime(start + 'T00:00:00', TZ),
    until = new Date(fromZonedTime(end + 'T00:00:00', TZ).getTime() + 86400000);
  if (from >= until || (until.getTime() - from.getTime()) / 86400000 > 367)
    throw Error('日期范围需为 1–367 天');
  return { from, until };
}
export function daysBack(days: number, now = new Date()) {
  const end = localDay(now);
  return { start: localDay(new Date(now.getTime() - (days - 1) * 86400000)), end };
}
export type Timed = { source: string; startTime: Date; endTime: Date; createdAt?: Date };
// Select one source per metric/day; never add Garmin and iPhone totals together.
export function selectSource<T extends Timed>(rows: T[], priority: string[]): T[] {
  if (!rows.length) return [];
  const sources = [...new Set(rows.map((r) => r.source))].sort((a, b) => {
    const rank = (s: string) => {
      const i = priority.indexOf(s);
      return i < 0 ? priority.length : i;
    };
    return rank(a) - rank(b) || a.localeCompare(b);
  });
  return rows.filter((r) => r.source === sources[0]);
}
// Prefer latest records on overlap, retain disjoint intervals. No blind sum of daily + hourly totals.
export function disjoint<T extends Timed>(rows: T[]): T[] {
  const chosen: T[] = [];
  for (const row of [...rows].sort(
    (a, b) =>
      (b.createdAt?.getTime() || 0) - (a.createdAt?.getTime() || 0) ||
      b.endTime.getTime() - b.startTime.getTime() - (a.endTime.getTime() - a.startTime.getTime()),
  ))
    if (
      !chosen.some(
        (r) =>
          (r.startTime < row.endTime && row.startTime < r.endTime) ||
          (r.startTime.getTime() === row.startTime.getTime() &&
            r.endTime.getTime() === row.endTime.getTime()),
      )
    )
      chosen.push(row);
  return chosen;
}
export function average(values: number[]) {
  return values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
}
export function deviation(values: number[]) {
  const mean = average(values);
  return mean === null
    ? null
    : Math.sqrt(values.reduce((sum, v) => sum + (v - mean) ** 2, 0) / values.length);
}
export function bedtimeMinutes(time: string) {
  const [h, m] = time.split(':').map(Number);
  return (h < 12 ? h + 24 : h) * 60 + m;
}
