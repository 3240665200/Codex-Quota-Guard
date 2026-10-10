import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UsageAnalytics, UsageStats } from '../../services/api';
import { getUsageAnalytics, getUsageOptions, listUsageModelPrices, saveUsageModelPrice } from '../../services/api';
import UsagePage from './index';

vi.mock('@ant-design/pro-components', () => ({ PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main> }));
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  AreaChart: () => <div>trend</div>, Area: () => null, CartesianGrid: () => null,
  Tooltip: () => null, XAxis: () => null, YAxis: () => null,
}));
vi.mock('../../services/api', () => ({
  getUsageAnalytics: vi.fn(), getUsageOptions: vi.fn(), listUsageModelPrices: vi.fn(),
  saveUsageModelPrice: vi.fn(), deleteUsageModelPrice: vi.fn(), getUserUsageEvents: vi.fn(),
}));
const stats: UsageStats = { eventCount: 21, totalTokens: 1200, inputTokens: 1000, cachedInputTokens: 300,
  cacheWriteInputTokens: 100, outputTokens: 200, reasoningOutputTokens: 150,
  breakdownEventCount: 1, costedEventCount: 1, estimatedCostUsd: 0.00325, cacheHitRatePercent: 30 };
const data: UsageAnalytics = {
  from: '2026-10-01T00:00:00Z', to: '2026-10-08T00:00:00Z', currency: 'USD', timezone: 'UTC', granularity: 'DAY',
  summary: stats, trend: [{ bucket: '2026-10-01T00:00:00Z', stats }],
  models: [{ key: 'test-model', label: 'test-model', stats }], reasoningEfforts: [], users: [], teams: [],
  events: { page: 0, size: 20, totalElements: 21, totalPages: 2, items: [{
    id: 'event-1', userId: 'u1', userName: 'Test User', teamId: 't1', teamName: 'Test Team',
    occurredAt: '2026-10-07T00:00:00Z', receivedAt: '2026-10-07T00:00:01Z', totalTokens: 1200,
    source: 'TOKEN_USAGE_RECORD', model: 'test-model', reasoningEffort: 'high',
    inputTokens: 1000, cachedInputTokens: 300, cacheWriteInputTokens: 100,
    outputTokens: 200, reasoningOutputTokens: 150, estimatedCostUsd: 0.00325, costStatus: 'PRICED',
  }] },
};

describe('usage statistics', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getUsageAnalytics).mockResolvedValue(data);
    vi.mocked(getUsageOptions).mockResolvedValue({ models: [{ value: 'test-model', label: 'test-model' }], users: [], teams: [] });
    vi.mocked(listUsageModelPrices).mockResolvedValue([]);
  });
  it('shows USD, missing-price coverage and preserves all ledger fields', async () => {
    render(<AntApp><UsagePage /></AntApp>);
    expect(await screen.findByText('Test User')).toBeVisible();
    expect(screen.getByText('Test Team')).toBeVisible();
    expect(screen.getByText('high')).toBeVisible();
    expect(screen.getAllByText('USD 0.00325')).toHaveLength(2);
    expect(screen.getByText('可估价 1 / 21 条 (5%)')).toBeVisible();
    expect(screen.getByText('费用仅包含分项完整且已定价的记录')).toBeVisible();
    expect(screen.getByRole('img', { name: 'Token 用量趋势图' })).toBeVisible();
    fireEvent.click(screen.getAllByTitle('Next Page')[0]);
    await waitFor(() => expect(getUsageAnalytics).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 20 })));
    fireEvent.change(screen.getByLabelText('开始日期'), { target: { value: '2026-10-05' } });
    fireEvent.change(screen.getByLabelText('结束日期'), { target: { value: '2026-10-06' } });
    fireEvent.click(screen.getByRole('button', { name: /查询/ }));
    await waitFor(() => expect(getUsageAnalytics).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0,
      from: new Date('2026-10-05T00:00:00').toISOString(), to: new Date('2026-10-07T00:00:00').toISOString() })));
  });
  it('keeps valid data out of a failed page instead of relabeling it', async () => {
    vi.mocked(getUsageAnalytics).mockResolvedValueOnce(data).mockRejectedValueOnce(new Error('读取失败'));
    render(<AntApp><UsagePage /></AntApp>);
    await screen.findByText('Test User');
    fireEvent.click(screen.getAllByTitle('Next Page')[0]);
    expect(await screen.findByText('统计读取失败')).toBeVisible();
    expect(screen.queryByText('Test User')).not.toBeInTheDocument();
  });
  it('requires explicit prices, including zero, and saves all USD components', async () => {
    vi.mocked(saveUsageModelPrice).mockResolvedValue({ model: 'test-model', inputPrice: 2, outputPrice: 8,
      cacheReadPrice: 0.5, cacheWritePrice: 0, currency: 'USD', updatedAt: '2026-10-10T00:00:00Z',
      priceSource: 'MANUAL', sourceReference: null, sourceCheckedAt: null });
    render(<AntApp><UsagePage /></AntApp>);
    await screen.findByText('Test User');
    fireEvent.click(screen.getByRole('tab', { name: '模型定价' }));
    fireEvent.click(screen.getByRole('button', { name: /添加定价/ }));
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await screen.findByText('请输入模型名称');
    expect(saveUsageModelPrice).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: 'test-model' } });
    const fields = screen.getAllByRole('spinbutton');
    [2, 8, 0.5, 0].forEach((value, index) => fireEvent.change(fields[index], { target: { value: String(value) } }));
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(saveUsageModelPrice).toHaveBeenCalledWith({
      model: 'test-model', inputPrice: 2, outputPrice: 8, cacheReadPrice: 0.5, cacheWritePrice: 0,
    }));
  });
  it('shows the CC Switch snapshot source beside imported prices', async () => {
    vi.mocked(listUsageModelPrices).mockResolvedValue([{ model: 'gpt-6.1-sol', inputPrice: 2, outputPrice: 10,
      cacheReadPrice: 0.1, cacheWritePrice: 2.5, currency: 'USD', updatedAt: '2026-10-10T00:00:00Z',
      priceSource: 'CC_SWITCH', sourceCheckedAt: '2026-10-10',
      sourceReference: 'https://github.com/farion1231/cc-switch/blob/example/src-tauri/src/database/schema.rs' }]);
    render(<AntApp><UsagePage /></AntApp>);
    await screen.findByText('Test User');
    fireEvent.click(screen.getByRole('tab', { name: '模型定价' }));
    expect(screen.getByRole('link', { name: 'CC Switch 快照 · 2026-10-10' })).toHaveAttribute('href',
      'https://github.com/farion1231/cc-switch/blob/example/src-tauri/src/database/schema.rs');
  });
});
