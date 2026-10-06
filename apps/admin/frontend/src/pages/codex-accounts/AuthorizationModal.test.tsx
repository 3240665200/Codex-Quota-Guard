import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import AuthorizationModal from './AuthorizationModal';
import { cancelCodexAuthorization, getCodexAuthorization, startCodexAuthorization } from '../../services/api';
import type { CodexAuthorization } from '../../services/api';

vi.mock('../../services/api', () => ({
  cancelCodexAuthorization: vi.fn(),
  getCodexAuthorization: vi.fn(),
  startCodexAuthorization: vi.fn(),
}));

const pending: CodexAuthorization = {
  id: 'authorization-1', status: 'PENDING', userCode: 'ABCD-1234',
  verificationUrl: 'https://auth.openai.com/codex/device',
  expiresAt: '2026-09-22T12:10:00Z', account: null, errorCode: null, message: null,
  mode: 'DEVICE_CODE', authUrl: null,
};

async function begin() {
  fireEvent.change(screen.getByLabelText('账户名称'), { target: { value: '开发账户' } });
  fireEvent.click(screen.getByRole('radio', { name: '设备码登录' }));
  fireEvent.click(screen.getByRole('button', { name: '获取授权码并打开官网' }));
  await waitFor(() => expect(startCodexAuthorization).toHaveBeenCalledWith('开发账户', 'DEVICE_CODE'));
}

describe('Codex authorization modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, 'open').mockReturnValue(null);
    const computedStyle = window.getComputedStyle.bind(window);
    vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => computedStyle(element));
    vi.mocked(startCodexAuthorization).mockResolvedValue(pending);
    vi.mocked(getCodexAuthorization).mockResolvedValue(pending);
    vi.mocked(cancelCodexAuthorization).mockResolvedValue(undefined);
  });

  it('starts only on explicit action, shows the official page and cancels on close', async () => {
    const close = vi.fn();
    render(<AuthorizationModal onClose={close} onAdded={vi.fn()} />);
    expect(startCodexAuthorization).not.toHaveBeenCalled();
    await begin();
    expect(await screen.findByText('ABCD-1234')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '打开 OpenAI 授权页' }))
      .toHaveAttribute('href', 'https://auth.openai.com/codex/device');
    expect(window.open).toHaveBeenCalledWith('https://auth.openai.com/codex/device', '_blank', 'noopener,noreferrer');
    fireEvent.click(screen.getByRole('button', { name: '取消授权' }));
    await waitFor(() => expect(close).toHaveBeenCalledOnce());
    expect(cancelCodexAuthorization).toHaveBeenCalledWith(pending.id);
  });

  it('handles a late start response after the modal was unmounted', async () => {
    let resolve!: (value: CodexAuthorization) => void;
    vi.mocked(startCodexAuthorization).mockReturnValue(new Promise((done) => { resolve = done; }));
    const view = render(<AuthorizationModal onClose={vi.fn()} onAdded={vi.fn()} />);
    await begin();
    view.unmount();
    await act(async () => { resolve(pending); });
    await waitFor(() => expect(cancelCodexAuthorization).toHaveBeenCalledWith(pending.id));
  });

  it('polls sequentially and refreshes the account list exactly once on success', async () => {
    const added = vi.fn();
    vi.mocked(getCodexAuthorization).mockResolvedValue({ ...pending, status: 'SUCCEEDED',
      userCode: null, verificationUrl: null,
      account: { id: 'account-1', email: 'test@example.com', displayName: '开发账户', status: 'ACTIVE',
        hasCredential: true, createdAt: '', updatedAt: '' },
    });
    const view = render(<AuthorizationModal onClose={vi.fn()} onAdded={added} />);
    await begin();
    await waitFor(() => expect(screen.getByText('账户已添加')).toBeInTheDocument(), { timeout: 4000 });
    expect(added).toHaveBeenCalledOnce();
    expect(getCodexAuthorization).toHaveBeenCalledTimes(1);
    view.unmount();
    expect(cancelCodexAuthorization).not.toHaveBeenCalled();
  });

  it('shows a retryable terminal error and never opens an untrusted authorization URL', async () => {
    vi.mocked(startCodexAuthorization).mockResolvedValue({ ...pending, status: 'FAILED',
      verificationUrl: 'https://attacker.invalid', message: '设备码登录未启用', errorCode: 'FAILED' });
    render(<AuthorizationModal onClose={vi.fn()} onAdded={vi.fn()} />);
    await begin();
    expect(await screen.findByText('设备码登录未启用')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '打开 OpenAI 授权页' })).not.toBeInTheDocument();
    expect(window.open).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '重新授权' }));
    expect(screen.getByLabelText('账户名称')).toBeInTheDocument();
  });

  it('does not claim successful cancellation when the server rejects it', async () => {
    const close = vi.fn();
    vi.mocked(cancelCodexAuthorization).mockRejectedValue(new Error('网络不可用'));
    render(<AuthorizationModal onClose={close} onAdded={vi.fn()} />);
    await begin();
    fireEvent.click(await screen.findByRole('button', { name: '取消授权' }));
    expect(await screen.findByText('网络不可用')).toBeInTheDocument();
    expect(close).not.toHaveBeenCalled();
  });

  it('opens the official browser flow and keeps a manual link for popup blockers', async () => {
    const authUrl = 'https://auth.openai.com/oauth/authorize?response_type=code'
      + '&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback&state=test-state'
      + '&code_challenge_method=S256&code_challenge=' + 'a'.repeat(43);
    vi.mocked(startCodexAuthorization).mockResolvedValue({ ...pending, mode: 'BROWSER',
      authUrl, userCode: null, verificationUrl: null });
    render(<AuthorizationModal onClose={vi.fn()} onAdded={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('账户名称'), { target: { value: '网页账户' } });
    fireEvent.click(screen.getByRole('radio', { name: '网页登录' }));
    fireEvent.click(screen.getByRole('button', { name: '前往官方登录' }));
    await waitFor(() => expect(startCodexAuthorization).toHaveBeenCalledWith('网页账户', 'BROWSER'));
    expect(await screen.findByRole('link', { name: '打开 OpenAI 授权页' })).toHaveAttribute('href', authUrl);
    expect(window.open).toHaveBeenCalledWith(authUrl, '_blank', 'noopener,noreferrer');
    expect(screen.getByText('登录后自动保存，无需下载或上传 JSON。')).toBeInTheDocument();
    expect(screen.queryByText('ABCD-1234')).not.toBeInTheDocument();
  });

  it('never opens unsafe URLs even when the authorization is pending', async () => {
    vi.mocked(startCodexAuthorization).mockResolvedValue({ ...pending, mode: 'BROWSER',
      authUrl: 'javascript:alert(1)', userCode: null, verificationUrl: null });
    render(<AuthorizationModal onClose={vi.fn()} onAdded={vi.fn()} />);
    await begin();
    expect(await screen.findByText('授权地址校验失败，请取消后重试。')).toBeInTheDocument();
    expect(window.open).not.toHaveBeenCalled();
    expect(screen.queryByRole('link', { name: '打开 OpenAI 授权页' })).not.toBeInTheDocument();
  });
});
