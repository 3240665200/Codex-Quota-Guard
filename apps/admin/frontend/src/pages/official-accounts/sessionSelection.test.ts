import { describe, expect, it } from 'vitest';
import type { OfficialSessionDevice } from '../../services/api';
import { sessionSelectionReason } from './sessionSelection';

const device = { renderId: 'row-1', sessionId: 'session-1', displayName: '办公电脑', currentDevice: false,
  trustedDevice: false, applications: [] } as unknown as OfficialSessionDevice;

describe('手动选择官方登出会话', () => {
  it.each([
    [{ ...device, sessionId: null }, '缺少会话 ID'],
    [{ ...device, sessionId: '  ' }, '缺少会话 ID'],
    [{ ...device, sessionId: 's'.repeat(513) }, '会话 ID 过长'],
    [{ ...device, renderId: '' }, '缺少设备行 ID'],
    [{ ...device, currentDevice: true }, '当前官方管理会话'],
    [{ ...device, currentDevice: null }, '当前会话身份未知'],
    [{ ...device, currentDevice: undefined }, '当前会话身份未知'],
    [{ ...device, trustedDevice: null }, '受信任状态未知'],
    [{ ...device, trustedDevice: undefined }, '受信任状态未知'],
  ] as Array<[OfficialSessionDevice, string]>)('explains why an unsafe device cannot be selected', (value, reason) => {
    expect(sessionSelectionReason(value, [value])).toBe(reason);
  });

  it('rejects duplicate official Session IDs and device row IDs', () => {
    expect(sessionSelectionReason(device, [device, { ...device, renderId: 'row-2' }])).toBe('会话 ID 重复');
    expect(sessionSelectionReason(device, [device, { ...device, sessionId: 'session-2' }])).toBe('设备行 ID 重复');
  });

  it('allows a distinct non-current session, including explicitly selected trusted devices', () => {
    expect(sessionSelectionReason(device, [device])).toBeNull();
    const trusted = { ...device, trustedDevice: true };
    expect(sessionSelectionReason(trusted, [trusted])).toBeNull();
  });
});
