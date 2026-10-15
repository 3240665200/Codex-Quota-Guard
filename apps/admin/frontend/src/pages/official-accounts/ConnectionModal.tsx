import { Alert, App as AntApp, Button, Card, Collapse, Descriptions, Divider, Empty, Form, Input,
  Modal, Popconfirm, Select, Space, Spin, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { managedErrorCode, managedErrorMessage } from '../../components/managedErrors';
import { deleteOfficialAccountConnection, getOfficialAccountConnection, getOfficialAccountSessions,
  isSecureManagementContext, listProxyNodes, refreshOfficialAccountConnection,
  saveOfficialAccountConnection } from '../../services/api';
import type { OfficialAccountConnection, OfficialAccountResponse, OfficialAccountSnapshot,
  OfficialConnectionInput, OfficialConnectionStatus, OfficialSessionDevice, ProxyNode } from '../../services/api';
import { LOCAL_PREVIEW_PARAMETER_MAX_BYTES, parseLocalPreviewParameters } from './localPreviewParameters';
import BrowserLoginPanel from './BrowserLoginPanel';
import SessionBindingQueue from './SessionBindingQueue';
import { SessionBindingResult, SessionBindingsTable } from './SessionBindings';
import { listSessionBindings } from '../../services/sessionBindingsApi';
import type { SessionBinding } from '../../services/sessionBindingsApi';

const statusLabels: Record<OfficialConnectionStatus, string> = {
  NOT_CONFIGURED: '未配置', UNVERIFIED: '待验证', CONNECTED: '已连接', AUTH_REQUIRED: '需要重新授权',
  ACCESS_BLOCKED: '官方拒绝访问', RATE_LIMITED: '请求受限', UNAVAILABLE: '暂不可用', INVALID_RESPONSE: '响应校验失败',
};
const shown = (value: string | null | undefined) => value || '未提供';
const dateText = (value: string | null | undefined) => {
  if (!value) return '暂无';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '未提供' : date.toLocaleString('zh-CN');
};
const booleanText = (value: boolean | null | undefined) => value == null ? '未知' : value ? '是' : '否';

function SessionSnapshot({ snapshot, officialAccountId, bindings }: {
  snapshot: OfficialAccountSnapshot; officialAccountId: string; bindings: SessionBinding[] | null;
}) {
  const identity = snapshot.account;
  const columns: TableColumnsType<OfficialSessionDevice> = [
    { title: '设备 / 会话', render: (_, device) => <Space orientation="vertical" size={2}>
      <Typography.Text strong>{device.displayName || device.deviceModel || '未命名设备'}</Typography.Text>
      <Typography.Text type="secondary">{shown(device.humanReadableDescription)}</Typography.Text>
      <Space size={4} wrap>
        {device.currentDevice === true ? <Tag color="cyan">当前设备</Tag> : null}
        {device.trustedDevice === true ? <Tag color="success">受信任</Tag> : null}
      </Space>
    </Space> },
    { title: '应用', render: (_, device) => device.applications.length ? device.applications.join('、') : '未提供' },
    { title: '位置', render: (_, device) => [device.city, device.regionCode, device.country].filter(Boolean).join(' / ') || '未提供' },
    { title: '最近登录', render: (_, device) => dateText(device.lastSignedInAt) },
    { title: '本系统绑定', render: (_, device) => bindings ? <SessionBindingResult
      officialAccountId={officialAccountId} sessionId={device.sessionId} bindings={bindings} />
      : <Typography.Text type="secondary">绑定结果暂不可用</Typography.Text> },
  ];
  return <Space orientation="vertical" size={16} style={{ width: '100%' }}>
    <Card size="small" title="官方账户身份">
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'email', label: '官方邮箱', children: shown(identity.email) },
        { key: 'name', label: '官方用户名', children: shown(identity.userName) },
        { key: 'user', label: '官方 User ID', children: shown(identity.userId) },
        { key: 'account', label: '官方 Account ID', children: shown(identity.accountId) },
        { key: 'workspace', label: '官方工作区', children: shown(identity.accountName) },
        { key: 'plan', label: '套餐', children: shown(identity.planType) },
        { key: 'role', label: '账户角色', children: shown(identity.accountUserRole) },
        { key: 'subscription', label: '订阅有效', children: booleanText(identity.activeSubscription) },
        { key: 'deactivated', label: '账户已停用', children: booleanText(identity.deactivated) },
      ]} />
    </Card>
    <Typography.Text type="secondary">官方快照时间：{dateText(snapshot.fetchedAt)}。这是最近一次成功读取的快照，设备状态可能已变化。</Typography.Text>
    {snapshot.sessions.showSessionManager === false ? <Alert type="info" showIcon
      title="官方当前未开放此账户的会话管理信息。" /> : null}
    <Table<OfficialSessionDevice> size="small" rowKey="renderId" columns={columns}
      dataSource={snapshot.sessions.devices} pagination={false} scroll={{ x: 820 }}
      locale={{ emptyText: '官方未返回设备会话' }}
      expandable={{ expandedRowRender: (device) => <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'session', label: '官方 Session ID', children: shown(device.sessionId) },
        { key: 'platform', label: '平台', children: shown(device.platform) },
        { key: 'model', label: '设备型号', children: shown(device.deviceModel) },
        { key: 'os', label: '系统版本', children: shown(device.osVersion) },
        { key: 'created', label: '会话创建时间', children: dateText(device.sessionCreatedAt) },
        { key: 'signedIn', label: '最近登录时间', children: dateText(device.lastSignedInAt) },
        { key: 'current', label: '当前设备', children: booleanText(device.currentDevice) },
        { key: 'trusted', label: '受信任设备', children: booleanText(device.trustedDevice) },
      ]} /> }} />
  </Space>;
}

export default function ConnectionModal({ account, onClose }: {
  account: OfficialAccountResponse; onClose: () => void;
}) {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<OfficialConnectionInput>();
  const [config, setConfig] = useState<OfficialAccountConnection | null>(null);
  const [nodes, setNodes] = useState<ProxyNode[]>([]);
  const [snapshot, setSnapshot] = useState<OfficialAccountSnapshot | null>(null);
  const [bindings, setBindings] = useState<SessionBinding[] | null>(null);
  const [bindingNotice, setBindingNotice] = useState('');
  const [browserLoginActive, setBrowserLoginActive] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'save' | 'refresh' | 'delete' | 'import' | null>(null);
  const [error, setError] = useState('');
  const [snapshotNotice, setSnapshotNotice] = useState('');
  const active = useRef(true);
  const parameterFileInput = useRef<HTMLInputElement>(null);
  const parameterFileReader = useRef<FileReader | null>(null);
  const secure = isSecureManagementContext();
  const localPreview = typeof window !== 'undefined' && window.location.protocol === 'http:'
    && ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname);
  const selectedNode = Form.useWatch('proxyNodeId', form);
  const locked = loading || busy !== null || browserLoginActive;

  const clearSecrets = () => form.setFieldsValue({ authorization: '', cookie: '' });
  const stopParameterImport = () => {
    const reader = parameterFileReader.current;
    parameterFileReader.current = null;
    if (reader?.readyState === FileReader.LOADING) reader.abort();
  };
  const close = () => { active.current = false; stopParameterImport(); clearSecrets(); form.resetFields(); onClose(); };

  const importParameters = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file || locked || !localPreview || !secure || !config?.encryptionReady) return;
    if (!file.name.toLowerCase().endsWith('.txt') || file.size > LOCAL_PREVIEW_PARAMETER_MAX_BYTES) {
      setError('请选择不超过 64 KiB 的本机 .txt 参数文件。');
      return;
    }
    setBusy('import'); setError('');
    const reader = new FileReader();
    parameterFileReader.current = reader;
    const finish = () => {
      if (parameterFileReader.current !== reader) return;
      parameterFileReader.current = null;
      if (active.current) setBusy(null);
    };
    reader.onload = () => {
      if (!active.current || parameterFileReader.current !== reader) return;
      try {
        if (typeof reader.result !== 'string') throw new Error('Invalid local file');
        const draft = parseLocalPreviewParameters(reader.result);
        form.setFieldsValue(draft);
        message.success('三项授权参数已填入本机草稿，尚未保存');
      } catch {
        setError('本机参数文件格式不正确，请检查三段标签、对应参数及允许的官方登录 Cookie。');
      } finally { finish(); }
    };
    reader.onerror = () => {
      if (active.current && parameterFileReader.current === reader) setError('无法读取本机参数文件，请重新选择。');
      finish();
    };
    reader.onabort = finish;
    reader.readAsText(file, 'UTF-8');
  };

  useEffect(() => {
    active.current = true;
    let cancelled = false;
    void Promise.allSettled([getOfficialAccountConnection(account.id), listProxyNodes(), getOfficialAccountSessions(account.id), listSessionBindings(account.id)])
      .then(([connectionResult, nodesResult, snapshotResult, bindingsResult]) => {
        if (cancelled || !active.current) return;
        if (connectionResult.status === 'fulfilled') {
          const saved = connectionResult.value;
          setConfig(saved);
          form.setFieldsValue({ authorization: '', cookie: '', requestedAccountId: saved.requestedAccountId ?? '',
            proxyNodeId: saved.proxyNodeId, version: saved.version });
        } else setError(managedErrorMessage(connectionResult.reason, '官方授权配置读取失败，请关闭窗口后重试。'));
        if (nodesResult.status === 'fulfilled') setNodes(nodesResult.value);
        else setError(managedErrorMessage(nodesResult.reason, '代理节点列表读取失败，请关闭窗口后重试。'));
        if (snapshotResult.status === 'fulfilled') setSnapshot(snapshotResult.value);
        else setSnapshotNotice(managedErrorCode(snapshotResult.reason) === 'OFFICIAL_SNAPSHOT_UNAVAILABLE'
          ? '尚无官方会话快照，请手动刷新官方账户与会话。'
          : managedErrorMessage(snapshotResult.reason, '官方会话快照读取失败，可手动刷新后重试。'));
        if (bindingsResult.status === 'fulfilled') setBindings(bindingsResult.value);
        else setBindingNotice(managedErrorMessage(bindingsResult.reason, '绑定结果暂时无法读取，请关闭窗口后重试。'));
        setLoading(false);
      });
    return () => { cancelled = true; active.current = false; stopParameterImport(); };
  }, [account.id, form]);

  const reloadAfterBrowserLogin = async () => {
    clearSecrets();
    const [connectionResult, snapshotResult, bindingsResult] = await Promise.allSettled([
      getOfficialAccountConnection(account.id), getOfficialAccountSessions(account.id), listSessionBindings(account.id),
    ]);
    if (!active.current) return;
    if (connectionResult.status === 'fulfilled') {
      const saved = connectionResult.value;
      setConfig(saved);
      form.setFieldsValue({ requestedAccountId: saved.requestedAccountId ?? '', proxyNodeId: saved.proxyNodeId, version: saved.version });
    } else setError(managedErrorMessage(connectionResult.reason, '授权已保存，但连接状态暂时无法读取，请关闭窗口后重新打开。'));
    if (snapshotResult.status === 'fulfilled') { setSnapshot(snapshotResult.value); setSnapshotNotice(''); }
    else {
      setSnapshot(null);
      setSnapshotNotice(managedErrorMessage(snapshotResult.reason, '授权已保存，尚无可用的官方会话快照，请手动刷新。'));
    }
    if (bindingsResult.status === 'fulfilled') { setBindings(bindingsResult.value); setBindingNotice(''); }
    else { setBindings(null); setBindingNotice(managedErrorMessage(bindingsResult.reason, '绑定结果暂时无法读取，请关闭窗口后重试。')); }
  };

  const save = async () => {
    if (locked || !config?.encryptionReady || !secure) return;
    let values: OfficialConnectionInput;
    try { values = await form.validateFields(); } catch { return; }
    const body: OfficialConnectionInput = {
      authorization: values.authorization.trim(), requestedAccountId: values.requestedAccountId.trim(),
      proxyNodeId: values.proxyNodeId || null, version: config.version,
      ...(values.cookie?.trim() ? { cookie: values.cookie.trim() } : {}),
    };
    setBusy('save'); setError(''); clearSecrets();
    try {
      const saved = await saveOfficialAccountConnection(account.id, body);
      if (!active.current) return;
      setConfig(saved); setSnapshot(null);
      setSnapshotNotice('尚无官方会话快照，请手动刷新官方账户与会话。');
      message.success('官方授权已加密保存，等待手动验证');
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, '官方授权保存失败，请检查配置后重新输入。'));
    } finally {
      body.authorization = ''; delete body.cookie;
      values.authorization = ''; delete values.cookie;
      if (active.current) { clearSecrets(); setBusy(null); }
    }
  };

  const refresh = async () => {
    if (locked || !config?.configured || !secure) return;
    setBusy('refresh'); setError('');
    try {
      const result = await refreshOfficialAccountConnection(account.id);
      if (active.current) { setConfig(result.connection); setSnapshot(result); setSnapshotNotice(''); }
    } catch (reason) {
      if (!active.current) return;
      setSnapshot(null);
      setSnapshotNotice('官方读取未成功，旧快照已清除；请检查授权和代理后手动刷新。');
      setError(managedErrorMessage(reason, '官方读取失败，请检查授权和代理后手动重试。'));
      // Reload safe status only; never retry the official upstream request automatically.
      try {
        const saved = await getOfficialAccountConnection(account.id);
        if (active.current) setConfig(saved);
      } catch { /* The primary refresh error already provides the actionable state. */ }
    } finally { if (active.current) setBusy(null); }
  };

  const remove = async () => {
    if (locked || !secure) return;
    setBusy('delete'); setError(''); clearSecrets();
    try {
      await deleteOfficialAccountConnection(account.id);
      if (active.current) { message.success('保存的官方授权已删除'); close(); }
    } catch (reason) {
      if (active.current) setError(managedErrorMessage(reason, '官方授权删除失败，请稍后重试。'));
    } finally { if (active.current) setBusy(null); }
  };

  const missingNode = config?.proxyNodeId && !nodes.some((node) => node.id === config.proxyNodeId);
  const nodeUnavailable = selectedNode && !nodes.some((node) => node.id === selectedNode && node.enabled);
  return <Modal open title={`授权与会话 · ${account.label}`} width={960} onCancel={close} destroyOnHidden
    styles={{ body: { maxHeight: '74vh', overflowY: 'auto' } }}
    footer={<Space wrap>
      {config?.configured ? <Popconfirm title="删除保存的官方授权？"
        description="同时清除后台保存的官方会话快照，不会使官方设备下线，也不影响账户登记和邮箱配置。"
        okText="确认删除" cancelText="取消" onConfirm={() => void remove()}>
        <Button danger disabled={locked || !secure} loading={busy === 'delete'}>删除保存的授权</Button>
      </Popconfirm> : null}
      <Button disabled={locked || !secure || !config?.configured} loading={busy === 'refresh'} onClick={() => void refresh()}>刷新官方账户与会话</Button>
      <Button onClick={close}>关闭</Button>
    </Space>}>
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      {!secure ? <Alert type="warning" showIcon title="请通过 HTTPS 打开管理后台后管理官方授权与会话；HTTP 仅允许本机调试。" /> : null}
      {secure && window.location.protocol === 'http:' ? <Alert type="warning" showIcon
        title="当前为本机 HTTP 调试模式；生产环境仍需 HTTPS。" /> : null}
      {config && !config.encryptionReady ? <Alert type="warning" showIcon title="服务端缺少官方授权加密密钥，暂时不能保存授权。" /> : null}
      {error ? <Alert type="error" showIcon title="官方账户操作未完成" description={error} /> : null}
      <Spin spinning={loading}>
        <Space orientation="vertical" size={16} style={{ width: '100%' }}>
          {config ? <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
            { key: 'status', label: '连接状态', children: <Tag color={config.status === 'CONNECTED' ? 'success' : config.status === 'UNVERIFIED' ? 'processing' : 'default'}>{statusLabels[config.status] ?? '未知'}</Tag> },
            { key: 'saved', label: '已保存授权', children: config.hasAuthorization ? `Authorization${config.hasCookie ? ' / Cookie' : ''}（加密保存，不回显）` : '暂无' },
            { key: 'import', label: '授权保存时间', children: dateText(config.importedAt) },
            { key: 'attempt', label: '最近尝试', children: dateText(config.lastAttemptAt) },
            { key: 'success', label: '最近成功', children: dateText(config.lastSuccessAt) },
          ]} /> : null}
          {config?.lastErrorCode ? <Alert type="warning" showIcon title={managedErrorMessage({ code: config.lastErrorCode }, '最近一次官方读取未成功，请检查授权和代理配置。')} /> : null}
          <Form form={form} layout="vertical" preserve={false} disabled={locked || !secure || !config?.encryptionReady}>
            <Form.Item name="proxyNodeId" label="访问代理节点" extra="后台读取官方账户时使用此节点或直连；不会失败回退或自动切换出口。本机登录浏览器使用管理员电脑当前网络。">
              <Select virtual={false} allowClear placeholder="直连（不使用代理）" options={[
                ...nodes.map((node) => ({ value: node.id, label: `${node.label} · ${node.protocol}${node.enabled ? '' : '（已停用）'}`, disabled: !node.enabled })),
                ...(missingNode ? [{ value: config.proxyNodeId!, label: '已保存的节点（当前不可用）', disabled: true }] : []),
              ]} />
            </Form.Item>
            {nodeUnavailable ? <Alert type="warning" showIcon title="当前代理节点不可用，请明确选择其他节点或清空为直连，再重新保存授权。" /> : null}
            <Space orientation="vertical" size={16} style={{ width: '100%' }}>
              <BrowserLoginPanel accountId={account.id} version={config?.version ?? 0} proxyNodeId={selectedNode || null}
                disabled={loading || busy !== null || !secure || !config?.encryptionReady || Boolean(nodeUnavailable)}
                onActiveChange={setBrowserLoginActive} onResultChanged={reloadAfterBrowserLogin} />
              <Collapse style={{ width: '100%' }} onChange={(keys) => { if (!keys.includes('manual')) clearSecrets(); }} items={[{
                key: 'manual', label: '高级：手动导入授权', children: <Space orientation="vertical" size={16} style={{ width: '100%' }}>
                  <Alert type="info" showIcon title="手动导入你自己的官方网页登录授权"
                    description="适用于已有授权参数的管理员。每次保存会完整替换授权，Cookie 留空会清除旧 Cookie。提交后立即清空，不在浏览器存储中保存。" />
          {localPreview ? <Alert type="info" showIcon title="本机参数文件导入草稿"
            description={<Space orientation="vertical" size={8}>
              <span id="local-preview-parameters-description">文件仅由当前浏览器读取，不上传。填入会替换 Account ID、Authorization 和 Cookie，保留代理选择；不会保存授权或刷新官方会话。</span>
              <input ref={parameterFileInput} id="local-preview-parameters-file" type="file" accept=".txt,text/plain"
                aria-label="本机参数文件" aria-describedby="local-preview-parameters-description" hidden
                onChange={importParameters} />
              <Button disabled={locked || !secure || !config?.encryptionReady} loading={busy === 'import'}
                aria-controls="local-preview-parameters-file" aria-describedby="local-preview-parameters-description"
                onClick={() => parameterFileInput.current?.click()}>从本机参数文件填入</Button>
            </Space>} /> : null}
            <Form.Item name="requestedAccountId" label="官方 Account ID" rules={[
              { required: true, whitespace: true, message: '请输入官方 Account ID' },
              { pattern: /^[^\x00-\x1f\x7f]+$/, message: '官方 Account ID 不能包含控制字符' },
            ]} extra="填写官方工作区的 Account ID，与本系统的用户 ID 或账户登记 ID 不同。已验证账户更换身份前，需要先删除保存的授权。">
              <Input maxLength={160} autoComplete="off" />
            </Form.Item>
            <Form.Item name="authorization" label="官方 Authorization" rules={[
              { required: true, whitespace: true, message: '请输入官方网页登录后的 Authorization' },
              { pattern: /^Bearer [^\s\x00-\x1f\x7f]+$/, message: '请输入完整的 Bearer 授权值，不要粘贴请求脚本或文件内容' },
            ]} extra="填写完整的 Bearer 授权值。提交后立即清空，不在浏览器存储中保存。">
              <Input.Password autoComplete="new-password" maxLength={16384} />
            </Form.Item>
            <Form.Item name="cookie" label="官方 Cookie（可选）" extra="仅接受你自己的 __Secure-next-auth.session-token（含 .0、.1 等数字分块）登录 Cookie；不接受其他 Cookie。官方拒绝访问时仍会显示失败状态，不保证导入后可连接。">
              <Input.Password autoComplete="new-password" maxLength={32768} />
            </Form.Item>
                  <Button disabled={locked || !secure || !config?.encryptionReady || Boolean(nodeUnavailable)}
                    loading={busy === 'save'} onClick={() => void save()}>保存授权</Button>
                </Space>,
              }]} />
            </Space>
          </Form>
          <Divider style={{ margin: '4px 0' }}>官方账户与设备会话</Divider>
          {secure ? <SessionBindingQueue accountId={account.id} disabled={locked} /> : null}
          {snapshot ? <SessionSnapshot snapshot={snapshot} officialAccountId={account.id} bindings={bindings} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={snapshotNotice || '尚无官方会话快照，请手动刷新官方账户与会话。'} />}
          {bindingNotice ? <Alert type="warning" showIcon title={bindingNotice} /> : null}
          {bindings ? <SessionBindingsTable bindings={bindings} /> : null}
        </Space>
      </Spin>
    </Space>
  </Modal>;
}
