import { Alert, App, Button, Select, Space, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { getUserBillingModes, setUserBillingMode } from '../../services/api';
import type { MeterBillingMode } from '../../services/api';

const options = [
  { value: 'unknown', label: '未确定（待核算）' },
  { value: 'standard', label: '普通模式 ×1' },
  { value: 'fast', label: '快速模式（默认 ×1.5）' },
];

export function BillingModes({ userId }: { userId: string }) {
  const { modal, message } = App.useApp();
  const [data, setData] = useState<MeterBillingMode[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let active = true;
    setLoading(true); setError(''); setData([]);
    getUserBillingModes(userId).then((result) => { if (active) setData(result); })
      .catch((reason) => { if (active) setError(getErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [userId, reload]);
  const change = (row: MeterBillingMode, serviceTier: MeterBillingMode['serviceTier']) => {
    modal.confirm({ title: '确认设备计费模式？', okText: '保存模式', cancelText: '取消',
      content: '这是一项管理员声明，不会切换 Codex 的快速模式。从保存时间起的新用量生效；旧流水和已入账金额不变。请与该设备实际使用模式保持一致。',
      onOk: async () => {
        setSaving(row.deviceId);
        try { setData(await setUserBillingMode(userId, row.deviceId, serviceTier)); message.success('设备计费模式已保存'); }
        catch (reason) { message.error(getErrorMessage(reason)); throw reason; }
        finally { setSaving(null); }
      },
    });
  };
  return <section aria-label="设备计费模式">
    <Space style={{ width: '100%', justifyContent: 'space-between' }}>
      <Typography.Title level={4}>设备计费模式</Typography.Title>
      <Button size="small" disabled={saving !== null} onClick={() => setReload((n) => n + 1)}>刷新设备模式</Button>
    </Space>
    <Alert showIcon type="info" title="当前 Codex 日志可能不含速度模式"
      description="有明确日志标记时优先使用日志；否则使用对应设备在用量发生时的设置。未确定的新用量待核算，可在下方流水中确认普通或快速模式。设置保存在服务器，桌面客户端退出后仍生效。" />
    {error ? <Alert type="error" showIcon title="设备模式读取失败" description={error} /> : null}
    <Table<MeterBillingMode> rowKey="deviceId" loading={loading} dataSource={data} pagination={false} size="small"
      locale={{ emptyText: '未配对统计设备' }} columns={[
        { title: '统计设备', dataIndex: 'deviceId', ellipsis: true },
        { title: '计费声明', width: 240, render: (_, row) => <Select aria-label={`设备 ${row.deviceId} 的计费模式`}
          style={{ width: '100%' }} options={options} value={row.serviceTier} disabled={row.status !== 'ACTIVE' || saving !== null}
          onChange={(value) => change(row, value)} /> },
        { title: '生效时间', render: (_, row) => row.effectiveAt ? new Date(row.effectiveAt).toLocaleString('zh-CN') : '尚未设置' },
      ]} />
  </section>;
}
