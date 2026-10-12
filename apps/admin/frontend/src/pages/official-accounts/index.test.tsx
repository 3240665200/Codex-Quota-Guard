import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import OfficialAccountsPage from './index';
import { createOfficialAccount, listOfficialAccounts } from '../../services/api';

vi.mock('../../services/api', () => ({
  createOfficialAccount: vi.fn(),
  deleteOfficialAccount: vi.fn(),
  listOfficialAccounts: vi.fn(),
  updateOfficialAccount: vi.fn(),
}));

describe('官方账户只读登记页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listOfficialAccounts).mockResolvedValue([{
      id: 'account-1', label: '研发账户', estimatedTotalTokens: 1_000_000,
      reporterEmail: 'alice@example.com', createdAt: '2026-10-08T00:00:00Z',
      updatedAt: '2026-10-08T00:00:00Z', version: 0,
      quota: { planType: 'plus', fetchedAt: '2026-10-08T01:00:00Z',
        receivedAt: '2026-10-08T01:00:01Z', windows: [
          { limitId: 'codex', slot: 'secondary', windowDurationMins: 10_080,
            usedPercent: 40, resetsAt: null },
        ] },
    }]);
  });

  it('shows a recent official snapshot without exposing credential controls', async () => {
    render(<AntApp><OfficialAccountsPage /></AntApp>);
    expect(await screen.findByText('研发账户')).toBeInTheDocument();
    expect(screen.getByText('7 天剩余 60%')).toBeInTheDocument();
    expect(screen.queryByText('5 小时剩余')).not.toBeInTheDocument();
    expect(screen.getByText(/不托管官方账户会话凭证/)).toBeInTheDocument();
  });

  it('registers an account estimate and reporting user, not a credential', async () => {
    vi.mocked(listOfficialAccounts).mockResolvedValue([]);
    vi.mocked(createOfficialAccount).mockResolvedValue({} as never);
    render(<AntApp><OfficialAccountsPage /></AntApp>);
    fireEvent.click(await screen.findByRole('button', { name: /添加官方账户/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '账户名称' }), {
      target: { value: '团队账户' },
    });
    fireEvent.change(screen.getByRole('spinbutton', { name: '估算总 Token' }), {
      target: { value: '1000000' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: '上报用户的系统邮箱' }), {
      target: { value: 'alice@example.com' },
    });
    expect(screen.queryByLabelText(/凭证|密码|Token JSON/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(createOfficialAccount).toHaveBeenCalledWith({
      label: '团队账户', estimatedTotalTokens: 1_000_000,
      reporterEmail: 'alice@example.com',
    }));
  });
});
