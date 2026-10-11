import { z } from 'zod';
import { randomBytes } from 'node:crypto';
import { Prisma } from '@prisma/client';
import { db } from './db';
import {
  ApiError,
  cookie,
  enforceTransport,
  hash,
  ingestUser,
  passwordHash,
  rateLimit,
  readJson,
  sameOrigin,
  userFromRequest,
  verifyPassword,
} from './security';
import { daysBack, localDay, settingsSchema } from './domain';
import { readSettings, report, syncStatus, rebuildDays } from './analytics';
import { ingest, lockUser } from './ingest';
import { answer, healthContext } from './assistant';
import { exportResponse } from './export';
import { isHozz, probeSchema } from './hozz';
const json = (data: unknown, status = 200, headers: Record<string, string> = {}) =>
  Response.json(data, { status, headers: { 'Cache-Control': 'no-store', ...headers } });
const loginSchema = z
  .object({ username: z.string().min(1).max(64), password: z.string().min(1).max(200) })
  .strict();
const fallbackPassword = passwordHash(randomBytes(32).toString('hex'));
function importResponse(result: Awaited<ReturnType<typeof ingest>>, requestId: string) {
  if (result.status === 'failed')
    return json(
      { ...result, error: { code: 'INGEST_VALIDATION', message: result.message, requestId } },
      422,
    );
  return json(result, result.status === 'awaiting_mapping' ? 202 : 200);
}
export async function handle(request: Request) {
  const requestId = randomBytes(8).toString('hex');
  let ingestOwnerId: string | undefined;
  let ingestSource = 'Health Auto Export';
  try {
    const route = new URL(request.url).pathname,
      method = request.method;
    if (route === '/api/healthz' && method === 'GET') {
      await db.$queryRaw`SELECT 1`;
      return json({ status: 'ok' });
    }
    enforceTransport(request);
    if (route === '/api/health/ingest' && method === 'POST') {
      const user = await ingestUser(request);
      ingestOwnerId = user.id;
      ingestSource = request.headers.has('hozz-format') ? 'Hozz' : 'Health Auto Export';
      await rateLimit('ingest:' + user.id, 60);
      const payload = await readJson(
        request,
        Math.min(Number(process.env.MAX_INGEST_BYTES) || 5242880, 10485760),
      );
      ingestSource =
        request.headers.has('hozz-format') || isHozz(payload) ? 'Hozz' : 'Health Auto Export';
      // An authenticated connectivity probe contains no health measurements. Never seed the DB.
      if (probeSchema.safeParse(payload).success)
        return json({ ok: true, status: 'connection_ok' });
      const result = await ingest(user.id, payload, undefined, ingestSource);
      return importResponse(result, requestId);
    }
    if (route === '/api/auth/login' && method === 'POST') {
      sameOrigin(request);
      const input = loginSchema.parse(await readJson(request));
      await rateLimit('login-global', 30);
      await rateLimit('login:' + hash(input.username), 8, 900000);
      const user = await db.user.findUnique({ where: { username: input.username } });
      if (!verifyPassword(input.password, user?.passwordHash ?? fallbackPassword) || !user)
        throw new ApiError(401, 'LOGIN', '用户名或密码不正确');
      const token = randomBytes(32).toString('hex');
      await db.session.deleteMany({ where: { expiresAt: { lt: new Date() } } });
      await db.session.create({
        data: {
          tokenHash: hash(token),
          userId: user.id,
          expiresAt: new Date(Date.now() + 7 * 86400000),
        },
      });
      return json({ ok: true }, 200, { 'Set-Cookie': cookie(token, 604800) });
    }
    const user = await userFromRequest(request);
    await rateLimit('read:' + user.id, 180);
    if (method !== 'GET') sameOrigin(request);
    if (route === '/api/auth/logout' && method === 'POST') {
      const raw = request.headers
        .get('cookie')
        ?.split(';')
        .map((v) => v.trim())
        .find((v) => v.startsWith('life_session='))
        ?.slice(13);
      if (raw) await db.session.deleteMany({ where: { tokenHash: hash(raw) } });
      return json({ ok: true }, 200, { 'Set-Cookie': cookie('', 0) });
    }
    if (route === '/api/settings') {
      if (method === 'GET')
        return json({ username: user.username, settings: readSettings(user.settings) });
      if (method === 'PUT') {
        const settings = settingsSchema.parse(await readJson(request));
        await db.$transaction(
          async (tx) => {
            await lockUser(tx, user.id);
            await tx.user.update({ where: { id: user.id }, data: { settings } });
            const dates = await tx.dailyHealthSummary.findMany({
              where: { userId: user.id },
              select: { date: true },
            });
            await rebuildDays(
              tx,
              user.id,
              dates.map((d) => d.date),
              settings,
            );
          },
          { timeout: 60000 },
        );
        return json({ settings });
      }
    }
    if (route === '/api/health/sync-status' && method === 'GET')
      return json(await syncStatus(user.id));
    if (route === '/api/health/reprocess' && method === 'POST') {
      const { importId } = z
        .object({ importId: z.string().max(100) })
        .strict()
        .parse(await readJson(request));
      const raw = await db.healthRawImport.findFirst({ where: { id: importId, userId: user.id } });
      if (!raw) throw new ApiError(404, 'IMPORT', '批次不存在');
      const result = await ingest(user.id, raw.payload, raw.id, raw.source);
      return importResponse(result, requestId);
    }
    if (route === '/api/export' && method === 'GET') {
      await rateLimit('export:' + user.id, 2, 3600000);
      return exportResponse(user.id);
    }
    if (route === '/api/my-data' && method === 'DELETE') {
      const { password, confirmation } = z
        .object({ password: z.string().max(200), confirmation: z.literal('DELETE_MY_HEALTH_DATA') })
        .strict()
        .parse(await readJson(request));
      if (!verifyPassword(password, user.passwordHash))
        throw new ApiError(403, 'PASSWORD', '密码验证失败');
      await db.$transaction(async (tx) => {
        await lockUser(tx, user.id);
        const where = { userId: user.id };
        await tx.healthRawImport.deleteMany({ where });
        await tx.healthSourceRecord.deleteMany({ where });
        await tx.healthSample.deleteMany({ where });
        await tx.sleepSession.deleteMany({ where });
        await tx.workout.deleteMany({ where });
        await tx.dailyHealthSummary.deleteMany({ where });
        await tx.syncLog.deleteMany({ where });
        await tx.aIConversation.deleteMany({ where });
        await tx.session.deleteMany({ where });
        await tx.user.update({
          where: { id: user.id },
          data: { settings: { ...readSettings(user.settings), aiConsent: false } },
        });
      });
      return json({ ok: true }, 200, { 'Set-Cookie': cookie('', 0) });
    }
    if (route === '/api/assistant/history' && method === 'GET')
      return json({
        messages: await db.aIConversation
          .findMany({ where: { userId: user.id }, orderBy: { createdAt: 'desc' }, take: 50 })
          .then((rows) => rows.reverse()),
      });
    if (route === '/api/assistant/chat' && method === 'POST') {
      const askedAt = new Date();
      await rateLimit('ai:' + user.id, 5);
      const input = z
        .object({ message: z.string().trim().min(1).max(1500), consent: z.literal(true) })
        .strict()
        .parse(await readJson(request));
      if (!readSettings(user.settings).aiConsent)
        throw new ApiError(403, 'AI_CONSENT', '请先在设置中授权 AI 使用必要摘要');
      const context = await healthContext(user.id, input.message, user.settings),
        content = await answer(input.message, context);
      // Re-check session after the external call: deleting data revokes all sessions.
      await db.$transaction(async (tx) => {
        await lockUser(tx, user.id);
        const current = await userFromRequest(request);
        if (!readSettings(current.settings).aiConsent)
          throw new ApiError(403, 'AI_CONSENT', 'AI 授权已撤销');
        await tx.aIConversation.createMany({
          data: [
            { userId: user.id, role: 'user', content: input.message, createdAt: askedAt },
            {
              userId: user.id,
              role: 'assistant',
              content,
              createdAt: new Date(Math.max(Date.now(), askedAt.getTime() + 1)),
            },
          ],
        });
      });
      return json({ content, range: context.range });
    }
    if (route.startsWith('/api/health/') && method === 'GET') {
      const params = new URL(request.url).searchParams,
        range = daysBack(30);
      const start = params.get('start') || range.start,
        end = params.get('end') || range.end;
      let data;
      try {
        data = await report(user.id, start, end, readSettings(user.settings));
      } catch (e) {
        if (e instanceof z.ZodError || (e instanceof Error && e.message.startsWith('日期范围')))
          throw new ApiError(400, 'DATE', '日期格式或范围无效（最多 367 天）');
        throw e;
      }
      if (route === '/api/health/summary') {
        const weekStart = localDay(new Date(new Date(end + 'T12:00:00Z').getTime() - 6 * 86400000));
        const week =
          data.start <= weekStart
            ? data
            : await report(user.id, weekStart, end, readSettings(user.settings));
        return json({
          ...data,
          recentWeek: {
            days: week.days.filter((d) => d.date >= weekStart),
            sleep: week.sleep.filter((s) => s.date >= weekStart),
          },
          sync: await syncStatus(user.id),
          settings: readSettings(user.settings),
        });
      }
      if (route === '/api/health/sleep')
        return json({
          sessions: data.sleep,
          stages: data.stages,
          days: data.days,
          analysis: data.analysis,
        });
      if (route === '/api/health/activity')
        return json({ days: data.days, analysis: data.analysis });
      if (route === '/api/health/workouts') return json({ workouts: data.workouts });
      if (route === '/api/health/weight') return json({ weight: data.weight });
    }
    throw new ApiError(404, 'NOT_FOUND', '接口不存在');
  } catch (error) {
    const known = error instanceof ApiError,
      status = known ? error.status : error instanceof z.ZodError ? 400 : 503,
      code = known
        ? error.code
        : error instanceof z.ZodError
          ? 'VALIDATION'
          : 'SERVICE_UNAVAILABLE';
    if (ingestOwnerId && status !== 429)
      await db.syncLog
        .create({
          data: {
            userId: ingestOwnerId,
            source: ingestSource,
            status: 'failed',
            receivedCount: 0,
            insertedCount: 0,
            duplicateCount: 0,
            errorMessage: '接收失败（' + code + '），请核对配置后重发',
          },
        })
        .catch(() => {});
    // No payloads, URL query strings, usernames, credentials or error stacks in ordinary logs.
    console.error(JSON.stringify({ event: 'api_error', requestId, status, code }));
    return json(
      {
        error: {
          code,
          message: known
            ? error.message
            : error instanceof z.ZodError
              ? '请求字段或数据格式不符合要求'
              : '服务暂时不可用，请稍后重试',
          requestId,
        },
      },
      status,
    );
  }
}
