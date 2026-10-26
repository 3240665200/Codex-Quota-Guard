import { usdScope, usdWindow } from '../../test/usd-fixtures';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp, ConfigProvider } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import TeamsPage from './index';
import {
  createTeam,
  getTeamQuotaOverview,
  listTeams,
  listOfficialAccounts,
  listUsers,
  updateTeam,
  updateTeamMembers,
  updateTeamUsdLimits,
} from '../../services/api';
import type { TeamResponse } from '../../services/api';

vi.mock('../../services/api', () => ({
  createTeam: vi.fn(),
  getTeamQuotaOverview: vi.fn(),
  deleteTeam: vi.fn(),
  listTeams: vi.fn(),
  listOfficialAccounts: vi.fn(),
  listUsers: vi.fn(),
  updateTeam: vi.fn(),
  updateTeamMembers: vi.fn(),
  updateTeamUsdLimits: vi.fn(),
}));

const team: TeamResponse = {
  fiveHourTokenLimit: 50000, weeklyTokenLimit: 100000, totalTokenLimit: 200000,
  createdAt: '2026-01-01T00:00:00Z',
  description: '研发团队',
  fiveHourQuotaPercent: 80,
  fiveHourUsdLimit: '50',
  totalUsdLimit: '200',
  officialAccountId: null,
  id: 'team-1',
  memberCount: 2,
  name: '研发',
  status: 'ACTIVE',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
  weeklyQuotaPercent: 90,
  weeklyUsdLimit: '100',
};

function renderPage() {
  return render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <AntApp>
        <TeamsPage />
      </AntApp>
    </ConfigProvider>,
  );
}

function tokenLimitInputs() {
  return screen.getAllByRole('spinbutton') as HTMLInputElement[];
}

describe('团队与 USD 配额页面', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listTeams).mockResolvedValue([]);
    vi.mocked(listOfficialAccounts).mockResolvedValue([]);
    vi.mocked(getTeamQuotaOverview).mockResolvedValue({
      id: team.id, name: team.name, status: team.status, memberCount: team.memberCount,
      billing: usdScope(usdWindow('50', '0.12', '49.88'), usdWindow('100', '0.30', '99.70'), usdWindow('200', '0.50', '199.50')),
      fiveHourTokenLimit: team.fiveHourTokenLimit,
      weeklyTokenLimit: team.weeklyTokenLimit,
      totalTokenLimit: team.totalTokenLimit,
      fiveHourUsedTokens: 120, weeklyUsedTokens: 300,
      totalUsedTokens: 500,
      fiveHourRemainingTokens: 49_880, weeklyRemainingTokens: 99_700,
      totalRemainingTokens: 199_500,
      measuredAt: '2026-10-08T04:00:00Z',
    });
    vi.mocked(createTeam).mockResolvedValue(team);
    vi.mocked(updateTeam).mockResolvedValue(team);
    vi.mocked(updateTeamUsdLimits).mockResolvedValue(usdScope());
    vi.mocked(updateTeamMembers).mockResolvedValue({ ...team, memberCount: 3 });
    vi.mocked(listUsers).mockResolvedValue({ items: [{
      id: 'candidate', displayName: '待加入用户', email: 'candidate@example.com',
      teamId: null, role: 'USER', status: 'ACTIVE',
      fiveHourQuotaPercent: null, weeklyQuotaPercent: null,
      fiveHourUsdLimit: null, weeklyUsdLimit: null,
      fiveHourTokenLimit: null, weeklyTokenLimit: null,
      createdAt: team.createdAt, updatedAt: team.updatedAt,
    }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
  });

  it('creates a team with a direct token limit and legacy percent defaults hidden', async () => {
    renderPage();
    expect(listTeams).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /新建团队/ }));
    fireEvent.change(screen.getByLabelText('团队名称'), {
      target: { value: '测试团队' },
    });

    const inputs = tokenLimitInputs();
    fireEvent.change(inputs[1], { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    fireEvent.click(await screen.findByRole('button', { name: '确认创建' }));
    expect(createTeam).toHaveBeenCalledOnce();
    const payload = vi.mocked(createTeam).mock.calls[0]?.[0];
    expect(payload?.name).toBe('测试团队');
    expect(payload?.fiveHourQuotaPercent).toBe(100);
    expect(payload?.weeklyQuotaPercent).toBe(100);
    expect(payload).not.toHaveProperty('assignedCodexAccountId');
    await waitFor(() =>
      expect(updateTeamUsdLimits).toHaveBeenCalledWith(team.id, {
        fiveHourUsdLimit: '0',
        totalUsdLimit: null,
        officialAccountId: null,
        version: team.version,
        weeklyUsdLimit: null,
      }),
    );
  });

  it('does not offer centrally managed Codex accounts', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /新建团队/ }));
    expect(screen.queryByRole('combobox', { name: '选择托管 Codex 账户' })).not.toBeInTheDocument();
  });

  it('shows shared consumption next to the team limits', async () => {
    vi.mocked(listTeams).mockResolvedValue([team]);
    renderPage();
    expect(await screen.findByTitle('49.88 USD')).toBeInTheDocument();
    expect(screen.getByTitle('50 USD')).toBeInTheDocument();
    expect(getTeamQuotaOverview).toHaveBeenCalledWith(team.id);
  });

  it('shows empty limits as unlimited while preserving measured usage', async () => {
    vi.mocked(listTeams).mockResolvedValue([{
      ...team, fiveHourUsdLimit: null, weeklyUsdLimit: null, totalUsdLimit: null,
    }]);
    vi.mocked(getTeamQuotaOverview).mockResolvedValue({
      id: team.id, name: team.name, status: team.status, memberCount: team.memberCount,
      billing: usdScope(usdWindow(null, '0.12'), usdWindow(null, '0.30'), usdWindow(null, '0.50')),
      fiveHourTokenLimit: null, weeklyTokenLimit: null, totalTokenLimit: null,
      fiveHourUsedTokens: 120, weeklyUsedTokens: 300, totalUsedTokens: 500,
      fiveHourRemainingTokens: null, weeklyRemainingTokens: null, totalRemainingTokens: null,
      measuredAt: '2026-10-08T04:00:00Z',
    });
    renderPage();
    expect(await screen.findByTitle('0.12 USD')).toBeInTheDocument();
    expect(screen.getAllByText(/无限制/)).toHaveLength(3);
    expect(screen.queryByText('未配置')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '编辑 研发' }));
    await waitFor(() => expect(screen.getByText('留空表示不设 7 天限制')).toBeVisible(), { timeout: 5000 });
    expect(screen.queryByText('7 天基础额度；现有团队暂需配置')).not.toBeInTheDocument();
  });

  it('opens member management from a visible team action and reloads member counts after saving', async () => {
    vi.mocked(listTeams).mockResolvedValueOnce([team])
      .mockResolvedValue([{ ...team, memberCount: 3 }]);
    renderPage();
    const manage = await screen.findByRole('button', { name: '管理成员 研发' });
    expect(manage).toHaveTextContent('管理成员');
    fireEvent.click(manage);
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 待加入用户' }));
    fireEvent.click(screen.getByRole('button', { name: '保存成员' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认变更' }));

    await waitFor(() => expect(listTeams).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('button', { name: '研发 成员 3 人' })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('管理成员：研发')).not.toBeInTheDocument());
  });

  it('also opens the independent member dialog from the member count', async () => {
    vi.mocked(listTeams).mockResolvedValue([team]);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '研发 成员 2 人' }));
    const title = await screen.findByText('管理成员：研发');
    const dialog = title.closest('[role="dialog"]') as HTMLElement;
    await waitFor(() => expect(dialog).toBeVisible());
    expect(within(dialog).queryByLabelText('团队名称')).not.toBeInTheDocument();
  });

  it('updates token limits without any managed account assignment', async () => {
    vi.mocked(listTeams).mockResolvedValue([team]);
    vi.mocked(updateTeam).mockResolvedValue({ ...team, version: 7 });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '编辑 研发' }));

    const inputs = tokenLimitInputs();
    fireEvent.change(inputs[0], { target: { value: '99999' } });
    fireEvent.change(inputs[1], { target: { value: '99999' } });
    fireEvent.change(inputs[2], { target: { value: '99999' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    fireEvent.click(await screen.findByRole('button', { name: '确认保存' }));

    await waitFor(() => expect(updateTeamUsdLimits).toHaveBeenCalled());
    expect(updateTeam).toHaveBeenCalledWith(
      team.id,
      expect.not.objectContaining({ assignedCodexAccountId: expect.anything() }),
    );
    expect(updateTeamUsdLimits).toHaveBeenCalledWith(team.id, {
      fiveHourUsdLimit: '99999',
      totalUsdLimit: '99999',
      officialAccountId: null,
      version: 7,
      weeklyUsdLimit: '99999',
    });
  });

  it('blocks a token limit larger than the supported maximum in the create form', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /新建团队/ }));

    const inputs = tokenLimitInputs();
    fireEvent.change(inputs[0], { target: { value: '1000000000001' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    expect(createTeam).not.toHaveBeenCalled();
  });

  it('validates the edit form before allowing a token limit above the supported maximum', async () => {
    vi.mocked(listTeams).mockResolvedValue([team]);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '编辑 研发' }));

    const inputs = tokenLimitInputs();
    fireEvent.change(inputs[1], { target: { value: '1000000000001' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(updateTeam).not.toHaveBeenCalled();
  });

  it('reloads and explains the partial result when the new team token limits fail', async () => {
    vi.mocked(updateTeamUsdLimits).mockRejectedValueOnce(new Error('token limit failed'));
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /新建团队/ }));
    fireEvent.change(screen.getByLabelText('团队名称'), {
      target: { value: '部分成功团队' },
    });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    fireEvent.click(await screen.findByRole('button', { name: '确认创建' }));

    expect(
      await screen.findByText('团队已创建，但 USD 上限保存失败，请在编辑团队中重试。'),
    ).toBeInTheDocument();
    await waitFor(() => expect(listTeams).toHaveBeenCalledTimes(2));
  });

  it('shows a safe version-conflict reason when editing token limits fails', async () => {
    vi.mocked(listTeams).mockResolvedValue([team]);
    vi.mocked(updateTeamUsdLimits).mockRejectedValueOnce(
      Object.assign(new Error('do not show raw server details'), { status: 409 }),
    );
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '编辑 研发' }));
    const inputs = tokenLimitInputs();
    fireEvent.change(inputs[0], { target: { value: '99999' } });
    fireEvent.change(inputs[1], { target: { value: '99999' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    fireEvent.click(await screen.findByRole('button', { name: '确认保存' }));

    expect(await screen.findByText(/团队版本冲突/)).toBeInTheDocument();
    expect(screen.queryByText(/do not show raw server details/)).not.toBeInTheDocument();
  });
});
