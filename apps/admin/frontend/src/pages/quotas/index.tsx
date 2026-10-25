import { ReloadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App as AntApp, Button, Input, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { QuotaAmount } from '../../components/QuotaAmount';
import { getTeamQuotaOverview, getUserQuotaOverview, listTeams, listUsers, resetTeamQuota } from '../../services/api';
import type { TeamQuotaOverview, UserListResponse, UserQuotaOverview, UserResponse } from '../../services/api';

const PAGE_SIZE = 20;
async function readUserQuotas(users: UserResponse[], isCurrent: () => boolean) {
  const pending = [...users];
  const result: Record<string, UserQuotaOverview | null> = {};
  // Reuse measured user usage, with a bounded number of requests per page.
  await Promise.all(Array.from({ length: Math.min(4, users.length) }, async () => {
    for (let user = pending.shift(); user && isCurrent(); user = pending.shift()) {
      try { result[user.id] = await getUserQuotaOverview(user.id); }
      catch { result[user.id] = null; }
    }
  }));
  return result;
}

function teamColumns(onReset: (team: TeamQuotaOverview) => Promise<void>,
  resettingTeamId: string | null): TableColumnsType<TeamQuotaOverview> { return [
  { title: '团队', dataIndex: 'name', width: 220, render: (name: string, row) => (
    <Space size={8}><Typography.Text strong>{name}</Typography.Text>
      <Tag>{row.memberCount} 人</Tag></Space>
  ) },
  { title: '状态', dataIndex: 'status', width: 90,
    render: (status: string) => <Tag color={status === 'ACTIVE' ? 'success' : 'default'}>
      {status === 'ACTIVE' ? '启用' : '停用'}</Tag> },
  { title: '5 小时共享额度', width: 230, render: (_, row) => (
    <QuotaAmount label={`${row.name} 5 小时团队`} used={row.fiveHourUsedTokens} limit={row.fiveHourTokenLimit}
      remaining={row.fiveHourRemainingTokens} />
  ) },
  { title: '7 天共享额度', width: 230, render: (_, row) => (
    <QuotaAmount label={`${row.name} 7 天团队`} used={row.weeklyUsedTokens} limit={row.weeklyTokenLimit}
      remaining={row.weeklyRemainingTokens} />
  ) },
  { title: '累计总额', width: 230, render: (_, row) => (
    <QuotaAmount label={`${row.name}累计总额`} used={row.totalUsedTokens} limit={row.totalTokenLimit}
      remaining={row.totalRemainingTokens} />
  ) },
  { title: '最后上报', dataIndex: 'measuredAt', width: 170,
    render: (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '暂无' },
  { title: '操作', key: 'action', width: 130, render: (_, row) => (
    <Popconfirm title={`重置「${row.name}」的共享额度？`}
      description="成员的个人用量不变；历史流水保留，重置前事件不再计入团队额度。"
      okText="确认重置" cancelText="取消" onConfirm={() => onReset(row)}>
      <Button type="link" loading={resettingTeamId === row.id}>重置团队额度</Button>
    </Popconfirm>
  ) },
]; }

function userColumns(quotas: Record<string, UserQuotaOverview | null>): TableColumnsType<UserResponse> { return [
  { title: '系统用户', dataIndex: 'displayName', width: 260, render: (name: string, row) => (
    <Space orientation="vertical" size={0}><Typography.Text strong>{name}</Typography.Text>
      <Typography.Text type="secondary">{row.email}</Typography.Text></Space>
  ) },
  { title: '5 小时个人额度', width: 250, render: (_, row) => quotas[row.id] ?
    <QuotaAmount label={`${row.displayName} 5 小时个人`} used={quotas[row.id]!.usage.fiveHourUsedTokens}
      limit={quotas[row.id]!.policy.fiveHourTokenLimit} remaining={quotas[row.id]!.usage.fiveHourRemainingTokens} />
    : <Typography.Text type="secondary">额度暂不可得</Typography.Text> },
  { title: '7 天个人额度', width: 250, render: (_, row) => quotas[row.id] ?
    <QuotaAmount label={`${row.displayName} 7 天个人`} used={quotas[row.id]!.usage.weeklyUsedTokens}
      limit={quotas[row.id]!.policy.weeklyTokenLimit} remaining={quotas[row.id]!.usage.weeklyRemainingTokens} />
    : <Typography.Text type="secondary">额度暂不可得</Typography.Text> },
  { title: '操作', key: 'action', width: 125, render: (_, row) => (
    <Button type="link" onClick={() => history.push(`/quotas/users/${encodeURIComponent(row.id)}`)}>
      查看额度
    </Button>
  ) },
]; }

export default function QuotasPage() {
  const { message } = AntApp.useApp();
  const [teams, setTeams] = useState<TeamQuotaOverview[]>([]);
  const [resettingTeamId, setResettingTeamId] = useState<string | null>(null);
  const [users, setUsers] = useState<UserListResponse | null>(null);
  const [userQuotas, setUserQuotas] = useState<Record<string, UserQuotaOverview | null>>({});
  const requestVersion = useRef(0);
  const activeRequest = useRef<number | null>(null);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (quiet = false) => {
    if (quiet && activeRequest.current !== null) return;
    const version = ++requestVersion.current;
    activeRequest.current = version;
    if (!quiet) setLoading(true);
    setError('');
    try {
      const [teamList, userList] = await Promise.all([
        listTeams(), listUsers({ page, size: PAGE_SIZE, q: query }),
      ]);
      if (version !== requestVersion.current) return;
      const [teamUsage, quotaUsage] = await Promise.all([
        Promise.all(teamList.map((team) => getTeamQuotaOverview(team.id))), readUserQuotas(userList.items, () => version === requestVersion.current),
      ]);
      if (version !== requestVersion.current) return;
      setTeams(teamUsage);
      setUsers(userList);
      setUserQuotas(quotaUsage);
    } catch (reason) {
      if (version === requestVersion.current) setError(getErrorMessage(reason));
    } finally {
      if (activeRequest.current === version) activeRequest.current = null;
      if (version === requestVersion.current) setLoading(false);
    }
  }, [page, query]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(true); }, 30_000);
    return () => { requestVersion.current++; window.clearInterval(timer); };
  }, [load]);

  const resetTeam = async (team: TeamQuotaOverview) => {
    setResettingTeamId(team.id);
    try {
      await resetTeamQuota(team.id);
      await load();
      message.success(`已重置「${team.name}」的团队共享额度`);
    } catch (reason) {
      message.error(`重置失败：${getErrorMessage(reason)}`);
    } finally {
      setResettingTeamId(null);
    }
  };

  return (
    <PageContainer title="额度查看" content="个人与团队额度分别统计；团队累计总额从上次重置后持续累加。"
      extra={<Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>刷新</Button>}>
      {error ? <Alert showIcon type="error" title="额度加载失败" description={error}
        action={<Button onClick={() => void load()}>重试</Button>} /> : null}
      <section aria-label="团队额度" style={{ marginBottom: 28 }}>
        <Typography.Title level={4}>团队额度</Typography.Title>
        <Table<TeamQuotaOverview> rowKey="id"
          columns={teamColumns(resetTeam, resettingTeamId)} dataSource={teams}
          loading={loading} pagination={false} scroll={{ x: 1300 }}
          locale={{ emptyText: '暂无团队' }} />
      </section>
      <section aria-label="用户额度">
        <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}>
          <Typography.Title level={4} style={{ margin: 0 }}>用户额度</Typography.Title>
          <Input.Search placeholder="搜索用户" aria-label="搜索用户" allowClear onSearch={(value) => {
            setPage(0); setQuery(value.trim());
          }} style={{ width: 240 }} />
        </Space>
        <Table<UserResponse> rowKey="id" columns={userColumns(userQuotas)} dataSource={users?.items ?? []}
          loading={loading} scroll={{ x: 900 }} locale={{ emptyText: '暂无用户' }}
          pagination={{ current: page + 1, pageSize: PAGE_SIZE,
            total: users?.totalElements ?? 0, showSizeChanger: false,
            onChange: (next) => setPage(next - 1) }} />
      </section>
    </PageContainer>
  );
}
