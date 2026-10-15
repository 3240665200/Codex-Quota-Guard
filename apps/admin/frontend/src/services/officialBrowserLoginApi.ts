import { request } from './api';

export type OfficialBrowserLoginState = 'WAITING_FOR_HELPER' | 'WAITING_FOR_LOGIN' | 'SAVING'
  | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED';

export interface OfficialBrowserLoginJob {
  id: string;
  officialAccountId: string;
  accountName: string;
  state: OfficialBrowserLoginState;
  expiresAt: string;
  errorCode: string | null;
  proxyNodeId: string | null;
}

export function startOfficialBrowserLogin(id: string, body: { version: number; proxyNodeId: string | null }): Promise<OfficialBrowserLoginJob> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/browser-login`, {
    method: 'POST', body: JSON.stringify(body),
  });
}

export function getOfficialBrowserLogin(id: string): Promise<OfficialBrowserLoginJob> {
  return request(`/api/v1/admin/browser-logins/${encodeURIComponent(id)}`);
}

export function cancelOfficialBrowserLogin(id: string): Promise<OfficialBrowserLoginJob> {
  return request(`/api/v1/admin/browser-logins/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
}
