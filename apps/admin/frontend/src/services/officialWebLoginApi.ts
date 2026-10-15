import { request } from './api';
import type { OfficialBrowserLoginState } from './officialBrowserLoginApi';

export interface OfficialWebLoginJob {
  id: string; officialAccountId: string; accountName: string; state: OfficialBrowserLoginState;
  expiresAt: string; errorCode: string | null; proxyNodeId: string | null;
  pageOrigin: string | null; readyToSave: boolean;
}
export interface OfficialWebLoginFrame {
  width: number; height: number; jpegBase64: string; pageOrigin: string | null;
  readyToSave: boolean; errorCode: string | null;
}
export const WEB_LOGIN_KEYS = ['Tab', 'Enter', 'Backspace', 'Delete', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'] as const;
export type OfficialWebLoginInput = { type: 'OPEN_SESSIONS' } | { type: 'CLICK'; x: number; y: number }
  | { type: 'WHEEL'; x: number; y: number; deltaY: number }
  | { type: 'TEXT'; text: string }
  | { type: 'KEY'; key: string; ctrl?: boolean; shift?: boolean };
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const nullable = (value: unknown) => value === null || typeof value === 'string';
const invalid = () => new Error('Invalid official web login response');
const base = (id: string) => `/api/v1/admin/web-logins/${encodeURIComponent(id)}`;
const options = { retryAfterRefresh: false };
export function safeOfficialOrigin(value: unknown): value is string | null {
  if (value === null) return true;
  if (typeof value !== 'string') return false;
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' && value === parsed.origin && !parsed.username && !parsed.password
      && ['chatgpt.com', 'auth.openai.com', 'auth0.openai.com', 'platform.openai.com', 'www.chatgpt.com'].includes(parsed.hostname);
  } catch { return false; }
}
function job(value: unknown, expectedId?: string, accountId?: string): OfficialWebLoginJob {
  if (!record(value) || typeof value.id !== 'string' || !value.id || expectedId && value.id !== expectedId
    || typeof value.officialAccountId !== 'string' || accountId && value.officialAccountId !== accountId
    || typeof value.accountName !== 'string' || !['WAITING_FOR_HELPER', 'WAITING_FOR_LOGIN', 'SAVING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(value.state as string)
    || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
    || !nullable(value.errorCode) || !nullable(value.proxyNodeId) || !safeOfficialOrigin(value.pageOrigin)
    || typeof value.readyToSave !== 'boolean') throw invalid();
  const { id, officialAccountId, accountName, state, expiresAt, errorCode, proxyNodeId, pageOrigin, readyToSave } = value;
  // Pick the documented fields so unexpected upstream properties never reach the view.
  return { id, officialAccountId, accountName, state, expiresAt, errorCode, proxyNodeId, pageOrigin, readyToSave } as OfficialWebLoginJob;
}
export async function startOfficialWebLogin(accountId: string, body: { version: number; proxyNodeId: string | null }): Promise<OfficialWebLoginJob> {
  return job(await request(`/api/v1/admin/official-accounts/${encodeURIComponent(accountId)}/web-login`, {
    method: 'POST', cache: 'no-store', body: JSON.stringify(body),
  }, options), undefined, accountId);
}
export async function getOfficialWebLogin(id: string): Promise<OfficialWebLoginJob> {
  return job(await request(base(id), { cache: 'no-store' }, options), id);
}
export async function getOfficialWebLoginFrame(id: string): Promise<OfficialWebLoginFrame> {
  const value = await request<unknown>(`${base(id)}/frame`, { cache: 'no-store' }, options);
  if (!record(value) || value.width !== 1024 || value.height !== 768 || typeof value.jpegBase64 !== 'string'
    || !value.jpegBase64 || value.jpegBase64.length > 2_800_000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value.jpegBase64)
    || !safeOfficialOrigin(value.pageOrigin) || typeof value.readyToSave !== 'boolean' || !nullable(value.errorCode)) throw invalid();
  const { width, height, jpegBase64, pageOrigin, readyToSave, errorCode } = value;
  return { width, height, jpegBase64, pageOrigin, readyToSave, errorCode } as OfficialWebLoginFrame;
}
export function sendOfficialWebLoginInput(id: string, input: OfficialWebLoginInput): Promise<void> {
  return request(`${base(id)}/input`, { method: 'POST', cache: 'no-store', body: JSON.stringify(input) }, options);
}
export async function completeOfficialWebLogin(id: string): Promise<OfficialWebLoginJob> {
  return job(await request(`${base(id)}/complete`, { method: 'POST', cache: 'no-store' }, options), id);
}
export async function cancelOfficialWebLogin(id: string): Promise<OfficialWebLoginJob> {
  return job(await request(`${base(id)}/cancel`, { method: 'POST', cache: 'no-store', keepalive: true }, options), id);
}
