export type Usd = string;
export interface UsdWindow {
  limitUsd: Usd | null; usedUsd: Usd; remainingUsd: Usd | null;
  pendingEvents: number; modeUnrecordedEvents: number; historicalEvents: number;
}
export interface UsdScope { currency: 'USD'; fiveHour: UsdWindow; weekly: UsdWindow; total: UsdWindow }
export interface UsdOfficialAccount { id: string; label: string; quota: UsdScope }
export interface UsdMember { userId: string; displayName: string; status: string; personal: UsdScope; contribution: UsdScope }
export interface UsdOverview {
  currency: 'USD'; personal: UsdScope; team: UsdScope | null; officialAccount: UsdOfficialAccount | null;
  teamMembers: UsdMember[]; allowed: boolean; blockReason: string | null;
}
export interface UsdBudgetInput {
  fiveHourUsdLimit: Usd | null; weeklyUsdLimit: Usd | null; totalUsdLimit?: Usd | null;
  officialAccountId?: string | null; version?: number;
}
