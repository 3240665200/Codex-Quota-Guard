import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';

interface ManagedApi {
  getOfficialAuthorizationPreset(): Promise<unknown>;
  applyOfficialAuthorizationPreset(id: string, body: Record<string, unknown>): Promise<unknown>;
  getOfficialAccountConnection(id: string): Promise<unknown>;
  saveOfficialAccountConnection(id: string, body: Record<string, unknown>): Promise<unknown>;
  deleteOfficialAccountConnection(id: string): Promise<void>;
  refreshOfficialAccountConnection(id: string): Promise<unknown>;
  getOfficialAccountSessions(id: string): Promise<unknown>;
  listProxyNodes(): Promise<unknown>;
  createProxyNode(body: Record<string, unknown>): Promise<unknown>;
  updateProxyNode(id: string, body: Record<string, unknown>): Promise<unknown>;
  deleteProxyNode(id: string): Promise<void>;
  testProxyNode(id: string): Promise<unknown>;
}
const managed = api as unknown as ManagedApi;

describe('官方授权与代理节点 API', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.clearAllMocks();
    api.clearSession();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '{}' });
  });

  it('encodes account IDs and separates saved snapshots from an explicit refresh', async () => {
    expect(managed.getOfficialAccountConnection).toBeTypeOf('function');
    await managed.getOfficialAccountConnection('account/id');
    await managed.getOfficialAccountSessions('account/id');
    await managed.refreshOfficialAccountConnection('account/id');
    await managed.deleteOfficialAccountConnection('account/id');
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/admin/official-accounts/account%2Fid/connection',
      '/api/v1/admin/official-accounts/account%2Fid/sessions',
      '/api/v1/admin/official-accounts/account%2Fid/connection/refresh',
      '/api/v1/admin/official-accounts/account%2Fid/connection',
    ]);
    expect(fetchMock.mock.calls[2][1]).toMatchObject({ method: 'POST' });
    expect(fetchMock.mock.calls[2][1].body).toBeUndefined();
    expect(fetchMock.mock.calls[3][1].method).toBe('DELETE');
  });

  it('submits official authorization in the JSON body without browser persistence', async () => {
    expect(managed.saveOfficialAccountConnection).toBeTypeOf('function');
    const write = vi.spyOn(Storage.prototype, 'setItem');
    const body = { authorization: 'Bearer fixture-only', cookie: '__Secure-next-auth.session-token=fixture-only',
      requestedAccountId: 'official-account', proxyNodeId: 'proxy-1', version: 2 };
    await managed.saveOfficialAccountConnection('account/id', body);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/admin/official-accounts/account%2Fid/connection');
    expect(fetchMock.mock.calls[0][1].method).toBe('PUT');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual(body);
    expect(fetchMock.mock.calls[0][1].headers.get('Authorization')).toBeNull();
    expect(write).not.toHaveBeenCalled();
  });

  it('reads preset metadata and applies its revision without exposing or persisting credentials', async () => {
    const write = vi.spyOn(Storage.prototype, 'setItem');
    expect(managed.getOfficialAuthorizationPreset).toBeTypeOf('function');
    expect(managed.applyOfficialAuthorizationPreset).toBeTypeOf('function');
    await managed.getOfficialAuthorizationPreset();
    const body = { version: 2, proxyNodeId: 'proxy-1', presetRevision: 'revision-1' };
    await managed.applyOfficialAuthorizationPreset('account/id', body);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/admin/official-authorization-preset', '/api/v1/admin/official-accounts/account%2Fid/connection/preset',
    ]);
    expect(fetchMock.mock.calls[1][1].method).toBe('POST');
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(body);
    expect(write).not.toHaveBeenCalled();
  });

  it('uses explicit proxy fields and a saved-node test without subscription or URI APIs', async () => {
    expect(managed.listProxyNodes).toBeTypeOf('function');
    await managed.listProxyNodes();
    const body = { label: '出口', protocol: 'SOCKS5', host: 'proxy.example', port: 1080,
      username: 'fixture', password: 'fixture-secret', enabled: true };
    await managed.createProxyNode(body);
    await managed.updateProxyNode('proxy/id', { label: '新名称', protocol: 'HTTP', host: 'proxy.example', port: 8080, enabled: false, version: 3 });
    await managed.testProxyNode('proxy/id');
    await managed.deleteProxyNode('proxy/id');
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/admin/proxy-nodes', '/api/v1/admin/proxy-nodes',
      '/api/v1/admin/proxy-nodes/proxy%2Fid',
      '/api/v1/admin/proxy-nodes/proxy%2Fid/test',
      '/api/v1/admin/proxy-nodes/proxy%2Fid',
    ]);
    expect(fetchMock.mock.calls.map(([, options]) => options.method ?? 'GET')).toEqual(['GET', 'POST', 'PUT', 'POST', 'DELETE']);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(body);
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).not.toHaveProperty('uri');
    expect(fetchMock.mock.calls[3][1].body).toBeUndefined();
    expect(api).not.toHaveProperty('importProxyNodes');
  });
});
