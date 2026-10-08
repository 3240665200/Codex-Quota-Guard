import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getUserUsageEvents } from '../../services/api';
import { UsageEventsTable } from './usage-events';

vi.mock('../../services/api', () => ({ getUserUsageEvents: vi.fn() }));

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
