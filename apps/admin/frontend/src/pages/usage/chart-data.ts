import type { UsageAnalytics, UsageAnalyticsParams } from '../../services/api';

export function localDate(value: Date): string {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
}

export function initialDates(now = new Date()) {
  const start = new Date(now);
  start.setDate(start.getDate() - 6);
  return { start: localDate(start), end: localDate(now) };
}

export function dateQuery(start: string, end: string, granularity: 'HOUR' | 'DAY'): UsageAnalyticsParams {
  const from = new Date(`${start}T00:00:00`);
  const to = new Date(`${end}T00:00:00`);
  to.setDate(to.getDate() + 1);
  const duration = to.getTime() - from.getTime();
  if (!Number.isFinite(duration) || duration <= 0 || duration > 93 * 86_400_000) {
    throw new Error('请选择有效日期，统计范围最多 93 天');
  }
  if (granularity === 'HOUR' && duration > 31 * 86_400_000) {
    throw new Error('小时趋势最多 31 天，请改为按天');
  }
  return { from: from.toISOString(), to: to.toISOString(), granularity };
}

export function trendPoints(data: UsageAnalytics) {
  const step = data.granularity === 'HOUR' ? 3_600_000 : 86_400_000;
  const first = Math.floor(Date.parse(data.from) / step) * step;
  const last = Date.parse(data.to);
  const byBucket = new Map(data.trend.map((item) => [Date.parse(item.bucket), item.stats]));
  const result = [];
  for (let time = first; time < last; time += step) {
    const stats = byBucket.get(time);
    const date = new Date(time).toISOString();
    result.push({
      bucket: date,
      label: data.granularity === 'HOUR' ? `${date.slice(5, 10)} ${date.slice(11, 16)}` : date.slice(5, 10),
      totalTokens: stats?.totalTokens ?? 0,
      estimatedCostUsd: stats ? stats.estimatedCostUsd : 0,
    });
  }
  return result;
}
