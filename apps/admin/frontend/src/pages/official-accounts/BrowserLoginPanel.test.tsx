import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BrowserLoginPanel from './BrowserLoginPanel';
import type { OfficialBrowserLoginJob } from '../../services/officialBrowserLoginApi';

const mocks = vi.hoisted(() => ({
  startOfficialBrowserLogin: vi.fn(), getOfficialBrowserLogin: vi.fn(), cancelOfficialBrowserLogin: vi.fn(),
}));
vi.mock('../../services/officialBrowserLoginApi', () => mocks);

const job: OfficialBrowserLoginJob = { id: 'job-1', officialAccountId: 'account-1', accountName: '研发账户',
  state: 'WAITING_FOR_HELPER', expiresAt: '2026-10-15T02:00:00Z', errorCode: null, proxyNodeId: 'proxy-1' };

function show(disabled = false) {
  const onCompleted = vi.fn().mockResolvedValue(undefined);
  const view = render(<BrowserLoginPanel accountId="account-1" version={4} proxyNodeId="proxy-1"
    disabled={disabled} onResultChanged={onCompleted} />);
  return { onCompleted, ...view };
}

describe('管理员官方网页登录', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.startOfficialBrowserLogin.mockResolvedValue(job);
    mocks.getOfficialBrowserLogin.mockResolvedValue({ ...job, state: 'WAITING_FOR_LOGIN' });
    mocks.cancelOfficialBrowserLogin.mockResolvedValue({ ...job, state: 'CANCELLED' });
  });
  afterEach(() => { vi.useRealTimers(); });

  it('creates a safe job and explains the real desktop helper workflow', async () => {
    show();
    fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' }));
    await waitFor(() => expect(mocks.startOfficialBrowserLogin).toHaveBeenCalledWith('account-1', { version: 4, proxyNodeId: 'proxy-1' }));
    expect(await screen.findByText('等待桌面助手')).toBeInTheDocument();
    expect(screen.getByText(/使用同一个管理员账号登录桌面客户端/)).toBeInTheDocument();
    expect(screen.getByText(/设置 → 官方登录助手/)).toBeInTheDocument();
    expect(screen.getByText(/在打开的 Edge 或 Chrome 官方页面亲自完成登录/)).toBeInTheDocument();
    expect(screen.getByText('job-1')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /打开客户端/ })).not.toBeInTheDocument();
  });

  it('polls every three seconds and reloads the saved result exactly once on completion', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { onCompleted } = show();
    fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' }));
    await act(async () => { await Promise.resolve(); });
    expect(mocks.getOfficialBrowserLogin).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(mocks.getOfficialBrowserLogin).toHaveBeenCalledWith('job-1');
    expect(screen.getByText('等待完成官方登录')).toBeInTheDocument();
    mocks.getOfficialBrowserLogin.mockResolvedValue({ ...job, state: 'COMPLETED' });
    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(screen.getByText('登录完成，授权已加密保存')).toBeInTheDocument();
    expect(onCompleted).toHaveBeenCalledOnce();
    await act(async () => { await vi.advanceTimersByTimeAsync(6000); });
    expect(mocks.getOfficialBrowserLogin).toHaveBeenCalledTimes(2);
    expect(onCompleted).toHaveBeenCalledOnce();
  });

  it('cancels only the current task and explains that the browser login itself is unchanged', async () => {
    show();
    fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' }));
    fireEvent.click(await screen.findByRole('button', { name: '取消登录任务' }));
    await waitFor(() => expect(mocks.cancelOfficialBrowserLogin).toHaveBeenCalledWith('job-1'));
    expect(await screen.findByText('登录任务已取消')).toBeInTheDocument();
    expect(screen.getByText(/取消任务不会让已登录的官方浏览器退出/)).toBeInTheDocument();
  });

  it.each(['FAILED', 'EXPIRED'] as const)('shows %s safely and allows a fresh explicit attempt', async (state) => {
    mocks.startOfficialBrowserLogin.mockResolvedValue({ ...job, state, errorCode: 'fixture-sensitive-error' });
    show();
    fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' }));
    expect(await screen.findByText(state === 'FAILED' ? '官方网页登录未完成' : '登录任务已过期')).toBeInTheDocument();
    expect(screen.queryByText('fixture-sensitive-error')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新开始官方网页登录' })).toBeEnabled();
  });

  it('disables task creation when transport, encryption, or proxy requirements block it', () => {
    show(true);
    expect(screen.getByRole('button', { name: '开始官方网页登录' })).toBeDisabled();
  });

  it('stops polling after unmount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { unmount } = show();
    fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' }));
    await act(async () => { await Promise.resolve(); });
    unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(6000); });
    expect(mocks.getOfficialBrowserLogin).not.toHaveBeenCalled();
  });
});
