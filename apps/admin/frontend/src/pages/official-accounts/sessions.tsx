import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Empty, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { listOfficialAccounts } from '../../services/api';
import type { OfficialAccountResponse } from '../../services/api';
import { managedErrorMessage } from '../../components/managedErrors';
import ConnectionModal from './ConnectionModal';

export default function OfficialSessionsPage() {
  const [accounts, setAccounts] = useState<OfficialAccountResponse[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true; let stopped = false; setLoading(true); setError('');
    void listOfficialAccounts().then((values) => {
      if (stopped || !alive.current) return;
      setAccounts(values); setSelectedId((value) => values.some((account) => account.id === value) ? value : null);
    }, (reason: unknown) => { if (!stopped && alive.current) { setError(managedErrorMessage(reason, '官方账户列表读取失败，请重试。')); setAccounts([]); setSelectedId(null); } })
      .finally(() => { if (!stopped && alive.current) setLoading(false); });
    return () => { stopped = true; alive.current = false; };
  }, [revision]);
  const account = accounts.find((value) => value.id === selectedId);
  return <PageContainer title="会话管理" content="选择官方账户，查看官方设备会话、本系统绑定与绑定队列，并核对登出操作。">
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Space wrap>
        <Typography.Text>官方账户</Typography.Text>
        <Select aria-label="选择官方账户" placeholder="请选择要管理会话的官方账户" loading={loading} disabled={loading} allowClear
          style={{ minWidth: 300 }} value={selectedId} options={accounts.map((value) => ({ value: value.id, label: value.label }))}
          onChange={(value) => setSelectedId(value || null)} />
        <Button loading={loading} onClick={() => { setSelectedId(null); setRevision((value) => value + 1); }}>刷新账户列表</Button>
      </Space>
      {error ? <Alert type="error" showIcon title="账户读取失败" description={error} /> : null}
      {account ? <ConnectionModal key={account.id} account={account} view="sessions" inline onClose={() => setSelectedId(null)} />
        : <Empty description={loading ? '正在读取官方账户' : accounts.length ? '请先选择官方账户，再查看设备与会话操作。' : '暂无官方账户，请先到账户管理添加。'} />}
    </Space>
  </PageContainer>;
}
