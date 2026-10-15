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
      if (next.quarantined) setError('后台仍在自动整理或重试，请稍后查看状态。');
      else setNotice('后台已确认新鲜会话快照，队列已恢复。');
    } catch (failure) {
      if (lifetime.active) setError(managedErrorMessage(failure, '队列恢复未完成，请检查官方连接后刷新状态重试。'));
    } finally {
      if (lifetime.active) { lifetime.busy = false; setAcknowledged(false); setPending(null); }
    }
  };
  const processing = !!state?.activeAttemptId;
  const label = processing ? state?.queueState === 'PREPARING' ? '正在准备' : '登录处理中'
    : state?.queueState === 'RETRYING' ? '自动重试中' : state?.quarantined ? '自动整理中' : state ? '队列正常' : '状态待确认';
  return <Card size="small" title="本设备绑定登录队列" extra={<Tag color={processing ? 'processing' : state?.quarantined ? 'warning' : state ? 'success' : 'default'}>{label}</Tag>}>
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Typography.Text type="secondary">排队最长 60 分钟；取得登录名额后有 30 分钟完成登录。取消、超时或识别失败后，后台会短暂整理并自动交接，无需人工恢复。</Typography.Text>
      <Typography.Text type="secondary">整理后会重新读取会话并排除已出现的旧会话，但不能完全保证不受控的官网迟到登录的真实归属。请停止已取消的登录页面；多个新增候选不会自动绑定。</Typography.Text>
      {state?.quarantined ? <>
        <Alert type="info" showIcon title={state.queueState === 'RETRYING' ? '等待新鲜官方会话快照，后台将自动重试' : '后台正在自动整理登录队列'}
          description="通常先等待约 30 秒，再重新取得会话基线。只有后台验证成功后才会授予下一位登录名额，无需点击恢复。" />
        {state.retryAt ? <Typography.Text type="secondary">下次整理或重试时间：{new Date(state.retryAt).toLocaleString('zh-CN')}</Typography.Text> : null}
        {processing ? <Typography.Text>当前仍有登录任务，请等待任务结束并刷新状态后再处理。</Typography.Text> : null}
        <Typography.Text type="secondary">高级手动恢复（通常无需操作）：仅在确认前一位用户已经停止登录后使用。</Typography.Text>
        <Checkbox disabled={disabled || processing || pending !== null} checked={acknowledged}
          onChange={(event) => setAcknowledged(event.target.checked)}>已确认前一个用户停止登录，理解迟到会话风险</Checkbox>
        <Button danger disabled={disabled || processing || pending !== null || !acknowledged}
          loading={pending === 'recover'} onClick={() => void recover()}>确认风险并恢复队列</Button>
      </> : processing ? <Typography.Text>当前用户正在完成官方登录，请等待本次任务结束。</Typography.Text>
        : state?.queueState === 'RETRYING' ? <Alert type="info" showIcon title="等待新鲜官方会话快照，后台将自动重试" /> : null}
      {error ? <Alert type="error" showIcon title={error} /> : null}
      {notice ? <Alert type="success" showIcon title={notice} /> : null}
      <Button disabled={disabled || pending !== null} loading={pending === 'read'} onClick={() => setRefresh((value) => value + 1)}>刷新队列状态</Button>
    </Space>
  </Card>;
}
