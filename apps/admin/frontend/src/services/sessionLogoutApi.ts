import { request } from './api';

export type SessionLogoutMode = 'ALL' | 'UNBOUND';
export type SessionLogoutExclusionReason = 'MISSING_SESSION_ID' | 'DUPLICATE_SESSION_ID' | 'DUPLICATE_ROW_ID'
  | 'ACTIVE_BINDING' | 'TRUSTED_DEVICE' | 'TRUSTED_DEVICE_UNKNOWN' | 'CURRENT_SESSION' | 'CURRENT_SESSION_UNKNOWN';
export interface SessionLogoutDevice {
  rowId: string | null;
  sessionId: string | null;
  displayName: string | null;
  platform: string | null;
  trustedDevice: boolean | null;
  current: boolean | null;
}
export interface SessionLogoutPreview {
  planId: string;
  officialAccountId: string;
  mode: SessionLogoutMode;
  expiresAt: string;
  targets: SessionLogoutDevice[];
  excluded: { session: SessionLogoutDevice; reason: SessionLogoutExclusionReason }[];
  warnings: string[];
}
export type SessionLogoutStatus = 'ACCEPTED' | 'PARTIAL' | 'FAILED' | 'UNKNOWN' | 'RESOLVED_UNKNOWN';
export interface SessionLogoutResult {
  planId: string;
  mode: SessionLogoutMode;
  status: SessionLogoutStatus;
  requiresReauthorization: boolean;
  results: { sessionId: string | null; status: 'ACCEPTED' | 'FAILED' | 'UNKNOWN' | 'NOT_ATTEMPTED'; errorCode: string | null }[];
  message: string;
}
const reasons: readonly string[] = ['MISSING_SESSION_ID', 'DUPLICATE_SESSION_ID', 'DUPLICATE_ROW_ID', 'ACTIVE_BINDING',
  'TRUSTED_DEVICE', 'TRUSTED_DEVICE_UNKNOWN', 'CURRENT_SESSION', 'CURRENT_SESSION_UNKNOWN'];
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const nullableString = (value: unknown) => value === null || typeof value === 'string';
const nullableBoolean = (value: unknown) => value === null || typeof value === 'boolean';
const device = (value: unknown): value is SessionLogoutDevice => record(value)
  && ['rowId', 'sessionId', 'displayName', 'platform'].every((key) => nullableString(value[key]))
  && nullableBoolean(value.trustedDevice) && nullableBoolean(value.current);
const invalid = () => new Error('Invalid official session logout response');
const base = (id: string) => `/api/v1/admin/official-accounts/${encodeURIComponent(id)}/session-logout`;

export async function previewSessionLogout(accountId: string, mode: SessionLogoutMode): Promise<SessionLogoutPreview> {
  const value = await request<unknown>(`${base(accountId)}/preview`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ mode }),
  }, { retryAfterRefresh: false });
  if (!record(value) || typeof value.planId !== 'string' || !value.planId.trim()
    || value.officialAccountId !== accountId || value.mode !== mode
    || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
    || !Array.isArray(value.targets) || !value.targets.every(device)
    || !Array.isArray(value.excluded) || !value.excluded.every((item) => record(item)
      && device(item.session) && typeof item.reason === 'string' && reasons.includes(item.reason))
    || !Array.isArray(value.warnings) || !value.warnings.every((item) => typeof item === 'string')) throw invalid();
  return value as unknown as SessionLogoutPreview;
}

export async function confirmSessionLogout(accountId: string, planId: string, mode: SessionLogoutMode): Promise<SessionLogoutResult> {
  const value = await request<unknown>(`${base(accountId)}/confirm`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ planId, acknowledgeLogoutRisk: true }),
  }, { retryAfterRefresh: false });
  if (!record(value) || value.planId !== planId || value.mode !== mode
    || !['ACCEPTED', 'PARTIAL', 'FAILED', 'UNKNOWN'].includes(value.status as string)
    || typeof value.requiresReauthorization !== 'boolean' || typeof value.message !== 'string'
    || !Array.isArray(value.results) || !value.results.every((item) => record(item) && nullableString(item.sessionId)
      && ['ACCEPTED', 'FAILED', 'UNKNOWN', 'NOT_ATTEMPTED'].includes(item.status as string)
      && nullableString(item.errorCode))) throw invalid();
  return value as unknown as SessionLogoutResult;
}
