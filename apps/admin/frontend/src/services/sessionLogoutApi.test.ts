import { beforeEach, describe, expect, it, vi } from 'vitest';
import { acknowledgeSessionLogoutUnknown, confirmSessionLogout, getSessionLogoutCurrent,
  previewSessionLogout } from './sessionLogoutApi';
import { request } from './api';

vi.mock('./api', () => ({ request: vi.fn() }));
const call = vi.mocked(request);
const session = { rowId: 'row-1', sessionId: 'session-1', displayName: '办公电脑', platform: 'web',
  trustedDevice: false, current: false };
const preview = { planId: 'plan-1', officialAccountId: 'account/id', mode: 'UNBOUND',
  expiresAt: '2026-10-15T10:02:00Z', targets: [session], excluded: [], warnings: [] };
const result = { planId: 'plan-1', mode: 'UNBOUND', status: 'PARTIAL', requiresReauthorization: false,
  results: [{ sessionId: 'session-1', status: 'UNKNOWN', errorCode: 'OFFICIAL_UNAVAILABLE' }], message: 'safe' };

describe('官方会话登出 API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('previews exactly one encoded account and never permits cached plans', async () => {
    call.mockResolvedValue(preview);
    expect(await previewSessionLogout('account/id', 'UNBOUND')).toEqual(preview);
    expect(call).toHaveBeenCalledWith('/api/v1/admin/official-accounts/account%2Fid/session-logout/preview',
      { method: 'POST', cache: 'no-store', body: JSON.stringify({ mode: 'UNBOUND' }) },
      { retryAfterRefresh: false });
  });

  it('confirms only the server plan with explicit acknowledgement and no automatic resend', async () => {
    call.mockResolvedValue(result);
    expect(await confirmSessionLogout('account/id', 'plan-1', 'UNBOUND')).toEqual(result);
    expect(call).toHaveBeenCalledWith('/api/v1/admin/official-accounts/account%2Fid/session-logout/confirm',
      { method: 'POST', cache: 'no-store', body: JSON.stringify({ planId: 'plan-1', acknowledgeLogoutRisk: true }) },
      { retryAfterRefresh: false });
    expect(call).toHaveBeenCalledTimes(1);
  });

  it.each([
    { ...preview, officialAccountId: 'other-account' }, { ...preview, mode: 'ALL' },
    { ...preview, expiresAt: 'invalid' }, { ...preview, planId: '' },
    { ...preview, targets: [{ ...session, current: 'false' }] },
    { ...preview, excluded: [{ session, reason: 'UNRECOGNIZED' }] },
  ])('rejects an unsafe or mismatched preview response', async (value) => {
    call.mockResolvedValue(value);
    await expect(previewSessionLogout('account/id', 'UNBOUND')).rejects.toThrow();
  });

  it.each([
    { ...result, planId: 'other-plan' }, { ...result, mode: 'ALL' },
    { ...result, status: 'SUCCESS' }, { ...result, requiresReauthorization: null },
    { ...result, results: [{ sessionId: 'session-1', status: 'DONE', errorCode: null }] },
  ])('rejects an unverified execution result without a retry', async (value) => {
    call.mockResolvedValue(value);
    await expect(confirmSessionLogout('account/id', 'plan-1', 'UNBOUND')).rejects.toThrow();
    expect(call).toHaveBeenCalledTimes(1);
  });

  it('reads the durable current operation without invoking an official request', async () => {
    const operation = { ...result, status: 'UNKNOWN', canAcknowledge: true, canAcknowledgeAt: null };
    call.mockResolvedValue({ operation });
    expect(await getSessionLogoutCurrent('account/id')).toEqual({ operation });
    expect(call).toHaveBeenCalledWith('/api/v1/admin/official-accounts/account%2Fid/session-logout/current',
      { cache: 'no-store' }, { retryAfterRefresh: false });
  });

  it('acknowledges an uncertain outcome only with the explicit manual-verification body', async () => {
    call.mockResolvedValue({ ...result, status: 'RESOLVED_UNKNOWN' });
    expect(await acknowledgeSessionLogoutUnknown('account/id', 'plan/id', 'UNBOUND')).toMatchObject({ status: 'RESOLVED_UNKNOWN' });
    expect(call).toHaveBeenCalledWith('/api/v1/admin/official-accounts/account%2Fid/session-logout/plan%2Fid/acknowledge-unknown',
      { method: 'POST', cache: 'no-store', body: JSON.stringify({ acknowledgeUncertainOutcome: true }) },
      { retryAfterRefresh: false });
  });

  it.each([
    { operation: { ...result, status: 'ACCEPTED', canAcknowledge: true, canAcknowledgeAt: null } },
    { operation: { ...result, status: 'UNKNOWN', canAcknowledge: 'true', canAcknowledgeAt: null } },
    { operation: { ...result, status: 'EXECUTING', canAcknowledge: false, canAcknowledgeAt: 'invalid' } },
  ])('rejects invalid durable lock metadata', async (value) => {
    call.mockResolvedValue(value);
    await expect(getSessionLogoutCurrent('account/id')).rejects.toThrow();
  });
});
