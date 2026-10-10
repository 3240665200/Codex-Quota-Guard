import { describe, expect, it } from 'vitest';
import type { UsageAnalytics, UsageStats } from '../../services/api';
import { averageRate, dashboardUsageQueries, hourlyRatePoints } from './dashboard-data';

const stats = (eventCount: number, totalTokens: number): UsageStats => ({
  eventCount, totalTokens, inputTokens: totalTokens, cachedInputTokens: 0, cacheWriteInputTokens: 0,
  outputTokens: 0, reasoningOutputTokens: 0, breakdownEventCount: eventCount, costedEventCount: eventCount,
  estimatedCostUsd: 0.01, cacheHitRatePercent: 0,
});

describe('dashboard usage metrics', () => {
  it('builds a five-minute rate window and a 24-hour trend window', () => {
    const queries = dashboardUsageQueries(new Date('2026-10-10T12:00:00.000Z'));
    expect(Date.parse(queries.lastFiveMinutes.to!) - Date.parse(queries.lastFiveMinutes.from!)).toBe(5 * 60_000);
    expect(Date.parse(queries.last24Hours.to!) - Date.parse(queries.last24Hours.from!)).toBe(24 * 60 * 60_000);
    expect(queries.lastFiveMinutes).toMatchObject({ granularity: 'HOUR', page: 0, size: 8 });
    expect(queries.last24Hours.to).toBe(queries.lastFiveMinutes.to);
  });

  it('calculates rates and normalizes partial hourly buckets', () => {
    const data: UsageAnalytics = {
      from: '2026-10-10T09:30:00.000Z', to: '2026-10-10T10:30:00.000Z',
      currency: 'USD', timezone: 'UTC', granularity: 'HOUR', summary: stats(240, 4800),
      trend: [
        { bucket: '2026-10-10T09:00:00.000Z', stats: stats(180, 3600) },
        { bucket: '2026-10-10T10:00:00.000Z', stats: stats(60, 1200) },
      ], models: [], reasoningEfforts: [], users: [], teams: [],
      events: { items: [], page: 0, size: 8, totalElements: 240, totalPages: 30 },
    };

    expect(averageRate(90)).toBe('0.3');
    expect(hourlyRatePoints(data)).toEqual([
      { bucket: '2026-10-10T09:00:00.000Z', label: '09:00', eventQps: 0.1, tokenTps: 2 },
      { bucket: '2026-10-10T10:00:00.000Z', label: '10:00', eventQps: 1 / 30, tokenTps: 2 / 3 },
    ]);
  });
});
