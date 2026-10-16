import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProxyNodesPage from './index';
import routes from '../../../config/routes';

const mocks = vi.hoisted(() => ({ listProxyNodes: vi.fn(), createProxyNode: vi.fn(),
  updateProxyNode: vi.fn(), deleteProxyNode: vi.fn(), testProxyNode: vi.fn(),
  isSecureManagementContext: vi.fn(), isSupportedManagementContext: vi.fn() }));
vi.mock('../../services/api', () => mocks);
const node = { id: 'proxy-1', label: '香港出口', protocol: 'SOCKS5', host: 'proxy.example',
  port: 1080, enabled: true, hasCredentials: true, version: 2,
  egressIp: '203.0.113.7', country: 'HK', region: 'Hong Kong', city: 'Central',
  testedAt: '2026-10-16T01:00:00Z', testStatus: 'CONNECTED',
  createdAt: '2026-10-14T00:00:00Z', updatedAt: '2026-10-14T00:00:00Z' };

describe('代理节点管理', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.isSecureManagementContext.mockReturnValue(true);
    mocks.isSupportedManagementContext.mockReturnValue(true);
    mocks.listProxyNodes.mockResolvedValue([node]);
    mocks.createProxyNode.mockResolvedValue(node);
    mocks.updateProxyNode.mockResolvedValue(node);
    mocks.deleteProxyNode.mockResolvedValue(undefined);
    mocks.testProxyNode.mockResolvedValue({ connected: true, httpStatus: 200, status: 'CONNECTED',
      egressIp: node.egressIp, country: node.country, region: node.region, city: node.city, testedAt: node.testedAt });
  });

  it('lists node endpoint, egress location and test state without import or subscription controls', async () => {
    expect(routes).toContainEqual(expect.objectContaining({ path: '/proxy-nodes', access: 'canAdmin', name: '代理节点' }));
    render(<AntApp><ProxyNodesPage /></AntApp>);
    expect(await screen.findByText('香港出口')).toBeInTheDocument();
    expect(screen.getByText('proxy.example:1080')).toBeInTheDocument();
    expect(screen.getByText('203.0.113.7')).toBeInTheDocument();
    expect(screen.getByText('HK / Hong Kong / Central')).toBeInTheDocument();
    expect(screen.getByText('测试成功')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /导入|订阅/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/VLESS.*VMess.*Trojan.*SS/)).not.toBeInTheDocument();
  });

  it('creates a node from explicit protocol, host, port and optional credentials', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    let submitted: Record<string, unknown> | undefined;
    mocks.createProxyNode.mockImplementation(async (body) => { submitted = { ...body }; return node; });
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '添加代理节点' }));
    const dialog = await screen.findByRole('dialog', { name: '添加代理节点' });
    expect(within(dialog).queryByLabelText('节点链接')).not.toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('节点名称'), { target: { value: '办公出口' } });
    fireEvent.change(within(dialog).getByLabelText('服务器'), { target: { value: 'proxy.example' } });
    fireEvent.change(within(dialog).getByLabelText('端口'), { target: { value: '8080' } });
    fireEvent.change(within(dialog).getByLabelText('用户名（可选）'), { target: { value: 'fixture-user' } });
    fireEvent.change(within(dialog).getByLabelText('密码（可选）'), { target: { value: 'fixture-password' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.createProxyNode).toHaveBeenCalledOnce());
    expect(submitted).toEqual({ label: '办公出口', protocol: 'HTTP', host: 'proxy.example', port: 8080,
      username: 'fixture-user', password: 'fixture-password', enabled: true });
    expect(storage).not.toHaveBeenCalled();
  });

  it('keeps saved credentials private and omits blank credentials on edit', async () => {
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '编辑 香港出口' }));
    const dialog = await screen.findByRole('dialog', { name: '编辑代理节点' });
    expect(within(dialog).getByLabelText('用户名（可选）')).toHaveValue('');
    expect(within(dialog).getByLabelText('密码（可选）')).toHaveValue('');
    expect(within(dialog).getByLabelText('服务器')).toHaveValue('proxy.example');
    expect(within(dialog).getByLabelText('端口')).toHaveValue(1080);
    fireEvent.change(within(dialog).getByLabelText('节点名称'), { target: { value: '备用出口' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.updateProxyNode).toHaveBeenCalledWith('proxy-1', {
      label: '备用出口', protocol: 'SOCKS5', host: 'proxy.example', port: 1080, enabled: true, version: 2,
    }));
  });

  it('clears saved credentials only through the explicit clear control', async () => {
    let submitted: Record<string, unknown> | undefined;
    mocks.updateProxyNode.mockImplementation(async (_id, body) => { submitted = { ...body }; return node; });
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '编辑 香港出口' }));
    const dialog = await screen.findByRole('dialog', { name: '编辑代理节点' });
    fireEvent.click(within(dialog).getByRole('checkbox', { name: '清除已保存的用户名和密码' }));
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.updateProxyNode).toHaveBeenCalledOnce());
    expect(submitted).toMatchObject({ username: '', password: '' });
  });

  it('clears entered credentials after a failed save without displaying upstream secrets', async () => {
    mocks.createProxyNode.mockRejectedValue(new Error('fixture-private-password'));
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '添加代理节点' }));
    const dialog = await screen.findByRole('dialog', { name: '添加代理节点' });
    fireEvent.change(within(dialog).getByLabelText('节点名称'), { target: { value: '办公出口' } });
    fireEvent.change(within(dialog).getByLabelText('服务器'), { target: { value: 'proxy.example' } });
    fireEvent.change(within(dialog).getByLabelText('端口'), { target: { value: '8080' } });
    fireEvent.change(within(dialog).getByLabelText('用户名（可选）'), { target: { value: 'fixture-user' } });
    fireEvent.change(within(dialog).getByLabelText('密码（可选）'), { target: { value: 'fixture-private-password' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.createProxyNode).toHaveBeenCalledOnce());
    await waitFor(() => expect(within(dialog).getByLabelText('密码（可选）')).toHaveValue(''));
    expect(within(dialog).getByLabelText('用户名（可选）')).toHaveValue('');
    expect(screen.queryByText('fixture-private-password')).not.toBeInTheDocument();
  });

  it('requires a valid endpoint before saving', async () => {
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '添加代理节点' }));
    const dialog = await screen.findByRole('dialog', { name: '添加代理节点' });
    fireEvent.change(within(dialog).getByLabelText('节点名称'), { target: { value: '办公出口' } });
    fireEvent.change(within(dialog).getByLabelText('服务器'), { target: { value: 'http://proxy.example/path' } });
    fireEvent.change(within(dialog).getByLabelText('端口'), { target: { value: '70000' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    expect(await within(dialog).findByText('只填写服务器域名或 IP，不含协议、路径或凭据')).toBeInTheDocument();
    expect(await within(dialog).findByText('端口必须是 1 至 65535 的整数')).toBeInTheDocument();
    expect(mocks.createProxyNode).not.toHaveBeenCalled();
  });

  it('allows HTTP management with a plaintext warning and confirms deletion', async () => {
    mocks.isSecureManagementContext.mockReturnValue(false);
    render(<AntApp><ProxyNodesPage /></AntApp>);
    expect(await screen.findByRole('button', { name: '添加代理节点' })).toBeEnabled();
    expect(screen.getByText(/HTTP.*明文/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: '删除 香港出口' }));
    expect(mocks.deleteProxyNode).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(mocks.deleteProxyNode).toHaveBeenCalledWith('proxy-1'));
  });

  it('disables management actions for unsupported page protocols', async () => {
    mocks.isSupportedManagementContext.mockReturnValue(false);
    render(<AntApp><ProxyNodesPage /></AntApp>);
    expect(await screen.findByText(/HTTP 或 HTTPS/)).toBeInTheDocument();
    for (const name of ['添加代理节点', '编辑 香港出口', '删除 香港出口', '测试 香港出口']) {
      expect(await screen.findByRole('button', { name })).toBeDisabled();
    }
    expect(mocks.createProxyNode).not.toHaveBeenCalled();
    expect(mocks.testProxyNode).not.toHaveBeenCalled();
  });

  it('tests the saved node and reloads persisted egress metadata', async () => {
    mocks.listProxyNodes.mockResolvedValueOnce([{ ...node, egressIp: null, country: null, region: null, city: null,
      testedAt: null, testStatus: 'UNTESTED' }]).mockResolvedValue([node]);
    render(<AntApp><ProxyNodesPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: '测试 香港出口' }));
    await waitFor(() => expect(mocks.testProxyNode).toHaveBeenCalledExactlyOnceWith('proxy-1'));
    expect(await screen.findByText('203.0.113.7')).toBeInTheDocument();
    expect(mocks.listProxyNodes).toHaveBeenCalledTimes(2);
    expect(screen.getByText(/不能证明官方账户授权可用/)).toBeInTheDocument();
  });
});
