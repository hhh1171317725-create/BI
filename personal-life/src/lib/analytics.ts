import type { HealthSample, SleepSession, Workout, Prisma } from '@prisma/client';
import { db } from './db';
import {
  average,
  bedtimeMinutes,
  clock,
  dayRange,
  defaults,
  deviation,
  disjoint,
  localDay,
  selectSource,
  settingsSchema,
  TZ,
} from './domain';
export type Settings = typeof defaults;
export const readSettings = (value: unknown): Settings => {
  const result = settingsSchema.safeParse(value);
  return result.success ? result.data : defaults;
};
export function aggregate(
  samples: HealthSample[],
  sleep: SleepSession[],
  workouts: Workout[],
  settings: Settings,
) {
  const dates = [
    ...new Set([
      ...samples.map((s) =>
        ['steps', 'active_energy'].includes(s.metricType)
          ? localDay(new Date(Math.max(s.startTime.getTime(), s.endTime.getTime() - 1)))
          : localDay(s.endTime),
      ),
      ...sleep.map((s) => localDay(s.endTime)),
      ...workouts.map((s) => localDay(s.endTime)),
    ]),
  ].sort();
  return dates.map((date) => {
    const daily = samples.filter(
      (s) =>
        (['steps', 'active_energy'].includes(s.metricType)
          ? localDay(new Date(Math.max(s.startTime.getTime(), s.endTime.getTime() - 1)))
          : localDay(s.endTime)) === date,
    );
    const choose = (metric: string) =>
      selectSource(
        daily.filter((s) => s.metricType === metric),
        settings.sourcePriority,
      );
    const steps = disjoint(choose('steps')),
      energy = disjoint(choose('active_energy')),
      resting = choose('resting_heart_rate');
    const sessions = disjoint(
        selectSource(
          sleep.filter((s) => localDay(s.endTime) === date),
          settings.sourcePriority,
        ),
      ),
      training = disjoint(
        selectSource(
          workouts.filter((s) => localDay(s.endTime) === date),
          settings.sourcePriority,
        ),
      );
    const total = (rows: { value: number }[]) =>
      rows.length ? rows.reduce((sum, s) => sum + s.value, 0) : null;
    const metadata = {
      steps: steps[0]?.source ?? null,
      sleep: sessions[0]?.source ?? null,
      activeCalories: energy[0]?.source ?? null,
      restingHeartRate: resting[0]?.source ?? null,
      workouts: training[0]?.source ?? null,
      sourceConflict:
        new Set(daily.filter((s) => s.metricType === 'steps').map((s) => s.source)).size > 1,
      overlapExcluded: choose('steps').length - steps.length,
    };
    return {
      date,
      sleepMinutes: sessions.length ? sessions.reduce((a, s) => a + s.durationMinutes, 0) : null,
      steps: total(steps),
      restingHeartRate: average(resting.map((s) => s.value)),
      activeCalories: total(energy),
      workoutMinutes: training.length ? training.reduce((a, s) => a + s.durationMinutes, 0) : null,
      workoutCount: training.length || null,
      metadata,
    };
  });
}
export async function report(userId: string, start: string, end: string, settings: Settings) {
  const { from, until } = dayRange(start, end),
    where = { userId, endTime: { gte: from, lt: until } };
  const [allSamples, sleep, workouts, stageRows, heartRows] = await Promise.all([
    db.healthSample.findMany({
      where: {
        userId,
        metricType: { notIn: ['heart_rate', 'sleep_stage'] },
        endTime: { gte: from, lte: until },
      },
      orderBy: { endTime: 'asc' },
    }),
    db.sleepSession.findMany({ where, orderBy: { endTime: 'asc' } }),
    db.workout.findMany({ where, orderBy: { endTime: 'asc' } }),
    db.healthSample.findMany({
      where: {
        userId,
        metricType: 'sleep_stage',
        endTime: { gte: new Date(from.getTime() - 86400000), lt: until },
      },
      orderBy: { startTime: 'asc' },
    }),
    db.$queryRaw<
      {
        date: string;
        source: string;
        bpm: number;
        minimum: number;
        maximum: number;
        count: number;
      }[]
    >`SELECT to_char("endTime" AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS date, source, avg(value)::float8 AS bpm, min(value)::float8 AS minimum, max(value)::float8 AS maximum, count(*)::int AS count FROM "HealthSample" WHERE "userId"=${userId} AND "metricType"='heart_rate' AND "endTime">=${from} AND "endTime"<${until} GROUP BY date, source ORDER BY date`,
  ]);
  const samples = allSamples.filter(
    (s) => ['steps', 'active_energy'].includes(s.metricType) || s.endTime < until,
  );
  const measured = aggregate(samples, sleep, workouts, settings).filter(
    (d) => d.date >= start && d.date <= end,
  );
  // Explicit nulls make missing dates visible as chart gaps, never as invented zeroes.
  const days = Array.from(
    { length: Math.round((until.getTime() - from.getTime()) / 86400000) },
    (_, i) => {
      const date = localDay(new Date(from.getTime() + i * 86400000));
      return (
        measured.find((d) => d.date === date) ?? {
          date,
          sleepMinutes: null,
          steps: null,
          restingHeartRate: null,
          activeCalories: null,
          workoutMinutes: null,
          workoutCount: null,
          metadata: {
            steps: null,
            sleep: null,
            activeCalories: null,
            restingHeartRate: null,
            workouts: null,
            sourceConflict: false,
            overlapExcluded: 0,
          },
        }
      );
    },
  );
  const heartRate = [...new Set(heartRows.map((r) => r.date))].map((date) => {
    const rows = heartRows.filter((r) => r.date === date);
    const source = [...new Set(rows.map((r) => r.source))].sort((a, b) => {
      const rank = (s: string) =>
        settings.sourcePriority.includes(s)
          ? settings.sourcePriority.indexOf(s)
          : settings.sourcePriority.length;
      return rank(a) - rank(b) || a.localeCompare(b);
    })[0];
    return rows.find((r) => r.source === source)!;
  });
  const selectedSleep = days
    .flatMap((d) =>
      disjoint(
        selectSource(
          sleep.filter((s) => localDay(s.endTime) === d.date),
          settings.sourcePriority,
        ),
      ),
    )
    .sort((a, b) => a.endTime.getTime() - b.endTime.getTime());
  const wakeMinutes = selectedSleep.map((s) => {
    const [h, m] = clock(s.endTime).split(':').map(Number);
    return h * 60 + m;
  });
  const sleepMinutes = days.map((d) => d.sleepMinutes).filter((v): v is number => v !== null);
  const weekday = days.filter((d) => {
    const dow = new Date(d.date + 'T00:00:00Z').getUTCDay();
    return dow !== 0 && dow !== 6;
  });
  const bedtimeStd = deviation(selectedSleep.map((s) => bedtimeMinutes(clock(s.startTime))));
  const weights = selectWeight(
    samples.filter((s) => s.metricType === 'weight'),
    settings,
  );
  let streak = 0;
  for (const d of [...days].reverse()) {
    if (
      d.date !== localDay(new Date(new Date(end + 'T12:00:00Z').getTime() - streak * 86400000)) ||
      !d.workoutMinutes
    )
      break;
    streak++;
  }
  return {
    timezone: TZ,
    start,
    end,
    days,
    sleep: selectedSleep.map((s) => ({
      ...s,
      bedtime: clock(s.startTime),
      wakeTime: clock(s.endTime),
      date: localDay(s.endTime),
    })),
    stages: stageRows.filter(
      (r) =>
        r.endTime >= from ||
        selectedSleep.some(
          (s) => r.source === s.source && r.startTime < s.endTime && s.startTime < r.endTime,
        ),
    ),
    heartRate,
    workouts: days.flatMap((d) =>
      disjoint(
        selectSource(
          workouts.filter((s) => localDay(s.endTime) === d.date),
          settings.sourcePriority,
        ),
      ),
    ),
    weight: weights,
    analysis: {
      averageSleepMinutes: average(sleepMinutes),
      measuredSleepDays: sleepMinutes.length,
      bedtimeVariationMinutes: bedtimeStd,
      wakeVariationMinutes: deviation(wakeMinutes),
      regularity:
        bedtimeStd === null || selectedSleep.length < 3
          ? '数据不足'
          : bedtimeStd <= 30
            ? '较规律'
            : bedtimeStd <= 60
              ? '存在波动'
              : '波动较大',
      weekdaySleepMinutes: average(
        weekday.map((d) => d.sleepMinutes).filter((v): v is number => v !== null),
      ),
      weekendSleepMinutes: average(
        days
          .filter((d) => !weekday.includes(d))
          .map((d) => d.sleepMinutes)
          .filter((v): v is number => v !== null),
      ),
      shortSleepDays: days.filter(
        (d) => d.sleepMinutes !== null && d.sleepMinutes < settings.sleepMinHours * 60,
      ).length,
      workoutCount: days.reduce((s, d) => s + (d.workoutCount ?? 0), 0),
      activeStreak: streak,
    },
  };
}
// Refresh only dates touched by a batch; historical backfills do not scan a lifetime of data.
export async function rebuildDays(
  tx: Prisma.TransactionClient,
  userId: string,
  dates: string[],
  settings: Settings,
) {
  for (const date of [...new Set(dates)]) {
    const { from, until } = dayRange(date, date),
      where = { userId, endTime: { gte: from, lt: until } };
    const [samples, sleep, workouts] = await Promise.all([
      tx.healthSample.findMany({ where: { userId, endTime: { gte: from, lte: until } } }),
      tx.sleepSession.findMany({ where }),
      tx.workout.findMany({ where }),
    ]);
    const day = aggregate(samples, sleep, workouts, settings).find((d) => d.date === date);
    if (day) {
      const { workoutCount, ...values } = day;
      await tx.dailyHealthSummary.upsert({
        where: { userId_date: { userId, date } },
        create: { userId, ...values, metadata: { ...values.metadata, workoutCount } },
        update: { ...values, metadata: { ...values.metadata, workoutCount } },
      });
    }
  }
}
function selectWeight(rows: HealthSample[], settings: Settings) {
  const dates = [...new Set(rows.map((s) => localDay(s.endTime)))];
  return dates
    .map(
      (date) =>
        selectSource(
          rows.filter((s) => localDay(s.endTime) === date),
          settings.sourcePriority,
        ).sort((a, b) => b.endTime.getTime() - a.endTime.getTime())[0],
    )
    .filter(Boolean);
}
export async function syncStatus(userId: string) {
  const [lastUpload, lastSuccess, lastFailure, latest, logs, imports, pending] = await Promise.all([
    db.syncLog.findFirst({ where: { userId }, orderBy: { createdAt: 'desc' } }),
    db.syncLog.findFirst({
      where: { userId, status: 'processed' },
      orderBy: { createdAt: 'desc' },
    }),
    db.syncLog.findFirst({ where: { userId, status: 'failed' }, orderBy: { createdAt: 'desc' } }),
    db.healthSample.findFirst({ where: { userId }, orderBy: { endTime: 'desc' } }),
    db.syncLog.findMany({ where: { userId }, orderBy: { createdAt: 'desc' }, take: 50 }),
    db.healthRawImport.findMany({
      where: { userId },
      select: { id: true, source: true, receivedAt: true, processStatus: true, errorMessage: true },
      orderBy: { receivedAt: 'desc' },
      take: 50,
    }),
    db.healthRawImport.count({ where: { userId, processStatus: 'awaiting_mapping' } }),
  ]);
  const latestWorkout = await db.workout.findFirst({
    where: { userId },
    orderBy: { endTime: 'desc' },
  });
  const latestRecord =
    latestWorkout && (!latest || latestWorkout.endTime > latest.endTime) ? latestWorkout : latest;
  const uploadAge = lastUpload ? (Date.now() - lastUpload.createdAt.getTime()) / 3600000 : null,
    measurementAge = latestRecord ? (Date.now() - latestRecord.endTime.getTime()) / 3600000 : null;
  return {
    lastUpload,
    lastSuccess,
    lastFailure,
    latestMeasurement: latestRecord
      ? {
          date: localDay(
            'metricType' in latestRecord &&
              ['steps', 'active_energy'].includes(latestRecord.metricType)
              ? new Date(
                  Math.max(latestRecord.startTime.getTime(), latestRecord.endTime.getTime() - 1),
                )
              : latestRecord.endTime,
          ),
          time: latestRecord.endTime,
          source: latestRecord.source,
        }
      : null,
    uploadAgeHours: uploadAge,
    measurementAgeHours: measurementAge,
    unsyncedDays: uploadAge === null ? null : Math.floor(uploadAge / 24),
    uploadDelayed: uploadAge === null || uploadAge > 24,
    pendingMapping: pending,
    logs,
    imports,
  };
}
