import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SessionBindingQueue from './SessionBindingQueue';

const mocks = vi.hoisted(() => ({ getSessionBindingQueue: vi.fn(), recoverSessionBindingQueue: vi.fn() }));
vi.mock('../../services/sessionBindingQueueApi', () => mocks);
const normal = { officialAccountId: 'account-1', quarantined: false, reason: null, activeAttemptId: null };
const quarantined = { ...normal, quarantined: true, reason: 'fixture-sensitive-reason' };
beforeEach(() => {
  vi.clearAllMocks(); mocks.getSessionBindingQueue.mockResolvedValue(quarantined);
  mocks.recoverSessionBindingQueue.mockResolvedValue(normal);
});
describe('官方会话绑定队列恢复', () => {
  it('loads safe state once and requires explicit acknowledgement before recovering', async () => {
    render(<SessionBindingQueue accountId="account-1" />);
    await screen.findByText('需管理员处理');
    expect(screen.queryByText('fixture-sensitive-reason')).not.toBeInTheDocument();
    const button = screen.getByRole('button', { name: '确认风险并恢复队列' });
    expect(button).toBeDisabled();
    expect(mocks.recoverSessionBindingQueue).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('checkbox', { name: '已确认前一个用户停止登录，理解迟到会话风险' }));
    expect(button).toBeEnabled();
    fireEvent.click(button);
    await waitFor(() => expect(mocks.recoverSessionBindingQueue).toHaveBeenCalledWith('account-1'));
    await screen.findByText('队列正常');
    expect(screen.getByText(/后台已确认/)).toBeInTheDocument();
    expect(mocks.getSessionBindingQueue).toHaveBeenCalledTimes(1);
  });
  it('blocks recovery while an official login attempt is active', async () => {
    mocks.getSessionBindingQueue.mockResolvedValue({ ...quarantined, activeAttemptId: 'active-attempt' });
    render(<SessionBindingQueue accountId="account-1" />);
    await screen.findByText('登录处理中');
    expect(screen.getByRole('button', { name: '确认风险并恢复队列' })).toBeDisabled();
    expect(screen.getByRole('checkbox')).toBeDisabled();
    expect(mocks.recoverSessionBindingQueue).not.toHaveBeenCalled();
  });
  it('does not recover a healthy queue and offers a manual status refresh', async () => {
    mocks.getSessionBindingQueue.mockResolvedValue(normal);
    render(<SessionBindingQueue accountId="account-1" />);
    await screen.findByText('队列正常');
    expect(screen.queryByRole('button', { name: '确认风险并恢复队列' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '刷新队列状态' }));
    await waitFor(() => expect(mocks.getSessionBindingQueue).toHaveBeenCalledTimes(2));
    expect(mocks.recoverSessionBindingQueue).not.toHaveBeenCalled();
  });
  it('keeps quarantine after a failed fresh snapshot and displays only fixed Chinese errors', async () => {
    mocks.recoverSessionBindingQueue.mockRejectedValue(new Error('upstream-sensitive-cookie'));
    render(<SessionBindingQueue accountId="account-1" />);
    await screen.findByText('需管理员处理');
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认风险并恢复队列' }));
    await screen.findByText('队列恢复未完成，请检查官方连接后刷新状态重试。');
    expect(screen.queryByText('upstream-sensitive-cookie')).not.toBeInTheDocument();
    expect(screen.getByText('需管理员处理')).toBeInTheDocument();
    expect(screen.getByRole('checkbox')).not.toBeChecked();
  });
  it('ignores a late response after switching official accounts', async () => {
    let finish!: (value: unknown) => void;
    mocks.getSessionBindingQueue.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    const view = render(<SessionBindingQueue accountId="account-1" />);
    mocks.getSessionBindingQueue.mockResolvedValue({ ...normal, officialAccountId: 'account-2' });
    view.rerender(<SessionBindingQueue accountId="account-2" />);
    await screen.findByText('队列正常');
    await act(async () => finish(quarantined));
    expect(screen.queryByText('需管理员处理')).not.toBeInTheDocument();
  });
});
