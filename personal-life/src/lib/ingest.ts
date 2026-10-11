import { Prisma } from '@prisma/client';
import { db } from './db';
import { fingerprint, normalizedSchema, sampleIdentity, localDay } from './domain';
import { readSettings, rebuildDays } from './analytics';
import { ApiError } from './security';
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
export async function ingest(userId: string, payload: unknown, reprocessId?: string) {
  const json = validateRaw(payload),
    source = 'Health Auto Export',
    payloadHash = fingerprint(json);
  const isNormalized = (json as Record<string, unknown>).format === 'normalized-v1';
  const enabled = process.env.ALLOW_NORMALIZED_INGEST === 'true';
  if (isNormalized && !enabled) throw new ApiError(422, 'ADAPTER_DISABLED', '规范化适配器尚未启用');
  const parsed = isNormalized ? normalizedSchema.safeParse(json) : null;
  const receivedCount = parsed?.success
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
      if (parsed && !parsed.success) {
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
          errorMessage,
        },
      });
      return {
        importId: raw.id,
        status,
        receivedCount: isNormalized ? receivedCount : null,
        insertedCount,
        duplicateCount,
        message:
          status === 'awaiting_mapping'
            ? '已安全保存原始批次，待脱敏真实样本确认映射后处理'
            : (errorMessage ?? '批次已处理'),
      };
    },
    { timeout: 30000 },
  );
}
