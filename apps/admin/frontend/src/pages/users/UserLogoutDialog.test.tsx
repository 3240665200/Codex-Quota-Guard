import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import UserLogoutDialog from './UserLogoutDialog';
import { confirmUserLogoutAll, getUserLogoutAllCurrent, getUserLogoutAllResult, previewUserLogoutAll } from '../../services/userLogoutApi';
import type { UserLogoutPreview, UserLogoutResult } from '../../services/userLogoutApi';
import { isSecureManagementContext } from '../../services/api';

vi.mock('../../services/userLogoutApi', () => ({
  confirmUserLogoutAll: vi.fn(), getUserLogoutAllCurrent: vi.fn(), getUserLogoutAllResult: vi.fn(), previewUserLogoutAll: vi.fn(),
}));
vi.mock('../../services/api', () => ({ isSecureManagementContext: vi.fn() }));
const user = { id: 'user-1', email: 'synthetic@example.test' };
const preview = (): UserLogoutPreview => ({
  planId: 'plan-1', userId: user.id, scope: 'ALL_ACCOUNTS', expiresAt: '2099-01-01T00:00:00Z', warnings: [],
  accounts: [{ officialAccountId: 'account-1', displayName: 'Synthetic account', planId: 'child-1', status: 'READY',
    sessionIds: ['session-1'], targets: [{ sessionId: 'session-1', rowId: 'row-1', displayName: 'Synthetic device',
      platform: 'Windows', trustedDevice: false, current: false }], excluded: [], errorCode: null }],
});
const result = (status: UserLogoutResult['status'] = 'PARTIAL'): UserLogoutResult => ({
  planId: 'plan-1', userId: user.id, scope: 'ALL_ACCOUNTS', status, systemStatus: 'REVOKED',
  accounts: [{ officialAccountId: 'account-1', displayName: 'Synthetic account', planId: 'child-1', status,
    requiresReauthorization: false, results: [{ sessionId: 'session-1', status: 'FAILED', errorCode: 'OFFICIAL_LOGOUT_REJECTED' }], errorCode: null }],
  message: 'Synthetic backend result',
});
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
function show(onClose = vi.fn()) { return render(<AntApp><UserLogoutDialog user={user} onClose={onClose} /></AntApp>); }

describe('user-scoped all-account logout confirmation', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(isSecureManagementContext).mockReturnValue(true);
    vi.mocked(getUserLogoutAllCurrent).mockResolvedValue({ operation: null });
    vi.mocked(previewUserLogoutAll).mockResolvedValue(preview());
    vi.mocked(confirmUserLogoutAll).mockResolvedValue(result());
    vi.mocked(getUserLogoutAllResult).mockResolvedValue(result());
  });

  it('previews only, requires acknowledgement, and can close without logout', async () => {
    const close = vi.fn(); show(close);
    expect(await screen.findByText('Synthetic device')).toBeInTheDocument();
    expect(screen.getByText(/仅处理该用户当前绑定的官方会话/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认下线所有账户' })).toBeDisabled();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /关\s*闭/ }));
    expect(close).toHaveBeenCalledOnce();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });

  it('issues exactly one confirmation and distinguishes product revocation from partial official failure', async () => {
    const pending = deferred<UserLogoutResult>();
    vi.mocked(confirmUserLogoutAll).mockReturnValue(pending.promise);
    show(); await screen.findByText('Synthetic device');
    fireEvent.click(screen.getByRole('checkbox'));
    const confirm = screen.getByRole('button', { name: '确认下线所有账户' });
    fireEvent.click(confirm); fireEvent.click(confirm);
    expect(confirmUserLogoutAll).toHaveBeenCalledExactlyOnceWith(user.id, 'plan-1');
    await act(async () => pending.resolve(result()));
    expect(await screen.findByText('系统账户：已强制下线')).toBeInTheDocument();
    expect(screen.getByText('部分官方会话未完成')).toBeInTheDocument();
    expect(screen.getByText(/已接受 0.*失败 1.*结果未知 0.*未尝试 0/)).toBeInTheDocument();
    expect(screen.queryByText('全部下线成功')).not.toBeInTheDocument();
  });

  it('restores a running operation instead of creating or confirming another plan', async () => {
    vi.mocked(getUserLogoutAllCurrent).mockResolvedValue({ operation: result('EXECUTING') });
    show();
    expect(await screen.findByText('后台正在执行，请勿重复提交')).toBeInTheDocument();
    expect(previewUserLogoutAll).not.toHaveBeenCalled();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });

  it('does not create a preview when the latest operation cannot be checked', async () => {
    vi.mocked(getUserLogoutAllCurrent).mockRejectedValue(new Error('Synthetic unavailable'));
    show();
    expect(await screen.findByText(/无法核对后台操作状态/)).toBeInTheDocument();
    expect(previewUserLogoutAll).not.toHaveBeenCalled();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });

  it('on a lost confirmation response only reads that plan and never retries its write', async () => {
    vi.mocked(confirmUserLogoutAll).mockRejectedValue(new Error('Synthetic lost response'));
    vi.mocked(getUserLogoutAllResult).mockRejectedValue(new Error('Synthetic unavailable'));
    show(); await screen.findByText('Synthetic device'); fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认下线所有账户' }));
    expect(await screen.findByText(/提交结果尚未确认/)).toBeInTheDocument();
    expect(getUserLogoutAllResult).toHaveBeenCalledExactlyOnceWith(user.id, 'plan-1');
    fireEvent.click(screen.getByRole('button', { name: '刷新操作状态' }));
    await waitFor(() => expect(getUserLogoutAllResult).toHaveBeenCalledTimes(2));
    expect(confirmUserLogoutAll).toHaveBeenCalledOnce();
  });

  it('rejects an expired preview and leaves all accounts untouched', async () => {
    vi.mocked(previewUserLogoutAll).mockResolvedValue({ ...preview(), expiresAt: '2000-01-01T00:00:00Z' });
    show();
    expect(await screen.findByText(/预览已过期/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认下线所有账户' })).toBeDisabled();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });

  it('allows an explicitly confirmed product-only effect when no active official bindings exist', async () => {
    vi.mocked(previewUserLogoutAll).mockResolvedValue({ ...preview(), accounts: [] });
    show();
    expect(await screen.findByText(/没有当前有效的官方会话绑定/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认下线所有账户' }));
    await waitFor(() => expect(confirmUserLogoutAll).toHaveBeenCalledExactlyOnceWith(user.id, 'plan-1'));
  });

  it('discards a late preview after closing the dialog', async () => {
    const pending = deferred<UserLogoutPreview>();
    vi.mocked(previewUserLogoutAll).mockReturnValue(pending.promise);
    const view = show();
    await waitFor(() => expect(previewUserLogoutAll).toHaveBeenCalledOnce());
    view.unmount();
    await act(async () => pending.resolve(preview()));
    expect(screen.queryByText('Synthetic device')).not.toBeInTheDocument();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });

  it('blocks all requests on an insecure remote management page', async () => {
    vi.mocked(isSecureManagementContext).mockReturnValue(false);
    show();
    expect(await screen.findByText(/仅允许 HTTPS 或本机 HTTP 调试/)).toBeInTheDocument();
    expect(getUserLogoutAllCurrent).not.toHaveBeenCalled();
    expect(previewUserLogoutAll).not.toHaveBeenCalled();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });

  it('lists exclusions and can explicitly preview again after a terminal result', async () => {
    vi.mocked(getUserLogoutAllCurrent).mockResolvedValue({ operation: result('PARTIAL') });
    const value = preview();
    value.accounts[0].status = 'NOT_ATTEMPTED';
    value.accounts[0].excluded = [{ session: { ...value.accounts[0].targets[0], current: true }, reason: 'CURRENT_SESSION' }];
    value.accounts[0].targets = [];
    vi.mocked(previewUserLogoutAll).mockResolvedValue(value);
    show(); await screen.findByText('部分官方会话未完成');
    fireEvent.click(screen.getByRole('button', { name: '重新预览' }));
    expect(await screen.findByText(/当前官方管理会话，保留/)).toBeInTheDocument();
    expect(screen.getByRole('checkbox')).not.toBeChecked();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });

  it('explains an acknowledged unknown outcome without claiming success or blocking a new preview', async () => {
    const value = result('PARTIAL');
    value.accounts[0].status = 'RESOLVED_UNKNOWN';
    value.accounts[0].results[0] = { sessionId: 'session-1', status: 'UNKNOWN', errorCode: 'OFFICIAL_LOGOUT_OUTCOME_UNKNOWN' };
    vi.mocked(getUserLogoutAllCurrent).mockResolvedValue({ operation: value });
    show();
    expect(await screen.findByText(/结果仍未知，已人工解除操作锁/)).toBeInTheDocument();
    expect(screen.getByText('部分官方会话未完成')).toBeInTheDocument();
    expect(screen.getByText(/已接受 0.*失败 0.*结果未知 1.*未尝试 0/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新预览' })).toBeEnabled();
    expect(confirmUserLogoutAll).not.toHaveBeenCalled();
  });
});
