import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProxyNodesPage from './index';

const mocks = vi.hoisted(() => ({ listProxyNodes: vi.fn(), createProxyNode: vi.fn(), updateProxyNode: vi.fn(),
  deleteProxyNode: vi.fn(), testProxyNode: vi.fn(), setProxyNodeEnabled: vi.fn(), listProxySubscriptions: vi.fn(),
  createProxySubscription: vi.fn(), updateProxySubscription: vi.fn(), refreshProxySubscription: vi.fn(), deleteProxySubscription: vi.fn(),
  isSecureManagementContext: vi.fn(), isSupportedManagementContext: vi.fn() }));
vi.mock('../../services/api', () => mocks);
const source = { id: 'source-1', label: '办公订阅', hasUrl: true, sourceHost: 'subscription.example', nodeCount: 1,
  enabledNodeCount: 1, status: 'READY', createdAt: null, updatedAt: null, refreshedAt: null, version: 3 };
const node = { id: 'proxy-1', label: '香港出口', protocol: 'VLESS', host: 'proxy.example', port: 443, enabled: true,
  sourceSubscriptionId: 'source-1', sourceMissing: false, version: 4, testStatus: 'UNTESTED' };
async function showSources() {
  render(<AntApp><ProxyNodesPage /></AntApp>);
  fireEvent.click(await screen.findByRole('button', { name: '管理订阅' }));
  return screen.findByRole('dialog', { name: '代理订阅管理' });
}
describe('手动更新代理订阅', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mocks.isSupportedManagementContext.mockReturnValue(true); mocks.isSecureManagementContext.mockReturnValue(true);
    mocks.listProxyNodes.mockResolvedValue([node]); mocks.listProxySubscriptions.mockResolvedValue([source]);
    mocks.createProxySubscription.mockResolvedValue(source); mocks.updateProxySubscription.mockResolvedValue(source);
    mocks.refreshProxySubscription.mockResolvedValue(source); mocks.deleteProxySubscription.mockResolvedValue(undefined);
    mocks.setProxyNodeEnabled.mockResolvedValue({ ...node, enabled: false });
  });
  it('shows subscription origin and only enables advanced nodes through the dedicated endpoint', async () => {
    render(<AntApp><ProxyNodesPage /></AntApp>);
    expect(await screen.findByRole('button', { name: '编辑 香港出口' })).toBeDisabled();
    fireEvent.click(screen.getByRole('switch', { name: '启停 香港出口' }));
    await waitFor(() => expect(mocks.setProxyNodeEnabled).toHaveBeenCalledWith('proxy-1', { enabled: false, version: 4 }));
    expect(mocks.updateProxyNode).not.toHaveBeenCalled();
  });
  it('prevents enabling a missing source node', async () => {
    mocks.listProxyNodes.mockResolvedValue([{ ...node, sourceMissing: true, enabled: false }]);
    render(<AntApp><ProxyNodesPage /></AntApp>);
    expect(await screen.findByText('订阅中已移除')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: '启停 香港出口' })).toBeDisabled();
  });
  it('imports a secret URL once, clears it and leaves subsequent refresh manual', async () => {
    const write = vi.spyOn(Storage.prototype, 'setItem');
    let submitted: unknown;
    mocks.createProxySubscription.mockImplementation(async (body) => { submitted = { ...body }; return source; });
    const dialog = await showSources();
    fireEvent.click(within(dialog).getByRole('button', { name: '导入订阅' }));
    const editor = await screen.findByRole('dialog', { name: '导入代理订阅' });
    const url = within(editor).getByLabelText('订阅 URL');
    expect(url).toHaveAttribute('type', 'password');
    fireEvent.change(within(editor).getByLabelText('订阅名称'), { target: { value: '办公订阅' } });
    fireEvent.change(url, { target: { value: 'https://subscription.example/list?token=fixture-private' } });
    fireEvent.click(within(editor).getByRole('button', { name: '导入并获取节点' }));
    await waitFor(() => expect(mocks.createProxySubscription).toHaveBeenCalledOnce());
    expect(submitted).toEqual({ label: '办公订阅', url: 'https://subscription.example/list?token=fixture-private' });
    expect(write).not.toHaveBeenCalled();
    expect(mocks.refreshProxySubscription).not.toHaveBeenCalled();
    expect(screen.queryByDisplayValue(/fixture-private/)).not.toBeInTheDocument();
  });
  it('edits metadata without echoing or automatically fetching the stored URL', async () => {
    const dialog = await showSources();
    fireEvent.click(await within(dialog).findByRole('button', { name: '编辑订阅 办公订阅' }));
    const editor = await screen.findByRole('dialog', { name: '编辑代理订阅' });
    expect(within(editor).getByLabelText('订阅 URL')).toHaveValue('');
    fireEvent.change(within(editor).getByLabelText('订阅名称'), { target: { value: '备用订阅' } });
    fireEvent.click(within(editor).getByRole('button', { name: '保存订阅' }));
    await waitFor(() => expect(mocks.updateProxySubscription).toHaveBeenCalledWith('source-1', { label: '备用订阅', version: 3 }));
    expect(mocks.refreshProxySubscription).not.toHaveBeenCalled();
  });
  it('refreshes explicitly and removes only the subscription source', async () => {
    const dialog = await showSources();
    fireEvent.click(await within(dialog).findByRole('button', { name: '更新订阅 办公订阅' }));
    await waitFor(() => expect(mocks.refreshProxySubscription).toHaveBeenCalledWith('source-1', 3));
    fireEvent.click(within(dialog).getByRole('button', { name: '移除订阅 办公订阅' }));
    fireEvent.click(await screen.findByRole('button', { name: '仅移除来源' }));
    await waitFor(() => expect(mocks.deleteProxySubscription).toHaveBeenCalledWith('source-1', 3));
    expect(mocks.deleteProxyNode).not.toHaveBeenCalled();
  });
  it('clears failed secret input and never renders upstream URL tokens', async () => {
    mocks.createProxySubscription.mockRejectedValue({ code: 'UNRECOGNIZED', message: 'https://private.example/?token=fixture-private' });
    const dialog = await showSources();
    fireEvent.click(within(dialog).getByRole('button', { name: '导入订阅' }));
    const editor = await screen.findByRole('dialog', { name: '导入代理订阅' });
    fireEvent.change(within(editor).getByLabelText('订阅名称'), { target: { value: '失败订阅' } });
    fireEvent.change(within(editor).getByLabelText('订阅 URL'), { target: { value: 'https://private.example/?token=fixture-private' } });
    fireEvent.click(within(editor).getByRole('button', { name: '导入并获取节点' }));
    expect(await within(editor).findByText('订阅保存失败，请检查订阅地址和格式后重试。')).toBeInTheDocument();
    expect(within(editor).getByLabelText('订阅 URL')).toHaveValue('');
    expect(screen.queryByText(/fixture-private/)).not.toBeInTheDocument();
  });
  it.each(['http://subscription.example/list', 'https://subscription.example:8443/list', 'https://user:secret@subscription.example/list'])(
    'rejects an unsupported subscription address before import: %s', async (url) => {
      const dialog = await showSources();
      fireEvent.click(within(dialog).getByRole('button', { name: '导入订阅' }));
      const editor = await screen.findByRole('dialog', { name: '导入代理订阅' });
      fireEvent.change(within(editor).getByLabelText('订阅名称'), { target: { value: '办公订阅' } });
      fireEvent.change(within(editor).getByLabelText('订阅 URL'), { target: { value: url } });
      fireEvent.click(within(editor).getByRole('button', { name: '导入并获取节点' }));
      expect(await within(editor).findByText('请输入 HTTPS 443 订阅地址，不含用户名、密码或片段')).toBeInTheDocument();
      expect(mocks.createProxySubscription).not.toHaveBeenCalled();
    },
  );
  it('marks a changed address as awaiting manual refresh', async () => {
    mocks.listProxySubscriptions.mockResolvedValue([{ ...source, status: 'URL_UPDATED' }]);
    const dialog = await showSources();
    expect(await within(dialog).findByText('地址已修改，待手动更新')).toBeInTheDocument();
    expect(mocks.refreshProxySubscription).not.toHaveBeenCalled();
  });
  it('keeps the current nodes on a failed refresh and displays an explicit unsupported-format message', async () => {
    mocks.refreshProxySubscription.mockRejectedValue({ code: 'PROXY_SUBSCRIPTION_FORMAT_UNSUPPORTED', message: 'fixture-private' });
    const dialog = await showSources();
    fireEvent.click(await within(dialog).findByRole('button', { name: '更新订阅 办公订阅' }));
    expect(await within(dialog).findByText(/订阅格式或协议参数不支持.*Clash YAML/)).toBeInTheDocument();
    expect(mocks.listProxyNodes).toHaveBeenCalledOnce();
    expect(mocks.deleteProxyNode).not.toHaveBeenCalled();
    expect(screen.queryByText('fixture-private')).not.toBeInTheDocument();
  });
  it('redacts subscription-list failures', async () => {
    mocks.listProxySubscriptions.mockRejectedValue(new Error('https://private.example/?token=fixture-private'));
    const dialog = await showSources();
    expect(await within(dialog).findByText('订阅读取失败，请稍后重试。')).toBeInTheDocument();
    expect(screen.queryByText(/fixture-private/)).not.toBeInTheDocument();
  });
});
