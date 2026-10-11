import { Prisma } from '@prisma/client';
import { db } from './db';

/** Stream a consistent database snapshot in pages, without loading a lifetime of samples into RAM. */
export function exportResponse(userId: string) {
  const encoder = new TextEncoder(),
    stream = new TransformStream<Uint8Array, Uint8Array>(),
    writer = stream.writable.getWriter();
  void (async () => {
    const write = (text: string) => writer.write(encoder.encode(text));
    try {
      await db.$transaction(
        async (tx) => {
          const user = await tx.user.findUniqueOrThrow({ where: { id: userId } });
          await write(
            '{"version":1,"exportedAt":' +
              JSON.stringify(new Date().toISOString()) +
              ',"settings":' +
              JSON.stringify(user.settings),
          );
          const args = (cursor?: string) => ({
            where: { userId },
            take: 500,
            orderBy: { id: 'asc' as const },
            ...(cursor ? { cursor: { id: cursor }, skip: 1 } : {}),
          });
          const collections: [string, (cursor?: string) => Promise<{ id: string }[]>][] = [
            ['imports', (c) => tx.healthRawImport.findMany({ ...args(c), take: 1 })],
            ['sourceRecords', (c) => tx.healthSourceRecord.findMany(args(c))],
            ['samples', (c) => tx.healthSample.findMany(args(c))],
            ['sleep', (c) => tx.sleepSession.findMany(args(c))],
            ['workouts', (c) => tx.workout.findMany(args(c))],
            ['summaries', (c) => tx.dailyHealthSummary.findMany(args(c))],
            ['logs', (c) => tx.syncLog.findMany(args(c))],
            ['conversations', (c) => tx.aIConversation.findMany(args(c))],
          ];
          for (const [name, page] of collections) {
            await write(',' + JSON.stringify(name) + ':[');
            let cursor: string | undefined,
              first = true;
            while (true) {
              const records = await page(cursor);
              if (!records.length) break;
              for (const row of records) {
                await write((first ? '' : ',') + JSON.stringify(row));
                first = false;
              }
              cursor = records.at(-1)!.id;
              if (records.length < (name === 'imports' ? 1 : 500)) break;
            }
            await write(']');
          }
          await write('}');
        },
        { isolationLevel: Prisma.TransactionIsolationLevel.RepeatableRead, timeout: 120000 },
      );
      await writer.close();
    } catch {
      await writer.abort(new Error('数据导出未完成，请重试')).catch(() => {});
    }
  })();
  return new Response(stream.readable, {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Disposition': 'attachment; filename="personal-health-export.json"',
    },
  });
}
