import { act, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import ConnectionModal from './ConnectionModal';
import type { OfficialAccountResponse } from '../../services/api';

const mocks = vi.hoisted(() => ({ getOfficialAccountConnection: vi.fn(), listProxyNodes: vi.fn(),
  getOfficialAuthorizationPreset: vi.fn(), applyOfficialAuthorizationPreset: vi.fn(),
  getOfficialAccountSessions: vi.fn(), isSecureManagementContext: vi.fn(), isSupportedManagementContext: vi.fn(), saveOfficialAccountConnection: vi.fn(),
  refreshOfficialAccountConnection: vi.fn(), deleteOfficialAccountConnection: vi.fn(), listSessionBindings: vi.fn(),
  releaseSessionBinding: vi.fn(), getSessionLogoutCurrent: vi.fn(), previewSessionLogout: vi.fn(),
  confirmSessionLogout: vi.fn(), acknowledgeSessionLogoutUnknown: vi.fn() }));
vi.mock('../../services/api', () => mocks);
vi.mock('../../services/sessionBindingsApi', () => ({ listSessionBindings: mocks.listSessionBindings,
  releaseSessionBinding: mocks.releaseSessionBinding }));
vi.mock('../../services/sessionLogoutApi', () => ({ getSessionLogoutCurrent: mocks.getSessionLogoutCurrent,
  previewSessionLogout: mocks.previewSessionLogout, confirmSessionLogout: mocks.confirmSessionLogout,
  acknowledgeSessionLogoutUnknown: mocks.acknowledgeSessionLogoutUnknown }));
vi.mock('./BrowserLoginPanel', () => ({ default: () => <span>登录辅助入口</span> }));
vi.mock('./SessionBindingQueue', () => ({ default: () => null }));

const connection = { configured: true, encryptionReady: true, status: 'CONNECTED', requestedAccountId: 'workspace-1',
  proxyNodeId: null, hasAuthorization: true, hasCookie: true, version: 5 };
const snapshot = { connection, account: { email: 'selected@example.test' }, fetchedAt: '2026-10-15T00:00:00Z',
  sessions: { showSessionManager: true, devices: [
    { renderId: 'row-1', sessionId: 'session-1', displayName: '绑定设备', applications: [], currentDevice: false, trustedDevice: false },
    { renderId: 'row-2', sessionId: 'session-2', displayName: '受信任设备', applications: [], currentDevice: false, trustedDevice: true },
    { renderId: 'row-3', sessionId: 'session-3', displayName: '当前管理设备', applications: [], currentDevice: true, trustedDevice: false },
  ] } };
const binding = { id: 'binding-1', userId: 'user-1', userDisplayName: '对应成员', userEmail: 'member@example.test',
  deviceId: 'device-1', enrollmentId: 'enrollment-1', officialAccountId: 'selected-account', sessionId: 'session-1',
  status: 'ACTIVE', matchMethod: 'SERIAL_LOGIN_DIFF', verificationLevel: 'USER_CONFIRMED_INFERENCE',
  createdAt: '2026-10-15T00:00:00Z', endedAt: null };

async function show() {
  render(<AntApp><ConnectionModal account={{ id: 'selected-account', label: '选择会话账户' } as OfficialAccountResponse} onClose={vi.fn()} /></AntApp>);
  const dialog = screen.getByRole('dialog');
  await within(dialog).findByText('selected@example.test');
  return dialog;
}

async function selectFirst(dialog: HTMLElement) {
  const checkbox = within(dialog).getByRole('checkbox', { name: '选择登出 绑定设备（Session ID：session-1）' });
  await waitFor(() => expect(checkbox).toBeEnabled()); fireEvent.click(checkbox);
}

describe('会话表手动勾选登出范围', () => {
  beforeAll(() => configure({ defaultHidden: true }));
  afterAll(() => configure({ defaultHidden: false }));
  beforeEach(() => {
    vi.clearAllMocks(); mocks.isSecureManagementContext.mockReturnValue(true);
    mocks.isSupportedManagementContext.mockReturnValue(true);
    mocks.getOfficialAuthorizationPreset.mockResolvedValue({ available: false, requestedAccountId: null, revision: null, errorCode: null });
    mocks.getOfficialAccountConnection.mockResolvedValue(connection); mocks.listProxyNodes.mockResolvedValue([]);
    mocks.getOfficialAccountSessions.mockResolvedValue(snapshot); mocks.listSessionBindings.mockResolvedValue([binding]);
    mocks.getSessionLogoutCurrent.mockResolvedValue({ operation: null }); mocks.releaseSessionBinding.mockResolvedValue(undefined);
    mocks.refreshOfficialAccountConnection.mockResolvedValue({ ...snapshot, fetchedAt: '2026-10-15T00:01:00Z' });
    mocks.previewSessionLogout.mockResolvedValue({ planId: 'selected-plan', officialAccountId: 'selected-account', mode: 'SELECTED',
      expiresAt: new Date(Date.now() + 120_000).toISOString(), targets: [{ rowId: 'row-1', sessionId: 'session-1',
        displayName: '选中目标', platform: 'web', trustedDevice: false, current: false }], excluded: [], warnings: [] });
    mocks.confirmSessionLogout.mockResolvedValue({ planId: 'selected-plan', mode: 'SELECTED', status: 'ACCEPTED',
      requiresReauthorization: false, results: [{ sessionId: 'session-1', status: 'ACCEPTED', errorCode: null }], message: '' });
  });

  it('shows bound users, permits explicit bound/trusted selection, and excludes the current official session', async () => {
    const dialog = await show(); await selectFirst(dialog);
    const trusted = within(dialog).getByRole('checkbox', { name: '选择登出 受信任设备（Session ID：session-2）' });
    expect(trusted).toBeEnabled(); fireEvent.click(trusted);
    expect(within(dialog).getByRole('checkbox', { name: /选择登出 当前管理设备/ })).toBeDisabled();
    expect(within(dialog).getByText('当前官方管理会话')).toBeInTheDocument();
    expect(within(dialog).getAllByText('对应成员')).toHaveLength(2);
    fireEvent.click(within(dialog).getByRole('button', { name: '登出选中会话（2）' }));
    await within(dialog).findByText('选中目标');
    expect(mocks.previewSessionLogout).toHaveBeenCalledExactlyOnceWith('selected-account', 'SELECTED', ['session-1', 'session-2']);
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
    mocks.listSessionBindings.mockResolvedValue([{ ...binding, status: 'INVALIDATED' }]);
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /我已核对预览/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '确认登出选中会话' }));
    await waitFor(() => expect(mocks.confirmSessionLogout).toHaveBeenCalledExactlyOnceWith('selected-account', 'selected-plan', 'SELECTED'));
    expect(await within(dialog).findByText('已失效')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '登出选中会话（0）' })).toBeDisabled();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('clears the selection and its preview after an explicit snapshot refresh', async () => {
    const dialog = await show(); await selectFirst(dialog);
    fireEvent.click(within(dialog).getByRole('button', { name: '登出选中会话（1）' }));
    await within(dialog).findByText('选中目标');
    fireEvent.click(within(dialog).getByRole('button', { name: '刷新官方账户与会话' }));
    await waitFor(() => expect(mocks.refreshOfficialAccountConnection).toHaveBeenCalledOnce());
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '登出选中会话（0）' })).toBeDisabled());
    expect(within(dialog).queryByText('选中目标')).not.toBeInTheDocument();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
  });

  it('ignores a late snapshot refresh from the previously selected account', async () => {
    let finish!: (value: typeof snapshot) => void;
    mocks.refreshOfficialAccountConnection.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const view = render(<AntApp><ConnectionModal account={{ id: 'selected-account', label: '选择会话账户' } as OfficialAccountResponse} onClose={vi.fn()} /></AntApp>);
    const dialog = screen.getByRole('dialog'); await within(dialog).findByText('selected@example.test');
    await selectFirst(dialog);
    fireEvent.click(within(dialog).getByRole('button', { name: '刷新官方账户与会话' }));
    await waitFor(() => expect(mocks.refreshOfficialAccountConnection).toHaveBeenCalledWith('selected-account'));
    mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, requestedAccountId: 'workspace-2', version: 20 });
    mocks.getOfficialAccountSessions.mockResolvedValue({ ...snapshot, account: { email: 'second@example.test' },
      sessions: { showSessionManager: true, devices: [{ ...snapshot.sessions.devices[0], renderId: 'second-row',
        sessionId: 'second-session', displayName: '第二账户设备' }] } });
    mocks.listSessionBindings.mockResolvedValue([]);
    view.rerender(<AntApp><ConnectionModal account={{ id: 'second-account', label: '第二账户' } as OfficialAccountResponse} onClose={vi.fn()} /></AntApp>);
    await within(dialog).findByText('second@example.test');
    await act(async () => finish(snapshot));
    expect(within(dialog).getByText('second@example.test')).toBeInTheDocument();
    expect(within(dialog).getByText('第二账户设备')).toBeInTheDocument();
    expect(within(dialog).queryByText('selected@example.test')).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('checkbox', { name: /Session ID：session-1/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '登出选中会话（0）' })).toBeDisabled();
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '刷新官方账户与会话' })).toBeEnabled());
  });

});
