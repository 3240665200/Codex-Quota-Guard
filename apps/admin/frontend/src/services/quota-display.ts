import { useSyncExternalStore } from 'react';

export type QuotaDisplayMode = 'remaining' | 'used';
export const quotaDisplayStorageKey = 'cqg-admin-quota-display';
const listeners = new Set<() => void>();
let volatileMode: QuotaDisplayMode | null = null;

function read(): QuotaDisplayMode {
  if (volatileMode) return volatileMode;
  try { return window.localStorage.getItem(quotaDisplayStorageKey) === 'used' ? 'used' : 'remaining'; }
  catch { return 'remaining'; }
}
function storageChanged(event: StorageEvent) {
  if (event.key !== null && event.key !== quotaDisplayStorageKey) return;
  volatileMode = null;
  listeners.forEach((listener) => listener());
}
function subscribe(listener: () => void) {
  if (listeners.size === 0) window.addEventListener('storage', storageChanged);
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) window.removeEventListener('storage', storageChanged);
  };
}
export function setQuotaDisplayMode(mode: QuotaDisplayMode) {
  volatileMode = mode;
  try { window.localStorage.setItem(quotaDisplayStorageKey, mode); volatileMode = null; }
  catch { /* Keep the preference functional for this page when storage is unavailable. */ }
  listeners.forEach((listener) => listener());
}
export function useQuotaDisplay() {
  const mode = useSyncExternalStore(subscribe, read, () => 'remaining' as const);
  return { mode, label: mode === 'remaining' ? '剩余' : '已用', setMode: setQuotaDisplayMode };
}
export function quotaPercent(mode: QuotaDisplayMode, used: number, remaining: number | null, limit: number | null): number | null {
  if (limit === null || remaining === null || !Number.isFinite(used) || !Number.isFinite(remaining) || !Number.isFinite(limit)) return null;
  if (limit === 0) return mode === 'used' ? 100 : 0;
  return Math.min(100, Math.max(0, ((mode === 'used' ? used : remaining) * 100) / limit));
}
