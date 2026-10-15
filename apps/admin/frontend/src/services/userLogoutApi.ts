import { request } from './api';
import type { SessionLogoutDevice, SessionLogoutExclusionReason } from './sessionLogoutApi';

export type UserLogoutStatus = 'EXECUTING' | 'ACCEPTED' | 'PARTIAL' | 'FAILED' | 'UNKNOWN';
export type UserLogoutAccountStatus = UserLogoutStatus | 'RESOLVED_UNKNOWN' | 'NOT_ATTEMPTED';
export interface UserLogoutAccountPreview {
  officialAccountId: string;
  displayName: string;
  planId: string | null;
  status: 'READY' | 'NOT_ATTEMPTED';
  sessionIds: string[];
  targets: SessionLogoutDevice[];
  excluded: { session: SessionLogoutDevice; reason: SessionLogoutExclusionReason }[];
  errorCode: string | null;
}
export interface UserLogoutPreview {
  planId: string;
  userId: string;
  scope: 'ALL_ACCOUNTS';
  expiresAt: string;
  accounts: UserLogoutAccountPreview[];
  warnings: string[];
}
export interface UserLogoutResult {
  planId: string;
  userId: string;
  scope: 'ALL_ACCOUNTS';
  status: UserLogoutStatus;
  systemStatus: 'REVOKED' | 'NOT_ATTEMPTED';
  accounts: {
    officialAccountId: string;
    displayName: string;
    planId: string | null;
    status: UserLogoutAccountStatus;
    requiresReauthorization: boolean;
    results: { sessionId: string | null; status: 'ACCEPTED' | 'FAILED' | 'UNKNOWN' | 'NOT_ATTEMPTED'; errorCode: string | null }[];
    errorCode: string | null;
  }[];
  message: string;
}

const statuses = ['EXECUTING', 'ACCEPTED', 'PARTIAL', 'FAILED', 'UNKNOWN'];
const accountStatuses = [...statuses, 'RESOLVED_UNKNOWN', 'NOT_ATTEMPTED'];
const exclusionReasons = ['MISSING_SESSION_ID', 'DUPLICATE_SESSION_ID', 'DUPLICATE_ROW_ID', 'ACTIVE_BINDING',
  'TRUSTED_DEVICE', 'TRUSTED_DEVICE_UNKNOWN', 'CURRENT_SESSION', 'CURRENT_SESSION_UNKNOWN'];
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const id = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value.length <= 512;
const nullableString = (value: unknown) => value === null || typeof value === 'string';
const errorCode = (value: unknown) => value === null || typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(value);
const unique = (values: unknown[]) => new Set(values).size === values.length;
const invalid = () => new Error('Invalid user logout response');
const base = (userId: string) => `/api/v1/admin/users/${encodeURIComponent(userId)}/force-logout-all`;
const device = (value: unknown): value is SessionLogoutDevice => record(value)
  && ['rowId', 'sessionId', 'displayName', 'platform'].every((key) => nullableString(value[key]))
  && (value.current === null || typeof value.current === 'boolean')
  && (value.trustedDevice === null || typeof value.trustedDevice === 'boolean');
const accountBase = (value: unknown): value is Record<string, unknown> => record(value)
  && id(value.officialAccountId) && typeof value.displayName === 'string'
  && (value.planId === null || id(value.planId)) && errorCode(value.errorCode);

function validatePreview(value: unknown, userId: string): UserLogoutPreview {
  if (!record(value) || !id(value.planId) || value.userId !== userId || value.scope !== 'ALL_ACCOUNTS'
    || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
    || !Array.isArray(value.warnings) || !value.warnings.every((item) => typeof item === 'string')
    || !Array.isArray(value.accounts) || !value.accounts.every((account) => {
      if (!accountBase(account) || !['READY', 'NOT_ATTEMPTED'].includes(account.status as string)
        || !Array.isArray(account.sessionIds) || !account.sessionIds.length || account.status === 'READY' && account.sessionIds.length > 100
        || !account.sessionIds.every(id) || !unique(account.sessionIds)
        || !Array.isArray(account.targets) || !account.targets.every(device)
        || !Array.isArray(account.excluded) || !account.excluded.every((item) => record(item)
          && device(item.session) && exclusionReasons.includes(item.reason as string))) return false;
      if (account.status !== 'READY') return true;
      const returned = [...account.targets, ...account.excluded.map((item) => item.session)] as SessionLogoutDevice[];
      const expected = new Set(account.sessionIds);
      return id(account.planId) && returned.length === expected.size
        && unique(returned.map((item) => item.sessionId))
        && returned.every((item) => item.sessionId !== null && expected.has(item.sessionId))
        && account.targets.every((item: SessionLogoutDevice) => id(item.rowId) && item.current === false && item.trustedDevice !== null);
    }) || !unique(value.accounts.map((item) => item.officialAccountId))) throw invalid();
  return value as unknown as UserLogoutPreview;
}

function validateResult(value: unknown, userId: string, planId?: string): UserLogoutResult {
  if (!record(value) || !id(value.planId) || planId !== undefined && value.planId !== planId
    || value.userId !== userId || value.scope !== 'ALL_ACCOUNTS' || !statuses.includes(value.status as string)
    || !['REVOKED', 'NOT_ATTEMPTED'].includes(value.systemStatus as string) || typeof value.message !== 'string'
    || !Array.isArray(value.accounts) || !value.accounts.every((account) => accountBase(account)
      && accountStatuses.includes(account.status as string) && typeof account.requiresReauthorization === 'boolean'
      && Array.isArray(account.results) && account.results.every((item) => record(item)
        && (item.sessionId === null || id(item.sessionId))
        && ['ACCEPTED', 'FAILED', 'UNKNOWN', 'NOT_ATTEMPTED'].includes(item.status as string) && errorCode(item.errorCode)))
    || !unique(value.accounts.map((item) => item.officialAccountId))) throw invalid();
  return value as unknown as UserLogoutResult;
}

export async function previewUserLogoutAll(userId: string): Promise<UserLogoutPreview> {
  return validatePreview(await request<unknown>(`${base(userId)}/preview`,
    { method: 'POST', cache: 'no-store' }, { retryAfterRefresh: false }), userId);
}
export async function confirmUserLogoutAll(userId: string, planId: string): Promise<UserLogoutResult> {
  return validateResult(await request<unknown>(`${base(userId)}/confirm`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify({ planId, acknowledgeLogoutRisk: true }),
  }, { retryAfterRefresh: false }), userId, planId);
}
export async function getUserLogoutAllResult(userId: string, planId: string): Promise<UserLogoutResult> {
  return validateResult(await request<unknown>(`${base(userId)}/${encodeURIComponent(planId)}`,
    { cache: 'no-store' }, { retryAfterRefresh: false }), userId, planId);
}
export async function getUserLogoutAllCurrent(userId: string): Promise<{ operation: UserLogoutResult | null }> {
  const value = await request<unknown>(`${base(userId)}/current`, { cache: 'no-store' }, { retryAfterRefresh: false });
  if (!record(value)) throw invalid();
  return { operation: value.operation === null ? null : validateResult(value.operation, userId) };
}
