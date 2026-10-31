import { afterEach, expect, it, vi } from 'vitest';
import { createDesktopRelease, listDesktopReleases, uploadDesktopArtifact, type DesktopRelease } from './desktopReleasesApi';
import axios from 'axios';
import { request } from './api';

afterEach(() => vi.unstubAllGlobals());
it('reports chunk upload progress through the existing authenticated request path', async () => {
  const callback=vi.fn();const controller=new AbortController();
  const transport=vi.spyOn(axios,'request').mockResolvedValue({status:200,data:'{"receivedBytes":4}'});
  const response=await request<{receivedBytes:number}>('/api/v1/admin/release-uploads/test/chunks/0',{
    method:'PUT',body:new Blob(['data']),signal:controller.signal,headers:{'Content-Type':'application/octet-stream','X-Chunk-SHA256':'a'.repeat(64)},
  },{onUploadProgress:callback});
  expect(response.receivedBytes).toBe(4);
  expect(transport.mock.calls[0][0]).toMatchObject({method:'PUT',signal:controller.signal,onUploadProgress:callback,headers:{'content-type':'application/octet-stream'}});
});
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
  expect(init.cache).toBe('no-store');
});
it('bypasses cached permanent redirects for collection reads and creation', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response('[]', { status: 200 }))
    .mockResolvedValueOnce(new Response('{"version":"0.4.12"}', { status: 200 }));
  vi.stubGlobal('fetch', fetch);
  await listDesktopReleases(); await createDesktopRelease('0.4.12', 'test');
  expect(fetch.mock.calls[0][1].cache).toBe('no-store');
  expect(fetch.mock.calls[1][1]).toMatchObject({ cache: 'no-store', method: 'POST', body: '{"version":"0.4.12","notes":"test"}' });
});
