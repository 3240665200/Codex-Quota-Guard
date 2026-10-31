import { request } from './api';

export interface BillingFacts {
  model: string | null; reasoningEffort: string | null; totalTokens: number;
  inputTokens: number | null; cachedInputTokens: number | null; cacheWriteInputTokens: number | null;
  outputTokens: number | null; reasoningOutputTokens: number | null; serviceTier: string | null;
}
export interface BillingPrices {
  inputPrice: number | string; outputPrice: number | string; cacheReadPrice: number | string;
  cacheWritePrice: number | string; fastMultiplier: number | string;
}
export interface BillingAuditState {
  original: BillingFacts; effective: BillingFacts; pricingMode: 'CATALOG' | 'CUSTOM';
  customPrice: BillingPrices | null; priceSnapshot: BillingPrices | null;
  billing: { costUsd: string | number | null; costStatus: string; modeStatus: string; multiplier: string | number };
}
export interface BillingAudit {
  id: string; revision: number; reviewedBy: string | null; administratorName: string | null;
  reviewedAt: string; note: string; before: BillingAuditState; after: BillingAuditState;
}
export interface BillingDetail {
  id: string; userId: string | null; teamId: string | null; officialAccountId: string | null;
  occurredAt: string; receivedAt: string; source: string; original: BillingFacts; effective: BillingFacts;
  revision: number; fingerprint: string; editable: boolean; pricingMode: 'CATALOG' | 'CUSTOM';
  customPrice: BillingPrices | null; catalogPrice: BillingPrices | null; appliedPrice: BillingPrices | null;
  multiplier: string; costStatus: string; costUsd: string | null; modeStatus: string; history: BillingAudit[];
}
export interface BillingReviewInput {
  revision: number; fingerprint: string; facts: BillingFacts; pricingMode: 'CATALOG' | 'CUSTOM';
  customPrice: BillingPrices | null; note: string; previewToken?: string;
}
export interface BillingPreview {
  previewToken: string; costStatus: string; costUsd: string | null; multiplier: string;
  appliedPrice: BillingPrices | null; missingFields: string[];
}
const route = (id: string) => `/api/v1/admin/usage/billing/entries/${encodeURIComponent(id)}`;
export const readBillingDetail = (id: string) => request<BillingDetail>(route(id), { cache: 'no-store' });
export const previewBillingReview = (id: string, body: BillingReviewInput) => request<BillingPreview>(`${route(id)}/preview`, {
  method: 'POST', body: JSON.stringify(body), cache: 'no-store',
}, { retryAfterRefresh: false });
export const saveBillingReview = (id: string, body: BillingReviewInput) => request<{ detail: BillingDetail; calculation: BillingPreview }>(`${route(id)}/review`, {
  method: 'POST', body: JSON.stringify(body), cache: 'no-store',
}, { retryAfterRefresh: false });
