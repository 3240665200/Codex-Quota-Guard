import { Alert, Button, Input, Modal, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Key } from 'react';

import { listUsers, updateTeamMembers } from '../../services/api';
import type { TeamMemberChange, TeamResponse, UserListResponse, UserResponse } from '../../services/api';

const PAGE_SIZE = 20;
const MAX_CHANGES = 100;

interface Props {
  team: TeamResponse;
  teams: TeamResponse[];
  onCancel: () => void;
  onSaved: () => void;
}

interface PendingChange extends TeamMemberChange {
  displayName: string;
  email: string;
}

// A separate editor lifetime prevents late responses or selections crossing teams.
export default function TeamMembersModal(props: Props) {
  return <TeamMembersEditor key={props.team.id} {...props} />;
}

function TeamMembersEditor({ team, teams, onCancel, onSaved }: Props) {
  const [data, setData] = useState<UserListResponse | null>(null);
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const [searchText, setSearchText] = useState('');
  const [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [conflict, setConflict] = useState<'membership' | 'team' | null>(null);
  const [pending, setPending] = useState<Map<string, PendingChange>>(() => new Map());
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const saveInFlight = useRef(false);
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    let current = true;
    setLoading(true);
    setLoadError(false);
    void listUsers({ page, size: PAGE_SIZE, q: query }).then((response) => {
      if (current) setData(response);
    }).catch(() => {
      if (current) {
        setData(null);
        setLoadError(true);
      }
    }).finally(() => {
      if (current) setLoading(false);
    });
    return () => { current = false; };
  }, [page, query, reload]);

  const users = data?.items ?? [];
  const changes = [...pending.values()];
  const blocked = loading || loadError || conflict !== null || saving;
  const canSave = !blocked && pending.size > 0 && pending.size <= MAX_CHANGES;
  const teamNames = useMemo(() => new Map(teams.map((item) => [item.id, item.name])), [teams]);
  const teamName = (id: string | null) => id ? teamNames.get(id) ?? `未知团队（${id}）` : '未加入团队';
  const isSelected = (user: UserResponse) => pending.get(user.id)?.selected ?? user.teamId === team.id;
  const selectedRowKeys = [...new Set([
    ...users.filter(isSelected).map((user) => user.id),
    ...changes.filter((change) => change.selected).map((change) => change.userId),
  ])];

  function changeSelection(keys: Key[]) {
    if (blocked || confirmOpen) return;
    const selected = new Set(keys);
    setPending((previous) => {
      const next = new Map(previous);
      const edited = users.filter((user) => selected.has(user.id)
        !== (previous.get(user.id)?.selected ?? user.teamId === team.id));

      // Reversals free capacity before new changes in a page-wide selection.
      for (const user of edited) {
        const existing = previous.get(user.id);
        const originalTeamId = existing ? existing.expectedTeamId : user.teamId;
        if (selected.has(user.id) === (originalTeamId === team.id)) next.delete(user.id);
      }
      for (const user of edited) {
        const existing = previous.get(user.id);
        const expectedTeamId = existing ? existing.expectedTeamId : user.teamId;
        const checked = selected.has(user.id);
        if (checked === (expectedTeamId === team.id)) continue;
        if (team.status === 'DISABLED' && checked && expectedTeamId !== team.id) continue;
        if (!next.has(user.id) && next.size >= MAX_CHANGES) continue;
        next.set(user.id, {
          userId: user.id, expectedTeamId, selected: checked,
          displayName: user.displayName, email: user.email,
        });
      }
      return next;
    });
    setSaveError('');
  }

  async function save() {
    if (!canSave || !confirmOpen || saveInFlight.current) return;
    saveInFlight.current = true;
    setSaving(true);
    setSaveError('');
    try {
      await updateTeamMembers(team.id, {
        changes: changes.map(({ userId, expectedTeamId, selected }) => ({ userId, expectedTeamId, selected })),
      });
      if (mounted.current) onSaved();
    } catch (error) {
      if (!mounted.current) return;
      const status = error && typeof error === 'object' && 'status' in error ? error.status : undefined;
      const code = error && typeof error === 'object' && 'code' in error ? error.code : undefined;
      if (status === 409) {
        setConflict(code === 'TEAM_MEMBERSHIP_CONFLICT' ? 'membership' : 'team');
        setSaveError(code === 'TEAM_MEMBERSHIP_CONFLICT'
          ? '用户团队归属已变化，本次未保存。请刷新并重新选择；刷新将清除待保存选择。'
          : '团队状态已变化，本次未保存。请关闭弹窗，刷新团队列表后重新选择。');
      } else {
        setSaveError(status === 401 ? '管理员登录已失效，请重新登录。'
          : status === 403 ? '当前账户没有管理员权限。'
            : status === 404 ? '团队已不存在，请关闭弹窗并刷新团队列表。'
              : '成员保存失败，请稍后重试。');
      }
    } finally {
      saveInFlight.current = false;
      if (mounted.current) {
        setSaving(false);
        setConfirmOpen(false);
      }
    }
  }

  const columns: TableColumnsType<UserResponse> = [
    { title: '用户', key: 'user', render: (_, user) => <Space orientation="vertical" size={0}>
      <Typography.Text strong>{user.displayName}</Typography.Text>
      <Typography.Text type="secondary">{user.email}</Typography.Text>
    </Space> },
    { title: '当前团队', dataIndex: 'teamId', render: (id: string | null) => teamName(id) },
    { title: '角色', dataIndex: 'role', width: 90, render: (role: string) => role === 'ADMIN' ? '管理员' : '用户' },
    { title: '状态', dataIndex: 'status', width: 90, render: (status: string) =>
      <Tag color={status === 'ACTIVE' ? 'success' : 'default'}>{status === 'ACTIVE' ? '启用' : '停用'}</Tag> },
  ];

  return <>
    <Modal
      open
      title={`管理成员：${team.name}`}
      width={860}
      style={{ top: 24 }}
      styles={{ body: { maxHeight: 'calc(100dvh - 180px)', overflowY: 'auto' } }}
      cancelText="取消"
      okText="保存成员"
      okButtonProps={{ disabled: !canSave || confirmOpen }}
      cancelButtonProps={{ disabled: saving }}
      closable={!saving}
      keyboard={!saving}
      mask={{ closable: false }}
      onCancel={() => { if (!saveInFlight.current) onCancel(); }}
      onOk={() => { if (canSave) setConfirmOpen(true); }}
    >
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          勾选加入本团队，取消勾选移出本团队。翻页和搜索会保留待保存选择，每次最多变更 100 人。
        </Typography.Paragraph>
        {team.status === 'DISABLED' && <Alert type="warning" showIcon title="团队已停用，仅可移出现有成员。" />}
        <Input.Search
          allowClear
          aria-label="搜索姓名或邮箱"
          placeholder="搜索姓名或邮箱"
          enterButton="搜索"
          value={searchText}
          disabled={saving || confirmOpen || conflict !== null}
          onChange={(event) => setSearchText(event.target.value)}
          onSearch={(value) => {
            setPage(0);
            setQuery(value.trim());
          }}
        />
        {loadError && <Alert type="error" showIcon title="用户列表加载失败，请重试后再保存。"
          action={<Button onClick={() => setReload((value) => value + 1)}>重试加载</Button>} />}
        {saveError && <Alert type="error" showIcon title={saveError}
          action={conflict === 'membership' ? <Button onClick={() => {
            setPending(new Map());
            setConflict(null);
            setSaveError('');
            setReload((value) => value + 1);
          }}>刷新并重新选择</Button> : conflict === 'team' ? <Button onClick={onCancel}>关闭</Button> : undefined} />}
        <Typography.Text aria-live="polite">
          待保存 {pending.size} / {MAX_CHANGES} 人：加入 {changes.filter((change) => change.selected).length} 人，
          移出 {changes.filter((change) => !change.selected).length} 人
        </Typography.Text>
        <Table<UserResponse>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={users}
          loading={loading}
          scroll={{ x: 660 }}
          rowSelection={{
            selectedRowKeys,
            preserveSelectedRowKeys: true,
            onChange: changeSelection,
            getCheckboxProps: (user) => ({
              'aria-label': `选择 ${user.displayName}`,
              disabled: blocked || confirmOpen
                || (team.status === 'DISABLED' && user.teamId !== team.id)
                || (pending.size >= MAX_CHANGES && !pending.has(user.id)),
            }),
          }}
          pagination={{
            current: page + 1,
            pageSize: PAGE_SIZE,
            total: data?.totalElements ?? 0,
            showSizeChanger: false,
            showTotal: (total) => `共 ${total} 位用户`,
            disabled: loading || saving || conflict !== null || confirmOpen,
            onChange: (nextPage) => setPage(nextPage - 1),
          }}
        />
      </Space>
    </Modal>
    <Modal
      open={confirmOpen}
      title="确认成员变更"
      okText="确认变更"
      cancelText="返回"
      confirmLoading={saving}
      cancelButtonProps={{ disabled: saving }}
      closable={!saving}
      keyboard={!saving}
      mask={{ closable: false }}
      onCancel={() => { if (!saveInFlight.current) setConfirmOpen(false); }}
      onOk={() => void save()}
    >
      <Typography.Paragraph>
        个人额度和历史流水保持不变。受影响用户的本系统会话将失效，需要重新登录；不会退出官方 Codex。
      </Typography.Paragraph>
      <ul style={{ maxHeight: 280, overflowY: 'auto', paddingInlineStart: 22 }}>
        {changes.map((change) => <li key={change.userId}>
          <Typography.Text strong>{change.displayName}</Typography.Text>（{change.email}）：
          {change.selected
            ? change.expectedTeamId
              ? `从「${teamName(change.expectedTeamId)}」转入「${team.name}」`
              : `加入「${team.name}」`
            : `从「${team.name}」移出，变为未加入团队`}
        </li>)}
      </ul>
    </Modal>
  </>;
}
