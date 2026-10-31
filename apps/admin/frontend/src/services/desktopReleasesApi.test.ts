import { afterEach, expect, it, vi } from 'vitest';
import { uploadDesktopArtifact, type DesktopRelease } from './desktopReleasesApi';

afterEach(() => vi.unstubAllGlobals());
it('uploads the actual binary files using browser multipart boundaries', async () => {
  const release: DesktopRelease = { id: 'synthetic', version: '0.4.12', notes: '', status: 'DRAFT', createdAt: '', publishedAt: null, revision: 2, artifacts: [] };
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(release), { status: 200 })); vi.stubGlobal('fetch', fetch);
  const installer = new File(['MZ-synthetic'], 'test.exe'); const signature = new File(['synthetic-signature'], 'test.exe.sig');
  await uploadDesktopArtifact(release, 'windows', 'x86_64', installer, signature);
  const [url, init] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/admin/desktop-releases/synthetic/artifacts');
  expect(init.headers.has('Content-Type')).toBe(false);
  expect(init.body).toBeInstanceOf(FormData);
  expect(init.body.get('installer')).toBe(installer); expect(init.body.get('signature')).toBe(signature);
  expect(init.body.get('revision')).toBe('2'); expect(init.body.get('target')).toBe('windows');
});
