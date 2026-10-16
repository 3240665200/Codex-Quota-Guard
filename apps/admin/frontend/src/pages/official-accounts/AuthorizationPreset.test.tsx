import { act, configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import ConnectionModal from './ConnectionModal';
import type { OfficialAccountResponse } from '../../services/api';

const mocks = vi.hoisted(() => ({ getOfficialAccountConnection: vi.fn(), getOfficialAccountSessions: vi.fn(),
  listProxyNodes: vi.fn(), getOfficialAuthorizationPreset: vi.fn(), applyOfficialAuthorizationPreset: vi.fn(),
  saveOfficialAccountConnection: vi.fn(), refreshOfficialAccountConnection: vi.fn(), deleteOfficialAccountConnection: vi.fn(),
  isSupportedManagementContext: vi.fn(), startOfficialWebLogin: vi.fn(), confirmSessionLogout: vi.fn() }));
vi.mock('../../services/api', () => mocks);
vi.mock('../../services/sessionBindingsApi', () => ({ listSessionBindings: vi.fn().mockResolvedValue([]), releaseSessionBinding: vi.fn() }));
vi.mock('../../services/officialWebLoginApi', () => ({ startOfficialWebLogin: mocks.startOfficialWebLogin, getOfficialWebLogin: vi.fn(),
  cancelOfficialWebLogin: vi.fn(), getOfficialWebLoginFrame: vi.fn(), sendOfficialWebLoginInput: vi.fn(),
  completeOfficialWebLogin: vi.fn(), WEB_LOGIN_KEYS: [] }));
vi.mock('../../services/sessionLogoutApi', () => ({ getSessionLogoutCurrent: vi.fn(), previewSessionLogout: vi.fn(),
  confirmSessionLogout: mocks.confirmSessionLogout, acknowledgeSessionLogoutUnknown: vi.fn() }));

const connection = { configured: true, encryptionReady: true, status: 'UNVERIFIED', requestedAccountId: 'workspace-before',
  proxyNodeId: 'proxy-1', hasAuthorization: true, hasCookie: false, version: 5 };
const preset = { available: true, requestedAccountId: 'workspace-preset', revision: 'revision-1', errorCode: null };
const account = (id = 'account-1') => ({ id, label: id } as OfficialAccountResponse);
const ui = (id = 'account-1') => <AntApp><ConnectionModal account={account(id)} onClose={vi.fn()} view="authorization" inline /></AntApp>;
async function show() {
  const view = render(ui());
  await waitFor(() => expect(screen.getByRole('button', { name: '使用预置授权' })).toBeEnabled());
  return view;
}
const jsonDraft = { requestedAccountId: 'workspace-file', authorization: 'Bearer synthetic-file', cookie: '' };
const txtDraft = '[Official Account ID]\nworkspace-file\n\n[Official Authorization - include Bearer]\nBearer synthetic-file\n\n[Official Cookie - optional]\n\n';
function chooseFile(contents: string, name: string) {
  fireEvent.change(screen.getByLabelText('本机参数文件'), { target: { files: [new File([contents], name)] } });
}

describe('统一预置授权与本机浏览器入口', () => {
  beforeAll(() => configure({ defaultHidden: true }));
  afterAll(() => configure({ defaultHidden: false }));
  afterEach(() => vi.unstubAllGlobals());
  beforeEach(() => {
    vi.clearAllMocks(); mocks.isSupportedManagementContext.mockReturnValue(true);
    mocks.getOfficialAccountConnection.mockResolvedValue(connection);
    mocks.getOfficialAccountSessions.mockRejectedValue({ code: 'OFFICIAL_SNAPSHOT_UNAVAILABLE' });
    mocks.listProxyNodes.mockResolvedValue([{ id: 'proxy-1', label: '代理一', protocol: 'HTTP', enabled: true }]);
    mocks.getOfficialAuthorizationPreset.mockResolvedValue(preset);
    mocks.applyOfficialAuthorizationPreset.mockResolvedValue({ ...connection, requestedAccountId: 'workspace-preset', version: 6 });
    mocks.saveOfficialAccountConnection.mockResolvedValue({ ...connection, version: 7 });
  });

  it('applies the preset with only account, version, selected proxy and revision and permits a later explicit refresh', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    await show();
    expect(screen.queryByLabelText('官方 Authorization')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '从本机参数文件填入' })).toBeEnabled();
    expect(mocks.applyOfficialAuthorizationPreset).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '使用预置授权' }));
    await screen.findByText('预置授权已加密保存，请手动刷新官方账户与会话');
    expect(mocks.applyOfficialAuthorizationPreset).toHaveBeenCalledExactlyOnceWith('account-1', {
      version: 5, proxyNodeId: 'proxy-1', presetRevision: 'revision-1',
    });
    expect(mocks.saveOfficialAccountConnection).not.toHaveBeenCalled();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
    expect(mocks.startOfficialWebLogin).not.toHaveBeenCalled();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
    expect(storage).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '刷新官方账户与会话' })).toBeEnabled();
  });

  it('opens the fixed official URL in the local browser without server login, automatic reads or logout', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    await show();
    fireEvent.click(screen.getByRole('button', { name: '在本机浏览器打开官方登录' }));
    expect(open).toHaveBeenCalledExactlyOnceWith('https://chatgpt.com/', '_blank', 'noopener,noreferrer');
    expect(screen.getByText(/不会自动读取.*Cookie/)).toBeInTheDocument();
    expect(mocks.startOfficialWebLogin).not.toHaveBeenCalled();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
    expect(mocks.getOfficialAccountConnection).toHaveBeenCalledOnce();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
  });

  it.each(['OFFICIAL_PRESET_REVISION_CONFLICT', 'OFFICIAL_CONNECTION_VERSION_CONFLICT'])('shows a safe %s without retrying or replacing credentials', async (code) => {
    mocks.applyOfficialAuthorizationPreset.mockRejectedValue({ status: 409, code, message: 'private-credential' });
    await show(); fireEvent.click(screen.getByRole('button', { name: '使用预置授权' }));
    await screen.findByText(code === 'OFFICIAL_PRESET_REVISION_CONFLICT'
      ? '预置授权已更新，请关闭窗口后重新打开，再确认使用。' : '配置已被其他管理员更新，请关闭窗口后重新打开。');
    expect(mocks.applyOfficialAuthorizationPreset).toHaveBeenCalledOnce();
    expect(screen.queryByText('private-credential')).not.toBeInTheDocument();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it.each(['http://localhost/', 'http://admin.example.test/', 'https://admin.example.test/'])('allows txt and JSON file fallback on %s with explicit save', async (url) => {
    const originalWindow = window;
    const location = new URL(url);
    vi.stubGlobal('window', new Proxy(originalWindow, { get: (target, property) => property === 'location' ? location : Reflect.get(target, property) }));
    mocks.getOfficialAuthorizationPreset.mockResolvedValue({ available: false, requestedAccountId: null, revision: null, errorCode: null });
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    render(ui());
    await waitFor(() => expect(screen.getByRole('button', { name: '从本机参数文件填入' })).toBeEnabled());
    expect(screen.getByRole('button', { name: '使用预置授权' })).toBeDisabled();
    chooseFile(txtDraft, 'authorization.txt');
    await waitFor(() => expect(screen.getByLabelText('官方 Authorization')).toHaveValue(jsonDraft.authorization));
    chooseFile(JSON.stringify(jsonDraft), 'authorization.json');
    await waitFor(() => expect(screen.getByRole('button', { name: '保存授权' })).toBeEnabled());
    expect(mocks.saveOfficialAccountConnection).not.toHaveBeenCalled();
    expect(mocks.applyOfficialAuthorizationPreset).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '保存授权' }));
    await waitFor(() => expect(mocks.saveOfficialAccountConnection).toHaveBeenCalledOnce());
    expect(mocks.saveOfficialAccountConnection.mock.calls[0][0]).toBe('account-1');
    expect(screen.getByLabelText('官方 Authorization')).toHaveValue('');
    expect(storage).not.toHaveBeenCalled();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('blocks apply/import/open for unsupported page protocols and blocks oversized files', async () => {
    mocks.isSupportedManagementContext.mockReturnValue(false); render(ui());
    await screen.findByText(/当前页面协议不受支持/);
    expect(screen.getByRole('button', { name: '使用预置授权' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '从本机参数文件填入' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '在本机浏览器打开官方登录' })).toBeDisabled();
    chooseFile(JSON.stringify(jsonDraft), 'authorization.json');
    expect(screen.queryByLabelText('官方 Authorization')).not.toBeInTheDocument();
    expect(mocks.saveOfficialAccountConnection).not.toHaveBeenCalled();
  });

  it('locks pending preset operations and ignores completion after switching accounts', async () => {
    let finish!: (value: unknown) => void;
    mocks.applyOfficialAuthorizationPreset.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const view = await show();
    fireEvent.click(screen.getByRole('button', { name: '使用预置授权' }));
    expect(screen.getByRole('button', { name: '使用预置授权' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '从本机参数文件填入' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '刷新官方账户与会话' })).toBeDisabled();
    mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, requestedAccountId: 'workspace-second', version: 12 });
    view.rerender(ui('account-2'));
    await waitFor(() => expect(screen.getByRole('button', { name: '使用预置授权' })).toBeEnabled());
    await act(async () => finish({ ...connection, requestedAccountId: 'workspace-stale', version: 6 }));
    fireEvent.click(screen.getByRole('button', { name: '高级：手动导入授权' }));
    expect(screen.getByLabelText('官方 Account ID')).toHaveValue('workspace-second');
    expect(screen.queryByText('预置授权已加密保存，请手动刷新官方账户与会话')).not.toBeInTheDocument();
  });

  it('cancels file loading on account change and ignores its stale content', async () => {
    let reader!: FileReader;
    vi.spyOn(FileReader.prototype, 'readAsText').mockImplementation(function (this: FileReader) {
      reader = this; Object.defineProperty(this, 'readyState', { value: FileReader.LOADING });
    });
    const abort = vi.spyOn(FileReader.prototype, 'abort').mockImplementation(() => undefined);
    const view = await show(); chooseFile(JSON.stringify(jsonDraft), 'authorization.json');
    mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, requestedAccountId: 'workspace-second' });
    view.rerender(ui('account-2'));
    await waitFor(() => expect(screen.getByRole('button', { name: '使用预置授权' })).toBeEnabled());
    expect(abort).toHaveBeenCalledOnce();
    Object.defineProperty(reader, 'result', { value: JSON.stringify(jsonDraft) });
    await act(async () => reader.onload?.call(reader, {} as ProgressEvent<FileReader>));
    fireEvent.click(screen.getByRole('button', { name: '高级：手动导入授权' }));
    expect(screen.getByLabelText('官方 Account ID')).toHaveValue('workspace-second');
    expect(screen.getByLabelText('官方 Authorization')).toHaveValue('');
  });
});
