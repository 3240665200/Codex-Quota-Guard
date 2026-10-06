import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import UsersPage from './index';
import { generateRandomPassword } from './generatePassword';
import {
  getSession,
  listCodexAccounts,
  listTeams,
  listUsers,
  updateUser,
  updateUserQuotaPolicy,
} from '../../services/api';
import type { TeamResponse, UserResponse } from '../../services/api';

vi.mock('../../services/api', () => ({
  assignCodexAccountToUser: vi.fn(),
  createUser: vi.fn(),
  deleteUser: vi.fn(),
  forceLogoutUser: vi.fn(),
  getSession: vi.fn(),
  listCodexAccounts: vi.fn(),
  listTeams: vi.fn(),
  listUsers: vi.fn(),
  unassignCodexAccountFromUser: vi.fn(),
  updateUser: vi.fn(),
  updateUserQuotaPolicy: vi.fn(),
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
  id: 'team-1',
  memberCount: 1,
  name: '研发',
  status: 'ACTIVE',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
  weeklyQuotaPercent: 90,
};

const user: UserResponse = {
  assignedCodexAccountId: null,
  createdAt: '2026-01-01T00:00:00Z',
  displayName: '张三',
  email: 'zhangsan@example.com',
  fiveHourQuotaPercent: null,
  id: 'user-1',
  role: 'USER',
  status: 'ACTIVE',
  teamId: team.id,
  updatedAt: '2026-01-01T00:00:00Z',
  weeklyQuotaPercent: null,
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
    vi.mocked(listCodexAccounts).mockResolvedValue({
      items: [],
      page: 0,
      size: 100,
      totalElements: 0,
      totalPages: 0,
    });
    vi.mocked(listTeams).mockResolvedValue([team]);
    vi.mocked(updateUser).mockResolvedValue(user);
    vi.mocked(updateUserQuotaPolicy).mockResolvedValue(undefined);
  });

  it('shows team defaults for null and submits 0 as a real personal override', async () => {
    renderPage();
    expect(await screen.findByText('张三')).toBeInTheDocument();
    fireEvent.click(
      await screen.findByRole('button', {
        name: '配置 zhangsan@example.com 的额度策略',
      }),
    );
    expect(screen.getByText('保存会使该用户当前会话失效')).toBeInTheDocument();
    expect(
      screen.getByRole('spinbutton', { name: '7 天个人覆盖' }),
    ).toHaveValue('');

    const fiveHourInput = screen.getByRole('spinbutton', {
      name: '5 小时个人覆盖',
    });
    fireEvent.input(fiveHourInput, { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: '保存额度策略' }));

    expect((await screen.findAllByText('确认保存额度策略？')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/现有会话失效/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '确认保存' }));

    await waitFor(() => expect(updateUserQuotaPolicy).toHaveBeenCalledOnce());
    expect(updateUserQuotaPolicy).toHaveBeenCalledWith(user.id, {
      fiveHourQuotaPercent: 0,
      teamId: team.id,
      weeklyQuotaPercent: null,
    });
  });

  it('does not claim the system default when a referenced team is unavailable', async () => {
    vi.mocked(listTeams).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('团队策略不可用')).toBeInTheDocument();
    expect(screen.getByText('额度策略暂不可用')).toBeInTheDocument();
    expect(screen.queryByText(/100%（系统默认）/)).not.toBeInTheDocument();
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
