import { usdScope } from '../../test/usd-fixtures';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { history } from '@umijs/max';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import UsersPage from './index';
import { generateRandomPassword } from './generatePassword';
import {
  forceLogoutUser,
  getSession,
  getUserOfficialQuota,
  getUserMeterDevices,
  getUserQuotaOverview,
  getUserUsageEvents,
  listTeams,
  listUsers,
  updateUser,
  updateUserQuotaPolicy,
  updateUserUsdLimits,
} from '../../services/api';
import type { TeamResponse, UserResponse } from '../../services/api';
import { getUserLogoutAllCurrent, previewUserLogoutAll } from '../../services/userLogoutApi';

vi.mock('@umijs/max', () => ({ history: { push: vi.fn() } }));

vi.mock('../../services/api', () => ({
  createUser: vi.fn(),
  deleteUser: vi.fn(),
  forceLogoutUser: vi.fn(),
  isSecureManagementContext: vi.fn(() => true),
  isSupportedManagementContext: vi.fn(() => true),
  getSession: vi.fn(),
  getUserOfficialQuota: vi.fn(),
  getUserMeterDevices: vi.fn(),
  getUserQuotaOverview: vi.fn(),
  getUserUsageEvents: vi.fn(),
  listTeams: vi.fn(),
  listUsers: vi.fn(),
  updateUser: vi.fn(),
  updateUserQuotaPolicy: vi.fn(),
  updateUserUsdLimits: vi.fn(),
}));
vi.mock('../../services/userLogoutApi', () => ({
  getUserLogoutAllCurrent: vi.fn(), previewUserLogoutAll: vi.fn(),
  confirmUserLogoutAll: vi.fn(), getUserLogoutAllResult: vi.fn(),
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
  fiveHourTokenLimit: 50000, weeklyTokenLimit: 100000, totalTokenLimit: 200000,
  createdAt: '2026-01-01T00:00:00Z',
  description: '研发团队',
  fiveHourQuotaPercent: 80,
  fiveHourUsdLimit: '50',
  totalUsdLimit: '200',
  officialAccountId: null,
  id: 'team-1',
  memberCount: 1,
  name: '研发',
  status: 'ACTIVE',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
  weeklyQuotaPercent: 90,
  weeklyUsdLimit: '100',
};

const user: UserResponse = {
  fiveHourTokenLimit: null, weeklyTokenLimit: null,
  createdAt: '2026-01-01T00:00:00Z',
  displayName: '张三',
  email: 'zhangsan@example.com',
  fiveHourQuotaPercent: null,
  fiveHourUsdLimit: null,
  id: 'user-1',
  role: 'USER',
  status: 'ACTIVE',
  teamId: team.id,
  updatedAt: '2026-01-01T00:00:00Z',
  weeklyQuotaPercent: null,
  weeklyUsdLimit: null,
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
    vi.mocked(updateUserUsdLimits).mockResolvedValue(usdScope());
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
    vi.mocked(getUserUsageEvents).mockResolvedValue({
      items: [{ id: 'event-1', occurredAt: '2026-10-07T00:00:00Z',
        receivedAt: '2026-10-07T00:00:02Z', totalTokens: 42,
        source: 'TOKEN_USAGE_RECORD' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
  });

  it('shows team token limits for null and submits 0 as a real personal token override', async () => {
    renderPage();
    expect(await screen.findByText('张三')).toBeInTheDocument();
    fireEvent.click(
      await screen.findByRole('button', {
        name: '配置 zhangsan@example.com 的团队与 USD 配额',
      }),
    );
    expect(screen.getByText('保存会使该用户当前会话失效')).toBeInTheDocument();
    expect(
      screen.getByRole('spinbutton', { name: '7 天个人 USD 上限' }),
    ).toHaveValue('');

    const fiveHourInput = screen.getByRole('spinbutton', {
      name: '5 小时个人 USD 上限',
    });
    fireEvent.input(fiveHourInput, { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: '保存 USD 配额' }));

    expect((await screen.findAllByText('确认保存 USD 配额？')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/现有会话失效/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '确认保存' }));

    await waitFor(() => expect(updateUserQuotaPolicy).toHaveBeenCalledOnce());
    expect(updateUserQuotaPolicy).toHaveBeenCalledWith(user.id, {
      fiveHourQuotaPercent: null,
      teamId: team.id,
      weeklyQuotaPercent: null,
    });
    await waitFor(() =>
      expect(updateUserUsdLimits).toHaveBeenCalledWith(user.id, {
        fiveHourUsdLimit: '0',
        weeklyUsdLimit: null,
      }),
    );
  });

  it('does not claim the system default when a referenced team is unavailable', async () => {
    vi.mocked(listTeams).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('团队 USD 配额不可用')).toBeInTheDocument();
    expect(screen.getByText('USD 配额暂不可用')).toBeInTheDocument();
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

  it('opens a dedicated quota page for the selected user', async () => {
    const navigate = vi.spyOn(history, 'push').mockImplementation(() => undefined);
    renderPage();
    await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '管理 zhangsan@example.com' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /查看额度/ }));

    expect(navigate).toHaveBeenCalledWith(`/quotas/users/${user.id}`);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    navigate.mockRestore();
  });

  it('splits product-only and all-bound-account logout into two distinct actions', async () => {
    vi.mocked(getSession).mockReturnValue({ accessToken: 'admin-token', user: { ...user, id: 'admin-1', role: 'ADMIN' } });
    renderPage();
    await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '管理 zhangsan@example.com' }));
    expect(await screen.findByRole('menuitem', { name: /强制下线系统账户/ })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /强制下线所有账户/ })).toBeInTheDocument();
    expect(forceLogoutUser).not.toHaveBeenCalled();
  });

  it('requires product-only confirmation and explains that official sessions are untouched', async () => {
    vi.mocked(getSession).mockReturnValue({ accessToken: 'admin-token', user: { ...user, id: 'admin-1', role: 'ADMIN' } });
    vi.mocked(forceLogoutUser).mockResolvedValue(undefined);
    renderPage();
    await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '管理 zhangsan@example.com' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /强制下线系统账户/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/不会登出官方会话/)).toBeInTheDocument();
    expect(forceLogoutUser).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认下线系统账户' }));
    await waitFor(() => expect(forceLogoutUser).toHaveBeenCalledExactlyOnceWith(user.id));
  });

  it('opens all-account preview for only the selected user without revoking the product session', async () => {
    vi.mocked(getSession).mockReturnValue({ accessToken: 'admin-token', user: { ...user, id: 'admin-1', role: 'ADMIN' } });
    vi.mocked(getUserLogoutAllCurrent).mockResolvedValue({ operation: null });
    vi.mocked(previewUserLogoutAll).mockResolvedValue({ planId: 'plan-1', userId: user.id, scope: 'ALL_ACCOUNTS',
      expiresAt: '2099-01-01T00:00:00Z', accounts: [], warnings: [] });
    renderPage(); await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '管理 zhangsan@example.com' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /强制下线所有账户/ }));
    // rc-component uses the same "test-id" for every modal title in tests;
    // the page's force-rendered hidden modals therefore obscure the accessible name.
    const title = await screen.findByText(`强制下线所有账户 · ${user.email}`);
    const dialog = title.closest<HTMLElement>('[role="dialog"]')!;
    await waitFor(() => expect(title).toBeVisible());
    expect(dialog).toBeVisible();
    expect(within(dialog).getByRole('button', { name: '确认下线所有账户' })).toBeDisabled();
    await waitFor(() => expect(previewUserLogoutAll).toHaveBeenCalledExactlyOnceWith(user.id));
    expect(forceLogoutUser).not.toHaveBeenCalled();
  });

  it('keeps a generated password available for manual copy when HTTP has no Clipboard API', async () => {
    const password = 'SyntheticPassword_HTTP_2026';
    vi.mocked(generateRandomPassword).mockReturnValue(password);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    renderPage();
    await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '管理 zhangsan@example.com' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /编辑/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '生成随机密码' }));
    const copyButton = within(dialog).getByRole('button', { name: '复制密码' });
    await waitFor(() => expect(copyButton).toBeEnabled());
    fireEvent.click(copyButton);
    expect(await screen.findByText('复制失败，请显示密码后手动复制')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('密码')).toHaveValue(password);
    expect(within(dialog).getByLabelText('密码')).toHaveAttribute('type', 'text');
    expect(updateUser).not.toHaveBeenCalled();
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
