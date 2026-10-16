import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { useState } from 'react';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import ConnectionModal from './ConnectionModal';
import type { OfficialAccountResponse } from '../../services/api';

const mocks = vi.hoisted(() => ({
  getOfficialAuthorizationPreset: vi.fn(), applyOfficialAuthorizationPreset: vi.fn(),
  createOfficialAccount: vi.fn(), deleteOfficialAccount: vi.fn(), listOfficialAccounts: vi.fn(),
  updateOfficialAccount: vi.fn(), getOfficialAccountConnection: vi.fn(), saveOfficialAccountConnection: vi.fn(),
  deleteOfficialAccountConnection: vi.fn(), refreshOfficialAccountConnection: vi.fn(),
  getOfficialAccountSessions: vi.fn(), listProxyNodes: vi.fn(), isSecureManagementContext: vi.fn(), isSupportedManagementContext: vi.fn(),
  getSessionLogoutCurrent: vi.fn(),
}));
vi.mock('../../services/api', () => mocks);
vi.mock('../../services/sessionBindingsApi', () => ({ listSessionBindings: vi.fn().mockResolvedValue([]) }));
vi.mock('../../services/sessionLogoutApi', () => ({ getSessionLogoutCurrent: mocks.getSessionLogoutCurrent,
  previewSessionLogout: vi.fn(), confirmSessionLogout: vi.fn(), acknowledgeSessionLogoutUnknown: vi.fn() }));

const connection = { configured: true, encryptionReady: true, status: 'UNVERIFIED',
  requestedAccountId: 'official-workspace', proxyNodeId: 'proxy-1', hasAuthorization: true, hasCookie: true,
  importedAt: '2026-10-14T00:00:00Z', lastAttemptAt: null, lastSuccessAt: null, lastErrorCode: null, version: 2 };
const snapshot = { connection: { ...connection, status: 'CONNECTED' },
  account: { userId: 'official-user', email: 'official@example.com', userName: '官方用户',
    accountId: 'official-workspace', accountName: '官方工作区', planType: 'team',
    accountUserRole: 'owner', deactivated: false, activeSubscription: true },
  sessions: { showSessionManager: true, devices: [
    { renderId: 'render-1', sessionId: 'session-fixture', platform: 'web', displayName: '办公电脑',
      deviceModel: 'Fixture PC', humanReadableDescription: 'Windows 浏览器', osVersion: 'Windows 11',
      currentDevice: true, trustedDevice: true, lastSignedInAt: '2026-10-13T00:00:00Z',
      sessionCreatedAt: '2026-10-12T00:00:00Z', city: 'Shanghai', regionCode: 'SH', country: 'CN',
      applications: ['ChatGPT', 'Codex'] },
  ] }, fetchedAt: '2026-10-14T00:01:00Z' };

function WorkspaceFixture() {
  const [open, setOpen] = useState(false);
  return <><button onClick={() => setOpen(true)}>授权与会话 研发账户</button>{open ? <ConnectionModal
    account={{ id: 'account-1', label: '研发账户' } as OfficialAccountResponse} onClose={() => setOpen(false)} /> : null}</>;
}
async function openConnection() {
  render(<AntApp><WorkspaceFixture /></AntApp>);
  fireEvent.click(await screen.findByRole('button', { name: '授权与会话 研发账户' }));
  const dialog = await screen.findByRole('dialog', { name: /授权与会话/ });
  await waitFor(() => expect(mocks.getOfficialAccountConnection).toHaveBeenCalledWith('account-1'));
  fireEvent.click(within(dialog).getByRole('button', { name: '高级：手动导入授权' }));
  await within(dialog).findByLabelText('官方 Authorization');
  return dialog;
}

describe('官方账户授权与只读会话', () => {
  beforeAll(() => configure({ defaultHidden: true }));
  afterAll(() => configure({ defaultHidden: false }));
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.isSecureManagementContext.mockReturnValue(true);
    mocks.isSupportedManagementContext.mockReturnValue(true);
    mocks.getOfficialAuthorizationPreset.mockResolvedValue({ available: false, requestedAccountId: null, revision: null, errorCode: null });
    mocks.getSessionLogoutCurrent.mockResolvedValue({ operation: null });
    mocks.listOfficialAccounts.mockResolvedValue([{ id: 'account-1', label: '研发账户',
      estimatedTotalTokens: 1000000, reporterEmail: null, quota: null,
      createdAt: '2026-10-14T00:00:00Z', updatedAt: '2026-10-14T00:00:00Z', version: 0 }]);
    mocks.getOfficialAccountConnection.mockResolvedValue(connection);
    mocks.getOfficialAccountSessions.mockResolvedValue(snapshot);
    mocks.saveOfficialAccountConnection.mockResolvedValue({ ...connection, version: 3 });
    mocks.refreshOfficialAccountConnection.mockResolvedValue(snapshot);
    mocks.deleteOfficialAccountConnection.mockResolvedValue(undefined);
    mocks.listProxyNodes.mockResolvedValue([{ id: 'proxy-1', label: '香港出口', protocol: 'SOCKS5',
      host: 'proxy.example', port: 1080, enabled: true, hasCredentials: true, version: 0 },
    { id: 'proxy-disabled', label: '停用出口', protocol: 'HTTP', host: 'disabled.example', port: 8080,
      enabled: false, hasCredentials: false, version: 0 }]);
  });

  it('shows safe snapshots and explicit logout preview controls without an automatic official refresh', async () => {
    const dialog = await openConnection();
    await waitFor(() => expect(dialog).toHaveTextContent('official@example.com'));
    expect(within(dialog).getByText('办公电脑')).toBeInTheDocument();
    expect(within(dialog).getByText('Windows 浏览器')).toBeInTheDocument();
    expect(within(dialog).getByText('ChatGPT、Codex')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('官方 Authorization')).toHaveValue('');
    expect(within(dialog).getByLabelText('官方 Cookie（可选）')).toHaveValue('');
    expect(within(dialog).queryByRole('button', { name: /下线|退出会话|撤销设备/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '登出所有会话' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '登出所有未绑定会话' })).toBeInTheDocument();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('embeds the session workspace as a page with all three safe logout controls and no authorization form', async () => {
    render(<AntApp><ConnectionModal account={{ id: 'account-1', label: '研发账户' } as OfficialAccountResponse}
      onClose={vi.fn()} view="sessions" inline /></AntApp>);
    await screen.findByText('official@example.com');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('官方 Authorization')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '开始官方网页登录' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '登出所有会话' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '登出所有未绑定会话' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^登出选中会话/ })).toBeInTheDocument();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('requires fresh authorization for a full replacement and clears submitted secrets on failure', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    let submitted: Record<string, unknown> | undefined;
    mocks.saveOfficialAccountConnection.mockImplementation(async (_id, body) => {
      submitted = { ...body };
      throw new Error('fixture-sensitive-upstream-error');
    });
    const dialog = await openConnection();
    await within(dialog).findByText('official@example.com');
    fireEvent.click(within(dialog).getByRole('button', { name: '保存授权' }));
    expect(await within(dialog).findByText('请输入官方网页登录后的 Authorization')).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('官方 Authorization'), { target: { value: 'Bearer fixture-secret' } });
    fireEvent.change(within(dialog).getByLabelText('官方 Cookie（可选）'), { target: { value: '__Secure-next-auth.session-token=fixture-only' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '保存授权' }));
    await waitFor(() => expect(mocks.saveOfficialAccountConnection).toHaveBeenCalledOnce());
    expect(submitted).toEqual({ authorization: 'Bearer fixture-secret', cookie: '__Secure-next-auth.session-token=fixture-only',
      requestedAccountId: 'official-workspace', proxyNodeId: 'proxy-1', version: 2 });
    await waitFor(() => expect(within(dialog).getByLabelText('官方 Authorization')).toHaveValue(''));
    expect(within(dialog).getByLabelText('官方 Cookie（可选）')).toHaveValue('');
    expect(screen.queryByText('fixture-sensitive-upstream-error')).not.toBeInTheDocument();
    expect(storage).not.toHaveBeenCalled();
  });

  it('uses only saved credentials for an explicit refresh', async () => {
    const dialog = await openConnection();
    await within(dialog).findByText('official@example.com');
    fireEvent.change(within(dialog).getByLabelText('官方 Authorization'), { target: { value: 'unsaved-fixture' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '刷新官方账户与会话' }));
    await waitFor(() => expect(mocks.refreshOfficialAccountConnection).toHaveBeenCalledWith('account-1'));
    expect(mocks.refreshOfficialAccountConnection.mock.calls[0]).toHaveLength(1);
    expect(mocks.saveOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('clears the old identity snapshot after replacing authorization and requires a new refresh', async () => {
    const dialog = await openConnection();
    await within(dialog).findByText('official@example.com');
    fireEvent.change(within(dialog).getByLabelText('官方 Authorization'), { target: { value: 'Bearer replacement-fixture' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '保存授权' }));
    await waitFor(() => expect(mocks.saveOfficialAccountConnection).toHaveBeenCalledOnce());
    await waitFor(() => expect(within(dialog).queryByText('official@example.com')).not.toBeInTheDocument());
    expect(within(dialog).getByLabelText('官方 Authorization')).toHaveValue('');
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('clears official identity and sessions when a refresh invalidates the saved snapshot', async () => {
    mocks.refreshOfficialAccountConnection.mockRejectedValue({ code: 'OFFICIAL_ACCESS_BLOCKED', status: 502 });
    const dialog = await openConnection();
    await within(dialog).findByText('official@example.com');
    fireEvent.click(within(dialog).getByRole('button', { name: '刷新官方账户与会话' }));
    await waitFor(() => expect(mocks.refreshOfficialAccountConnection).toHaveBeenCalledOnce());
    await waitFor(() => expect(within(dialog).queryByText('official@example.com')).not.toBeInTheDocument());
    expect(within(dialog).queryByText('办公电脑')).not.toBeInTheDocument();
    expect(within(dialog).getByText(/官方站点拒绝了本次访问/)).toBeInTheDocument();
  });

  it.each(['transport', 'encryption'])('blocks authorization writes when %s is unavailable', async (reason) => {
    if (reason === 'transport') {
      mocks.isSecureManagementContext.mockReturnValue(false);
      mocks.isSupportedManagementContext.mockReturnValue(false);
    }
    else mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, encryptionReady: false });
    const dialog = await openConnection();
    expect(await within(dialog).findByText(reason === 'transport' ? /HTTP 或 HTTPS/ : /服务端.*加密.*密钥/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '保存授权' })).toBeDisabled();
  });

  it('allows saving authorization over remote HTTP while warning about plaintext transport', async () => {
    mocks.isSecureManagementContext.mockReturnValue(false);
    const dialog = await openConnection();
    expect(within(dialog).getByText(/HTTP.*明文/)).toBeInTheDocument();
    const save = within(dialog).getByRole('button', { name: '保存授权' });
    expect(save).toBeEnabled();
    fireEvent.change(within(dialog).getByLabelText('官方 Authorization'), { target: { value: 'Bearer synthetic-http-token' } });
    fireEvent.click(save);
    await waitFor(() => expect(mocks.saveOfficialAccountConnection).toHaveBeenCalledOnce());
    expect(within(dialog).getByLabelText('官方 Authorization')).toHaveValue('');
  });

  it('clears secrets on close and requires confirmation to remove the saved authorization', async () => {
    let dialog = await openConnection();
    await within(dialog).findByText('official@example.com');
    fireEvent.change(within(dialog).getByLabelText('官方 Authorization'), { target: { value: 'temporary-fixture' } });
    fireEvent.click(within(dialog).getAllByRole('button', { name: /^关\s*闭$/ })[0]);
    await waitFor(() => expect(screen.queryByDisplayValue('temporary-fixture')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '授权与会话 研发账户' }));
    dialog = await screen.findByRole('dialog', { name: /授权与会话/ });
    await within(dialog).findByText('official@example.com');
    fireEvent.click(within(dialog).getByRole('button', { name: '高级：手动导入授权' }));
    expect(within(dialog).getByLabelText('官方 Authorization')).toHaveValue('');
    fireEvent.click(within(dialog).getByRole('button', { name: '删除保存的授权' }));
    expect(mocks.deleteOfficialAccountConnection).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(mocks.deleteOfficialAccountConnection).toHaveBeenCalledWith('account-1'));
  });

  it('explains an absent saved snapshot without initiating a network refresh', async () => {
    mocks.getOfficialAccountSessions.mockRejectedValue({ status: 409, code: 'OFFICIAL_SNAPSHOT_UNAVAILABLE' });
    const dialog = await openConnection();
    expect(await within(dialog).findByText(/尚无官方会话快照/)).toBeInTheDocument();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });
});
