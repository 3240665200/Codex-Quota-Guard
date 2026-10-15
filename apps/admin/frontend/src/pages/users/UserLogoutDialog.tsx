import { Alert, Button, Checkbox, Modal, Space, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { managedErrorMessage } from '../../components/managedErrors';
import { confirmUserLogoutAll, getUserLogoutAllCurrent, getUserLogoutAllResult, previewUserLogoutAll } from '../../services/userLogoutApi';
import type { UserLogoutPreview, UserLogoutResult } from '../../services/userLogoutApi';

type Lifetime = { active: boolean; busy: boolean; userId: string };
const unresolved = (result: UserLogoutResult | null) => result?.status === 'EXECUTING' || result?.status === 'UNKNOWN';
const exclusionLabel: Record<string, string> = {
  CURRENT_SESSION: '当前官方管理会话，保留', CURRENT_SESSION_UNKNOWN: '无法确定当前管理会话，保留',
  TRUSTED_DEVICE_UNKNOWN: '信任状态未知，保留', MISSING_SESSION_ID: '缺少会话标识',
  DUPLICATE_SESSION_ID: '会话标识重复', DUPLICATE_ROW_ID: '设备记录重复',
  ACTIVE_BINDING: '已有绑定', TRUSTED_DEVICE: '受信任设备',
};
const outcomeTitle: Record<UserLogoutResult['status'], string> = {
  EXECUTING: '后台正在执行，请勿重复提交', ACCEPTED: '请求已处理，官方下线生效可能延迟',
  PARTIAL: '部分官方会话未完成', FAILED: '下线请求未完成', UNKNOWN: '存在结果未知的官方请求，请先核对',
};

export default function UserLogoutDialog({ user, onClose }: {
  user: { id: string; email: string }; onClose: () => void;
}) {
  const [plan, setPlan] = useState<UserLogoutPreview | null>(null);
  const [result, setResult] = useState<UserLogoutResult | null>(null);
  const [submittedId, setSubmittedId] = useState<string | null>(null);
  const [phase, setPhase] = useState<'current' | 'preview' | 'confirm' | 'read' | null>('current');
  const [error, setError] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  const [expired, setExpired] = useState(false);
  const lifetimeRef = useRef<Lifetime | null>(null);
  const operationStarted = useRef(Date.now());
  const active = (lifetime: Lifetime) => lifetime.active && lifetimeRef.current === lifetime;

  const loadPreview = async (lifetime: Lifetime) => {
    const value = await previewUserLogoutAll(lifetime.userId);
    if (active(lifetime)) { setPlan(value); setResult(null); setSubmittedId(null); setAcknowledged(false); }
  };
  const loadCurrent = async (lifetime: Lifetime) => {
    const { operation } = await getUserLogoutAllCurrent(lifetime.userId);
    if (!active(lifetime)) return;
    if (operation) { setResult(operation); setSubmittedId(operation.planId); }
    else await loadPreview(lifetime);
  };

  useEffect(() => {
    const lifetime = { active: true, busy: true, userId: user.id }; lifetimeRef.current = lifetime;
    setPlan(null); setResult(null); setSubmittedId(null); setPhase('current'); setError(''); setAcknowledged(false);
    operationStarted.current = Date.now();
    void loadCurrent(lifetime).catch(() => {
      if (active(lifetime)) setError('无法核对后台操作状态或生成安全预览，请刷新状态；本次不会执行下线。');
    }).finally(() => { if (active(lifetime)) { lifetime.busy = false; setPhase(null); } });
    return () => { lifetime.active = false; };
  }, [user.id]);

  useEffect(() => {
    if (!plan) { setExpired(false); return; }
    const remaining = Date.parse(plan.expiresAt) - Date.now();
    setExpired(remaining <= 0);
    if (remaining <= 0) return;
    const timer = window.setTimeout(() => { setExpired(true); setAcknowledged(false); }, Math.min(remaining, 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [plan]);

  const refresh = useCallback(async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy) return;
    lifetime.busy = true; setPhase('read'); setError('');
    try {
      if (submittedId) {
        const value = await getUserLogoutAllResult(lifetime.userId, submittedId);
        if (active(lifetime)) setResult(value);
      } else await loadCurrent(lifetime);
    } catch {
      if (active(lifetime)) setError(submittedId ? '无法读取本次下线结果，请稍后刷新；不会重新提交下线请求。' : '无法核对后台操作状态，请稍后刷新。');
    } finally { if (active(lifetime)) { lifetime.busy = false; setPhase(null); } }
  }, [submittedId]);

  useEffect(() => {
    if (result?.status !== 'EXECUTING' || phase !== null || error || Date.now() - operationStarted.current > 300_000) return;
    const timer = window.setTimeout(() => void refresh(), 2_000);
    return () => window.clearTimeout(timer);
  }, [result, phase, error, refresh]);

  const confirm = async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy || !plan || submittedId || !acknowledged || expired) return;
    if (Date.parse(plan.expiresAt) <= Date.now()) { setExpired(true); setAcknowledged(false); return; }
    const planId = plan.planId;
    lifetime.busy = true; setPhase('confirm'); setSubmittedId(planId); setPlan(null); setAcknowledged(false); setError('');
    operationStarted.current = Date.now();
    try {
      const value = await confirmUserLogoutAll(lifetime.userId, planId);
      if (active(lifetime)) setResult(value);
    } catch {
      if (active(lifetime)) setError('提交结果尚未确认，正在只读核对；不会自动重发下线请求。');
      try {
        const value = await getUserLogoutAllResult(lifetime.userId, planId);
        if (active(lifetime)) { setResult(value); setError(''); }
      } catch { /* Preserve the uncertain outcome; only an explicit read can resolve it. */ }
    } finally { if (active(lifetime)) { lifetime.busy = false; setPhase(null); } }
  };

  const newPreview = async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy || unresolved(result) || submittedId && !result) return;
    lifetime.busy = true; setPhase('preview'); setError(''); setPlan(null); setAcknowledged(false);
    try { await loadPreview(lifetime); }
    catch (failure) { if (active(lifetime)) setError(managedErrorMessage(failure, '无法生成安全预览，请核对官方授权和会话状态。')); }
    finally { if (active(lifetime)) { lifetime.busy = false; setPhase(null); } }
  };
  const visiblePlan = plan?.userId === user.id ? plan : null;
  const visibleResult = result?.userId === user.id ? result : null;
  const items = visibleResult?.accounts.flatMap((account) => account.results) ?? [];
  const count = (status: string) => items.filter((item) => item.status === status).length;
  const busy = phase !== null;

  return <Modal open title={`强制下线所有账户 · ${user.email}`} width={760} mask={{ closable: false }}
    closable={false} keyboard={phase !== 'confirm'} onCancel={() => { if (phase !== 'confirm') onClose(); }}
    footer={<Space wrap>
      <Button disabled={busy} onClick={() => void refresh()}>刷新操作状态</Button>
      <Button disabled={phase === 'confirm'} onClick={onClose}>关闭</Button>
      {visiblePlan ? <Button danger type="primary" loading={phase === 'confirm'} disabled={busy || expired || !acknowledged || !!submittedId}
        onClick={() => void confirm()}>确认下线所有账户</Button> : null}
    </Space>}>
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Alert showIcon type="warning" title="系统登录 + 该用户绑定的官方会话"
        description="仅处理该用户当前绑定的官方会话，不登出其他用户、未绑定或已解绑的会话。统计程序的上报凭据不受影响。" />
      {phase === 'current' || phase === 'preview' ? <Typography.Text>正在核对后台状态与目标设备…</Typography.Text> : null}
      {visiblePlan ? <>
        <Typography.Text>预览有效至：{new Date(visiblePlan.expiresAt).toLocaleString('zh-CN')}</Typography.Text>
        {!visiblePlan.accounts.length ? <Alert type="info" showIcon title="没有当前有效的官方会话绑定，本次仅使系统登录失效。" /> : null}
        {visiblePlan.accounts.map((account) => <section key={account.officialAccountId}>
          <Typography.Text strong>{account.displayName}</Typography.Text>
          {account.status === 'NOT_ATTEMPTED' ? <Typography.Paragraph type="warning">此官方账户无法安全执行，将保留并标记未尝试（{account.errorCode || '没有可执行目标'}）。</Typography.Paragraph> : null}
          {account.targets.length ? <ul>{account.targets.map((target) => <li key={target.sessionId}>
            <span>{target.displayName || '未命名设备'}</span> · {target.platform || '平台未知'}
            {target.trustedDevice ? '（受信任设备：仅登出会话，不解除信任）' : ''}
          </li>)}</ul> : null}
          {account.excluded.length ? <ul>{account.excluded.map((entry, index) => <li key={index}>
            {entry.session.displayName || '未命名设备'}：{exclusionLabel[entry.reason] || '安全检查未通过，保留'}
          </li>)}</ul> : null}
        </section>)}
        {expired ? <Alert showIcon type="warning" title="预览已过期，请重新预览。" /> : null}
        <Checkbox disabled={busy || expired} checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)}>
          我已核对目标用户和设备，确认使其系统登录失效并提交上述官方会话下线请求
        </Checkbox>
      </> : null}
      {visibleResult ? <>
        <Alert showIcon type={visibleResult.systemStatus === 'REVOKED' ? 'info' : 'warning'}
          title={visibleResult.systemStatus === 'REVOKED' ? '系统账户：已强制下线' : '系统账户：尚未执行下线'} />
        <Alert showIcon type={visibleResult.status === 'ACCEPTED' ? 'info' : 'warning'} title={outcomeTitle[visibleResult.status]}
          description={`官方会话：已接受 ${count('ACCEPTED')} 个，失败 ${count('FAILED')} 个，结果未知 ${count('UNKNOWN')} 个，未尝试 ${count('NOT_ATTEMPTED')} 个。官方已接受不代表设备立即下线。`} />
        {visibleResult.accounts.map((account) => <div key={account.officialAccountId}>
          <Typography.Text strong>{account.displayName}</Typography.Text>：{account.status}
          {account.errorCode ? <Typography.Text type="warning">（{account.errorCode}）</Typography.Text> : null}
          {account.requiresReauthorization ? <Typography.Paragraph type="warning">此官方账户需要重新授权。</Typography.Paragraph> : null}
        </div>)}
      </> : null}
      {phase === 'confirm' ? <Typography.Text>正在提交一次性下线请求，请勿重复操作。</Typography.Text> : null}
      {error ? <Alert showIcon type="error" title={error} /> : null}
      {!busy && (expired || visibleResult && !unresolved(visibleResult)) ? <Button onClick={() => void newPreview()}>重新预览</Button> : null}
    </Space>
  </Modal>;
}
