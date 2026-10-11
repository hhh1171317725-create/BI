import { Prisma } from '@prisma/client';
import { localDay } from './domain';
import { hozzIdentity, sleepSessions, type HozzProjection } from './hozz';

type Tx = Prisma.TransactionClient;
export async function storeHozz(
  tx: Tx,
  userId: string,
  records: HozzProjection[],
  reprocess = false,
) {
  const newest = new Map<string, HozzProjection>();
  for (const r of records) {
    const previous = newest.get(r.key);
    if (
      !previous ||
      (r.coverage
        ? +r.observedAt! > +previous.observedAt!
        : r.version > previous.version || (r.version === previous.version && r.deleted))
    )
      newest.set(r.key, r);
  }
  const existing = await tx.healthSourceRecord.findMany({
    where: { userId, source: 'Hozz', recordId: { in: [...newest.keys()] } },
  });
  const byId = new Map(existing.map((r) => [r.recordId, r]));
  const changed = [...newest.values()].filter((r) => {
    const old = byId.get(r.key);
    return (
      !old ||
      (r.coverage
        ? +r.observedAt! > +(old.observedAt || 0)
        : r.version > old.recordVersion ||
          (reprocess && r.version === old.recordVersion && !old.deleted && !r.deleted))
    );
  });
  const dates = new Set<string>(),
    sleepWindows: [number, number][] = [];
  function touched(start?: Date | null, end?: Date | null, type?: string) {
    if (!start || !end) return;
    dates.add(localDay(end));
    dates.add(localDay(new Date(Math.max(+start, +end - 1))));
    if (type === 'HKCategoryTypeIdentifierSleepAnalysis')
      sleepWindows.push([+start - 86400000, +end + 86400000]);
  }
  for (const r of changed) {
    const old = byId.get(r.key);
    touched(
      old?.startTime,
      old?.endTime,
      (old?.payload as Record<string, unknown>)?.type as string | undefined,
    );
    touched(r.startTime, r.endTime, r.raw.type as string);
  }
  // Stable projection keys permit replacement/deletion without adding a second measurement.
  const sampleKeys = changed.map((r) => hozzIdentity(r.key, 'sample'));
  const workoutKeys = changed.map((r) => hozzIdentity(r.key, 'workout'));
  await tx.healthSample.deleteMany({ where: { userId, fingerprint: { in: sampleKeys } } });
  await tx.workout.deleteMany({ where: { userId, fingerprint: { in: workoutKeys } } });
  const data = (r: HozzProjection) => ({
    userId,
    source: 'Hozz',
    recordId: r.key,
    recordType: String(r.raw.type),
    recordVersion: r.version,
    deleted: r.deleted,
    payload: r.raw as Prisma.InputJsonValue,
    startTime: r.startTime ?? null,
    endTime: r.endTime ?? null,
    observedAt: r.observedAt ?? null,
  });
  await tx.healthSourceRecord.createMany({
    data: changed.filter((r) => !byId.has(r.key)).map(data),
  });
  for (const r of changed.filter((r) => byId.has(r.key)))
    await tx.healthSourceRecord.update({
      where: { userId_source_recordId: { userId, source: 'Hozz', recordId: r.key } },
      data: data(r),
    });
  const metadata = (r: HozzProjection) =>
    ({
      adapter: 'Hozz',
      hozzId: r.key,
      recordVersion: r.version,
      sourceBundle:
        ((r.raw.source as Record<string, unknown> | undefined)?.bundleIdentifier as string) ?? '',
      ...(r.sample?.deviceId || r.workout?.deviceId
        ? { deviceId: r.sample?.deviceId || r.workout?.deviceId }
        : {}),
      ...(r.sample?.stage ? { stage: r.sample.stage } : {}),
    }) as Prisma.InputJsonValue;
  await tx.healthSample.createMany({
    data: changed
      .filter((r) => r.sample && !r.deleted)
      .map((r) => ({
        userId,
        metricType: r.sample!.metricType,
        value: r.sample!.value,
        unit: r.sample!.unit,
        startTime: r.startTime!,
        endTime: r.endTime!,
        source: r.sample!.source,
        sourceRecordId: r.key,
        fingerprint: hozzIdentity(r.key, 'sample'),
        metadata: metadata(r),
      })),
  });
  await tx.workout.createMany({
    data: changed
      .filter((r) => r.workout && !r.deleted)
      .map((r) => ({
        userId,
        workoutType: r.workout!.workoutType,
        startTime: r.startTime!,
        endTime: r.endTime!,
        durationMinutes: r.workout!.durationMinutes,
        source: r.workout!.source,
        fingerprint: hozzIdentity(r.key, 'workout'),
        metadata: metadata(r),
      })),
  });
  // Merge only nearby affected windows; a history backfill must not reload all lifetime sleep data.
  const windows: [number, number][] = [];
  for (const [a, b] of sleepWindows.sort((x, y) => x[0] - y[0])) {
    const last = windows.at(-1);
    if (last && a <= last[1]) last[1] = Math.max(last[1], b);
    else windows.push([a, b]);
  }
  for (const [a, b] of windows) {
    const overlap = { startTime: { lte: new Date(b) }, endTime: { gte: new Date(a) } };
    const adapter = { path: ['adapter'], equals: 'Hozz' };
    const previous = await tx.sleepSession.findMany({
      where: { userId, ...overlap, metadata: adapter },
      select: { endTime: true },
    });
    previous.forEach((r) => dates.add(localDay(r.endTime)));
    await tx.sleepSession.deleteMany({ where: { userId, ...overlap, metadata: adapter } });
    const stages = await tx.healthSample.findMany({
      where: { userId, metricType: 'sleep_stage', ...overlap, metadata: adapter },
    });
    const sessions = sleepSessions(stages);
    sessions.forEach((r) => dates.add(localDay(r.endTime)));
    await tx.sleepSession.createMany({
      data: sessions.map((s) => ({ userId, ...s })),
      skipDuplicates: true,
    });
  }
  return {
    dates: [...dates],
    insertedCount: changed.length,
    duplicateCount: records.length - changed.length,
    retainedCount: records.filter((r) => r.retained).length,
  };
}
