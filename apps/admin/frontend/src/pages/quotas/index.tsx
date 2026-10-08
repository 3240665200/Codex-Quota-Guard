import { ReloadOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App as AntApp, Button, Input, Popconfirm, Progress, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { TokenAmount } from '../../components/TokenAmount';
import { getTeamQuotaOverview, listTeams, listUsers, resetTeamQuota } from '../../services/api';
import type { TeamQuotaOverview, UserListResponse, UserResponse } from '../../services/api';

const PAGE_SIZE = 20;
function QuotaCell({ used, limit, remaining }: {
  used: number; limit: number | null; remaining: number | null;
}) {
  if (limit === null) return <Typography.Text type="secondary">不设限</Typography.Text>;
  return (
    <div style={{ minWidth: 165 }}>
      <div>已用 <TokenAmount value={used} /></div>
      <div>上限 <TokenAmount value={limit} /></div>
      <Progress percent={limit === 0 ? 100 : Math.min(100, Math.round(used / limit * 100))}
        size="small" showInfo={false} status={remaining === 0 ? 'exception' : 'normal'} />
      <Typography.Text type={remaining === 0 ? 'danger' : 'secondary'}>
        剩余 <TokenAmount value={remaining} />
      </Typography.Text>
    </div>
  );
}

function teamColumns(onReset: (team: TeamQuotaOverview) => Promise<void>,
  resettingTeamId: string | null): TableColumnsType<TeamQuotaOverview> { return [
  { title: '团队', dataIndex: 'name', render: (name: string, row) => (
    <Space size={8}><Typography.Text strong>{name}</Typography.Text>
      <Tag>{row.memberCount} 人</Tag></Space>
  ) },
  { title: '状态', dataIndex: 'status', width: 90,
    render: (status: string) => <Tag color={status === 'ACTIVE' ? 'success' : 'default'}>
      {status === 'ACTIVE' ? '启用' : '停用'}</Tag> },
  { title: '5 小时共享额度', width: 230, render: (_, row) => (
    <QuotaCell used={row.fiveHourUsedTokens} limit={row.fiveHourTokenLimit}
      remaining={row.fiveHourRemainingTokens} />
  ) },
  { title: '7 天共享额度', width: 230, render: (_, row) => (
    <QuotaCell used={row.weeklyUsedTokens} limit={row.weeklyTokenLimit}
      remaining={row.weeklyRemainingTokens} />
  ) },
  { title: '累计总额', width: 230, render: (_, row) => (
    <QuotaCell used={row.totalUsedTokens} limit={row.totalTokenLimit}
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

const userColumns: TableColumnsType<UserResponse> = [
  { title: '系统用户', dataIndex: 'displayName', render: (name: string, row) => (
    <Space orientation="vertical" size={0}><Typography.Text strong>{name}</Typography.Text>
      <Typography.Text type="secondary">{row.email}</Typography.Text></Space>
  ) },
  { title: '5 小时个人上限', dataIndex: 'fiveHourTokenLimit',
    render: (value: number | null) => value === null ? '不设限' : <TokenAmount value={value} /> },
  { title: '7 天个人上限', dataIndex: 'weeklyTokenLimit',
    render: (value: number | null) => value === null ? '不设限' : <TokenAmount value={value} /> },
  { title: '操作', key: 'action', width: 125, render: (_, row) => (
    <Button type="link" onClick={() => history.push(`/quotas/users/${encodeURIComponent(row.id)}`)}>
      查看额度
    </Button>
  ) },
];

export default function QuotasPage() {
  const { message } = AntApp.useApp();
  const [teams, setTeams] = useState<TeamQuotaOverview[]>([]);
  const [resettingTeamId, setResettingTeamId] = useState<string | null>(null);
  const [users, setUsers] = useState<UserListResponse | null>(null);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    setError('');
    try {
      const [teamList, userList] = await Promise.all([
        listTeams(), listUsers({ page, size: PAGE_SIZE, q: query }),
      ]);
      const teamUsage = await Promise.all(teamList.map((team) => getTeamQuotaOverview(team.id)));
      setTeams(teamUsage);
      setUsers(userList);
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [page, query]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(true); }, 30_000);
    return () => window.clearInterval(timer);
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
          loading={loading} pagination={false} scroll={{ x: 1120 }}
          locale={{ emptyText: '暂无团队' }} />
      </section>
      <section aria-label="用户额度">
        <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}>
          <Typography.Title level={4} style={{ margin: 0 }}>用户额度</Typography.Title>
          <Input.Search placeholder="搜索用户" aria-label="搜索用户" allowClear onSearch={(value) => {
            setPage(0); setQuery(value.trim());
          }} style={{ width: 240 }} />
        </Space>
        <Table<UserResponse> rowKey="id" columns={userColumns} dataSource={users?.items ?? []}
          loading={loading} scroll={{ x: 720 }} locale={{ emptyText: '暂无用户' }}
          pagination={{ current: page + 1, pageSize: PAGE_SIZE,
            total: users?.totalElements ?? 0, showSizeChanger: false,
            onChange: (next) => setPage(next - 1) }} />
      </section>
    </PageContainer>
  );
}
