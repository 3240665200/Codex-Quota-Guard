import { usdFixture } from "@/test/usd-fixtures";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Dashboard } from "@/components/dashboard";
import i18n from "@/lib/i18n";
import {
  api,
  type AssistanceEvent,
  type QuotaOverview,
  type SessionBinding,
  type SessionBindingAttempt,
  type SystemSession,
} from "@/lib/api";

const userId = "11111111-1111-4111-8111-111111111111";
const deviceId = "22222222-2222-4222-8222-222222222222";
const binding: SessionBinding = {
  id: "33333333-3333-4333-8333-333333333333",
  userId,
  userDisplayName: "Alice",
  userEmail: "alice@example.com",
  deviceId,
  enrollmentId: deviceId,
  officialAccountId: "44444444-4444-4444-8444-444444444444",
  sessionId: "official-session",
  status: "ACTIVE",
  matchMethod: "SERIAL_LOGIN_DIFF",
  verificationLevel: "USER_CONFIRMED_INFERENCE",
  createdAt: "2026-10-15T10:00:00Z",
  endedAt: null,
};

const session: SystemSession = {
  authenticated: true,
  user: {
    id: userId,
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
};

const deniedAccess = {
  allowed: false,
  bindingId: null,
  reason: "SESSION_BINDING_REQUIRED",
};
const allowedAccess = { allowed: true, bindingId: binding.id, reason: null };
const quota: QuotaOverview = {
  billing: usdFixture({allowed:false,blockReason:"SESSION_BINDING_REQUIRED"}),
  user: session.user!,
  team: null,
  teamQuota: null,
  teamMembers: null,
  policy: {
    fiveHourTokenLimit: null,
    weeklyTokenLimit: null,
    fiveHourSource: "UNCONFIGURED",
    weeklySource: "UNCONFIGURED",
  },
  usage: {
    measurementStatus: "CLIENT_REPORTED",
    fiveHourUsedTokens: 0,
    weeklyUsedTokens: 0,
    fiveHourRemainingTokens: null,
    weeklyRemainingTokens: null,
    fiveHourResetsAt: null,
    weeklyResetsAt: null,
    measuredAt: null,
  },
  allowed: false,
  blockReason: "TOKEN_QUOTA_UNCONFIGURED",
};

function attempt(
  status: SessionBindingAttempt["status"] = "READY_FOR_LOGIN",
  overrides: Partial<SessionBindingAttempt> = {},
): SessionBindingAttempt {
  return {
    id: "55555555-5555-4555-8555-555555555555",
    deviceId,
    enrollmentId: deviceId,
    officialAccountId: binding.officialAccountId,
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

function show(role = "USER") {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  });
  const result = render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={client}>
        <Dashboard
          session={{
            ...session,
            user: { ...session.user!, role },
          }}
          onLogout={vi.fn()}
        />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  return { ...result, client };
}

async function emitStreamEvent(type: AssistanceEvent) {
  await act(async () => {
    streams[0]?.handler(type);
    await new Promise((resolve) => setTimeout(resolve, 1));
  });
}

beforeEach(() => {
  vi.spyOn(api, "authenticatorInfo").mockResolvedValue({
    available: false,
    reasonCode: "AUTHENTICATOR_UNCONFIGURED",
    officialAccountId: null,
    accountLabel: null,
  });
  streams = [];
  vi.spyOn(api, "clientAccess").mockResolvedValue(deniedAccess);
  vi.spyOn(api, "sessionBindingCurrent").mockResolvedValue({
    attempt: null,
    binding: null,
  });
  vi.spyOn(api, "sessionBindingStart").mockResolvedValue(attempt());
  vi.spyOn(api, "sessionBindingAction").mockImplementation(async () =>
    attempt("CANCELLED"),
  );
  vi.spyOn(api, "loginAssistanceInfo").mockResolvedValue({
    available: false,
    reasonCode: "NO_TEAM",
    officialAccountId: null,
    accountLabel: null,
    recipientEmail: null,
  });
  vi.spyOn(api, "loginAssistanceCurrent").mockResolvedValue({ listener: null });
  vi.spyOn(api, "autostartStatus").mockResolvedValue({
    supported: true,
    enabled: false,
  });
  vi.spyOn(api, "usageStatus").mockResolvedValue({
    state: "OFFLINE",
    lastScanEpochSeconds: null,
    lastReportEpochSeconds: null,
    reportedEvents: 0,
    errorCode: null,
    version: null,
  });
  vi.spyOn(api, "meterServiceVersion").mockResolvedValue({
    installedVersion: null,
    bundledVersion: "0.2.6",
    needsUpgrade: null,
    status: "NOT_INSTALLED",
  });
  vi.spyOn(api, "quotaOverview").mockResolvedValue(quota);
  vi.spyOn(api, "codexRateLimits").mockResolvedValue({
    planType: null,
    windows: [],
    fetchedAt: new Date().toISOString(),
  });
  vi.spyOn(api, "systemLogout").mockResolvedValue(undefined);
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
  await Promise.resolve();
  await Promise.resolve();
  vi.restoreAllMocks();
});

describe("device binding access gate", () => {
  it("keeps assistance and binding available while the central access gate denies protected menus", async () => {
    show("ADMIN");

    expect(
      await screen.findByRole("region", { name: "本设备官方会话绑定" }),
    ).toBeVisible();
    expect(api.sessionBindingCurrent).toHaveBeenCalled();
    expect(api.loginAssistanceInfo).toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    expect(
      screen.getByText(/完成本设备官方会话绑定后，中央后台确认有效/),
    ).toBeVisible();
    expect(api.quotaOverview).not.toHaveBeenCalled();
    expect(api.codexRateLimits).not.toHaveBeenCalled();
  });

  it("does not grant access from a saved ACTIVE binding when central access denies it", async () => {
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("BOUND", { binding }),
      binding,
    });

    show();

    expect(
      await screen.findByRole("region", { name: "本设备官方会话绑定" }),
    ).toBeVisible();
    expect(
      await screen.findByText("本设备官方会话绑定记录已保存。"),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "额度总览" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    expect(api.quotaOverview).not.toHaveBeenCalled();
  });

  it("refreshes the binding record from SSE without rechecking an unchanged central grant", async () => {
    vi.mocked(api.clientAccess).mockResolvedValue(allowedAccess);
    vi.mocked(api.sessionBindingCurrent).mockResolvedValue({
      attempt: attempt("BOUND", { binding }),
      binding,
    });
    show();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    const accessReads = vi.mocked(api.clientAccess).mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
    await screen.findByText("本设备官方会话绑定记录已保存。");
    expect(streams).toHaveLength(1);

    const bindingReads = vi.mocked(api.sessionBindingCurrent).mock.calls.length;
    await emitStreamEvent("state_changed");
    await waitFor(() =>
      expect(api.sessionBindingCurrent).toHaveBeenCalledTimes(bindingReads + 1),
    );
    expect(api.clientAccess).toHaveBeenCalledTimes(accessReads);
    expect(screen.getByRole("button", { name: "设置" })).toBeEnabled();
  });

  it("shares one assistance stream across workspace navigation and aborts it on dashboard unmount", async () => {
    vi.mocked(api.clientAccess).mockResolvedValue(allowedAccess);
    const view = show();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    expect(streams).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
    await screen.findByRole("region", { name: "本设备官方会话绑定" });
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "启动设置" });

    expect(streams).toHaveLength(1);
    expect(streams[0].signal.aborted).toBe(false);
    view.unmount();
    await waitFor(() => expect(streams[0].signal.aborted).toBe(true));
  });

  it("locks protected pages on an access error and shows the safe retry guidance", async () => {
    vi.mocked(api.clientAccess).mockResolvedValueOnce(allowedAccess);
    const { client } = show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "启动设置" });

    vi.mocked(api.clientAccess).mockRejectedValueOnce(
      new Error(
        "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。",
      ),
    );
    await act(async () => {
      await client.invalidateQueries({ queryKey: ["client-access"] });
    });

    expect(await screen.findByText("绑定状态核验失败")).toBeVisible();
    expect(
      screen.getByText(/已保存的绑定记录不代表当前访问权限已核验通过/),
    ).toBeVisible();
    expect(screen.getByText(/无法连接中央服务器，请检查地址/)).toBeVisible();
    expect(screen.queryByRole("heading", { name: "启动设置" })).toBeNull();
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    expect(api.systemLogout).not.toHaveBeenCalled();
  });

  it("rechecks access after a BOUND event and keeps menus locked until central access allows them", async () => {
    const accessResponses = [deniedAccess, deniedAccess, allowedAccess];
    vi.mocked(api.clientAccess).mockImplementation(
      async () => accessResponses.shift() ?? deniedAccess,
    );
    let current: {
      attempt: SessionBindingAttempt | null;
      binding: SessionBinding | null;
    } = {
      attempt: attempt("AWAITING_CONFIRMATION", {
        candidateSessionId: "new-session",
        candidateDisplayName: "This login",
        candidatePlatform: "Windows",
      }),
      binding: null,
    };
    vi.mocked(api.sessionBindingCurrent).mockImplementation(
      async () => current,
    );
    show();

    await screen.findByText("This login");
    current = {
      attempt: attempt("BOUND", { binding }),
      binding,
    };
    await emitStreamEvent("state_changed");

    await waitFor(() => expect(api.clientAccess).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "重新核对绑定" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    expect(api.clientAccess).toHaveBeenCalledTimes(3);
  });

  it("retreats immediately on the protected binding-required event without logging out", async () => {
    vi.mocked(api.clientAccess).mockResolvedValueOnce(allowedAccess);
    const { client } = show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "启动设置" });

    vi.mocked(api.clientAccess).mockResolvedValueOnce(deniedAccess);
    await act(async () => {
      window.dispatchEvent(new Event("quota-guard-binding-required"));
      await client.refetchQueries({ queryKey: ["client-access"] });
    });

    expect(
      await screen.findByRole("region", { name: "本设备官方会话绑定" }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    expect(api.systemLogout).not.toHaveBeenCalled();
  });
});
