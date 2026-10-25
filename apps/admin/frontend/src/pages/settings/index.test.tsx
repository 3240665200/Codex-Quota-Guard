import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import SettingsPage from './index';
import { QuotaAmount } from '../../components/QuotaAmount';
import { quotaDisplayStorageKey, quotaPercent, setQuotaDisplayMode } from '../../services/quota-display';

beforeEach(() => { setQuotaDisplayMode('remaining'); localStorage.clear(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setQuotaDisplayMode('remaining'); });
describe('额度显示设置', () => {
  it('defaults to remaining and saves a single selected display mode across remounts', () => {
    const view = render(<><SettingsPage /><QuotaAmount label="个人" used={25} remaining={75} limit={100} /></>);
    expect(screen.getByRole('radio', { name: '剩余' })).toBeChecked();
    expect(screen.getByRole('progressbar', { name: '个人剩余额度' })).toHaveAttribute('aria-valuenow', '75');
    fireEvent.click(screen.getByRole('radio', { name: '已用' }));
    expect(screen.getByRole('radio', { name: '剩余' })).not.toBeChecked();
    expect(screen.getByRole('progressbar', { name: '个人已用额度' })).toHaveAttribute('aria-valuenow', '25');
    expect(localStorage.getItem(quotaDisplayStorageKey)).toBe('used');
    view.unmount(); render(<SettingsPage />);
    expect(screen.getByRole('radio', { name: '已用' })).toBeChecked();
  });
  it('updates when another browser tab changes or clears the preference', () => {
    render(<SettingsPage />);
    act(() => {
      localStorage.setItem(quotaDisplayStorageKey, 'used');
      window.dispatchEvent(new StorageEvent('storage', { key: quotaDisplayStorageKey, newValue: 'used' }));
    });
    expect(screen.getByRole('radio', { name: '已用' })).toBeChecked();
    act(() => { localStorage.clear(); window.dispatchEvent(new StorageEvent('storage', { key: null })); });
    expect(screen.getByRole('radio', { name: '剩余' })).toBeChecked();
  });
  it('keeps the setting usable when browser storage is blocked and handles empty or exhausted quotas', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    render(<SettingsPage />);
    fireEvent.click(screen.getByRole('radio', { name: '已用' }));
    expect(screen.getByRole('radio', { name: '已用' })).toBeChecked();
    expect(quotaPercent('remaining', 120, 0, 100)).toBe(0);
    expect(quotaPercent('used', 120, 0, 100)).toBe(100);
    expect(quotaPercent('used', 0, 0, 0)).toBe(100);
    expect(quotaPercent('remaining', 0, 0, 0)).toBe(0);
    expect(quotaPercent('used', 1, null, null)).toBeNull();
    expect(quotaPercent('remaining', 0, null, 100)).toBeNull();
  });
});
