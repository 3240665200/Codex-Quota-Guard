import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { history, useParams } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App as AntApp, Button, Descriptions, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { QuotaAmount } from '../../components/QuotaAmount';
import { useQuotaDisplay } from '../../services/quota-display';
import { getUser, getUserMeterDevices, getUserOfficialQuota,
  getUserQuotaOverview } from '../../services/api';
import { resetTeamQuota, resetUserQuota } from '../../services/api';
import type { MeterDeviceStatus, OfficialQuotaSnapshot, UserQuotaOverview,
  UserResponse } from '../../services/api';
import { UsageEventsTable } from '../users/usage-events';
import { BillingModes } from './billing-modes';

function describeLimitReason(reason: string): string {
  switch (reason) {
    case 'BILLING_PENDING': return '部分用量待核算；请检查价格、Token 分项和速度模式，未知费用不计为 0 美元';
    case 'OFFICIAL_ACCOUNT_USD_QUOTA_EXCEEDED': return '关联官方账户的美元预算已用尽';
    case 'TEAM_FIVE_HOUR_USD_QUOTA_EXCEEDED': return '团队 5 小时共享额度已用尽';
    case 'TEAM_WEEKLY_USD_QUOTA_EXCEEDED': return '团队 7 天共享额度已用尽';
    case 'TEAM_TOTAL_USD_QUOTA_EXCEEDED': return '团队累计总额度已用尽';
    case 'FIVE_HOUR_USD_QUOTA_EXCEEDED': return '个人 5 小时额度已用尽';
    case 'WEEKLY_USD_QUOTA_EXCEEDED': return '个人 7 天额度已用尽';
    case 'TEAM_UNAVAILABLE': return '所属团队不可用';
    default: return '当前额度不可用';
  }
}

function TokenWindow({ label, quota }: {
  label: string; quota?: import('../../services/usd-types').UsdWindow;
}) {
  return (
    <div style={{ flex: '1 1 260px', minWidth: 0 }}>
      <Typography.Text strong>{label}</Typography.Text>
      <div style={{ marginTop: 8 }}><QuotaAmount label={`${label} USD`} quota={quota} /></div>
    </div>
  );
}

const deviceColumns: TableColumnsType<MeterDeviceStatus> = [
  { title: '设备', dataIndex: 'deviceId', ellipsis: true },
  { title: '状态', render: (_, row) => <Tag color={row.online ? 'success' : 'default'}>
    {row.online ? '在线' : row.status === 'REVOKED' ? '已撤销' : '离线'}</Tag> },
  { title: '最后心跳', dataIndex: 'lastSeenAt',
    render: (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '暂无' },
];

export function UserQuotaDetail({ userId }: { userId: string }) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const { message } = AntApp.useApp();
  const [user, setUser] = useState<UserResponse | null>(null);
  const [overview, setOverview] = useState<UserQuotaOverview | null>(null);
  const [official, setOfficial] = useState<OfficialQuotaSnapshot | null>(null);
  const [devices, setDevices] = useState<MeterDeviceStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [resettingQuota, setResettingQuota] = useState<'personal' | 'team' | null>(null);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    setError('');
    try {
      const [userResult, quotaResult, officialResult, deviceResult] = await Promise.all([
        getUser(userId), getUserQuotaOverview(userId),
        getUserOfficialQuota(userId), getUserMeterDevices(userId),
      ]);
      setUser(userResult);
      setOverview(quotaResult);
      setOfficial(officialResult);
      setDevices(deviceResult);
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(true); }, 30_000);
    return () => window.clearInterval(timer);
  }, [load]);

  const resetQuota = async (scope: 'personal' | 'team', id: string) => {
    setResettingQuota(scope);
    try {
      if (scope === 'personal') await resetUserQuota(id);
      else await resetTeamQuota(id);
      await load();
      message.success(scope === 'personal' ? '个人额度已重置' : '团队共享额度已重置');
    } catch (reason) {
      message.error(`重置失败：${getErrorMessage(reason)}`);
    } finally {
      setResettingQuota(null);
    }
  };

  const team = overview?.teamQuota;
  return (
    <PageContainer title={user ? `${user.displayName}的额度` : '用户额度'}
      content={user?.email}
      extra={<Space>
        <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/quotas')}>返回额度查看</Button>
        <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>刷新</Button>
        <Popconfirm title="重置个人额度？"
          description="团队共享用量不变；历史流水保留，重置前事件不再计入个人额度。"
          okText="确认重置" cancelText="取消" onConfirm={() => resetQuota('personal', userId)}>
          <Button loading={resettingQuota === 'personal'} disabled={resettingQuota === 'team'}>重置个人额度</Button>
        </Popconfirm>
      </Space>}>
      {error ? <Alert showIcon title="额度读取失败" description={error} type="error"
        action={<Button onClick={() => void load()}>重试</Button>} /> : null}
      {loading ? <Typography.Text>正在读取额度…</Typography.Text> : null}
      {user && overview ? <div style={{ display: 'grid', gap: 28 }}>
        <section aria-label="个人用量">
          <Typography.Title level={4}>个人额度（USD）</Typography.Title>
          <Typography.Paragraph type="secondary">
            来自本机计费上报；这里的剩余值仅对应个人上限，团队限制在下方单独显示。
          </Typography.Paragraph>
          <Space align="start" size={32} wrap style={{ width: '100%' }}>
            <TokenWindow label="5 小时" quota={overview.billing?.personal.fiveHour} />
            <TokenWindow label="7 天" quota={overview.billing?.personal.weekly} />
          </Space>
          {overview.blockReason ? <Alert style={{ marginTop: 16 }} showIcon type="warning"
            title="额度受限" description={describeLimitReason(overview.blockReason)} /> : null}
        </section>
        <section aria-label="团队共享额度">
          <Typography.Title level={4}>团队共享额度</Typography.Title>
          {team ? <>
            <Space style={{ width: '100%', justifyContent: 'space-between' }}>
              <Typography.Paragraph type="secondary">{team.name} · {team.memberCount} 位成员合计</Typography.Paragraph>
              <Popconfirm title="重置团队额度？"
                description="全体成员的个人用量不变；历史流水保留，重置前事件不再计入团队额度。"
                okText="确认重置" cancelText="取消" onConfirm={() => resetQuota('team', team.id)}>
                <Button size="small" loading={resettingQuota === 'team'} disabled={resettingQuota === 'personal'}>重置团队额度</Button>
              </Popconfirm>
            </Space>
            <Space align="start" size={32} wrap style={{ width: '100%' }}>
              <TokenWindow label="5 小时" quota={overview.billing?.team?.fiveHour} />
              <TokenWindow label="7 天" quota={overview.billing?.team?.weekly} />
              <TokenWindow label="累计总额" quota={overview.billing?.team?.total} />
            </Space>
          </> : <Typography.Text type="secondary">未加入团队</Typography.Text>}
        </section>
        <section aria-label="官方账户美元额度">
          <Typography.Title level={4}>官方账户额度（USD）</Typography.Title>
          <TokenWindow label={overview.billing?.officialAccount?.label ?? '官方账户'} quota={overview.billing?.officialAccount?.quota.total} />
        </section>
        <section aria-label="官方额度">
          <Typography.Title level={4}>官方套餐参考（百分比）</Typography.Title>
          {official ? <Descriptions column={{ xs: 1, sm: 2, md: 3 }} size="small" bordered items={[
            { key: 'plan', label: '套餐', children: official.planType ?? '未知' },
            ...official.windows.filter((item) => item.windowDurationMins === 300).map((item) => ({
              key: 'five', label: '官方 5 小时', children: `${displayLabel} ${mode === 'remaining' ? Math.max(0, 100 - item.usedPercent) : item.usedPercent}%`,
            })),
            ...official.windows.filter((item) => item.windowDurationMins === 10_080).map((item) => ({
              key: 'week', label: '官方 7 天', children: `${displayLabel} ${mode === 'remaining' ? Math.max(0, 100 - item.usedPercent) : item.usedPercent}%`,
            })),
            { key: 'at', label: '客户端最近上报', children: new Date(official.receivedAt).toLocaleString('zh-CN') },
          ]} /> : <Typography.Text type="secondary">暂无客户端上报的官方额度</Typography.Text>}
        </section>
        <section aria-label="统计程序">
          <Typography.Title level={4}>统计程序</Typography.Title>
          <Table<MeterDeviceStatus> rowKey="deviceId" size="small" columns={deviceColumns}
            dataSource={devices} pagination={false} locale={{ emptyText: '未配对设备' }} />
        </section>
        <BillingModes key={`mode-${userId}`} userId={userId} />
        <UsageEventsTable key={userId} userId={userId} />
      </div> : null}
    </PageContainer>
  );
}

export default function UserQuotaPage() {
  const { id } = useParams<{ id: string }>();
  return id ? <UserQuotaDetail userId={id} /> : <Alert showIcon type="error" title="缺少用户 ID" />;
}
