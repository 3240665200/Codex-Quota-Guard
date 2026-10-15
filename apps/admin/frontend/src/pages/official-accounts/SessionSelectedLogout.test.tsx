import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SessionLogoutPanel from './SessionLogoutPanel';

const mocks = vi.hoisted(() => ({ previewSessionLogout: vi.fn(), confirmSessionLogout: vi.fn(),
  getSessionLogoutCurrent: vi.fn(), acknowledgeSessionLogoutUnknown: vi.fn() }));
vi.mock('../../services/sessionLogoutApi', () => mocks);
const device = { rowId: 'row-1', sessionId: 'session-1', displayName: '选中的已绑定电脑', platform: 'web',
  trustedDevice: true, current: false };
const plan = { planId: 'selected-plan', officialAccountId: 'selected-account', mode: 'SELECTED',
  expiresAt: new Date(Date.now() + 120_000).toISOString(), targets: [device], excluded: [],
  warnings: ['SELECTED_SESSIONS_ONLY', 'SELECTED_BOUND_SESSION_ACCESS_REVOKED', 'TRUSTED_DEVICE_SESSION_ONLY'] };

async function previewSelected() {
  const button = screen.getByRole('button', { name: '登出选中会话（1）' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  return screen.findByRole('region', { name: '登出预览' });
}

describe('登出选中会话预览与确认', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getSessionLogoutCurrent.mockResolvedValue({ operation: null });
    mocks.previewSessionLogout.mockResolvedValue(plan);
    mocks.confirmSessionLogout.mockResolvedValue({ planId: plan.planId, mode: 'SELECTED', status: 'ACCEPTED',
      requiresReauthorization: false, results: [{ sessionId: 'session-1', status: 'ACCEPTED', errorCode: null }], message: '' });
  });

  it('disables the selected operation until at least one session is explicitly selected', async () => {
    render(<SessionLogoutPanel accountId="selected-account" selectedSessionIds={[]} />);
    expect(screen.getByRole('button', { name: '登出选中会话（0）' })).toBeDisabled();
    expect(mocks.previewSessionLogout).not.toHaveBeenCalled();
  });

  it('previews only the selected scope and explicitly warns about binding and trust before confirmation', async () => {
    render(<SessionLogoutPanel accountId="selected-account" selectedSessionIds={['session-1']} />);
    await previewSelected();
    expect(mocks.previewSessionLogout).toHaveBeenCalledExactlyOnceWith('selected-account', 'SELECTED', ['session-1']);
    expect(screen.getByText('仅登出本次明确选中的会话')).toBeInTheDocument();
    expect(screen.getByText(/对应客户端需要重新绑定.*只能使用登录辅助/)).toBeInTheDocument();
    expect(screen.getByText(/不会解除设备信任/)).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: '确认登出选中会话' });
    expect(submit).toBeDisabled();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('checkbox', { name: /我已核对预览/ }));
    fireEvent.click(submit);
    await waitFor(() => expect(mocks.confirmSessionLogout).toHaveBeenCalledExactlyOnceWith('selected-account', 'selected-plan', 'SELECTED'));
  });

  it('discards an acknowledged selected preview when the selection changes', async () => {
    const view = render(<SessionLogoutPanel accountId="selected-account" selectedSessionIds={['session-1']} />);
    await previewSelected();
    fireEvent.click(screen.getByRole('checkbox', { name: /我已核对预览/ }));
    view.rerender(<SessionLogoutPanel accountId="selected-account" selectedSessionIds={['session-2']} />);
    expect(screen.queryByRole('region', { name: '登出预览' })).not.toBeInTheDocument();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
  });

  it('ignores an old selected preview arriving after selection or snapshot context changed', async () => {
    let finish!: (value: typeof plan) => void;
    mocks.previewSessionLogout.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const view = render(<SessionLogoutPanel accountId="selected-account" contextVersion="snapshot-1" selectedSessionIds={['session-1']} />);
    const button = screen.getByRole('button', { name: '登出选中会话（1）' });
    await waitFor(() => expect(button).toBeEnabled()); fireEvent.click(button);
    view.rerender(<SessionLogoutPanel accountId="selected-account" contextVersion="snapshot-2" selectedSessionIds={['session-2']} />);
    await act(async () => finish(plan));
    expect(screen.queryByRole('region', { name: '登出预览' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '确认登出选中会话' })).not.toBeInTheDocument();
    expect(mocks.confirmSessionLogout).not.toHaveBeenCalled();
  });
});
