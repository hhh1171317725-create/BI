import { PrismaClient } from '@prisma/client';
const globalDb = globalThis as unknown as { lifeDb?: PrismaClient };
export const db = globalDb.lifeDb ?? new PrismaClient({ log: [] });
if (process.env.NODE_ENV !== 'production') globalDb.lifeDb = db;
