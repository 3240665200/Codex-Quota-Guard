import { beforeEach, describe, expect, it, vi } from 'vitest';
import { previewSessionLogout, confirmSessionLogout } from './sessionLogoutApi';
import { request } from './api';

vi.mock('./api', () => ({ request: vi.fn() }));
const call = vi.mocked(request);
const device = { rowId: 'row-1', sessionId: 'session-1', displayName: '办公电脑', platform: 'web',
  trustedDevice: true, current: false };
const preview = { planId: 'plan-1', officialAccountId: 'account/id', mode: 'SELECTED',
  expiresAt: '2026-10-15T10:02:00Z', targets: [device], excluded: [], warnings: ['SELECTED_SESSIONS_ONLY'] };

describe('选中会话登出 API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends only explicitly selected session IDs to an encoded account without retries or caching', async () => {
    call.mockResolvedValue(preview);
    expect(await previewSessionLogout('account/id', 'SELECTED', ['session-1'])).toEqual(preview);
    expect(call).toHaveBeenCalledExactlyOnceWith('/api/v1/admin/official-accounts/account%2Fid/session-logout/preview',
      { method: 'POST', cache: 'no-store', body: JSON.stringify({ mode: 'SELECTED', sessionIds: ['session-1'] }) },
      { retryAfterRefresh: false });
  });

  it.each([[], [''], ['  '], ['s'.repeat(513)], ['session-1', 'session-1'], Array.from({ length: 101 }, (_, i) => `s-${i}`)]
    .map((sessionIds) => ({ sessionIds })))(
    'rejects an invalid selected scope before sending anything', async ({ sessionIds }) => {
      await expect(previewSessionLogout('account/id', 'SELECTED', sessionIds)).rejects.toThrow();
      expect(call).not.toHaveBeenCalled();
    });

  it.each([
    { ...preview, targets: [{ ...device, sessionId: 'unselected' }] },
    { ...preview, targets: [] },
    { ...preview, targets: [device, device] },
    { ...preview, targets: [{ ...device, current: true }] },
    { ...preview, targets: [{ ...device, trustedDevice: null }] },
    { ...preview, excluded: [{ session: { ...device, sessionId: 'unselected' }, reason: 'CURRENT_SESSION' }] },
    { ...preview, excluded: [{ session: device, reason: 'CURRENT_SESSION' }] },
    { ...preview, targets: [], excluded: [{ session: device, reason: 'CURRENT_SESSION' },
      { session: device, reason: 'CURRENT_SESSION' }] },
  ])('rejects a preview that drifts from the selected scope', async (value) => {
    call.mockResolvedValue(value);
    await expect(previewSessionLogout('account/id', 'SELECTED', ['session-1'])).rejects.toThrow();
    expect(call).toHaveBeenCalledOnce();
  });

  it('accepts exclusions for selected current sessions without sending a revoke', async () => {
    const value = { ...preview, targets: [], excluded: [{ session: { ...device, current: true }, reason: 'CURRENT_SESSION' }] };
    call.mockResolvedValue(value);
    expect(await previewSessionLogout('account/id', 'SELECTED', ['session-1'])).toEqual(value);
  });

  it.each(['ALL', 'UNBOUND'] as const)('rejects a selected scope attached to %s', async (mode) => {
    await expect(previewSessionLogout('account/id', mode, ['session-1'])).rejects.toThrow();
    expect(call).not.toHaveBeenCalled();
  });

  it('validates selected execution results through the existing acknowledged plan flow', async () => {
    const result = { planId: 'plan-1', mode: 'SELECTED', status: 'UNKNOWN', requiresReauthorization: false,
      results: [{ sessionId: 'session-1', status: 'UNKNOWN', errorCode: null }], message: '' };
    call.mockResolvedValue(result);
    expect(await confirmSessionLogout('account/id', 'plan-1', 'SELECTED')).toEqual(result);
    expect(call).toHaveBeenCalledOnce();
  });
});
