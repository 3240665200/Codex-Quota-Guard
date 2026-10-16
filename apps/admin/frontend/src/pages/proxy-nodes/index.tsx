import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App as AntApp, Button, Checkbox, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { managedErrorMessage } from '../../components/managedErrors';
import ManagementTransportNotice from '../../components/ManagementTransportNotice';
import { createProxyNode, deleteProxyNode, isSupportedManagementContext,
  listProxyNodes, setProxyNodeEnabled, testProxyNode, updateProxyNode } from '../../services/api';
import type { ProxyConnectionTest, ProxyNode, ProxyNodeInput } from '../../services/api';
import SubscriptionManager from './SubscriptionManager';

type NodeForm = ProxyNodeInput & { clearCredentials?: boolean };
const protocols = ['HTTP', 'HTTPS', 'SOCKS5'] as const;
const locationText = (node: Pick<ProxyNode, 'country' | 'region' | 'city'>) =>
  [node.country, node.region, node.city].filter(Boolean).join(' / ') || '暂无地区信息';
const dateText = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '尚未测试';

export default function ProxyNodesPage() {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<NodeForm>();
  const clearCredentials = Form.useWatch('clearCredentials', form);
  const [nodes, setNodes] = useState<ProxyNode[]>([]);
  const [editing, setEditing] = useState<ProxyNode | null>(null);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState<string | null>(null);
  const [changing, setChanging] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [subscriptionsOpen, setSubscriptionsOpen] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [testResult, setTestResult] = useState<{ label: string; result: ProxyConnectionTest } | null>(null);
  const active = useRef(true);
  const pending = useRef(false);
  const loadRevision = useRef(0);
  const supported = isSupportedManagementContext();
  const busy = saving || testing !== null || changing !== null || removing !== null;

  const load = useCallback(async () => {
    const revision = ++loadRevision.current;
    setLoading(true);
    try {
      const result = await listProxyNodes();
      if (active.current && revision === loadRevision.current) { setNodes(result); setError(''); }
    } catch (reason) {
      if (active.current && revision === loadRevision.current) setError(managedErrorMessage(reason, '代理节点读取失败，请稍后重试。'));
    } finally { if (active.current && revision === loadRevision.current) setLoading(false); }
  }, []);
  useEffect(() => {
    active.current = true; void load();
    return () => { active.current = false; loadRevision.current += 1; };
  }, [load]);

  const clearSecrets = () => form.setFieldsValue({ username: '', password: '' });
  const closeEdit = () => { clearSecrets(); form.resetFields(); setOpen(false); setFormError(''); };
  const edit = (node: ProxyNode | null) => {
    if (node && (node.sourceSubscriptionId || !protocols.includes(node.protocol as NodeForm['protocol']))) return;
    setEditing(node); setFormError(''); form.resetFields();
    form.setFieldsValue({ label: node?.label ?? '', enabled: node?.enabled ?? true,
      protocol: node ? (protocols.includes(node.protocol as NodeForm['protocol']) ? node.protocol as NodeForm['protocol'] : undefined) : 'HTTP',
      host: node?.host ?? '', port: node?.port, username: '', password: '', clearCredentials: false });
    setOpen(true);
  };

  const save = async () => {
    if (!supported || pending.current) return;
    let values: NodeForm;
    try { values = await form.validateFields(); } catch { return; }
    if (!active.current || pending.current) return;
    pending.current = true;
    const username = values.username?.trim() ?? '';
    const password = values.password ?? '';
    const body: ProxyNodeInput = { label: values.label.trim(), protocol: values.protocol,
      host: values.host.trim(), port: values.port, enabled: values.enabled,
      ...(editing ? { version: editing.version } : {}),
      ...(values.clearCredentials ? { username: '', password: '' } : username || password ? { username, password } : {}) };
    setSaving(true); setFormError(''); clearSecrets();
    try {
      if (editing) await updateProxyNode(editing.id, body);
      else await createProxyNode(body);
      if (!active.current) return;
      closeEdit(); message.success('代理节点已保存'); await load();
    } catch (reason) {
      if (active.current) setFormError(managedErrorMessage(reason, '节点保存失败，请检查服务器配置后重新输入凭据。'));
    } finally {
      delete values.username; delete values.password; delete body.username; delete body.password;
      pending.current = false;
      if (active.current) { clearSecrets(); setSaving(false); }
    }
  };

  const remove = async (node: ProxyNode) => {
    if (!supported || pending.current) return;
    pending.current = true; setRemoving(node.id); setError('');
    try {
      await deleteProxyNode(node.id);
      if (active.current) { message.success('代理节点已删除'); await load(); }
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, '代理节点删除失败，请稍后重试。'));
    } finally { pending.current = false; if (active.current) setRemoving(null); }
  };
  const test = async (node: ProxyNode) => {
    if (!supported || !node.enabled || node.sourceMissing || pending.current) return;
    pending.current = true;
    setTesting(node.id); setTestResult(null); setError('');
    try {
      const result = await testProxyNode(node.id);
      if (active.current) { setTestResult({ label: node.label, result }); await load(); }
    } catch (reason) {
      if (active.current) {
        await load();
        if (active.current) setError(managedErrorMessage(reason, '代理连接测试失败，请检查节点服务。'));
      }
    } finally { pending.current = false; if (active.current) setTesting(null); }
  };

  const toggle = async (node: ProxyNode, enabled: boolean) => {
    if (!supported || pending.current || (enabled && node.sourceMissing)) return;
    pending.current = true;
    setChanging(node.id); setError('');
    try {
      await setProxyNodeEnabled(node.id, { enabled, version: node.version });
      if (active.current) { message.success(enabled ? '节点已启用' : '节点已停用'); await load(); }
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, '节点状态更新失败，请刷新后重试。'));
    } finally { pending.current = false; if (active.current) setChanging(null); }
  };

  const columns: TableColumnsType<ProxyNode> = [
    { title: '节点名称', dataIndex: 'label', render: (label: string) => <Typography.Text strong>{label}</Typography.Text> },
    { title: '协议', dataIndex: 'protocol', render: (protocol: string) => <Tag>{protocol === 'SHADOWSOCKS' ? 'SS' : protocol}</Tag> },
    { title: '来源', render: (_, node) => <Space orientation="vertical" size={2}>
      <Tag>{node.sourceSubscriptionId ? '订阅节点' : '手动节点'}</Tag>
      {node.sourceMissing ? <Tag color="warning">订阅中已移除</Tag> : null}
    </Space> },
    { title: '服务器', render: (_, node) => `${node.host.includes(':') ? `[${node.host}]` : node.host}:${node.port}` },
    { title: '出口 IP', dataIndex: 'egressIp', render: (value: string | null) => value || '尚未获取' },
    { title: '出口地区', render: (_, node) => locationText(node) },
    { title: '连接测试', render: (_, node) => <Space orientation="vertical" size={2}>
      <Tag color={node.testStatus === 'CONNECTED' ? 'success' : node.testStatus === 'FAILED' ? 'error' : 'default'}>
        {node.testStatus === 'CONNECTED' ? '测试成功' : node.testStatus === 'FAILED' ? '测试失败' : '未测试'}</Tag>
      <Typography.Text type="secondary">{dateText(node.testedAt)}</Typography.Text>
    </Space> },
    { title: '状态', render: (_, node) => <Switch aria-label={`启停 ${node.label}`} checked={node.enabled}
      checkedChildren="启用" unCheckedChildren="停用" loading={changing === node.id}
      disabled={!supported || busy || Boolean(node.sourceMissing && !node.enabled)}
      onChange={(enabled) => void toggle(node, enabled)} /> },
    { title: '节点授权', render: (_, node) => node.hasCredentials ? '已加密保存' : '无需授权' },
    { title: '操作', width: 200, render: (_, node) => <Space size={0}>
      <Button type="link" aria-label={`测试 ${node.label}`} disabled={!supported || busy || !node.enabled || node.sourceMissing}
        loading={testing === node.id} onClick={() => void test(node)}>测试节点</Button>
      <Button type="text" aria-label={`编辑 ${node.label}`} icon={<EditOutlined />}
        title={node.sourceSubscriptionId ? '订阅节点请通过订阅更新，启停可单独操作' : undefined}
        disabled={!supported || busy || Boolean(node.sourceSubscriptionId)
          || !protocols.includes(node.protocol as NodeForm['protocol'])} onClick={() => edit(node)} />
      <Popconfirm title={`删除「${node.label}」？`} description="正在被官方账户使用的节点不可删除。"
        okText="确认删除" cancelText="取消" onConfirm={() => void remove(node)}>
        <Button type="text" aria-label={`删除 ${node.label}`} danger icon={<DeleteOutlined />} loading={removing === node.id} disabled={!supported || busy} />
      </Popconfirm>
    </Space> },
  ];

  return <PageContainer title="代理节点" content="管理手动节点与代理订阅。每个官方账户独立选择单节点或前置 → 出口双节点链路，所有官方请求均经过该账户保存的链路。"
    extra={<Space wrap>
      <Button icon={<ReloadOutlined />} disabled={busy} loading={loading} onClick={() => void load()}>刷新</Button>
      <Button disabled={!supported || busy} onClick={() => setSubscriptionsOpen(true)}>管理订阅</Button>
      <Button type="primary" aria-label="添加代理节点" icon={<PlusOutlined />} disabled={!supported || busy} onClick={() => edit(null)}>添加代理节点</Button>
    </Space>}>
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <ManagementTransportNotice supported={supported} />
      {error ? <Alert type="error" showIcon title="代理节点操作未完成" description={error} /> : null}
      {testResult ? <Alert showIcon type={testResult.result.connected ? 'success' : 'warning'}
        title={`${testResult.label}：${testResult.result.connected ? '节点连接测试成功' : '节点连接测试失败'}`}
        description="测试通过此节点检查连通性并获取出口信息，不能证明官方账户授权可用；不会自动切换节点或重试。" /> : null}
      <Typography.Text type="secondary">出口 IP 和地区来自最近一次节点测试，可能与节点服务器地址不同。保存后请点击“测试节点”，再到官方账户的“授权登录”选择节点。</Typography.Text>
      <Table<ProxyNode> rowKey="id" columns={columns} dataSource={nodes} loading={loading}
        pagination={false} scroll={{ x: 1200 }} locale={{ emptyText: '暂无代理节点，请先添加并测试一个节点。' }} />
    </Space>
    {subscriptionsOpen ? <SubscriptionManager supported={supported} onClose={() => setSubscriptionsOpen(false)} onChanged={load} /> : null}
    <Modal open={open} title={editing ? '编辑代理节点' : '添加代理节点'} destroyOnHidden
      closable={!saving} mask={{ closable: !saving }} keyboard={!saving} cancelButtonProps={{ disabled: saving }}
      onCancel={() => { if (!saving) closeEdit(); }} onOk={() => void save()} okText="保存" cancelText="取消"
      okButtonProps={{ loading: saving, disabled: !supported }}>
      {formError ? <Alert type="error" showIcon title={formError} /> : null}
      <Form form={form} layout="vertical" preserve={false} disabled={saving}>
        <Form.Item name="label" label="节点名称" rules={[{ required: true, whitespace: true, message: '请输入节点名称' }]}>
          <Input maxLength={120} />
        </Form.Item>
        <Form.Item name="protocol" label="协议" rules={[{ required: true, message: '请选择 HTTP、HTTPS 或 SOCKS5 协议' }]}>
          <Select options={protocols.map((value) => ({ value, label: value }))} />
        </Form.Item>
        <Form.Item name="host" label="服务器" rules={[{ required: true, whitespace: true, message: '请输入服务器域名或 IP' },
          { pattern: /^[^\s/@?#\\]+$/, message: '只填写服务器域名或 IP，不含协议、路径或凭据' }]}>
          <Input maxLength={253} autoComplete="off" placeholder="例如 proxy.example.com" />
        </Form.Item>
        <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' },
          { type: 'integer', min: 1, max: 65535, message: '端口必须是 1 至 65535 的整数' }]}>
          <InputNumber style={{ width: '100%' }} controls={false} />
        </Form.Item>
        {editing?.hasCredentials ? <Form.Item name="clearCredentials" valuePropName="checked">
          <Checkbox onChange={(event) => { if (event.target.checked) clearSecrets(); }}>清除已保存的用户名和密码</Checkbox>
        </Form.Item> : null}
        <Form.Item name="username" label="用户名（可选）" extra={editing ? '已保存凭据不回显；两项留空保留原凭据，填写时同时提供新用户名和密码。' : '无需认证时，用户名和密码均可留空。'}
          dependencies={['password', 'clearCredentials']} rules={[({ getFieldValue }) => ({
            validator: (_, value) => !getFieldValue('clearCredentials') && getFieldValue('password') && !value?.trim()
              ? Promise.reject(new Error('请同时填写用户名和密码')) : Promise.resolve(),
          })]}>
          <Input disabled={saving || clearCredentials} maxLength={255} autoComplete="off" />
        </Form.Item>
        <Form.Item name="password" label="密码（可选）" dependencies={['username', 'clearCredentials']} rules={[({ getFieldValue }) => ({
          validator: (_, value) => !getFieldValue('clearCredentials') && getFieldValue('username')?.trim() && !value
            ? Promise.reject(new Error('请同时填写用户名和密码')) : Promise.resolve(),
        })]}>
          <Input.Password disabled={saving || clearCredentials} autoComplete="new-password" maxLength={4096} />
        </Form.Item>
        <Form.Item name="enabled" label="启用节点" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
  </PageContainer>;
}
