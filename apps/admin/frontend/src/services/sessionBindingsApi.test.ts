import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession } from './api';
import { listSessionBindings, releaseSessionBinding } from './sessionBindingsApi';

describe('管理员设备会话绑定 API', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.clearAllMocks();
    clearSession();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '[]' });
  });

  it('reads safe binding results for exactly the selected official account', async () => {
    await listSessionBindings('account/id');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/admin/session-bindings?officialAccountId=account%2Fid');
    expect(fetchMock.mock.calls[0][1].method ?? 'GET').toBe('GET');
    expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
  });

  it('releases exactly one binding with account scope and explicit acknowledgement without caching', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, text: async () => '' });
    await releaseSessionBinding('binding/id', 'account/id');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/admin/session-bindings/binding%2Fid/release');
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST', cache: 'no-store' });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      officialAccountId: 'account/id', acknowledgeUnbind: true,
    });
  });
});
