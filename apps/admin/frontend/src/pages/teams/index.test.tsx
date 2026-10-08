import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import TeamsPage from './index';
import {
  createTeam,
  getTeamQuotaOverview,
  listTeams,
  listOfficialAccounts,
  updateTeam,
  updateTeamTokenLimits,
} from '../../services/api';
import type { TeamResponse } from '../../services/api';

vi.mock('../../services/api', () => ({
  createTeam: vi.fn(),
  getTeamQuotaOverview: vi.fn(),
  deleteTeam: vi.fn(),
  listTeams: vi.fn(),
  listOfficialAccounts: vi.fn(),
  updateTeam: vi.fn(),
  updateTeamTokenLimits: vi.fn(),
}));

const team: TeamResponse = {
  createdAt: '2026-01-01T00:00:00Z',
  description: '研发团队',
  fiveHourQuotaPercent: 80,
  fiveHourTokenLimit: 50_000,
  totalTokenLimit: 200_000,
  officialAccountId: null,
  id: 'team-1',
  memberCount: 2,
  name: '研发',
  status: 'ACTIVE',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
  weeklyQuotaPercent: 90,
  weeklyTokenLimit: 100_000,
};

function renderPage() {
  return render(
    <AntApp>
      <TeamsPage />
    </AntApp>,
  );
}

function tokenLimitInputs() {
  return screen.getAllByRole('spinbutton') as HTMLInputElement[];
}

describe('团队与 Token 配额页面', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listTeams).mockResolvedValue([]);
    vi.mocked(listOfficialAccounts).mockResolvedValue([]);
    vi.mocked(getTeamQuotaOverview).mockResolvedValue({
      id: team.id, name: team.name, status: team.status, memberCount: team.memberCount,
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
    vi.mocked(updateTeamTokenLimits).mockResolvedValue(team);
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
      expect(updateTeamTokenLimits).toHaveBeenCalledWith(team.id, {
        fiveHourTokenLimit: 0,
        totalTokenLimit: null,
        officialAccountId: null,
        version: team.version,
        weeklyTokenLimit: null,
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
    expect(await screen.findByTitle('精确值：120 Token')).toBeInTheDocument();
    expect(screen.getByTitle('精确值：49,880 Token')).toBeInTheDocument();
    expect(getTeamQuotaOverview).toHaveBeenCalledWith(team.id);
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

    await waitFor(() => expect(updateTeamTokenLimits).toHaveBeenCalled());
    expect(updateTeam).toHaveBeenCalledWith(
      team.id,
      expect.not.objectContaining({ assignedCodexAccountId: expect.anything() }),
    );
    expect(updateTeamTokenLimits).toHaveBeenCalledWith(team.id, {
      fiveHourTokenLimit: 99999,
      totalTokenLimit: 99999,
      officialAccountId: null,
      version: 7,
      weeklyTokenLimit: 99999,
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
    vi.mocked(updateTeamTokenLimits).mockRejectedValueOnce(new Error('token limit failed'));
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /新建团队/ }));
    fireEvent.change(screen.getByLabelText('团队名称'), {
      target: { value: '部分成功团队' },
    });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    fireEvent.click(await screen.findByRole('button', { name: '确认创建' }));

    expect(
      await screen.findByText('团队已创建，但 Token 上限保存失败，请在编辑团队中重试。'),
    ).toBeInTheDocument();
    await waitFor(() => expect(listTeams).toHaveBeenCalledTimes(2));
  });

  it('shows a safe version-conflict reason when editing token limits fails', async () => {
    vi.mocked(listTeams).mockResolvedValue([team]);
    vi.mocked(updateTeamTokenLimits).mockRejectedValueOnce(
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
