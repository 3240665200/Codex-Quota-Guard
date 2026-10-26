import { request } from './api';

export interface RestrictionSettings {
  personalQuotaLogoutEnabled: boolean;
  unboundCleanupEnabled: boolean;
  unboundCheckIntervalMinutes: number;
  personalCheckIntervalMinutes: number;
  version: number;
}
export interface RestrictionPolicy extends RestrictionSettings {
  quotaCheckIntervalSeconds: number;
  updatedAt: string;
}
export interface RestrictionAccountStatus {
  officialAccountId: string; accountLabel: string;
  quotaCheckedAt: string | null; nextQuotaAt: string | null;
  unboundCheckedAt: string | null; nextUnboundAt: string | null;
  lastErrorCode: string | null; pendingPlanId: string | null; operationStatus: string | null;
}
export interface RestrictionExecution {
  id: string; officialAccountId: string | null; accountLabel: string;
  rule: 'PERSONAL_QUOTA' | 'UNBOUND'; checkedAt: string; status: string;
  targetedCount: number; excludedCount: number; acceptedCount: number; unknownCount: number;
  errorCode: string | null;
}
export interface RestrictionManagement {
  policy: RestrictionPolicy;
  accounts: RestrictionAccountStatus[];
  executions: RestrictionExecution[];
  staleBindings?: {
    enabled: boolean; intervalMinutes: number; lastCheckedAt: string | null;
    nextCheckAt: string | null; removedCount: number; lastErrorCode: string | null;
  };
}

const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const instant = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value));
const nullableInstant = (value: unknown) => value === null || instant(value);
const nullableString = (value: unknown) => value === null || typeof value === 'string';
function validate(value: unknown): RestrictionManagement {
  if (!record(value) || !record(value.policy)) throw new Error('限制管理响应不完整，请刷新后重试。');
  const p = value.policy;
  const s = value.staleBindings;
  if (s !== undefined && (!record(s) || typeof s.enabled !== 'boolean' || s.intervalMinutes !== 1
    || !nullableInstant(s.lastCheckedAt) || !nullableInstant(s.nextCheckAt) || !count(s.removedCount) || !nullableString(s.lastErrorCode)))
    throw new Error('失效会话绑定清理响应不完整，请刷新后重试。');
  if (typeof p.personalQuotaLogoutEnabled !== 'boolean' || typeof p.unboundCleanupEnabled !== 'boolean'
    || !count(p.unboundCheckIntervalMinutes) || p.unboundCheckIntervalMinutes < 30 || p.unboundCheckIntervalMinutes > 1440
    || !count(p.personalCheckIntervalMinutes) || p.personalCheckIntervalMinutes < 1 || p.personalCheckIntervalMinutes > 1440
    || !count(p.version) || !count(p.quotaCheckIntervalSeconds) || p.quotaCheckIntervalSeconds !== p.personalCheckIntervalMinutes * 60 || !instant(p.updatedAt)
    || !Array.isArray(value.accounts) || !value.accounts.every((row) => record(row)
      && typeof row.officialAccountId === 'string' && typeof row.accountLabel === 'string'
      && ['quotaCheckedAt', 'nextQuotaAt', 'unboundCheckedAt', 'nextUnboundAt'].every((key) => nullableInstant(row[key]))
      && ['lastErrorCode', 'pendingPlanId', 'operationStatus'].every((key) => nullableString(row[key])))
    || !Array.isArray(value.executions) || !value.executions.every((row) => record(row)
      && typeof row.id === 'string' && nullableString(row.officialAccountId) && typeof row.accountLabel === 'string'
      && ['PERSONAL_QUOTA', 'UNBOUND'].includes(row.rule as string) && instant(row.checkedAt) && typeof row.status === 'string'
      && ['targetedCount', 'excludedCount', 'acceptedCount', 'unknownCount'].every((key) => count(row[key])) && nullableString(row.errorCode)))
    throw new Error('限制管理响应不完整，请刷新后重试。');
  return value as unknown as RestrictionManagement;
}

export async function getRestrictionManagement(): Promise<RestrictionManagement> {
  return validate(await request<unknown>('/api/v1/admin/restrictions', { cache: 'no-store' }));
}
export async function saveRestrictionSettings(settings: RestrictionSettings): Promise<RestrictionManagement> {
  if (typeof settings.personalQuotaLogoutEnabled !== 'boolean' || typeof settings.unboundCleanupEnabled !== 'boolean'
    || !count(settings.version) || !count(settings.unboundCheckIntervalMinutes)
    || settings.unboundCheckIntervalMinutes < 30 || settings.unboundCheckIntervalMinutes > 1440
    || !count(settings.personalCheckIntervalMinutes) || settings.personalCheckIntervalMinutes < 1 || settings.personalCheckIntervalMinutes > 1440)
    throw new Error('个人检查间隔须为 1～1440 分钟，未绑定会话检查间隔须为 30～1440 分钟。');
  return validate(await request<unknown>('/api/v1/admin/restrictions', {
    method: 'PUT', cache: 'no-store', body: JSON.stringify({
      personalQuotaLogoutEnabled: settings.personalQuotaLogoutEnabled,
      unboundCleanupEnabled: settings.unboundCleanupEnabled,
      unboundCheckIntervalMinutes: settings.unboundCheckIntervalMinutes,
      personalCheckIntervalMinutes: settings.personalCheckIntervalMinutes,
      version: settings.version,
    }),
  }, { retryAfterRefresh: false }));
}

export async function saveStaleBindingSettings(enabled: boolean, version: number): Promise<RestrictionManagement> {
  if (typeof enabled !== 'boolean' || !count(version)) throw new Error('失效会话绑定清理设置无效。');
  return validate(await request<unknown>('/api/v1/admin/restrictions/stale-bindings', {
    method: 'PUT', cache: 'no-store', body: JSON.stringify({ enabled, version }),
  }, { retryAfterRefresh: false }));
}
