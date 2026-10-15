import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession } from './api';
import { listSessionBindings } from './sessionBindingsApi';

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
});
