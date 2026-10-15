import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cancelOfficialWebLogin, completeOfficialWebLogin, getOfficialWebLogin, getOfficialWebLoginFrame,
  sendOfficialWebLoginInput, startOfficialWebLogin } from './officialWebLoginApi';
const call = vi.hoisted(() => vi.fn());
vi.mock('./api', () => ({ request: call }));
const job = { id: 'job/id', officialAccountId: 'account/id', accountName: '研发账户', state: 'WAITING_FOR_LOGIN',
  expiresAt: '2099-10-15T02:00:00Z', errorCode: null, proxyNodeId: null, pageOrigin: 'https://auth.openai.com', readyToSave: false };
describe('服务器网页登录 API', () => {
  beforeEach(() => { vi.clearAllMocks(); call.mockResolvedValue(job); });
  it('uses isolated new endpoints and no-store without mutation replay', async () => {
    await startOfficialWebLogin('account/id', { version: 3, proxyNodeId: null });
    await getOfficialWebLogin('job/id'); await completeOfficialWebLogin('job/id'); await cancelOfficialWebLogin('job/id');
    expect(call.mock.calls.map((args) => args[0])).toEqual(['/api/v1/admin/official-accounts/account%2Fid/web-login',
      '/api/v1/admin/web-logins/job%2Fid', '/api/v1/admin/web-logins/job%2Fid/complete', '/api/v1/admin/web-logins/job%2Fid/cancel']);
    for (const args of call.mock.calls) { expect(args[1].cache).toBe('no-store'); expect(args[2]).toEqual({ retryAfterRefresh: false }); }
  });
  it('sends only constrained input, never retries, and accepts 204', async () => {
    call.mockResolvedValue(undefined); await sendOfficialWebLoginInput('job/id', { type: 'TEXT', text: '用户中文' });
    expect(call).toHaveBeenCalledExactlyOnceWith('/api/v1/admin/web-logins/job%2Fid/input',
      { method: 'POST', cache: 'no-store', body: JSON.stringify({ type: 'TEXT', text: '用户中文' }) }, { retryAfterRefresh: false });
  });
  it('rejects invalid dimensions or an origin that includes credentials or a query', async () => {
    call.mockResolvedValue({ width: 1024, height: 768, jpegBase64: '/9j/AA==', pageOrigin: 'https://auth.openai.com/?secret=x', readyToSave: false, errorCode: null });
    await expect(getOfficialWebLoginFrame('job/id')).rejects.toThrow();
  });
  it('returns only documented frame fields and accepts a not-yet-loaded job origin', async () => {
    const frame = { width: 1024, height: 768, jpegBase64: '/9j/AA==', pageOrigin: 'https://chatgpt.com', readyToSave: true, errorCode: null };
    call.mockResolvedValue({ ...frame, authorization: 'fixture-hidden' });
    expect(await getOfficialWebLoginFrame('job/id')).toEqual(frame);
    call.mockResolvedValue({ ...job, pageOrigin: null }); expect(await getOfficialWebLogin('job/id')).toEqual({ ...job, pageOrigin: null });
  });
  it.each([null, {}, { ...job, id: 'another-job' }, { ...job, state: 'UNKNOWN' }, { ...job, expiresAt: 'invalid' },
    { ...job, pageOrigin: 'https://outside.example' }, { ...job, pageOrigin: 'not a URL' }, { ...job, pageOrigin: 3 },
    { ...job, readyToSave: 'yes' }])('rejects malformed or mismatched job metadata: %j', async (value) => {
    call.mockResolvedValue(value); await expect(getOfficialWebLogin('job/id')).rejects.toThrow();
  });
  it('rejects a start response belonging to a different account', async () => {
    call.mockResolvedValue({ ...job, officialAccountId: 'different-account' });
    await expect(startOfficialWebLogin('account/id', { version: 1, proxyNodeId: null })).rejects.toThrow();
  });
  it.each([{ width: 2048 }, { jpegBase64: '' }, { jpegBase64: 'invalid frame!' }, { errorCode: undefined }])('rejects unsafe frame data: %j', async (invalid) => {
    call.mockResolvedValue({ width: 1024, height: 768, jpegBase64: '/9j/AA==', pageOrigin: 'https://chatgpt.com', readyToSave: false, errorCode: null, ...invalid });
    await expect(getOfficialWebLoginFrame('job/id')).rejects.toThrow();
  });
});
