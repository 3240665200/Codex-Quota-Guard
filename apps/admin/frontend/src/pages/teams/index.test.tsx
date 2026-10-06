import { fireEvent, render, screen } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import TeamsPage from './index';
import { createTeam, listTeams, updateTeam } from '../../services/api';
import type { TeamResponse } from '../../services/api';

vi.mock('../../services/api', () => ({
  createTeam: vi.fn(),
  deleteTeam: vi.fn(),
  listTeams: vi.fn(),
  updateTeam: vi.fn(),
}));

const team: TeamResponse = {
  createdAt: '2026-01-01T00:00:00Z',
  description: '研发团队',
  fiveHourQuotaPercent: 80,
  id: 'team-1',
  memberCount: 2,
  name: '研发',
  status: 'ACTIVE',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
  weeklyQuotaPercent: 90,
};

function renderPage() {
  return render(
    <AntApp>
      <TeamsPage />
    </AntApp>,
  );
}

function quotaInputs() {
  return screen.getAllByRole('spinbutton') as HTMLInputElement[];
}

describe('团队与额度策略页面', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listTeams).mockResolvedValue([]);
    vi.mocked(createTeam).mockResolvedValue(team);
    vi.mocked(updateTeam).mockResolvedValue(team);
  });

  it('accepts 0 as a quota value in the create form', async () => {
    renderPage();
    expect(listTeams).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /新建团队/ }));
    fireEvent.change(screen.getByLabelText('团队名称'), {
      target: { value: '测试团队' },
    });

    const inputs = quotaInputs();
    fireEvent.change(inputs[0], { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    fireEvent.click(await screen.findByRole('button', { name: '确认创建' }));
    expect(createTeam).toHaveBeenCalledOnce();
    const payload = vi.mocked(createTeam).mock.calls[0]?.[0];
    expect(payload?.name).toBe('测试团队');
    expect(payload?.fiveHourQuotaPercent).toBe(0);
    expect(payload?.weeklyQuotaPercent).toBe(100);
  });

  it('blocks an out-of-range value in the create form', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /新建团队/ }));

    const inputs = quotaInputs();
    fireEvent.change(inputs[0], { target: { value: '101' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    expect(createTeam).not.toHaveBeenCalled();
  });

  it('validates the edit form before allowing a quota above 100', async () => {
    vi.mocked(listTeams).mockResolvedValue([team]);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '编辑 研发' }));

    const inputs = quotaInputs();
    fireEvent.change(inputs[1], { target: { value: '101' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(updateTeam).not.toHaveBeenCalled();
  });
});
