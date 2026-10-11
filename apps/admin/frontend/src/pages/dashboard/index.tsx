import {
  CheckCircleOutlined,
  FireOutlined,
  LineChartOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Empty, Progress, Segmented, Space, Table, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip as ChartTooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { ErrorState, getErrorMessage, LoadingState } from '../../components/AdminState';
import { DashboardStatCard } from '../../components/DashboardStatCard';
import { TokenAmount } from '../../components/TokenAmount';
import { UsageCost } from '../../components/UsageAmounts';
import { getDashboard, getUsageAnalytics } from '../../services/api';
import type { DashboardStats, UsageAnalytics, UsageLedgerItem, UsageStats } from '../../services/api';
import { formatTokenAmount } from '../../utils/token';
import { averageRate, dashboardUsageQueries, hourlyRatePoints } from './dashboard-data';

type RateMode = 'QPS' | 'TPS';

function EventRateChart({ data, mode }: { data: UsageAnalytics; mode: RateMode }) {
  const points = useMemo(() => hourlyRatePoints(data), [data]);
  const dataKey = mode === 'QPS' ? 'eventQps' : 'tokenTps';
  const label = mode === 'QPS' ? '事件 / 秒' : 'Token / 秒';
  const color = mode === 'QPS' ? '#4a9d9a' : '#bd8b47';

  return data.summary.eventCount === 0 ? (
    <Empty description="近 24 小时暂无用量事件" />
  ) : (
    <div className="cqg-dashboard-chart" role="img" aria-label={`近 24 小时${mode}趋势图`}>
      <ResponsiveContainer width="100%" height="100%" minWidth={0}>
        <AreaChart data={points} margin={{ top: 12, right: 16, bottom: 4, left: 2 }}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} />
          <XAxis dataKey="label" minTickGap={28} tick={{ fontSize: 11 }} />
          <YAxis width={68} tick={{ fontSize: 11 }} tickFormatter={(value: number) => mode === 'TPS'
            ? value >= 10_000
              ? formatTokenAmount(value).replace(' Token', '')
              : new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(value)
            : new Intl.NumberFormat('en-US', { maximumFractionDigits: 3 }).format(value)} />
          <ChartTooltip
            labelFormatter={(_, payload) => `${payload[0]?.payload.bucket ?? ''} (UTC)`}
            formatter={(value) => [mode === 'QPS'
              ? `${new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 }).format(Number(value))} 事件 / 秒`
              : `${new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 }).format(Number(value))} Token / 秒`, label]}
          />
          <Area dataKey={dataKey} type="monotone" stroke={color} fill={color} fillOpacity={0.14}
            strokeWidth={2} connectNulls={false} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

function modelColumns(totalTokens: number): TableColumnsType<UsageAnalytics['models'][number]> {
  return [
    { title: '模型', dataIndex: 'label', ellipsis: true, render: (value: string) => <Typography.Text strong>{value}</Typography.Text> },
    {
      title: 'Token', width: 132,
      render: (_, item) => <Space orientation="vertical" size={3} className="cqg-dashboard-model-usage">
        <TokenAmount value={item.stats.totalTokens} />
        <Progress aria-label={`${item.label} Token 占比`} size="small" showInfo={false}
          percent={totalTokens > 0 ? Math.min(100, item.stats.totalTokens / totalTokens * 100) : 0}
          strokeColor="#4a9d9a" />
      </Space>,
    },
    { title: '事件', dataIndex: ['stats', 'eventCount'], width: 82, align: 'right', render: (value: number) => value.toLocaleString() },
  ];
}

const recentEventColumns: TableColumnsType<UsageLedgerItem> = [
  {
    title: '时间', dataIndex: 'occurredAt', width: 180,
    render: (value: string) => new Intl.DateTimeFormat('zh-CN', {
      dateStyle: 'long', timeStyle: 'medium', hourCycle: 'h23',
    }).format(new Date(value)),
  },
  {
    title: '用户 / 模型', width: 220,
    render: (_, item) => <Space orientation="vertical" size={2} className="cqg-dashboard-event-model">
      <Typography.Text>{item.userName ?? '已删除用户'}</Typography.Text>
      <Typography.Text type="secondary" ellipsis={{ tooltip: item.model ?? '未知模型' }}>{item.model ?? '未知模型'}</Typography.Text>
    </Space>,
  },
  { title: '思考', dataIndex: 'reasoningEffort', width: 82, render: (value: string | null) => value ?? '—' },
  { title: 'Token', dataIndex: 'totalTokens', width: 130, align: 'right', render: (value: number) => <TokenAmount value={value} /> },
  { title: '估算费用', dataIndex: 'estimatedCostUsd', width: 130, align: 'right', render: (value: number | null) => <UsageCost value={value} /> },
];

function UsageBreakdown({ stats }: { stats: UsageStats }) {
  const parts: Array<[string, number | null | undefined]> = [
    ['输入', stats.inputTokens],
    ['缓存读', stats.cachedInputTokens],
    ['缓存写', stats.cacheWriteInputTokens],
    ['输出', stats.outputTokens],
    ['思考', stats.reasoningOutputTokens],
  ];

  return (
    <div className="cqg-dashboard-breakdown" aria-label="近 24 小时用量明细">
      {parts.map(([label, value]) => <div key={label}><span>{label}</span><strong><TokenAmount value={value} /></strong></div>)}
      <div><span>缓存命中</span><strong>{stats.cacheHitRatePercent == null ? '—' : `${stats.cacheHitRatePercent}%`}</strong></div>
      <div><span>估算费用</span><strong><UsageCost value={stats.estimatedCostUsd} /></strong></div>
      <div><span>计价覆盖</span><strong>{stats.costedEventCount.toLocaleString()} / {stats.eventCount.toLocaleString()} 条</strong></div>
    </div>
  );
}

export default function DashboardPage() {
  const [data, setData] = useState<DashboardStats | null>(null);
  const [last24Hours, setLast24Hours] = useState<UsageAnalytics | null>(null);
  const [lastFiveMinutes, setLastFiveMinutes] = useState<UsageAnalytics | null>(null);
  const [dashboardError, setDashboardError] = useState('');
  const [usageError, setUsageError] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [rateMode, setRateMode] = useState<RateMode>('QPS');

  useEffect(() => {
    let active = true;
    const load = async () => {
      setRefreshing(true);
      const { lastFiveMinutes: recentQuery, last24Hours: dayQuery } = dashboardUsageQueries();
      const [dashboardResult, dayResult, recentResult] = await Promise.allSettled([
        getDashboard(),
        getUsageAnalytics(dayQuery),
        getUsageAnalytics(recentQuery),
      ]);
      if (!active) return;

      if (dashboardResult.status === 'fulfilled') {
        setData(dashboardResult.value);
        setDashboardError('');
      } else {
        setDashboardError(getErrorMessage(dashboardResult.reason));
      }

      const usageFailures: string[] = [];
      if (dayResult.status === 'fulfilled') setLast24Hours(dayResult.value);
      else usageFailures.push(`近 24 小时：${getErrorMessage(dayResult.reason)}`);
      if (recentResult.status === 'fulfilled') setLastFiveMinutes(recentResult.value);
      else usageFailures.push(`近 5 分钟：${getErrorMessage(recentResult.reason)}`);
      setUsageError(usageFailures.join('；'));
      setLoading(false);
      setRefreshing(false);
    };

    void load();
    return () => { active = false; };
  }, [refreshKey]);

  useEffect(() => {
    const timer = window.setInterval(() => setRefreshKey((value) => value + 1), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const recentEvents = last24Hours?.events.items ?? [];
  const models = last24Hours?.models ?? [];
  const modelCols = useMemo(() => modelColumns(last24Hours?.summary.totalTokens ?? 0), [last24Hours?.summary.totalTokens]);

  return (
    <PageContainer
      content="系统用户状态与客户端用量运行情况。"
      extra={[<Tooltip key="refresh" title="刷新概览"><Button aria-label="刷新概览" icon={<ReloadOutlined />}
        loading={refreshing} onClick={() => setRefreshKey((value) => value + 1)} /></Tooltip>]}
      title="概览"
    >
      {loading && !data ? <LoadingState label="概览加载中" /> : null}
      {!loading && !data && dashboardError ? (
        <ErrorState description={dashboardError} onRetry={() => setRefreshKey((value) => value + 1)} title="概览加载失败" />
      ) : null}
      {data ? (
        <>
          {dashboardError ? <Alert action={<Button onClick={() => setRefreshKey((value) => value + 1)}>重试</Button>}
            className="cqg-inline-alert" title="用户统计刷新失败" description={dashboardError} showIcon type="error" /> : null}
          <div className="cqg-dashboard-stat-grid">
            <DashboardStatCard color="#4a9d9a" icon={<TeamOutlined />} title="用户总数" value={data.totalUsers} />
            <DashboardStatCard color="#6b8e8e" icon={<CheckCircleOutlined />} title="活跃用户" value={data.activeUsers} />
            <DashboardStatCard color="#c17767" icon={<StopOutlined />} title="已禁用" value={data.disabledUsers} />
            <DashboardStatCard color="#668477" icon={<SafetyCertificateOutlined />} title="管理员" value={data.adminUsers} />
            <DashboardStatCard color="#bd8b47" icon={<UserOutlined />} title="近 7 天新增" value={data.newUsersLast7Days} />
          </div>

          <section className="cqg-dashboard-usage" aria-label="用量运行概况">
            <div className="cqg-dashboard-section-head">
              <div><h2>用量运行概况</h2><p>用量数据自动刷新 · 每 30 秒</p></div>
              <Typography.Text className="cqg-dashboard-data-note">
                按桌面端事件发生时间估算速率；不代表官方 API 网关请求、并发或实际到达速率
              </Typography.Text>
            </div>

            {usageError ? <Alert className="cqg-inline-alert" showIcon type="warning" title="部分用量统计暂不可用"
              description={usageError} action={<Button onClick={() => setRefreshKey((value) => value + 1)}>重试</Button>} /> : null}

            <div className="cqg-dashboard-stat-grid cqg-dashboard-runtime-grid">
              <DashboardStatCard color="#4a9d9a" icon={<LineChartOutlined />} title="事件 QPS（估算）"
                value={lastFiveMinutes ? averageRate(lastFiveMinutes.summary.eventCount) : '—'}
                detail="近 5 分钟事件 / 秒" />
              <DashboardStatCard color="#bd8b47" icon={<FireOutlined />} title="Token/s（估算）"
                value={lastFiveMinutes ? averageRate(lastFiveMinutes.summary.totalTokens) : '—'}
                detail="近 5 分钟平均 Token 速率" />
              <DashboardStatCard color="#668477" icon={<CheckCircleOutlined />} title="近 24 小时事件"
                value={last24Hours?.summary.eventCount.toLocaleString() ?? '—'} detail="客户端已上报并去重" />
              <DashboardStatCard color="#c17767" icon={<FireOutlined />} title="近 24 小时 Token"
                value={last24Hours ? formatTokenAmount(last24Hours.summary.totalTokens) : '—'} detail="全部输入、输出 Token" />
            </div>

            {last24Hours ? <UsageBreakdown stats={last24Hours.summary} /> : loading ? (
              <Typography.Text className="cqg-dashboard-data-note">正在读取近 24 小时用量明细…</Typography.Text>
            ) : null}

            <div className="cqg-dashboard-analysis-grid">
              <section className="cqg-dashboard-panel" aria-label="速率趋势">
                <div className="cqg-dashboard-panel-head">
                  <div><h3>近 24 小时速率趋势</h3><span>按小时聚合 · UTC</span></div>
                  <Segmented aria-label="速率趋势指标" options={[{ label: 'QPS', value: 'QPS' }, { label: 'TPS', value: 'TPS' }]}
                    value={rateMode} onChange={(value) => setRateMode(value as RateMode)} />
                </div>
                {last24Hours ? <EventRateChart data={last24Hours} mode={rateMode} /> : <Empty description={loading ? '用量数据加载中' : '暂无趋势数据'} />}
              </section>

              <section className="cqg-dashboard-panel" aria-label="模型用量排行">
                <div className="cqg-dashboard-panel-head">
                  <div><h3>模型用量排行</h3><span>近 24 小时 · 按 Token 排序</span></div>
                </div>
                <Table rowKey={(item) => item.key ?? item.label} columns={modelCols} dataSource={models} size="small"
                  pagination={false} scroll={{ x: 360 }} locale={{ emptyText: loading ? '用量数据加载中' : '暂无模型用量' }} />
              </section>
            </div>

            <section className="cqg-dashboard-panel cqg-dashboard-recent" aria-label="最近用量流水">
              <div className="cqg-dashboard-panel-head">
                <div><h3>最近用量流水</h3><span>近 24 小时最新 8 条 · 时间按本地时区显示</span></div>
                <Typography.Text className="cqg-dashboard-data-note">费用根据当前模型单价估算，非官方账单</Typography.Text>
              </div>
              <Table rowKey="id" columns={recentEventColumns} dataSource={recentEvents} size="small" pagination={false}
                scroll={{ x: 780 }} locale={{ emptyText: loading ? '用量数据加载中' : '近 24 小时暂无用量流水' }} />
            </section>
          </section>
        </>
      ) : null}
    </PageContainer>
  );
}
