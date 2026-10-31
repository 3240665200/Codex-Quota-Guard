import { request } from './api';

export type DesktopArtifact = { id: string; target: 'windows' | 'darwin' | 'linux'; arch: 'x86_64' | 'aarch64'; filename: string; sha256: string; sizeBytes: number; uploadedAt: string };
export type DesktopRelease = { id: string; version: string; notes: string; status: 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'; createdAt: string; publishedAt: string | null; revision: number; artifacts: DesktopArtifact[] };
export type ReleaseKind = 'desktop' | 'meter';
const base = '/api/v1/admin/desktop-releases';
const catalog = (kind: ReleaseKind) => kind === 'meter' ? '/api/v1/admin/meter-releases' : base;
// Bypass previously cached permanent redirects from the old proxy rule.
export const listDesktopReleases = (kind: ReleaseKind = 'desktop') => request<DesktopRelease[]>(catalog(kind), { cache: 'no-store' });
export const createDesktopRelease = (version: string, notes: string, kind: ReleaseKind = 'desktop') => request<DesktopRelease>(catalog(kind), { method: 'POST', cache: 'no-store', body: JSON.stringify({ version, notes }) });
export const changeDesktopRelease = (release: DesktopRelease, action: 'publish' | 'withdraw', kind: ReleaseKind = 'desktop') => request<DesktopRelease>(`${catalog(kind)}/${release.id}/${action}`, { method: 'POST', cache: 'no-store', body: JSON.stringify({ revision: release.revision }) });
export function uploadDesktopArtifact(release: DesktopRelease, target: string, arch: string, installer: File, signature: File) {
  const body = new FormData();
  body.set('revision', String(release.revision)); body.set('target', target); body.set('arch', arch);
  body.set('installer', installer); body.set('signature', signature);
  return request<DesktopRelease>(`${base}/${release.id}/artifacts`, { method: 'POST', cache: 'no-store', body });
}
