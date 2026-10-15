import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession } from './api';
import { cancelOfficialBrowserLogin, getOfficialBrowserLogin, startOfficialBrowserLogin } from './officialBrowserLoginApi';

describe('官方网页登录任务 API', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.clearAllMocks(); clearSession(); vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '{}' });
  });

  it('starts a login task with only the saved version and selected proxy', async () => {
    await startOfficialBrowserLogin('account/id', { version: 3, proxyNodeId: 'proxy-1' });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/admin/official-accounts/account%2Fid/browser-login');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ version: 3, proxyNodeId: 'proxy-1' });
  });

  it('reads and cancels the exact login job without credential parameters', async () => {
    await getOfficialBrowserLogin('job/id');
    await cancelOfficialBrowserLogin('job/id');
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/admin/browser-logins/job%2Fid', '/api/v1/admin/browser-logins/job%2Fid/cancel',
    ]);
    expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
    expect(fetchMock.mock.calls[1][1].method).toBe('POST');
    expect(fetchMock.mock.calls[1][1].body).toBeUndefined();
  });
});
