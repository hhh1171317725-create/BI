import { Prisma } from '@prisma/client';
import { db } from './db';
import { fingerprint, normalizedSchema, sampleIdentity, localDay } from './domain';
import { readSettings, rebuildDays } from './analytics';
import { ApiError } from './security';
import { isHozz, parseHozz, type HozzProjection } from './hozz';
import { storeHozz } from './hozz-store';
type Tx = Prisma.TransactionClient;
export async function lockUser(tx: Tx, userId: string) {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${userId}))`;
}
export function validateRaw(payload: unknown) {
  if (
    !payload ||
    typeof payload !== 'object' ||
    (Array.isArray(payload) && payload.length === 0) ||
    (!Array.isArray(payload) && !Object.keys(payload).length)
  )
    throw new ApiError(400, 'EMPTY', '需要非空 JSON 对象或数组');
  function walk(value: unknown, depth = 0) {
    if (depth > 25) throw new ApiError(400, 'DEPTH', 'JSON 嵌套过深');
    if (value && typeof value === 'object')
      for (const v of Object.values(value)) walk(v, depth + 1);
  }
  walk(payload);
  return payload as Prisma.InputJsonValue;
}
export async function ingest(
  userId: string,
  payload: unknown,
  reprocessId?: string,
  requestedSource?: string,
) {
  const json = validateRaw(payload),
    source = requestedSource || (isHozz(payload) ? 'Hozz' : 'Health Auto Export'),
    payloadHash = fingerprint(json);
  let hozz: HozzProjection[] | null = null,
    hozzError: string | null = null;
  const hozzInput = source === 'Hozz' || isHozz(payload);
  if (hozzInput) {
    try {
      hozz = parseHozz(payload);
    } catch {
      hozzError =
        Array.isArray(payload) &&
        payload.some(
          (v) =>
            v && typeof v === 'object' && Number.isInteger(v.schemaVersion) && v.schemaVersion > 1,
        )
          ? '未知 Hozz schemaVersion，原始数据已保留，等待适配'
          : 'Hozz JSON 字段、单位或时间范围无效，请检查导出格式与版本';
    }
  }
  const isNormalized = (json as Record<string, unknown>).format === 'normalized-v1';
  const enabled = process.env.ALLOW_NORMALIZED_INGEST === 'true';
  if (isNormalized && !enabled) throw new ApiError(422, 'ADAPTER_DISABLED', '规范化适配器尚未启用');
  const parsed = isNormalized ? normalizedSchema.safeParse(json) : null;
  const receivedCount =
    hozzInput && Array.isArray(payload)
      ? payload.length
      : parsed?.success
        ? parsed.data.samples.length + parsed.data.workouts.length
        : 0;
  return db.$transaction(
    async (tx) => {
      await lockUser(tx, userId);
      const raw = await tx.healthRawImport.upsert({
        where: { userId_source_payloadHash: { userId, source, payloadHash } },
        create: { userId, source, payloadHash, payload: json },
        update: {},
      });
      if (reprocessId && raw.id !== reprocessId)
        throw new ApiError(409, 'IMPORT', '原始批次已变化');
      let status = 'awaiting_mapping',
        errorMessage: string | null = null,
        insertedCount = 0,
        duplicateCount = 0;
      let retainedCount = 0;
      if (hozzError) {
        status = hozzError.startsWith('未知') ? 'awaiting_mapping' : 'failed';
        errorMessage = hozzError;
      } else if (hozz) {
        const stored = await storeHozz(tx, userId, hozz, !!reprocessId);
        insertedCount = stored.insertedCount;
        duplicateCount = stored.duplicateCount;
        retainedCount = stored.retainedCount;
        status = retainedCount ? 'processed_with_gaps' : 'processed';
        if (retainedCount) errorMessage = '部分类型、单位或跨日累计记录仅留存原始数据，未计入指标';
        if (stored.dates.length) {
          const user = await tx.user.findUniqueOrThrow({ where: { id: userId } });
          await rebuildDays(tx, userId, stored.dates, readSettings(user.settings));
        }
      } else if (parsed && !parsed.success) {
        status = 'failed';
        errorMessage = '规范化测试格式不符合契约，请核对单位、时间范围及必填字段';
      } else if (parsed?.success) {
        const samples = parsed.data.samples.map((v) => ({
          userId,
          metricType: v.metricType,
          value: v.value,
          unit: v.unit,
          startTime: new Date(v.startTime),
          endTime: new Date(v.endTime),
          source: v.source,
          sourceRecordId: v.sourceRecordId,
          fingerprint: sampleIdentity(v),
          metadata: {
            ...(v.deviceId ? { deviceId: v.deviceId } : {}),
            ...(v.stage ? { stage: v.stage } : {}),
          },
        }));
        const sleeps = parsed.data.samples
          .filter((v) => v.metricType === 'sleep_duration')
          .map((v) => ({
            userId,
            startTime: new Date(v.startTime),
            endTime: new Date(v.endTime),
            durationMinutes: v.value,
            source: v.source,
            fingerprint: sampleIdentity(v),
            metadata: v.deviceId ? { deviceId: v.deviceId } : {},
          }));
        const workouts = parsed.data.workouts.map((v) => ({
          userId,
          workoutType: v.workoutType,
          startTime: new Date(v.startTime),
          endTime: new Date(v.endTime),
          durationMinutes: v.durationMinutes,
          calories: v.calories,
          source: v.source,
          fingerprint: fingerprint({
            ...v,
            startTime: new Date(v.startTime).toISOString(),
            endTime: new Date(v.endTime).toISOString(),
          }),
          metadata: {
            ...(v.sourceRecordId ? { sourceRecordId: v.sourceRecordId } : {}),
            ...(v.deviceId ? { deviceId: v.deviceId } : {}),
          },
        }));
        const s = await tx.healthSample.createMany({ data: samples, skipDuplicates: true }),
          w = await tx.workout.createMany({ data: workouts, skipDuplicates: true });
        await tx.sleepSession.createMany({ data: sleeps, skipDuplicates: true });
        insertedCount = s.count + w.count;
        duplicateCount = receivedCount - insertedCount;
        status = 'processed';
        if (insertedCount) {
          const user = await tx.user.findUniqueOrThrow({ where: { id: userId } });
          const dates = [
            ...samples.map((s) =>
              localDay(
                ['steps', 'active_energy'].includes(s.metricType)
                  ? new Date(Math.max(s.startTime.getTime(), s.endTime.getTime() - 1))
                  : s.endTime,
              ),
            ),
            ...workouts.map((w) => localDay(w.endTime)),
          ];
          await rebuildDays(tx, userId, dates, readSettings(user.settings));
        }
      }
      await tx.healthRawImport.update({
        where: { id: raw.id },
        data: { processStatus: status, errorMessage },
      });
      await tx.syncLog.create({
        data: {
          userId,
          source,
          status,
          receivedCount,
          insertedCount,
          duplicateCount,
          retainedCount,
          errorMessage,
        },
      });
      return {
        importId: raw.id,
        status,
        retainedCount,
        receivedCount: isNormalized || hozzInput ? receivedCount : null,
        insertedCount,
        duplicateCount,
        message:
          status === 'awaiting_mapping'
            ? '已安全保存原始批次，待脱敏真实样本确认映射后处理'
            : (errorMessage ?? '批次已处理'),
      };
    },
    { timeout: 60000 },
  );
}
