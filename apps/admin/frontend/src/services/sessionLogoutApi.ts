import { request } from './api';

export type SessionLogoutMode = 'ALL' | 'UNBOUND' | 'SELECTED';
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
export type SessionLogoutStatus = 'EXECUTING' | 'ACCEPTED' | 'PARTIAL' | 'FAILED' | 'UNKNOWN' | 'RESOLVED_UNKNOWN';
export interface SessionLogoutResult {
  planId: string;
  mode: SessionLogoutMode;
  status: SessionLogoutStatus;
  requiresReauthorization: boolean;
  results: { sessionId: string | null; status: 'ACCEPTED' | 'FAILED' | 'UNKNOWN' | 'NOT_ATTEMPTED'; errorCode: string | null }[];
  message: string;
}
export interface SessionLogoutOperation extends SessionLogoutResult {
  canAcknowledge: boolean;
  canAcknowledgeAt: string | null;
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
const resultFields = (value: Record<string, unknown>) => typeof value.planId === 'string' && !!value.planId.trim()
  && ['ALL', 'UNBOUND', 'SELECTED'].includes(value.mode as string)
  && typeof value.requiresReauthorization === 'boolean' && typeof value.message === 'string'
  && Array.isArray(value.results) && value.results.every((item) => record(item) && nullableString(item.sessionId)
    && ['ACCEPTED', 'FAILED', 'UNKNOWN', 'NOT_ATTEMPTED'].includes(item.status as string) && nullableString(item.errorCode));
function validateResult(value: unknown, planId: string, mode: SessionLogoutMode, statuses: readonly string[]): SessionLogoutResult {
  if (!record(value) || !resultFields(value) || value.planId !== planId || value.mode !== mode
    || !statuses.includes(value.status as string)) throw invalid();
  return value as unknown as SessionLogoutResult;
}

export async function previewSessionLogout(accountId: string, mode: SessionLogoutMode, sessionIds?: readonly string[]): Promise<SessionLogoutPreview> {
  const selected = mode === 'SELECTED' ? [...(sessionIds ?? [])] : [];
  if (mode === 'SELECTED' && (!Array.isArray(sessionIds) || !selected.length || selected.length > 100
    || selected.some((id) => typeof id !== 'string' || !id.trim() || id.length > 512)
    || new Set(selected).size !== selected.length) || mode !== 'SELECTED' && sessionIds?.length) throw invalid();
  const value = await request<unknown>(`${base(accountId)}/preview`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ mode, ...(mode === 'SELECTED' ? { sessionIds: selected } : {}) }),
  }, { retryAfterRefresh: false });
  if (!record(value) || typeof value.planId !== 'string' || !value.planId.trim()
    || value.officialAccountId !== accountId || value.mode !== mode
    || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
    || !Array.isArray(value.targets) || !value.targets.every(device)
    || !Array.isArray(value.excluded) || !value.excluded.every((item) => record(item)
      && device(item.session) && typeof item.reason === 'string' && reasons.includes(item.reason))
    || !Array.isArray(value.warnings) || !value.warnings.every((item) => typeof item === 'string')) throw invalid();
  const preview = value as unknown as SessionLogoutPreview;
  if (mode === 'SELECTED') {
    const requested = new Set(selected);
    const returned = [...preview.targets, ...preview.excluded.map((item) => item.session)];
    const ids = returned.map((item) => item.sessionId);
    const rowIds = returned.flatMap((item) => item.rowId ? [item.rowId] : []);
    if (ids.length !== selected.length || new Set(ids).size !== ids.length
      || ids.some((id) => id === null || !requested.has(id)) || new Set(rowIds).size !== rowIds.length
      || preview.targets.some((item) => !item.rowId?.trim() || item.current !== false || item.trustedDevice === null)) throw invalid();
  }
  return preview;
}

export async function confirmSessionLogout(accountId: string, planId: string, mode: SessionLogoutMode): Promise<SessionLogoutResult> {
  const value = await request<unknown>(`${base(accountId)}/confirm`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ planId, acknowledgeLogoutRisk: true }),
  }, { retryAfterRefresh: false });
  return validateResult(value, planId, mode, ['EXECUTING', 'ACCEPTED', 'PARTIAL', 'FAILED', 'UNKNOWN']);
}

export async function getSessionLogoutCurrent(accountId: string): Promise<{ operation: SessionLogoutOperation | null }> {
  const value = await request<unknown>(`${base(accountId)}/current`, { cache: 'no-store' }, { retryAfterRefresh: false });
  if (!record(value)) throw invalid();
  if (value.operation === null) return { operation: null };
  const operation = value.operation;
  if (!record(operation) || !resultFields(operation)
    || !['EXECUTING', 'ACCEPTED', 'PARTIAL', 'FAILED', 'UNKNOWN', 'RESOLVED_UNKNOWN'].includes(operation.status as string)
    || typeof operation.canAcknowledge !== 'boolean' || !(operation.canAcknowledgeAt === null
      || typeof operation.canAcknowledgeAt === 'string' && Number.isFinite(Date.parse(operation.canAcknowledgeAt)))) throw invalid();
  return { operation: operation as unknown as SessionLogoutOperation };
}

export async function acknowledgeSessionLogoutUnknown(accountId: string, planId: string, mode: SessionLogoutMode): Promise<SessionLogoutResult> {
  const value = await request<unknown>(`${base(accountId)}/${encodeURIComponent(planId)}/acknowledge-unknown`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ acknowledgeUncertainOutcome: true }),
  }, { retryAfterRefresh: false });
  return validateResult(value, planId, mode, ['RESOLVED_UNKNOWN']);
}
