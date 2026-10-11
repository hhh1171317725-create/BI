import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export function createConfig(directory, address) {
  const url = new URL(address);
  if (
    url.protocol !== 'https:' ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash
  )
    throw Error('请输入不带路径、凭据或查询参数的 HTTPS 域名');
  const target = resolve(directory, '.env');
  if (existsSync(target))
    throw Error('.env 已存在，为保护原配置不会覆盖。请在服务器编辑现有文件。');
  const template = readFileSync(
    resolve(dirname(fileURLToPath(import.meta.url)), '../.env.example'),
    'utf8',
  );
  const databasePassword = randomBytes(32).toString('hex');
  const values = {
    APP_ORIGIN: url.origin,
    POSTGRES_PASSWORD: databasePassword,
    DATABASE_URL: `postgresql://life_app:${databasePassword}@postgres:5432/personal_life`,
    BOOTSTRAP_PASSWORD: randomBytes(24).toString('base64url'),
    INGEST_API_KEY: randomBytes(32).toString('hex'),
  };
  const content = template
    .split('\n')
    .map((line) => {
      const key = line.split('=')[0];
      return Object.hasOwn(values, key) ? `${key}=${values[key]}` : line;
    })
    .join('\n');
  writeFileSync(target, content, { flag: 'wx', mode: 0o600 });
  return { origin: url.origin, target };
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    if (!process.argv[2]) throw Error('用法：node scripts/configure.mjs https://你的健康域名');
    const result = createConfig(process.cwd(), process.argv[2]);
    console.log('已生成 .env，独立密码和接收密钥仅保存在该文件，没有输出到日志。');
    console.log('服务域名：' + result.origin + '（仍需配置 DNS、HTTPS 和反向代理）');
    console.log(
      '请在服务器的私有编辑器中查看 BOOTSTRAP_PASSWORD 和 INGEST_API_KEY，不要上传或截图。',
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
