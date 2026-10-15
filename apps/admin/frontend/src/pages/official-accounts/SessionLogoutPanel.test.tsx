import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SessionLogoutPanel from './SessionLogoutPanel';

const mocks = vi.hoisted(() => ({ previewSessionLogout: vi.fn(), confirmSessionLogout: vi.fn() }));
vi.mock('../../services/sessionLogoutApi', () => mocks);
const device = { rowId: 'row-1', sessionId: 'session-1', displayName: '待登出电脑', platform: 'web',
  trustedDevice: false, current: false };
const plan = (mode = 'UNBOUND') => ({ planId: `plan-${mode}`, officialAccountId: 'account-1', mode,
  expiresAt: new Date(Date.now() + 120_000).toISOString(), targets: [device],
  excluded: [{ session: { ...device, rowId: 'row-2', sessionId: 'bound-1', displayName: '保留电脑' }, reason: 'ACTIVE_BINDING' }],
  warnings: ['OFFICIAL_PROPAGATION_DELAY'] });
const result = (status = 'ACCEPTED') => ({ planId: 'plan-UNBOUND', mode: 'UNBOUND', status,
  requiresReauthorization: false, results: [{ sessionId: 'session-1', status, errorCode: null }], message: 'fixture-secret-message' });
const deferred = <T,>() => { let resolve!: (value: T) => void; let reject!: (value: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
async function preview(mode = 'UNBOUND') {
  fireEvent.click(screen.getByRole('button', { name: mode === 'ALL' ? '登出所有会话' : '登出所有未绑定会话', exact: true }));
  return screen.findByRole('region', { name: '登出预览' });
}
async function confirm() {
  fireEvent.click(screen.getByRole('checkbox', { name: /我已核对预览/ }));
  fireEvent.click(screen.getByRole('button', { name: '确认登出所有未绑定会话', exact: true }));
}

describe('官方会话登出面板', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.previewSessionLogout.mockImplementation(async (_account: string, mode: string) => plan(mode));
    mocks.confirmSessionLogout.mockResolvedValue(result());
  });

  it('waits for an explicit preview and shows the fresh target list and exclusion reasons', async () => {
    render(<SessionLogoutPanel accountId="account-1" />);
    expect(mocks.previewSessionLogout).not.toHaveBeenCalled();
    const region = await preview();
    expect(mocks.previewSessionLogout).toHaveBeenCalledWith('account-1', 'UNBOUND');
    expect(within(region).getByText('待登出电脑')).toBeInTheDocument();
    expect(within(region).getByText('保留电脑')).toBeInTheDocument();
    expect(within(region).getByText('已有有效绑定')).toBeInTheDocument();
    expect(within(region).getByText('目标 1 个，排除 1 个')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认登出所有未绑定会话' })).toBeDisabled();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
  });

  it('warns that ALL includes trusted, bound, and current official sessions but preserves the management login', async () => {
    render(<SessionLogoutPanel accountId="account-1" />);
    await preview('ALL');
    expect(screen.getByText(/包括已绑定、受信任及当前官方管理会话/)).toBeInTheDocument();
    expect(screen.getByText(/不会退出本系统管理账户/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认登出所有会话', exact: true })).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox', { name: /我已核对预览/ }));
    fireEvent.click(screen.getByRole('button', { name: '确认登出所有会话', exact: true }));
    await waitFor(() => expect(mocks.confirmSessionLogout).toHaveBeenCalledWith('account-1', 'plan-ALL', 'ALL'));
  });

  it('consumes the plan synchronously, prevents duplicate submission, and refreshes after execution', async () => {
    const pending = deferred<ReturnType<typeof result>>();
    mocks.confirmSessionLogout.mockReturnValue(pending.promise);
    const onSettled = vi.fn(); const onBusyChange = vi.fn();
    render(<SessionLogoutPanel accountId="account-1" onSettled={onSettled} onBusyChange={onBusyChange} />);
    await preview(); await confirm();
    const button = screen.getByRole('button', { name: /正在提交登出/ });
    fireEvent.click(button); fireEvent.click(button);
    expect(mocks.confirmSessionLogout).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/关闭窗口不会取消/)).toBeInTheDocument();
    expect(onBusyChange).toHaveBeenLastCalledWith(true);
    await act(async () => pending.resolve(result()));
    expect(onSettled).toHaveBeenCalledWith('account-1');
    expect(onBusyChange).toHaveBeenLastCalledWith(false);
    expect(screen.getByText(/官方已接受登出请求/)).toBeInTheDocument();
    expect(screen.getByText(/实际下线可能延迟/)).toBeInTheDocument();
    expect(screen.queryByText('fixture-secret-message')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it.each(['PARTIAL', 'UNKNOWN', 'FAILED'])('never describes %s as complete success or retries it', async (status) => {
    mocks.confirmSessionLogout.mockResolvedValue({ ...result(status), results: [
      { sessionId: 'session-1', status: 'ACCEPTED', errorCode: null },
      { sessionId: 'session-2', status: 'UNKNOWN', errorCode: 'fixture-private-code' },
      { sessionId: 'session-3', status: 'NOT_ATTEMPTED', errorCode: null },
    ] });
    render(<SessionLogoutPanel accountId="account-1" />);
    await preview(); await confirm();
    expect(await screen.findByText(/已接受 1 个，失败 0 个，结果未知 1 个，未尝试 1 个/)).toBeInTheDocument();
    expect(screen.getByText(/请核对后重新预览；不会自动重试/)).toBeInTheDocument();
    expect(screen.queryByText('登出成功')).not.toBeInTheDocument();
    expect(screen.queryByText('fixture-private-code')).not.toBeInTheDocument();
    expect(mocks.confirmSessionLogout).toHaveBeenCalledTimes(1);
  });

  it('invalidates a changed context or expired plan and requires another preview', async () => {
    mocks.confirmSessionLogout.mockRejectedValue({ code: 'OFFICIAL_LOGOUT_CONTEXT_CHANGED', message: 'fixture-private-message' });
    render(<SessionLogoutPanel accountId="account-1" />);
    await preview(); await confirm();
    expect(await screen.findByText(/条件已变化，本次未执行，请重新预览/)).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByText('fixture-private-message')).not.toBeInTheDocument();
    await preview();
    expect(screen.getByRole('checkbox', { name: /我已核对预览/ })).not.toBeChecked();
  });

  it('does not submit a plan that is already expired', async () => {
    mocks.previewSessionLogout.mockResolvedValue({ ...plan(), expiresAt: new Date(Date.now() - 1_000).toISOString() });
    render(<SessionLogoutPanel accountId="account-1" />);
    await preview();
    expect(screen.getByText(/预览已过期，请重新预览/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认登出所有未绑定会话' })).toBeDisabled();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
  });

  it('clears the previous plan and acknowledgement when switching modes', async () => {
    render(<SessionLogoutPanel accountId="account-1" />);
    await preview();
    fireEvent.click(screen.getByRole('checkbox', { name: /我已核对预览/ }));
    await preview('ALL');
    expect(screen.getByRole('checkbox', { name: /我已核对预览/ })).not.toBeChecked();
    expect(screen.getByRole('button', { name: '确认登出所有会话', exact: true })).toBeDisabled();
  });

  it('ignores a late preview after switching accounts and clears all old confirmation state', async () => {
    const pending = deferred<ReturnType<typeof plan>>(); mocks.previewSessionLogout.mockReturnValueOnce(pending.promise);
    const view = render(<SessionLogoutPanel accountId="account-1" />);
    fireEvent.click(screen.getByRole('button', { name: '登出所有未绑定会话', exact: true }));
    view.rerender(<SessionLogoutPanel accountId="account-2" />);
    await act(async () => pending.resolve(plan()));
    expect(screen.queryByRole('region', { name: '登出预览' })).not.toBeInTheDocument();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '登出所有会话', exact: true })).toBeEnabled();
  });

  it('clears a preview when the saved authorization version changes', async () => {
    const view = render(<SessionLogoutPanel accountId="account-1" contextVersion={3} />);
    await preview();
    view.rerender(<SessionLogoutPanel accountId="account-1" contextVersion={4} />);
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
  });

  it('preserves an issued request after unmount and refreshes only the original account', async () => {
    const pending = deferred<ReturnType<typeof result>>(); mocks.confirmSessionLogout.mockReturnValue(pending.promise);
    const onSettled = vi.fn();
    const view = render(<SessionLogoutPanel accountId="account-1" onSettled={onSettled} />);
    await preview(); await confirm(); view.unmount();
    await act(async () => pending.resolve(result()));
    expect(onSettled).toHaveBeenCalledWith('account-1');
    expect(mocks.confirmSessionLogout).toHaveBeenCalledTimes(1);
  });

  it('treats a lost execution response as unknown and still reloads safe state', async () => {
    mocks.confirmSessionLogout.mockRejectedValue(new Error('fixture-secret-network'));
    const onSettled = vi.fn();
    render(<SessionLogoutPanel accountId="account-1" onSettled={onSettled} />);
    await preview(); await confirm();
    expect(await screen.findByText(/请求结果未知，请核对后重新预览；不会自动重试/)).toBeInTheDocument();
    expect(onSettled).toHaveBeenCalledWith('account-1');
    expect(mocks.confirmSessionLogout).toHaveBeenCalledTimes(1);
  });

  it('disables confirmation when no target exists or the parent is busy', async () => {
    mocks.previewSessionLogout.mockResolvedValue({ ...plan(), targets: [] });
    const view = render(<SessionLogoutPanel accountId="account-1" />);
    await preview();
    fireEvent.click(screen.getByRole('checkbox', { name: /我已核对预览/ }));
    expect(screen.getByRole('button', { name: '确认登出所有未绑定会话' })).toBeDisabled();
    view.rerender(<SessionLogoutPanel accountId="account-1" disabled />);
    expect(screen.getByRole('button', { name: '登出所有会话', exact: true })).toBeDisabled();
  });
});
