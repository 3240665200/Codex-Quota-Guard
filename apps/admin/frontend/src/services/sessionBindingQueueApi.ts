import { request } from './api';

export interface SessionBindingQueueState {
  officialAccountId: string;
  quarantined: boolean;
  reason: string | null;
  activeAttemptId: string | null;
  queueState: 'IDLE' | 'SETTLING' | 'RETRYING' | 'PREPARING' | 'ACTIVE';
  retryAt: string | null;
}

function validate(value: unknown, accountId: string): SessionBindingQueueState {
  if (!value || typeof value !== 'object') throw new Error('Invalid binding queue metadata');
  const data = value as Record<string, unknown>;
  if (data.officialAccountId !== accountId || typeof data.quarantined !== 'boolean'
    || (data.activeAttemptId !== null && (typeof data.activeAttemptId !== 'string' || !data.activeAttemptId))
    || (data.reason !== null && typeof data.reason !== 'string')
    || !['IDLE', 'SETTLING', 'RETRYING', 'PREPARING', 'ACTIVE'].includes(String(data.queueState))
    || (data.retryAt !== null && (typeof data.retryAt !== 'string' || !Number.isFinite(Date.parse(data.retryAt))))) throw new Error('Invalid binding queue metadata');
  return { officialAccountId: accountId, quarantined: data.quarantined, reason: data.reason as string | null,
    activeAttemptId: data.activeAttemptId as string | null, queueState: data.queueState as SessionBindingQueueState['queueState'],
    retryAt: data.retryAt as string | null };
}
export async function getSessionBindingQueue(accountId: string): Promise<SessionBindingQueueState> {
  return validate(await request(`/api/v1/admin/session-bindings/accounts/${encodeURIComponent(accountId)}`,
    { cache: 'no-store' }), accountId);
}
export async function recoverSessionBindingQueue(accountId: string): Promise<SessionBindingQueueState> {
  return validate(await request(`/api/v1/admin/session-bindings/accounts/${encodeURIComponent(accountId)}/recover`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ acknowledgeLateLoginRisk: true }),
  }), accountId);
}
