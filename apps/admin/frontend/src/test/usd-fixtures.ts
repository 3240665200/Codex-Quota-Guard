import type { UsdOverview, UsdScope, UsdWindow } from '../services/usd-types';

export function usdWindow(limit: string | null = null, used = '0', remaining: string | null = null): UsdWindow {
  return { limitUsd: limit, usedUsd: used, remainingUsd: remaining,
    pendingEvents: 0, modeUnrecordedEvents: 0, historicalEvents: 0 };
}
export function usdScope(fiveHour = usdWindow(), weekly = usdWindow(), total = usdWindow()): UsdScope {
  return { currency: 'USD', fiveHour, weekly, total };
}
export function usdOverview(overrides: Partial<UsdOverview> = {}): UsdOverview {
  return { currency: 'USD', personal: usdScope(), team: null, officialAccount: null, teamMembers: [],
    allowed: true, blockReason: null, ...overrides };
}
