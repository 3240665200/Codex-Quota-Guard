import { Button, Card, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import type { SessionBinding } from '../../services/sessionBindingsApi';

const statusLabels: Record<SessionBinding['status'], string> = {
  ACTIVE: '已绑定', ENDED: '已结束', INVALIDATED: '已失效',
};
const verificationLabels: Record<SessionBinding['verificationLevel'], string> = {
  USER_CONFIRMED_INFERENCE: '用户确认的推断',
  AUTOMATIC_SERIAL_INFERENCE: '后台自动复核的推断',
};
const dateText = (value: string | null) => {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN');
};

export function SessionBindingResult({ officialAccountId, sessionId, bindings }: {
  officialAccountId: string; sessionId: string | null; bindings: SessionBinding[];
}) {
  if (!sessionId) return <Typography.Text type="secondary">官方未提供 Session ID，无法匹配</Typography.Text>;
  const matches = bindings.filter((binding) => binding.officialAccountId === officialAccountId
    && binding.sessionId === sessionId && binding.status === 'ACTIVE');
  if (matches.length > 1) return <Tag color="warning">绑定记录冲突，请检查</Tag>;
  const binding = matches[0];
  if (!binding) return <Typography.Text type="secondary">暂无当前绑定</Typography.Text>;
  return <Space orientation="vertical" size={2}>
    <Tag color="success">已绑定</Tag>
    <Typography.Text>{binding.userDisplayName || binding.userEmail || binding.userId}</Typography.Text>
    <Typography.Text type="secondary">设备：{binding.deviceId}</Typography.Text>
    <Typography.Text type="secondary">{verificationLabels[binding.verificationLevel] ?? '核验方式未知'}</Typography.Text>
  </Space>;
}

export function SessionBindingsTable({ bindings, disabled = false, onRelease }: {
  bindings: SessionBinding[]; disabled?: boolean; onRelease?: (binding: SessionBinding) => void;
}) {
  const columns: TableColumnsType<SessionBinding> = [
    { title: '本系统用户', render: (_, binding) => <Space orientation="vertical" size={2}>
      <Typography.Text>{binding.userDisplayName || binding.userEmail || binding.userId}</Typography.Text>
      {binding.userEmail ? <Typography.Text type="secondary">{binding.userEmail}</Typography.Text> : null}
    </Space> },
    { title: '客户端设备', dataIndex: 'deviceId' },
    { title: '官方 Session ID', dataIndex: 'sessionId' },
    { title: '绑定状态', render: (_, binding) => <Tag color={binding.status === 'ACTIVE' ? 'success' : 'default'}>
      {statusLabels[binding.status] ?? '未知'}</Tag> },
    { title: '核验方式', render: (_, binding) => verificationLabels[binding.verificationLevel] ?? '核验方式未知' },
    { title: '绑定时间', render: (_, binding) => dateText(binding.createdAt) },
    { title: '结束时间', render: (_, binding) => dateText(binding.endedAt) },
    ...(onRelease ? [{ title: '操作', key: 'release', render: (_: unknown, binding: SessionBinding) => binding.status === 'ACTIVE'
      ? <Button danger size="small" disabled={disabled} onClick={() => onRelease(binding)}>解绑会话</Button> : null }] : []),
  ];
  return <Card size="small" title="设备会话绑定记录">
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Typography.Text type="secondary">新绑定依据串行登录前后会话差异，由后台自动复核并保存，无需用户再次确认；历史记录保留原核验方式。该推断未经官方强验证，无法证明物理设备身份。</Typography.Text>
      <Table<SessionBinding> size="small" rowKey="id" columns={columns} dataSource={bindings}
        pagination={false} scroll={{ x: 820 }} locale={{ emptyText: '尚无设备会话绑定记录' }} />
    </Space>
  </Card>;
}
