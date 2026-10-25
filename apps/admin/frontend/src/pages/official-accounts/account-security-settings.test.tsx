import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AuthenticatorConfigModal from './AuthenticatorConfigModal';
import QuotaPollingModal from './QuotaPollingModal';
import * as api from '../../services/api';
import type { OfficialAccountResponse } from '../../services/api';

vi.mock('../../services/api', () => ({
  getOfficialAuthenticator: vi.fn(), saveOfficialAuthenticator: vi.fn(), deleteOfficialAuthenticator: vi.fn(),
  getOfficialQuotaPolling: vi.fn(), saveOfficialQuotaPolling: vi.fn(), refreshOfficialQuota: vi.fn(),
}));
const account: OfficialAccountResponse = { id: 'official-1', label: '研发账户', estimatedTotalTokens: 1000, reporterEmail: null,
  quota: null, createdAt: '', updatedAt: '', version: 0 };
const config = { configured: true, encryptionReady: true, enabled: true, algorithm: 'SHA1', digits: 6, periodSeconds: 30, version: 2 };
const polling: api.OfficialQuotaPolling = { enabled: true, intervalSeconds: 300, version: 1, status: 'SUCCESS', lastAttemptAt: null,
  lastSuccessAt: null, nextAttemptAt: null, lastErrorCode: null, quota: null };
describe('官方账户额度检测与验证器设置', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.getOfficialAuthenticator).mockResolvedValue(config);
    vi.mocked(api.saveOfficialAuthenticator).mockResolvedValue({ ...config, version: 3 });
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
  it('disables immediate requests while the server cooldown is active', async () => {
    vi.mocked(api.getOfficialQuotaPolling).mockResolvedValue({ ...polling, nextAttemptAt: new Date(Date.now() + 60_000).toISOString() });
    render(<QuotaPollingModal account={account} onClose={vi.fn()} onChange={vi.fn()} />);
    await screen.findByText(/下次允许检测/);
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByRole('button', { name: '立即检测' })).toBeDisabled();
    expect(api.refreshOfficialQuota).not.toHaveBeenCalled();
  });
});
