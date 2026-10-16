import { Alert, App as AntApp, Button, Card, Checkbox, Collapse, Descriptions, Divider, Empty, Form, Input,
  Modal, Popconfirm, Select, Space, Spin, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent, ReactNode } from 'react';
import { managedErrorCode, managedErrorMessage } from '../../components/managedErrors';
import ManagementTransportNotice from '../../components/ManagementTransportNotice';
import { applyOfficialAuthorizationPreset, deleteOfficialAccountConnection, getOfficialAccountConnection, getOfficialAccountSessions,
  getOfficialAuthorizationPreset,
  isSupportedManagementContext, listProxyNodes, refreshOfficialAccountConnection,
  saveOfficialAccountConnection, saveOfficialAccountProxyChain, testProxyChain } from '../../services/api';
import type { OfficialAccountConnection, OfficialAccountResponse, OfficialAccountSnapshot,
  OfficialAuthorizationPreset, OfficialConnectionInput, OfficialConnectionStatus, OfficialSessionDevice, ProxyChainTest, ProxyNode } from '../../services/api';
import { LOCAL_PREVIEW_PARAMETER_MAX_BYTES, parseLocalPreviewParameters } from './localPreviewParameters';
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

function WorkspaceShell({ inline, title, footer, children, onCancel }: {
  inline: boolean; title: string; footer: ReactNode; children: ReactNode; onCancel: () => void;
}) {
  return inline ? <Card title={title} extra={footer}>{children}</Card>
    : <Modal open title={title} width={1100} onCancel={onCancel} destroyOnHidden
      styles={{ body: { maxHeight: '74vh', overflowY: 'auto' } }} footer={footer}>{children}</Modal>;
}

export default function ConnectionModal({ account, onClose, view = 'all', inline = false }: {
  account: OfficialAccountResponse; onClose: () => void; view?: 'all' | 'authorization' | 'sessions'; inline?: boolean;
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
  const [preset, setPreset] = useState<OfficialAuthorizationPreset | null>(null);
  const [presetNotice, setPresetNotice] = useState('');
  const [manualKeys, setManualKeys] = useState<string[]>([]);
  const [logoutBusy, setLogoutBusy] = useState(false);
  const [queueRevision, setQueueRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'save' | 'preset' | 'refresh' | 'delete' | 'import' | 'chain' | 'proxy' | null>(null);
  const [chainResult, setChainResult] = useState<{ key: string; result: ProxyChainTest } | null>(null);
  const [error, setError] = useState('');
  const [snapshotNotice, setSnapshotNotice] = useState('');
  const active = useRef(true);
  const currentAccountId = useRef(account.id); currentAccountId.current = account.id;
  const releaseLifetime = useRef({ accountId: account.id, active: true, busy: false });
  const parameterFileInput = useRef<HTMLInputElement>(null);
  const parameterFileReader = useRef<FileReader | null>(null);
  const supported = isSupportedManagementContext();
  const selectedNode = Form.useWatch('proxyNodeId', form);
  const selectedFront = Form.useWatch('frontProxyNodeId', form);
  const availableNode = (id: string | null | undefined) => Boolean(id && nodes.some((node) => node.id === id && node.enabled && !node.sourceMissing));
  const invalidChain = (front: string | null | undefined, exit: string | null | undefined) => !availableNode(exit)
    || Boolean(front && (!availableNode(front) || front === exit));
  const nodeUnavailable = invalidChain(selectedFront, selectedNode);
  const savedNodeUnavailable = invalidChain(config?.frontProxyNodeId, config?.proxyNodeId);
  const nodeChanged = view !== 'sessions' && (selectedNode !== config?.proxyNodeId
    || (selectedFront ?? null) !== (config?.frontProxyNodeId ?? null));
  const chainKey = JSON.stringify([account.id, selectedFront ?? null, selectedNode ?? null]);
  const chainLabel = ['服务器', ...(selectedFront ? [nodes.find((node) => node.id === selectedFront)?.label || '前置节点不可用'] : []),
    nodes.find((node) => node.id === selectedNode)?.label || '请选择出口节点', '官网'].join(' → ');
  const refreshUnavailable = savedNodeUnavailable || (view !== 'sessions' && (nodeUnavailable || nodeChanged));
  const externallyLocked = loading || busy !== null || releaseBusy;
  const locked = externallyLocked || logoutBusy;
  const selectedSessionIds = sessionSelection.snapshot === snapshot ? sessionSelection.ids : [];
  const isCurrentLifetime = (lifetime: typeof releaseLifetime.current) => active.current && lifetime.active
    && releaseLifetime.current === lifetime && currentAccountId.current === lifetime.accountId;

  const updateSnapshot = (value: OfficialAccountSnapshot | null) => {
    setSnapshot(value); setSnapshotRevision((revision) => revision + 1);
    setSessionSelection({ snapshot: value, ids: [] }); setSelectionNotice('');
  };
  const selectSessions = (ids: string[]) => {
    if (locked || !supported || !snapshot) return;
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
    const lifetime = releaseLifetime.current;
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file || !isCurrentLifetime(lifetime) || lifetime.busy || locked || !supported || !config?.encryptionReady) return;
    if (!/\.(txt|json)$/i.test(file.name) || file.size > LOCAL_PREVIEW_PARAMETER_MAX_BYTES) {
      setError('请选择不超过 64 KiB 的本机 .txt 或 .json 参数文件。');
      return;
    }
    lifetime.busy = true; setBusy('import'); setError('');
    const reader = new FileReader();
    parameterFileReader.current = reader;
    const finish = () => {
      if (parameterFileReader.current !== reader) return;
      parameterFileReader.current = null;
      lifetime.busy = false;
      if (isCurrentLifetime(lifetime)) setBusy(null);
    };
    reader.onload = () => {
      if (!isCurrentLifetime(lifetime) || parameterFileReader.current !== reader) return;
      try {
        if (typeof reader.result !== 'string') throw new Error('Invalid local file');
        const draft = parseLocalPreviewParameters(reader.result);
        setManualKeys(['manual']);
        form.setFieldsValue(draft);
        message.success('三项授权参数已填入本机草稿，尚未保存');
      } catch {
        setError('本机参数文件格式不正确，请检查 JSON 字段或三段标签、对应参数及允许的官方登录 Cookie。');
      } finally { finish(); }
    };
    reader.onerror = () => {
      if (isCurrentLifetime(lifetime) && parameterFileReader.current === reader) setError('无法读取本机参数文件，请重新选择。');
      finish();
    };
    reader.onabort = finish;
    reader.readAsText(file, 'UTF-8');
  };

  useEffect(() => {
    active.current = true;
    const lifetime = { accountId: account.id, active: true, busy: false }; releaseLifetime.current = lifetime;
    setLoading(true); setConfig(null); setNodes([]); updateSnapshot(null); setBindings(null);
    setBusy(null); setPreset(null); setPresetNotice(''); setManualKeys([]); form.resetFields();
    setChainResult(null);
    setError(''); setSnapshotNotice(''); setBindingNotice(''); setLogoutBusy(false);
    setReleaseTarget(null); setReleaseAcknowledged(false); setReleaseBusy(false); setReleaseError(''); setBindingRevision(0);
    let cancelled = false;
    void Promise.allSettled([getOfficialAccountConnection(account.id), listProxyNodes(), getOfficialAccountSessions(account.id), listSessionBindings(account.id),
      view === 'sessions' ? Promise.resolve(null) : getOfficialAuthorizationPreset()])
      .then(([connectionResult, nodesResult, snapshotResult, bindingsResult, presetResult]) => {
        if (cancelled || !active.current) return;
        if (connectionResult.status === 'fulfilled') {
          const saved = connectionResult.value;
          setConfig(saved);
          form.setFieldsValue({ authorization: '', cookie: '', requestedAccountId: saved.requestedAccountId ?? '',
            frontProxyNodeId: saved.frontProxyNodeId ?? null, proxyNodeId: saved.proxyNodeId, version: saved.version });
        } else setError(managedErrorMessage(connectionResult.reason, '官方授权配置读取失败，请关闭窗口后重试。'));
        if (nodesResult.status === 'fulfilled') setNodes(nodesResult.value);
        else setError(managedErrorMessage(nodesResult.reason, '代理节点列表读取失败，请关闭窗口后重试。'));
        if (snapshotResult.status === 'fulfilled') updateSnapshot(snapshotResult.value);
        else setSnapshotNotice(managedErrorCode(snapshotResult.reason) === 'OFFICIAL_SNAPSHOT_UNAVAILABLE'
          ? '尚无官方会话快照，请手动刷新官方账户与会话。'
          : managedErrorMessage(snapshotResult.reason, '官方会话快照读取失败，可手动刷新后重试。'));
        if (bindingsResult.status === 'fulfilled') setBindings(bindingsResult.value);
        else setBindingNotice(managedErrorMessage(bindingsResult.reason, '绑定结果暂时无法读取，请关闭窗口后重试。'));
        if (presetResult.status === 'fulfilled') {
          setPreset(presetResult.value);
          if (presetResult.value?.errorCode) setPresetNotice(managedErrorMessage({ code: presetResult.value.errorCode },
            '预置授权暂不可用，可从本机参数文件填入或手动导入授权。'));
        } else setPresetNotice(managedErrorMessage(presetResult.reason, '预置授权暂不可用，可从本机参数文件填入或手动导入授权。'));
        setLoading(false);
      });
    return () => { cancelled = true; lifetime.active = false; active.current = false; stopParameterImport(); };
  }, [account.id, form, view]);

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
      form.setFieldsValue({ requestedAccountId: saved.requestedAccountId ?? '', frontProxyNodeId: saved.frontProxyNodeId ?? null, proxyNodeId: saved.proxyNodeId, version: saved.version });
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
    if (locked || !supported || !active.current || binding.status !== 'ACTIVE' || binding.officialAccountId !== account.id) return;
    setReleaseTarget(binding); setReleaseAcknowledged(false); setReleaseError('');
  };
  const release = async () => {
    const lifetime = releaseLifetime.current;
    if (!lifetime.active || lifetime.accountId !== account.id || lifetime.busy || locked || !supported
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
    if (!isCurrentLifetime(lifetime) || lifetime.busy || locked || !config?.encryptionReady || !supported || nodeUnavailable) return;
    let values: OfficialConnectionInput;
    try { values = await form.validateFields(); } catch { return; }
    if (!isCurrentLifetime(lifetime) || lifetime.busy || invalidChain(values.frontProxyNodeId, values.proxyNodeId)) return;
    const body: OfficialConnectionInput = {
      authorization: values.authorization.trim(), requestedAccountId: values.requestedAccountId.trim(),
      proxyNodeId: values.proxyNodeId, version: config.version,
      ...(values.frontProxyNodeId || config.frontProxyNodeId ? { frontProxyNodeId: values.frontProxyNodeId ?? null } : {}),
      ...(values.cookie?.trim() ? { cookie: values.cookie.trim() } : {}),
    };
    lifetime.busy = true; setBusy('save'); setError(''); clearSecrets();
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
      lifetime.busy = false;
      if (isCurrentLifetime(lifetime)) { clearSecrets(); setBusy(null); }
    }
  };

  const applyPreset = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || lifetime.busy || locked || !supported || !config?.encryptionReady
      || !preset?.available || !preset.revision || nodeUnavailable) return;
    lifetime.busy = true; setBusy('preset'); setError(''); clearSecrets();
    try {
      const saved = await applyOfficialAuthorizationPreset(lifetime.accountId, {
        version: config.version, proxyNodeId: selectedNode, presetRevision: preset.revision,
        ...(selectedFront || config.frontProxyNodeId ? { frontProxyNodeId: selectedFront ?? null } : {}),
      });
      if (!isCurrentLifetime(lifetime)) return;
      setConfig(saved); updateSnapshot(null);
      form.setFieldsValue({ requestedAccountId: saved.requestedAccountId ?? '', frontProxyNodeId: saved.frontProxyNodeId ?? null, proxyNodeId: saved.proxyNodeId, version: saved.version });
      setSnapshotNotice('尚无官方会话快照，请手动刷新官方账户与会话。');
      message.success('预置授权已加密保存，请手动刷新官方账户与会话');
    } catch (reason) {
      if (isCurrentLifetime(lifetime)) setError(managedErrorMessage(reason, '预置授权保存失败，请检查配置后重试。'));
    } finally {
      lifetime.busy = false;
      if (isCurrentLifetime(lifetime)) { clearSecrets(); setBusy(null); }
    }
  };

  const saveChain = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || locked || lifetime.busy || !supported || !config?.configured || nodeUnavailable || !selectedNode) return;
    lifetime.busy = true; setBusy('proxy'); setError('');
    try {
      const saved = await saveOfficialAccountProxyChain(lifetime.accountId, { proxyNodeId: selectedNode, version: config.version,
        ...(selectedFront || config.frontProxyNodeId ? { frontProxyNodeId: selectedFront ?? null } : {}) });
      if (!isCurrentLifetime(lifetime)) return;
      setConfig(saved); updateSnapshot(null);
      form.setFieldsValue({ frontProxyNodeId: saved.frontProxyNodeId ?? null, proxyNodeId: saved.proxyNodeId, version: saved.version });
      setSnapshotNotice('代理链已保存，请重新刷新官方账户与会话。');
      message.success('代理链已保存，请重新刷新官方账户与会话。');
    } catch (reason) {
      if (isCurrentLifetime(lifetime)) setError(managedErrorMessage(reason, '代理链保存失败，请检查节点状态后重试。'));
    } finally {
      lifetime.busy = false;
      if (isCurrentLifetime(lifetime)) setBusy(null);
    }
  };

  const testChain = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || locked || lifetime.busy || !supported || nodeUnavailable || !selectedNode) return;
    const input = { proxyNodeId: selectedNode, ...(selectedFront ? { frontProxyNodeId: selectedFront } : {}) };
    lifetime.busy = true; setBusy('chain'); setChainResult(null); setError('');
    try {
      const result = await testProxyChain(input);
      if (!isCurrentLifetime(lifetime)) return;
      if (result.proxyNodeId !== input.proxyNodeId || (result.frontProxyNodeId ?? null) !== (input.frontProxyNodeId ?? null)) {
        setError('完整链路测试返回的节点与当前选择不一致，请重新测试。'); return;
      }
      if (result.connected && !result.egressIp) {
        setError(managedErrorMessage({ code: 'PROXY_TEST_RESPONSE_INVALID' })); return;
      }
      setChainResult({ key: chainKey, result });
    } catch (reason) {
      if (isCurrentLifetime(lifetime)) setError(managedErrorMessage(reason, '完整链路测试失败，请检查前置与出口节点后重试。'));
    } finally {
      lifetime.busy = false;
      if (isCurrentLifetime(lifetime)) setBusy(null);
    }
  };

  const refresh = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || lifetime.busy || locked || !config?.configured || !supported || refreshUnavailable) return;
    setSessionSelection({ snapshot: null, ids: [] }); setSnapshotRevision((revision) => revision + 1);
    lifetime.busy = true; setBusy('refresh'); setError('');
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
    } finally { lifetime.busy = false; if (isCurrentLifetime(lifetime)) setBusy(null); }
  };

  const remove = async () => {
    const lifetime = releaseLifetime.current;
    if (!isCurrentLifetime(lifetime) || lifetime.busy || locked || !supported) return;
    lifetime.busy = true; setBusy('delete'); setError(''); clearSecrets();
    try {
      await deleteOfficialAccountConnection(lifetime.accountId);
      if (isCurrentLifetime(lifetime)) { message.success('保存的官方授权已删除'); close(); }
    } catch (reason) {
      if (isCurrentLifetime(lifetime)) setError(managedErrorMessage(reason, '官方授权删除失败，请稍后重试。'));
    } finally { lifetime.busy = false; if (isCurrentLifetime(lifetime)) setBusy(null); }
  };

  const missingNode = config?.proxyNodeId && !nodes.some((node) => node.id === config.proxyNodeId);
  const missingFront = config?.frontProxyNodeId && !nodes.some((node) => node.id === config.frontProxyNodeId);
  const nodeOption = (node: ProxyNode, otherId: string | null | undefined) => ({ value: node.id,
    label: `${node.label} · ${node.protocol}${node.sourceMissing ? '（订阅中已移除）' : node.enabled ? '' : '（已停用）'}`,
    disabled: !node.enabled || node.sourceMissing || node.id === otherId });
  return <><WorkspaceShell inline={inline} title={`${view === 'sessions' ? '会话管理' : view === 'authorization' ? '授权登录' : '授权与会话'} · ${account.label}`} onCancel={close}
    footer={<Space wrap>
      {config?.configured && view !== 'sessions' ? <Popconfirm title="删除保存的官方授权？"
        description="同时清除后台保存的官方会话快照，不会使官方设备下线，也不影响账户登记和邮箱配置。"
        okText="确认删除" cancelText="取消" onConfirm={() => void remove()}>
        <Button danger disabled={locked || !supported} loading={busy === 'delete'}>删除保存的授权</Button>
      </Popconfirm> : null}
      <Button disabled={locked || !supported || !config?.configured || refreshUnavailable} loading={busy === 'refresh'} onClick={() => void refresh()}>刷新官方账户与会话</Button>
      {!inline ? <Button onClick={close}>关闭</Button> : null}
    </Space>}>
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <ManagementTransportNotice supported={supported} />
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
          {!loading && (view === 'sessions' ? savedNodeUnavailable : nodeUnavailable) ? <Alert type="warning" showIcon
            title="每个官方账户必须选择一个已启用的代理节点，所有官方请求均禁止直连。"
            description={<span>{nodes.some((node) => node.enabled)
              ? `请在此账户的授权登录页选择可用节点，并保存${config?.configured ? '代理链' : '授权'}。`
              : `暂无已启用节点，请先添加并启用节点，再返回此账户保存${config?.configured ? '代理链' : '授权'}。`} <a href="#/proxy-nodes">管理代理节点</a></span>} /> : null}
          {!loading && view !== 'sessions' && !nodeUnavailable && nodeChanged ? <Alert type="info" showIcon
            title="节点选择尚未保存，请保存代理链、使用预置授权或保存授权后再刷新；只会更新当前官方账户。" /> : null}
          {view !== 'sessions' ? <Form form={form} layout="vertical" preserve={false} disabled={locked || !supported || !config?.encryptionReady}>
            <Form.Item name="frontProxyNodeId" label="前置代理节点（可选）"
              extra="留空使用单节点；选择后由服务器先连接前置节点，再连接后置／出口节点。两个节点必须不同且均可用。">
              <Select virtual={false} allowClear placeholder="不使用前置节点（单节点）" options={[
                ...nodes.map((node) => nodeOption(node, selectedNode)),
                ...(missingFront ? [{ value: config.frontProxyNodeId!, label: '已保存的前置节点（当前不可用）', disabled: true }] : []),
              ]} />
            </Form.Item>
            <Form.Item name="proxyNodeId" label="访问代理节点" rules={[{ required: true, message: '请选择一个已启用的代理节点' }]}
              extra="后置／出口节点（必选）。此选择只保存到当前官方账户，所有官方请求经过保存的链路，不会失败回退或自动切换出口；本机浏览器由本机网络设置决定出口。">
              <Select virtual={false} placeholder="请选择已启用的代理节点" options={[
                ...nodes.map((node) => nodeOption(node, selectedFront)),
                ...(missingNode ? [{ value: config.proxyNodeId!, label: '已保存的节点（当前不可用）', disabled: true }] : []),
              ]} />
            </Form.Item>
            <Space orientation="vertical" size={12} style={{ width: '100%', marginBottom: 16 }}>
              <Typography.Text strong>{chainLabel}</Typography.Text>
              {selectedFront && selectedFront === selectedNode ? <Alert type="warning" showIcon title="前置节点与出口节点不能相同，请重新选择。" /> : null}
              <Space wrap>
                <Button disabled={locked || !supported || nodeUnavailable} loading={busy === 'chain'} onClick={() => void testChain()}>测试完整链路</Button>
                <Button disabled={locked || !supported || !config?.configured || nodeUnavailable} loading={busy === 'proxy'} onClick={() => void saveChain()}>保存代理链</Button>
              </Space>
              <Typography.Text type="secondary">测试使用当前选择的完整链路获取实际出口，不会保存账户配置，也不会更新单节点测试结果；成功后仍需保存代理链或授权，并手动验证官方连接。</Typography.Text>
              {chainResult?.key === chainKey ? <Alert showIcon type={chainResult.result.connected ? 'success' : 'warning'}
                title={chainResult.result.connected ? '完整链路测试成功' : '完整链路测试失败'}
                description={`完整链路出口 IP：${chainResult.result.egressIp || '未获取'}；地区：${[chainResult.result.country, chainResult.result.region, chainResult.result.city].filter(Boolean).join(' / ') || '未知'}；测试时间：${dateText(chainResult.result.testedAt)}`} /> : null}
            </Space>
            <Space orientation="vertical" size={16} style={{ width: '100%' }}>
              <Card size="small" title="预置授权">
                <Space orientation="vertical" size={12} style={{ width: '100%' }}>
                  <Typography.Text>{preset?.available
                    ? `服务端已准备官方 Account ID：${preset.requestedAccountId || '未提供'}。点击后应用到当前账户，无需填写授权字段。`
                    : loading ? '正在检查预置授权。' : '服务端尚无可用预置授权，可从本机参数文件填入或手动导入授权。'}</Typography.Text>
                  {presetNotice ? <Alert type="warning" showIcon title={presetNotice} /> : null}
                  <Typography.Text type="secondary">授权由服务端加密保存，凭据不会返回此页面。应用后请手动刷新以验证连接。</Typography.Text>
                  <Button type="primary" disabled={locked || !supported || !config?.encryptionReady || Boolean(nodeUnavailable)
                    || !preset?.available || !preset.revision} loading={busy === 'preset'} onClick={() => void applyPreset()}>使用预置授权</Button>
                </Space>
              </Card>
              <Alert type="info" showIcon title="仅打开官方网站"
                description={<Space orientation="vertical" size={8}>
                  <span>此按钮只在本机浏览器的新标签页打开 ChatGPT，不会自动读取跨站 Cookie 或回传授权。登录后可使用私有扩展导出自己的授权文件，再点击“从本机参数文件填入”并明确保存；也可继续使用预置授权。</span>
                  <Button disabled={locked || !supported}
                    onClick={() => { if (!locked && supported) window.open('https://chatgpt.com/', '_blank', 'noopener,noreferrer'); }}>在本机浏览器打开官方登录</Button>
                </Space>} />
              <Alert type="info" showIcon title="本机参数文件导入草稿"
                description={<Space orientation="vertical" size={8}>
                  <span id="local-preview-parameters-description">支持不超过 64 KiB 的 .txt 或 .json 文件，仅由当前浏览器读取，不上传。填入会替换 Account ID、Authorization 和 Cookie，保留代理选择；请核对后明确保存，不会自动刷新官方会话。</span>
                  <input ref={parameterFileInput} id="local-preview-parameters-file" type="file" accept=".txt,.json,text/plain,application/json"
                    aria-label="本机参数文件" aria-describedby="local-preview-parameters-description" hidden
                    disabled={locked || !supported || !config?.encryptionReady} onChange={importParameters} />
                  <Button disabled={locked || !supported || !config?.encryptionReady} loading={busy === 'import'}
                    aria-controls="local-preview-parameters-file" aria-describedby="local-preview-parameters-description"
                    onClick={() => parameterFileInput.current?.click()}>从本机参数文件填入</Button>
                </Space>} />
              <Collapse style={{ width: '100%' }} activeKey={manualKeys} onChange={(keys) => {
                setManualKeys(typeof keys === 'string' ? [keys] : keys);
                if (!keys.includes('manual')) clearSecrets();
              }} items={[{
                key: 'manual', label: '高级：手动导入授权', children: <Space orientation="vertical" size={16} style={{ width: '100%' }}>
                  <Alert type="info" showIcon title="手动导入你自己的官方网页登录授权"
                    description="适用于已有授权参数的管理员。每次保存会完整替换授权，Cookie 留空会清除旧 Cookie。提交后立即清空，不在浏览器存储中保存。" />
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
                  <Button disabled={locked || !supported || !config?.encryptionReady || Boolean(nodeUnavailable)}
                    loading={busy === 'save'} onClick={() => void save()}>保存授权</Button>
                </Space>,
              }]} />
            </Space>
          </Form> : null}
          {view !== 'authorization' ? <>
          <Divider style={{ margin: '4px 0' }}>官方账户与设备会话</Divider>
          {supported ? <SessionLogoutPanel key={account.id} accountId={account.id}
            contextVersion={JSON.stringify([config?.version ?? null, bindingRevision, snapshotRevision, selectedSessionIds])}
            selectedSessionIds={selectedSessionIds}
            disabled={externallyLocked} allowPreview={Boolean(config?.configured) && !refreshUnavailable} onBusyChange={(value) => {
              if (active.current && currentAccountId.current === account.id) setLogoutBusy(value);
            }} onSettled={reloadAfterLogout} /> : null}
          {supported ? <SessionBindingQueue key={`${account.id}:${queueRevision}`} accountId={account.id} disabled={locked} /> : null}
          {selectionNotice ? <Alert type="warning" showIcon title={selectionNotice} /> : null}
          {snapshot && !logoutBusy ? <SessionSnapshot snapshot={snapshot} officialAccountId={account.id} bindings={bindings}
            selectedSessionIds={selectedSessionIds} onSelectionChange={selectSessions} selectionDisabled={locked || !supported} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={logoutBusy ? '会话操作状态核对中，暂不显示旧快照。' : snapshotNotice || '尚无官方会话快照，请手动刷新官方账户与会话。'} />}
          {bindingNotice ? <Alert type="warning" showIcon title={bindingNotice} /> : null}
          {bindings && !logoutBusy ? <SessionBindingsTable bindings={bindings} disabled={locked || !supported} onRelease={selectRelease} /> : null}
          </> : null}
        </Space>
      </Spin>
    </Space>
  </WorkspaceShell>
    <Modal open={Boolean(releaseTarget && releaseTarget.officialAccountId === account.id)} title="解绑会话确认"
      okText="确认解绑" cancelText="取消" destroyOnHidden
      onCancel={() => { if (!releaseBusy) setReleaseTarget(null); }} onOk={() => void release()}
      confirmLoading={releaseBusy} okButtonProps={{ danger: true, disabled: locked || !supported || !releaseEligible || !releaseAcknowledged }}
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
        <Checkbox checked={releaseAcknowledged} disabled={releaseBusy || locked || !supported || !releaseEligible}
          onChange={(event) => setReleaseAcknowledged(event.target.checked)}>我已了解解绑影响，确认解除此会话绑定</Checkbox>
      </Space> : null}
    </Modal>
  </>;
}
