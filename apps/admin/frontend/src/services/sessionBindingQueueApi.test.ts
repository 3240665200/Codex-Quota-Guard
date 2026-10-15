import { beforeEach, expect, it, vi } from 'vitest';
import { getSessionBindingQueue, recoverSessionBindingQueue } from './sessionBindingQueueApi';

const mocks = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('./api', () => mocks);
beforeEach(() => {
  mocks.request.mockReset(); mocks.request.mockResolvedValue({ officialAccountId: 'account/id', quarantined: false,
    activeAttemptId: null, reason: null });
});
it('uses only the selected account route and an explicit acknowledgement body', async () => {
  await getSessionBindingQueue('account/id');
  expect(mocks.request).toHaveBeenLastCalledWith('/api/v1/admin/session-bindings/accounts/account%2Fid', { cache: 'no-store' });
  await recoverSessionBindingQueue('account/id');
  expect(mocks.request).toHaveBeenLastCalledWith('/api/v1/admin/session-bindings/accounts/account%2Fid/recover', {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ acknowledgeLateLoginRisk: true }),
  });
});
it('rejects malformed state and a response for a different official account', async () => {
  mocks.request.mockResolvedValue({ officialAccountId: 'other-account', quarantined: false, reason: null, activeAttemptId: null });
  await expect(getSessionBindingQueue('account/id')).rejects.toThrow();
  mocks.request.mockResolvedValue({ officialAccountId: 'account/id', quarantined: 'false', reason: null, activeAttemptId: null });
  await expect(getSessionBindingQueue('account/id')).rejects.toThrow();
});
