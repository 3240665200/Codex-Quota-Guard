import { Alert, App as AntApp, Button, Card, Checkbox, Collapse, Descriptions, Divider, Empty, Form, Input,
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
import SessionLogoutPanel from './SessionLogoutPanel';
import { SessionBindingResult, SessionBindingsTable } from './SessionBindings';
import { listSessionBindings, releaseSessionBinding } from '../../services/sessionBindingsApi';
import type { SessionBinding } from '../../services/sessionBindingsApi';
import { sessionSelectionReason } from './sessionSelection';

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

function SessionSnapshot({ snapshot, officialAccountId, bindings, selectedSessionIds, onSelectionChange, selectionDisabled }: {
  snapshot: OfficialAccountSnapshot; officialAccountId: string; bindings: SessionBinding[] | null;
  selectedSessionIds: string[]; onSelectionChange: (ids: string[]) => void; selectionDisabled: boolean;
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
    { title: '选择限制', render: (_, device) => <Typography.Text type="secondary">
      {sessionSelectionReason(device, snapshot.sessions.devices) || '—'}</Typography.Text> },
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
    <Typography.Text type="secondary">可勾选 1 至 100 个会话，再点击“登出选中会话”核对预览。已绑定和受信任设备允许明确选中；缺少或重复 ID、当前管理会话以及身份或信任状态未知项不能选择。</Typography.Text>
    {snapshot.sessions.showSessionManager === false ? <Alert type="info" showIcon
      title="官方当前未开放此账户的会话管理信息。" /> : null}
    <Table<OfficialSessionDevice> size="small" rowKey="renderId" columns={columns}
      dataSource={snapshot.sessions.devices} pagination={false} scroll={{ x: 820 }}
      rowSelection={{ selectedRowKeys: snapshot.sessions.devices.filter((device) => device.sessionId
        && selectedSessionIds.includes(device.sessionId)).map((device) => device.renderId),
        onChange: (_, rows) => onSelectionChange(rows.filter((device) => !sessionSelectionReason(device, snapshot.sessions.devices))
          .map((device) => device.sessionId!)),
        getCheckboxProps: (device) => ({ disabled: selectionDisabled || Boolean(sessionSelectionReason(device, snapshot.sessions.devices)),
          'aria-label': `选择登出 ${device.displayName || device.deviceModel || '未命名设备'}（Session ID：${device.sessionId || '未提供'}）` }),
      }}
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
  const [snapshotRevision, setSnapshotRevision] = useState(0);
  const [sessionSelection, setSessionSelection] = useState<{ snapshot: OfficialAccountSnapshot | null; ids: string[] }>({ snapshot: null, ids: [] });
  const [selectionNotice, setSelectionNotice] = useState('');
  const [bindings, setBindings] = useState<SessionBinding[] | null>(null);
  const [bindingNotice, setBindingNotice] = useState('');
  const [bindingRevision, setBindingRevision] = useState(0);
  const [releaseTarget, setReleaseTarget] = useState<SessionBinding | null>(null);
  const [releaseAcknowledged, setReleaseAcknowledged] = useState(false);
  const [releaseBusy, setReleaseBusy] = useState(false);
  const [releaseError, setReleaseError] = useState('');
  const [browserLoginActive, setBrowserLoginActive] = useState(false);
  const [logoutBusy, setLogoutBusy] = useState(false);
  const [queueRevision, setQueueRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'save' | 'refresh' | 'delete' | 'import' | null>(null);
  const [error, setError] = useState('');
  const [snapshotNotice, setSnapshotNotice] = useState('');
  const active = useRef(true);
  const currentAccountId = useRef(account.id); currentAccountId.current = account.id;
  const releaseLifetime = useRef({ accountId: account.id, active: true, busy: false });
  const parameterFileInput = useRef<HTMLInputElement>(null);
  const parameterFileReader = useRef<FileReader | null>(null);
  const secure = isSecureManagementContext();
  const localPreview = typeof window !== 'undefined' && window.location.protocol === 'http:'
    && ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname);
  const selectedNode = Form.useWatch('proxyNodeId', form);
  const externallyLocked = loading || busy !== null || browserLoginActive || releaseBusy;
  const locked = externallyLocked || logoutBusy;
  const selectedSessionIds = sessionSelection.snapshot === snapshot ? sessionSelection.ids : [];
  const isCurrentLifetime = (lifetime: typeof releaseLifetime.current) => active.current && lifetime.active
    && releaseLifetime.current === lifetime && currentAccountId.current === lifetime.accountId;

  const updateSnapshot = (value: OfficialAccountSnapshot | null) => {
    setSnapshot(value); setSnapshotRevision((revision) => revision + 1);
    setSessionSelection({ snapshot: value, ids: [] }); setSelectionNotice('');
  };
  const selectSessions = (ids: string[]) => {
    if (locked || !secure || !snapshot) return;
    if (ids.length > 100) { setSelectionNotice('每次最多选择 100 个会话，请缩小选择范围。'); return; }
    if (ids.some((id) => !snapshot.sessions.devices.some((device) => device.sessionId === id
      && !sessionSelectionReason(device, snapshot.sessions.devices)))) return;
    setSessionSelection({ snapshot, ids: [...ids] }); setSelectionNotice('');
  };

  const clearSecrets = () => form.setFieldsValue({ authorization: '', cookie: '' });
  const stopParameterImport = () => {
    const reader = parameterFileReader.current;
    parameterFileReader.current = null;
    if (reader?.readyState === FileReader.LOADING) reader.abort();
  };
  const close = () => {
    active.current = false; releaseLifetime.current.active = false;
    setSessionSelection({ snapshot: null, ids: [] });
    setReleaseTarget(null); stopParameterImport(); clearSecrets(); form.resetFields(); onClose();
  };

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
    const lifetime = { accountId: account.id, active: true, busy: false }; releaseLifetime.current = lifetime;
    setLoading(true); setConfig(null); updateSnapshot(null); setBindings(null);
    setBusy(null); setBrowserLoginActive(false);
    setError(''); setSnapshotNotice(''); setBindingNotice(''); setLogoutBusy(false);
    setReleaseTarget(null); setReleaseAcknowledged(false); setReleaseBusy(false); setReleaseError(''); setBindingRevision(0);
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
        if (snapshotResult.status === 'fulfilled') updateSnapshot(snapshotResult.value);
        else setSnapshotNotice(managedErrorCode(snapshotResult.reason) === 'OFFICIAL_SNAPSHOT_UNAVAILABLE'
          ? '尚无官方会话快照，请手动刷新官方账户与会话。'
          : managedErrorMessage(snapshotResult.reason, '官方会话快照读取失败，可手动刷新后重试。'));
        if (bindingsResult.status === 'fulfilled') setBindings(bindingsResult.value);
        else setBindingNotice(managedErrorMessage(bindingsResult.reason, '绑定结果暂时无法读取，请关闭窗口后重试。'));
        setLoading(false);
      });
    return () => { cancelled = true; lifetime.active = false; active.current = false; stopParameterImport(); };
  }, [account.id, form]);

  const reloadAfterBrowserLogin = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime)) return;
    clearSecrets();
    const [connectionResult, snapshotResult, bindingsResult] = await Promise.allSettled([
      getOfficialAccountConnection(lifetime.accountId), getOfficialAccountSessions(lifetime.accountId), listSessionBindings(lifetime.accountId),
    ]);
    if (!isCurrentLifetime(lifetime)) return;
    if (connectionResult.status === 'fulfilled') {
      const saved = connectionResult.value;
      setConfig(saved);
      form.setFieldsValue({ requestedAccountId: saved.requestedAccountId ?? '', proxyNodeId: saved.proxyNodeId, version: saved.version });
    } else setError(managedErrorMessage(connectionResult.reason, '授权已保存，但连接状态暂时无法读取，请关闭窗口后重新打开。'));
    if (snapshotResult.status === 'fulfilled') { updateSnapshot(snapshotResult.value); setSnapshotNotice(''); }
    else {
      updateSnapshot(null);
      setSnapshotNotice(managedErrorMessage(snapshotResult.reason, '授权已保存，尚无可用的官方会话快照，请手动刷新。'));
    }
    if (bindingsResult.status === 'fulfilled') { setBindings(bindingsResult.value); setBindingNotice(''); }
    else { setBindings(null); setBindingNotice(managedErrorMessage(bindingsResult.reason, '绑定结果暂时无法读取，请关闭窗口后重试。')); }
  };

  const reloadAfterLogout = async (completedAccountId: string) => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || lifetime.accountId !== completedAccountId) return;
    // Clear first: a failed safe read must never leave the pre-logout snapshot visible.
    updateSnapshot(null); setBindings(null); clearSecrets();
    setSnapshotNotice('正在重新核对官方连接与会话状态，尚不能确认设备实际下线。');
    const [connectionResult, snapshotResult, bindingsResult] = await Promise.allSettled([
      getOfficialAccountConnection(completedAccountId), getOfficialAccountSessions(completedAccountId),
      listSessionBindings(completedAccountId),
    ]);
    if (!isCurrentLifetime(lifetime)) return;
    if (connectionResult.status === 'fulfilled') {
      const saved = connectionResult.value; setConfig(saved);
      form.setFieldsValue({ requestedAccountId: saved.requestedAccountId ?? '', proxyNodeId: saved.proxyNodeId, version: saved.version });
    } else { setConfig(null); setError(managedErrorMessage(connectionResult.reason, '登出操作后连接状态读取失败，请关闭窗口后重新打开。')); }
    if (snapshotResult.status === 'fulfilled') { updateSnapshot(snapshotResult.value); setSnapshotNotice(''); }
    else setSnapshotNotice('登出操作后旧快照已清除，尚无当前会话快照；官方实际下线可能延迟，请在官网核对。');
    if (bindingsResult.status === 'fulfilled') { setBindings(bindingsResult.value); setBindingNotice(''); }
    else setBindingNotice(managedErrorMessage(bindingsResult.reason, '登出操作后绑定记录读取失败，请关闭窗口后重新打开。'));
    setQueueRevision((value) => value + 1);
  };

  const releaseEligible = releaseTarget?.officialAccountId === account.id && releaseTarget.status === 'ACTIVE'
    && bindings?.some((binding) => binding.id === releaseTarget.id && binding.status === 'ACTIVE'
      && binding.officialAccountId === account.id && binding.sessionId === releaseTarget.sessionId);
  const selectRelease = (binding: SessionBinding) => {
    if (locked || !secure || !active.current || binding.status !== 'ACTIVE' || binding.officialAccountId !== account.id) return;
    setReleaseTarget(binding); setReleaseAcknowledged(false); setReleaseError('');
  };
  const release = async () => {
    const lifetime = releaseLifetime.current;
    if (!lifetime.active || lifetime.accountId !== account.id || lifetime.busy || locked || !secure
      || !releaseTarget || !releaseEligible || !releaseAcknowledged) return;
    const submitted = releaseTarget;
    const isCurrent = () => isCurrentLifetime(lifetime);
    lifetime.busy = true; setReleaseBusy(true); setReleaseError('');
    try {
      await releaseSessionBinding(submitted.id, lifetime.accountId);
      if (!isCurrent()) return;
      // Clear first: the write succeeded even if the safe read cannot confirm current ownership.
      setBindings(null); setBindingNotice(''); setReleaseTarget(null); setReleaseAcknowledged(false);
      setSessionSelection({ snapshot: null, ids: [] }); setSelectionNotice('');
      setBindingRevision((value) => value + 1);
      message.success('本系统会话绑定已解除');
      try {
        const current = await listSessionBindings(lifetime.accountId);
        if (isCurrent()) { setBindings(current); setBindingNotice(''); }
      } catch {
        if (isCurrent()) setBindingNotice('解绑已成功，但绑定记录暂时无法读取，请关闭窗口后重新打开核对。');
      }
    } catch (reason) {
      if (isCurrent()) setReleaseError(managedErrorCode(reason) === 'OFFICIAL_SESSION_LOGOUT_BUSY'
        ? '此账户有执行中或待核对的官方登出操作，请先核对后再解绑。'
        : managedErrorMessage(reason, '未能确认解绑结果，请核对绑定状态后重试。'));
    } finally {
      lifetime.busy = false;
      if (isCurrent()) setReleaseBusy(false);
    }
  };

  const save = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || locked || !config?.encryptionReady || !secure) return;
    let values: OfficialConnectionInput;
    try { values = await form.validateFields(); } catch { return; }
    if (!isCurrentLifetime(lifetime)) return;
    const body: OfficialConnectionInput = {
      authorization: values.authorization.trim(), requestedAccountId: values.requestedAccountId.trim(),
      proxyNodeId: values.proxyNodeId || null, version: config.version,
      ...(values.cookie?.trim() ? { cookie: values.cookie.trim() } : {}),
    };
    setBusy('save'); setError(''); clearSecrets();
    try {
      const saved = await saveOfficialAccountConnection(lifetime.accountId, body);
      if (!isCurrentLifetime(lifetime)) return;
      setConfig(saved); updateSnapshot(null);
      setSnapshotNotice('尚无官方会话快照，请手动刷新官方账户与会话。');
      message.success('官方授权已加密保存，等待手动验证');
    } catch (reason) {
      if (isCurrentLifetime(lifetime)) setError(managedErrorMessage(reason, '官方授权保存失败，请检查配置后重新输入。'));
    } finally {
      body.authorization = ''; delete body.cookie;
      values.authorization = ''; delete values.cookie;
      if (isCurrentLifetime(lifetime)) { clearSecrets(); setBusy(null); }
    }
  };

  const refresh = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || locked || !config?.configured || !secure) return;
    setSessionSelection({ snapshot: null, ids: [] }); setSnapshotRevision((revision) => revision + 1);
    setBusy('refresh'); setError('');
    try {
      const result = await refreshOfficialAccountConnection(lifetime.accountId);
      if (isCurrentLifetime(lifetime)) { setConfig(result.connection); updateSnapshot(result); setSnapshotNotice(''); }
    } catch (reason) {
      if (!isCurrentLifetime(lifetime)) return;
      updateSnapshot(null);
      setSnapshotNotice('官方读取未成功，旧快照已清除；请检查授权和代理后手动刷新。');
      setError(managedErrorMessage(reason, '官方读取失败，请检查授权和代理后手动重试。'));
      // Reload safe status only; never retry the official upstream request automatically.
      try {
        const saved = await getOfficialAccountConnection(lifetime.accountId);
        if (isCurrentLifetime(lifetime)) setConfig(saved);
      } catch { /* The primary refresh error already provides the actionable state. */ }
    } finally { if (isCurrentLifetime(lifetime)) setBusy(null); }
  };

  const remove = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || locked || !secure) return;
    setBusy('delete'); setError(''); clearSecrets();
    try {
      await deleteOfficialAccountConnection(lifetime.accountId);
      if (isCurrentLifetime(lifetime)) { message.success('保存的官方授权已删除'); close(); }
    } catch (reason) {
      if (isCurrentLifetime(lifetime)) setError(managedErrorMessage(reason, '官方授权删除失败，请稍后重试。'));
    } finally { if (isCurrentLifetime(lifetime)) setBusy(null); }
  };

  const missingNode = config?.proxyNodeId && !nodes.some((node) => node.id === config.proxyNodeId);
  const nodeUnavailable = selectedNode && !nodes.some((node) => node.id === selectedNode && node.enabled);
  return <><Modal open title={`授权与会话 · ${account.label}`} width={960} onCancel={close} destroyOnHidden
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
              <BrowserLoginPanel key={account.id} accountId={account.id} version={config?.version ?? 0} proxyNodeId={selectedNode || null}
                disabled={loading || busy !== null || logoutBusy || releaseBusy || !secure || !config?.encryptionReady || Boolean(nodeUnavailable)}
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
          {secure ? <SessionLogoutPanel key={account.id} accountId={account.id}
            contextVersion={JSON.stringify([config?.version ?? null, bindingRevision, snapshotRevision, selectedSessionIds])}
            selectedSessionIds={selectedSessionIds}
            disabled={externallyLocked} allowPreview={Boolean(config?.configured)} onBusyChange={(value) => {
              if (active.current && currentAccountId.current === account.id) setLogoutBusy(value);
            }} onSettled={reloadAfterLogout} /> : null}
          {secure ? <SessionBindingQueue key={`${account.id}:${queueRevision}`} accountId={account.id} disabled={locked} /> : null}
          {selectionNotice ? <Alert type="warning" showIcon title={selectionNotice} /> : null}
          {snapshot && !logoutBusy ? <SessionSnapshot snapshot={snapshot} officialAccountId={account.id} bindings={bindings}
            selectedSessionIds={selectedSessionIds} onSelectionChange={selectSessions} selectionDisabled={locked || !secure} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={logoutBusy ? '会话操作状态核对中，暂不显示旧快照。' : snapshotNotice || '尚无官方会话快照，请手动刷新官方账户与会话。'} />}
          {bindingNotice ? <Alert type="warning" showIcon title={bindingNotice} /> : null}
          {bindings && !logoutBusy ? <SessionBindingsTable bindings={bindings} disabled={locked || !secure} onRelease={selectRelease} /> : null}
        </Space>
      </Spin>
    </Space>
  </Modal>
    <Modal open={Boolean(releaseTarget && releaseTarget.officialAccountId === account.id)} title="解绑会话确认"
      okText="确认解绑" cancelText="取消" destroyOnHidden
      onCancel={() => { if (!releaseBusy) setReleaseTarget(null); }} onOk={() => void release()}
      confirmLoading={releaseBusy} okButtonProps={{ danger: true, disabled: locked || !secure || !releaseEligible || !releaseAcknowledged }}
      cancelButtonProps={{ disabled: releaseBusy }} closable={!releaseBusy} mask={{ closable: !releaseBusy }}>
      {releaseTarget ? <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Descriptions size="small" column={1} items={[
          { key: 'account', label: '官方账户', children: account.label },
          { key: 'user', label: '本系统用户', children: releaseTarget.userDisplayName || releaseTarget.userEmail || releaseTarget.userId },
          ...(releaseTarget.userEmail ? [{ key: 'email', label: '用户邮箱', children: releaseTarget.userEmail }] : []),
          { key: 'userId', label: '用户 ID', children: releaseTarget.userId },
          { key: 'device', label: '客户端设备', children: releaseTarget.deviceId },
          { key: 'session', label: '官方 Session ID', children: releaseTarget.sessionId },
        ]} />
        <Alert type="warning" showIcon title="仅解除本系统的设备会话绑定，不会登出官方会话。"
          description="解绑后，该客户端只能使用登录辅助；不会删除历史记录，也不会停止统计。" />
        {releaseError ? <Alert type="error" showIcon title={releaseError} /> : null}
        <Checkbox checked={releaseAcknowledged} disabled={releaseBusy || locked || !secure || !releaseEligible}
          onChange={(event) => setReleaseAcknowledged(event.target.checked)}>我已了解解绑影响，确认解除此会话绑定</Checkbox>
      </Space> : null}
    </Modal>
  </>;
}
