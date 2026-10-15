import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, App as AntApp, Button, Form, Input, Modal, Popconfirm, Space, Switch, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { managedErrorMessage } from '../../components/managedErrors';
import ManagementTransportNotice from '../../components/ManagementTransportNotice';
import { createProxyNode, deleteProxyNode, importProxyNodes, isSupportedManagementContext,
  listProxyNodes, testProxyNode, updateProxyNode } from '../../services/api';
import type { ProxyConnectionTest, ProxyImportInput, ProxyNode, ProxyNodeInput } from '../../services/api';

function testDescription(result: ProxyConnectionTest): string {
  if (result.status === 'ACCESS_BLOCKED') return '官方站点返回 403，当前出口访问被拒绝。';
  if (result.status === 'AUTH_REQUIRED') return '官方站点返回 401，需要有效的官方授权。';
  if (result.status === 'CONNECTED') return '已通过此节点收到官方站点的成功响应。';
  return result.httpStatus ? `官方站点已响应（HTTP ${result.httpStatus}）。` : '未获得有效的官方站点响应。';
}

export default function ProxyNodesPage() {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<ProxyNodeInput>();
  const [importForm] = Form.useForm<{ content?: string; subscriptionUrl?: string }>();
  const [nodes, setNodes] = useState<ProxyNode[]>([]);
  const [editing, setEditing] = useState<ProxyNode | null>(null);
  const [open, setOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [importing, setImporting] = useState(false);
  const [testing, setTesting] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [importError, setImportError] = useState('');
  const [testResult, setTestResult] = useState<{ label: string; result: ProxyConnectionTest } | null>(null);
  const active = useRef(true);
  const supported = isSupportedManagementContext();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listProxyNodes();
      if (active.current) { setNodes(result); setError(''); }
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, '代理节点读取失败，请稍后重试。'));
    } finally { if (active.current) setLoading(false); }
  }, []);
  useEffect(() => {
    active.current = true; void load();
    return () => { active.current = false; };
  }, [load]);

  const closeEdit = () => { form.setFieldsValue({ uri: '' }); form.resetFields(); setOpen(false); setFormError(''); };
  const closeImport = () => { importForm.resetFields(); setImportOpen(false); setImportError(''); };
  const edit = (node: ProxyNode | null) => {
    setEditing(node); setFormError('');
    form.setFieldsValue({ label: node?.label ?? '', enabled: node?.enabled ?? true, uri: '' });
    setOpen(true);
  };

  const save = async () => {
    if (!supported || saving) return;
    let values: ProxyNodeInput;
    try { values = await form.validateFields(); } catch { return; }
    const uri = values.uri?.trim();
    const body: ProxyNodeInput = { label: values.label.trim(), enabled: values.enabled,
      ...(editing ? { version: editing.version } : {}), ...(uri ? { uri } : {}) };
    setSaving(true); setFormError(''); form.setFieldsValue({ uri: '' });
    try {
      if (editing) await updateProxyNode(editing.id, body);
      else await createProxyNode(body as ProxyNodeInput & { uri: string });
      if (!active.current) return;
      closeEdit(); message.success('代理节点已保存'); await load();
    } catch (reason) {
      if (active.current) setFormError(managedErrorMessage(reason, '节点保存失败，请检查配置后重新输入链接。'));
    } finally {
      delete values.uri; delete body.uri;
      if (active.current) { form.setFieldsValue({ uri: '' }); setSaving(false); }
    }
  };

  const importNodes = async () => {
    if (!supported || importing) return;
    const values = importForm.getFieldsValue();
    const content = values.content?.trim();
    const subscriptionUrl = values.subscriptionUrl?.trim();
    if (Boolean(content) === Boolean(subscriptionUrl)) {
      setImportError(content ? '节点内容和订阅地址只能填写一项' : '请填写节点内容或 HTTPS 订阅地址'); return;
    }
    if (subscriptionUrl) {
      try {
        const parsed = new URL(subscriptionUrl);
        if (parsed.protocol !== 'https:') throw new Error();
      } catch { setImportError('订阅地址必须使用 HTTPS'); return; }
    }
    const body: ProxyImportInput = content ? { content } : { subscriptionUrl: subscriptionUrl! };
    setImporting(true); setImportError(''); importForm.resetFields();
    try {
      const result = await importProxyNodes(body);
      if (!active.current) return;
      closeImport(); message.success(`已导入 ${result.importedCount} 个节点`); await load();
    } catch (reason) {
      if (active.current) setImportError(managedErrorMessage(reason, '节点导入失败，请检查格式后重新输入。'));
    } finally {
      delete values.content; delete values.subscriptionUrl;
      if ('content' in body) body.content = '';
      if ('subscriptionUrl' in body) body.subscriptionUrl = '';
      if (active.current) { importForm.resetFields(); setImporting(false); }
    }
  };

  const remove = async (node: ProxyNode) => {
    if (!supported) return;
    try {
      await deleteProxyNode(node.id);
      if (active.current) { message.success('代理节点已删除'); await load(); }
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, '代理节点删除失败，请稍后重试。'));
    }
  };
  const test = async (node: ProxyNode) => {
    if (!supported) return;
    setTesting(node.id); setTestResult(null); setError('');
    try {
      const result = await testProxyNode(node.id);
      if (active.current) setTestResult({ label: node.label, result });
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, '代理连接测试失败，请检查节点服务。'));
    } finally { if (active.current) setTesting(null); }
  };

  const columns: TableColumnsType<ProxyNode> = [
    { title: '节点名称', dataIndex: 'label', render: (label: string) => <Typography.Text strong>{label}</Typography.Text> },
    { title: '协议', dataIndex: 'protocol', render: (protocol: string) => <Tag>{protocol === 'SHADOWSOCKS' ? 'SS' : protocol}</Tag> },
    { title: '服务器', render: (_, node) => `${node.host.includes(':') ? `[${node.host}]` : node.host}:${node.port}` },
    { title: '状态', render: (_, node) => <Tag color={node.enabled ? 'success' : 'default'}>{node.enabled ? '启用' : '停用'}</Tag> },
    { title: '节点授权', render: (_, node) => node.hasCredentials ? '已加密保存' : '无需授权' },
    { title: '操作', width: 200, render: (_, node) => <Space size={0}>
      <Button type="link" aria-label={`测试 ${node.label}`} disabled={!supported || !node.enabled || testing !== null}
        loading={testing === node.id} onClick={() => void test(node)}>测试</Button>
      <Button type="text" aria-label={`编辑 ${node.label}`} icon={<EditOutlined />} disabled={!supported || saving || importing} onClick={() => edit(node)} />
      <Popconfirm title={`删除「${node.label}」？`} description="正在被官方账户使用的节点不可删除。"
        okText="确认删除" cancelText="取消" onConfirm={() => void remove(node)}>
        <Button type="text" aria-label={`删除 ${node.label}`} danger icon={<DeleteOutlined />} disabled={!supported} />
      </Popconfirm>
    </Space> },
  ];

  return <PageContainer title="代理节点" content="管理官方账户访问出口。支持 HTTP、HTTPS、SOCKS5、VLESS、VMess、Trojan、SS 链接；节点和订阅中的授权信息由后台加密保存。"
    extra={<Space wrap>
      <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>
      <Button disabled={!supported || saving || importing} onClick={() => { importForm.resetFields(); setImportError(''); setImportOpen(true); }}>导入节点 / 订阅</Button>
      <Button type="primary" aria-label="添加代理节点" icon={<PlusOutlined />} disabled={!supported || saving || importing} onClick={() => edit(null)}>添加代理节点</Button>
    </Space>}>
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <ManagementTransportNotice supported={supported} />
      {error ? <Alert type="error" showIcon title="代理节点操作未完成" description={error} /> : null}
      {testResult ? <Alert showIcon type={testResult.result.status === 'CONNECTED' ? 'success' : 'warning'}
        title={`${testResult.label}：${testDescription(testResult.result)}`}
        description="测试仅访问服务端固定的官方站点，不能证明官方账户授权可用；不会自动切换节点或重试。" /> : null}
      <Typography.Text type="secondary">订阅仅在点击导入时读取，不会自动刷新。账户需要在“授权与会话”中明确选择节点。</Typography.Text>
      <Table<ProxyNode> rowKey="id" columns={columns} dataSource={nodes} loading={loading}
        pagination={false} scroll={{ x: 850 }} locale={{ emptyText: '暂无代理节点' }} />
    </Space>
    <Modal open={open} title={editing ? '编辑代理节点' : '添加代理节点'} destroyOnHidden
      closable={!saving} mask={{ closable: !saving }} keyboard={!saving} cancelButtonProps={{ disabled: saving }}
      onCancel={() => { if (!saving) closeEdit(); }} onOk={() => void save()} okText="保存" cancelText="取消"
      okButtonProps={{ loading: saving, disabled: !supported }}>
      {formError ? <Alert type="error" showIcon title={formError} /> : null}
      <Form form={form} layout="vertical" preserve={false} disabled={saving}>
        <Form.Item name="label" label="节点名称" rules={[{ required: true, whitespace: true, message: '请输入节点名称' }]}>
          <Input maxLength={120} />
        </Form.Item>
        <Form.Item name="uri" label="节点链接" extra={editing ? '已保存的完整链接不回显；留空保持原节点授权，填写新链接则整体替换。' : '输入支持协议的完整节点链接，提交后立即清空。'}
          rules={[{ required: !editing, whitespace: true, message: '请输入节点链接' },
            { pattern: /^(https?|socks5|vless|vmess|trojan|ss):\/\/\S+$/i, message: '请输入支持协议的完整节点链接' }]}>
          <Input.Password autoComplete="new-password" maxLength={32768} />
        </Form.Item>
        <Form.Item name="enabled" label="启用节点" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
    <Modal open={importOpen} title="导入代理节点" width={720} destroyOnHidden
      closable={!importing} mask={{ closable: !importing }} keyboard={!importing} cancelButtonProps={{ disabled: importing }}
      onCancel={() => { if (!importing) closeImport(); }} onOk={() => void importNodes()} okText="导入" cancelText="取消"
      okButtonProps={{ loading: importing, disabled: !supported }}>
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <Alert type="info" showIcon title="填写一种导入来源"
          description="支持每行一个 URI、Base64 编码的 URI 订阅内容，或提供这些内容的 HTTPS 订阅地址。本轮不支持 Clash YAML 或 sing-box JSON。导入后不保存订阅地址、不自动刷新。" />
        {importError ? <Alert type="error" showIcon title={importError} /> : null}
        <Form form={importForm} layout="vertical" preserve={false} disabled={importing}>
          <Form.Item name="content" label="节点链接或 Base64 订阅内容">
            <Input.TextArea autoComplete="off" rows={6} maxLength={1048576} spellCheck={false} />
          </Form.Item>
          <Form.Item name="subscriptionUrl" label="HTTPS 订阅地址" extra="可能包含订阅密钥；仅用于本次导入，提交后清空。">
            <Input.Password autoComplete="new-password" maxLength={8192} />
          </Form.Item>
        </Form>
      </Space>
    </Modal>
  </PageContainer>;
}
