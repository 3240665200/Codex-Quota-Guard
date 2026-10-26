import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './api';
import { getRestrictionManagement, saveRestrictionSettings, saveStaleBindingSettings } from './restrictionsApi';

vi.mock('./api', () => ({ request: vi.fn() }));
const response = { policy: { personalQuotaLogoutEnabled: false, unboundCleanupEnabled: false,
  unboundCheckIntervalMinutes: 90, personalCheckIntervalMinutes: 1, quotaCheckIntervalSeconds: 60, version: 0, updatedAt: '2026-11-01T00:00:00Z' }, accounts: [], executions: [] };
describe('限制管理 API', () => {
  beforeEach(() => { vi.mocked(request).mockReset(); vi.mocked(request).mockResolvedValue(response); });
  it('reads the fixed administrator route without starting a logout', async () => {
    expect(await getRestrictionManagement()).toEqual(response);
    expect(request).toHaveBeenCalledExactlyOnceWith('/api/v1/admin/restrictions', { cache: 'no-store' });
  });
  it('validates stale-binding status and sends only the independent switch and policy version', async () => {
    const staleBindings = { enabled: true, intervalMinutes: 1, lastCheckedAt: null, nextCheckAt: null, removedCount: 0, lastErrorCode: null };
    vi.mocked(request).mockResolvedValue({ ...response, staleBindings });
    await saveStaleBindingSettings(false, 2);
    expect(request).toHaveBeenCalledWith('/api/v1/admin/restrictions/stale-bindings', {
      method: 'PUT', cache: 'no-store', body: JSON.stringify({ enabled: false, version: 2 }),
    }, { retryAfterRefresh: false });
    vi.mocked(request).mockResolvedValue({ ...response, staleBindings: { ...staleBindings, intervalMinutes: 0 } });
    await expect(getRestrictionManagement()).rejects.toThrow('失效会话绑定清理响应不完整');
  });
  it('sends only policy fields and does not automatically replay a configuration write', async () => {
    await saveRestrictionSettings({ ...response.policy, extra: 'must-not-be-sent' } as never);
    const [url, init, options] = vi.mocked(request).mock.calls[0];
    expect(url).toBe('/api/v1/admin/restrictions');
    expect(init).toMatchObject({ method: 'PUT', cache: 'no-store' });
    expect(JSON.parse(init!.body as string)).toEqual({ personalQuotaLogoutEnabled: false, unboundCleanupEnabled: false,
      unboundCheckIntervalMinutes: 90, personalCheckIntervalMinutes: 1, version: 0 });
    expect(options).toEqual({ retryAfterRefresh: false });
  });
  it.each([29, 1441, 90.5, NaN])('rejects an invalid interval %s before sending it', async (interval) => {
    await expect(saveRestrictionSettings({ ...response.policy, unboundCheckIntervalMinutes: interval })).rejects.toThrow();
    expect(request).not.toHaveBeenCalled();
  });
  it.each([
    { ...response, policy: { ...response.policy, unboundCleanupEnabled: null } },
    { ...response, accounts: [{}] },
    { ...response, executions: [{ id: 'missing-status' }] },
    { ...response, policy: { ...response.policy, updatedAt: 'invalid' } },
  ])('rejects incomplete responses instead of inventing disabled rules', async (invalid) => {
    vi.mocked(request).mockResolvedValue(invalid);
    await expect(getRestrictionManagement()).rejects.toThrow('限制管理响应不完整');
  });
});
