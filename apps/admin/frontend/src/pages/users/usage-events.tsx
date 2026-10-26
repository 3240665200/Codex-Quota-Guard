import { Alert, App, Button, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { TokenAmount } from '../../components/TokenAmount';
import { UsageCost, UsageTokens } from '../../components/UsageAmounts';
import { confirmUsageBillingMode, getUserUsageEvents } from '../../services/api';
import type { UsageEventLogPage } from '../../services/api';

const PAGE_SIZE = 10;
type UsageEvent = UsageEventLogPage['items'][number];

function LedgerTime({ value }: { value: string }) {
  const at = new Date(value);
  return <time className="cqg-ledger-date" dateTime={value} title={at.toLocaleString('zh-CN')}>
    <span>{at.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })}</span>
    <span className="cqg-usage-muted">{at.toLocaleTimeString('zh-CN', { hour12: false })}</span>
  </time>;
}

export const usageEventColumns = [
  { title: '服务端收到', dataIndex: 'receivedAt', width: 160,
    render: (value: string) => <LedgerTime value={value} /> },
  { title: '本机发生', dataIndex: 'occurredAt', width: 160,
    render: (value: string) => <LedgerTime value={value} /> },
  { title: 'Token', dataIndex: 'totalTokens', width: 160,
    render: (value: number) => <TokenAmount value={value} /> },
  { title: '来源', dataIndex: 'source', width: 170,
    render: (value: UsageEvent['source']) => value === 'TOKEN_USAGE_RECORD'
      ? 'Codex 用量记录' : '旧日志累计差值' },
  { title: '模型名称', dataIndex: 'model', width: 190, ellipsis: true,
    render: (value: string | null | undefined) => value ?? '未知' },
  { title: '思考等级', dataIndex: 'reasoningEffort', width: 100,
    render: (value: string | null | undefined) => value ?? '未知' },
  { title: '速度模式 / 倍率', width: 180, render: (_, item) => item.billingModeStatus === 'FAST' ? `快速 ×${item.billingMultiplier}` : item.billingModeStatus === 'STANDARD' ? '普通 ×1' : item.billingOrigin === 'HISTORICAL_RECALC' ? '历史未记录（普通费率回算）' : '未记录（待确认）' },
  { title: '模式依据', width: 150, render: (_, item) => item.billingModeSource === 'REPORTED' ? '日志明确记录' : item.billingModeSource === 'ADMIN_DECLARED' ? '管理员设备设置' : item.billingModeSource === 'ADMIN_CONFIRMED' ? '管理员逐笔确认' : '日志未记录' },
  { title: '计费来源', width: 135, render: (_, item) => item.billingOrigin === 'HISTORICAL_RECALC' ? '历史回算' : item.billingOrigin === 'ADMIN_REVIEW' ? '管理员核对入账' : '实时入账' },
  { title: '输入 Token', dataIndex: 'inputTokens', width: 130, render: (value) => <UsageTokens value={value} /> },
  { title: '缓存读 Token', dataIndex: 'cachedInputTokens', width: 130, render: (value) => <UsageTokens value={value} /> },
  { title: '缓存写 Token', dataIndex: 'cacheWriteInputTokens', width: 130, render: (value) => <UsageTokens value={value} /> },
  { title: '输出 Token', dataIndex: 'outputTokens', width: 130, render: (value) => <UsageTokens value={value} /> },
  { title: '思考 Token', dataIndex: 'reasoningOutputTokens', width: 130, render: (value) => <UsageTokens value={value} /> },
  { title: '账本费用 (USD)', dataIndex: 'estimatedCostUsd', width: 180,
    render: (value, item) => <UsageCost value={value} status={item.costStatus} /> },
] satisfies TableColumnsType<UsageEvent>;
export const usageEventsTableWidth = usageEventColumns.reduce((total, column) => total + column.width, 0);

export function UsageEventsTable({ userId }: { userId: string }) {
  const { modal, message } = App.useApp();
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [data, setData] = useState<UsageEventLogPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    setData(null);
    setSelected([]);
    getUserUsageEvents(userId, page, PAGE_SIZE)
      .then((result) => { if (active) setData(result); })
      .catch((reason) => { if (active) setError(getErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [userId, page, reload]);

  const confirmMode = (serviceTier: 'standard' | 'fast') => modal.confirm({
    title: `确认 ${selected.length} 笔待核算用量的速度模式？`, okText: '确认并核算', cancelText: '取消',
    content: `按${serviceTier === 'fast' ? '快速模式（默认 1.5 倍，采用各模型价格表倍率）' : '普通模式（1 倍）'}核算所选记录。当前价格表将形成账本快照；已入账记录不会改变。请先确认实际使用模式。`,
    onOk: async () => {
      setConfirming(true);
      try { const result = await confirmUsageBillingMode(selected, serviceTier); message.success(`已确认 ${result.updated} 笔用量`); setReload((n) => n + 1); }
      catch (reason) { message.error(getErrorMessage(reason)); throw reason; }
      finally { setConfirming(false); }
    },
  });

  return (
    <section aria-label="Token 用量流水" style={{ width: '100%', minWidth: 0 }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={5}>Token 用量流水</Typography.Title>
        <Button onClick={() => setReload((value) => value + 1)} size="small">刷新流水</Button>
      </Space>
      <Typography.Paragraph type="secondary">
        只显示中央服务已接收的记录；本机日志并非官方账单。
      </Typography.Paragraph>
      <Space wrap style={{ marginBottom: 12 }}>
        <Button disabled={selected.length === 0 || confirming || loading} onClick={() => confirmMode('standard')}>按普通模式确认</Button>
        <Button disabled={selected.length === 0 || confirming || loading} onClick={() => confirmMode('fast')}>按快速模式确认</Button>
        <Typography.Text type="secondary">仅可选择未入账且模式未记录的流水。</Typography.Text>
      </Space>
      {error ? <Alert showIcon type="error" title="用量流水读取失败" description={error} /> : null}
      <Table<UsageEvent>
        className="cqg-ledger-table"
        tableLayout="fixed"
        columns={usageEventColumns}
        dataSource={data?.items ?? []}
        loading={loading}
        locale={{ emptyText: '暂无已接收的 Token 事件' }}
        pagination={{ current: page + 1, pageSize: PAGE_SIZE,
          total: data?.totalElements ?? 0, showSizeChanger: false,
          onChange: (nextPage) => setPage(nextPage - 1) }}
        rowKey="id"
        rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys.map(String)),
          getCheckboxProps: (item) => ({ disabled: confirming || item.estimatedCostUsd != null || item.billingModeStatus !== 'UNRECORDED' }) }}
        scroll={{ x: usageEventsTableWidth }}
        size="small"
      />
    </section>
  );
}
