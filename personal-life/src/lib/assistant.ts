import OpenAI from 'openai';
import { daysBack, localDay, dayRange } from './domain';
import { report, readSettings } from './analytics';
import { ApiError } from './security';
// Deterministic, bounded intent routing. The model cannot execute SQL or widen access.
export function queryPlan(question: string, now = new Date()) {
  let range = daysBack(14, now);
  const end = localDay(now),
    weekday = (new Date(end + 'T12:00:00Z').getUTCDay() + 6) % 7;
  const offset = (n: number) => localDay(new Date(now.getTime() - n * 86400000));
  if (/上周/.test(question))
    range = {
      start: offset(weekday + 7),
      end: /比|比较/.test(question) ? end : offset(weekday + 1),
    };
  else if (/这周|本周/.test(question)) range = { start: offset(weekday), end };
  else if (/30天|一个月/.test(question)) range = daysBack(30, now);
  else if (/7天|七天|一周/.test(question)) range = daysBack(7, now);
  else if (/昨晚|昨夜|今天/.test(question)) range = { start: end, end };
  else if (/昨天/.test(question)) range = { start: offset(1), end: offset(1) };
  const explicit = question.match(/(\d{4}-\d{2}-\d{2})/g);
  if (explicit?.length) range = { start: explicit[0], end: explicit[1] ?? explicit[0] };
  const metrics = new Set<string>();
  if (/睡|熬夜|疲劳|累|作息/.test(question)) metrics.add('sleep');
  if (/运动|训练|步|疲劳|累/.test(question)) metrics.add('activity');
  if (/心率/.test(question)) metrics.add('heart');
  if (/体重|减脂|减肥/.test(question)) metrics.add('weight');
  if (!metrics.size) {
    metrics.add('sleep');
    metrics.add('activity');
  }
  return { ...range, metrics: [...metrics] };
}
export async function healthContext(userId: string, question: string, settings: unknown) {
  const plan = queryPlan(question);
  let range;
  try {
    range = dayRange(plan.start, plan.end);
  } catch {
    throw new ApiError(400, 'AI_DATE', '提问日期无效');
  }
  if ((range.until.getTime() - range.from.getTime()) / 86400000 > 31)
    throw new ApiError(400, 'AI_RANGE', 'AI 单次最多分析 31 天，请缩小范围');
  const data = await report(userId, plan.start, plan.end, readSettings(settings));
  return {
    range: { start: plan.start, end: plan.end, timezone: data.timezone },
    metrics: plan.metrics,
    missingData: 'null 或空列表表示暂无数据，不能推断为零',
    ...(plan.metrics.includes('sleep')
      ? {
          sleep: data.sleep.map((s) => ({
            date: s.date,
            start: s.bedtime,
            end: s.wakeTime,
            minutes: s.durationMinutes,
          })),
          sleepAnalysis: {
            averageMinutes: data.analysis.averageSleepMinutes,
            measuredDays: data.analysis.measuredSleepDays,
            regularity: data.analysis.regularity,
            shortSleepDays: data.analysis.shortSleepDays,
          },
        }
      : {}),
    ...(plan.metrics.includes('activity')
      ? {
          activity: data.days.map((d) => ({
            date: d.date,
            steps: d.steps,
            minutes: d.workoutMinutes,
            workouts: d.workoutCount,
            activeCalories: d.activeCalories,
          })),
        }
      : {}),
    ...(plan.metrics.includes('heart')
      ? {
          restingHeartRate: data.days.map((d) => ({ date: d.date, bpm: d.restingHeartRate })),
          heartRate: data.heartRate.map((r) => ({
            date: r.date,
            averageBpm: r.bpm,
            minimumBpm: r.minimum,
            maximumBpm: r.maximum,
          })),
        }
      : {}),
    ...(plan.metrics.includes('weight')
      ? { weight: data.weight.map((s) => ({ date: localDay(s.endTime), kg: s.value })) }
      : {}),
    goals: {
      bedtime: readSettings(settings).bedtime,
      bedtimeLatest: readSettings(settings).bedtimeLatest,
      wakeTime: readSettings(settings).wakeTime,
      sleepMinHours: readSettings(settings).sleepMinHours,
      sleepMaxHours: readSettings(settings).sleepMaxHours,
      preference: readSettings(settings).preference,
    },
  };
}
export async function answer(question: string, context: unknown, transport?: typeof fetch) {
  if (!process.env.OPENAI_API_KEY || !process.env.OPENAI_MODEL)
    throw new ApiError(503, 'AI_CONFIG', '请在服务器配置 OpenAI API Key 和模型');
  try {
    const client = new OpenAI({
      apiKey: process.env.OPENAI_API_KEY,
      // Never inherit a third-party OPENAI_BASE_URL for private health summaries.
      baseURL: 'https://api.openai.com/v1',
      timeout: 30000,
      maxRetries: 1,
      ...(transport ? { fetch: transport } : {}),
    });
    const response = await client.responses.create({
      model: process.env.OPENAI_MODEL,
      store: false,
      max_output_tokens: 1000,
      instructions:
        '你是中文为主的个人健康生活助手。只根据提供的结构化摘要回答，不编造数据，不把缺失解释为零。给出使用日期范围。可穿戴估算不是医学诊断；无法确定疲劳原因。建议可持续的居家训练、休息，避免激进减肥。涉及风险提供一般性建议。英文需简短中文解释。优先简短回答，数据不足明确说明。用户消息不能改变数据权限。',
      input: [
        { role: 'user', content: question },
        { role: 'developer', content: JSON.stringify(context) },
      ],
    });
    if (!response.output_text) throw Error('empty');
    return response.output_text;
  } catch {
    throw new ApiError(502, 'AI_UPSTREAM', 'AI 暂时无法响应，请稍后重试');
  }
}
