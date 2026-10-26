import { z } from 'zod';
const amount = z.string().min(1).max(64).refine((value) => decimalParts(value) !== null, '美元金额无效');
const count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
export const usdWindowSchema = z.object({
  limitUsd: amount.nullable(), usedUsd: amount, remainingUsd: amount.nullable(),
  pendingEvents: count, modeUnrecordedEvents: count, historicalEvents: count,
}).superRefine((window, context) => {
  if (window.limitUsd === null && window.remainingUsd !== null || window.limitUsd !== null && window.remainingUsd === null && window.pendingEvents === 0) context.addIssue({ code: 'custom', message: '美元余额与上限或待核算状态不一致' });
});
export const usdScopeSchema = z.object({ currency: z.literal('USD'), fiveHour: usdWindowSchema, weekly: usdWindowSchema, total: usdWindowSchema });
export const usdOverviewSchema = z.object({
  currency: z.literal('USD'), personal: usdScopeSchema, team: usdScopeSchema.nullable(),
  officialAccount: z.object({ id: z.string(), label: z.string(), quota: usdScopeSchema }).nullable(),
  teamMembers: z.array(z.object({ userId: z.string(), displayName: z.string(), status: z.string(), personal: usdScopeSchema, contribution: usdScopeSchema })),
  allowed: z.boolean(), blockReason: z.string().nullable(),
});
export type UsdWindow = z.infer<typeof usdWindowSchema>;
export type UsdScope = z.infer<typeof usdScopeSchema>;
export type UsdOverview = z.infer<typeof usdOverviewSchema>;

/** Preserve decimal strings for display; floating point is used only for bar widths. */
function decimalParts(value: string): [string, string] | null {
  const match = /^(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value);
  if (!match || Math.abs(Number(match[3] || 0)) > 30) return null;
  const digits = match[1] + (match[2] || '');
  const point = match[1].length + Number(match[3] || 0);
  const plain = point <= 0 ? '0.' + '0'.repeat(-point) + digits
    : point >= digits.length ? digits + '0'.repeat(point - digits.length)
    : digits.slice(0, point) + '.' + digits.slice(point);
  const [whole, fraction = ''] = plain.split('.');
  return [whole.replace(/^0+(?=\d)/, ''), fraction.replace(/0+$/, '')];
}
export function formatUsd(value: string): string {
  const parts = decimalParts(value);
  if (!parts) return '暂不可得';
  return '$' + parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + parts[1].padEnd(2, '0') + ' USD';
}
/** Exact comparisons also protect the allowed/exhausted contract at large balances. */
export function compareUsd(left: string, right: string): number {
  const a = decimalParts(left), b = decimalParts(right);
  if (!a || !b) return NaN;
  if (a[0].length !== b[0].length) return a[0].length > b[0].length ? 1 : -1;
  if (a[0] !== b[0]) return a[0] > b[0] ? 1 : -1;
  const length = Math.max(a[1].length, b[1].length);
  const af = a[1].padEnd(length, '0'), bf = b[1].padEnd(length, '0');
  return af === bf ? 0 : af > bf ? 1 : -1;
}
export function usdPercent(window: UsdWindow, mode: 'used' | 'remaining'): number | null {
  if (window.limitUsd === null) return null;
  if (mode === 'remaining' && window.remainingUsd === null) return null;
  const limit = Number(window.limitUsd);
  if (limit === 0) return mode === 'used' ? 100 : 0;
  return Math.max(0, Math.min(100, Number(mode === 'used' ? window.usedUsd : window.remainingUsd) / limit * 100));
}
