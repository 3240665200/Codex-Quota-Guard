import { Alert, Button, Space, Table, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { TokenAmount } from '../../components/TokenAmount';
import { getUserUsageEvents } from '../../services/api';
import type { UsageEventLogPage } from '../../services/api';

const PAGE_SIZE = 10;
type UsageEvent = UsageEventLogPage['items'][number];

const columns: TableColumnsType<UsageEvent> = [
  { title: '服务端收到', dataIndex: 'receivedAt',
    render: (value: string) => new Date(value).toLocaleString('zh-CN') },
  { title: '本机发生', dataIndex: 'occurredAt',
    render: (value: string) => new Date(value).toLocaleString('zh-CN') },
  { title: 'Token', dataIndex: 'totalTokens',
    render: (value: number) => <TokenAmount value={value} /> },
  { title: '来源', dataIndex: 'source',
    render: (value: UsageEvent['source']) => value === 'TOKEN_USAGE_RECORD'
      ? 'Codex 用量记录' : '旧日志累计差值' },
];

export function UsageEventsTable({ userId }: { userId: string }) {
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [data, setData] = useState<UsageEventLogPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    setData(null);
    getUserUsageEvents(userId, page, PAGE_SIZE)
      .then((result) => { if (active) setData(result); })
      .catch((reason) => { if (active) setError(getErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [userId, page, reload]);

  return (
    <section aria-label="Token 用量流水" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={5}>Token 用量流水</Typography.Title>
        <Button onClick={() => setReload((value) => value + 1)} size="small">刷新流水</Button>
      </Space>
      <Typography.Paragraph type="secondary">
        只显示中央服务已接收的记录；本机日志并非官方账单。
      </Typography.Paragraph>
      {error ? <Alert showIcon type="error" title="用量流水读取失败" description={error} /> : null}
      <Table<UsageEvent>
        columns={columns}
        dataSource={data?.items ?? []}
        loading={loading}
        locale={{ emptyText: '暂无已接收的 Token 事件' }}
        pagination={{ current: page + 1, pageSize: PAGE_SIZE,
          total: data?.totalElements ?? 0, showSizeChanger: false,
          onChange: (nextPage) => setPage(nextPage - 1) }}
        rowKey="id"
        scroll={{ x: 640 }}
        size="small"
      />
    </section>
  );
}
