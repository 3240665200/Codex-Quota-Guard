import type { UsageAnalytics, UsageAnalyticsParams } from '../../services/api';

const HOUR_MS = 60 * 60 * 1000;
const FIVE_MINUTES_MS = 5 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

export function dashboardUsageQueries(now = new Date()): {
  lastFiveMinutes: UsageAnalyticsParams;
  last24Hours: UsageAnalyticsParams;
} {
  const to = now.toISOString();
  const query = (duration: number): UsageAnalyticsParams => ({
    from: new Date(now.getTime() - duration).toISOString(),
    to,
    granularity: 'HOUR',
    page: 0,
    size: 8,
  });

  return {
    lastFiveMinutes: query(FIVE_MINUTES_MS),
    last24Hours: query(DAY_MS),
  };
}

export function averageRate(value: number, seconds = FIVE_MINUTES_MS / 1000): string {
  if (!Number.isFinite(value) || seconds <= 0) return '—';
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 }).format(value / seconds);
}

export function hourlyRatePoints(data: UsageAnalytics) {
  const from = Date.parse(data.from);
  const to = Date.parse(data.to);
  if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) return [];

  const statsByHour = new Map(data.trend.map(({ bucket, stats }) => [Date.parse(bucket), stats]));
  const points = [];
  for (let bucket = Math.floor(from / HOUR_MS) * HOUR_MS; bucket < to; bucket += HOUR_MS) {
    const intervalStart = Math.max(bucket, from);
    const intervalEnd = Math.min(bucket + HOUR_MS, to);
    const intervalSeconds = Math.max(1, (intervalEnd - intervalStart) / 1000);
    const stats = statsByHour.get(bucket);
    const date = new Date(bucket).toISOString();
    points.push({
      bucket: date,
      label: date.slice(11, 16),
      eventQps: (stats?.eventCount ?? 0) / intervalSeconds,
      tokenTps: (stats?.totalTokens ?? 0) / intervalSeconds,
    });
  }
  return points;
}
