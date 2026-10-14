import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import TeamMembersModal from './TeamMembersModal';
import { listUsers, updateTeamMembers } from '../../services/api';
import type { TeamResponse, UserListResponse, UserResponse } from '../../services/api';

vi.mock('../../services/api', () => ({ listUsers: vi.fn(), updateTeamMembers: vi.fn() }));

const team: TeamResponse = {
  id: 'team-1', name: '研发', description: '', status: 'ACTIVE', memberCount: 2,
  fiveHourQuotaPercent: 100, weeklyQuotaPercent: 100, fiveHourTokenLimit: null,
  weeklyTokenLimit: null, totalTokenLimit: null, officialAccountId: null, version: 1,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
};
const otherTeam = { ...team, id: 'team-2', name: '设计' };
const user = (id: string, teamId: string | null = null): UserResponse => ({
  id, displayName: id, email: `${id}@example.com`, teamId, role: 'USER', status: 'ACTIVE',
  fiveHourQuotaPercent: null, weeklyQuotaPercent: null,
  fiveHourTokenLimit: null, weeklyTokenLimit: null,
  createdAt: team.createdAt, updatedAt: team.updatedAt,
});
const member = user('现有成员', team.id);
const candidate = user('新成员');
const transfer = { ...user('转入成员', otherTeam.id), status: 'DISABLED' as const };
const page = (items: UserResponse[], current = 0, totalElements = items.length): UserListResponse => ({
  items, page: current, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20),
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
const onCancel = vi.fn();
const onSaved = vi.fn();
function modal(target = team) {
  return <AntApp><TeamMembersModal team={target} teams={[team, otherTeam]} onCancel={onCancel} onSaved={onSaved} /></AntApp>;
}
function checkbox(name: string) { return screen.getByRole('checkbox', { name: `选择 ${name}` }); }
async function openConfirmation() {
  fireEvent.click(screen.getByRole('button', { name: '保存成员' }));
  return screen.findByRole('dialog', { name: '确认成员变更' });
}
async function confirm() {
  const dialog = await openConfirmation();
  fireEvent.click(within(dialog).getByRole('button', { name: '确认变更' }));
}

describe('团队成员管理', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(listUsers).mockResolvedValue(page([member, candidate, transfer]));
    vi.mocked(updateTeamMembers).mockResolvedValue({ ...team, memberCount: 3 });
  });

  it('loads only one candidate page and preselects current members with team and status context', async () => {
    render(modal());
    expect(await screen.findByRole('checkbox', { name: '选择 现有成员' })).toBeChecked();
    expect(checkbox(candidate.displayName)).not.toBeChecked();
    expect(listUsers).toHaveBeenCalledExactlyOnceWith({ page: 0, size: 20, q: '' });
    const row = screen.getByText(transfer.email).closest('tr')!;
    expect(within(row).getByText('设计')).toBeInTheDocument();
    expect(within(row).getByText('停用')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存成员' })).toBeDisabled();
  });

  it('saves only explicit deltas and confirms the source team and session consequences', async () => {
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 转入成员' }));
    fireEvent.click(checkbox(member.displayName));
    const dialog = await openConfirmation();
    expect(dialog).toHaveTextContent('转入成员');
    expect(dialog).toHaveTextContent('设计');
    expect(dialog).toHaveTextContent(/个人额度.*历史流水.*不变/);
    expect(dialog).toHaveTextContent(/本系统.*重新登录/);
    expect(dialog).toHaveTextContent(/不会退出官方 Codex/);
    fireEvent.click(within(dialog).getByRole('button', { name: '确认变更' }));
    await waitFor(() => expect(updateTeamMembers).toHaveBeenCalledOnce());
    const changes = vi.mocked(updateTeamMembers).mock.calls[0][1].changes;
    expect(changes).toHaveLength(2);
    expect(changes).toEqual(expect.arrayContaining([
      { userId: transfer.id, expectedTeamId: otherTeam.id, selected: true },
      { userId: member.id, expectedTeamId: team.id, selected: false },
    ]));
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
  });

  it('keeps changes across pagination and search without removing unvisited members', async () => {
    const second = user('第二页成员');
    vi.mocked(listUsers).mockImplementation(async ({ page: current = 0, q = '' } = {}) =>
      q ? page([transfer]) : current === 1 ? page([second], 1, 21) : page([member, candidate], 0, 21));
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    fireEvent.click(screen.getByTitle('2'));
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 第二页成员' }));
    fireEvent.click(screen.getByTitle('1'));
    await waitFor(() => expect(checkbox(candidate.displayName)).toBeChecked());
    const search = screen.getByPlaceholderText('搜索姓名或邮箱');
    fireEvent.change(search, { target: { value: '转入' } });
    fireEvent.keyDown(search, { key: 'Enter', code: 'Enter', keyCode: 13 });
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 转入成员' }));
    await confirm();
    await waitFor(() => expect(updateTeamMembers).toHaveBeenCalledWith(team.id, { changes: expect.arrayContaining([
      { userId: candidate.id, expectedTeamId: null, selected: true },
      { userId: second.id, expectedTeamId: null, selected: true },
      { userId: transfer.id, expectedTeamId: otherTeam.id, selected: true },
    ]) }));
    expect(vi.mocked(updateTeamMembers).mock.calls[0][1].changes).toHaveLength(3);
    expect(listUsers).toHaveBeenLastCalledWith({ page: 0, size: 20, q: '转入' });
  });

  it('drops reversed choices and never writes when cancelled or unchanged', async () => {
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    fireEvent.click(checkbox(candidate.displayName));
    fireEvent.click(checkbox(member.displayName));
    fireEvent.click(checkbox(member.displayName));
    expect(screen.getByRole('button', { name: '保存成员' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '保存成员' }));
    fireEvent.click(checkbox(candidate.displayName));
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(onCancel).toHaveBeenCalledOnce();
    expect(updateTeamMembers).not.toHaveBeenCalled();
  });

  it('can cancel confirmation without saving or losing the pending choices', async () => {
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    const dialog = await openConfirmation();
    fireEvent.click(within(dialog).getByRole('button', { name: /返\s*回/ }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '确认成员变更' })).not.toBeInTheDocument());
    expect(checkbox(candidate.displayName)).toBeChecked();
    expect(updateTeamMembers).not.toHaveBeenCalled();
  });

  it('allows removals from disabled teams while blocking additions', async () => {
    render(modal({ ...team, status: 'DISABLED' }));
    expect(await screen.findByRole('checkbox', { name: '选择 现有成员' })).toBeEnabled();
    expect(checkbox(candidate.displayName)).toBeDisabled();
    expect(checkbox(transfer.displayName)).toBeDisabled();
    fireEvent.click(checkbox(member.displayName));
    await confirm();
    await waitFor(() => expect(updateTeamMembers).toHaveBeenCalledWith(team.id, { changes: [
      { userId: member.id, expectedTeamId: team.id, selected: false },
    ] }));
  });

  it('blocks submission after a candidate load failure until a successful retry', async () => {
    vi.mocked(listUsers).mockResolvedValueOnce(page([member, candidate], 0, 21))
      .mockRejectedValueOnce(new Error('private server details'))
      .mockResolvedValue(page([transfer], 1, 21));
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    fireEvent.click(screen.getByTitle('2'));
    expect(await screen.findByText('用户列表加载失败，请重试后再保存。')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存成员' })).toBeDisabled();
    expect(screen.queryByText('private server details')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重试加载' }));
    await screen.findByText(transfer.email);
    await confirm();
    await waitFor(() => expect(updateTeamMembers).toHaveBeenCalledWith(team.id, { changes: [
      { userId: candidate.id, expectedTeamId: null, selected: true },
    ] }));
  });

  it('requires an explicit refresh and reselection after a membership conflict', async () => {
    vi.mocked(updateTeamMembers).mockRejectedValueOnce(Object.assign(new Error('private details'), {
      status: 409, code: 'TEAM_MEMBERSHIP_CONFLICT',
    }));
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    await confirm();
    expect(await screen.findByText(/用户团队归属已变化.*刷新.*重新选择/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存成员' })).toBeDisabled();
    expect(onSaved).not.toHaveBeenCalled();
    vi.mocked(listUsers).mockResolvedValue(page([{ ...candidate, teamId: otherTeam.id }]));
    fireEvent.click(screen.getByRole('button', { name: '刷新并重新选择' }));
    expect(await screen.findByRole('checkbox', { name: '选择 新成员' })).not.toBeChecked();
    fireEvent.click(checkbox(candidate.displayName));
    await confirm();
    await waitFor(() => expect(updateTeamMembers).toHaveBeenLastCalledWith(team.id, { changes: [
      { userId: candidate.id, expectedTeamId: otherTeam.id, selected: true },
    ] }));
  });

  it('retains choices and shows a safe message on other save failures', async () => {
    vi.mocked(updateTeamMembers).mockRejectedValueOnce(new Error('private database details'));
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    await confirm();
    expect(await screen.findByText('成员保存失败，请稍后重试。')).toBeInTheDocument();
    expect(checkbox(candidate.displayName)).toBeChecked();
    expect(screen.queryByText('private database details')).not.toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('submits a pending save only once even when confirmation is clicked twice', async () => {
    const save = deferred<TeamResponse>();
    vi.mocked(updateTeamMembers).mockReturnValue(save.promise);
    render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    const dialog = await openConfirmation();
    const submit = within(dialog).getByRole('button', { name: '确认变更' });
    fireEvent.click(submit);
    fireEvent.click(submit);
    expect(updateTeamMembers).toHaveBeenCalledOnce();
    await act(async () => { save.resolve(team); });
    expect(onSaved).toHaveBeenCalledOnce();
  });

  it('ignores stale search responses', async () => {
    const stale = deferred<UserListResponse>();
    vi.mocked(listUsers).mockImplementation(({ q = '' } = {}) =>
      q === '旧搜索' ? stale.promise : Promise.resolve(page(q ? [candidate] : [member])));
    render(modal());
    await screen.findByText(member.email);
    const search = screen.getByPlaceholderText('搜索姓名或邮箱');
    fireEvent.change(search, { target: { value: '旧搜索' } });
    fireEvent.keyDown(search, { key: 'Enter', code: 'Enter', keyCode: 13 });
    fireEvent.change(search, { target: { value: '新搜索' } });
    fireEvent.keyDown(search, { key: 'Enter', code: 'Enter', keyCode: 13 });
    await screen.findByText(candidate.email);
    await act(async () => { stale.resolve(page([transfer])); });
    expect(screen.queryByText(transfer.email)).not.toBeInTheDocument();
    expect(screen.getByText(candidate.email)).toBeInTheDocument();
  });

  it('does not carry choices or late requests into another team', async () => {
    const stale = deferred<UserListResponse>();
    vi.mocked(listUsers).mockResolvedValueOnce(page([candidate], 0, 21))
      .mockReturnValueOnce(stale.promise).mockResolvedValue(page([transfer]));
    const view = render(modal());
    fireEvent.click(await screen.findByRole('checkbox', { name: '选择 新成员' }));
    fireEvent.click(screen.getByTitle('2'));
    view.rerender(modal(otherTeam));
    expect(await screen.findByRole('checkbox', { name: '选择 转入成员' })).toBeChecked();
    await act(async () => { stale.resolve(page([candidate], 1, 21)); });
    expect(screen.queryByText(candidate.email)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存成员' })).toBeDisabled();
    fireEvent.click(checkbox(transfer.displayName));
    await confirm();
    await waitFor(() => expect(updateTeamMembers).toHaveBeenCalledWith(otherTeam.id, { changes: [
      { userId: transfer.id, expectedTeamId: otherTeam.id, selected: false },
    ] }));
  });

  it('caps a batch at 100 explicit changes across pages', async () => {
    vi.mocked(listUsers).mockImplementation(async ({ page: current = 0 } = {}) =>
      page(Array.from({ length: current === 5 ? 1 : 20 }, (_, index) => user(`用户${current * 20 + index}`)), current, 101));
    render(modal());
    for (let current = 0; current < 5; current++) {
      await screen.findByText(`用户${current * 20}@example.com`);
      fireEvent.click(screen.getAllByRole('checkbox')[0]);
      fireEvent.click(screen.getByTitle('Next Page'));
    }
    await screen.findByText('用户100@example.com');
    expect(checkbox('用户100')).toBeDisabled();
    await confirm();
    await waitFor(() => expect(updateTeamMembers).toHaveBeenCalledOnce());
    expect(vi.mocked(updateTeamMembers).mock.calls[0][1].changes).toHaveLength(100);
  });
});
