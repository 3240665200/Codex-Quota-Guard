import { Alert, Button, Card, Descriptions, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ClipboardEvent, KeyboardEvent, MouseEvent, WheelEvent } from 'react';
import { managedErrorMessage } from '../../components/managedErrors';
import { cancelOfficialWebLogin, completeOfficialWebLogin, getOfficialWebLogin, getOfficialWebLoginFrame,
  sendOfficialWebLoginInput, startOfficialWebLogin, WEB_LOGIN_KEYS } from '../../services/officialWebLoginApi';
import type { OfficialWebLoginFrame, OfficialWebLoginInput, OfficialWebLoginJob } from '../../services/officialWebLoginApi';

const pending = (job: OfficialWebLoginJob | null) => !!job && ['WAITING_FOR_HELPER', 'WAITING_FOR_LOGIN', 'SAVING'].includes(job.state);
const labels = { WAITING_FOR_HELPER: '正在准备服务器浏览器', WAITING_FOR_LOGIN: '等待完成官方登录', SAVING: '正在预检并加密保存授权',
  COMPLETED: '登录完成，授权已加密保存', FAILED: '官方网页登录未完成', CANCELLED: '登录任务已取消', EXPIRED: '登录任务已过期' };
type Lifetime = { alive: boolean; accountId: string; epoch: number; job: OfficialWebLoginJob | null;
  queue: OfficialWebLoginInput[]; sending: boolean; paused: boolean; saveAttempted: boolean };
const lifetime = (accountId: string): Lifetime => ({ alive: true, accountId, epoch: 0, job: null, queue: [], sending: false, paused: false, saveAttempted: false });
const abandon = (job: OfficialWebLoginJob | null) => { if (pending(job)) void cancelOfficialWebLogin(job!.id).catch(() => undefined); };

export default function BrowserLoginPanel({ accountId, version, proxyNodeId, disabled, onResultChanged, onActiveChange }: {
  accountId: string; version: number; proxyNodeId: string | null; disabled: boolean;
  onResultChanged: () => Promise<void>; onActiveChange?: (active: boolean) => void;
}) {
  const [job, setJob] = useState<OfficialWebLoginJob | null>(null);
  const [frame, setFrame] = useState<OfficialWebLoginFrame | null>(null);
  const [working, setWorking] = useState<'start' | 'cancel' | 'complete' | null>(null);
  const [error, setError] = useState('');
  const [paused, setPaused] = useState(false);
  const [revision, setRevision] = useState(0);
  const current = useRef(lifetime(accountId));
  const canvas = useRef<HTMLCanvasElement>(null);
  const textInput = useRef<HTMLInputElement>(null);
  const completedId = useRef<string | null>(null);
  const callbacks = useRef({ onResultChanged, onActiveChange }); callbacks.current = { onResultChanged, onActiveChange };
  const visibleJob = job?.officialAccountId === accountId ? job : null;
  const active = pending(visibleJob);
  const isCurrent = (scope: Lifetime, epoch = scope.epoch) => scope.alive && current.current === scope && scope.epoch === epoch;
  const clearFrame = () => {
    setFrame(null);
    const element = canvas.current; element?.getContext('2d')?.clearRect(0, 0, element.width, element.height);
    if (textInput.current) textInput.current.value = '';
  };
  const pause = (scope: Lifetime, reason: unknown, fallback: string) => {
    if (!isCurrent(scope)) return;
    scope.paused = true; scope.queue.length = 0;
    clearFrame(); setPaused(true); setError(managedErrorMessage(reason, fallback));
  };
  const accept = async (scope: Lifetime, value: OfficialWebLoginJob) => {
    if (!isCurrent(scope) || value.officialAccountId !== scope.accountId || scope.job && value.id !== scope.job.id) return;
    if (scope.job && Date.parse(value.expiresAt) > Date.parse(scope.job.expiresAt)) value = { ...value, expiresAt: scope.job.expiresAt };
    scope.job = value; setJob(value);
    if (!pending(value)) { scope.queue.length = 0; clearFrame(); }
    if (['COMPLETED', 'FAILED'].includes(value.state) && completedId.current !== value.id) {
      completedId.current = value.id;
      try { await callbacks.current.onResultChanged(); }
      catch { if (isCurrent(scope)) setError('登录任务已结束，账户结果暂时未能重新读取，请手动刷新。'); }
    }
  };

  useEffect(() => {
    const scope = lifetime(accountId); current.current = scope;
    setJob(null); clearFrame(); setWorking(null); setError(''); setPaused(false);
    return () => {
      scope.alive = false; scope.epoch += 1; scope.queue.length = 0; scope.paused = true;
      const element = canvas.current; element?.getContext('2d')?.clearRect(0, 0, element.width, element.height);
      if (textInput.current) textInput.current.value = '';
      abandon(scope.job); callbacks.current.onActiveChange?.(false);
    };
  }, [accountId]);
  useEffect(() => { callbacks.current.onActiveChange?.(active || working !== null); }, [active, working]);
  useEffect(() => {
    const closePage = () => {
      const scope = current.current;
      if (!pending(scope.job)) return;
      scope.epoch += 1; scope.paused = true; scope.queue.length = 0;
      abandon(scope.job);
      scope.job = { ...scope.job!, state: 'CANCELLED', pageOrigin: null, readyToSave: false };
      setJob(scope.job); setWorking(null); setPaused(true); clearFrame();
    };
    window.addEventListener('pagehide', closePage);
    return () => window.removeEventListener('pagehide', closePage);
  }, []);

  useEffect(() => {
    if (!visibleJob || !pending(visibleJob) || paused || working === 'cancel' || working === 'complete') return;
    const scope = current.current; const epoch = scope.epoch; let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const check = async () => {
      try {
        const result = await getOfficialWebLogin(visibleJob.id);
        if (stopped || !isCurrent(scope, epoch)) return;
        await accept(scope, result);
        if (stopped || !isCurrent(scope, epoch) || !pending(result)) return;
        if (result.state === 'WAITING_FOR_LOGIN') {
          const image = await getOfficialWebLoginFrame(result.id);
          if (stopped || !isCurrent(scope, epoch) || scope.paused) return;
          if (image.errorCode === 'BROWSER_LOGIN_SESSION_CHANGED') {
            setFrame({ ...image, readyToSave: false });
            setError(managedErrorMessage({ code: image.errorCode }));
          } else {
            setFrame(image);
            if (image.errorCode) { pause(scope, { code: image.errorCode }, '此登录页面暂不可用，请检查任务或取消后重新开始。'); return; }
          }
        } else clearFrame();
        if (!stopped && isCurrent(scope, epoch)) timer = setTimeout(() => void check(), 1000);
      } catch (reason) {
        if (!stopped && isCurrent(scope, epoch)) pause(scope, reason, '暂时无法读取服务器浏览器，请重新检查任务或取消；不会切换到桌面助手。');
      }
    };
    void check();
    return () => { stopped = true; clearTimeout(timer); };
  }, [visibleJob?.id, visibleJob?.state, paused, revision, working]);

  useEffect(() => {
    if (!visibleJob || !pending(visibleJob)) return;
    const scope = current.current;
    const expire = () => {
      if (!isCurrent(scope) || !pending(scope.job)) return;
      const expired = { ...scope.job!, state: 'EXPIRED' as const, pageOrigin: null, readyToSave: false };
      scope.paused = true; scope.epoch += 1; scope.queue.length = 0;
      abandon(scope.job); scope.job = expired; setJob(expired); setPaused(true); clearFrame(); setWorking(null);
    };
    const remaining = Date.parse(visibleJob.expiresAt) - Date.now();
    const timer = setTimeout(expire, Math.max(0, Math.min(remaining, 2_147_483_647)));
    return () => clearTimeout(timer);
  }, [visibleJob?.id, visibleJob?.expiresAt, visibleJob?.state]);

  useEffect(() => {
    if (!frame || !canvas.current) return;
    const element = canvas.current; const image = new Image(); let stopped = false;
    image.onload = () => { if (!stopped) element.getContext('2d')?.drawImage(image, 0, 0, frame.width, frame.height); };
    image.onerror = () => { if (!stopped) pause(current.current, null, '浏览器画面无法显示，请重新检查任务。'); };
    image.src = `data:image/jpeg;base64,${frame.jpegBase64}`;
    return () => { stopped = true; image.onload = null; image.onerror = null; image.src = ''; element.getContext('2d')?.clearRect(0, 0, element.width, element.height); };
  }, [frame]);

  const start = async () => {
    if (disabled || working || pending(current.current.job)) return;
    const scope = current.current; const epoch = ++scope.epoch;
    scope.job = null; scope.queue.length = 0; scope.sending = false;
    scope.paused = false; scope.saveAttempted = false; setPaused(false); setError(''); clearFrame(); setWorking('start');
    try {
      const result = await startOfficialWebLogin(accountId, { version, proxyNodeId });
      if (!isCurrent(scope, epoch)) { abandon(result); return; }
      await accept(scope, result);
    } catch (reason) { if (isCurrent(scope, epoch)) setError(managedErrorMessage(reason, '无法创建服务器网页登录任务，请检查配置后重试。')); }
    finally { if (isCurrent(scope, epoch)) setWorking(null); }
  };
  const cancel = async () => {
    const scope = current.current; if (!scope.job || working || !pending(scope.job)) return;
    const epoch = ++scope.epoch; scope.paused = true; scope.queue.length = 0;
    setPaused(true); clearFrame(); setWorking('cancel'); setError('');
    try { const result = await cancelOfficialWebLogin(scope.job.id); if (isCurrent(scope, epoch)) await accept(scope, result); }
    catch (reason) { if (isCurrent(scope, epoch)) setError(managedErrorMessage(reason, '取消结果尚未确认，请重新检查任务状态。')); }
    finally { if (isCurrent(scope, epoch)) setWorking(null); }
  };
  const complete = async () => {
    const scope = current.current;
    if (!scope.job || working || !frame?.readyToSave || scope.paused || scope.sending || scope.queue.length || scope.saveAttempted) return;
    scope.saveAttempted = true; const epoch = ++scope.epoch;
    scope.queue.length = 0; clearFrame(); setWorking('complete'); setError('');
    try { const result = await completeOfficialWebLogin(scope.job.id); if (isCurrent(scope, epoch)) await accept(scope, result); }
    catch (reason) { if (isCurrent(scope, epoch)) pause(scope, reason, '保存请求结果尚未确认，请重新检查任务；此请求不会自动重发。'); }
    finally { if (isCurrent(scope, epoch)) setWorking(null); }
  };
  const enqueue = (input: OfficialWebLoginInput) => {
    const scope = current.current;
    if (!isCurrent(scope) || scope.paused || scope.saveAttempted || !frame || !scope.job || scope.job.state !== 'WAITING_FOR_LOGIN' || working) return;
    if (scope.queue.length + Number(scope.sending) >= 16) { setError('输入过快，请等待服务器处理后继续。'); return; }
    scope.queue.push(input);
    if (scope.sending) return;
    const epoch = scope.epoch; scope.sending = true;
    void (async () => {
      try {
        while (isCurrent(scope, epoch) && !scope.paused && scope.queue.length) {
          const next = scope.queue.shift()!;
          await sendOfficialWebLoginInput(scope.job!.id, next);
        }
      } catch (reason) { if (isCurrent(scope, epoch)) pause(scope, reason, '输入结果尚未确认，请检查画面后继续；本次输入不会自动重发。'); }
      finally { if (isCurrent(scope, epoch)) { scope.sending = false; scope.queue.length = 0; } }
    })();
  };
  const point = (event: MouseEvent<HTMLCanvasElement> | WheelEvent<HTMLCanvasElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    if (!frame || !rect.width || !rect.height) return null;
    return { x: Math.max(0, Math.min(frame.width - 1, Math.floor((event.clientX - rect.left) * frame.width / rect.width))),
      y: Math.max(0, Math.min(frame.height - 1, Math.floor((event.clientY - rect.top) * frame.height / rect.height))) };
  };
  const text = (value: string) => {
    if (!value || value.length > 2048 || /[\x00-\x1f\x7f]/.test(value)) { setError('每次输入限 1 至 2048 个字符，不支持换行或控制字符。'); return; }
    enqueue({ type: 'TEXT', text: value });
  };
  const paste = (event: ClipboardEvent) => { event.preventDefault(); text(event.clipboardData.getData('text/plain')); };
  const key = (event: KeyboardEvent<HTMLCanvasElement>) => {
    if (event.key === 'Escape') { event.currentTarget.blur(); return; }
    if (event.nativeEvent.isComposing || event.altKey || event.metaKey) return;
    if (event.ctrlKey) { if (event.key.toLowerCase() === 'a' && !event.shiftKey) { event.preventDefault(); enqueue({ type: 'KEY', key: 'a', ctrl: true }); } return; }
    if ((WEB_LOGIN_KEYS as readonly string[]).includes(event.key) && (!event.shiftKey || event.key === 'Tab')) {
      event.preventDefault(); enqueue({ type: 'KEY', key: event.key, ...(event.shiftKey ? { shift: true } : {}) });
    } else if (event.key.length === 1) { event.preventDefault(); text(event.key); }
  };

  return <Card size="small" title="官方网页登录（推荐）"><Space orientation="vertical" size={12} style={{ width: '100%' }}>
    <Typography.Paragraph style={{ marginBottom: 0 }}>登录在服务器的独立浏览器中进行。请在下方官方页面亲自输入账号、密码和验证码；验证码与浏览器验证需要你自行完成。</Typography.Paragraph>
    <Typography.Text type="secondary">登录与后续读取均使用所选代理节点；失败时不会自动切换出口。登录画面和输入仅用于本次任务，关闭、取消或到期会销毁该浏览器。</Typography.Text>
    {error ? <Alert type="error" showIcon title="登录任务操作未完成" description={error} /> : null}
    {visibleJob ? <><Tag color={visibleJob.state === 'COMPLETED' ? 'success' : active ? 'processing' : 'default'}>{labels[visibleJob.state]}</Tag>
      <Descriptions size="small" column={1} items={[
        { key: 'origin', label: '当前官方站点', children: frame?.pageOrigin || visibleJob.pageOrigin || '等待官方页面' },
        { key: 'expires', label: '任务有效期至', children: new Date(visibleJob.expiresAt).toLocaleString('zh-CN') },
      ]} />
      {visibleJob.state === 'FAILED' ? <Alert type="warning" showIcon title={managedErrorMessage({ code: visibleJob.errorCode }, '官方网页登录未完成，请检查服务器浏览器配置后重新开始。')} /> : null}
    </> : null}
    {frame && active && !paused ? <div style={{ width: '100%' }}>
      <canvas ref={canvas} width={frame.width} height={frame.height} tabIndex={0} aria-label="官方登录浏览器画面"
        aria-describedby="official-browser-input-help" style={{ width: '100%', maxWidth: 1024, display: 'block', border: '1px solid #bfbfbf', borderRadius: 6 }}
        onClick={(event) => { event.currentTarget.focus(); const position = point(event); if (position) enqueue({ type: 'CLICK', ...position }); }}
        onWheel={(event) => { event.preventDefault(); const position = point(event); const deltaY = Math.max(-1000, Math.min(1000, Math.round(event.deltaY))); if (position && deltaY) enqueue({ type: 'WHEEL', ...position, deltaY }); }}
        onKeyDown={key} onPaste={paste}>请使用支持 Canvas 的浏览器查看官方登录页面。</canvas>
      <p id="official-browser-input-help">点击画面中的输入框后可键入或粘贴。中文输入可使用下方输入框；文字发送后立即清空。Tab / Shift+Tab 切换官方页面焦点，Ctrl+A 全选，Esc 退出画面键盘输入。</p>
      <input ref={textInput} type="password" aria-label="向官方页面输入文字" autoComplete="off" maxLength={2048}
        placeholder="中文输入或粘贴到已选中的官方输入框" style={{ width: '100%', padding: 8 }} onPaste={paste}
        onInput={(event) => { if (!(event.nativeEvent as InputEvent).isComposing) { const value = event.currentTarget.value; event.currentTarget.value = ''; if (value) text(value); } }}
        onCompositionEnd={(event) => { const value = event.currentTarget.value; event.currentTarget.value = ''; if (value) text(value); }} />
      {frame.readyToSave ? <Alert type="info" showIcon title="已发现登录候选，尚未连接或保存。请确认登录完成后点击“完成登录并保存”，后台将预检官方账户与会话。" /> : null}
    </div> : null}
    <Space wrap>
      <Button type="primary" disabled={disabled || working !== null || active} loading={working === 'start'} onClick={() => void start()}>{visibleJob ? '重新开始官方网页登录' : '开始官方网页登录'}</Button>
      {active ? <><Button disabled={!frame || paused || working !== null || current.current.saveAttempted} onClick={() => enqueue({ type: 'OPEN_SESSIONS' })}>打开官方会话页</Button>
        <Button type="primary" disabled={!frame?.readyToSave || paused || working !== null || current.current.saveAttempted} loading={working === 'complete'} onClick={() => void complete()}>完成登录并保存</Button>
        <Button disabled={working !== null || visibleJob?.state === 'SAVING'} loading={working === 'cancel'} onClick={() => void cancel()}>取消登录任务</Button></> : null}
      {paused && active ? <Button disabled={working !== null} onClick={() => { current.current.paused = false; setPaused(false); setError(''); setRevision((value) => value + 1); }}>重新检查任务</Button> : null}
    </Space>
  </Space></Card>;
}
