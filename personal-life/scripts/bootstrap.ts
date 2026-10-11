import { db } from '../src/lib/db';
import { passwordHash } from '../src/lib/security';
async function run() {
  const username = process.env.BOOTSTRAP_USERNAME,
    password = process.env.BOOTSTRAP_PASSWORD,
    key = process.env.INGEST_API_KEY;
  if (
    !username ||
    username.length > 64 ||
    !password ||
    password.length < 14 ||
    password.length > 200 ||
    password.startsWith('CHANGE_ME') ||
    !key ||
    key.length < 40 ||
    key.startsWith('CHANGE_ME')
  )
    throw Error('请配置用户名、至少14位强密码和至少40字符随机接收密钥');
  const origin = new URL(process.env.APP_ORIGIN || '');
  if (origin.protocol !== 'https:' && process.env.ALLOW_INSECURE_LOCAL !== 'true')
    throw Error('生产域名必须使用 HTTPS');
  if (process.env.APP_ORIGIN!==origin.origin) throw Error('APP_ORIGIN 只能包含协议、域名和端口，不带末尾斜杠');
  await db.user.upsert({
    where: { username },
    create: { username, passwordHash: passwordHash(password) },
    update: {},
  });
  await db.rateBucket.deleteMany({ where: { expiresAt: { lt: new Date() } } });
  console.log('用户初始化完成（已有用户密码未被覆盖）');
}
run()
  .catch(() => {
    console.error('初始化失败，请检查环境变量和数据库连接');
    process.exitCode = 1;
  })
  .finally(() => db.$disconnect());
