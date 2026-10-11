import { createHash, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { db } from './db';
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export const hash = (text: string) => createHash('sha256').update(text).digest('hex');
const scryptOptions = { N: 131072, r: 8, p: 1, maxmem: 256 * 1024 * 1024 };
export function passwordHash(password: string) {
  const salt = randomBytes(16).toString('hex');
  return `scrypt-v1:${salt}:${scryptSync(password, salt, 64, scryptOptions).toString('hex')}`;
}
export function verifyPassword(password: string, stored: string) {
  const [version, salt, digest] = stored.split(':');
  if (version !== 'scrypt-v1' || !salt || !digest) return false;
  const expected = Buffer.from(digest, 'hex'),
    actual = scryptSync(password, salt, 64, scryptOptions);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}
export function safeEqual(a: string, b: string) {
  return timingSafeEqual(Buffer.from(hash(a)), Buffer.from(hash(b)));
}
export function enforceTransport(request: Request) {
  const origin = process.env.APP_ORIGIN;
  if (!origin) throw new ApiError(503, 'CONFIG', '尚未配置服务域名');
  const local =
    process.env.ALLOW_INSECURE_LOCAL === 'true' &&
    ['localhost', '127.0.0.1'].includes(new URL(request.url).hostname);
  if (
    !local &&
    new URL(request.url).protocol !== 'https:' &&
    request.headers.get('x-forwarded-proto') !== 'https'
  )
    throw new ApiError(400, 'HTTPS_REQUIRED', '请通过 HTTPS 访问');
}
export function sameOrigin(request: Request) {
  if (request.headers.get('origin') !== process.env.APP_ORIGIN)
    throw new ApiError(403, 'ORIGIN', '请求来源无效');
}
let cleanedAt = 0;
export async function rateLimit(key: string, limit: number, windowMs = 60000) {
  if (Date.now() - cleanedAt > 300000) {
    cleanedAt = Date.now();
    await db.rateBucket.deleteMany({ where: { expiresAt: { lt: new Date() } } });
  }
  const slot = Math.floor(Date.now() / windowMs),
    bucket = await db.rateBucket.upsert({
      where: { key: hash(key) + ':' + slot },
      create: { key: hash(key) + ':' + slot, count: 1, expiresAt: new Date((slot + 2) * windowMs) },
      update: { count: { increment: 1 } },
    });
  if (bucket.count > limit) throw new ApiError(429, 'RATE_LIMIT', '请求过于频繁，请稍后重试');
}
export async function userFromRequest(request: Request) {
  const raw = request.headers
    .get('cookie')
    ?.split(';')
    .map((v) => v.trim())
    .find((v) => v.startsWith('life_session='))
    ?.slice(13);
  if (!raw) throw new ApiError(401, 'AUTH', '请先登录');
  const session = await db.session.findUnique({
    where: { tokenHash: hash(raw) },
    include: { user: true },
  });
  if (!session || session.expiresAt.getTime() < Date.now())
    throw new ApiError(401, 'AUTH', '登录已过期');
  return session.user;
}
export async function ingestUser(request: Request) {
  const secret = process.env.INGEST_API_KEY;
  if (!secret || secret.length < 40 || secret.startsWith('CHANGE_ME'))
    throw new ApiError(503, 'CONFIG', '接收密钥尚未配置');
  const supplied =
    request.headers.get('authorization')?.replace(/^Bearer /i, '') ||
    request.headers.get('x-api-key') ||
    '';
  if (!safeEqual(supplied, secret)) throw new ApiError(401, 'INGEST_AUTH', '接收密钥无效');
  const user = await db.user.findUnique({
    where: { username: process.env.BOOTSTRAP_USERNAME || '' },
  });
  if (!user) throw new ApiError(503, 'CONFIG', '请先初始化用户');
  return user;
}
export async function readJson(request: Request, maxBytes = 65536): Promise<unknown> {
  if (!request.headers.get('content-type')?.toLowerCase().startsWith('application/json'))
    throw new ApiError(415, 'CONTENT_TYPE', '仅支持 application/json');
  if (Number(request.headers.get('content-length')) > maxBytes)
    throw new ApiError(413, 'BODY_SIZE', '上传过大，请拆分批次');
  if (!request.body) throw new ApiError(400, 'EMPTY', '请求内容为空');
  const reader = request.body.getReader(),
    parts: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) {
        await reader.cancel();
        throw new ApiError(413, 'BODY_SIZE', '上传过大，请拆分批次');
      }
      parts.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  try {
    return JSON.parse(Buffer.concat(parts).toString('utf8'));
  } catch {
    throw new ApiError(400, 'JSON', 'JSON 格式无效');
  }
}
export const cookie = (token: string, maxAge: number) =>
  `life_session=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}${process.env.ALLOW_INSECURE_LOCAL === 'true' ? '' : '; Secure'}`;
