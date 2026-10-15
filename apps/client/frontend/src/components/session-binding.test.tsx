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
const handlers = vi.hoisted(() => new Set<(event: { type: string }) => void>());
vi.mock("@/lib/assistance-events", () => ({
  subscribeToAssistanceEvents: (
    _user: string,
    handler: (event: { type: string }) => void,
  ) => {
    handlers.add(handler);
    return () => handlers.delete(handler);
  },
}));
async function changed() {
  await act(async () => {
    handlers.forEach((handler) => handler({ type: "state_changed" }));
    await vi.advanceTimersByTimeAsync(1);
  });
}
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
    queueState: "ACTIVE",
    retryAt: null,
    ...extras,
  };
}
beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date("2026-10-15T10:00:00Z"));
  native.mockReset();
  handlers.clear();
  native.mockImplementation(async (name) => {
    if (name === "session_binding_current")
      return { attempt: null, binding: null };
    if (name === "session_binding_start") return attempt();
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
  it("推送后读取后台准备结果，不会替用户发起官方登录或识别", async () => {
    let ready = false;
    native.mockImplementation(async (name) =>
      name === "session_binding_current"
        ? {
            attempt: ready
              ? attempt()
              : attempt("QUEUED", { queuePosition: 2 }),
            binding: null,
          }
        : attempt(),
    );
    render(<SessionBinding userId="user-a" />);
    await screen.findByText(/正在排队/);
    ready = true;
    await changed();
    await screen.findByRole("button", { name: "已完成登录，识别本设备" });
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_detect"),
    ).toBe(false);
  });
  it("产品登录过期时立即清除已识别候选", async () => {
    let expired = false;
    native.mockImplementation(async (name) => {
      if (name === "session_binding_current" && !expired)
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
    expired = true;
    await changed();
    await screen.findByText("请先登录系统账户。");
    expect(screen.queryByText("过期前候选")).toBeNull();
    expect(screen.queryByRole("button", { name: "确认绑定此会话" })).toBeNull();
  });
  it("首次显示可见引导，排他准备完成后才允许用户识别", async () => {
    await begin();
    expect(screen.getByText(/官方 Codex 应用/)).toBeVisible();
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_prepare"),
    ).toBe(false);
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
    expect(screen.queryByText("等待管理员恢复绑定队列")).toBeNull();
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
  it.each(["READY_FOR_LOGIN", "DETECTING", "AWAITING_CONFIRMATION"])(
    "取得登录名额后在取消前明确告知自动整理与迟到会话风险：%s",
    async (status) => {
      native.mockImplementation(async (name) =>
        name === "session_binding_current"
          ? { attempt: attempt(status), binding: null }
          : attempt("CANCELLED", { quarantined: true }),
      );
      render(<SessionBinding userId="user-a" />);
      await screen.findByText(/取消后后台会自动整理队列.*迟到会话/);
      expect(
        screen.getByRole("button", { name: "取消本次绑定" }),
      ).toBeEnabled();
      expect(
        native.mock.calls.some(([name]) => name === "session_binding_cancel"),
      ).toBe(false);
    },
  );
  it("取消后未受保护的终态提供显式重新申请入口", async () => {
    const original = native.getMockImplementation()!;
    native.mockImplementation(async (name, args) =>
      name === "session_binding_current"
        ? { attempt: attempt("CANCELLED"), binding: null }
        : original(name, args),
    );
    render(<SessionBinding userId="user-a" />);
    expect(await screen.findByText(/可以重新申请排队/)).toBeVisible();
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_start"),
    ).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "重新申请绑定" }));
    await screen.findByRole("button", { name: "已完成登录，识别本设备" });
    expect(
      native.mock.calls.filter(([name]) => name === "session_binding_start"),
    ).toHaveLength(1);
  });
  it("取消后显示自动整理，重新检查不会自动重建或绕过旧任务", async () => {
    let quarantined = true;
    native.mockImplementation(async (name) =>
      name === "session_binding_current"
        ? {
            attempt: attempt("CANCELLED", {
              quarantined,
              queueState: quarantined ? "SETTLING" : "IDLE",
            }),
            binding: null,
          }
        : attempt(),
    );
    render(<SessionBinding userId="user-a" />);
    await screen.findByText("正在整理绑定队列");
    expect(screen.getByRole("button", { name: "重新申请绑定" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "重新读取绑定状态" }));
    await screen.findByText("正在整理绑定队列");
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_start"),
    ).toBe(false);
    quarantined = false;
    fireEvent.click(screen.getByRole("button", { name: "重新读取绑定状态" }));
    await screen.findByRole("button", { name: "重新申请绑定" });
    expect(screen.queryByText("等待管理员恢复绑定队列")).toBeNull();
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_start"),
    ).toBe(false);
  });
  it("申请失败后保留显式重试入口，只有新后台响应才能推进状态", async () => {
    let starts = 0;
    native.mockImplementation(async (name) => {
      if (name === "session_binding_current")
        return { attempt: null, binding: null };
      if (name === "session_binding_start" && starts++ === 0)
        throw new Error("synthetic unavailable");
      return attempt();
    });
    render(<SessionBinding userId="user-a" />);
    fireEvent.click(
      await screen.findByRole("button", { name: "开始绑定本设备" }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "重试申请绑定" }),
    );
    await screen.findByRole("button", { name: "已完成登录，识别本设备" });
    expect(starts).toBe(2);
  });
  it("重新检查失败保留已知终态，但不会自动重建任务", async () => {
    let reads = 0;
    native.mockImplementation(async () => {
      if (reads++ === 0)
        return {
          attempt: attempt("CANCELLED", { quarantined: true }),
          binding: null,
        };
      throw new Error("synthetic unavailable");
    });
    render(<SessionBinding userId="user-a" />);
    fireEvent.click(
      await screen.findByRole("button", { name: "重新读取绑定状态" }),
    );
    await screen.findByText("暂时无法连接本地服务，请确认应用仍在运行。");
    expect(
      native.mock.calls.some(([name]) => name === "session_binding_start"),
    ).toBe(false);
    expect(
      screen.getByRole("button", { name: "重新读取绑定状态" }),
    ).toBeEnabled();
  });
  it("设备登记不可用时指出先配对统计程序再重新读取状态", async () => {
    native.mockRejectedValue(
      "本设备绑定信息不可用，请检查统计程序设备登记或联系管理员。",
    );
    render(<SessionBinding userId="user-a" />);
    await screen.findByText(/先.*配对统计程序.*重新读取绑定状态/);
    expect(
      screen.getByRole("button", { name: "重新读取绑定状态" }),
    ).toBeEnabled();
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
    await waitFor(() =>
      expect(native).toHaveBeenCalledWith("session_binding_current"),
    );
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
  it("本地倒计时结束禁用确认，等待服务器核对而非伪造过期", async () => {
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
        screen.getByRole("button", { name: "确认绑定此会话" }),
      ).toBeDisabled(),
    );
    expect(screen.getByText(/正在核对服务器到期状态/)).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /重新申请绑定|开始绑定本设备/ }),
    ).toBeNull();
  });
});
