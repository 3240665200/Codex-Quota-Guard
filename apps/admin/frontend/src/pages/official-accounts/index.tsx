import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App as AntApp, Button, Form, Input, InputNumber, Modal, Popconfirm,
  Progress, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { TokenAmount } from '../../components/TokenAmount';
import { createOfficialAccount, deleteOfficialAccount, listOfficialAccounts,
  updateOfficialAccount } from '../../services/api';
import type { OfficialAccountInput, OfficialAccountResponse,
  OfficialQuotaSnapshot } from '../../services/api';
import MailboxConfigModal from './MailboxConfigModal';

const MAX_TOKEN_LIMIT = 1_000_000_000_000;

function QuotaWindow({ snapshot, minutes, label }: {
  snapshot: OfficialQuotaSnapshot | null; minutes: number; label: string;
}) {
  const window = snapshot?.windows.find((item) => item.windowDurationMins === minutes);
  if (!window) return null;
  const remaining = Math.max(0, 100 - window.usedPercent);
  return <div style={{ minWidth: 125 }}>
    <Typography.Text>{label}剩余 {remaining.toFixed(1).replace(/\.0$/, '')}%</Typography.Text>
    <Progress aria-label={`${label}官方额度`} percent={window.usedPercent}
      size="small" showInfo={false} status={remaining === 0 ? 'exception' : 'normal'} />
  </div>;
}

export default function OfficialAccountsPage() {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<OfficialAccountInput>();
  const [accounts, setAccounts] = useState<OfficialAccountResponse[]>([]);
  const [editing, setEditing] = useState<OfficialAccountResponse | null>(null);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [mailboxAccount, setMailboxAccount] = useState<OfficialAccountResponse | null>(null);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      setAccounts(await listOfficialAccounts());
      setError('');
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(true); }, 30_000);
    return () => window.clearInterval(timer);
  }, [load]);

  const edit = (account: OfficialAccountResponse | null) => {
    setEditing(account);
    form.setFieldsValue(account ? {
      label: account.label,
      estimatedTotalTokens: account.estimatedTotalTokens,
      reporterEmail: account.reporterEmail,
    } : { label: '', estimatedTotalTokens: 0, reporterEmail: null });
    setOpen(true);
  };

  const save = async () => {
    let values: OfficialAccountInput;
    try { values = await form.validateFields(); } catch { return; }
    setSaving(true);
    try {
      const body = { ...values, reporterEmail: values.reporterEmail?.trim() || null };
      if (editing) await updateOfficialAccount(editing.id, { ...body, version: editing.version });
      else await createOfficialAccount(body);
      message.success(editing ? '官方账户已更新' : '官方账户已登记');
      setOpen(false);
      form.resetFields();
      await load();
    } catch (reason) {
      message.error(getErrorMessage(reason));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (account: OfficialAccountResponse) => {
    try {
      await deleteOfficialAccount(account.id);
      message.success('官方账户登记已删除');
      await load();
    } catch (reason) {
      message.error(getErrorMessage(reason));
    }
  };

  const columns: TableColumnsType<OfficialAccountResponse> = [
    { title: '官方账户', dataIndex: 'label', render: (value: string) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '团队估算总量', dataIndex: 'estimatedTotalTokens', width: 165,
      render: (value: number) => <TokenAmount value={value} /> },
    { title: '上报用户', dataIndex: 'reporterEmail', width: 220,
      render: (value: string | null) => value || <Typography.Text type="secondary">未指定</Typography.Text> },
    { title: '套餐', width: 90, render: (_, account) => account.quota?.planType || '未知' },
    { title: '官方额度', width: 310, render: (_, account) => account.quota?.windows.some(
      (item) => item.windowDurationMins === 300 || item.windowDurationMins === 10_080)
      ? <Space align="start" size={16}>
          <QuotaWindow snapshot={account.quota} minutes={300} label="5 小时" />
          <QuotaWindow snapshot={account.quota} minutes={10_080} label="7 天" />
        </Space>
      : <Tag>{account.quota ? '未返回额度窗口' : '待客户端上报'}</Tag> },
    { title: '快照时间', width: 210, render: (_, account) => account.quota
      ? <Space size={4} wrap>
          <span>{new Date(account.quota.receivedAt).toLocaleString('zh-CN')}</span>
          {Date.now() - new Date(account.quota.receivedAt).getTime() > 5 * 60_000
            ? <Tag color="warning">旧快照</Tag> : null}
        </Space> : '暂无' },
    { title: '操作', width: 210, render: (_, account) => <Space size={0}>
      <Button aria-label={`邮箱配置 ${account.label}`} type="link" onClick={() => setMailboxAccount(account)}>邮箱配置</Button>
      <Button aria-label={`编辑 ${account.label}`} icon={<EditOutlined />} type="text" onClick={() => edit(account)} />
      <Popconfirm title={`删除「${account.label}」？`} description="已关联团队的账户不可删除。"
        okText="删除" cancelText="取消" onConfirm={() => void remove(account)}>
        <Button aria-label={`删除 ${account.label}`} danger icon={<DeleteOutlined />} type="text" />
      </Popconfirm>
    </Space> },
  ];

  return <PageContainer title="官方账户"
    content="查看用户上报的官方额度，不托管官方账户会话凭证、不转发官方请求；自有邮箱授权由后台加密保存，用于代收登录码。"
    extra={<Space>
      <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
      <Button icon={<PlusOutlined />} type="primary" onClick={() => edit(null)}>添加官方账户</Button>
    </Space>}>
    {error ? <Alert showIcon type="error" title="账户读取失败" description={error}
      action={<Button onClick={() => void load()}>重试</Button>} /> : null}
    <Table<OfficialAccountResponse> rowKey="id" columns={columns} dataSource={accounts}
      loading={loading} pagination={false} scroll={{ x: 1200 }}
      locale={{ emptyText: '暂无官方账户登记' }} />
    {mailboxAccount ? <MailboxConfigModal key={mailboxAccount.id} account={mailboxAccount}
      onClose={() => setMailboxAccount(null)} /> : null}
    <Modal open={open} title={editing ? '编辑官方账户' : '添加官方账户'}
      onCancel={() => setOpen(false)} onOk={() => void save()} okButtonProps={{ loading: saving }}
      okText="保存" destroyOnHidden>
      <Form form={form} layout="vertical">
        <Form.Item name="label" label="账户名称" rules={[{ required: true, message: '请输入账户名称' }]}>
          <Input maxLength={120} placeholder="例如：研发团队官方账户" />
        </Form.Item>
        <Form.Item name="estimatedTotalTokens" label="估算总 Token"
          extra="关联团队后，此数值作为团队累计上限；它不是官方百分比换算结果。"
          rules={[{ required: true, message: '请输入估算总量' }]}>
          <InputNumber min={0} max={MAX_TOKEN_LIMIT} precision={0} suffix="Token" style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="reporterEmail" label="上报用户的系统邮箱"
          extra="该用户需要在自己的电脑上登录对应官方账户并打开客户端，才会产生额度快照。"
          rules={[{ type: 'email', message: '请输入有效邮箱' }]}>
          <Input maxLength={320} placeholder="可稍后指定" />
        </Form.Item>
      </Form>
    </Modal>
  </PageContainer>;
}
