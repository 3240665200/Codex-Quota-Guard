import { Alert, Button, Card, Checkbox, Descriptions, List, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { managedErrorCode, managedErrorMessage } from '../../components/managedErrors';
import { confirmSessionLogout, previewSessionLogout } from '../../services/sessionLogoutApi';
import type { SessionLogoutDevice, SessionLogoutExclusionReason, SessionLogoutMode,
  SessionLogoutPreview, SessionLogoutResult } from '../../services/sessionLogoutApi';

const modeLabel: Record<SessionLogoutMode, string> = { ALL: '登出所有会话', UNBOUND: '登出所有未绑定会话' };
const exclusionLabels: Record<SessionLogoutExclusionReason, string> = {
  MISSING_SESSION_ID: '缺少会话 ID', DUPLICATE_SESSION_ID: '会话 ID 重复', DUPLICATE_ROW_ID: '设备行 ID 重复',
  ACTIVE_BINDING: '已有有效绑定', TRUSTED_DEVICE: '受信任设备', TRUSTED_DEVICE_UNKNOWN: '受信任状态未知',
  CURRENT_SESSION: '当前官方管理会话', CURRENT_SESSION_UNKNOWN: '当前会话身份未知',
};
// An issued request continues across close/reopen. Never abort or resend it from the UI.
const issuedAccounts = new Set<string>();
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };
const notify = () => listeners.forEach((listener) => listener());
type Lifetime = { active: boolean; busy: boolean; scope: string };
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
  return managedErrorMessage(failure, '请求结果未知，请核对后重新预览；不会自动重试。');
}

export default function SessionLogoutPanel({ accountId, contextVersion, disabled = false, onBusyChange, onSettled }: {
  accountId: string; contextVersion?: number; disabled?: boolean;
  onBusyChange?: (busy: boolean) => void;
  onSettled?: (accountId: string) => void | Promise<void>;
}) {
  const scope = `${accountId}:${contextVersion ?? ''}`;
  const [previewState, setPreviewState] = useState<{ scope: string; value: SessionLogoutPreview } | null>(null);
  const [resultState, setResultState] = useState<{ scope: string; value: SessionLogoutResult } | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [pending, setPending] = useState<SessionLogoutMode | 'confirm' | null>(null);
  const [expired, setExpired] = useState(false);
  const [error, setError] = useState('');
  const [refreshNotice, setRefreshNotice] = useState('');
  const lifetimeRef = useRef<Lifetime | null>(null);
  const busyCallback = useRef(onBusyChange); busyCallback.current = onBusyChange;
  const issued = useSyncExternalStore(subscribe, () => issuedAccounts.has(accountId), () => false);
  const plan = previewState?.scope === scope ? previewState.value : null;
  const result = resultState?.scope === scope ? resultState.value : null;
  const locked = disabled || pending !== null || issued;

  useEffect(() => {
    const lifetime = { active: true, busy: false, scope }; lifetimeRef.current = lifetime;
    setPreviewState(null); setResultState(null); setAcknowledged(false); setPending(null);
    setExpired(false); setError(''); setRefreshNotice('');
    return () => { lifetime.active = false; busyCallback.current?.(false); };
  }, [scope]);

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
    if (!lifetime?.active || lifetime.scope !== scope || lifetime.busy || disabled || issuedAccounts.has(accountId)) return;
    lifetime.busy = true; setPending(mode); busyCallback.current?.(true);
    setPreviewState(null); setResultState(null); setAcknowledged(false); setError(''); setRefreshNotice('');
    try {
      const value = await previewSessionLogout(accountId, mode);
      if (lifetime.active) setPreviewState({ scope, value });
    } catch (failure) {
      if (lifetime.active) setError(managedErrorCode(failure) ? failureText(failure)
        : managedErrorMessage(failure, '登出预览读取失败，请手动重新预览。'));
    } finally {
      if (lifetime.active) { lifetime.busy = false; setPending(null); busyCallback.current?.(false); }
    }
  };

  const execute = async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.scope !== scope || lifetime.busy || disabled || issuedAccounts.has(accountId)
      || !plan || !acknowledged || !plan.targets.length) return;
    if (Date.parse(plan.expiresAt) <= Date.now()) { setExpired(true); setAcknowledged(false); return; }
    const submitted = plan;
    const settle = onSettled;
    lifetime.busy = true; issuedAccounts.add(accountId); notify();
    setPending('confirm'); setPreviewState(null); setAcknowledged(false); setError('');
    busyCallback.current?.(true);
    try {
      const value = await confirmSessionLogout(accountId, submitted.planId, submitted.mode);
      if (lifetime.active) setResultState({ scope, value });
    } catch (failure) {
      if (lifetime.active) setError(failureText(failure));
    } finally {
      try { await settle?.(accountId); }
      catch { if (lifetime.active) setRefreshNotice('登出请求已结束，但状态重读未完成；旧快照不可作为当前状态，请关闭后重新打开。'); }
      issuedAccounts.delete(accountId); notify();
      if (lifetime.active) { lifetime.busy = false; setPending(null); busyCallback.current?.(false); }
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
      <Typography.Text type="secondary">后台会重新读取官方会话并核对绑定后生成预览；未绑定模式会保留受信任、状态未知、已绑定、缺少 ID 和当前官方管理会话。</Typography.Text>
      <Space wrap>
        <Button danger disabled={locked} loading={pending === 'ALL'} onClick={() => void readPreview('ALL')}>登出所有会话</Button>
        <Button danger disabled={locked} loading={pending === 'UNBOUND'} onClick={() => void readPreview('UNBOUND')}>登出所有未绑定会话</Button>
      </Space>
      {issued ? <Alert type="warning" showIcon title="登出请求已发出，关闭窗口不会取消；请等待结果，不要重复操作。" /> : null}
      {plan ? <section aria-label="登出预览">
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          {plan.mode === 'ALL' ? <Alert type="error" showIcon title="将登出全部官方会话"
            description="包括已绑定、受信任及当前官方管理会话，可能使后台保存的官方授权失效并要求重新授权；会影响当前官方登录，但不会退出本系统管理账户。" /> : <Alert type="warning" showIcon title="仅登出后台新鲜核对的未绑定会话"
            description="受信任、绑定状态不明、已绑定、缺少会话 ID 和当前官方管理会话均会保留；请逐项核对后台返回的设备和排除原因。" />}
          <Descriptions size="small" column={1} items={[
            { key: 'count', label: '后台预览', children: `目标 ${plan.targets.length} 个，排除 ${plan.excluded.length} 个` },
            { key: 'expiry', label: '有效至', children: new Date(plan.expiresAt).toLocaleString('zh-CN') },
          ]} />
          <List size="small" header="本次目标设备" dataSource={plan.targets} locale={{ emptyText: '没有可执行的目标设备' }}
            renderItem={(item) => <List.Item><Device value={item} /></List.Item>} />
          <List size="small" header="排除的设备及原因" dataSource={plan.excluded} locale={{ emptyText: '没有排除设备' }}
            renderItem={(item) => <List.Item><Space orientation="vertical" size={2}><Device value={item.session} />
              <Typography.Text type="secondary">{exclusionLabels[item.reason]}</Typography.Text></Space></List.Item>} />
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
        title={result.status === 'ACCEPTED' ? '官方已接受登出请求，实际下线可能延迟，请核对设备状态。'
          : `${result.status === 'PARTIAL' ? '部分请求未完成' : result.status === 'FAILED' ? '登出请求未完成' : '登出结果未知'}，请核对后重新预览；不会自动重试。`}
        description={<Space orientation="vertical" size={4}>
          <Typography.Text>已接受 {counts.accepted} 个，失败 {counts.failed} 个，结果未知 {counts.unknown} 个，未尝试 {counts.untouched} 个</Typography.Text>
          {result.mode === 'ALL' ? <Typography.Text>全部会话登出是单次全局请求，以上为请求结果，不代表逐台设备已经下线。</Typography.Text> : null}
          {result.requiresReauthorization ? <Typography.Text>官方授权可能已失效，请重新完成官方网页登录。</Typography.Text> : null}
        </Space>} /> : null}
      {error ? <Alert type="error" showIcon title={error} /> : null}
      {refreshNotice ? <Alert type="warning" showIcon title={refreshNotice} /> : null}
    </Space>
  </Card>;
}
