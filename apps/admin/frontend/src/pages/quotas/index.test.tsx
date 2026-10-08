import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import QuotasPage from './index';
import { getTeamQuotaOverview, listTeams, listUsers, resetTeamQuota } from '../../services/api';

vi.mock('@umijs/max', () => ({ history: { push: vi.fn() } }));

vi.mock('../../services/api', () => ({
  getTeamQuotaOverview: vi.fn(), listTeams: vi.fn(), listUsers: vi.fn(),
  resetTeamQuota: vi.fn(),
}));

describe('额度查看页面', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listTeams).mockResolvedValue([{ id: 'team-1', name: '研发', memberCount: 2 } as never]);
    vi.mocked(getTeamQuotaOverview).mockResolvedValue({
      id: 'team-1', name: '研发', status: 'ACTIVE', memberCount: 2,
      fiveHourTokenLimit: 500, weeklyTokenLimit: 1000,
      totalTokenLimit: 2000,
      fiveHourUsedTokens: 510, weeklyUsedTokens: 510,
      totalUsedTokens: 800,
      fiveHourRemainingTokens: 0, weeklyRemainingTokens: 490,
      totalRemainingTokens: 1200,
      measuredAt: '2026-10-08T04:00:00Z',
    });
    vi.mocked(listUsers).mockResolvedValue({
      items: [{ id: 'user-1', displayName: 'Alice', email: 'alice@example.com',
        fiveHourTokenLimit: null, weeklyTokenLimit: null } as never],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    vi.mocked(resetTeamQuota).mockResolvedValue(undefined);
  });

  it('shows shared team totals and lets an administrator refresh them', async () => {
    render(<AntApp><QuotasPage /></AntApp>);
    expect((await screen.findAllByTitle('精确值：510 Token')).length).toBe(2);
    expect(screen.getByTitle('精确值：500 Token')).toBeInTheDocument();
    expect(screen.getByTitle('精确值：0 Token')).toBeInTheDocument();
    expect(screen.getByTitle('精确值：1,000 Token')).toBeInTheDocument();
    expect(screen.getByText('Alice')).toBeInTheDocument();
    fireEvent.click(screen.getByText('刷新'));
    await waitFor(() => expect(getTeamQuotaOverview).toHaveBeenCalledTimes(2));
  });

  it('shows a large team total in units while preserving its exact value', async () => {
    vi.mocked(getTeamQuotaOverview).mockResolvedValue({
      id: 'team-1', name: '研发', status: 'ACTIVE', memberCount: 2,
      fiveHourTokenLimit: 100_000, weeklyTokenLimit: 1_000_000_000,
      totalTokenLimit: 2_000_000_000,
      fiveHourUsedTokens: 99_999, weeklyUsedTokens: 808_239_710,
      totalUsedTokens: 900_000_000,
      fiveHourRemainingTokens: 1, weeklyRemainingTokens: 191_760_290,
      totalRemainingTokens: 1_100_000_000,
      measuredAt: '2026-10-08T04:00:00Z',
    });
    render(<AntApp><QuotasPage /></AntApp>);
    expect(await screen.findByText('约8.08亿 Token')).toHaveAttribute(
      'title', '精确值：808,239,710 Token',
    );
    expect(screen.getByText('约10万 Token')).toHaveAttribute(
      'title', '精确值：99,999 Token',
    );
  });

  it('confirms a team reset and refreshes shared usage in place', async () => {
    vi.mocked(getTeamQuotaOverview).mockResolvedValueOnce({
      id: 'team-1', name: '研发', status: 'ACTIVE', memberCount: 2,
      fiveHourTokenLimit: 500, weeklyTokenLimit: 1000,
      totalTokenLimit: 2000,
      fiveHourUsedTokens: 510, weeklyUsedTokens: 510,
      totalUsedTokens: 800,
      fiveHourRemainingTokens: 0, weeklyRemainingTokens: 490,
      totalRemainingTokens: 1200,
      measuredAt: '2026-10-08T04:00:00Z',
    }).mockResolvedValue({
      id: 'team-1', name: '研发', status: 'ACTIVE', memberCount: 2,
      fiveHourTokenLimit: 500, weeklyTokenLimit: 1000,
      totalTokenLimit: 2000,
      fiveHourUsedTokens: 0, weeklyUsedTokens: 0,
      totalUsedTokens: 0,
      fiveHourRemainingTokens: 500, weeklyRemainingTokens: 1000,
      totalRemainingTokens: 2000,
      measuredAt: '2026-10-08T04:00:00Z',
    });
    render(<AntApp><QuotasPage /></AntApp>);
    expect((await screen.findAllByTitle('精确值：510 Token')).length).toBe(2);
    fireEvent.click(screen.getByRole('button', { name: '重置团队额度' }));
    expect(screen.getByText(/成员的个人用量不变/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认重置' }));
    await waitFor(() => expect(resetTeamQuota).toHaveBeenCalledWith('team-1'));
    expect((await screen.findAllByTitle('精确值：0 Token')).length).toBe(3);
    expect(getTeamQuotaOverview).toHaveBeenCalledTimes(2);
  });
});
