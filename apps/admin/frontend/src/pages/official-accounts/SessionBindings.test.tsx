import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SessionBindingResult, SessionBindingsTable } from './SessionBindings';
import type { SessionBinding } from '../../services/sessionBindingsApi';

const binding: SessionBinding = {
  id: 'binding-1', userId: 'user-1', userDisplayName: '测试成员', userEmail: 'member@example.test',
  deviceId: 'device-1', enrollmentId: 'enrollment-1', officialAccountId: 'official-1',
  sessionId: 'session-1', status: 'ACTIVE', matchMethod: 'SERIAL_LOGIN_DIFF',
  verificationLevel: 'USER_CONFIRMED_INFERENCE', createdAt: '2026-10-15T00:00:00Z', endedAt: null,
};

describe('官方设备会话绑定结果', () => {
  it('shows the exact active account and session match with its inference level', () => {
    render(<SessionBindingResult officialAccountId="official-1" sessionId="session-1" bindings={[binding]} />);
    expect(screen.getByText('已绑定')).toBeInTheDocument();
    expect(screen.getByText('测试成员')).toBeInTheDocument();
    expect(screen.getByText('设备：device-1')).toBeInTheDocument();
    expect(screen.getByText('用户确认的推断')).toBeInTheDocument();
  });

  it.each([
    { ...binding, officialAccountId: 'other-account' },
    { ...binding, sessionId: 'other-session' },
    { ...binding, status: 'ENDED' as const },
    { ...binding, status: 'INVALIDATED' as const },
  ])('never infers a current binding from another account, session, or history', (other) => {
    render(<SessionBindingResult officialAccountId="official-1" sessionId="session-1" bindings={[other]} />);
    expect(screen.getByText('暂无当前绑定')).toBeInTheDocument();
    expect(screen.queryByText('测试成员')).not.toBeInTheDocument();
  });

  it('does not match a missing official Session ID', () => {
    render(<SessionBindingResult officialAccountId="official-1" sessionId={null} bindings={[binding]} />);
    expect(screen.getByText('官方未提供 Session ID，无法匹配')).toBeInTheDocument();
  });

  it('shows conflicting exact matches without selecting a user', () => {
    render(<SessionBindingResult officialAccountId="official-1" sessionId="session-1"
      bindings={[binding, { ...binding, id: 'binding-2', userId: 'user-2' }]} />);
    expect(screen.getByText('绑定记录冲突，请检查')).toBeInTheDocument();
    expect(screen.queryByText('测试成员')).not.toBeInTheDocument();
  });

  it('shows binding history and makes the verification limit visible', () => {
    render(<SessionBindingsTable bindings={[binding,
      { ...binding, id: 'binding-2', status: 'ENDED', endedAt: '2026-10-15T01:00:00Z' },
      { ...binding, id: 'binding-3', status: 'INVALIDATED' }]} />);
    expect(screen.getByText('设备会话绑定记录')).toBeInTheDocument();
    expect(screen.getByText('已绑定')).toBeInTheDocument();
    expect(screen.getByText('已结束')).toBeInTheDocument();
    expect(screen.getByText('已失效')).toBeInTheDocument();
    expect(screen.getByText(/绑定来自串行登录前后会话差异与用户确认/)).toBeInTheDocument();
  });

  it('explains when no device has completed binding', () => {
    render(<SessionBindingsTable bindings={[]} />);
    expect(screen.getByText('尚无设备会话绑定记录')).toBeInTheDocument();
  });
});
