import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SessionBinding } from "@/components/session-binding";
import {
  api,
  type AssistanceEvent,
  type SessionBinding as Binding,
  type SessionBindingAttempt,
} from "@/lib/api";

const attemptId = "11111111-1111-4111-8111-111111111111";
const deviceId = "22222222-2222-4222-8222-222222222222";
const officialAccountId = "33333333-3333-4333-8333-333333333333";

const binding: Binding = {
  id: attemptId,
  userId: "44444444-4444-4444-8444-444444444444",
  userDisplayName: "Alice",
  userEmail: "alice@example.com",
  deviceId,
  enrollmentId: deviceId,
  officialAccountId,
  sessionId: "new-official-session",
  status: "ACTIVE",
  matchMethod: "SERIAL_LOGIN_DIFF",
  verificationLevel: "USER_CONFIRMED_INFERENCE",
  createdAt: "2026-10-15T10:00:00Z",
  endedAt: null,
};

function attempt(
  status: SessionBindingAttempt["status"] = "READY_FOR_LOGIN",
  overrides: Partial<SessionBindingAttempt> = {},
): SessionBindingAttempt {
  return {
    id: attemptId,
    deviceId,
    enrollmentId: deviceId,
    officialAccountId,
    status,
    queuePosition: 0,
    loginAllowed: status === "READY_FOR_LOGIN",
    candidateSessionId: null,
    candidateDisplayName: null,
    candidatePlatform: null,
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    errorCode: null,
    quarantined: false,
    binding: null,
    queueState: "ACTIVE",
    retryAt: null,
    ...overrides,
  };
}

interface StreamCall {
  handler: (event: AssistanceEvent) => void;
  signal: AbortSignal;
}

let streams: StreamCall[] = [];

async function emitStateChanged() {
  await act(async () => {
    streams[0]?.handler("state_changed");
    await vi.advanceTimersByTimeAsync(1);
  });
}

function renderBinding(userId = "user-a", onBound = vi.fn()) {
  const view = render(<SessionBinding userId={userId} onBound={onBound} />);
  return { ...view, onBound };
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date("2026-10-15T10:00:00Z"));
  streams = [];
  vi.spyOn(api, "sessionBindingCurrent").mockResolvedValue({
    attempt: null,
    binding: null,
  });
  vi.spyOn(api, "sessionBindingStart").mockResolvedValue(attempt());
  vi.spyOn(api, "sessionBindingAction").mockImplementation(async () =>
    attempt("CANCELLED"),
  );
  vi.spyOn(api, "streamLoginAssistanceEvents").mockImplementation(
    (handler, signal) =>
      new Promise<void>((resolve) => {
        streams.push({ handler, signal });
        if (signal.aborted) resolve();
        else signal.addEventListener("abort", () => resolve(), { once: true });
      }),
  );
});

afterEach(async () => {
  cleanup();
  window.dispatchEvent(new Event("quota-guard:assistance-clear"));
  await act(async () => {
    await Promise.resolve();
    await vi.runOnlyPendingTimersAsync();
  });
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("本设备官方会话绑定", () => {
  it("loads the central queue and starts one explicit enrollment request", async () => {
    const view = renderBinding();
    const start = await screen.findByRole("button", { name: "开始绑定本设备" });
    await waitFor(() => expect(streams).toHaveLength(1));
    fireEvent.click(start);

    expect(await screen.findByText(/后台会自动检测并完成绑定/)).toBeVisible();
    expect(api.sessionBindingCurrent).toHaveBeenCalled();
    expect(api.sessionBindingStart).toHaveBeenCalledOnce();
    expect(api.sessionBindingAction).not.toHaveBeenCalled();

    view.unmount();
    await waitFor(() => expect(streams[0].signal.aborted).toBe(true));
  });

  it("refreshes the queued position from an SSE event without starting another attempt", async () => {
    let current: {
      attempt: SessionBindingAttempt | null;
      binding: Binding | null;
    } = {
      attempt: attempt("QUEUED", { queuePosition: 3, loginAllowed: false }),
      binding: null,
    };
    vi.mocked(api.sessionBindingCurrent).mockImplementation(
      async () => current,
    );
    renderBinding();
    await screen.findByText(/当前排位 3/);
    const reads = vi.mocked(api.sessionBindingCurrent).mock.calls.length;

    current = {
      attempt: attempt("READY_FOR_LOGIN"),
      binding: null,
    };
    await emitStateChanged();

    expect(await screen.findByText(/官方 Codex 登录/)).toBeVisible();
    expect(api.sessionBindingCurrent).toHaveBeenCalledTimes(reads + 1);
    expect(api.sessionBindingStart).not.toHaveBeenCalled();
    expect(api.sessionBindingAction).not.toHaveBeenCalled();
  });

  it("shows the automatically verified candidate but never offers manual confirm or detect", async () => {
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("AWAITING_CONFIRMATION", {
        candidateSessionId: "official-session-1",
        candidateDisplayName: "Alice 的电脑",
        candidatePlatform: "Windows",
      }),
      binding: null,
    });
    renderBinding();

    expect(await screen.findByText("Alice 的电脑")).toBeVisible();
    expect(screen.getByText(/后台正在复核/)).toBeVisible();
    expect(screen.getByText(/不能证明物理设备身份/)).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /确认绑定|识别本设备/ }),
    ).toBeNull();
    expect(api.sessionBindingAction).not.toHaveBeenCalled();
  });

  it("updates the saved binding from SSE and reports its ID to the access gate", async () => {
    let current: {
      attempt: SessionBindingAttempt | null;
      binding: Binding | null;
    } = {
      attempt: attempt("READY_FOR_LOGIN"),
      binding: null,
    };
    vi.mocked(api.sessionBindingCurrent).mockImplementation(
      async () => current,
    );
    const { onBound } = renderBinding();
    await screen.findByText(/官方 Codex 登录/);

    current = {
      attempt: attempt("BOUND", { binding }),
      binding,
    };
    await emitStateChanged();

    expect(
      await screen.findByText("本设备官方会话绑定记录已保存。"),
    ).toBeVisible();
    expect(screen.getByText(/绑定账户：Alice/)).toBeVisible();
    expect(onBound).toHaveBeenCalledWith(binding.id);
    expect(api.sessionBindingAction).not.toHaveBeenCalled();
  });

  it("clears a previously displayed candidate when the product login expires", async () => {
    let expired = false;
    vi.mocked(api.sessionBindingCurrent).mockImplementation(async () => {
      if (expired) throw new Error("请先登录系统账户。");
      return {
        attempt: attempt("AWAITING_CONFIRMATION", {
          candidateSessionId: "candidate-session",
          candidateDisplayName: "过期前候选",
        }),
        binding: null,
      };
    });
    renderBinding();
    expect(await screen.findByText("过期前候选")).toBeVisible();

    expired = true;
    await emitStateChanged();

    expect(await screen.findByText("请先登录系统账户。")).toBeVisible();
    expect(screen.queryByText("过期前候选")).toBeNull();
    expect(screen.queryByRole("button", { name: "确认绑定此会话" })).toBeNull();
  });

  it("does not guess an existing login when no new official session appears", async () => {
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("REJECTED", {
        errorCode: "SESSION_BINDING_NO_NEW_SESSION",
        quarantined: true,
        queueState: "SETTLING",
      }),
      binding: null,
    });
    renderBinding();

    expect(await screen.findByText(/没有发现本次新增的官方会话/)).toBeVisible();
    expect(screen.getByText("正在整理绑定队列")).toBeVisible();
    expect(screen.queryByRole("button", { name: "确认绑定此会话" })).toBeNull();
    expect(api.sessionBindingStart).not.toHaveBeenCalled();
  });

  it("cancels only the active attempt and keeps the cancellation result", async () => {
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("READY_FOR_LOGIN"),
      binding: null,
    });
    vi.mocked(api.sessionBindingAction).mockResolvedValue(
      attempt("CANCELLED", { queueState: "SETTLING", quarantined: true }),
    );
    renderBinding();
    fireEvent.click(
      await screen.findByRole("button", { name: "取消本次绑定" }),
    );

    expect(await screen.findByText(/本次绑定已取消/)).toBeVisible();
    expect(api.sessionBindingAction).toHaveBeenCalledWith(attemptId, "cancel");
    expect(api.sessionBindingStart).not.toHaveBeenCalled();
  });

  it("keeps queue cleanup visible and exposes a deliberate reapply action", async () => {
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("CANCELLED", {
        quarantined: true,
        queueState: "SETTLING",
      }),
      binding: null,
    });
    renderBinding();

    expect(await screen.findByText("正在整理绑定队列")).toBeVisible();
    expect(api.sessionBindingStart).not.toHaveBeenCalled();
    fireEvent.click(
      await screen.findByRole("button", { name: "重新申请绑定" }),
    );

    expect(await screen.findByText(/后台会自动检测并完成绑定/)).toBeVisible();
    expect(api.sessionBindingStart).toHaveBeenCalledOnce();
  });

  it("keeps an explicit retry when the queue request fails", async () => {
    vi.mocked(api.sessionBindingStart)
      .mockRejectedValueOnce(new Error("中央后台暂不可用，请稍后重试。"))
      .mockResolvedValueOnce(attempt("QUEUED", { queuePosition: 2 }));
    renderBinding();
    fireEvent.click(
      await screen.findByRole("button", { name: "开始绑定本设备" }),
    );

    const retry = await screen.findByRole("button", { name: "重试申请绑定" });
    fireEvent.click(retry);
    expect(await screen.findByText(/正在排队，当前排位 2/)).toBeVisible();
    expect(api.sessionBindingStart).toHaveBeenCalledTimes(2);
  });

  it("re-reads a failed terminal state without automatically recreating the attempt", async () => {
    vi.mocked(api.sessionBindingCurrent)
      .mockResolvedValueOnce({
        attempt: attempt("CANCELLED", { quarantined: true }),
        binding: null,
      })
      .mockRejectedValueOnce(
        new Error(
          "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。",
        ),
      );
    renderBinding();
    fireEvent.click(
      await screen.findByRole("button", { name: "重新读取绑定状态" }),
    );

    expect(
      await screen.findByText(/无法连接中央服务器，请检查地址/),
    ).toBeVisible();
    expect(api.sessionBindingStart).not.toHaveBeenCalled();
    expect(
      screen.getByRole("button", { name: "重新读取绑定状态" }),
    ).toBeEnabled();
  });

  it("asks to run the installer when this installation has no device identity", async () => {
    const error = "本设备绑定信息不可用，请检查统计程序设备登记或联系管理员。";
    vi.mocked(api.sessionBindingCurrent).mockRejectedValue(new Error(error));
    renderBinding();

    expect(await screen.findByText(error)).toBeVisible();
    expect(
      screen.getByText("请先重新运行安装器准备统计服务，再登录完成设备登记。"),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "重新读取绑定状态" }),
    ).toBeEnabled();
  });

  it("ignores a late response after switching to another system user", async () => {
    let finish!: (value: {
      attempt: SessionBindingAttempt | null;
      binding: Binding | null;
    }) => void;
    vi.mocked(api.sessionBindingCurrent)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValue({
        attempt: null,
        binding: null,
      });
    const view = renderBinding("user-a");
    await waitFor(() => expect(finish).toBeTypeOf("function"));

    view.rerender(<SessionBinding userId="user-b" />);
    await screen.findByRole("button", { name: "开始绑定本设备" });
    await act(async () => {
      finish({
        attempt: attempt("AWAITING_CONFIRMATION", {
          candidateSessionId: "old-session",
          candidateDisplayName: "旧用户电脑",
        }),
        binding: null,
      });
    });

    expect(screen.queryByText("旧用户电脑")).toBeNull();
  });

  it("does not cancel a reserved official login just because the panel unmounts", async () => {
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("READY_FOR_LOGIN"),
      binding: null,
    });
    const view = renderBinding();
    await screen.findByText(/已取得本次独占登录名额/);

    view.unmount();

    expect(api.sessionBindingAction).not.toHaveBeenCalled();
    await waitFor(() => expect(streams[0]?.signal.aborted).toBe(true));
  });

  it("waits for the central deadline result instead of inventing expiration or saving a candidate", async () => {
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("AWAITING_CONFIRMATION", {
        candidateSessionId: "session",
        candidateDisplayName: "本机候选",
        expiresAt: "2026-10-15T10:00:01Z",
      }),
      binding: null,
    });
    renderBinding();
    expect(await screen.findByText("本机候选")).toBeVisible();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100);
    });

    expect(screen.getByText(/正在核对服务器到期状态/)).toBeVisible();
    expect(screen.queryByRole("button", { name: "确认绑定此会话" })).toBeNull();
    expect(screen.queryByRole("button", { name: "重新申请绑定" })).toBeNull();
  });
});
