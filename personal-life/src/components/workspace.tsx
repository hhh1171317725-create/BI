'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import dynamic from 'next/dynamic';
const Trend = dynamic(() => import('./trend'), {
  ssr: false,
  loading: () => <div className="empty">正在加载趋势图…</div>,
});
type Settings = {
  bedtime: string;
  bedtimeLatest: string;
  wakeTime: string;
  sleepMinHours: number;
  sleepMaxHours: number;
  heightCm: number;
  referenceWeightKg: number;
  goal: string;
  preference: string;
  sourcePriority: string[];
  aiConsent: boolean;
};
type Day = {
  date: string;
  sleepMinutes: number | null;
  steps: number | null;
  restingHeartRate: number | null;
  activeCalories: number | null;
  workoutMinutes: number | null;
  workoutCount: number | null;
  metadata: Record<string, string | number | boolean | null>;
};
type Sleep = {
  id: string;
  date: string;
  startTime: string;
  endTime: string;
  durationMinutes: number;
  source: string;
  bedtime: string;
  wakeTime: string;
};
type Workout = {
  id: string;
  workoutType: string;
  startTime: string;
  endTime: string;
  durationMinutes: number;
  calories: number | null;
  source: string;
};
type Weight = { id: string; value: number; endTime: string; source: string };
type Log = {
  id: string;
  createdAt: string;
  source: string;
  status: string;
  receivedCount: number;
  insertedCount: number;
  duplicateCount: number;
  retainedCount: number;
  errorMessage: string | null;
};
type Import = {
  id: string;
  receivedAt: string;
  processStatus: string;
  errorMessage: string | null;
  source: string;
};
type Sync = {
  lastUpload: Log | null;
  lastSuccess: Log | null;
  lastFailure: Log | null;
  latestMeasurement: { date: string; time: string; source: string } | null;
  uploadAgeHours: number | null;
  measurementAgeHours: number | null;
  unsyncedDays: number | null;
  uploadDelayed: boolean;
  pendingMapping: number;
  hozzCoverage: { type: string; state: string; complete: boolean; observedAt: string | null }[];
  hozzHistoryIncomplete: boolean;
  logs: Log[];
  imports: Import[];
};
type Stage = {
  id: string;
  startTime: string;
  endTime: string;
  value: number;
  source: string;
  metadata: { stage?: string };
};
type Summary = {
  recentWeek: { days: Day[]; sleep: Sleep[] };
  heartRate: {
    date: string;
    bpm: number;
    minimum: number;
    maximum: number;
    count: number;
    source: string;
  }[];
  stages: Stage[];
  start: string;
  end: string;
  days: Day[];
  sleep: Sleep[];
  workouts: Workout[];
  weight: Weight[];
  sync: Sync;
  settings: Settings;
  analysis: {
    averageSleepMinutes: number | null;
    measuredSleepDays: number;
    bedtimeVariationMinutes: number | null;
    wakeVariationMinutes: number | null;
    regularity: string;
    weekdaySleepMinutes: number | null;
    weekendSleepMinutes: number | null;
    shortSleepDays: number;
    workoutCount: number;
    activeStreak: number;
  };
};
const date = (v: string | undefined) =>
  v
    ? new Date(v).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
    : '暂无数据';
const fmt = (n: number | null | undefined, digits = 0) =>
  n == null ? '暂无数据' : n.toLocaleString('zh-CN', { maximumFractionDigits: digits });
const day = (offset = 0) =>
  new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(
    new Date(Date.now() - offset * 86400000),
  );
const labels: Record<string, string> = {
  processed: '已处理',
  processed_with_gaps: '已处理 · 部分记录仅留存',
  awaiting_mapping: '待格式映射',
  failed: '处理失败',
};
const nav = [
  ['/', '健康概览', 'Overview'],
  ['/sleep', '睡眠分析', 'Sleep'],
  ['/fitness', '运动分析', 'Fitness'],
  ['/assistant', '生活助手', 'Assistant'],
  ['/settings/sync', '数据同步', 'Sync'],
  ['/settings', '个人设置', 'Settings'],
];
function Card({
  title,
  value,
  unit = '',
  source,
  updated,
}: {
  title: string;
  value: string;
  unit?: string;
  source?: string | null;
  updated?: string;
}) {
  return (
    <article className="stat">
      <span>{title}</span>
      <strong>
        {value}
        <small>{value === '暂无数据' ? '' : unit}</small>
      </strong>
      <p>来源 · {source || '暂无数据'}</p>
      <p>同步于 · {date(updated)}</p>
    </article>
  );
}
export default function Workspace({ screen }: { screen: string }) {
  const generation = useRef(0),
    [origin, setOrigin] = useState(''),
    [priorityText, setPriorityText] = useState('');
  const router = useRouter(),
    [error, setError] = useState(''),
    [notice, setNotice] = useState(''),
    [busy, setBusy] = useState(false),
    [loading, setLoading] = useState(true),
    [start, setStart] = useState(day(29)),
    [end, setEnd] = useState(day()),
    [data, setData] = useState<Summary | null>(null),
    [settings, setSettings] = useState<Settings | null>(null),
    [messages, setMessages] = useState<{ id?: string; role: string; content: string }[]>([]),
    [question, setQuestion] = useState(''),
    [consent, setConsent] = useState(false);
  const api = useCallback(
    async (path: string, method = 'GET', body?: unknown) => {
      const r = await fetch(path, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
        cache: 'no-store',
      });
      const payload = await r.json();
      if (!r.ok) {
        if (r.status === 401 && screen !== '/login') router.replace('/login');
        throw Error(payload.error?.message || '请求失败');
      }
      return payload;
    },
    [router, screen],
  );
  const load = useCallback(
    async (from: string, to: string) => {
      const current = ++generation.current;
      setLoading(true);
      setError('');
      try {
        const result = (await api(`/api/health/summary?start=${from}&end=${to}`)) as Summary;
        if (current !== generation.current) return;
        setData(result);
        setSettings(result.settings);
        setPriorityText(result.settings.sourcePriority.join(', '));
      } catch (e) {
        if (current === generation.current) setError(e instanceof Error ? e.message : '加载失败');
      } finally {
        if (current === generation.current) setLoading(false);
      }
    },
    [api],
  );
  useEffect(() => {
    setOrigin(location.origin);
    setStart(day(29));
    setEnd(day());
    if (screen === '/login') {
      setLoading(false);
      return;
    }
    let stopped = false;
    void load(day(29), day());
    if (screen === '/assistant')
      void api('/api/assistant/history')
        .then((r) => {
          if (!stopped) setMessages(r.messages);
        })
        .catch((e) => setError(e.message));
    return () => {
      stopped = true;
      generation.current++;
    };
  }, [screen, load, api]);
  async function action(work: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }
  if (screen === '/login')
    return (
      <main className="login">
        <div className="login-box">
          <div className="logo">L</div>
          <p className="eyebrow">YOUR PRIVATE HEALTH SPACE</p>
          <h1>把生活，照顾好。</h1>
          <p className="muted">登录你的个人健康工作台</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const form = new FormData(e.currentTarget);
              void action(async () => {
                await api('/api/auth/login', 'POST', {
                  username: form.get('username'),
                  password: form.get('password'),
                });
                router.replace('/');
                router.refresh();
              });
            }}
          >
            <label>
              用户名
              <input name="username" autoComplete="username" required maxLength={64} />
            </label>
            <label>
              密码
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                required
                maxLength={200}
              />
            </label>
            {error && (
              <p className="error" role="alert">
                {error}
              </p>
            )}
            <button disabled={busy}>{busy ? '正在登录…' : '进入工作台 →'}</button>
          </form>
          <p className="tiny">私人访问 · 独立账户 · 数据保存在你的服务器</p>
        </div>
      </main>
    );
  const section = nav.find((n) => n[0] === screen),
    today = data?.days.find((d) => d.date === data?.end),
    sleep = data?.sleep
      .filter((s) => s.date === data?.end)
      .sort((a, b) => b.durationMinutes - a.durationMinutes)[0],
    updated = data?.sync.lastSuccess?.createdAt,
    seven =
      data?.recentWeek.days
        .filter(
          (d) =>
            d.date >=
              new Date(new Date(data.end + 'T12:00:00Z').getTime() - 6 * 86400000)
                .toISOString()
                .slice(0, 10) && d.date <= data.end,
        )
        .map((d) => d.sleepMinutes)
        .filter((v): v is number => v !== null) || [],
    average = seven.length ? seven.reduce((a, b) => a + b, 0) / seven.length : null,
    lastWeight = data?.weight.at(-1),
    firstWeight = data?.weight[0];
  return (
    <div className="shell">
      <aside className="sidebar">
        <Link className="brand" href="/">
          <span className="logo">L</span>
          <span>
            LIFE<span className="brand-small">PERSONAL HEALTH</span>
          </span>
        </Link>
        <p className="tiny">你的节奏，你的生活</p>
        <nav>
          {nav.map(([href, title, sub]) => (
            <Link key={href} href={href} className={href === screen ? 'selected' : ''}>
              <span>{title}</span>
              <small>{sub}</small>
            </Link>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className="dot" /> Asia / Shanghai
          <button
            className="secondary"
            disabled={busy}
            onClick={() =>
              void action(async () => {
                await api('/api/auth/logout', 'POST');
                router.replace('/login');
                router.refresh();
              })
            }
          >
            退出登录
          </button>
        </div>
      </aside>
      <main className="content">
        <header className="page-head">
          <div>
            <p className="eyebrow">PERSONAL LIFE MANAGEMENT</p>
            <h1>{section?.[1]}</h1>
            <p className="muted">用真实数据认识自己，循序渐进地改善生活。</p>
          </div>
          <button
            className="secondary"
            disabled={busy}
            onClick={() =>
              void action(async () => {
                await api('/api/auth/logout', 'POST');
                router.replace('/login');
                router.refresh();
              })
            }
          >
            退出登录
          </button>
        </header>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        {notice && (
          <div className="notice" role="status">
            {notice}
          </div>
        )}
        {!['/assistant', '/settings'].includes(screen) && (
          <form
            className="date-bar"
            onSubmit={(e) => {
              e.preventDefault();
              if (start > end) {
                setError('开始日期不能晚于结束日期');
                return;
              }
              void load(start, end);
            }}
          >
            <label>
              开始日期
              <input
                type="date"
                value={start}
                onChange={(e) => setStart(e.target.value)}
                required
              />
            </label>
            <label>
              结束日期
              <input type="date" value={end} onChange={(e) => setEnd(e.target.value)} required />
            </label>
            <button disabled={loading}>查询</button>
            {[7, 30].map((n) => (
              <button
                type="button"
                className="secondary"
                key={n}
                disabled={loading}
                onClick={() => {
                  setStart(day(n - 1));
                  setEnd(day());
                  void load(day(n - 1), day());
                }}
              >
                近 {n} 天
              </button>
            ))}
          </form>
        )}
        {loading && (
          <p className="muted" role="status">
            正在读取你的数据…
          </p>
        )}
        {data?.sync.uploadDelayed && (
          <div className="notice">
            {data.sync.lastUpload ? '超过 24 小时未收到上传请求。' : '尚未收到健康数据上传。'}
            可在手机解锁后打开 Hozz 检查同步。数据上传与新测量时间分别记录。
          </div>
        )}
        {!!data?.sync.pendingMapping && (
          <div className="notice">
            {data.sync.pendingMapping}{' '}
            个原始批次已保存，等待真实格式映射。当前不会据此生成健康指标。
          </div>
        )}
        {data?.sync.hozzHistoryIncomplete && (
          <div className="notice">
            Hozz
            历史读取尚未确认完整：以下指标只反映已收到的记录，缺少数据不代表没有运动或睡眠。请在手机解锁时继续同步。
          </div>
        )}
        {data && screen === '/' && (
          <>
            <div className="section-heading">
              <h2>每日状态</h2>
              <span>{data.end} · 睡眠归属醒来当天</span>
            </div>
            <div className="stats">
              <Card
                title="昨晚睡眠"
                value={fmt(today?.sleepMinutes == null ? null : today.sleepMinutes / 60, 1)}
                unit="小时"
                source={String(today?.metadata.sleep || '')}
                updated={updated}
              />
              <Card
                title="入睡时间"
                value={sleep?.bedtime || '暂无数据'}
                source={sleep?.source}
                updated={updated}
              />
              <Card
                title="起床时间"
                value={sleep?.wakeTime || '暂无数据'}
                source={sleep?.source}
                updated={updated}
              />
              <Card
                title="最近 7 天平均睡眠"
                value={fmt(average == null ? null : average / 60, 1)}
                unit="小时"
                source={seven.length ? `有效记录 ${seven.length} 天` : '暂无数据'}
                updated={updated}
              />
              <Card
                title="最近 7 天规律性"
                value={regularitySeven(data.recentWeek.sleep, data.end)}
                source="入睡时间波动 / 至少3条记录"
                updated={updated}
              />
              <Card
                title="当日步数"
                value={fmt(today?.steps)}
                unit="步"
                source={String(today?.metadata.steps || '')}
                updated={updated}
              />
              <Card
                title="当日运动"
                value={fmt(today?.workoutMinutes)}
                unit="分钟"
                source={String(today?.metadata.workouts || '')}
                updated={updated}
              />
              <Card
                title="静息心率"
                value={fmt(today?.restingHeartRate)}
                unit="bpm"
                source={String(today?.metadata.restingHeartRate || '')}
                updated={updated}
              />
              <Card
                title="区间体重变化"
                value={fmt(
                  lastWeight && firstWeight && lastWeight.id !== firstWeight.id
                    ? lastWeight.value - firstWeight.value
                    : null,
                  1,
                )}
                unit="kg"
                source={lastWeight?.source}
                updated={updated}
              />
              <Card
                title="最近收到上传"
                value={date(data.sync.lastUpload?.createdAt)}
                source={data.sync.lastUpload?.source}
                updated={data.sync.lastUpload?.createdAt}
              />
            </div>
            <div className="charts">
              <section className="panel">
                <h2>
                  睡眠趋势 <small>小时</small>
                </h2>
                <Trend
                  data={data.days.map((d) => ({
                    ...d,
                    hours: d.sleepMinutes === null ? null : d.sleepMinutes / 60,
                  }))}
                  field="hours"
                  label="睡眠"
                  unit="小时"
                />
              </section>
              <section className="panel">
                <h2>每日步数</h2>
                <Trend data={data.days} field="steps" label="步数" unit="步" bar />
              </section>
            </div>
          </>
        )}
        {data && screen === '/sleep' && (
          <>
            <div className="stats">
              <Card
                title="平均睡眠"
                value={fmt(
                  data.analysis.averageSleepMinutes === null
                    ? null
                    : data.analysis.averageSleepMinutes / 60,
                  1,
                )}
                unit="小时"
                source={`有效睡眠 ${data.analysis.measuredSleepDays} 天`}
                updated={updated}
              />
              <Card
                title="入睡时间波动"
                value={fmt(data.analysis.bedtimeVariationMinutes, 1)}
                unit="分钟"
                source="入睡时间标准差"
                updated={updated}
              />
              <Card
                title="起床时间波动"
                value={fmt(data.analysis.wakeVariationMinutes, 1)}
                unit="分钟"
                source="起床时间标准差"
                updated={updated}
              />
              <Card
                title="作息规律性"
                value={data.analysis.regularity}
                source="当前查询区间"
                updated={updated}
              />
            </div>
            <section className="panel">
              <h2>睡眠时间线</h2>
              <p className="muted">
                目标入睡 {data.settings.bedtime}–{data.settings.bedtimeLatest} · 起床{' '}
                {data.settings.wakeTime} · 睡眠 {data.settings.sleepMinHours}–
                {data.settings.sleepMaxHours} 小时
              </p>
              <Trend
                data={data.days.map((d) => ({
                  ...d,
                  hours: d.sleepMinutes === null ? null : d.sleepMinutes / 60,
                }))}
                field="hours"
                label="睡眠时长"
                unit="小时"
              />
              <p className="muted">
                工作日平均{' '}
                {fmt(
                  data.analysis.weekdaySleepMinutes === null
                    ? null
                    : data.analysis.weekdaySleepMinutes / 60,
                  1,
                )}{' '}
                小时 · 休息日{' '}
                {fmt(
                  data.analysis.weekendSleepMinutes === null
                    ? null
                    : data.analysis.weekendSleepMinutes / 60,
                  1,
                )}{' '}
                小时
              </p>
              {data.analysis.shortSleepDays > 0 && (
                <p className="notice">
                  有 {data.analysis.shortSleepDays} 天低于你的 {data.settings.sleepMinHours}{' '}
                  小时睡眠目标。可尝试提前安排休息。
                </p>
              )}
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>醒来日期</th>
                      <th>入睡</th>
                      <th>起床</th>
                      <th>时长</th>
                      <th>来源</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.sleep.map((s) => (
                      <tr key={s.id}>
                        <td>{s.date}</td>
                        <td>{s.bedtime}</td>
                        <td>{s.wakeTime}</td>
                        <td>{fmt(s.durationMinutes / 60, 1)} 小时</td>
                        <td>{s.source}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!data.sleep.length && <p className="empty">暂无睡眠数据</p>}
              </div>
              <h3>睡眠阶段（原始区间）</h3>
              {data.stages.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>开始</th>
                        <th>结束</th>
                        <th>阶段</th>
                        <th>分钟</th>
                        <th>来源</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.stages.map((s) => (
                        <tr key={s.id}>
                          <td>{date(s.startTime)}</td>
                          <td>{date(s.endTime)}</td>
                          <td>
                            {(
                              {
                                awake: '清醒',
                                core: '浅睡',
                                deep: '深睡',
                                rem: 'REM 快速眼动',
                                asleep: '睡眠',
                                in_bed: '在床',
                              } as Record<string, string>
                            )[s.metadata.stage || ''] || '未知阶段'}
                          </td>
                          <td>{fmt(s.value, 1)}</td>
                          <td>{s.source}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="empty">暂无睡眠阶段数据</p>
              )}
              <p className="tiny">
                睡眠阶段仅在数据源提供且映射已验证时保留，不据此生成睡眠评分。可穿戴设备估算不属于医学诊断。
              </p>
            </section>
          </>
        )}
        {data && screen === '/fitness' && (
          <>
            <div className="stats">
              <Card
                title="区间运动次数"
                value={data.workouts.length ? fmt(data.analysis.workoutCount) : '暂无数据'}
                unit="次"
                source="已去重运动记录"
                updated={updated}
              />
              <Card
                title="连续运动天数"
                value={data.workouts.length ? fmt(data.analysis.activeStreak) : '暂无数据'}
                unit="天"
                source="截至查询结束日"
                updated={updated}
              />
              <Card
                title="当日活动消耗"
                value={fmt(today?.activeCalories)}
                unit="kcal"
                source={String(today?.metadata.activeCalories || '')}
                updated={updated}
              />
              <Card
                title="最新记录体重"
                value={fmt(lastWeight?.value, 1)}
                unit="kg"
                source={lastWeight?.source}
                updated={updated}
              />
            </div>
            <div className="charts">
              <section className="panel">
                <h2>活动趋势</h2>
                <Trend data={data.days} field="workoutMinutes" label="运动时长" unit="分钟" bar />
              </section>
              <section className="panel">
                <h2>体重趋势</h2>
                <Trend
                  data={data.weight.map((w) => ({
                    date: new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(
                      new Date(w.endTime),
                    ),
                    kg: w.value,
                  }))}
                  field="kg"
                  label="体重"
                  unit="kg"
                />
              </section>
            </div>
            <div className="charts">
              <section className="panel">
                <h2>每日步数</h2>
                <Trend data={data.days} field="steps" label="步数" unit="步" bar />
              </section>
              <section className="panel">
                <h2>
                  每周活动趋势 <small>按周一分组</small>
                </h2>
                <Trend
                  data={weeklyActivity(data.days)}
                  field="minutes"
                  label="运动时长"
                  unit="分钟"
                  bar
                />
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>周起始</th>
                        <th>次数</th>
                        <th>分钟</th>
                      </tr>
                    </thead>
                    <tbody>
                      {weeklyActivity(data.days).map((w) => (
                        <tr key={w.date}>
                          <td>{w.date}</td>
                          <td>{fmt(w.count)}</td>
                          <td>{fmt(w.minutes)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </div>
            <section className="panel">
              <h2>
                心率趋势 <small>每日平均 · 非医学评估</small>
              </h2>
              <Trend data={data.heartRate} field="bpm" label="心率" unit="bpm" />
            </section>
            <section className="panel">
              <h2>运动记录</h2>
              <p className="muted">疲劳时从短时、低强度的居家活动开始，重视持续性。</p>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>时间</th>
                      <th>类型</th>
                      <th>时长</th>
                      <th>热量</th>
                      <th>来源</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.workouts.map((w) => (
                      <tr key={w.id}>
                        <td>{date(w.startTime)}</td>
                        <td>{w.workoutType}</td>
                        <td>{fmt(w.durationMinutes)} 分钟</td>
                        <td>{fmt(w.calories)} kcal</td>
                        <td>{w.source}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!data.workouts.length && <p className="empty">暂无运动数据</p>}
              </div>
            </section>
          </>
        )}
        {screen === '/assistant' && (
          <section className="panel assistant">
            <h2>
              生活助手 <small>Health companion</small>
            </h2>
            <p className="muted">
              只查询回答所需的日期与指标摘要。首次使用需在个人设置中授权。当前问题独立查询，历史对话不会自动发送。
            </p>
            <div className="suggestions">
              {['我昨晚睡了多久？', '我这周运动了几次？', '这周睡眠比上周好吗？'].map((q) => (
                <button className="secondary" key={q} onClick={() => setQuestion(q)}>
                  {q}
                </button>
              ))}
            </div>
            <div className="chat-log">
              {messages.map((m, i) => (
                <article key={m.id || i} className={'chat ' + m.role}>
                  <small>{m.role === 'user' ? '你' : '生活助手'}</small>
                  <p>{m.content}</p>
                </article>
              ))}
              {!messages.length && <p className="empty">从一个关于睡眠或运动的小问题开始。</p>}
            </div>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const message = question;
                void action(async () => {
                  const result = await api('/api/assistant/chat', 'POST', {
                    message,
                    consent: true,
                  });
                  setMessages((m) => [
                    ...m,
                    { role: 'user', content: message },
                    { role: 'assistant', content: result.content },
                  ]);
                  setQuestion('');
                  setConsent(false);
                });
              }}
            >
              <label>
                你的问题
                <textarea
                  value={question}
                  maxLength={1500}
                  onChange={(e) => setQuestion(e.target.value)}
                  required
                  placeholder="我最近7天几点睡觉？"
                />
              </label>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={consent}
                  onChange={(e) => setConsent(e.target.checked)}
                />
                允许本次将必要健康统计摘要和问题发送给 OpenAI
              </label>
              <button disabled={busy || !consent || !settings?.aiConsent}>
                {busy ? '正在分析…' : '发送问题'}
              </button>
            </form>
          </section>
        )}
        {data && screen === '/settings/sync' && (
          <>
            <section className="panel">
              <h2>Hozz 手机连接</h2>
              <p className="muted">Automatic → Add a destination → Web address，填写以下内容。</p>
              <p>
                Web address：
                <code style={{ overflowWrap: 'anywhere' }}>{origin}/api/health/ingest</code>
              </p>
              <button
                className="secondary"
                onClick={() =>
                  void action(async () => {
                    await navigator.clipboard.writeText(origin + '/api/health/ingest');
                    setNotice('接收地址已复制');
                  })
                }
              >
                复制接收地址
              </button>
              <p>
                Authorization header：<code>Bearer 你的INGEST_API_KEY</code>（Bearer
                后有一个空格）。密钥在服务器的 personal-life/.env 内，不在网页显示。
              </p>
              <p>
                Format：<strong>JSON</strong>，不要选择 NDJSON 或 Metrics JSON。How often：When new
                data arrives。首次建议 Start from 7 days ago；若版本提供请求大小限制，选择 1 MB。
              </p>
              <p className="tiny">
                先测试连接，再保存并在手机解锁时同步。iOS 决定后台运行时间；关闭 App
                后需要重新打开。不承诺实时同步。
              </p>
            </section>
            <div className="stats">
              <Card
                title="最近处理成功"
                value={date(data.sync.lastSuccess?.createdAt)}
                source="来源记录处理完成"
                updated={data.sync.lastSuccess?.createdAt}
              />
              <Card
                title="最近处理失败"
                value={date(data.sync.lastFailure?.createdAt)}
                source="安全错误记录"
                updated={data.sync.lastFailure?.createdAt}
              />
              <Card
                title="最近收到的测量"
                value={data.sync.latestMeasurement?.date || '暂无数据'}
                source={data.sync.latestMeasurement?.source}
                updated={updated}
              />
              <Card
                title="连续未收到上传"
                value={fmt(data.sync.unsyncedDays)}
                unit="天"
                source="上传请求时间"
                updated={updated}
              />
            </div>
            {!!data.sync.hozzCoverage.length && (
              <section className="panel">
                <h2>Hozz 历史读取状态</h2>
                <p className="muted">
                  收到最新日期的记录不代表历史已经全部传完。未确认完整时，不把缺失值当作零。
                </p>
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>指标类型</th>
                        <th>完整性</th>
                        <th>手机报告状态</th>
                        <th>报告时间</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.sync.hozzCoverage.map((r) => (
                        <tr key={r.type}>
                          <td>{r.type.replace(/^HK(Quantity|Category)TypeIdentifier/, '')}</td>
                          <td>{r.complete ? '已确认读取完整' : '尚未确认完整'}</td>
                          <td>{r.state}</td>
                          <td>{date(r.observedAt ?? undefined)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
            <section className="panel">
              <h2>原始批次</h2>
              <p className="muted">
                收到原始数据不代表已完成格式映射。接口地址：{origin}
                /api/health/ingest；密钥仅在服务器环境变量中设置。
              </p>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>收到时间</th>
                      <th>来源</th>
                      <th>状态</th>
                      <th>处理</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.sync.imports.map((r) => (
                      <tr key={r.id}>
                        <td>{date(r.receivedAt)}</td>
                        <td>{r.source}</td>
                        <td>
                          {labels[r.processStatus] || r.processStatus}
                          {r.errorMessage && <small>{r.errorMessage}</small>}
                        </td>
                        <td>
                          <button
                            className="secondary"
                            disabled={busy}
                            onClick={() =>
                              void action(async () => {
                                const result = await api('/api/health/reprocess', 'POST', {
                                  importId: r.id,
                                });
                                await load(start, end);
                                setNotice(result.message);
                              })
                            }
                          >
                            重新处理
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!data.sync.imports.length && <p className="empty">暂无同步批次</p>}
              </div>
            </section>
            <section className="panel">
              <h2>同步日志</h2>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>时间</th>
                      <th>状态</th>
                      <th>收到</th>
                      <th>新增 / 更新</th>
                      <th>重复</th>
                      <th>仅留存</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.sync.logs.map((l) => (
                      <tr key={l.id}>
                        <td>{date(l.createdAt)}</td>
                        <td>
                          {labels[l.status] || l.status}
                          {l.errorMessage && <small>{l.errorMessage}</small>}
                        </td>
                        <td>{l.status === 'awaiting_mapping' ? '待解析' : l.receivedCount}</td>
                        <td>{l.insertedCount}</td>
                        <td>{l.duplicateCount}</td>
                        <td>{l.retainedCount || 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="tiny">
                最新测量时间：{date(data.sync.latestMeasurement?.time)}
                。无新测量与无上传请求分别判断。
              </p>
            </section>
          </>
        )}
        {screen === '/settings' && settings && (
          <section className="panel">
            <h2>目标与偏好</h2>
            <p className="muted">基本信息是你设置的参考值，不作为健康测量记录。</p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void action(async () => {
                  const saved = await api('/api/settings', 'PUT', {
                    ...settings,
                    sourcePriority: priorityText
                      .split(',')
                      .map((v) => v.trim())
                      .filter(Boolean),
                  });
                  setSettings(saved.settings);
                  setNotice('设置已保存');
                });
              }}
            >
              <div className="form-grid">
                {(['bedtime', 'bedtimeLatest', 'wakeTime'] as const).map((k) => (
                  <label key={k}>
                    {
                      {
                        bedtime: '目标入睡（最早）',
                        bedtimeLatest: '目标入睡（最晚）',
                        wakeTime: '目标起床',
                      }[k]
                    }
                    <input
                      type="time"
                      value={settings[k]}
                      onChange={(e) => setSettings({ ...settings, [k]: e.target.value })}
                    />
                  </label>
                ))}
                {(['sleepMinHours', 'sleepMaxHours', 'heightCm', 'referenceWeightKg'] as const).map(
                  (k) => (
                    <label key={k}>
                      {
                        {
                          sleepMinHours: '最少睡眠（小时）',
                          sleepMaxHours: '最多睡眠（小时）',
                          heightCm: '身高（cm）',
                          referenceWeightKg: '参考体重（kg）',
                        }[k]
                      }
                      <input
                        type="number"
                        step="0.1"
                        required
                        value={settings[k]}
                        onChange={(e) => setSettings({ ...settings, [k]: Number(e.target.value) })}
                      />
                    </label>
                  ),
                )}
                <label>
                  长期目标
                  <input
                    value={settings.goal}
                    maxLength={100}
                    onChange={(e) => setSettings({ ...settings, goal: e.target.value })}
                  />
                </label>
                <label>
                  运动偏好
                  <input
                    value={settings.preference}
                    maxLength={100}
                    onChange={(e) => setSettings({ ...settings, preference: e.target.value })}
                  />
                </label>
              </div>
              <label>
                来源优先级（精确名称，逗号分隔）
                <input value={priorityText} onChange={(e) => setPriorityText(e.target.value)} />
              </label>
              <p className="tiny">
                步数与活动消耗按每天每项选择一个来源。其他来源保留原始记录，用于核对；不跨来源累加。
              </p>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={settings.aiConsent}
                  onChange={(e) => setSettings({ ...settings, aiConsent: e.target.checked })}
                />
                授权生活助手使用必要摘要（每次提问还会再次确认）
              </label>
              <button disabled={busy}>保存设置</button>
            </form>
            <hr />
            <h2>数据由你掌控</h2>
            <a className="button secondary" href="/api/export">
              导出个人数据 JSON
            </a>
            <form
              className="danger-zone"
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                if (!confirm('永久删除全部健康、原始批次及聊天记录？备份需另外删除。')) return;
                void action(async () => {
                  await api('/api/my-data', 'DELETE', {
                    password: f.get('password'),
                    confirmation: 'DELETE_MY_HEALTH_DATA',
                  });
                  router.replace('/login');
                  router.refresh();
                });
              }}
            >
              <label>
                输入密码确认删除
                <input
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  maxLength={200}
                />
              </label>
              <button className="danger" disabled={busy}>
                删除我的健康数据与对话
              </button>
              <p className="tiny">保留登录账户，撤销 AI 授权并退出所有登录。离线备份不自动删除。</p>
            </form>
          </section>
        )}
        <footer>数据仅用于生活习惯参考 · 暂无数据时不推测 · Asia/Shanghai</footer>
      </main>
    </div>
  );
}
function regularitySeven(sleep: Sleep[], end: string) {
  const begin = new Date(new Date(end + 'T12:00:00Z').getTime() - 6 * 86400000)
    .toISOString()
    .slice(0, 10);
  const rows = sleep.filter((s) => s.date >= begin && s.date <= end);
  if (!rows.length) return '暂无数据';
  if (rows.length < 3) return '数据不足';
  const minutes = rows.map((s) => {
      const [h, m] = s.bedtime.split(':').map(Number);
      return (h < 12 ? h + 24 : h) * 60 + m;
    }),
    mean = minutes.reduce((a, b) => a + b) / minutes.length,
    std = Math.sqrt(minutes.reduce((a, b) => a + (b - mean) ** 2, 0) / minutes.length);
  return std <= 30 ? '较规律' : std <= 60 ? '存在波动' : '波动较大';
}
function weeklyActivity(days: Day[]) {
  const groups = new Map<string, { date: string; minutes: number | null; count: number | null }>();
  for (const d of days) {
    const date = new Date(d.date + 'T12:00:00Z'),
      offset = (date.getUTCDay() + 6) % 7,
      monday = new Date(date.getTime() - offset * 86400000).toISOString().slice(0, 10);
    const group = groups.get(monday) || { date: monday, minutes: null, count: null };
    if (d.workoutMinutes !== null) group.minutes = (group.minutes ?? 0) + d.workoutMinutes;
    if (d.workoutCount !== null) group.count = (group.count ?? 0) + d.workoutCount;
    groups.set(monday, group);
  }
  return [...groups.values()];
}
