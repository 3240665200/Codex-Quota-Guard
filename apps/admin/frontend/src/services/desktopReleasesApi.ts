import { request } from './api';

export type DesktopArtifact = { id: string; target: 'windows' | 'darwin' | 'linux'; arch: 'x86_64' | 'aarch64'; filename: string; sha256: string; sizeBytes: number; uploadedAt: string };
export type DesktopRelease = { id: string; version: string; notes: string; status: 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'; createdAt: string; publishedAt: string | null; revision: number; artifacts: DesktopArtifact[] };
const base = '/api/v1/admin/desktop-releases';
export const listDesktopReleases = () => request<DesktopRelease[]>(base);
export const createDesktopRelease = (version: string, notes: string) => request<DesktopRelease>(base, { method: 'POST', body: JSON.stringify({ version, notes }) });
export const changeDesktopRelease = (release: DesktopRelease, action: 'publish' | 'withdraw') => request<DesktopRelease>(`${base}/${release.id}/${action}`, { method: 'POST', body: JSON.stringify({ revision: release.revision }) });
export function uploadDesktopArtifact(release: DesktopRelease, target: string, arch: string, installer: File, signature: File) {
  const body = new FormData();
  body.set('revision', String(release.revision)); body.set('target', target); body.set('arch', arch);
  body.set('installer', installer); body.set('signature', signature);
  return request<DesktopRelease>(`${base}/${release.id}/artifacts`, { method: 'POST', body });
}
