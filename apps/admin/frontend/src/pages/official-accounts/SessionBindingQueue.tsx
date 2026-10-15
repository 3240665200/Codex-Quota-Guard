import { Alert, Button, Card, Checkbox, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { managedErrorMessage } from '../../components/managedErrors';
import { getSessionBindingQueue, recoverSessionBindingQueue } from '../../services/sessionBindingQueueApi';
import type { SessionBindingQueueState } from '../../services/sessionBindingQueueApi';

/** Queue recovery changes only product attribution. It never revokes an official login. */
export default function SessionBindingQueue({ accountId, disabled = false }: { accountId: string; disabled?: boolean }) {
  const [state, setState] = useState<SessionBindingQueueState | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [pending, setPending] = useState<'read' | 'recover' | null>('read');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [refresh, setRefresh] = useState(0);
  const lifetimeRef = useRef<{ active: boolean; busy: boolean } | null>(null);

  useEffect(() => {
    const lifetime = { active: true, busy: true }; lifetimeRef.current = lifetime;
    setState(null); setAcknowledged(false); setPending('read'); setError(''); setNotice('');
    void getSessionBindingQueue(accountId).then((next) => {
      if (lifetime.active) setState(next);
    }, (failure: unknown) => {
      if (lifetime.active) setError(managedErrorMessage(failure, '绑定队列状态读取失败，请手动刷新后重试。'));
    }).finally(() => {
      if (lifetime.active) { lifetime.busy = false; setPending(null); }
    });
    return () => { lifetime.active = false; };
  }, [accountId, refresh]);

  const recover = async () => {
    const lifetime = lifetimeRef.current;
    if (!lifetime?.active || lifetime.busy || disabled || !acknowledged || !state?.quarantined || state.activeAttemptId) return;
    lifetime.busy = true; setPending('recover'); setError(''); setNotice('');
    try {
      const next = await recoverSessionBindingQueue(accountId);
      if (!lifetime.active) return;
      // Only the verified server response may remove quarantine.
      setState(next);
      if (next.quarantined) setError('后台尚未确认恢复，队列仍需管理员处理。');
      else setNotice('后台已确认新鲜会话快照，队列已恢复。');
    } catch (failure) {
      if (lifetime.active) setError(managedErrorMessage(failure, '队列恢复未完成，请检查官方连接后刷新状态重试。'));
    } finally {
      if (lifetime.active) { lifetime.busy = false; setAcknowledged(false); setPending(null); }
    }
  };
  const processing = !!state?.activeAttemptId;
  const label = processing ? '登录处理中' : state?.quarantined ? '需管理员处理' : state ? '队列正常' : '状态待确认';
  return <Card size="small" title="本设备绑定登录队列" extra={<Tag color={processing ? 'processing' : state?.quarantined ? 'warning' : state ? 'success' : 'default'}>{label}</Tag>}>
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Typography.Text type="secondary">官方登录按账户排队。取消、超时或无法唯一识别新增会话时，需要管理员核对后恢复，防止迟到登录归到下一位用户。</Typography.Text>
      {state?.quarantined ? <>
        <Alert type="warning" showIcon title="恢复前请确认前一位用户已停止官方登录"
          description="已打开的官方登录页面可能继续产生迟到会话。请先与前一位用户核对；确认后，后台会重新取得新鲜官方会话快照，成功后才允许后续用户登录。" />
        {processing ? <Typography.Text>当前仍有登录任务，请等待任务结束并刷新状态后再处理。</Typography.Text> : null}
        <Checkbox disabled={disabled || processing || pending !== null} checked={acknowledged}
          onChange={(event) => setAcknowledged(event.target.checked)}>已确认前一个用户停止登录，理解迟到会话风险</Checkbox>
        <Button danger disabled={disabled || processing || pending !== null || !acknowledged}
          loading={pending === 'recover'} onClick={() => void recover()}>确认风险并恢复队列</Button>
      </> : processing ? <Typography.Text>当前用户正在完成官方登录，请等待本次任务结束。</Typography.Text> : null}
      {error ? <Alert type="error" showIcon title={error} /> : null}
      {notice ? <Alert type="success" showIcon title={notice} /> : null}
      <Button disabled={disabled || pending !== null} loading={pending === 'read'} onClick={() => setRefresh((value) => value + 1)}>刷新队列状态</Button>
    </Space>
  </Card>;
}
