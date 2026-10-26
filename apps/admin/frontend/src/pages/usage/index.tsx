import { DollarOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Empty, Form, Input, Progress, Segmented, Select, Space, Switch, Table, Tabs, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from 'recharts';
import { getErrorMessage, LoadingState } from '../../components/AdminState';
import { TokenAmount } from '../../components/TokenAmount';
import { formatUsd, UsageCost, UsageTokens } from '../../components/UsageAmounts';
import { getUsageAnalytics, getUsageOptions, listUsageModelPrices } from '../../services/api';
import type { UsageAnalytics, UsageAnalyticsParams, UsageGroup, UsageLedgerItem, UsageModelPrice, UsageOptions } from '../../services/api';
import { formatTokenAmount } from '../../utils/token';
import { usageEventColumns } from '../users/usage-events';
import { dateQuery, initialDates, localDate, trendPoints } from './chart-data';
import { ModelPrices } from './model-prices';

const efforts = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'].map((value) => ({ value, label: value }));
const emptyOptions: UsageOptions = { models: [], users: [], teams: [] };
const ledgerColumns: TableColumnsType<UsageLedgerItem> = [
  { title: '用户', dataIndex: 'userName', width: 140, ellipsis: true, render: (value) => value ?? '已删除用户' },
  { title: '团队', dataIndex: 'teamName', width: 160, ellipsis: true, render: (value) => value ?? '未分配 / 已删除' },
  ...usageEventColumns,
];

function Distribution({ groups, total }: { groups: UsageGroup[]; total: number }) {
  const columns: TableColumnsType<UsageGroup> = [
    { title: '名称', dataIndex: 'label', width: 220, ellipsis: true },
    { title: 'Token', render: (_, item) => <TokenAmount value={item.stats.totalTokens} />, width: 150 },
    { title: '占比', render: (_, item) => <Progress aria-label={`${item.label} Token 占比`} size="small" strokeColor="#159979" percent={total > 0 ? Math.round(item.stats.totalTokens / total * 1000) / 10 : 0} />, width: 200 },
    { title: '事件数', render: (_, item) => item.stats.eventCount.toLocaleString(), width: 100 },
    { title: '账本费用 (USD)', render: (_, item) => <UsageCost value={item.stats.estimatedCostUsd} />, width: 180 },
    { title: '可估价事件', render: (_, item) => `${item.stats.costedEventCount} / ${item.stats.eventCount}`, width: 140 },
  ];
  return <Table rowKey={(item) => item.key ?? 'unknown'} columns={columns} dataSource={groups} pagination={false}
    size="small" scroll={{ x: 990 }} locale={{ emptyText: '暂无用量' }} />;
}

export default function UsagePage() {
  const [dates, setDates] = useState(initialDates);
  const [granularity, setGranularity] = useState<'HOUR' | 'DAY'>('DAY');
  const [filters, setFilters] = useState<Pick<UsageAnalyticsParams, 'model' | 'reasoningEffort' | 'userId' | 'teamId'>>({});
  const [query, setQuery] = useState<UsageAnalyticsParams>(() => {
    const value = initialDates();
    return { ...dateQuery(value.start, value.end, 'DAY'), page: 0, size: 20 };
  });
  const [data, setData] = useState<UsageAnalytics | null>(null);
  const [options, setOptions] = useState<UsageOptions>(emptyOptions);
  const [prices, setPrices] = useState<UsageModelPrice[]>([]);
  const [optionsError, setOptionsError] = useState('');
  const [error, setError] = useState('');
  const [filterError, setFilterError] = useState('');
  const [loading, setLoading] = useState(true);
  const [pricesLoading, setPricesLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [chartMode, setChartMode] = useState<'Token' | 'USD'>('Token');
  const [tab, setTab] = useState('overview');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    // A failed page/filter request must not relabel the previous result.
    setData(null);
    getUsageAnalytics(query).then((result) => { if (active) setData(result); })
      .catch((reason) => { if (active) setError(getErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [query, reload]);
  useEffect(() => {
    let active = true;
    setPricesLoading(true);
    setOptionsError('');
    Promise.all([getUsageOptions(), listUsageModelPrices()]).then(([nextOptions, nextPrices]) => {
      if (active) { setOptions(nextOptions); setPrices(nextPrices); }
    }).catch((reason) => { if (active) setOptionsError(getErrorMessage(reason)); })
      .finally(() => { if (active) setPricesLoading(false); });
    return () => { active = false; };
  }, [reload]);
  useEffect(() => {
    if (!autoRefresh) return;
    const timer = window.setInterval(() => setReload((value) => value + 1), 30_000);
    return () => window.clearInterval(timer);
  }, [autoRefresh]);

  const apply = () => {
    try {
      setQuery({ ...dateQuery(dates.start, dates.end, granularity), ...filters, page: 0, size: query.size });
      setFilterError('');
    } catch (reason) {
      setFilterError(getErrorMessage(reason));
    }
  };
  const preset = (days: number) => {
    const end = new Date();
    const start = new Date(end);
    start.setDate(start.getDate() - days + 1);
    const value = { start: localDate(start), end: localDate(end) };
    const nextGranularity = days > 31 ? 'DAY' : granularity;
    setDates(value);
    setGranularity(nextGranularity);
    setQuery({ ...dateQuery(value.start, value.end, nextGranularity), ...filters, page: 0, size: query.size });
    setFilterError('');
  };
  const chartData = useMemo(() => data ? trendPoints(data) : [], [data]);
  const pricedPercent = data && data.summary.eventCount > 0 ? Math.round(data.summary.costedEventCount / data.summary.eventCount * 100) : 0;
  const selector = (key: keyof typeof filters, label: string, choices: UsageOptions['models']) => (
    <Form.Item label={label} key={key}>
      <Select aria-label={label} placeholder={`全部${label}`} allowClear showSearch optionFilterProp="label"
        options={choices} value={filters[key]} onChange={(value: string | undefined) => setFilters((previous) => ({ ...previous, [key]: value }))} />
    </Form.Item>
  );

  return <PageContainer title="用量统计" extra={[
    <Space key="refresh" size="middle">
      <Space size="small"><span className="cqg-usage-muted">30 秒刷新</span><Switch aria-label="自动刷新" checked={autoRefresh} onChange={setAutoRefresh} /></Space>
      <Tooltip title="刷新统计"><Button aria-label="刷新统计" icon={<ReloadOutlined />} loading={loading} onClick={() => setReload((value) => value + 1)} /></Tooltip>
    </Space>,
  ]}>
    <div className="cqg-usage-page">
      {optionsError ? <Alert showIcon type="warning" title="筛选项或定价读取失败" description={optionsError} className="cqg-inline-alert" /> : null}
      <Tabs activeKey={tab} onChange={setTab} items={[
        { key: 'overview', label: '用量概览' },
        { key: 'prices', label: '模型定价' },
      ]} />
      {tab === 'prices' ? <ModelPrices prices={prices} models={options.models} loading={pricesLoading}
        onChanged={() => setReload((value) => value + 1)} /> : <>
        <section className="cqg-usage-filters" aria-label="用量筛选">
          <Form layout="vertical" className="cqg-usage-filter-grid" onFinish={apply}>
            <Form.Item label="开始日期"><Input aria-label="开始日期" type="date" value={dates.start}
              onChange={(event) => setDates((previous) => ({ ...previous, start: event.target.value }))} /></Form.Item>
            <Form.Item label="结束日期"><Input aria-label="结束日期" type="date" value={dates.end}
              onChange={(event) => setDates((previous) => ({ ...previous, end: event.target.value }))} /></Form.Item>
            {selector('model', '模型', options.models)}
            {selector('reasoningEffort', '思考等级', efforts)}
            {selector('userId', '用户', options.users)}
            {selector('teamId', '团队', options.teams)}
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>查询</Button>
          </Form>
          <div className="cqg-usage-filter-footer">
            <Space wrap>{[1, 7, 30, 90].map((days) => <Button key={days} size="small" type="text" onClick={() => preset(days)}>{days === 1 ? '今天' : `近 ${days} 天`}</Button>)}</Space>
            <Segmented aria-label="趋势粒度" options={[{ label: '按天', value: 'DAY' }, { label: '按小时', value: 'HOUR' }]}
              value={granularity} onChange={(value) => setGranularity(value as 'HOUR' | 'DAY')} />
          </div>
          {filterError ? <Alert role="alert" showIcon type="error" title={filterError} /> : null}
        </section>
        {error ? <Alert showIcon type="error" title="统计读取失败" description={error} className="cqg-inline-alert"
          action={<Button onClick={() => setReload((value) => value + 1)}>重试</Button>} /> : null}
        {loading ? <LoadingState label="正在读取用量统计" /> : null}
        {data ? <>
          <section className="cqg-usage-metrics" aria-label="用量汇总">
            <div className="cqg-usage-metric"><span><DollarOutlined /> 账本费用 (USD)</span>
              <strong>{data.summary.estimatedCostUsd == null ? '—' : formatUsd(data.summary.estimatedCostUsd)}</strong>
              <small>可估价 {data.summary.costedEventCount.toLocaleString()} / {data.summary.eventCount.toLocaleString()} 条 ({pricedPercent}%)</small></div>
            <div className="cqg-usage-metric"><span>总 Token</span><strong><TokenAmount value={data.summary.totalTokens} /></strong>
              <small>分项完整 {data.summary.breakdownEventCount.toLocaleString()} / {data.summary.eventCount.toLocaleString()} 条</small></div>
            <div className="cqg-usage-metric"><span>用量事件</span><strong>{data.summary.eventCount.toLocaleString()}</strong>
              <small>已接收并去重</small></div>
            <div className="cqg-usage-metric"><span>缓存命中率</span><strong>{data.summary.cacheHitRatePercent == null ? '—' : `${data.summary.cacheHitRatePercent}%`}</strong>
              <small>缓存读 / 完整分项输入</small></div>
          </section>
          <div className="cqg-usage-breakdown" aria-label="Token 分项汇总">
            {([
              ['输入', data.summary.inputTokens], ['缓存读', data.summary.cachedInputTokens],
              ['缓存写', data.summary.cacheWriteInputTokens], ['输出', data.summary.outputTokens], ['思考', data.summary.reasoningOutputTokens],
            ] as const).map(([label, value]) => <div key={label}><span>{label}</span><UsageTokens value={value} /></div>)}
          </div>
          {data.summary.costedEventCount < data.summary.eventCount ? <Alert className="cqg-inline-alert" type="warning" showIcon
            title="费用仅包含分项完整且已定价的记录" /> : null}
          <section className="cqg-usage-section" aria-label="用量趋势">
            <div className="cqg-usage-section-head"><div><h2>用量趋势</h2><span className="cqg-usage-muted">{data.granularity === 'HOUR' ? '按小时' : '按天'} · UTC</span></div>
              <Segmented aria-label="趋势单位" options={['Token', 'USD']} value={chartMode} onChange={(value) => setChartMode(value as 'Token' | 'USD')} /></div>
            {data.summary.eventCount === 0 ? <Empty description="当前范围暂无用量" /> : <div className="cqg-usage-chart" role="img" aria-label={`${chartMode} 用量趋势图`}>
              <ResponsiveContainer width="100%" height="100%" minWidth={0}>
                <AreaChart data={chartData} margin={{ top: 12, right: 20, bottom: 12, left: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb" />
                  <XAxis dataKey="label" minTickGap={40} tick={{ fontSize: 12 }} />
                  <YAxis width={88} tick={{ fontSize: 12 }} tickFormatter={(value: number) => chartMode === 'USD' ? formatUsd(value) : formatTokenAmount(value).replace(' Token', '')} />
                  <ChartTooltip labelFormatter={(_, payload) => `${payload[0]?.payload.bucket ?? ''} (UTC)`}
                    formatter={(value) => [chartMode === 'USD' ? `USD ${formatUsd(Number(value))}` : formatTokenAmount(Number(value)), chartMode === 'USD' ? '账本费用' : '总 Token']} />
                  <Area type="monotone" dataKey={chartMode === 'USD' ? 'estimatedCostUsd' : 'totalTokens'} stroke={chartMode === 'USD' ? '#1769c2' : '#159979'}
                    fill={chartMode === 'USD' ? '#dbeafe' : '#d5f3e7'} strokeWidth={2} connectNulls={false} isAnimationActive={false} />
                </AreaChart>
              </ResponsiveContainer>
            </div>}
          </section>
          <section className="cqg-usage-section" aria-label="用量分布">
            <div className="cqg-usage-section-head"><h2>用量分布</h2><span className="cqg-usage-muted">Top 20 · 按 Token 排序</span></div>
            <Tabs items={[
              { key: 'model', label: '模型', children: <Distribution groups={data.models} total={data.summary.totalTokens} /> },
              { key: 'effort', label: '思考等级', children: <Distribution groups={data.reasoningEfforts} total={data.summary.totalTokens} /> },
              { key: 'user', label: '用户', children: <Distribution groups={data.users} total={data.summary.totalTokens} /> },
              { key: 'team', label: '团队', children: <Distribution groups={data.teams} total={data.summary.totalTokens} /> },
            ]} />
          </section>
          <section className="cqg-usage-section" aria-label="全部用量流水">
            <div className="cqg-usage-section-head"><h2>用量流水</h2><span className="cqg-usage-muted">入账时价格快照 · 非官方账单</span></div>
            <Table rowKey="id" columns={ledgerColumns} dataSource={data.events.items} size="small" scroll={{ x: 2070 }}
              locale={{ emptyText: '当前筛选下暂无流水' }}
              pagination={{ current: (query.page ?? 0) + 1, pageSize: query.size ?? 20, total: data.events.totalElements,
                showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100], showTotal: (total) => `共 ${total.toLocaleString()} 条`,
                onChange: (page, size) => setQuery((previous) => ({ ...previous, page: size === previous.size ? page - 1 : 0, size })) }} />
          </section>
        </> : null}
      </>}
    </div>
  </PageContainer>;
}
