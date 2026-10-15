import { Alert, Button, Card, Checkbox, Descriptions, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { managedErrorCode, managedErrorMessage } from '../../components/managedErrors';
import { acknowledgeSessionLogoutUnknown, confirmSessionLogout, getSessionLogoutCurrent,
  previewSessionLogout } from '../../services/sessionLogoutApi';
import type { SessionLogoutDevice, SessionLogoutExclusionReason, SessionLogoutMode,
  SessionLogoutOperation, SessionLogoutPreview, SessionLogoutResult } from '../../services/sessionLogoutApi';

const modeLabel: Record<SessionLogoutMode, string> = { ALL: '登出所有会话', UNBOUND: '登出所有未绑定会话' };
const exclusionLabels: Record<SessionLogoutExclusionReason, string> = {
  MISSING_SESSION_ID: '缺少会话 ID', DUPLICATE_SESSION_ID: '会话 ID 重复', DUPLICATE_ROW_ID: '设备行 ID 重复',
  ACTIVE_BINDING: '已有有效绑定', TRUSTED_DEVICE: '受信任设备', TRUSTED_DEVICE_UNKNOWN: '受信任状态未知',
  CURRENT_SESSION: '当前官方管理会话', CURRENT_SESSION_UNKNOWN: '当前会话身份未知',
};
// An issued request continues across close/reopen. Never abort or resend it from the UI.
const issuedAccounts = new Set<string>();
const operations = new Map<string, SessionLogoutOperation>();
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };
const notify = () => listeners.forEach((listener) => listener());
type Lifetime = { active: boolean; busy: boolean; scope: string; settled: Set<string>;
  settle?: (accountId: string) => void | Promise<void> };
const unresolved = (value: SessionLogoutOperation | undefined | null) => value?.status === 'EXECUTING' || value?.status === 'UNKNOWN';
const storeOperation = (accountId: string, operation: SessionLogoutOperation | null) => {
  if (operation) operations.set(accountId, operation); else operations.delete(accountId);
  notify();
};
const Device = ({ value }: { value: SessionLogoutDevice }) => <Space size={4} wrap>
  <Typography.Text>{value.displayName || '未命名设备'}</Typography.Text>
  <Typography.Text type="secondary">{value.platform || '平台未知'} · Session ID：{value.sessionId || '未提供'}</Typography.Text>
  {value.trustedDevice === true ? <Tag color="success">受信任</Tag> : null}
  {value.current === true ? <Tag color="cyan">当前官方管理会话</Tag> : null}
</Space>;

function failureText(failure: unknown) {
  const code = managedErrorCode(failure);
  if (code === 'OFFICIAL_LOGOUT_PLAN_EXPIRED') return '预览已过期，本次未执行，请重新预览。';
  if (code === 'OFFICIAL_LOGOUT_CONTEXT_CHANGED') return '条件已变化，本次未执行，请重新预览。';
  if (code === 'OFFICIAL_LOGOUT_PLAN_CONSUMED') return '此预览已使用，不能重复提交；请核对结果后重新预览。';
  if (code === 'OFFICIAL_LOGOUT_BUSY') return '此账户已有会话操作，请等待并核对结果后重新预览。';
  if (code === 'OFFICIAL_LOGOUT_PLAN_FORBIDDEN') return '此预览属于其他管理员，请使用当前管理账户重新预览。';
  if (code === 'OFFICIAL_LOGOUT_SNAPSHOT_INCOMPLETE') return '后台未取得完整的新鲜会话，无法安全生成登出预览。';
  if (code === 'OFFICIAL_LOGOUT_ACK_REQUIRED') return '需要重新核对预览并明确确认风险。';
  if (code === 'OFFICIAL_LOGOUT_STILL_RUNNING') return '后台仍在处理或尚处于安全等待期，暂不能解除操作锁，请稍后手动刷新状态。';
  return managedErrorMessage(failure, '请求结果未知，请核对后重新预览；不会自动重试。');
}

export default function SessionLogoutPanel({ accountId, contextVersion, disabled = false, allowPreview = true, onBusyChange, onSettled }: {
  accountId: string; contextVersion?: number | string; disabled?: boolean; allowPreview?: boolean;
  onBusyChange?: (busy: boolean) => void;
  onSettled?: (accountId: string) => void | Promise<void>;
}) {
  const scope = accountId;
  const [previewState, setPreviewState] = useState<{ scope: string; version?: number | string; value: SessionLogoutPreview } | null>(null);
  const [resultState, setResultState] = useState<{ scope: string; value: SessionLogoutResult } | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [pending, setPending] = useState<SessionLogoutMode | 'confirm' | 'current' | 'acknowledge' | 'reload' | null>('current');
  const [metadataReady, setMetadataReady] = useState(false);
  const [manualVerification, setManualVerification] = useState(false);
  const [expired, setExpired] = useState(false);
  const [error, setError] = useState('');
  const [refreshNotice, setRefreshNotice] = useState('');
  const lifetimeRef = useRef<Lifetime | null>(null);
  const busyCallback = useRef(onBusyChange); busyCallback.current = onBusyChange;
  const issued = useSyncExternalStore(subscribe, () => issuedAccounts.has(accountId), () => false);
  const operation = useSyncExternalStore(subscribe, () => operations.get(accountId) ?? null, () => null);
  const plan = previewState?.scope === scope && previewState.version === contextVersion ? previewState.value : null;
  const result = resultState?.scope === scope ? resultState.value : null;
  const locked = disabled || !allowPreview || pending !== null || issued || !metadataReady || unresolved(operation);

  const settleTerminal = async (lifetime: Lifetime, value: SessionLogoutResult) => {
    if (value.status === 'EXECUTING' || lifetime.settled.has(value.planId)) return;
    lifetime.settled.add(value.planId);
    try { await lifetime.settle?.(lifetime.scope); }
    catch { if (lifetime.active) setRefreshNotice('操作状态已更新，但状态重读未完成；旧快照不可作为当前状态，请关闭后重新打开。'); }
  };
  const readCurrent = async (lifetime: Lifetime, expectedPlanId?: string) => {
    try {
      const { operation: value } = await getSessionLogoutCurrent(lifetime.scope);
      storeOperation(lifetime.scope, value);
      if (lifetime.active) {
        setMetadataReady(true);
        if (value && (!expectedPlanId || value.planId === expectedPlanId)) setResultState({ scope: lifetime.scope, value });
      }
      if (value && (!expectedPlanId || value.planId === expectedPlanId)) await settleTerminal(lifetime, value);
    } catch {
      if (lifetime.active) { setMetadataReady(false); setRefreshNotice('无法核对后台登出操作锁，请手动刷新状态；确认前不会开放登出操作。'); }
    }
  };

  useEffect(() => {
    const lifetime = { active: true, busy: true, scope, settled: new Set<string>(), settle: onSettled }; lifetimeRef.current = lifetime;
    setPreviewState(null); setResultState(null); setAcknowledged(false); setPending('current');
    setExpired(false); setError(''); setRefreshNotice(''); setMetadataReady(false); setManualVerification(false);
    void readCurrent(lifetime).finally(() => {
      if (lifetime.active) { lifetime.busy = false; setPending(null); }
    });
    return () => { lifetime.active = false; busyCallback.current?.(false); };
  }, [scope]);

  useEffect(() => { setPreviewState(null); setAcknowledged(false); }, [contextVersion]);
  useEffect(() => { busyCallback.current?.(pending !== null || issued || unresolved(operation) || !metadataReady); },
    [pending, issued, operation, metadataReady]);
  useEffect(() => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || operation?.status !== 'EXECUTING' || !metadataReady || pending !== null) return;
    const timer = window.setTimeout(() => {
      if (!lifetime.active || lifetime.busy) return;
      lifetime.busy = true; setPending('current');
      void readCurrent(lifetime, operation.planId).finally(() => { if (lifetime.active) { lifetime.busy = false; setPending(null); } });
    }, 2_000);
    return () => window.clearTimeout(timer);
  }, [operation, metadataReady, pending]);

  useEffect(() => {
    if (!plan) return;
    const remaining = Date.parse(plan.expiresAt) - Date.now();
    setExpired(remaining <= 0);
    if (remaining <= 0) return;
    const timer = window.setTimeout(() => { setExpired(true); setAcknowledged(false); }, remaining);
    return () => window.clearTimeout(timer);
  }, [plan]);

  const readPreview = async (mode: SessionLogoutMode) => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.scope !== scope || lifetime.busy || locked || issuedAccounts.has(accountId)) return;
    lifetime.busy = true; setPending(mode);
    setPreviewState(null); setResultState(null); setAcknowledged(false); setError(''); setRefreshNotice('');
    try {
      const value = await previewSessionLogout(accountId, mode);
      if (lifetime.active) setPreviewState({ scope, version: contextVersion, value });
    } catch (failure) {
      if (lifetime.active) setError(managedErrorCode(failure) ? failureText(failure)
        : managedErrorMessage(failure, '登出预览读取失败，请手动重新预览。'));
    } finally {
      if (lifetime.active) { lifetime.busy = false; setPending(null); }
    }
  };

  const execute = async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.scope !== scope || lifetime.busy || locked || issuedAccounts.has(accountId)
      || !plan || !acknowledged || !plan.targets.length) return;
    if (Date.parse(plan.expiresAt) <= Date.now()) { setExpired(true); setAcknowledged(false); return; }
    const submitted = plan;
    lifetime.busy = true; issuedAccounts.add(accountId); notify();
    storeOperation(accountId, { planId: submitted.planId, mode: submitted.mode, status: 'EXECUTING',
      requiresReauthorization: submitted.mode === 'ALL', results: [], message: '', canAcknowledge: false, canAcknowledgeAt: null });
    setPending('confirm'); setPreviewState(null); setAcknowledged(false); setError('');
    try {
      const value = await confirmSessionLogout(accountId, submitted.planId, submitted.mode);
      storeOperation(accountId, { ...value, canAcknowledge: false, canAcknowledgeAt: null });
      if (lifetime.active) setResultState({ scope, value });
      await settleTerminal(lifetime, value);
    } catch (failure) {
      storeOperation(accountId, { planId: submitted.planId, mode: submitted.mode, status: 'UNKNOWN',
        requiresReauthorization: submitted.mode === 'ALL', results: [], message: '', canAcknowledge: false, canAcknowledgeAt: null });
      if (lifetime.active) setError(failureText(failure));
    } finally {
      await readCurrent(lifetime, submitted.planId);
      // Even a lost confirmation response may already have changed official state.
      if (!lifetime.settled.has(submitted.planId) && operations.get(accountId)?.status !== 'EXECUTING') {
        await settleTerminal(lifetime, { planId: submitted.planId, mode: submitted.mode, status: 'UNKNOWN',
          requiresReauthorization: submitted.mode === 'ALL', results: [], message: '' });
      }
      issuedAccounts.delete(accountId); notify();
      if (lifetime.active) { lifetime.busy = false; setPending(null); }
    }
  };

  const refreshCurrent = () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy || disabled || issued) return;
    lifetime.busy = true; setPending('current'); setPreviewState(null); setAcknowledged(false); setManualVerification(false); setRefreshNotice('');
    void readCurrent(lifetime).finally(() => { if (lifetime.active) { lifetime.busy = false; setPending(null); } });
  };
  const acknowledgeUnknown = async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy || disabled || issued || !metadataReady || !operation?.canAcknowledge || !manualVerification) return;
    const submitted = operation;
    lifetime.busy = true; issuedAccounts.add(accountId); notify(); setPending('acknowledge'); setManualVerification(false); setError('');
    try {
      const value = await acknowledgeSessionLogoutUnknown(accountId, submitted.planId, submitted.mode);
      storeOperation(accountId, { ...value, canAcknowledge: false, canAcknowledgeAt: null });
      if (lifetime.active) setResultState({ scope, value });
      // A previous UNKNOWN reload does not replace the reload after explicit acknowledgement.
      lifetime.settled.delete(value.planId);
      await settleTerminal(lifetime, value);
    } catch (failure) { if (lifetime.active) setError(failureText(failure)); }
    finally {
      await readCurrent(lifetime, submitted.planId); issuedAccounts.delete(accountId); notify();
      if (lifetime.active) { lifetime.busy = false; setPending(null); }
    }
  };

  const counts = result ? {
    accepted: result.results.filter((item) => item.status === 'ACCEPTED').length,
    failed: result.results.filter((item) => item.status === 'FAILED').length,
    unknown: result.results.filter((item) => item.status === 'UNKNOWN').length,
    untouched: result.results.filter((item) => item.status === 'NOT_ATTEMPTED').length,
  } : null;
  return <Card size="small" title="官方会话登出">
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Typography.Text type="secondary">后台会重新读取官方会话并核对绑定后生成预览。未绑定模式排除受信任、已绑定、当前管理会话及状态不明确项；若当前管理会话也需登出，请用“登出所有会话”。</Typography.Text>
      <Space wrap>
        <Button danger disabled={locked} loading={pending === 'ALL'} onClick={() => void readPreview('ALL')}>登出所有会话</Button>
        <Button danger disabled={locked} loading={pending === 'UNBOUND'} onClick={() => void readPreview('UNBOUND')}>登出所有未绑定会话</Button>
      </Space>
      {issued ? <Alert type="warning" showIcon title="登出请求已发出，关闭窗口不会取消；请等待结果，不要重复操作。" /> : null}
      {unresolved(operation) ? <Space orientation="vertical" size={8} style={{ width: '100%' }}>
        <Alert type="warning" showIcon title="后台仍保留此操作锁"
          description="执行中或结果未知的操作不会因关闭窗口、等待超时而自动解锁。关闭窗口不会取消已发出的请求。" />
        {operation?.canAcknowledge ? <>
          <Typography.Text>请先在官网人工核对；解除锁不会撤销已发送的官方请求，也不会重试下线。</Typography.Text>
          <Checkbox checked={manualVerification} disabled={disabled || pending !== null || issued}
            onChange={(event) => setManualVerification(event.target.checked)}>我已在官网人工核对结果，理解解除锁不会撤销或重试官方请求</Checkbox>
          <Button danger disabled={disabled || pending !== null || issued || !manualVerification}
            loading={pending === 'acknowledge'} onClick={() => void acknowledgeUnknown()}>已在官网核对结果，解除本地操作锁</Button>
        </> : <Typography.Text type="secondary">后台尚未允许确认未知结果，请等待安全核对时间后刷新状态。{operation?.canAcknowledgeAt
          ? `可再次核对时间：${new Date(operation.canAcknowledgeAt).toLocaleString('zh-CN')}。` : ''}</Typography.Text>}
      </Space> : null}
      {plan ? <section aria-label="登出预览">
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          {plan.mode === 'ALL' ? <Alert type="error" showIcon title="将登出全部官方会话"
            description="包括已绑定、受信任及当前官方管理会话，可能使后台保存的官方授权失效并要求重新授权；会影响当前官方登录，但不会退出本系统管理账户。此操作终止登录，不主动解除设备信任。" /> : <Alert type="warning" showIcon title="仅登出后台新鲜核对的未绑定会话"
            description="受信任、绑定状态不明、已绑定、缺少会话 ID 和当前官方管理会话均会保留；请逐项核对后台返回的设备和排除原因。" />}
          <Descriptions size="small" column={1} items={[
            { key: 'count', label: '后台预览', children: `目标 ${plan.targets.length} 个，排除 ${plan.excluded.length} 个` },
            { key: 'expiry', label: '有效至', children: new Date(plan.expiresAt).toLocaleString('zh-CN') },
          ]} />
          <div><Typography.Text strong>本次目标设备</Typography.Text>
            {plan.targets.length ? <ul>{plan.targets.map((item, index) => <li key={index}><Device value={item} /></li>)}</ul>
              : <Typography.Paragraph type="secondary">没有可执行的目标设备</Typography.Paragraph>}</div>
          <div><Typography.Text strong>排除的设备及原因</Typography.Text>
            {plan.excluded.length ? <ul>{plan.excluded.map((item, index) => <li key={index}><Space orientation="vertical" size={2}>
              <Device value={item.session} /><Typography.Text type="secondary">{exclusionLabels[item.reason]}</Typography.Text></Space></li>)}</ul>
              : <Typography.Paragraph type="secondary">没有排除设备</Typography.Paragraph>}</div>
          {plan.warnings.includes('OFFICIAL_PROPAGATION_DELAY') ? <Typography.Text type="secondary">官方接受请求后，实际下线可能延迟；预览数量不代表已生效数量。</Typography.Text> : null}
          {expired ? <Alert type="warning" showIcon title="预览已过期，请重新预览。" /> : null}
          <Checkbox checked={acknowledged} disabled={locked || expired || !plan.targets.length}
            onChange={(event) => setAcknowledged(event.target.checked)}>我已核对预览的目标设备、排除原因和登出风险，确认执行本次操作</Checkbox>
          <Space wrap>
            <Button danger type="primary" disabled={locked || expired || !acknowledged || !plan.targets.length}
              onClick={() => void execute()}>确认{modeLabel[plan.mode]}</Button>
            <Button disabled={locked} onClick={() => { setPreviewState(null); setAcknowledged(false); }}>放弃本次预览</Button>
          </Space>
        </Space>
      </section> : null}
      {pending === 'confirm' ? <Button danger loading disabled>正在提交登出请求</Button> : null}
      {result && counts ? <Alert showIcon type={result.status === 'ACCEPTED' ? 'info' : 'warning'}
        title={result.status === 'RESOLVED_UNKNOWN' ? '本地操作锁已解除，未知结果仍需以官网核对为准；请重新预览后再操作。'
          : result.status === 'EXECUTING' ? '请求正在后台执行；当前显示的是提交状态，尚不能确认官方已接受或实际生效。'
          : result.status === 'ACCEPTED' ? '官方已接受登出请求，实际下线可能延迟，请核对设备状态。'
          : `${result.status === 'PARTIAL' ? '部分请求未完成' : result.status === 'FAILED' ? '登出请求未完成' : '登出结果未知'}，请核对后重新预览；不会自动重试。`}
        description={<Space orientation="vertical" size={4}>
          <Typography.Text>已接受 {counts.accepted} 个，失败 {counts.failed} 个，结果未知 {counts.unknown} 个，未尝试 {counts.untouched} 个</Typography.Text>
          {result.results.some((item) => item.errorCode === 'OFFICIAL_LOGOUT_CONTEXT_CHANGED') ? <Typography.Text>条件已变化，本次未执行，请重新预览。</Typography.Text> : null}
          {result.mode === 'ALL' ? <Typography.Text>全部会话登出是单次全局请求，以上为请求结果，不代表逐台设备已经下线。</Typography.Text> : null}
          {result.requiresReauthorization ? <Typography.Text>官方授权可能已失效，请重新完成官方网页登录。</Typography.Text> : null}
        </Space>} /> : null}
      {error ? <Alert type="error" showIcon title={error} /> : null}
      {refreshNotice ? <Alert type="warning" showIcon title={refreshNotice} /> : null}
      <Button disabled={disabled || pending !== null || issued} loading={pending === 'current'} onClick={refreshCurrent}>刷新登出操作状态</Button>
    </Space>
  </Card>;
}
