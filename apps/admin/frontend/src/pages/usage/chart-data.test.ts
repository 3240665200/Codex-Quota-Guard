import { describe, expect, it } from 'vitest';
import type { UsageAnalytics } from '../../services/api';
import { dateQuery, initialDates, trendPoints } from './chart-data';
import { formatUsd } from '../../components/UsageAmounts';

describe('usage analytics presentation', () => {
  it('keeps date boundaries exclusive and validates query size', () => {
    const now = new Date(2026, 9, 10, 12);
    expect(initialDates(now)).toEqual({ start: '2026-10-04', end: '2026-10-10' });
    const query = dateQuery('2026-10-10', '2026-10-10', 'HOUR');
    expect(Date.parse(query.to!) - Date.parse(query.from!)).toBe(86_400_000);
    expect(() => dateQuery('', '2026-10-10', 'DAY')).toThrow();
    expect(() => dateQuery('2026-01-01', '2026-10-10', 'DAY')).toThrow();
    expect(() => dateQuery('2026-09-01', '2026-10-10', 'HOUR')).toThrow();
  });
  it('fills only empty buckets with zero, not unknown cost for existing events', () => {
    const data = { from: '2026-10-01T00:00:00Z', to: '2026-10-04T00:00:00Z', granularity: 'DAY',
      trend: [{ bucket: '2026-10-02T00:00:00Z', stats: { totalTokens: 1200, estimatedCostUsd: null } }] } as UsageAnalytics;
    const points = trendPoints(data);
    expect(points).toHaveLength(3);
    expect(points[0]).toMatchObject({ totalTokens: 0, estimatedCostUsd: 0 });
    expect(points[1]).toMatchObject({ totalTokens: 1200, estimatedCostUsd: null });
  });
  it('does not round micro USD to a misleading zero', () => {
    expect(formatUsd(0)).toBe('0.00');
    expect(formatUsd(0.00325)).toBe('0.00325');
    expect(formatUsd(0.0000001)).toBe('< 0.000001');
  });
});
