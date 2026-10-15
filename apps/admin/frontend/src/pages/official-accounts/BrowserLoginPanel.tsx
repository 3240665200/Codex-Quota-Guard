import { Alert, Button, Card, Descriptions, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { managedErrorMessage } from '../../components/managedErrors';
import { cancelOfficialBrowserLogin, getOfficialBrowserLogin, startOfficialBrowserLogin } from '../../services/officialBrowserLoginApi';
import type { OfficialBrowserLoginJob, OfficialBrowserLoginState } from '../../services/officialBrowserLoginApi';

const labels: Record<OfficialBrowserLoginState, string> = {
  WAITING_FOR_HELPER: '等待桌面助手', WAITING_FOR_LOGIN: '等待完成官方登录', SAVING: '正在加密保存授权',
  COMPLETED: '登录完成，授权已加密保存', FAILED: '官方网页登录未完成', CANCELLED: '登录任务已取消', EXPIRED: '登录任务已过期',
};
const pending = (state: OfficialBrowserLoginState) => ['WAITING_FOR_HELPER', 'WAITING_FOR_LOGIN', 'SAVING'].includes(state);

export default function BrowserLoginPanel({ accountId, version, proxyNodeId, disabled, onCompleted, onActiveChange }: {
  accountId: string; version: number; proxyNodeId: string | null; disabled: boolean;
  onCompleted: () => Promise<void>; onActiveChange?: (active: boolean) => void;
}) {
  const [job, setJob] = useState<OfficialBrowserLoginJob | null>(null);
  const [working, setWorking] = useState<'start' | 'cancel' | null>(null);
  const [error, setError] = useState('');
  const [pollFailed, setPollFailed] = useState(false);
  const [pollRevision, setPollRevision] = useState(0);
  const mounted = useRef(true);
  const requestRevision = useRef(0);
  const completedId = useRef<string | null>(null);
  const onCompletedRef = useRef(onCompleted);
  const onActiveChangeRef = useRef(onActiveChange);
  onCompletedRef.current = onCompleted;
  onActiveChangeRef.current = onActiveChange;

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; requestRevision.current += 1; };
  }, []);

  useEffect(() => {
    onActiveChangeRef.current?.(working !== null || Boolean(job && pending(job.state)));
  }, [job?.state, working]);

  const accept = async (result: OfficialBrowserLoginJob) => {
    if (!mounted.current) return;
    setJob(result);
    if (result.state === 'COMPLETED' && completedId.current !== result.id) {
      completedId.current = result.id;
      try { await onCompletedRef.current(); }
      catch { if (mounted.current) setError('授权已保存，账户与会话结果暂时未能重新读取，请手动刷新。'); }
    }
  };

  useEffect(() => {
    if (!job || !pending(job.state) || pollFailed) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const revision = requestRevision.current;
    const check = async () => {
      try {
        const result = await getOfficialBrowserLogin(job.id);
        if (stopped || !mounted.current || revision !== requestRevision.current) return;
        await accept(result);
        if (!stopped && pending(result.state)) timer = setTimeout(() => void check(), 3000);
      } catch (reason) {
        if (stopped || !mounted.current || revision !== requestRevision.current) return;
        setError(managedErrorMessage(reason, '暂时无法读取登录任务状态，请重新检查任务。'));
        setPollFailed(true);
      }
    };
    timer = setTimeout(() => void check(), 3000);
    return () => { stopped = true; clearTimeout(timer); };
  }, [job?.id, pollRevision, pollFailed]);

  const start = async () => {
    if (disabled || working || (job && pending(job.state))) return;
    const revision = ++requestRevision.current;
    setWorking('start'); setError(''); setPollFailed(false);
    try {
      const result = await startOfficialBrowserLogin(accountId, { version, proxyNodeId });
      if (mounted.current && revision === requestRevision.current) await accept(result);
    } catch (reason) {
      if (mounted.current) setError(managedErrorMessage(reason, '无法创建官方网页登录任务，请检查配置后重试。'));
    } finally { if (mounted.current) setWorking(null); }
  };

  const cancel = async () => {
    if (!job || working || !pending(job.state)) return;
    const revision = ++requestRevision.current;
    setWorking('cancel'); setError('');
    try {
      const result = await cancelOfficialBrowserLogin(job.id);
      if (mounted.current && revision === requestRevision.current) await accept(result);
    } catch (reason) {
      if (mounted.current) setError(managedErrorMessage(reason, '取消任务未成功，请重新检查任务状态。'));
    } finally {
      if (mounted.current) { setWorking(null); setPollRevision((value) => value + 1); }
    }
  };

  const expires = job ? new Date(job.expiresAt) : null;
  return <Card size="small" title="官方网页登录（推荐）">
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Typography.Paragraph style={{ marginBottom: 0 }}>通过桌面客户端的官方登录助手，在真实浏览器中完成登录，授权会自动加密保存到后台。</Typography.Paragraph>
      <ol style={{ margin: 0, paddingInlineStart: 22 }}>
        <li>使用同一个管理员账号登录桌面客户端。</li>
        <li>进入“设置 → 官方登录助手”，选择此账户的待授权任务。</li>
        <li>在打开的 Edge 或 Chrome 官方页面亲自完成登录，返回本页查看结果。</li>
      </ol>
      <Typography.Text type="secondary">本机浏览器使用管理员电脑当前网络（包括系统代理）；后台后续读取使用本页选择的访问代理节点，失败时不会自动切换为直连。</Typography.Text>
      {error ? <Alert type="error" showIcon title="登录任务操作未完成" description={error} /> : null}
      {job ? <>
        <Tag color={job.state === 'COMPLETED' ? 'success' : pending(job.state) ? 'processing' : 'default'}>{labels[job.state] ?? '未知任务状态'}</Tag>
        <Descriptions size="small" column={1} items={[
          { key: 'id', label: '任务识别码', children: <Typography.Text copyable>{job.id}</Typography.Text> },
          { key: 'expires', label: '任务有效期至', children: expires && !Number.isNaN(expires.getTime()) ? expires.toLocaleString('zh-CN') : '未提供' },
        ]} />
        {job.state === 'FAILED' ? <Alert type="warning" showIcon
          title={managedErrorMessage({ code: job.errorCode }, '官方网页登录未完成，请确认桌面助手状态后重新开始。')} /> : null}
        {job.state === 'CANCELLED' ? <Typography.Text type="secondary">取消任务不会让已登录的官方浏览器退出。</Typography.Text> : null}
        {job.state === 'EXPIRED' ? <Typography.Text type="secondary">此任务已超时，请重新开始，并在有效期内完成登录。</Typography.Text> : null}
      </> : null}
      <Space wrap>
        <Button type="primary" disabled={disabled || working !== null || Boolean(job && pending(job.state))}
          loading={working === 'start'} onClick={() => void start()}>{job ? '重新开始官方网页登录' : '开始官方网页登录'}</Button>
        {job && pending(job.state) ? <Button loading={working === 'cancel'} disabled={working !== null || job.state === 'SAVING'}
          onClick={() => void cancel()}>取消登录任务</Button> : null}
        {pollFailed && job && pending(job.state) ? <Button onClick={() => { setPollFailed(false); setError(''); setPollRevision((value) => value + 1); }}>重新检查任务</Button> : null}
      </Space>
      {job && pending(job.state) ? <Typography.Text type="secondary">每 3 秒更新任务状态。关闭本页会停止页面检查，任务将继续等待助手或到期。</Typography.Text> : null}
    </Space>
  </Card>;
}
