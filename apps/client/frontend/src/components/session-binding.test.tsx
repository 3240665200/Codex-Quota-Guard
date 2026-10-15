import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { SessionBinding } from "./session-binding";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const native = vi.mocked(invoke);
const id = "11111111-1111-4111-8111-111111111111";
const deviceId = "22222222-2222-4222-8222-222222222222";
const binding = {
  id,
  userId: id,
  userDisplayName: "Alice",
  userEmail: "alice@example.com",
  deviceId,
  enrollmentId: deviceId,
  officialAccountId: id,
  sessionId: "new-official-session",
  status: "ACTIVE",
  matchMethod: "SERIAL_LOGIN_DIFF",
  verificationLevel: "USER_CONFIRMED_INFERENCE",
  createdAt: "2026-10-15T10:00:00Z",
  endedAt: null,
};
function attempt(status = "READY_FOR_LOGIN", extras = {}) {
  return {
    id,
    deviceId,
    enrollmentId: deviceId,
    officialAccountId: id,
    status,
    queuePosition: 0,
    loginAllowed: status === "READY_FOR_LOGIN",
    candidateSessionId: null,
    candidateDisplayName: null,
    candidatePlatform: null,
    expiresAt: "2026-10-15T10:10:00Z",
    errorCode: null,
    quarantined: false,
    binding: null,
    ...extras,
  };
}
beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date("2026-10-15T10:00:00Z"));
  native.mockReset();
  native.mockImplementation(async (name) => {
    if (name === "session_binding_current")
      return { attempt: null, binding: null };
    if (name === "session_binding_start")
      return attempt("QUEUED", { queuePosition: 1 });
    if (name === "session_binding_prepare") return attempt();
    if (name === "session_binding_cancel") return attempt("CANCELLED");
    return attempt();
  });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});
async function begin() {
  render(<SessionBinding userId="user-a" />);
  fireEvent.click(
    await screen.findByRole("button", { name: "开始绑定本设备" }),
  );
  await screen.findByRole("button", { name: "已完成登录，识别本设备" });
}
describe("本设备官方会话绑定", () => {
  it("用户确认后才显示已保存，重新打开从中央恢复同一绑定", async () => {
    let saved = false;
    native.mockImplementation(async (name) => {
      if (name === "session_binding_current")
        return { attempt: null, binding: saved ? binding : null };
      if (name === "session_binding_detect")
        return attempt("AWAITING_CONFIRMATION", {
          candidateSessionId: binding.sessionId,
          candidateDisplayName: "本次官方登录",
          candidatePlatform: "Windows",
        });
      if (name === "session_binding_confirm") {
        saved = true;
        return attempt("BOUND", { binding });
      }
      return attempt();
    });
    const first = render(<SessionBinding userId="user-a" />);
    fireEvent.click(
      await screen.findByRole("button", { name: "开始绑定本设备" }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "已完成登录，识别本设备" }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "确认绑定此会话" }),
    );
    await screen.findByText("本设备官方会话已绑定。");
    expect(
      native.mock.calls.find(
        ([name]) => name === "session_binding_confirm",
      )?.[1],
    ).toEqual({ attemptId: id });
    first.unmount();
    render(<SessionBinding userId="user-a" />);
    await screen.findByText("本设备官方会话已绑定。");
    expect(screen.queryByRole("button", { name: "开始绑定本设备" })).toBeNull();
  });
  it("轮到排队用户后自动准备，但不会替用户发起官方登录或识别", async () => {
    native.mockImplementation(async (name) =>
      name === "session_binding_current"
        ? { attempt: attempt("QUEUED", { queuePosition: 2 }), binding: null }
        : attempt(),
    );
    render(<SessionBinding userId="user-a" />);
    await screen.findByText(/正在排队/);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await screen.findByRole("button", { name: "已完成登录，识别本设备" });
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_detect"),
    ).toBe(false);
  });
  it("产品登录过期时立即清除已识别候选", async () => {
    native.mockImplementation(async (name) => {
      if (name === "session_binding_current")
        return {
          attempt: attempt("AWAITING_CONFIRMATION", {
            candidateSessionId: "candidate",
            candidateDisplayName: "过期前候选",
          }),
          binding: null,
        };
      throw "请先登录系统账户。";
    });
    render(<SessionBinding userId="user-a" />);
    await screen.findByText("过期前候选");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await screen.findByText("请先登录系统账户。");
    expect(screen.queryByText("过期前候选")).toBeNull();
    expect(screen.queryByRole("button", { name: "确认绑定此会话" })).toBeNull();
  });
  it("首次显示可见引导，排他准备完成后才允许用户识别", async () => {
    await begin();
    expect(screen.getByText(/官方 Codex 应用/)).toBeVisible();
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_prepare"),
    ).toBe(true);
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_detect"),
    ).toBe(false);
    expect(
      native.mock.calls.find(([name]) => name === "session_binding_start")?.[1],
    ).toBeUndefined();
  });
  it("排队期间不显示可操作的登录识别按钮", async () => {
    native.mockImplementation(async (name) =>
      name === "session_binding_current"
        ? { attempt: attempt("QUEUED", { queuePosition: 2 }), binding: null }
        : attempt("QUEUED", { queuePosition: 2 }),
    );
    render(<SessionBinding userId="user-a" />);
    await screen.findByText(/正在排队/);
    expect(
      screen.queryByRole("button", { name: "已完成登录，识别本设备" }),
    ).toBeNull();
  });
  it("显示唯一候选并要求确认，不会收到候选就自动绑定", async () => {
    native.mockImplementation(async (name) => {
      if (name === "session_binding_current")
        return { attempt: null, binding: null };
      if (name === "session_binding_detect")
        return attempt("AWAITING_CONFIRMATION", {
          candidateSessionId: "official-session-1",
          candidateDisplayName: "Alice 的电脑",
          candidatePlatform: "Windows",
        });
      return attempt();
    });
    await begin();
    fireEvent.click(
      screen.getByRole("button", { name: "已完成登录，识别本设备" }),
    );
    await screen.findByText("Alice 的电脑");
    expect(
      screen.getByRole("button", { name: "确认绑定此会话" }),
    ).toBeVisible();
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_confirm"),
    ).toBe(false);
    expect(screen.getByText(/不能证明物理设备身份/)).toBeVisible();
  });
  it("无新增会话明确提示不能猜测已有登录并要求管理员处理", async () => {
    native.mockImplementation(async (name) =>
      name === "session_binding_current"
        ? {
            attempt: attempt("REJECTED", {
              errorCode: "SESSION_BINDING_NO_NEW_SESSION",
              quarantined: true,
            }),
            binding: null,
          }
        : attempt(),
    );
    render(<SessionBinding userId="user-a" />);
    await screen.findByText(/没有发现本次新增的官方会话/);
    expect(screen.getByText(/管理员/)).toBeVisible();
  });
  it("取消会释放本次任务并清除候选", async () => {
    await begin();
    fireEvent.click(screen.getByRole("button", { name: "取消本次绑定" }));
    await screen.findByText(/本次绑定已取消/);
    expect(
      native.mock.calls.find(
        ([name]) => name === "session_binding_cancel",
      )?.[1],
    ).toEqual({ attemptId: id });
  });
  it("换用户后忽略旧用户尚未返回的响应", async () => {
    let finish!: (value: unknown) => void;
    native.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = render(<SessionBinding userId="user-a" />);
    view.rerender(<SessionBinding userId="user-b" />);
    await screen.findByRole("button", { name: "开始绑定本设备" });
    await act(async () =>
      finish({
        attempt: attempt("AWAITING_CONFIRMATION", {
          candidateSessionId: "old-session",
          candidateDisplayName: "旧用户电脑",
        }),
        binding: null,
      }),
    );
    expect(screen.queryByText("旧用户电脑")).toBeNull();
  });
  it("离开页面不自动取消已准备的官方登录，重开可从中央恢复", async () => {
    const view = render(<SessionBinding userId="user-a" />);
    await screen.findByRole("button", { name: "开始绑定本设备" });
    view.unmount();
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_cancel"),
    ).toBe(false);
  });
  it("本地到期清除确认入口，即使服务离线也不会无限保留候选", async () => {
    native.mockImplementation(async () => ({
      attempt: attempt("AWAITING_CONFIRMATION", {
        candidateSessionId: "session",
        candidateDisplayName: "本机候选",
        expiresAt: "2026-10-15T10:00:01Z",
      }),
      binding: null,
    }));
    render(<SessionBinding userId="user-a" />);
    await screen.findByText("本机候选");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100);
    });
    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: "确认绑定此会话" }),
      ).toBeNull(),
    );
    expect(screen.getByText(/本次绑定已超时/)).toBeVisible();
  });
});
