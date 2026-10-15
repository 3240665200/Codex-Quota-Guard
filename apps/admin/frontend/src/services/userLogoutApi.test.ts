import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './api';
import { confirmUserLogoutAll, getUserLogoutAllCurrent, getUserLogoutAllResult, previewUserLogoutAll } from './userLogoutApi';

vi.mock('./api', () => ({ request: vi.fn() }));
const target = { rowId: 'row-1', sessionId: 'session-1', displayName: 'Synthetic device', platform: 'Windows', trustedDevice: false, current: false };
const preview = () => ({
  planId: 'aggregate-1', userId: 'user/1', scope: 'ALL_ACCOUNTS', expiresAt: '2099-01-01T00:00:00Z',
  accounts: [{ officialAccountId: 'account-1', displayName: 'Synthetic account', planId: 'child-1', status: 'READY',
    sessionIds: ['session-1'], targets: [target], excluded: [], errorCode: null }], warnings: [],
});
const result = () => ({
  planId: 'aggregate-1', userId: 'user/1', scope: 'ALL_ACCOUNTS', status: 'PARTIAL', systemStatus: 'REVOKED',
  accounts: [{ officialAccountId: 'account-1', displayName: 'Synthetic account', planId: 'child-1', status: 'PARTIAL',
    requiresReauthorization: false, results: [{ sessionId: 'session-1', status: 'FAILED', errorCode: 'OFFICIAL_LOGOUT_REJECTED' }], errorCode: null }],
  message: 'Synthetic safe result',
});

describe('user-scoped logout transport', () => {
  beforeEach(() => vi.clearAllMocks());

  it('requests a backend-scoped preview without posting arbitrary session ids', async () => {
    vi.mocked(request).mockResolvedValue(preview());
    await expect(previewUserLogoutAll('user/1')).resolves.toEqual(preview());
    expect(request).toHaveBeenCalledExactlyOnceWith('/api/v1/admin/users/user%2F1/force-logout-all/preview',
      { method: 'POST', cache: 'no-store' }, { retryAfterRefresh: false });
  });

  it('confirms only the expected plan once, with no authorization-refresh replay', async () => {
    vi.mocked(request).mockResolvedValue(result());
    await expect(confirmUserLogoutAll('user/1', 'aggregate-1')).resolves.toEqual(result());
    expect(request).toHaveBeenCalledExactlyOnceWith('/api/v1/admin/users/user%2F1/force-logout-all/confirm',
      { method: 'POST', cache: 'no-store', body: JSON.stringify({ planId: 'aggregate-1', acknowledgeLogoutRisk: true }) },
      { retryAfterRefresh: false });
  });

  it('reads a result and the latest operation without replaying logout', async () => {
    vi.mocked(request).mockResolvedValueOnce(result()).mockResolvedValueOnce({ operation: result() });
    await expect(getUserLogoutAllResult('user/1', 'aggregate-1')).resolves.toEqual(result());
    await expect(getUserLogoutAllCurrent('user/1')).resolves.toEqual({ operation: result() });
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/admin/users/user%2F1/force-logout-all/aggregate-1',
      { cache: 'no-store' }, { retryAfterRefresh: false });
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/admin/users/user%2F1/force-logout-all/current',
      { cache: 'no-store' }, { retryAfterRefresh: false });
  });

  it.each(['user', 'scope', 'account', 'session', 'unsafeTarget', 'expiry'])('rejects a mismatched or unsafe preview: %s', async (kind) => {
    const value = preview();
    if (kind === 'user') value.userId = 'another-user';
    if (kind === 'scope') value.scope = 'ALL';
    if (kind === 'account') value.accounts.push(value.accounts[0]);
    if (kind === 'session') value.accounts[0].targets = [{ ...target, sessionId: 'not-requested' }];
    if (kind === 'unsafeTarget') value.accounts[0].targets = [{ ...target, current: true }];
    if (kind === 'expiry') value.expiresAt = 'not-a-date';
    vi.mocked(request).mockResolvedValue(value);
    await expect(previewUserLogoutAll('user/1')).rejects.toThrow('Invalid user logout response');
  });

  it.each(['user', 'plan', 'status', 'system', 'reauthorization'])('rejects malformed or wrong-scope result: %s', async (kind) => {
    const value: any = result();
    if (kind === 'user') value.userId = 'another-user';
    if (kind === 'plan') value.planId = 'another-plan';
    if (kind === 'status') value.accounts[0].results[0].status = 'DONE';
    if (kind === 'system') value.systemStatus = 'UNKNOWN_SUCCESS';
    if (kind === 'reauthorization') delete value.accounts[0].requiresReauthorization;
    vi.mocked(request).mockResolvedValue(value);
    await expect(confirmUserLogoutAll('user/1', 'aggregate-1')).rejects.toThrow('Invalid user logout response');
  });

  it('supports no current operation and no official bindings without inventing targets', async () => {
    vi.mocked(request).mockResolvedValueOnce({ operation: null }).mockResolvedValueOnce({ ...preview(), accounts: [] });
    await expect(getUserLogoutAllCurrent('user/1')).resolves.toEqual({ operation: null });
    expect((await previewUserLogoutAll('user/1')).accounts).toEqual([]);
  });

  it('retains an oversized excluded account as not attempted instead of hiding the whole preview', async () => {
    const value = preview();
    value.accounts[0] = { ...value.accounts[0], status: 'NOT_ATTEMPTED',
      sessionIds: Array.from({ length: 101 }, (_, index) => `session-${index}`), targets: [], excluded: [] };
    vi.mocked(request).mockResolvedValue(value);
    expect((await previewUserLogoutAll('user/1')).accounts[0].sessionIds).toHaveLength(101);
  });
});
