import { Alert, App as AntApp, Button, Checkbox, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { managedErrorMessage } from '../../components/managedErrors';
import { createProxySubscription, deleteProxySubscription, listProxySubscriptions,
  refreshProxySubscription, updateProxySubscription } from '../../services/api';
import type { ProxySubscription } from '../../services/api';

type SubscriptionForm = { label: string; url: string; allowInsecureTrojan: boolean };
const dateText = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '尚未更新';

export default function SubscriptionManager({ supported, onClose, onChanged }: {
  supported: boolean; onClose: () => void; onChanged: () => Promise<void>;
}) {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<SubscriptionForm>();
  const allowInsecureTrojan = Form.useWatch('allowInsecureTrojan', form);
  const [subscriptions, setSubscriptions] = useState<ProxySubscription[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<ProxySubscription | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const active = useRef(true);
  const pending = useRef(false);
  const loadRevision = useRef(0);
  const load = useCallback(async () => {
    const revision = ++loadRevision.current;
    setLoading(true);
    try {
      const result = await listProxySubscriptions();
      if (active.current && revision === loadRevision.current) { setSubscriptions(result); setError(''); }
    } catch (reason) {
      if (active.current && revision === loadRevision.current) setError(managedErrorMessage(reason, '订阅读取失败，请稍后重试。'));
    } finally { if (active.current && revision === loadRevision.current) setLoading(false); }
  }, []);
  useEffect(() => {
    active.current = true; void load();
    return () => { active.current = false; loadRevision.current += 1; };
  }, [load]);
  const clearUrl = () => form.setFieldsValue({ url: '' });
  const closeEditor = () => { clearUrl(); form.resetFields(); setEditing(null); setEditorOpen(false); setFormError(''); };
  const edit = (source: ProxySubscription | null) => {
    form.resetFields(); setEditing(source); setFormError('');
    form.setFieldsValue({ label: source?.label ?? '', url: '', allowInsecureTrojan: source?.allowInsecureTrojan ?? false }); setEditorOpen(true);
  };
  const save = async () => {
    if (!supported || pending.current) return;
    let values: SubscriptionForm;
    try { values = await form.validateFields(); } catch { return; }
    if (!active.current || pending.current) return;
    pending.current = true; setBusy(true); setFormError('');
    const body = { label: values.label.trim(), allowInsecureTrojan: values.allowInsecureTrojan === true,
      ...(values.url?.trim() ? { url: values.url.trim() } : {}) };
    clearUrl(); values.url = '';
    try {
      if (editing) await updateProxySubscription(editing.id, { ...body, version: editing.version });
      else await createProxySubscription({ ...body, url: body.url! });
      if (!active.current) return;
      closeEditor(); setError(''); message.success(editing ? '订阅已保存，需要时请手动更新节点' : '订阅已导入');
      await Promise.all([load(), onChanged()]);
    } catch (reason) {
      if (active.current) setFormError(managedErrorMessage(reason, '订阅保存失败，请检查订阅地址和格式后重试。'));
    } finally {
      delete body.url; values.url = ''; pending.current = false;
      if (active.current) { clearUrl(); setBusy(false); }
    }
  };
  const change = async (source: ProxySubscription, action: 'refresh' | 'remove') => {
    if (!supported || pending.current) return;
    pending.current = true; setBusy(true); setError('');
    try {
      if (action === 'refresh') await refreshProxySubscription(source.id, source.version);
      else await deleteProxySubscription(source.id, source.version);
      if (!active.current) return;
      message.success(action === 'refresh' ? '订阅节点已更新' : '订阅来源已移除，节点已保留');
      await Promise.all([load(), onChanged()]);
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, action === 'refresh'
        ? '订阅更新失败，现有节点已保留，请检查订阅后手动重试。' : '订阅移除失败，请刷新列表后重试。'));
    } finally { pending.current = false; if (active.current) setBusy(false); }
  };
  const columns: TableColumnsType<ProxySubscription> = [
    { title: '订阅名称', dataIndex: 'label' },
    { title: '来源域名', dataIndex: 'sourceHost' },
    { title: '节点', render: (_, source) => `${source.enabledNodeCount} 启用 / ${source.nodeCount} 总计` },
    { title: '状态', render: (_, source) => <Space wrap>
      <Tag color={source.status === 'URL_UPDATED' ? 'warning' : 'default'}>
        {source.status === 'URL_UPDATED' ? '地址已修改，待手动更新' : '已保存'}</Tag>
      {source.allowInsecureTrojan ? <Tag color="warning">Trojan 兼容已开启</Tag> : null}
    </Space> },
    { title: '最近更新', render: (_, source) => dateText(source.refreshedAt) },
    { title: '操作', render: (_, source) => <Space wrap>
      <Button aria-label={`更新订阅 ${source.label}`} disabled={!supported || busy || !source.hasUrl}
        onClick={() => void change(source, 'refresh')}>更新节点</Button>
      <Button aria-label={`编辑订阅 ${source.label}`} disabled={!supported || busy} onClick={() => edit(source)}>编辑</Button>
      <Popconfirm title={`移除订阅「${source.label}」？`} description={source.allowInsecureTrojan
        ? '移除来源会恢复此前例外节点的证书验证并停用这些节点，保留所有节点 ID 与账户选择。'
        : '只解除订阅来源，保留所有节点与账户选择。'}
        okText="仅移除来源" cancelText="取消" onConfirm={() => void change(source, 'remove')}>
        <Button danger aria-label={`移除订阅 ${source.label}`} disabled={!supported || busy}>移除来源</Button>
      </Popconfirm>
    </Space> },
  ];
  return <Modal open title={editorOpen ? editing ? '编辑代理订阅' : '导入代理订阅' : '代理订阅管理'}
    width={editorOpen ? 560 : 1000} onCancel={() => { if (!busy) { if (editorOpen) closeEditor(); else onClose(); } }}
    closable={!busy} mask={{ closable: !busy }} keyboard={!busy}
    footer={editorOpen ? <Space>
      <Button disabled={busy} onClick={closeEditor}>取消</Button>
      <Button type="primary" disabled={!supported || busy} loading={busy} onClick={() => void save()}>{editing ? '保存订阅' : '导入并获取节点'}</Button>
    </Space> : <Button disabled={busy} onClick={onClose}>关闭</Button>}>
      {!editorOpen ? <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <Typography.Text>订阅仅在首次导入和点击“更新节点”时获取。支持 Clash YAML、V2Ray URI 列表及 Base64；协议支持 HTTP、HTTPS、SOCKS5、SS、VMess、VLESS、Trojan，不支持的内容会明确提示。</Typography.Text>
        <Typography.Text type="secondary">订阅 URL 须为公网 HTTPS 443 地址，加密保存且不回显。最多 128 个节点、1 MiB 正文。更新后消失的节点会标记不可用；请核对账户链路，节点不会自动切换。</Typography.Text>
        {error ? <Alert type="error" showIcon title={error} /> : null}
        <Space><Button type="primary" disabled={!supported || busy} onClick={() => edit(null)}>导入订阅</Button>
          <Button loading={loading} disabled={busy} onClick={() => { setError(''); void load(); }}>刷新列表</Button></Space>
        <Table<ProxySubscription> rowKey="id" columns={columns} dataSource={subscriptions} loading={loading}
          pagination={false} scroll={{ x: 900 }} locale={{ emptyText: '暂无代理订阅，可导入订阅或继续手动添加节点。' }} />
      </Space> : <>
      {formError ? <Alert type="error" showIcon title={formError} /> : null}
      <Form form={form} layout="vertical" preserve={false} disabled={busy} initialValues={{ allowInsecureTrojan: false }}>
        <Form.Item name="label" label="订阅名称" rules={[{ required: true, whitespace: true, message: '请输入订阅名称' }]}>
          <Input maxLength={120} autoComplete="off" />
        </Form.Item>
        <Form.Item name="url" label="订阅 URL" extra={editing ? '留空保留已保存地址。保存不会获取节点，请随后手动更新。' : '首次导入会立即获取节点，之后由你手动更新。'}
          rules={[{ required: !editing, whitespace: true, message: '请输入订阅 URL' }, { validator: (_, value) => {
            if (!value?.trim()) return Promise.resolve();
            try {
              const parsed = new URL(value.trim());
              if (parsed.protocol !== 'https:' || (parsed.port && parsed.port !== '443') || parsed.username || parsed.password || parsed.hash) throw new Error();
              return Promise.resolve();
            } catch { return Promise.reject(new Error('请输入 HTTPS 443 订阅地址，不含用户名、密码或片段')); }
          } }]}>
          <Input.Password autoComplete="new-password" maxLength={8192} visibilityToggle={false} />
        </Form.Item>
        <Form.Item name="allowInsecureTrojan" valuePropName="checked">
          <Checkbox>允许此订阅的 Trojan 节点跳过证书验证</Checkbox>
        </Form.Item>
        <Typography.Paragraph type="secondary">仅对本订阅 URI 中声明 allowInsecure=1/true 的 Trojan 节点生效；订阅下载、官方站点、其他协议及其他订阅仍验证 TLS 证书。</Typography.Paragraph>
        {allowInsecureTrojan ? <Alert type="warning" showIcon
          title="跳过证书验证可能遭受中间人攻击；仅在信任该订阅及节点时开启。" /> : null}
        {editing?.allowInsecureTrojan ? <Typography.Paragraph type="secondary">
          关闭此选项会恢复此前例外节点的证书验证并停用这些节点，保留节点 ID 与账户引用。
        </Typography.Paragraph> : null}
      </Form>
      </>}
    </Modal>;
}
