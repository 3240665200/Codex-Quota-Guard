import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProxyNodesPage from './index';
import routes from '../../../config/routes';

const mocks = vi.hoisted(() => ({ listProxyNodes: vi.fn(), createProxyNode: vi.fn(),
  updateProxyNode: vi.fn(), deleteProxyNode: vi.fn(), importProxyNodes: vi.fn(), testProxyNode: vi.fn(), isSecureManagementContext: vi.fn() }));
vi.mock('../../services/api', () => mocks);
const node = { id: 'proxy-1', label: '香港出口', protocol: 'SOCKS5', host: 'proxy.example',
  port: 1080, enabled: true, hasCredentials: true, version: 2,
  createdAt: '2026-10-14T00:00:00Z', updatedAt: '2026-10-14T00:00:00Z' };

describe('代理节点管理', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.isSecureManagementContext.mockReturnValue(true);
    mocks.listProxyNodes.mockResolvedValue([node]);
    mocks.createProxyNode.mockResolvedValue(node);
    mocks.updateProxyNode.mockResolvedValue(node);
    mocks.deleteProxyNode.mockResolvedValue(undefined);
    mocks.importProxyNodes.mockResolvedValue({ nodes: [node], importedCount: 1 });
    mocks.testProxyNode.mockResolvedValue({ connected: true, httpStatus: 403, status: 'ACCESS_BLOCKED' });
  });

  it('has an admin-only menu and lists safe node metadata', async () => {
    expect(routes).toContainEqual(expect.objectContaining({ path: '/proxy-nodes', access: 'canAdmin', name: '代理节点' }));
    render(<AntApp><ProxyNodesPage /></AntApp>);
    expect(await screen.findByText('香港出口')).toBeInTheDocument();
    expect(screen.getByText('proxy.example:1080')).toBeInTheDocument();
    expect(screen.getByText(/已加密保存/)).toBeInTheDocument();
    expect(screen.getByText(/VLESS.*VMess.*Trojan.*SS/)).toBeInTheDocument();
  });

  it('keeps saved node URI private and omits it when editing metadata', async () => {
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '编辑 香港出口' }));
    const dialog = await screen.findByRole('dialog', { name: '编辑代理节点' });
    expect(within(dialog).getByLabelText('节点链接')).toHaveValue('');
    fireEvent.change(within(dialog).getByLabelText('节点名称'), { target: { value: '备用出口' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.updateProxyNode).toHaveBeenCalledWith('proxy-1', {
      label: '备用出口', enabled: true, version: 2,
    }));
  });

  it('imports URI/base64 subscription content and clears sensitive content after failure', async () => {
    mocks.importProxyNodes.mockRejectedValue(new Error('fixture-sensitive-proxy-error'));
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    let submitted: Record<string, unknown> | undefined;
    mocks.importProxyNodes.mockImplementation(async (body) => { submitted = { ...body }; throw new Error('private-url'); });
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '导入节点 / 订阅' }));
    const dialog = await screen.findByRole('dialog', { name: '导入代理节点' });
    expect(within(dialog).getByText(/Clash YAML.*sing-box JSON/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('节点链接或 Base64 订阅内容'), {
      target: { value: 'socks5://fixture:private@proxy.example:1080' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /^导\s*入$/ }));
    await waitFor(() => expect(mocks.importProxyNodes).toHaveBeenCalledOnce());
    expect(submitted).toEqual({ content: 'socks5://fixture:private@proxy.example:1080' });
    await waitFor(() => expect(within(dialog).getByLabelText('节点链接或 Base64 订阅内容')).toHaveValue(''));
    expect(screen.queryByText('private-url')).not.toBeInTheDocument();
    expect(storage).not.toHaveBeenCalled();
  });

  it('accepts exactly one import source and rejects non-HTTPS subscription URLs', async () => {
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '导入节点 / 订阅' }));
    const dialog = await screen.findByRole('dialog', { name: '导入代理节点' });
    fireEvent.change(within(dialog).getByLabelText('HTTPS 订阅地址'), { target: { value: 'http://subscription.example/nodes' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^导\s*入$/ }));
    expect(await within(dialog).findByText('订阅地址必须使用 HTTPS')).toBeInTheDocument();
    expect(mocks.importProxyNodes).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByLabelText('HTTPS 订阅地址'), { target: { value: 'https://subscription.example/nodes' } });
    fireEvent.change(within(dialog).getByLabelText('节点链接或 Base64 订阅内容'), { target: { value: 'fixture-content' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^导\s*入$/ }));
    expect(await within(dialog).findByText('节点内容和订阅地址只能填写一项')).toBeInTheDocument();
    expect(mocks.importProxyNodes).not.toHaveBeenCalled();
  });

  it('requires confirmation to delete a node and blocks writes over HTTP', async () => {
    mocks.isSecureManagementContext.mockReturnValue(false);
    render(<AntApp><ProxyNodesPage /></AntApp>);
    expect(await screen.findByText(/请通过 HTTPS/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '添加代理节点' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '导入节点 / 订阅' })).toBeDisabled();
    fireEvent.click(await screen.findByRole('button', { name: '删除 香港出口' }));
    expect(mocks.deleteProxyNode).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(mocks.deleteProxyNode).toHaveBeenCalledWith('proxy-1'));
  });

  it('tests only the saved node against the server-fixed destination and explains an upstream 403', async () => {
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '测试 香港出口' }));
    await waitFor(() => expect(mocks.testProxyNode).toHaveBeenCalledWith('proxy-1'));
    expect(mocks.testProxyNode.mock.calls[0]).toHaveLength(1);
    expect(await screen.findByText(/官方站点返回 403/)).toBeInTheDocument();
    expect(screen.getByText(/不能证明官方账户授权可用/)).toBeInTheDocument();
  });
});
