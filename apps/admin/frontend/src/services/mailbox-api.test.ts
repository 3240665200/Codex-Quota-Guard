import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';

interface MailboxApi {
  listMailboxProviders(): Promise<unknown[]>;
  getOfficialAccountMailbox(id: string): Promise<unknown>;
  saveOfficialAccountMailbox(id: string, body: Record<string, unknown>): Promise<unknown>;
  deleteOfficialAccountMailbox(id: string): Promise<void>;
  testOfficialAccountMailbox(id: string): Promise<unknown>;
}
const mailbox = api as unknown as MailboxApi;

describe('邮箱配置管理 API', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.clearAllMocks();
    api.clearSession();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => JSON.stringify({ configured: false }) });
  });

  it('uses the central provider and encoded account configuration endpoints', async () => {
    expect(mailbox.listMailboxProviders).toBeTypeOf('function');
    await mailbox.listMailboxProviders();
    await mailbox.getOfficialAccountMailbox('account/id');
    await mailbox.saveOfficialAccountMailbox('account/id', { provider: 'CUSTOM', enabled: false });
    await mailbox.testOfficialAccountMailbox('account/id');
    await mailbox.deleteOfficialAccountMailbox('account/id');
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/admin/mail-assistance/providers',
      '/api/v1/admin/official-accounts/account%2Fid/mailbox',
      '/api/v1/admin/official-accounts/account%2Fid/mailbox',
      '/api/v1/admin/official-accounts/account%2Fid/mailbox/test',
      '/api/v1/admin/official-accounts/account%2Fid/mailbox',
    ]);
    expect(fetchMock.mock.calls[2][1].method).toBe('PUT');
    expect(fetchMock.mock.calls[3][1]).toMatchObject({ method: 'POST' });
    expect(fetchMock.mock.calls[3][1].body).toBeUndefined();
    expect(fetchMock.mock.calls[4][1].method).toBe('DELETE');
  });
});
