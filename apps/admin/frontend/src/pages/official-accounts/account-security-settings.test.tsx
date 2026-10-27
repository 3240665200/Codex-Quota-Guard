import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AuthenticatorConfigModal from './AuthenticatorConfigModal';
import QuotaPollingModal from './QuotaPollingModal';
import * as api from '../../services/api';
import { readAuthenticatorQr } from '../../utils/authenticator-qr';
import type { OfficialAccountResponse } from '../../services/api';

vi.mock('../../services/api', () => ({
  getOfficialAuthenticator: vi.fn(), saveOfficialAuthenticator: vi.fn(), deleteOfficialAuthenticator: vi.fn(), previewOfficialAuthenticator: vi.fn(),
  getOfficialQuotaPolling: vi.fn(), saveOfficialQuotaPolling: vi.fn(), refreshOfficialQuota: vi.fn(),
}));
vi.mock('../../utils/authenticator-qr', () => ({ readAuthenticatorQr: vi.fn() }));
const account: OfficialAccountResponse = { id: 'official-1', label: '研发账户', estimatedTotalTokens: 1000, reporterEmail: null,
  quota: null, createdAt: '', updatedAt: '', version: 0 };
const config = { configured: true, encryptionReady: true, enabled: true, algorithm: 'SHA1', digits: 6, periodSeconds: 30, version: 2 };
const syntheticUri = 'otpauth://totp/OpenAI%3Asynthetic?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
const preview = { code: '123456', serverTime: '2026-10-25T04:00:00Z', expiresAt: '2026-10-25T04:00:30Z', periodSeconds: 30, officialAccountId: account.id };
const polling: api.OfficialQuotaPolling = { enabled: true, intervalSeconds: 300, version: 1, status: 'SUCCESS', lastAttemptAt: null,
  lastSuccessAt: null, nextAttemptAt: null, lastErrorCode: null, quota: null };
describe('官方账户额度检测与验证器设置', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.getOfficialAuthenticator).mockResolvedValue(config);
    vi.mocked(api.saveOfficialAuthenticator).mockResolvedValue({ ...config, version: 3 });
    vi.mocked(api.previewOfficialAuthenticator).mockResolvedValue(preview);
    vi.mocked(readAuthenticatorQr).mockResolvedValue(syntheticUri);
    vi.mocked(api.getOfficialQuotaPolling).mockResolvedValue(polling);
    vi.mocked(api.saveOfficialQuotaPolling).mockResolvedValue({ ...polling, intervalSeconds: 600, version: 2 });
  });
  afterEach(cleanup);
  it('does not refill an existing secret and clears a new secret after save', async () => {
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const input = await screen.findByLabelText('TOTP 密钥或 otpauth://totp 链接');
    await waitFor(() => expect(screen.getByRole('button', { name: /保\s*存/ })).toBeEnabled());
    expect(input).toHaveValue('');
    fireEvent.change(input, { target: { value: 'JBSWY3DPEHPK3PXP' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(api.saveOfficialAuthenticator).toHaveBeenCalledWith('official-1', { enabled: true, secret: 'JBSWY3DPEHPK3PXP', version: 2 }));
    await waitFor(() => expect(input).toHaveValue(''));
  });
  it('saves the chosen interval in seconds and the version returned by the backend', async () => {
    const onChange = vi.fn();
    render(<QuotaPollingModal account={account} onClose={vi.fn()} onChange={onChange} />);
    const input = await screen.findByRole('spinbutton', { name: '检测时间间隔（分钟）' });
    fireEvent.change(input, { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }));
    await waitFor(() => expect(api.saveOfficialQuotaPolling).toHaveBeenCalledWith('official-1', { enabled: true, intervalSeconds: 600, version: 1 }));
    expect(onChange).toHaveBeenCalled();
  });
  it('imports an image locally and only submits the extracted key after explicit save', async () => {
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const input = await screen.findByLabelText('TOTP 密钥或 otpauth://totp 链接');
    const file = new File(['synthetic-qr'], 'synthetic.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText('验证器二维码图片'), { target: { files: [file] } });
    await screen.findByText('已识别：OpenAI:synthetic');
    expect(readAuthenticatorQr).toHaveBeenCalledWith(file);
    expect(input).toHaveValue(syntheticUri);
    expect(api.saveOfficialAuthenticator).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '获取当前验证码' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(api.saveOfficialAuthenticator).toHaveBeenCalledWith(account.id, { enabled: true, secret: syntheticUri, version: 2 }));
    await waitFor(() => expect(input).toHaveValue(''));
  });
  it('discards an image decoder result after the dialog has been closed', async () => {
    let resolve!: (value: string) => void;
    vi.mocked(readAuthenticatorQr).mockReturnValue(new Promise((done) => { resolve = done; }));
    const onClose = vi.fn();
    render(<AuthenticatorConfigModal account={account} onClose={onClose} />);
    await screen.findByLabelText('TOTP 密钥或 otpauth://totp 链接');
    fireEvent.change(screen.getByLabelText('验证器二维码图片'), { target: { files: [new File(['qr'], 'test.png', { type: 'image/png' })] } });
    fireEvent.click(screen.getByRole('button', { name: /关\s*闭/ }));
    await act(async () => resolve(syntheticUri));
    expect(onClose).toHaveBeenCalled();
    expect(screen.queryByText('已识别：OpenAI:synthetic')).not.toBeInTheDocument();
    expect(api.saveOfficialAuthenticator).not.toHaveBeenCalled();
  });
  it('previews the saved account code and hides it when the window loses focus', async () => {
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const button = await screen.findByRole('button', { name: '获取当前验证码' });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    expect(await screen.findByLabelText('当前一次性验证码')).toHaveTextContent('123456');
    expect(api.previewOfficialAuthenticator).toHaveBeenCalledWith(account.id);
    expect(sessionStorage.length).toBe(0);
    fireEvent.blur(window);
    expect(screen.queryByLabelText('当前一次性验证码')).not.toBeInTheDocument();
  });
  it('does not reveal a pending preview after the user has left the window', async () => {
    let resolve!: (value: api.OfficialAuthenticatorCode) => void;
    vi.mocked(api.previewOfficialAuthenticator).mockReturnValue(new Promise((done) => { resolve = done; }));
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const button = await screen.findByRole('button', { name: '获取当前验证码' });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    fireEvent.blur(window);
    await act(async () => resolve(preview));
    expect(screen.queryByLabelText('当前一次性验证码')).not.toBeInTheDocument();
  });
  it('rejects a preview returned for another account', async () => {
    vi.mocked(api.previewOfficialAuthenticator).mockResolvedValue({ ...preview, officialAccountId: 'another-account' });
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const button = await screen.findByRole('button', { name: '获取当前验证码' });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    await screen.findByText('验证码已过期或返回格式不完整，请重新获取。');
    expect(screen.queryByLabelText('当前一次性验证码')).not.toBeInTheDocument();
  });
  it('hides an expired code using elapsed time instead of the computer wall clock', async () => {
    const now = vi.spyOn(performance, 'now').mockReturnValue(0);
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const button = await screen.findByRole('button', { name: '获取当前验证码' });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    await screen.findByLabelText('当前一次性验证码');
    now.mockReturnValue(31_000);
    await waitFor(() => expect(screen.queryByLabelText('当前一次性验证码')).not.toBeInTheDocument());
    expect(api.previewOfficialAuthenticator).toHaveBeenCalledTimes(1);
  });
  it('automatically replaces an expired code and stops updating when requested', async () => {
    const now=vi.spyOn(performance,'now').mockReturnValue(0);
    vi.mocked(api.previewOfficialAuthenticator).mockResolvedValueOnce(preview).mockResolvedValueOnce({
      ...preview,code:'654321',serverTime:'2026-10-25T04:00:30Z',expiresAt:'2026-10-25T04:01:00Z',
    });
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const button=await screen.findByRole('button',{name:'获取当前验证码'});
    await waitFor(() => expect(button).toBeEnabled()); fireEvent.click(button);
    expect(await screen.findByLabelText('当前一次性验证码')).toHaveTextContent('123456');
    now.mockReturnValue(31_000);
    await waitFor(() => expect(screen.getByLabelText('当前一次性验证码')).toHaveTextContent('654321'));
    expect(api.previewOfficialAuthenticator).toHaveBeenCalledTimes(2);
    expect(screen.getByText(/本轮到期/)).toBeVisible();
    fireEvent.click(screen.getByRole('button',{name:'停止自动更新'}));
    now.mockReturnValue(62_000);
    await act(async () => { await new Promise(resolve => setTimeout(resolve,350)); });
    expect(api.previewOfficialAuthenticator).toHaveBeenCalledTimes(2);
    expect(screen.queryByLabelText('当前一次性验证码')).not.toBeInTheDocument();
  });
  it('discards a pending code after automatic updating has been stopped', async () => {
    let resolve!: (value:api.OfficialAuthenticatorCode)=>void;
    vi.mocked(api.previewOfficialAuthenticator).mockReturnValue(new Promise(done => { resolve=done; }));
    render(<AuthenticatorConfigModal account={account} onClose={vi.fn()} />);
    const button=await screen.findByRole('button',{name:'获取当前验证码'});
    await waitFor(() => expect(button).toBeEnabled()); fireEvent.click(button);
    fireEvent.click(await screen.findByRole('button',{name:'停止自动更新'}));
    await act(async () => resolve(preview));
    expect(screen.queryByLabelText('当前一次性验证码')).not.toBeInTheDocument();
  });
  it('disables immediate requests while the server cooldown is active', async () => {
    vi.mocked(api.getOfficialQuotaPolling).mockResolvedValue({ ...polling, nextAttemptAt: new Date(Date.now() + 60_000).toISOString() });
    render(<QuotaPollingModal account={account} onClose={vi.fn()} onChange={vi.fn()} />);
    await screen.findByText(/下次允许检测/);
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByRole('button', { name: '立即检测' })).toBeDisabled();
    expect(api.refreshOfficialQuota).not.toHaveBeenCalled();
  });
});
