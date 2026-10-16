import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';

const management = api as unknown as Record<string, (...args: any[]) => Promise<unknown>>;
describe('订阅与完整代理链 API', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.clearAllMocks(); api.clearSession(); vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '{}' });
  });
  it('manages subscriptions with explicit versioned refresh and never persists the URL', async () => {
    const write = vi.spyOn(Storage.prototype, 'setItem');
    const input = { label: '办公订阅', url: 'https://subscription.example/list?token=fixture-only' };
    for (const name of ['listProxySubscriptions', 'createProxySubscription', 'updateProxySubscription', 'refreshProxySubscription', 'deleteProxySubscription']) {
      expect(management[name]).toBeTypeOf('function');
    }
    await management.listProxySubscriptions();
    await management.createProxySubscription(input);
    await management.updateProxySubscription('source/id', { label: '备用订阅', version: 3 });
    await management.refreshProxySubscription('source/id', 4);
    await management.deleteProxySubscription('source/id', 5);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/admin/proxy-subscriptions', '/api/v1/admin/proxy-subscriptions',
      '/api/v1/admin/proxy-subscriptions/source%2Fid', '/api/v1/admin/proxy-subscriptions/source%2Fid/refresh',
      '/api/v1/admin/proxy-subscriptions/source%2Fid?version=5',
    ]);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(input);
    expect(JSON.parse(fetchMock.mock.calls[3][1].body)).toEqual({ version: 4 });
    expect(write).not.toHaveBeenCalled();
  });
  it('tests a pair separately from a node and uses the dedicated enable endpoint', async () => {
    expect(management.testProxyChain).toBeTypeOf('function');
    expect(management.setProxyNodeEnabled).toBeTypeOf('function');
    await management.testProxyChain({ frontProxyNodeId: 'front', proxyNodeId: 'exit' });
    await management.setProxyNodeEnabled('proxy/id', { enabled: false, version: 3 });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/admin/proxy-chains/test');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ frontProxyNodeId: 'front', proxyNodeId: 'exit' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/admin/proxy-nodes/proxy%2Fid/enabled');
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ enabled: false, version: 3 });
  });
  it('saves only the account proxy chain without submitting authorization secrets', async () => {
    expect(management.saveOfficialAccountProxyChain).toBeTypeOf('function');
    await management.saveOfficialAccountProxyChain('account/id', { frontProxyNodeId: 'front', proxyNodeId: 'exit', version: 5 });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/admin/official-accounts/account%2Fid/connection/proxy');
    expect(fetchMock.mock.calls[0][1].method).toBe('PUT');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ frontProxyNodeId: 'front', proxyNodeId: 'exit', version: 5 });
  });
});
