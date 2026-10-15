import type { OfficialSessionDevice } from '../../services/api';

export function sessionSelectionReason(device: OfficialSessionDevice, devices: OfficialSessionDevice[]): string | null {
  if (!device.sessionId?.trim()) return '缺少会话 ID';
  if (device.sessionId.length > 512) return '会话 ID 过长';
  if (devices.filter((item) => item.sessionId === device.sessionId).length > 1) return '会话 ID 重复';
  if (!device.renderId?.trim()) return '缺少设备行 ID';
  if (devices.filter((item) => item.renderId === device.renderId).length > 1) return '设备行 ID 重复';
  if (device.currentDevice === true) return '当前官方管理会话';
  if (device.currentDevice !== false) return '当前会话身份未知';
  if (device.trustedDevice !== true && device.trustedDevice !== false) return '受信任状态未知';
  return null;
}
