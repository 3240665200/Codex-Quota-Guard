import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UsageAnalytics, UsageStats } from '../../services/api';
import { getDashboard, getUsageAnalytics } from '../../services/api';
import DashboardPage from './index';

vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children, title, content, extra }: {
    children: React.ReactNode; title: string; content?: string; extra?: React.ReactNode[];
  }) => <main><h1>{title}</h1><p>{content}</p>{extra}{children}</main>,
}));
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  AreaChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Area: () => null, CartesianGrid: () => null, Tooltip: () => null, XAxis: () => null, YAxis: () => null,
}));
vi.mock('../../components/DashboardStatCard', () => ({
  DashboardStatCard: ({ title, value, detail }: { title: string; value: React.ReactNode; detail?: string }) => (
    <article><span>{title}</span><strong>{value}</strong>{detail ? <small>{detail}</small> : null}</article>
  ),
}));
vi.mock('../../services/api', () => ({ getDashboard: vi.fn(), getUsageAnalytics: vi.fn() }));

const stats: UsageStats = {
  eventCount: 120, totalTokens: 6000, inputTokens: 5000, cachedInputTokens: 1200,
  cacheWriteInputTokens: 50, outputTokens: 1000, reasoningOutputTokens: 300,
  breakdownEventCount: 120, costedEventCount: 110, estimatedCostUsd: 0.25, cacheHitRatePercent: 24,
};
const occurredAt = '2026-10-10T11:59:00.000Z';

function analytics(summary = stats): UsageAnalytics {
  return {
    from: '2026-10-09T12:00:00.000Z', to: '2026-10-10T12:00:00.000Z', currency: 'USD', timezone: 'UTC',
    granularity: 'HOUR', summary, trend: [{ bucket: '2026-10-10T11:00:00.000Z', stats: summary }],
    models: [{ key: 'gpt-test', label: 'gpt-test', stats: summary }], reasoningEfforts: [], users: [], teams: [],
    events: {
      page: 0, size: 8, totalElements: 1, totalPages: 1,
      items: [{ id: 'event-1', userId: 'user-1', userName: 'Alice', teamId: 'team-1', teamName: 'Team A',
        occurredAt, receivedAt: '2026-10-10T11:59:01.000Z', totalTokens: 6000,
        source: 'TOKEN_USAGE_RECORD', model: 'gpt-test', reasoningEffort: 'high', inputTokens: 5000,
        cachedInputTokens: 1200, cacheWriteInputTokens: 50, outputTokens: 1000,
        reasoningOutputTokens: 300, estimatedCostUsd: 0.25, costStatus: 'PRICED' }],
    },
  };
}

describe('dashboard overview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getDashboard).mockResolvedValue({ totalUsers: 12, activeUsers: 10, disabledUsers: 2, adminUsers: 1, newUsersLast7Days: 3 });
    vi.mocked(getUsageAnalytics).mockImplementation(async (query) => {
      const fiveMinutes = Date.parse(query.to!) - Date.parse(query.from!) < 6 * 60_000;
      return analytics(fiveMinutes ? { ...stats, eventCount: 90, totalTokens: 30_000 } : stats);
    });
  });

  it('shows event-based QPS/TPS, 24-hour breakdown, models and recent usage', async () => {
    render(<AntApp><DashboardPage /></AntApp>);

    expect(await screen.findByText('事件 QPS（估算）')).toBeVisible();
    expect(screen.getByText('0.3')).toBeVisible();
    expect(screen.getByText('100')).toBeVisible();
    expect(screen.getByText('近 24 小时事件')).toBeVisible();
    expect(screen.getByText('模型用量排行')).toBeVisible();
    expect(screen.getAllByText('gpt-test').length).toBeGreaterThan(0);
    expect(screen.getByText('Alice')).toBeVisible();
    const formattedOccurredAt = new Intl.DateTimeFormat('zh-CN', {
      dateStyle: 'long', timeStyle: 'medium', hourCycle: 'h23',
    }).format(new Date(occurredAt));
    expect(screen.getByText(formattedOccurredAt)).toBeVisible();
    expect(screen.getByText(/官方 API 网关请求/)).toBeVisible();
    expect(screen.getByRole('img', { name: '近 24 小时QPS趋势图' })).toBeVisible();

    fireEvent.click(screen.getByRole('radio', { name: 'TPS' }));
    expect(screen.getByRole('img', { name: '近 24 小时TPS趋势图' })).toBeVisible();
  });

  it('requests five-minute and 24-hour windows from the existing analytics API', async () => {
    render(<AntApp><DashboardPage /></AntApp>);
    await screen.findByText('事件 QPS（估算）');
    await waitFor(() => expect(getUsageAnalytics).toHaveBeenCalledTimes(2));

    const queries = vi.mocked(getUsageAnalytics).mock.calls.map(([query]) => query);
    const durations = queries.map(({ from, to }) => Date.parse(to!) - Date.parse(from!)).sort((a, b) => a - b);
    expect(durations).toEqual([5 * 60_000, 24 * 60 * 60_000]);
    expect(queries.every(({ granularity }) => granularity === 'HOUR')).toBe(true);
  });
});
