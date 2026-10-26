import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { confirmUsageBillingMode, getUserUsageEvents } from '../../services/api';
import { UsageEventsTable } from './usage-events';

vi.mock('../../services/api', () => ({ getUserUsageEvents: vi.fn(), confirmUsageBillingMode: vi.fn() }));

function page(index: number, tokens: number, totalElements: number) {
  return {
    items: [{ id: `event-${index}`, receivedAt: '2026-10-07T00:00:00Z',
      occurredAt: '2026-10-07T00:00:00Z', totalTokens: tokens,
      source: 'TOKEN_USAGE_RECORD' as const }],
    page: index, size: 10, totalElements, totalPages: Math.ceil(totalElements / 10),
  };
}

describe('Token 用量流水', () => {
  beforeEach(() => vi.clearAllMocks());

  it('adds model and reasoning effort columns while keeping existing fields', async () => {
    const result = page(0, 42, 1);
    vi.mocked(getUserUsageEvents).mockResolvedValue({ ...result,
      items: [{ ...result.items[0], model: 'gpt-6.1-sol', reasoningEffort: 'max',
        inputTokens: 30, cachedInputTokens: 10, cacheWriteInputTokens: 0, outputTokens: 12,
        reasoningOutputTokens: 5, estimatedCostUsd: 0.000155, costStatus: 'PRICED' }] });
    render(<AntApp><UsageEventsTable userId="user-1" /></AntApp>);
    expect(await screen.findByText('gpt-6.1-sol')).toBeInTheDocument();
    expect(screen.getByText('max')).toBeInTheDocument();
    for (const heading of ['服务端收到', '本机发生', 'Token', '来源', '模型名称', '思考等级']) {
      expect(screen.getAllByText(heading).length).toBeGreaterThan(0);
    }
    expect(screen.getByText('42 Token')).toBeInTheDocument();
    expect(screen.getByText('30 Token')).toBeInTheDocument();
    expect(screen.getByText('USD 0.000155')).toBeInTheDocument();
  });

  it('labels legacy missing metadata as unknown rather than guessing', async () => {
    vi.mocked(getUserUsageEvents).mockResolvedValue(page(0, 42, 1));
    render(<AntApp><UsageEventsTable userId="user-1" /></AntApp>);
    await screen.findByText('42 Token');
    expect(screen.getAllByText('未知')).toHaveLength(2);
  });

  it('confirms only the explicitly selected unbilled record and refreshes the ledger', async () => {
    vi.mocked(getUserUsageEvents).mockResolvedValue({ ...page(0, 42, 1), items: [{
      ...page(0, 42, 1).items[0], billingModeStatus: 'UNRECORDED', costStatus: 'MISSING_SERVICE_TIER', estimatedCostUsd: null,
    }] });
    vi.mocked(confirmUsageBillingMode).mockResolvedValue({ updated: 1 });
    render(<AntApp><UsageEventsTable userId="user-1" /></AntApp>);
    await screen.findByText('待确认速度模式');
    expect(screen.getByRole('button', { name: '按快速模式确认' })).toBeDisabled();
    fireEvent.click(screen.getAllByRole('checkbox')[1]);
    fireEvent.click(screen.getByRole('button', { name: '按快速模式确认' }));
    expect(confirmUsageBillingMode).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: '确认并核算' }));
    await waitFor(() => expect(confirmUsageBillingMode).toHaveBeenCalledWith(['event-0'], 'fast'));
    await waitFor(() => expect(getUserUsageEvents).toHaveBeenCalledTimes(2));
  });

  it('does not allow confirmation of an already priced record', async () => {
    vi.mocked(getUserUsageEvents).mockResolvedValue({ ...page(0, 42, 1), items: [{
      ...page(0, 42, 1).items[0], billingModeStatus: 'UNRECORDED', costStatus: 'PRICED', estimatedCostUsd: 0.01,
    }] });
    render(<AntApp><UsageEventsTable userId="user-1" /></AntApp>);
    await screen.findByText('USD 0.01');
    expect(screen.getAllByRole('checkbox')[1]).toBeDisabled();
    expect(screen.getByRole('button', { name: '按普通模式确认' })).toBeDisabled();
  });

  it('pages accepted events without switching users', async () => {
    vi.mocked(getUserUsageEvents)
      .mockResolvedValueOnce(page(0, 42, 11))
      .mockResolvedValueOnce(page(1, 7, 11));
    render(<AntApp><UsageEventsTable userId="user-1" /></AntApp>);

    expect(await screen.findByText('42 Token')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('Next Page'));
    await waitFor(() => expect(getUserUsageEvents).toHaveBeenCalledWith('user-1', 1, 10));
    expect(await screen.findByText('7 Token')).toBeInTheDocument();
  });

  it('shows a safe error and retries without exposing raw records', async () => {
    vi.mocked(getUserUsageEvents)
      .mockRejectedValueOnce(new Error('暂时不可读取'))
      .mockResolvedValueOnce({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    render(<AntApp><UsageEventsTable userId="user-1" /></AntApp>);

    expect(await screen.findByText('用量流水读取失败')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '刷新流水' }));
    expect(await screen.findByText('暂无已接收的 Token 事件')).toBeInTheDocument();
  });

  it('does not mislabel a previous page as the current page after a request fails', async () => {
    vi.mocked(getUserUsageEvents)
      .mockResolvedValueOnce(page(0, 42, 11))
      .mockRejectedValueOnce(new Error('暂时不可读取'));
    render(<AntApp><UsageEventsTable userId="user-1" /></AntApp>);

    expect(await screen.findByText('42 Token')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('Next Page'));
    expect(await screen.findByText('用量流水读取失败')).toBeInTheDocument();
    expect(screen.queryByText('42 Token')).not.toBeInTheDocument();
  });
});
