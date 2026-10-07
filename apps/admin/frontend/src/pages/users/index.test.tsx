import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import UsersPage from './index';
import { generateRandomPassword } from './generatePassword';
import {
  getSession,
  getUserOfficialQuota,
  getUserMeterDevices,
  getUserQuotaOverview,
  listTeams,
  listUsers,
  updateUser,
  updateUserQuotaPolicy,
  updateUserTokenLimits,
} from '../../services/api';
import type { TeamResponse, UserResponse } from '../../services/api';

vi.mock('../../services/api', () => ({
  createUser: vi.fn(),
  deleteUser: vi.fn(),
  forceLogoutUser: vi.fn(),
  getSession: vi.fn(),
  getUserOfficialQuota: vi.fn(),
  getUserMeterDevices: vi.fn(),
  getUserQuotaOverview: vi.fn(),
  listTeams: vi.fn(),
  listUsers: vi.fn(),
  updateUser: vi.fn(),
  updateUserQuotaPolicy: vi.fn(),
  updateUserTokenLimits: vi.fn(),
}));
vi.mock('./generatePassword', () => ({ generateRandomPassword: vi.fn() }));

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
afterEach(() => {
  if (originalClipboard) {
    Object.defineProperty(navigator, 'clipboard', originalClipboard);
  } else {
    Reflect.deleteProperty(navigator, 'clipboard');
  }
});

const team: TeamResponse = {
  createdAt: '2026-01-01T00:00:00Z',
  description: '研发团队',
  fiveHourQuotaPercent: 80,
  fiveHourTokenLimit: 50_000,
  id: 'team-1',
  memberCount: 1,
  name: '研发',
  status: 'ACTIVE',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
  weeklyQuotaPercent: 90,
  weeklyTokenLimit: 100_000,
};

const user: UserResponse = {
  createdAt: '2026-01-01T00:00:00Z',
  displayName: '张三',
  email: 'zhangsan@example.com',
  fiveHourQuotaPercent: null,
  fiveHourTokenLimit: null,
  id: 'user-1',
  role: 'USER',
  status: 'ACTIVE',
  teamId: team.id,
  updatedAt: '2026-01-01T00:00:00Z',
  weeklyQuotaPercent: null,
  weeklyTokenLimit: null,
};

function renderPage() {
  return render(
    <AntApp>
      <UsersPage />
    </AntApp>,
  );
}

describe('用户额度策略配置', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getSession).mockReturnValue({ accessToken: 'admin-token', user });
    vi.mocked(listUsers).mockResolvedValue({
      items: [user],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    vi.mocked(listTeams).mockResolvedValue([team]);
    vi.mocked(updateUser).mockResolvedValue(user);
    vi.mocked(updateUserQuotaPolicy).mockResolvedValue(undefined);
    vi.mocked(updateUserTokenLimits).mockResolvedValue(user);
    vi.mocked(getUserOfficialQuota).mockResolvedValue({
      planType: 'plus',
      fetchedAt: '2026-10-06T00:00:00Z',
      receivedAt: '2026-10-06T00:00:01Z',
      windows: [{ limitId: 'codex', slot: 'primary', windowDurationMins: 300,
        usedPercent: 25.5, resetsAt: '2026-10-06T05:00:00Z' }],
    });
    vi.mocked(getUserMeterDevices).mockResolvedValue([{
      deviceId: 'meter-1', status: 'ACTIVE', online: true,
      lastSeenAt: '2026-10-07T00:00:00Z', expiresAt: '2026-11-07T00:00:00Z',
    }]);
    vi.mocked(getUserQuotaOverview).mockResolvedValue({
      policy: { fiveHourTokenLimit: 1000, weeklyTokenLimit: 5000 },
      usage: { fiveHourUsedTokens: 120, weeklyUsedTokens: 300,
        fiveHourRemainingTokens: 880, weeklyRemainingTokens: 4700 },
    });
  });

  it('shows team token limits for null and submits 0 as a real personal token override', async () => {
    renderPage();
    expect(await screen.findByText('张三')).toBeInTheDocument();
    fireEvent.click(
      await screen.findByRole('button', {
        name: '配置 zhangsan@example.com 的团队与 Token 配额',
      }),
    );
    expect(screen.getByText('保存会使该用户当前会话失效')).toBeInTheDocument();
    expect(
      screen.getByRole('spinbutton', { name: '7 天个人 Token 上限' }),
    ).toHaveValue('');

    const fiveHourInput = screen.getByRole('spinbutton', {
      name: '5 小时个人 Token 上限',
    });
    fireEvent.input(fiveHourInput, { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: '保存 Token 配额' }));

    expect((await screen.findAllByText('确认保存 Token 配额？')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/现有会话失效/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '确认保存' }));

    await waitFor(() => expect(updateUserQuotaPolicy).toHaveBeenCalledOnce());
    expect(updateUserQuotaPolicy).toHaveBeenCalledWith(user.id, {
      fiveHourQuotaPercent: null,
      teamId: team.id,
      weeklyQuotaPercent: null,
    });
    await waitFor(() =>
      expect(updateUserTokenLimits).toHaveBeenCalledWith(user.id, {
        fiveHourTokenLimit: 0,
        weeklyTokenLimit: null,
      }),
    );
  });

  it('does not claim the system default when a referenced team is unavailable', async () => {
    vi.mocked(listTeams).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('团队 Token 配额不可用')).toBeInTheDocument();
    expect(screen.getByText('Token 配额暂不可用')).toBeInTheDocument();
    expect(screen.queryByText(/100%（系统默认）/)).not.toBeInTheDocument();
  });

  it('does not load or expose individual Codex account assignment controls', async () => {
    renderPage();

    expect(await screen.findByText('张三')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Codex 账户' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('combobox', { name: '分配 zhangsan@example.com 的 Codex 账户' }),
    ).not.toBeInTheDocument();
  });

  it('shows client-reported official quota separately from personal token usage', async () => {
    renderPage();
    await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '管理 zhangsan@example.com' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /查看额度/ }));

    expect(await screen.findByText(/官方 5 小时：已使用 25.5%/)).toBeInTheDocument();
    expect(screen.getByText(/Rust 常驻计费：在线/)).toBeInTheDocument();
    expect(screen.getByText(/个人 5 小时：已用 120 Token，剩余 880 Token/)).toBeInTheDocument();
    expect(getUserOfficialQuota).toHaveBeenCalledWith(user.id);
    expect(getUserMeterDevices).toHaveBeenCalledWith(user.id);
    expect(getUserQuotaOverview).toHaveBeenCalledWith(user.id);
  });

  it('generates, copies, and saves a new password only from the edit dialog', async () => {
    const password = 'TestPassword_2026_ABCDEF';
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.mocked(generateRandomPassword).mockReturnValue(password);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });

    renderPage();
    expect(await screen.findByText('张三')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '管理 zhangsan@example.com' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /编辑/ }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('编辑用户')).toBeInTheDocument();
    const passwordInput = within(dialog).getByLabelText('密码');
    expect(passwordInput).toHaveValue('');
    fireEvent.click(within(dialog).getByRole('button', { name: '生成随机密码' }));
    expect(passwordInput).toHaveValue(password);
    expect(passwordInput).toHaveAttribute('type', 'text');

    const copyButton = within(dialog).getByRole('button', { name: '复制密码' });
    await waitFor(() => expect(copyButton).toBeEnabled());
    fireEvent.click(copyButton);
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(password));

    fireEvent.click(within(dialog).getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(updateUser).toHaveBeenCalledOnce());
    expect(updateUser).toHaveBeenCalledWith(
      user.id,
      expect.objectContaining({ password }),
    );
  });
});
