import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import OfficialSessionsPage from './sessions';
const list = vi.hoisted(() => vi.fn());
vi.mock('../../services/api', () => ({ listOfficialAccounts: list }));
vi.mock('@ant-design/pro-components', () => ({ PageContainer: ({ title, children }: { title: string; children: ReactNode }) => <main><h1>{title}</h1>{children}</main> }));
vi.mock('antd', () => ({
  Space: ({ children }: { children: ReactNode }) => <div>{children}</div>, Typography: { Text: ({ children }: { children: ReactNode }) => <span>{children}</span> },
  Button: ({ children, onClick }: { children: ReactNode; onClick: () => void }) => <button onClick={onClick}>{children}</button>,
  Empty: ({ description }: { description: string }) => <p>{description}</p>, Alert: ({ description }: { description: string }) => <p>{description}</p>,
  Select: ({ options, onChange, value, ...props }: { options: { value: string; label: string }[]; onChange: (value: string) => void; value: string }) => <select aria-label={(props as Record<string, string>)['aria-label']} value={value || ''} onChange={(event) => onChange(event.target.value)}><option value="">请选择账户</option>{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select>,
}));
vi.mock('./ConnectionModal', () => ({ default: ({ account, view, inline }: { account: { id: string }; view: string; inline: boolean }) => <section aria-label="会话工作区" data-inline={String(inline)} data-view={view}>{account.id}</section> }));
describe('独立会话管理页', () => {
  beforeEach(() => { list.mockResolvedValue([{ id: 'one', label: '账户一' }, { id: 'two', label: '账户二' }]); });
  it('selects an account and embeds the existing safe session workspace without a list modal', async () => {
    render(<OfficialSessionsPage />); await screen.findByText('账户一');
    expect(screen.queryByLabelText('会话工作区')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('选择官方账户'), { target: { value: 'one' } });
    expect(screen.getByLabelText('会话工作区')).toHaveAttribute('data-inline', 'true');
    expect(screen.getByLabelText('会话工作区')).toHaveAttribute('data-view', 'sessions');
    fireEvent.change(screen.getByLabelText('选择官方账户'), { target: { value: 'two' } });
    expect(screen.getByLabelText('会话工作区')).toHaveTextContent('two');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
