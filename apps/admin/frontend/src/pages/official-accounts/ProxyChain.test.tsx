import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ConnectionModal from './ConnectionModal';
import type { OfficialAccountResponse } from '../../services/api';

const mocks = vi.hoisted(() => ({ getOfficialAccountConnection: vi.fn(), getOfficialAccountSessions: vi.fn(),
  listProxyNodes: vi.fn(), getOfficialAuthorizationPreset: vi.fn(), applyOfficialAuthorizationPreset: vi.fn(),
  saveOfficialAccountConnection: vi.fn(), refreshOfficialAccountConnection: vi.fn(), deleteOfficialAccountConnection: vi.fn(),
  testProxyChain: vi.fn(), testProxyNode: vi.fn(), saveOfficialAccountProxyChain: vi.fn(), isSupportedManagementContext: vi.fn() }));
vi.mock('../../services/api', () => mocks);
vi.mock('../../services/sessionBindingsApi', () => ({ listSessionBindings: vi.fn().mockResolvedValue([]), releaseSessionBinding: vi.fn() }));
const connection = { configured: true, encryptionReady: true, status: 'UNVERIFIED', requestedAccountId: 'workspace',
  frontProxyNodeId: 'front', proxyNodeId: 'exit', hasAuthorization: true, hasCookie: false, version: 5 };
const nodes = [{ id: 'front', label: '前置香港', protocol: 'VLESS', enabled: true },
  { id: 'exit', label: '住宅出口', protocol: 'SOCKS5', enabled: true }];
async function show() {
  render(<AntApp><ConnectionModal account={{ id: 'account-1', label: '账户一' } as OfficialAccountResponse}
    onClose={vi.fn()} view="authorization" inline /></AntApp>);
  await act(async () => {});
  await screen.findByLabelText('前置代理节点（可选）');
}
describe('每账户独立单节点与双节点链路', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mocks.isSupportedManagementContext.mockReturnValue(true);
    mocks.getOfficialAccountConnection.mockResolvedValue(connection); mocks.listProxyNodes.mockResolvedValue(nodes);
    mocks.getOfficialAccountSessions.mockRejectedValue({ code: 'OFFICIAL_SNAPSHOT_UNAVAILABLE' });
    mocks.getOfficialAuthorizationPreset.mockResolvedValue({ available: true, requestedAccountId: 'workspace', revision: 'revision-1' });
    mocks.applyOfficialAuthorizationPreset.mockResolvedValue({ ...connection, version: 6 });
    mocks.saveOfficialAccountConnection.mockResolvedValue({ ...connection, version: 6 });
    mocks.saveOfficialAccountProxyChain.mockResolvedValue({ ...connection, version: 6 });
    mocks.testProxyChain.mockResolvedValue({ frontProxyNodeId: 'front', proxyNodeId: 'exit', connected: true,
      egressIp: '203.0.113.44', country: 'HK', region: null, city: null, httpStatus: 200, testedAt: '2026-10-16T00:00:00Z' });
  });
  it('shows the ordered chain and preserves both nodes while applying preset authorization', async () => {
    await show();
    expect(screen.getByText('服务器 → 前置香港 → 住宅出口 → 官网')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '使用预置授权' }));
    await waitFor(() => expect(mocks.applyOfficialAuthorizationPreset).toHaveBeenCalledWith('account-1', {
      version: 5, frontProxyNodeId: 'front', proxyNodeId: 'exit', presetRevision: 'revision-1',
    }));
  });
  it('tests the complete selected chain and shows its actual exit independently of single-node tests', async () => {
    await show(); fireEvent.click(screen.getByRole('button', { name: '测试完整链路' }));
    await waitFor(() => expect(mocks.testProxyChain).toHaveBeenCalledWith({ frontProxyNodeId: 'front', proxyNodeId: 'exit' }));
    expect(await screen.findByText(/完整链路出口 IP：203\.0\.113\.44/)).toBeInTheDocument();
    expect(mocks.testProxyNode).not.toHaveBeenCalled();
  });
  it.each(['disabled', 'missing', 'sourceMissing', 'same', 'noExit'])('blocks save and chain test for %s configuration', async (reason) => {
    if (reason === 'disabled') mocks.listProxyNodes.mockResolvedValue([{ ...nodes[0], enabled: false }, nodes[1]]);
    if (reason === 'missing') mocks.listProxyNodes.mockResolvedValue([nodes[1]]);
    if (reason === 'sourceMissing') mocks.listProxyNodes.mockResolvedValue([{ ...nodes[0], sourceMissing: true }, nodes[1]]);
    if (reason === 'same') mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, frontProxyNodeId: 'exit' });
    if (reason === 'noExit') mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, proxyNodeId: null });
    await show();
    expect(screen.getByRole('button', { name: '使用预置授权' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '测试完整链路' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '保存代理链' })).toBeDisabled();
    expect(mocks.testProxyChain).not.toHaveBeenCalled();
  });
  it('keeps the single-node request compatible when no front node is selected', async () => {
    mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, frontProxyNodeId: null });
    await show(); expect(screen.getByText('服务器 → 住宅出口 → 官网')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '测试完整链路' }));
    await waitFor(() => expect(mocks.testProxyChain).toHaveBeenCalledWith({ proxyNodeId: 'exit' }));
  });
  it('retains both selections through local authorization file import and submission', async () => {
    let submitted: unknown;
    mocks.saveOfficialAccountConnection.mockImplementation(async (_id, body) => { submitted = { ...body }; return connection; });
    await show();
    fireEvent.change(screen.getByLabelText('本机参数文件'), { target: { files: [new File([JSON.stringify({
      requestedAccountId: 'workspace-file', authorization: 'Bearer fixture-file', cookie: '',
    })], 'authorization.json')] } });
    await screen.findByLabelText('官方 Authorization');
    fireEvent.click(screen.getByRole('button', { name: '保存授权' }));
    await waitFor(() => expect(mocks.saveOfficialAccountConnection).toHaveBeenCalledOnce());
    expect(submitted).toMatchObject({ frontProxyNodeId: 'front', proxyNodeId: 'exit', authorization: 'Bearer fixture-file' });
  });
  it('clears previous success when a new chain test fails without rendering upstream secrets', async () => {
    await show(); fireEvent.click(screen.getByRole('button', { name: '测试完整链路' }));
    await screen.findByText(/完整链路出口 IP/);
    mocks.testProxyChain.mockRejectedValue({ message: 'fixture-private', code: 'PROXY_CONNECTION_FAILED' });
    fireEvent.click(screen.getByRole('button', { name: '测试完整链路' }));
    await waitFor(() => expect(mocks.testProxyChain).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('无法通过此代理建立连接，请检查节点服务。')).toBeInTheDocument();
    expect(screen.queryByText(/203\.0\.113\.44/)).not.toBeInTheDocument();
    expect(screen.queryByText('fixture-private')).not.toBeInTheDocument();
  });
  it('offers saving the proxy chain for an already authorized account without entering credentials', async () => {
    await show();
    expect(screen.getByRole('button', { name: '保存代理链' })).toBeEnabled();
    expect(screen.queryByLabelText('官方 Authorization')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '保存代理链' }));
    await waitFor(() => expect(mocks.saveOfficialAccountProxyChain).toHaveBeenCalledWith('account-1', {
      frontProxyNodeId: 'front', proxyNodeId: 'exit', version: 5,
    }));
    expect(mocks.saveOfficialAccountConnection).not.toHaveBeenCalled();
    expect(mocks.refreshOfficialAccountConnection).not.toHaveBeenCalled();
    expect(await screen.findByText('代理链已保存，请重新刷新官方账户与会话。')).toBeInTheDocument();
  });
  it('keeps an unsaved authorization draft when saving only the chain', async () => {
    await show();
    fireEvent.click(screen.getByRole('button', { name: '高级：手动导入授权' }));
    const authorization = await screen.findByLabelText('官方 Authorization');
    fireEvent.change(authorization, { target: { value: 'Bearer fixture-draft' } });
    fireEvent.click(screen.getByRole('button', { name: '保存代理链' }));
    await waitFor(() => expect(mocks.saveOfficialAccountProxyChain).toHaveBeenCalledOnce());
    expect(authorization).toHaveValue('Bearer fixture-draft');
  });
  it('requires saved authorization before saving only the chain', async () => {
    mocks.getOfficialAccountConnection.mockResolvedValue({ ...connection, configured: false });
    await show();
    expect(screen.getByRole('button', { name: '保存代理链' })).toBeDisabled();
  });
  it('explicitly clears a formerly saved front node for a single-node account chain', async () => {
    await show();
    fireEvent.mouseDown(screen.getByRole('button', { name: 'Clear' }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() => expect(screen.getByText('服务器 → 住宅出口 → 官网')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '保存代理链' }));
    await waitFor(() => expect(mocks.saveOfficialAccountProxyChain).toHaveBeenCalledWith('account-1', {
      frontProxyNodeId: null, proxyNodeId: 'exit', version: 5,
    }));
  });
  it('rejects a success result without an egress IP', async () => {
    mocks.testProxyChain.mockResolvedValue({ connected: true, frontProxyNodeId: 'front', proxyNodeId: 'exit', egressIp: null });
    await show(); fireEvent.click(screen.getByRole('button', { name: '测试完整链路' }));
    expect(await screen.findByText('未能验证节点测试返回的出口信息，请检查节点后重新测试。')).toBeInTheDocument();
    expect(screen.queryByText('完整链路测试成功')).not.toBeInTheDocument();
  });
  it('hides a previous pair result as soon as the selected chain changes', async () => {
    await show(); fireEvent.click(screen.getByRole('button', { name: '测试完整链路' }));
    await screen.findByText(/完整链路出口 IP/);
    fireEvent.mouseDown(screen.getByRole('button', { name: 'Clear' }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() => expect(screen.queryByText(/完整链路出口 IP/)).not.toBeInTheDocument());
  });
  it('sends only one authorization write when save is clicked twice before validation completes', async () => {
    mocks.saveOfficialAccountConnection.mockImplementation(() => new Promise(() => {}));
    await show();
    fireEvent.click(screen.getByRole('button', { name: '高级：手动导入授权' }));
    fireEvent.change(await screen.findByLabelText('官方 Authorization'), { target: { value: 'Bearer fixture-double' } });
    const save = screen.getByRole('button', { name: '保存授权' });
    fireEvent.click(save); fireEvent.click(save);
    await waitFor(() => expect(mocks.saveOfficialAccountConnection).toHaveBeenCalledOnce());
    expect(screen.getByRole('button', { name: '保存代理链' })).toBeDisabled();
  });

  it('keeps file import exclusive when it starts during authorization validation', async () => {
    let reader!: FileReader;
    vi.spyOn(FileReader.prototype, 'readAsText').mockImplementation(function (this: FileReader) {
      reader = this;
      Object.defineProperty(this, 'readyState', { value: FileReader.LOADING });
    });
    await show();
    fireEvent.click(screen.getByRole('button', { name: '高级：手动导入授权' }));
    fireEvent.change(await screen.findByLabelText('官方 Authorization'), { target: { value: 'Bearer fixture-before-import' } });
    fireEvent.click(screen.getByRole('button', { name: '保存授权' }));
    fireEvent.change(screen.getByLabelText('本机参数文件'), { target: { files: [new File(['{}'], 'authorization.json')] } });
    await act(async () => {});
    expect(mocks.saveOfficialAccountConnection).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '保存代理链' })).toBeDisabled();
    Object.defineProperty(reader, 'result', { value: JSON.stringify({
      requestedAccountId: 'workspace-file', authorization: 'Bearer fixture-after-import', cookie: '',
    }) });
    await act(async () => reader.onload?.call(reader, {} as ProgressEvent<FileReader>));
    expect(screen.getByLabelText('官方 Authorization')).toHaveValue('Bearer fixture-after-import');
    expect(screen.getByRole('button', { name: '保存授权' })).toBeEnabled();
    expect(mocks.saveOfficialAccountConnection).not.toHaveBeenCalled();
  });

});
