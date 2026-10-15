import { StrictMode } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import { LoginAssistance } from "./login-assistance";
import { SessionBinding } from "./session-binding";

const handlers = vi.hoisted(() => new Set<(event: {type: string}) => void>());
vi.mock("@/lib/assistance-events", () => ({
  subscribeToAssistanceEvents: vi.fn((_user, handler) => {handlers.add(handler);return () => handlers.delete(handler);}),
}));
vi.mock("@/lib/api", async (original) => ({...await original<typeof import("@/lib/api")>(), api: {
  loginAssistanceInfo: vi.fn(), loginAssistanceCurrent: vi.fn(), loginAssistanceStart: vi.fn(), loginAssistanceCancel: vi.fn(), loginAssistanceStatus: vi.fn(),
  sessionBindingCurrent: vi.fn(), sessionBindingStart: vi.fn(), sessionBindingAction: vi.fn(),
}}));
const mail = {id:"11111111-1111-4111-8111-111111111111",state:"LISTENING",startedAt:"2026-10-15T10:00:00Z",expiresAt:"2026-10-15T10:02:00Z",listeningAt:"2026-10-15T10:00:00Z",receivedAt:null,code:null,errorCode:null} as const;
const queued = {id:mail.id,deviceId:mail.id,enrollmentId:mail.id,officialAccountId:mail.id,status:"QUEUED",queuePosition:1,loginAllowed:false,candidateSessionId:null,candidateDisplayName:null,candidatePlatform:null,expiresAt:"2026-10-15T11:00:00Z",errorCode:null,quarantined:false,binding:null,queueState:"PREPARING",retryAt:null} as const;
async function signal() {await act(async () => {handlers.forEach(handler => handler({type:"state_changed"}));await vi.advanceTimersByTimeAsync(1);});}
beforeEach(() => {
  vi.useFakeTimers({shouldAdvanceTime:true});vi.setSystemTime(new Date("2026-10-15T10:00:00Z"));vi.clearAllMocks();handlers.clear();
  vi.mocked(api.loginAssistanceInfo).mockResolvedValue({available:true,reasonCode:null,officialAccountId:"official",accountLabel:"团队",recipientEmail:"team@example.com"});
  vi.mocked(api.loginAssistanceCurrent).mockResolvedValue({listener:null});
  vi.mocked(api.sessionBindingCurrent).mockResolvedValue({attempt:null,binding:null});
});
afterEach(() => {cleanup();vi.useRealTimers();});
it("restores current listening and a fresh received code without starting or cancelling mail on unmount", async () => {
  vi.mocked(api.loginAssistanceCurrent).mockResolvedValue({listener:mail});
  const first=render(<LoginAssistance userId="alice"/>);
  await screen.findByText("正在监听");first.unmount();expect(api.loginAssistanceCancel).not.toHaveBeenCalled();
  vi.mocked(api.loginAssistanceCurrent).mockResolvedValue({listener:{...mail,state:"RECEIVED",receivedAt:mail.startedAt,code:"123456"}});
  render(<LoginAssistance userId="alice"/>);await screen.findByLabelText("本次登录验证码");
  expect(api.loginAssistanceStart).not.toHaveBeenCalled();expect(localStorage.length).toBe(0);
});
it("StrictMode ignores late old-owner mail start without cancelling a background listener", async () => {
  let finish!: (value: typeof mail) => void;
  vi.mocked(api.loginAssistanceStart).mockImplementation(() => new Promise(resolve => {finish=resolve;}));
  const view=render(<StrictMode><LoginAssistance userId="alice"/></StrictMode>);
  fireEvent.click(await screen.findByRole("button",{name:"监听邮件"}));
  view.rerender(<StrictMode><LoginAssistance userId="bob"/></StrictMode>);
  await act(async () => finish(mail));
  expect(api.loginAssistanceCancel).not.toHaveBeenCalled();expect(screen.queryByText("正在监听")).toBeNull();
});
it("uses bounded invalidations to re-read current and has no periodic mail status polling", async () => {
  vi.mocked(api.loginAssistanceCurrent).mockResolvedValue({listener:mail});render(<LoginAssistance userId="alice"/>);await screen.findByText("正在监听");
  const before=vi.mocked(api.loginAssistanceCurrent).mock.calls.length;
  await act(async () => {for(let i=0;i<20;i++) handlers.forEach(handler => handler({type:"state_changed"}));await vi.advanceTimersByTimeAsync(1);});
  expect(api.loginAssistanceCurrent).toHaveBeenCalledTimes(before+1);
  await act(async () => {await vi.advanceTimersByTimeAsync(10_000);});expect(api.loginAssistanceStatus).not.toHaveBeenCalled();
});
it("server advances queue without renderer prepare polling and the countdown follows the supplied deadline", async () => {
  vi.mocked(api.sessionBindingCurrent).mockResolvedValue({attempt:queued,binding:null});render(<SessionBinding userId="alice"/>);await screen.findByText(/正在排队/);
  expect(screen.getByLabelText("绑定剩余时间")).toHaveTextContent("60:00");
  await act(async () => {await vi.advanceTimersByTimeAsync(2_000);});expect(api.sessionBindingAction).not.toHaveBeenCalled();
  vi.mocked(api.sessionBindingCurrent).mockResolvedValue({attempt:{...queued,status:"READY_FOR_LOGIN",loginAllowed:true,queueState:"ACTIVE",expiresAt:"2026-10-15T10:30:02Z"},binding:null});await signal();
  await screen.findByRole("button",{name:"已完成登录，识别本设备"});expect(screen.getByLabelText("绑定剩余时间")).toHaveTextContent("30:00");
});
it("does not fabricate server EXPIRED or enable retry when the local countdown ends", async () => {
  const ready={...queued,status:"READY_FOR_LOGIN" as const,loginAllowed:true,queueState:"ACTIVE" as const,expiresAt:"2026-10-15T10:00:01Z"};
  vi.mocked(api.sessionBindingCurrent).mockResolvedValueOnce({attempt:ready,binding:null}).mockRejectedValue(new Error("offline"));
  render(<SessionBinding userId="alice"/>);await screen.findByRole("button",{name:"已完成登录，识别本设备"});
  await act(async () => {await vi.advanceTimersByTimeAsync(1_100);});await screen.findByText(/正在核对服务器到期状态/);
  expect(screen.getByRole("button",{name:"已完成登录，识别本设备"})).toBeDisabled();expect(screen.queryByRole("button",{name:"重新申请绑定"})).toBeNull();
  expect(screen.queryByText(/本次绑定已超时/)).toBeNull();expect(api.sessionBindingCurrent).toHaveBeenCalledTimes(2);
});
it("shows automatic settling and permits explicit new queue request without pretending to unlock a guard", async () => {
  vi.mocked(api.sessionBindingCurrent).mockResolvedValue({attempt:{...queued,status:"CANCELLED",quarantined:true,queueState:"SETTLING",retryAt:"2026-10-15T10:00:30Z"},binding:null});
  vi.mocked(api.sessionBindingStart).mockResolvedValue({...queued,quarantined:true,queueState:"SETTLING"});
  render(<SessionBinding userId="alice"/>);await screen.findByText(/后台正在自动整理/);
  expect(screen.queryByText(/等待管理员恢复绑定队列/)).toBeNull();fireEvent.click(screen.getByRole("button",{name:"重新申请绑定"}));
  await waitFor(() => expect(api.sessionBindingStart).toHaveBeenCalledOnce());expect(api.sessionBindingAction).not.toHaveBeenCalled();
});
