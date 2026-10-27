import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import BrowserLoginPanel from './BrowserLoginPanel';

const mocks = vi.hoisted(() => ({ startOfficialWebLogin: vi.fn(), getOfficialWebLogin: vi.fn(),
  getOfficialWebLoginFrame: vi.fn(), sendOfficialWebLoginInput: vi.fn(),
  completeOfficialWebLogin: vi.fn(), cancelOfficialWebLogin: vi.fn() }));
vi.mock('../../services/officialWebLoginApi', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../services/officialWebLoginApi')>(), ...mocks,
}));
vi.mock('antd', () => {
  const Block = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
  return { Card: Block, Space: Block, Tag: Block, Typography: { Paragraph: Block, Text: Block },
    Alert: ({ title, description }: { title: ReactNode; description?: ReactNode }) => <div role="alert">{title}{description}</div>,
    Descriptions: ({ items }: { items: { key: string; label: string; children: ReactNode }[] }) => <dl>{items.map((item) => <div key={item.key}>{item.label}{item.children}</div>)}</dl>,
    Button: ({ children, onClick, disabled }: { children: ReactNode; onClick?: () => void; disabled?: boolean }) => <button onClick={onClick} disabled={disabled}>{children}</button> };
});

const job = { id: 'job-1', officialAccountId: 'account-1', accountName: '研发账户', state: 'WAITING_FOR_LOGIN',
  expiresAt: '2099-10-15T02:00:00Z', errorCode: null, proxyNodeId: 'proxy-1', pageOrigin: 'https://auth.openai.com', readyToSave: false };
const frame = { width: 1024, height: 768, jpegBase64: '/9j/AA==', pageOrigin: 'https://auth.openai.com', readyToSave: false, errorCode: null };
function show(accountId = 'account-1') {
  const onResultChanged = vi.fn().mockResolvedValue(undefined);
  const view = render(<BrowserLoginPanel accountId={accountId} version={4} proxyNodeId="proxy-1" disabled={false} onResultChanged={onResultChanged} />);
  return { onResultChanged, ...view };
}
async function start() {
  fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' }));
  await waitFor(() => expect(mocks.getOfficialWebLoginFrame).toHaveBeenCalledWith('job-1'));
}
describe('服务器官方网页登录', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.startOfficialWebLogin.mockResolvedValue(job); mocks.getOfficialWebLogin.mockResolvedValue(job);
    mocks.getOfficialWebLoginFrame.mockResolvedValue(frame); mocks.sendOfficialWebLoginInput.mockResolvedValue(undefined);
    mocks.completeOfficialWebLogin.mockResolvedValue({ ...job, state: 'COMPLETED', readyToSave: false });
    mocks.cancelOfficialWebLogin.mockResolvedValue({ ...job, state: 'CANCELLED', readyToSave: false });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ clearRect: vi.fn(), drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
  });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
  it('uses a server browser and waits for explicit save even when a candidate exists', async () => {
    mocks.getOfficialWebLoginFrame.mockResolvedValue({ ...frame, readyToSave: true });
    const { onResultChanged } = show(); await start();
    expect(mocks.startOfficialWebLogin).toHaveBeenCalledWith('account-1', { version: 4, proxyNodeId: 'proxy-1' });
    expect(screen.getByText(/登录在服务器的独立浏览器中进行/)).toBeInTheDocument();
    expect(screen.queryByText(/设置 → 官方登录助手/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('官方登录浏览器画面')).toBeInTheDocument();
    expect(onResultChanged).not.toHaveBeenCalled(); expect(mocks.completeOfficialWebLogin).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '完成登录并保存' }));
    await waitFor(() => expect(mocks.completeOfficialWebLogin).toHaveBeenCalledOnce());
    expect(onResultChanged).toHaveBeenCalledOnce();
    expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument();
  });
  it('keeps the front and exit nodes when starting a login that will submit an OTP', async () => {
    render(<BrowserLoginPanel accountId="account-1" version={4} proxyNodeId="proxy-1" frontProxyNodeId="front-1"
      disabled={false} onResultChanged={vi.fn().mockResolvedValue(undefined)} />);
    await start();
    expect(mocks.startOfficialWebLogin).toHaveBeenCalledWith('account-1', { version:4, proxyNodeId:'proxy-1', frontProxyNodeId:'front-1' });
    fireEvent.paste(screen.getByLabelText('官方登录浏览器画面'), { clipboardData: { getData: () => '123456' } });
    await waitFor(() => expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledWith('job-1', {type:'TEXT',text:'123456'}));
  });
  it('sends scaled click, bounded wheel, keyboard and Chinese paste without a retained input value', async () => {
    show(); await start(); const canvas = screen.getByLabelText('官方登录浏览器画面');
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 512, height: 384 } as DOMRect);
    fireEvent.click(canvas, { clientX: 256, clientY: 192 });
    fireEvent.wheel(canvas, { clientX: 128, clientY: 96, deltaY: 1500 });
    fireEvent.keyDown(canvas, { key: 'Tab', shiftKey: true });
    fireEvent.paste(canvas, { clipboardData: { getData: () => '中文邮箱' } });
    await waitFor(() => expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledTimes(4));
    expect(mocks.sendOfficialWebLoginInput.mock.calls.map((call) => call[1])).toEqual([
      { type: 'CLICK', x: 512, y: 384 }, { type: 'WHEEL', x: 256, y: 192, deltaY: 1000 },
      { type: 'KEY', key: 'Tab', shift: true }, { type: 'TEXT', text: '中文邮箱' },
    ]);
    expect(screen.queryByDisplayValue('中文邮箱')).not.toBeInTheDocument(); expect(sessionStorage.length).toBe(0);
  });
  it('clears the image and cancels its own job when unmounted, including a late start response', async () => {
    const first = show(); await start(); first.unmount();
    await waitFor(() => expect(mocks.cancelOfficialWebLogin).toHaveBeenCalledWith('job-1'));
    let resolve!: (value: typeof job) => void;
    mocks.startOfficialWebLogin.mockReturnValue(new Promise((done) => { resolve = done; }));
    const second = show(); fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' })); second.unmount();
    await act(async () => { resolve({ ...job, id: 'late-job' }); });
    await waitFor(() => expect(mocks.cancelOfficialWebLogin).toHaveBeenCalledWith('late-job'));
  });
  it('does not replay ambiguous completion failures and clears the frame', async () => {
    mocks.getOfficialWebLoginFrame.mockResolvedValue({ ...frame, readyToSave: true });
    mocks.completeOfficialWebLogin.mockRejectedValue({ code: 'fixture-sensitive' });
    show(); await start(); fireEvent.click(screen.getByRole('button', { name: '完成登录并保存' }));
    await waitFor(() => expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument());
    expect(mocks.completeOfficialWebLogin).toHaveBeenCalledOnce(); expect(screen.queryByText('fixture-sensitive')).not.toBeInTheDocument();
  });
  it('stops at local expiry without showing stale pixels', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mocks.startOfficialWebLogin.mockResolvedValue({ ...job, expiresAt: new Date(Date.now() + 1200).toISOString() });
    show(); await start(); await act(async () => { await vi.advanceTimersByTimeAsync(1500); });
    expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument();
    expect(mocks.cancelOfficialWebLogin).toHaveBeenCalledWith('job-1');
  });
  it('opens the fixed official session page only after an explicit click', async () => {
    show(); await start(); expect(mocks.sendOfficialWebLoginInput).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '打开官方会话页' }));
    await waitFor(() => expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledExactlyOnceWith('job-1', { type: 'OPEN_SESSIONS' }));
  });
  it('bounds input queue and cancels queued text on account switch', async () => {
    let resolve!: () => void; mocks.sendOfficialWebLoginInput.mockReturnValue(new Promise<void>((done) => { resolve = done; }));
    const view = show(); await start(); const canvas = screen.getByLabelText('官方登录浏览器画面');
    for (let i = 0; i < 25; i += 1) fireEvent.paste(canvas, { clipboardData: { getData: () => '临时输入' } });
    expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledOnce(); expect(screen.getByText(/输入过快/)).toBeInTheDocument();
    view.rerender(<BrowserLoginPanel accountId="account-2" version={1} proxyNodeId={null} disabled={false} onResultChanged={vi.fn()} />);
    await act(async () => { resolve(); });
    expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledOnce();
    expect(mocks.cancelOfficialWebLogin).toHaveBeenCalledWith('job-1');
    expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument();
  });
  it('clears a failed input and does not automatically replay it', async () => {
    mocks.sendOfficialWebLoginInput.mockRejectedValue({ status: 503, message: 'fixture-secret' });
    show(); await start(); fireEvent.paste(screen.getByLabelText('官方登录浏览器画面'), { clipboardData: { getData: () => '临时密码' } });
    await waitFor(() => expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument());
    expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledOnce(); expect(screen.queryByText('fixture-secret')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新检查任务' })).toBeInTheDocument();
  });
  it('keeps a changed session visible for explicit recapture and never offers to save it', async () => {
    mocks.getOfficialWebLoginFrame.mockResolvedValue({ ...frame, readyToSave: true, errorCode: 'BROWSER_LOGIN_SESSION_CHANGED' });
    show(); await start(); expect(screen.getByLabelText('官方登录浏览器画面')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '完成登录并保存' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '打开官方会话页' }));
    await waitFor(() => expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledWith('job-1', { type: 'OPEN_SESSIONS' }));
  });
  it('clears pixels and cancels on page close, without retaining the pending task', async () => {
    show(); await start(); fireEvent(window, new Event('pagehide'));
    expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument();
    expect(mocks.cancelOfficialWebLogin).toHaveBeenCalledWith('job-1');
  });
  it('never confirms cancellation when pagehide interrupts saving and verifies the BFCache result explicitly', async () => {
    mocks.getOfficialWebLoginFrame.mockResolvedValue({ ...frame, readyToSave: true });
    let finish!: (value: typeof job) => void;
    mocks.completeOfficialWebLogin.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    mocks.cancelOfficialWebLogin.mockRejectedValue({ code: 'BROWSER_LOGIN_SAVING', status: 409 });
    const { onResultChanged } = show(); await start(); const frameReads = mocks.getOfficialWebLoginFrame.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '完成登录并保存' }));
    fireEvent(window, new Event('pagehide'));
    expect(screen.queryByText('登录任务已取消')).not.toBeInTheDocument();
    expect(screen.getByText('登录任务结果待核对')).toBeInTheDocument();
    expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument();
    mocks.getOfficialWebLogin.mockResolvedValue({ ...job, state: 'COMPLETED', readyToSave: false });
    fireEvent(window, new PageTransitionEvent('pageshow', { persisted: true }));
    fireEvent.click(screen.getByRole('button', { name: '重新检查任务' }));
    expect(await screen.findByText('登录完成，授权已加密保存')).toBeInTheDocument();
    await act(async () => { finish({ ...job, state: 'FAILED' }); });
    expect(onResultChanged).toHaveBeenCalledOnce();
    expect(screen.getByText('登录完成，授权已加密保存')).toBeInTheDocument();
    expect(mocks.completeOfficialWebLogin).toHaveBeenCalledOnce();
    expect(mocks.sendOfficialWebLoginInput).not.toHaveBeenCalled();
    expect(mocks.getOfficialWebLoginFrame).toHaveBeenCalledTimes(frameReads);
  });
  it('checks a still-saving BFCache job without restoring frames, replaying writes, or locally expiring an unknown outcome', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const expiresAt = new Date(Date.now() + 1200).toISOString();
    mocks.startOfficialWebLogin.mockResolvedValue({ ...job, expiresAt });
    mocks.getOfficialWebLogin.mockResolvedValue({ ...job, expiresAt });
    mocks.getOfficialWebLoginFrame.mockResolvedValue({ ...frame, readyToSave: true });
    mocks.completeOfficialWebLogin.mockReturnValue(new Promise(() => undefined));
    mocks.cancelOfficialWebLogin.mockRejectedValue({ code: 'BROWSER_LOGIN_SAVING', status: 409 });
    show(); await start(); const frameReads = mocks.getOfficialWebLoginFrame.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '完成登录并保存' }));
    fireEvent(window, new Event('pagehide'));
    await act(async () => { await vi.advanceTimersByTimeAsync(1500); });
    expect(screen.queryByText('登录任务已过期')).not.toBeInTheDocument();
    mocks.getOfficialWebLogin.mockResolvedValue({ ...job, state: 'SAVING', expiresAt: '2099-10-15T02:00:00Z' });
    fireEvent(window, new PageTransitionEvent('pageshow', { persisted: true }));
    fireEvent.click(screen.getByRole('button', { name: '重新检查任务' }));
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText('正在预检并加密保存授权')).toBeInTheDocument();
    expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument();
    expect(mocks.completeOfficialWebLogin).toHaveBeenCalledOnce();
    expect(mocks.sendOfficialWebLoginInput).not.toHaveBeenCalled();
    expect(mocks.getOfficialWebLoginFrame).toHaveBeenCalledTimes(frameReads);
  });
  it('cancels explicitly and permits a new task without reusing the old job id', async () => {
    show(); await start(); fireEvent.click(screen.getByRole('button', { name: '取消登录任务' }));
    expect(await screen.findByText('登录任务已取消')).toBeInTheDocument();
    mocks.startOfficialWebLogin.mockResolvedValue({ ...job, id: 'job-2' });
    mocks.getOfficialWebLogin.mockResolvedValue({ ...job, id: 'job-2' });
    fireEvent.click(screen.getByRole('button', { name: '重新开始官方网页登录' }));
    await waitFor(() => expect(mocks.getOfficialWebLoginFrame).toHaveBeenCalledWith('job-2'));
  });
  it('hides a frame read failure and resumes only after an explicit status check', async () => {
    mocks.getOfficialWebLoginFrame.mockRejectedValueOnce({ status: 503, message: 'fixture-private' });
    show(); await start(); expect(screen.queryByLabelText('官方登录浏览器画面')).not.toBeInTheDocument();
    expect(screen.queryByText('fixture-private')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重新检查任务' }));
    expect(await screen.findByLabelText('官方登录浏览器画面')).toBeInTheDocument();
  });
  it('supports transient Chinese composition and ignores unsupported modifier shortcuts', async () => {
    show(); await start(); const input = screen.getByLabelText('向官方页面输入文字') as HTMLInputElement;
    fireEvent.input(input, { target: { value: '中文临时' }, isComposing: true });
    expect(mocks.sendOfficialWebLoginInput).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input); expect(input.value).toBe('');
    fireEvent.input(input, { target: { value: 'x' }, isComposing: false });
    const canvas = screen.getByLabelText('官方登录浏览器画面');
    fireEvent.keyDown(canvas, { key: 'a', ctrlKey: true });
    fireEvent.keyDown(canvas, { key: 'Enter', altKey: true });
    fireEvent.keyDown(canvas, { key: 'F10' });
    fireEvent.keyDown(canvas, { key: 'Escape' });
    await waitFor(() => expect(mocks.sendOfficialWebLoginInput).toHaveBeenCalledTimes(3));
    expect(mocks.sendOfficialWebLoginInput.mock.calls.map((call) => call[1])).toEqual([
      { type: 'TEXT', text: '中文临时' }, { type: 'TEXT', text: 'x' }, { type: 'KEY', key: 'a', ctrl: true },
    ]);
  });
  it('draws valid image frames and clears pixels when leaving', async () => {
    vi.stubGlobal('Image', class {
      onload: (() => void) | null = null; onerror: (() => void) | null = null;
      set src(value: string) { if (value) queueMicrotask(() => this.onload?.()); }
    });
    const context = HTMLCanvasElement.prototype.getContext as ReturnType<typeof vi.fn>;
    const view = show(); await start();
    await waitFor(() => expect(context.mock.results.some((value) => value.value?.drawImage.mock.calls.length)).toBe(true));
    view.unmount();
    expect(context.mock.results.some((value) => value.value?.clearRect.mock.calls.length)).toBe(true);
  });
  it('shows a safe start failure and does not create a task while disabled', async () => {
    mocks.startOfficialWebLogin.mockRejectedValue({ message: 'fixture-private' });
    const view = show(); fireEvent.click(screen.getByRole('button', { name: '开始官方网页登录' }));
    expect(await screen.findByText(/无法创建服务器网页登录任务/)).toBeInTheDocument();
    expect(screen.queryByText('fixture-private')).not.toBeInTheDocument();
    view.rerender(<BrowserLoginPanel accountId="account-1" version={4} proxyNodeId={null} disabled onResultChanged={vi.fn()} />);
    expect(screen.getByRole('button', { name: '开始官方网页登录' })).toBeDisabled();
  });
});
