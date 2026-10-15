import { request } from './api';

export interface SessionBinding {
  id: string;
  userId: string;
  userDisplayName: string | null;
  userEmail: string | null;
  deviceId: string;
  enrollmentId: string;
  officialAccountId: string;
  sessionId: string;
  status: 'ACTIVE' | 'ENDED' | 'INVALIDATED';
  matchMethod: 'SERIAL_LOGIN_DIFF';
  verificationLevel: 'USER_CONFIRMED_INFERENCE';
  createdAt: string;
  endedAt: string | null;
}

export function listSessionBindings(officialAccountId: string): Promise<SessionBinding[]> {
  const params = new URLSearchParams({ officialAccountId });
  return request(`/api/v1/admin/session-bindings?${params.toString()}`);
}

export function releaseSessionBinding(bindingId: string, officialAccountId: string): Promise<void> {
  return request(`/api/v1/admin/session-bindings/${encodeURIComponent(bindingId)}/release`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ officialAccountId, acknowledgeUnbind: true }),
  }, { retryAfterRefresh: false });
}
