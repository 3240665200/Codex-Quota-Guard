import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { UserQuotaDetail } from './user';
import { getUser, getUserBillingModes, getUserMeterDevices, getUserOfficialQuota,
  getUserQuotaOverview, getUserUsageEvents } from '../../services/api';
import { resetTeamQuota, resetUserQuota } from '../../services/api';
import { setQuotaDisplayMode } from '../../services/quota-display';
import { usdOverview, usdScope, usdWindow } from '../../test/usd-fixtures';

vi.mock('@umijs/max', () => ({ history: { push: vi.fn() }, useParams: () => ({ id: 'user-1' }) }));

vi.mock('../../services/api', () => ({
  getUser: vi.fn(), getUserMeterDevices: vi.fn(), getUserOfficialQuota: vi.fn(),
  getUserQuotaOverview: vi.fn(), getUserUsageEvents: vi.fn(),
  resetTeamQuota: vi.fn(), resetUserQuota: vi.fn(),
  getUserBillingModes: vi.fn().mockResolvedValue([]), setUserBillingMode: vi.fn(), confirmUsageBillingMode: vi.fn(),
}));

describe('独立用户额度页面', () => {
  beforeEach(() => {
    setQuotaDisplayMode('used');
    vi.clearAllMocks();
    vi.mocked(getUserBillingModes).mockResolvedValue([]);
    vi.mocked(getUser).mockResolvedValue({ id: 'user-1', displayName: 'Alice',
      email: 'alice@example.com', fiveHourTokenLimit: 1000,
      weeklyTokenLimit: 2000 } as never);
    vi.mocked(getUserQuotaOverview).mockResolvedValue({
      billing: usdOverview({ personal: usdScope(usdWindow('10', '2.8', '7.2'), usdWindow('20', '2.8', '17.2')),
        team: usdScope(usdWindow('5', '5.1', '0'), usdWindow('10', '5.1', '4.9'), usdWindow('20', '8', '12')) }),
      policy: { fiveHourTokenLimit: 1000, weeklyTokenLimit: 2000 },
      usage: { fiveHourUsedTokens: 280, weeklyUsedTokens: 280,
        fiveHourRemainingTokens: 0, weeklyRemainingTokens: 490 },
      teamQuota: { id: 'team-1', name: '研发', status: 'ACTIVE', memberCount: 2,
        fiveHourTokenLimit: 500, weeklyTokenLimit: 1000,
        totalTokenLimit: 2000,
        fiveHourUsedTokens: 510, weeklyUsedTokens: 510,
        totalUsedTokens: 800,
        fiveHourRemainingTokens: 0, weeklyRemainingTokens: 490,
        totalRemainingTokens: 1200,
        measuredAt: '2026-10-08T04:00:00Z' },
      allowed: false, blockReason: 'TEAM_FIVE_HOUR_USD_QUOTA_EXCEEDED',
    });
    vi.mocked(getUserOfficialQuota).mockResolvedValue({ planType: 'plus',
      fetchedAt: '2026-10-08T04:00:00Z', receivedAt: '2026-10-08T04:00:00Z',
      windows: [{ limitId: 'codex', slot: 'primary', windowDurationMins: 300,
        usedPercent: 25.5, resetsAt: null }] });
    vi.mocked(getUserMeterDevices).mockResolvedValue([]);
    vi.mocked(getUserUsageEvents).mockResolvedValue({
      items: [{ id: 'event-1', occurredAt: '2026-10-08T04:00:00Z',
        receivedAt: '2026-10-08T04:00:01Z', totalTokens: 42,
        source: 'TOKEN_USAGE_RECORD' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    vi.mocked(resetTeamQuota).mockResolvedValue(undefined);
    vi.mocked(resetUserQuota).mockResolvedValue(undefined);
  });

  it('separates personal, shared team, official and ledger usage', async () => {
    render(<AntApp><UserQuotaDetail userId="user-1" /></AntApp>);
    expect(await screen.findByText('Alice的额度')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '个人用量' })).toHaveTextContent('已用 $2.80 USD');
    expect(screen.getByRole('region', { name: '团队共享额度' })).toHaveTextContent('已用 $5.10 USD');
    expect(screen.getByText('团队 5 小时共享额度已用尽')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '官方额度' })).toHaveTextContent('25.5%');
    expect(await screen.findByText('42 Token')).toBeInTheDocument();
    expect(getUserUsageEvents).toHaveBeenCalledWith('user-1', 0, 10);
  });

  it('offers separate confirmed personal and team resets', async () => {
    render(<AntApp><UserQuotaDetail userId="user-1" /></AntApp>);
    await screen.findByText('Alice的额度');
    fireEvent.click(screen.getByRole('button', { name: '重置个人额度' }));
    expect(screen.getByText(/团队共享用量不变/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认重置' }));
    await waitFor(() => expect(resetUserQuota).toHaveBeenCalledWith('user-1'));

    fireEvent.click(screen.getByRole('button', { name: '重置团队额度' }));
    expect(screen.getByText(/全体成员的个人用量不变/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认重置' }));
    await waitFor(() => expect(resetTeamQuota).toHaveBeenCalledWith('team-1'));
  });
});
