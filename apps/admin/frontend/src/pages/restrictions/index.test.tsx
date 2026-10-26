import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp, ConfigProvider } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RestrictionsPage from './index';
import { getRestrictionManagement, saveRestrictionSettings, saveStaleBindingSettings } from '../../services/restrictionsApi';
import type { RestrictionManagement } from '../../services/restrictionsApi';
import routes from '../../../config/routes';

vi.mock('../../services/restrictionsApi', () => ({ getRestrictionManagement: vi.fn(), saveRestrictionSettings: vi.fn(), saveStaleBindingSettings: vi.fn() }));
const state: RestrictionManagement = {
  policy: { personalQuotaLogoutEnabled: false, unboundCleanupEnabled: false, unboundCheckIntervalMinutes: 90,
    personalCheckIntervalMinutes: 1, quotaCheckIntervalSeconds: 60, version: 2, updatedAt: '2026-11-01T00:00:00Z' }, accounts: [], executions: [],
};
function page() { return render(<ConfigProvider theme={{ token: { motion: false } }}><AntApp><RestrictionsPage /></AntApp></ConfigProvider>); }
async function ready() { await waitFor(() => expect(screen.getByRole('button', { name: '保存限制设置' })).toBeEnabled(), { timeout: 5000 }); }
describe('限制管理', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(getRestrictionManagement).mockResolvedValue(state); vi.mocked(saveRestrictionSettings).mockResolvedValue(state); });
  it('exposes a dedicated administrator menu with disabled defaults and a ninety-minute interval', async () => {
    expect(routes).toContainEqual(expect.objectContaining({ path: '/restrictions', name: '限制管理', access: 'canAdmin', component: './restrictions' }));
    page(); await ready();
    expect(screen.getByRole('switch', { name: '个人额度用尽后下线绑定会话' })).not.toBeChecked();
    expect(screen.getByRole('switch', { name: '定时下线未绑定的官方会话' })).not.toBeChecked();
    expect(screen.getByRole('spinbutton', { name: '未绑定会话检查间隔' })).toHaveValue('90');
    expect(screen.getByText(/保留官方标记的当前设备、受信任设备/)).toBeVisible();
    expect(saveRestrictionSettings).not.toHaveBeenCalled();
  });
  it('shows minute-based stale binding cleanup and saves its switch without replaying other unsaved fields', async () => {
    const next = { ...state, staleBindings: { enabled: true, intervalMinutes: 1, lastCheckedAt: null, nextCheckAt: null, removedCount: 4, lastErrorCode: null } };
    vi.mocked(getRestrictionManagement).mockResolvedValue(next);
    vi.mocked(saveStaleBindingSettings).mockResolvedValue({ ...next, policy: { ...state.policy, version: 3 }, staleBindings: { ...next.staleBindings, enabled: false } });
    page(); await ready();
    expect(screen.getByText(/每 1 分钟核对官方会话列表/)).toBeVisible();
    expect(screen.getByText(/累计解除 4 条绑定/)).toBeVisible();
    fireEvent.click(screen.getByRole('switch', { name: '自动清理失效会话绑定' }));
    await waitFor(() => expect(saveStaleBindingSettings).toHaveBeenCalledWith(false, 2));
    await waitFor(() => expect(screen.getByRole('switch', { name: '自动清理失效会话绑定' })).not.toBeChecked());
    expect(saveRestrictionSettings).not.toHaveBeenCalled();
  });
  it('saves independent rule switches and the chosen interval with the last loaded version', async () => {
    page(); await ready();
    fireEvent.click(screen.getByRole('switch', { name: '个人额度用尽后下线绑定会话' }));
    fireEvent.click(screen.getByRole('switch', { name: '定时下线未绑定的官方会话' }));
    await waitFor(() => expect(screen.getByRole('spinbutton', { name: '未绑定会话检查间隔' })).toBeEnabled());
    fireEvent.change(screen.getByRole('spinbutton', { name: '未绑定会话检查间隔' }), { target: { value: '120' } });
    fireEvent.blur(screen.getByRole('spinbutton', { name: '未绑定会话检查间隔' }));
    fireEvent.click(screen.getByRole('button', { name: '保存限制设置' }));
    await waitFor(() => expect(saveRestrictionSettings).toHaveBeenCalledWith({ personalQuotaLogoutEnabled: true, unboundCleanupEnabled: true,
      unboundCheckIntervalMinutes: 120, personalCheckIntervalMinutes: 1, version: 2 }), { timeout: 5000 });
  });
  it('keeps edited switches after a version conflict instead of overwriting another administrator', async () => {
    vi.mocked(saveRestrictionSettings).mockRejectedValue(new Error('限制设置已变化，请刷新后再保存。'));
    page(); await ready(); fireEvent.click(screen.getByRole('switch', { name: '个人额度用尽后下线绑定会话' }));
    fireEvent.click(screen.getByRole('button', { name: '保存限制设置' }));
    expect(await screen.findByText('限制设置已变化，请刷新后再保存。')).toBeVisible();
    expect(screen.getByRole('switch', { name: '个人额度用尽后下线绑定会话' })).toBeChecked();
    expect(getRestrictionManagement).toHaveBeenCalledTimes(1);
  });
  it('does not present failed loads as an empty, editable policy', async () => {
    vi.mocked(getRestrictionManagement).mockRejectedValueOnce(new Error('读取失败'));
    page(); expect(await screen.findByText('读取失败')).toBeVisible();
    expect(screen.getByRole('button', { name: '保存限制设置' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '刷新设置与状态' })); await ready();
    expect(screen.queryByText('读取失败')).not.toBeInTheDocument();
  });
  it('shows uncertain official outcomes as pending verification rather than a successful logout', async () => {
    vi.mocked(getRestrictionManagement).mockResolvedValue({ ...state, executions: [{ id: 'run-1', officialAccountId: 'account-1',
      accountLabel: '测试账户', rule: 'UNBOUND', checkedAt: '2026-11-01T01:30:00Z', status: 'UNKNOWN',
      targetedCount: 1, excludedCount: 4, acceptedCount: 0, unknownCount: 1, errorCode: 'OFFICIAL_LOGOUT_OUTCOME_UNKNOWN' }] });
    page(); await ready();
    expect(screen.getByText('结果待核对')).toBeVisible();
    expect(screen.getByText(/后台不会重复发送/)).toBeVisible();
    expect(screen.queryByText('官方已接受请求')).not.toBeInTheDocument();
  });
});
