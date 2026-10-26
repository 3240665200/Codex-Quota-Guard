import type { UsdOverview, UsdScope, UsdWindow } from '@/lib/usd';
export const moneyWindow = (limit: string | null = null, used = '0', remaining: string | null = null): UsdWindow => ({
  limitUsd: limit, usedUsd: used, remainingUsd: remaining, pendingEvents: 0, modeUnrecordedEvents: 0, historicalEvents: 0,
});
export const moneyScope = (): UsdScope => ({ currency: 'USD', fiveHour: moneyWindow(), weekly: moneyWindow(), total: moneyWindow() });
export const usdFixture = (overrides: Partial<UsdOverview> = {}): UsdOverview => ({
  currency: 'USD', personal: moneyScope(), team: null, officialAccount: null, teamMembers: [],
  allowed: true, blockReason: null, ...overrides,
});
