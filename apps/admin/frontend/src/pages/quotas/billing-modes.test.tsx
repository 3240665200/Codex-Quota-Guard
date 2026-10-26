import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
import { BillingModes } from './billing-modes';
import { getUserBillingModes, setUserBillingMode } from '../../services/api';
vi.mock('../../services/api', () => ({ getUserBillingModes: vi.fn(), setUserBillingMode: vi.fn() }));
beforeEach(() => vi.clearAllMocks());
it('saves a device declaration only after explaining its effective time and limits', async () => {
  const row = { deviceId: 'device-1', status: 'ACTIVE' as const, serviceTier: 'unknown' as const, effectiveAt: null };
  vi.mocked(getUserBillingModes).mockResolvedValue([row]);
  vi.mocked(setUserBillingMode).mockResolvedValue([{ ...row, serviceTier: 'fast', effectiveAt: '2026-10-26T00:00:00Z' }]);
  render(<App><BillingModes userId="member-1" /></App>);
  fireEvent.mouseDown(await screen.findByRole('combobox', { name: '设备 device-1 的计费模式' }));
  fireEvent.click(await screen.findByText('快速模式（默认 ×1.5）'));
  await waitFor(() => expect(screen.getByText(/不会切换 Codex 的快速模式/)).toBeVisible());
  expect(setUserBillingMode).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '保存模式' }));
  await waitFor(() => expect(setUserBillingMode).toHaveBeenCalledWith('member-1', 'device-1', 'fast'));
});
it('shows a retryable mode error without substituting a standard billing default', async () => {
  vi.mocked(getUserBillingModes).mockRejectedValue(new Error('读取失败'));
  render(<App><BillingModes userId="member-1" /></App>);
  expect(await screen.findByText('设备模式读取失败')).toBeVisible();
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
});
