import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ConnectionModal from './ConnectionModal';
import type { OfficialAccountResponse } from '../../services/api';

const mocks = vi.hoisted(() => ({
  getOfficialAccountConnection: vi.fn(), listProxyNodes: vi.fn(), getOfficialAccountSessions: vi.fn(),
  isSecureManagementContext: vi.fn(), saveOfficialAccountConnection: vi.fn(),
  refreshOfficialAccountConnection: vi.fn(), deleteOfficialAccountConnection: vi.fn(),
  listSessionBindings: vi.fn(), startOfficialBrowserLogin: vi.fn(),
  getOfficialBrowserLogin: vi.fn(), cancelOfficialBrowserLogin: vi.fn(),
  getSessionLogoutCurrent: vi.fn(), previewSessionLogout: vi.fn(), confirmSessionLogout: vi.fn(),
  acknowledgeSessionLogoutUnknown: vi.fn(), getSessionBindingQueue: vi.fn(), recoverSessionBindingQueue: vi.fn(),
}));
vi.mock('../../services/api', () => mocks);
vi.mock('../../services/sessionBindingsApi', () => ({ listSessionBindings: mocks.listSessionBindings }));
vi.mock('../../services/officialBrowserLoginApi', () => ({
  startOfficialBrowserLogin: mocks.startOfficialBrowserLogin, getOfficialBrowserLogin: mocks.getOfficialBrowserLogin,
  cancelOfficialBrowserLogin: mocks.cancelOfficialBrowserLogin,
}));
vi.mock('../../services/sessionLogoutApi', () => ({ getSessionLogoutCurrent: mocks.getSessionLogoutCurrent,
  previewSessionLogout: mocks.previewSessionLogout, confirmSessionLogout: mocks.confirmSessionLogout,
  acknowledgeSessionLogoutUnknown: mocks.acknowledgeSessionLogoutUnknown }));
vi.mock('../../services/sessionBindingQueueApi', () => ({ getSessionBindingQueue: mocks.getSessionBindingQueue,
  recoverSessionBindingQueue: mocks.recoverSessionBindingQueue }));

const connection = { configured: true, encryptionReady: true, status: 'CONNECTED', requestedAccountId: 'workspace-1',
  proxyNodeId: null, hasAuthorization: true, hasCookie: true, importedAt: null, lastAttemptAt: null,
  lastSuccessAt: null, lastErrorCode: null, version: 5 };
const snapshot = { connection, account: { email: 'official@example.test' },
  sessions: { showSessionManager: true, devices: [{ renderId: 'row-1', sessionId: 'session-1',
    displayName: '官方设备', applications: [] }] }, fetchedAt: '2026-10-15T00:00:00Z' };
const binding = { id: 'binding-1', userId: 'user-1', userDisplayName: '已绑定成员', userEmail: 'member@example.test',
  deviceId: 'device-1', enrollmentId: 'enrollment-1', officialAccountId: 'account-1', sessionId: 'session-1',
  status: 'ACTIVE', matchMethod: 'SERIAL_LOGIN_DIFF', verificationLevel: 'USER_CONFIRMED_INFERENCE',
  createdAt: '2026-10-15T00:00:00Z', endedAt: null };

async function show() {
  render(<AntApp><ConnectionModal account={{ id: 'account-1', label: '研发账户' } as OfficialAccountResponse} onClose={vi.fn()} /></AntApp>);
  const dialog = screen.getByRole('dialog');
  await within(dialog).findByText('official@example.test');
  return dialog;
}

describe('官方网页登录与设备绑定入口', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mocks.isSecureManagementContext.mockReturnValue(true);
    mocks.getOfficialAccountConnection.mockResolvedValue(connection);
    mocks.listProxyNodes.mockResolvedValue([]); mocks.getOfficialAccountSessions.mockResolvedValue(snapshot);
    mocks.listSessionBindings.mockResolvedValue([binding]);
    mocks.getSessionLogoutCurrent.mockResolvedValue({ operation: null });
    mocks.getSessionBindingQueue.mockResolvedValue({ officialAccountId: 'account-1', quarantined: false, reason: null, activeAttemptId: null });
    mocks.previewSessionLogout.mockResolvedValue({ planId: 'plan-1', officialAccountId: 'account-1', mode: 'UNBOUND',
      expiresAt: new Date(Date.now() + 120_000).toISOString(), targets: [{ rowId: 'row-2', sessionId: 'unbound-1',
        displayName: '预览未绑定设备', platform: 'web', trustedDevice: false, current: false }], excluded: [], warnings: [] });
    mocks.confirmSessionLogout.mockResolvedValue({ planId: 'plan-1', mode: 'UNBOUND', status: 'ACCEPTED',
      requiresReauthorization: false, results: [{ sessionId: 'unbound-1', status: 'ACCEPTED', errorCode: null }], message: '' });
    mocks.startOfficialBrowserLogin.mockResolvedValue({ id: 'job-1', officialAccountId: 'account-1', accountName: '研发账户',
      state: 'WAITING_FOR_HELPER', expiresAt: '2026-10-15T01:00:00Z', errorCode: null, proxyNodeId: null });
  });

  it('makes official login the primary path and keeps manual credentials in a closed advanced section', async () => {
    const dialog = await show();
    expect(within(dialog).getByRole('button', { name: '开始官方网页登录' })).toBeEnabled();
    expect(within(dialog).queryByLabelText('官方 Authorization')).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '高级：手动导入授权' }));
    expect(await within(dialog).findByLabelText('官方 Authorization')).toHaveValue('');
    expect(within(dialog).getByRole('button', { name: '保存授权' })).toBeEnabled();
  });

  it('loads only this account binding results and shows the matching current session', async () => {
    const dialog = await show();
    expect(mocks.listSessionBindings).toHaveBeenCalledWith('account-1');
    expect(await within(dialog).findByText('设备会话绑定记录')).toBeInTheDocument();
    expect(within(dialog).getAllByText('已绑定成员')).toHaveLength(2);
    expect(within(dialog).getByText('用户确认的推断')).toBeInTheDocument();
  });

  it('reloads safe connection, snapshot, and bindings after helper completion without another official refresh', async () => {
    mocks.startOfficialBrowserLogin.mockImplementation(async () => {
      mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, version: 6 });
      mocks.getOfficialAccountSessions.mockResolvedValue({ ...snapshot, account: { email: 'completed@example.test' } });
      return { id: 'job-1', officialAccountId: 'account-1', accountName: '研发账户', state: 'COMPLETED',
        expiresAt: '2026-10-15T01:00:00Z', errorCode: null, proxyNodeId: null };
    });
    const dialog = await show();
    fireEvent.click(within(dialog).getByRole('button', { name: '开始官方网页登录' }));
    expect(await within(dialog).findByText('completed@example.test')).toBeInTheDocument();
    expect(mocks.startOfficialBrowserLogin).toHaveBeenCalledWith('account-1', { version: 5, proxyNodeId: null });
    expect(mocks.getOfficialAccountConnection).toHaveBeenCalledTimes(2);
    expect(mocks.listSessionBindings).toHaveBeenCalledTimes(2);
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('does not describe unavailable bindings as unbound', async () => {
    mocks.listSessionBindings.mockRejectedValue(new Error('fixture-private-upstream'));
    const dialog = await show();
    expect(await within(dialog).findByText('绑定结果暂时无法读取，请关闭窗口后重试。')).toBeInTheDocument();
    expect(within(dialog).queryByText('暂无当前绑定')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('fixture-private-upstream')).not.toBeInTheDocument();
  });

  it('clears an old snapshot when helper authorization was saved but official verification failed', async () => {
    mocks.startOfficialBrowserLogin.mockImplementation(async () => {
      mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, version: 6, status: 'ACCESS_BLOCKED',
        lastErrorCode: 'OFFICIAL_CHALLENGE_REQUIRED' });
      mocks.getOfficialAccountSessions.mockRejectedValue({ code: 'OFFICIAL_SNAPSHOT_UNAVAILABLE' });
      return { id: 'job-1', officialAccountId: 'account-1', accountName: '研发账户', state: 'FAILED',
        expiresAt: '2026-10-15T01:00:00Z', errorCode: 'OFFICIAL_CHALLENGE_REQUIRED', proxyNodeId: null };
    });
    const dialog = await show();
    fireEvent.click(within(dialog).getByRole('button', { name: '开始官方网页登录' }));
    await waitFor(() => expect(mocks.getOfficialAccountConnection).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(within(dialog).queryByText('official@example.test')).not.toBeInTheDocument());
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('reloads safe connection, clears unavailable old snapshots, and refreshes bindings and queue after logout', async () => {
    mocks.confirmSessionLogout.mockImplementation(async () => {
      mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, version: 6, status: 'AUTH_REQUIRED' });
      mocks.getOfficialAccountSessions.mockRejectedValue({ code: 'OFFICIAL_SNAPSHOT_UNAVAILABLE' });
      mocks.listSessionBindings.mockResolvedValue([{ ...binding, status: 'INVALIDATED' }]);
      return { planId: 'plan-1', mode: 'UNBOUND', status: 'ACCEPTED', requiresReauthorization: true,
        results: [{ sessionId: 'unbound-1', status: 'ACCEPTED', errorCode: null }], message: '' };
    });
    const dialog = await show();
    const previewButton = within(dialog).getByRole('button', { name: '登出所有未绑定会话' });
    await waitFor(() => expect(previewButton).toBeEnabled()); fireEvent.click(previewButton);
    await within(dialog).findByText('预览未绑定设备');
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /我已核对预览/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '确认登出所有未绑定会话' }));
    await waitFor(() => expect(mocks.getOfficialAccountConnection).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(mocks.getSessionBindingQueue).toHaveBeenCalledTimes(2));
    expect(mocks.listSessionBindings).toHaveBeenCalledTimes(2);
    expect(within(dialog).queryByText('official@example.test')).not.toBeInTheDocument();
    expect(within(dialog).getByText('需要重新授权')).toBeInTheDocument();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('keeps other connection mutations disabled while a server logout is unresolved', async () => {
    mocks.getSessionLogoutCurrent.mockResolvedValue({ operation: { planId: 'pending-plan', mode: 'ALL', status: 'EXECUTING',
      requiresReauthorization: true, results: [], message: '', canAcknowledge: false, canAcknowledgeAt: null } });
    render(<AntApp><ConnectionModal account={{ id: 'account-1', label: '研发账户' } as OfficialAccountResponse} onClose={vi.fn()} /></AntApp>);
    const dialog = screen.getByRole('dialog');
    await within(dialog).findByText('后台仍保留此操作锁');
    expect(within(dialog).getByRole('button', { name: '刷新官方账户与会话' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: '删除保存的授权' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: '开始官方网页登录' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: /^关\s*闭$/ })).toBeEnabled();
  });

  it('does not write an old logout completion into another selected account', async () => {
    let finish!: (value: unknown) => void;
    mocks.confirmSessionLogout.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const view = render(<AntApp><ConnectionModal account={{ id: 'account-1', label: '研发账户' } as OfficialAccountResponse} onClose={vi.fn()} /></AntApp>);
    const dialog = screen.getByRole('dialog');
    await within(dialog).findByText('official@example.test');
    const previewButton = within(dialog).getByRole('button', { name: '登出所有未绑定会话' });
    await waitFor(() => expect(previewButton).toBeEnabled()); fireEvent.click(previewButton);
    await within(dialog).findByText('预览未绑定设备');
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /我已核对预览/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '确认登出所有未绑定会话' }));
    mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, requestedAccountId: 'workspace-2' });
    mocks.getOfficialAccountSessions.mockResolvedValue({ ...snapshot, account: { email: 'second@example.test' } });
    mocks.listSessionBindings.mockResolvedValue([]);
    view.rerender(<AntApp><ConnectionModal account={{ id: 'account-2', label: '第二账户' } as OfficialAccountResponse} onClose={vi.fn()} /></AntApp>);
    await within(dialog).findByText('second@example.test');
    const reads = mocks.getOfficialAccountConnection.mock.calls.length;
    await act(async () => finish({ planId: 'plan-1', mode: 'UNBOUND', status: 'ACCEPTED', requiresReauthorization: false, results: [], message: '' }));
    expect(mocks.getOfficialAccountConnection).toHaveBeenCalledTimes(reads);
    expect(within(dialog).getByText('second@example.test')).toBeInTheDocument();
    expect(within(dialog).queryByText('预览未绑定设备')).not.toBeInTheDocument();
  });
});
