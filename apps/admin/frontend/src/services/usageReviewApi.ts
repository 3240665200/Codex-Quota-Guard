import { request } from './api';

export interface ReviewEvent {
  eventId: string; occurredAt: string; totalTokens: number;
  source: 'TOKEN_USAGE_RECORD' | 'CUMULATIVE_TOKEN_COUNT_FALLBACK';
  model: string | null; reasoningEffort: string | null; serviceTier: string | null;
  inputTokens: number | null; cachedInputTokens: number | null; cacheWriteInputTokens: number | null;
  outputTokens: number | null; reasoningOutputTokens: number | null;
}
export interface ReviewItem {
  id: string; userId: string; displayName: string; email: string; deviceId: string;
  teamId: string | null; officialAccountId: string | null; occurredAt: string; receivedAt: string;
  reason: 'TOO_OLD' | 'FUTURE_TIME' | 'CLIENT_DEFERRED'; status: 'PENDING' | 'ARCHIVED' | 'IMPORTED';
  reviewedAt: string | null; reviewedBy: string | null; reviewNote: string | null; event: ReviewEvent;
}
export interface PendingBill {
  id: string; userId: string | null; displayName: string; email: string; occurredAt: string;
  model: string | null; totalTokens: number; costStatus: string; modeStatus: string;
}
export interface ReviewPage<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface ReviewFilters { userId?: string; from?: string; to?: string }
export interface ReviewBulkJob extends ReviewFilters {
  id: string; requestedBy: string; action: 'ARCHIVE' | 'IMPORT';
  status: 'DRAFT' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED';
  createdAt: string; totalCount: number; excludedCount: number; processedCount: number;
  succeededCount: number; skippedCount: number; failedCount: number; lastErrorCode: string | null;
}
const base = '/api/v1/admin/usage/reviews';
export async function listReviews(params: ReviewFilters & { page: number; size: number; status?: string }, billing = false, reviewed = false) {
  const query = new URLSearchParams({ page: String(params.page), size: String(params.size) });
  if (params.userId) query.set('userId', params.userId);
  if (params.status && !billing) query.set('status', params.status);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  const result = await request<ReviewPage<ReviewItem | PendingBill>>(`${base}${billing ? reviewed ? '/billing-reviewed' : '/billing' : ''}?${query}`, { cache: 'no-store' });
  if (!result || !Array.isArray(result.items) || !Number.isSafeInteger(result.totalElements) || result.totalElements < 0)
    throw new Error('用量核对响应不完整，请刷新后重试。');
  return result;
}
export async function previewReviewJob(action: 'ARCHIVE' | 'IMPORT', filters: ReviewFilters) {
  return request<ReviewBulkJob>(`${base}/bulk-jobs`, { method: 'POST', body: JSON.stringify({ action, ...filters }), cache: 'no-store' }, { retryAfterRefresh: false });
}
export async function startReviewJob(id: string, note: string) {
  return request<ReviewBulkJob>(`${base}/bulk-jobs/${encodeURIComponent(id)}/start`, {
    method: 'POST', body: JSON.stringify({ note }), cache: 'no-store',
  }, { retryAfterRefresh: false });
}
export async function listReviewJobs() {
  return request<ReviewBulkJob[]>(`${base}/bulk-jobs`, { cache: 'no-store' });
}
export async function reviewUsage(ids: string[], action: 'ARCHIVE' | 'IMPORT', note: string) {
  return request<{ updated: number }>(`${base}/actions`, {
    method: 'POST', body: JSON.stringify({ ids, action, note }), cache: 'no-store',
  }, { retryAfterRefresh: false });
}
export async function confirmBillingMode(eventIds: string[], serviceTier: 'standard' | 'fast') {
  return request<{ updated: number }>('/api/v1/admin/usage/billing/confirm-mode', {
    method: 'POST', body: JSON.stringify({ eventIds, serviceTier }), cache: 'no-store',
  }, { retryAfterRefresh: false });
}
export async function reconcilePrices() {
  return request<{ updated: number }>('/api/v1/admin/usage/billing/reconcile', { method: 'POST', cache: 'no-store' }, { retryAfterRefresh: false });
}
