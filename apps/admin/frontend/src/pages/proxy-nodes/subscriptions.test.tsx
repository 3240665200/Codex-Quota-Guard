import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProxyNodesPage from './index';

const mocks = vi.hoisted(() => ({ listProxyNodes: vi.fn(), createProxyNode: vi.fn(), updateProxyNode: vi.fn(),
  deleteProxyNode: vi.fn(), testProxyNode: vi.fn(), setProxyNodeEnabled: vi.fn(), listProxySubscriptions: vi.fn(),
  createProxySubscription: vi.fn(), updateProxySubscription: vi.fn(), refreshProxySubscription: vi.fn(), deleteProxySubscription: vi.fn(),
  isSecureManagementContext: vi.fn(), isSupportedManagementContext: vi.fn() }));
vi.mock('../../services/api', () => mocks);
const source = { id: 'source-1', label: '办公订阅', hasUrl: true, allowInsecureTrojan: false, sourceHost: 'subscription.example', nodeCount: 1,
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
    expect(within(editor).getByRole('checkbox', { name: '允许此订阅的 Trojan 节点跳过证书验证' })).not.toBeChecked();
    expect(within(editor).queryByText(/中间人攻击/)).not.toBeInTheDocument();
    fireEvent.change(within(editor).getByLabelText('订阅名称'), { target: { value: '办公订阅' } });
    fireEvent.change(url, { target: { value: 'https://subscription.example/list?token=fixture-private' } });
    fireEvent.click(within(editor).getByRole('button', { name: '导入并获取节点' }));
    await waitFor(() => expect(mocks.createProxySubscription).toHaveBeenCalledOnce());
    expect(submitted).toEqual({ label: '办公订阅', url: 'https://subscription.example/list?token=fixture-private', allowInsecureTrojan: false });
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
    await waitFor(() => expect(mocks.updateProxySubscription).toHaveBeenCalledWith('source-1', { label: '备用订阅', allowInsecureTrojan: false, version: 3 }));
    expect(mocks.refreshProxySubscription).not.toHaveBeenCalled();
  });
  it('imports Trojan compatibility only after explicit consent with its scope and risk visible', async () => {
    let submitted: unknown;
    mocks.createProxySubscription.mockImplementation(async (body) => { submitted = { ...body }; return source; });
    const dialog = await showSources();
    fireEvent.click(within(dialog).getByRole('button', { name: '导入订阅' }));
    const editor = await screen.findByRole('dialog', { name: '导入代理订阅' });
    expect(within(editor).getByText(/仅对本订阅 URI 中声明 allowInsecure=1\/true.*其他协议及其他订阅仍验证 TLS 证书/)).toBeInTheDocument();
    fireEvent.click(within(editor).getByRole('checkbox', { name: '允许此订阅的 Trojan 节点跳过证书验证' }));
    expect(await within(editor).findByText(/跳过证书验证可能遭受中间人攻击/)).toBeInTheDocument();
    fireEvent.change(within(editor).getByLabelText('订阅名称'), { target: { value: 'Trojan 订阅' } });
    fireEvent.change(within(editor).getByLabelText('订阅 URL'), { target: { value: 'https://subscription.example/list' } });
    fireEvent.click(within(editor).getByRole('button', { name: '导入并获取节点' }));
    await waitFor(() => expect(submitted).toEqual({ label: 'Trojan 订阅', url: 'https://subscription.example/list', allowInsecureTrojan: true }));
    expect(mocks.refreshProxySubscription).not.toHaveBeenCalled();
  });
  it('restores a subscription consent flag and explicitly disables it when unchecked', async () => {
    mocks.listProxySubscriptions.mockResolvedValue([{ ...source, allowInsecureTrojan: true }]);
    const dialog = await showSources();
    expect(await within(dialog).findByText('Trojan 兼容已开启')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '编辑订阅 办公订阅' }));
    const editor = await screen.findByRole('dialog', { name: '编辑代理订阅' });
    const checkbox = within(editor).getByRole('checkbox', { name: '允许此订阅的 Trojan 节点跳过证书验证' });
    expect(checkbox).toBeChecked();
    expect(within(editor).getByText(/关闭此选项会恢复此前例外节点的证书验证并停用这些节点.*账户引用/)).toBeInTheDocument();
    fireEvent.click(checkbox);
    await waitFor(() => {
      expect(checkbox).not.toBeChecked();
      expect(within(editor).queryByText(/中间人攻击/)).not.toBeInTheDocument();
    });
    fireEvent.click(within(editor).getByRole('button', { name: '保存订阅' }));
    await waitFor(() => expect(mocks.updateProxySubscription).toHaveBeenCalledWith('source-1', {
      label: '办公订阅', allowInsecureTrojan: false, version: 3,
    }));
  });
  it('starts every new subscription with consent off after cancelling an enabled editor or checked draft', async () => {
    mocks.listProxySubscriptions.mockResolvedValue([{ ...source, allowInsecureTrojan: true }]);
    const dialog = await showSources();
    fireEvent.click(await within(dialog).findByRole('button', { name: '编辑订阅 办公订阅' }));
    const existingEditor = await screen.findByRole('dialog', { name: '编辑代理订阅' });
    expect(within(existingEditor).getByRole('checkbox')).toBeChecked();
    fireEvent.click(within(existingEditor).getByRole('button', { name: /取\s*消/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '导入订阅' }));
    const editor = await screen.findByRole('dialog', { name: '导入代理订阅' });
    expect(within(editor).getByRole('checkbox')).not.toBeChecked();
    fireEvent.click(within(editor).getByRole('checkbox'));
    fireEvent.click(within(editor).getByRole('button', { name: /取\s*消/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '导入订阅' }));
    const nextEditor = await screen.findByRole('dialog', { name: '导入代理订阅' });
    expect(within(nextEditor).getByRole('checkbox')).not.toBeChecked();
    expect(within(nextEditor).queryByText(/中间人攻击/)).not.toBeInTheDocument();
    expect(mocks.updateProxySubscription).not.toHaveBeenCalled();
    expect(mocks.createProxySubscription).not.toHaveBeenCalled();
  });
  it('refreshes explicitly and removes only the subscription source', async () => {
    const dialog = await showSources();
    fireEvent.click(await within(dialog).findByRole('button', { name: '更新订阅 办公订阅' }));
    await waitFor(() => expect(mocks.refreshProxySubscription).toHaveBeenCalledWith('source-1', 3));
    fireEvent.click(within(dialog).getByRole('button', { name: '移除订阅 办公订阅' }));
    expect(await screen.findByText('只解除订阅来源，保留所有节点与账户选择。')).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: '仅移除来源' }));
    await waitFor(() => expect(mocks.deleteProxySubscription).toHaveBeenCalledWith('source-1', 3));
    expect(mocks.deleteProxyNode).not.toHaveBeenCalled();
  });
  it('explains restored verification and disabled exception nodes before removing a compatible subscription', async () => {
    mocks.listProxySubscriptions.mockResolvedValue([{ ...source, allowInsecureTrojan: true }]);
    const dialog = await showSources();
    fireEvent.click(await within(dialog).findByRole('button', { name: '移除订阅 办公订阅' }));
    expect(await screen.findByText('移除来源会恢复此前例外节点的证书验证并停用这些节点，保留所有节点 ID 与账户选择。')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '仅移除来源' }));
    await waitFor(() => expect(mocks.deleteProxySubscription).toHaveBeenCalledWith('source-1', 3));
    expect(mocks.deleteProxyNode).not.toHaveBeenCalled();
  });
  it.each([
    ['PROXY_TROJAN_TLS_CONFIRMATION_REQUIRED', /当前订阅表单.*主动勾选/],
    ['PROXY_TROJAN_SNI_CONFLICT', /不一致的 TLS 服务器名称参数/],
  ])('explains %s safely without automatically granting consent', async (code, expected) => {
    mocks.createProxySubscription.mockRejectedValue({ code, message: 'https://private.example/?token=fixture-private' });
    const dialog = await showSources();
    fireEvent.click(within(dialog).getByRole('button', { name: '导入订阅' }));
    const editor = await screen.findByRole('dialog', { name: '导入代理订阅' });
    fireEvent.change(within(editor).getByLabelText('订阅名称'), { target: { value: 'Trojan 订阅' } });
    fireEvent.change(within(editor).getByLabelText('订阅 URL'), { target: { value: 'https://subscription.example/list' } });
    fireEvent.click(within(editor).getByRole('button', { name: '导入并获取节点' }));
    expect(await within(editor).findByText(expected)).toBeInTheDocument();
    expect(within(editor).getByRole('checkbox')).not.toBeChecked();
    expect(within(editor).getByLabelText('订阅 URL')).toHaveValue('');
    expect(screen.queryByText(/fixture-private/)).not.toBeInTheDocument();
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
