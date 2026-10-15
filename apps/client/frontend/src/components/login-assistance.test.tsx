import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { invoke } from "@tauri-apps/api/core";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Dashboard } from "./dashboard";
import type { LoginAssistanceInfo, SystemSession } from "@/lib/api";
import i18n from "@/lib/i18n";

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
const invokeMock = vi.mocked(invoke);
const listenerId = "11111111-1111-4111-8111-111111111111";
const nextListenerId = "22222222-2222-4222-8222-222222222222";
const session: SystemSession = {
  authenticated: true,
  user: {
    id: "user-1",
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
};
const available: LoginAssistanceInfo = {
  available: true,
  reasonCode: null,
  officialAccountId: "official-1",
  accountLabel: "团队官方账户",
  recipientEmail: "team@example.com",
};
type ListenerState =
  | "CONNECTING"
  | "LISTENING"
  | "RECEIVED"
  | "CANCELLED"
  | "TIMED_OUT"
  | "FAILED";
function listener(state: ListenerState = "LISTENING", id = listenerId) {
  return {
    id,
    state,
    startedAt: "2026-10-12T10:00:00Z",
    expiresAt: "2026-10-12T10:02:00Z",
    listeningAt: state === "CONNECTING" ? null : "2026-10-12T10:00:01Z",
    receivedAt: state === "RECEIVED" ? "2026-10-12T10:00:02Z" : null,
    code: state === "RECEIVED" ? "123456" : null,
    errorCode: state === "FAILED" ? "MAILBOX_UNAVAILABLE" : null,
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
let clients: QueryClient[] = [];
let infoResponse = available;
let startResponse = listener();
let statusResponse = listener();
let cancelResponse = listener("CANCELLED");
let serverListenerExists = false;
function calls(command: string) {
  return invokeMock.mock.calls.filter(([name]) => name === command);
}
async function renderDashboard(bound = true) {
  const currentImplementation = invokeMock.getMockImplementation()!;
  invokeMock.mockImplementation(async (command, args) =>
    command === "system_access"
      ? {
          allowed: bound,
          bindingId: bound ? listenerId : null,
          reason: bound ? null : "SESSION_BINDING_REQUIRED",
        }
      : command === "session_binding_current"
        ? { attempt: null, binding: null }
        : currentImplementation(command, args),
  );
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, refetchOnWindowFocus: false },
    },
  });
  clients.push(client);
  const onLogout = vi.fn();
  const tree = (current: SystemSession) => (
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={client}>
        <Dashboard session={current} onLogout={onLogout} />
      </QueryClientProvider>
    </I18nextProvider>
  );
  const view = render(tree(session));
  if (bound)
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "额度总览" })).toBeEnabled(),
    );
  else await screen.findByText("team@example.com");
  return {
    ...view,
    client,
    onLogout,
    switchUser: () => {
      serverListenerExists = false;
      view.rerender(
        tree({
          ...session,
          user: {
            ...session.user!,
            id: "user-2",
            email: "bob@example.com",
            displayName: "Bob",
          },
        }),
      );
    },
  };
}
async function openPage() {
  fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
  await screen.findByText("team@example.com");
}
async function start() {
  fireEvent.click(screen.getByRole("button", { name: "监听邮件" }));
  await screen.findByText("正在监听");
}
async function tick(ms = 2_000) {
  await act(async () => {
    if (ms === 2_000)
      handlers.forEach((handler) => handler({ type: "state_changed" }));
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe("one-message login assistance", () => {
  it("keeps fallback assistance mounted during a manual access refresh", async () => {
    await renderDashboard(false);
    await start();
    const heading = screen.getByRole("heading", { name: "监听登录邮件" });
    const count = calls("login_assistance_info").length;
    fireEvent.click(screen.getByRole("button", { name: "刷新数据" }));
    await tick(20);
    expect(screen.getByRole("heading", { name: "监听登录邮件" })).toBe(heading);
    expect(calls("login_assistance_info")).toHaveLength(count);
    expect(calls("login_assistance_cancel")).toHaveLength(0);
  });
  beforeEach(() => {
    clients = [];
    infoResponse = available;
    startResponse = listener();
    statusResponse = listener();
    cancelResponse = listener("CANCELLED");
    serverListenerExists = false;
    handlers.clear();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-10-12T10:00:01Z"));
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command) => {
      if (command === "login_assistance_info") return infoResponse;
      if (command === "login_assistance_start") {
        serverListenerExists = true;
        statusResponse = startResponse;
        return startResponse;
      }
      if (command === "login_assistance_current")
        return {
          listener:
            serverListenerExists &&
            ["CONNECTING", "LISTENING", "RECEIVED"].includes(
              statusResponse.state,
            )
              ? statusResponse
              : null,
        };
      if (command === "login_assistance_status") return statusResponse;
      if (command === "login_assistance_cancel") {
        statusResponse = cancelResponse;
        return cancelResponse;
      }
      if (command === "status")
        return { service: "codex-quota-guard", state: "online" };
      if (command === "quota_overview")
        return {
          user: session.user,
          team: { id: "team-1", name: "Design" },
          policy: {
            fiveHourTokenLimit: 1_000,
            weeklyTokenLimit: 10_000,
            fiveHourSource: "TEAM",
            weeklySource: "TEAM",
          },
          usage: {
            measurementStatus: "CLIENT_REPORTED",
            fiveHourUsedTokens: 0,
            weeklyUsedTokens: 0,
            fiveHourRemainingTokens: 1_000,
            weeklyRemainingTokens: 10_000,
            fiveHourResetsAt: null,
            weeklyResetsAt: null,
            measuredAt: null,
          },
          allowed: true,
          blockReason: null,
        };
      if (command === "codex_rate_limits")
        return { planType: null, windows: [], fetchedAt: null };
      if (command === "meter_service_version")
        return {
          installedVersion: null,
          bundledVersion: "0.1.1",
          needsUpgrade: null,
          status: "NOT_INSTALLED",
        };
      if (command === "usage_status")
        return {
          fallback: {
            state: "SIGNED_OUT",
            lastScannedAt: null,
            lastReportedAt: null,
            lastReportedEvents: 0,
            issues: [],
          },
          service: {
            state: "NOT_INSTALLED",
            lastScanEpochSeconds: null,
            lastReportEpochSeconds: null,
            reportedEvents: 0,
            errorCode: null,
          },
          pairingState: "NOT_INSTALLED",
        };
      throw new Error("Unexpected test command");
    });
  });
  afterEach(() => {
    cleanup();
    clients.forEach((client) => client.clear());
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("only fetches eligibility after selection and does not ask for mailbox credentials", async () => {
    await renderDashboard();
    expect(calls("login_assistance_info")).toHaveLength(0);
    await openPage();
    expect(calls("login_assistance_info")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "监听邮件" })).toBeEnabled();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByText(/等待.*正在监听.*再.*ChatGPT/)).toBeVisible();
    expect(screen.getByText(/本次监听.*新到.*一封/)).toBeVisible();
    expect(calls("login_assistance_start")).toHaveLength(0);
  });

  it("does not say ready while connecting and reads current after a state signal", async () => {
    startResponse = listener("CONNECTING");
    await renderDashboard();
    await openPage();
    fireEvent.click(screen.getByRole("button", { name: "监听邮件" }));
    expect(await screen.findByText("正在连接邮箱")).toBeVisible();
    expect(screen.queryByText("正在监听")).not.toBeInTheDocument();
    statusResponse = listener("LISTENING");
    await tick();
    expect(screen.getByText("正在监听")).toBeVisible();
    expect(calls("login_assistance_status")).toHaveLength(0);
    expect(calls("login_assistance_current").length).toBeGreaterThan(1);
  });

  it("receives one code, stops polling, and stores it outside the global query cache", async () => {
    const { client } = await renderDashboard();
    await openPage();
    await start();
    statusResponse = listener("RECEIVED");
    await tick();
    expect(screen.getByLabelText("本次登录验证码")).toHaveTextContent("123456");
    expect(screen.getByText("已收到验证码，监听已停止")).toBeVisible();
    const count = calls("login_assistance_status").length;
    await tick(6_000);
    expect(calls("login_assistance_status")).toHaveLength(count);
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((query) => query.state.data),
      ),
    ).not.toContain("123456");
    expect(localStorage.getItem("login-assistance")).toBeNull();
  });

  it("clears the old code immediately and can listen for a new single message", async () => {
    await renderDashboard();
    await openPage();
    await start();
    statusResponse = listener("RECEIVED");
    await tick();
    const pending = deferred<ReturnType<typeof listener>>();
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) =>
      command === "login_assistance_start"
        ? pending.promise
        : original(command, args),
    );
    fireEvent.click(screen.getByRole("button", { name: "再次监听" }));
    expect(screen.queryByLabelText("本次登录验证码")).not.toBeInTheDocument();
    await act(async () =>
      pending.resolve(listener("LISTENING", nextListenerId)),
    );
    expect(screen.getByText("正在监听")).toBeVisible();
    expect(calls("login_assistance_start")).toHaveLength(2);
  });

  it("cancels an active listener and stops polling only after confirmed cancellation", async () => {
    await renderDashboard();
    await openPage();
    await start();
    fireEvent.click(screen.getByRole("button", { name: "取消监听" }));
    expect(await screen.findByText("监听已取消")).toBeVisible();
    expect(calls("login_assistance_cancel")[0]?.[1]).toEqual({ listenerId });
    const count = calls("login_assistance_status").length;
    await tick(6_000);
    expect(calls("login_assistance_status")).toHaveLength(count);
    expect(screen.getByRole("button", { name: "再次监听" })).toBeEnabled();
  });

  it("keeps the listener active when cancellation cannot be confirmed", async () => {
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "login_assistance_cancel")
        throw new Error("synthetic transport failure");
      return original(command, args);
    });
    await renderDashboard();
    await openPage();
    await start();
    fireEvent.click(screen.getByRole("button", { name: "取消监听" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("未能确认取消");
    expect(screen.queryByText("监听已取消")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "取消监听" })).toBeEnabled();
    await tick();
    expect(calls("login_assistance_current").length).toBeGreaterThan(1);
  });

  it("shows timeout without an old code and permits another attempt", async () => {
    await renderDashboard();
    await openPage();
    await start();
    statusResponse = listener("TIMED_OUT");
    await tick();
    expect(
      screen.getByText("监听已超时，请重新监听后再发送验证码"),
    ).toBeVisible();
    expect(screen.queryByLabelText("本次登录验证码")).not.toBeInTheDocument();
    const count = calls("login_assistance_status").length;
    await tick(6_000);
    expect(calls("login_assistance_status")).toHaveLength(count);
    expect(screen.getByRole("button", { name: "再次监听" })).toBeEnabled();
  });

  it("clears a received code when the response expiry passes without polling again", async () => {
    await renderDashboard();
    await openPage();
    await start();
    statusResponse = listener("RECEIVED");
    await tick();
    expect(screen.getByLabelText("本次登录验证码")).toHaveTextContent("123456");
    const count = calls("login_assistance_status").length;
    await tick(120_000);
    expect(screen.queryByLabelText("本次登录验证码")).not.toBeInTheDocument();
    expect(
      screen.getByText("验证码已过期，请重新监听后再发送验证码"),
    ).toBeVisible();
    expect(calls("login_assistance_status")).toHaveLength(count);
    expect(screen.getByRole("button", { name: "再次监听" })).toBeEnabled();
  });

  it("does not cancel or fake a timeout when the server cannot confirm local expiry", async () => {
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "login_assistance_current" && serverListenerExists)
        throw new Error("synthetic unavailable");
      return original(command, args);
    });
    await renderDashboard();
    await openPage();
    await start();
    await tick(120_000);
    expect(
      screen.queryByText("监听已超时，请重新监听后再发送验证码"),
    ).toBeNull();
    expect(screen.getByRole("button", { name: "再次监听" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "取消监听" })).toBeEnabled();
    expect(calls("login_assistance_cancel")).toHaveLength(0);
    const count = calls("login_assistance_status").length;
    await tick(6_000);
    expect(calls("login_assistance_status")).toHaveLength(count);
  });

  it("uses a safe fixed message for an OAuth failure without displaying its raw error code", async () => {
    await renderDashboard();
    await openPage();
    await start();
    statusResponse = { ...listener("FAILED"), errorCode: "MAIL_OAUTH_FAILED" };
    await tick();
    expect(
      screen.getByText("邮箱认证失败，请联系管理员检查邮箱配置。"),
    ).toBeVisible();
    expect(screen.queryByText("MAIL_OAUTH_FAILED")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "再次监听" })).toBeEnabled();
  });

  it.each([
    ["MAIL_DNS_FAILED", "无法解析邮箱服务器，请联系管理员检查邮箱主机与 DNS。"],
    [
      "MAIL_HOST_BLOCKED",
      "邮箱服务器地址被安全策略阻止，请联系管理员核对主机配置。",
    ],
    [
      "MAIL_AUTH_FAILED",
      "邮箱认证失败，请联系管理员检查邮箱地址、IMAP 开关与授权码。",
    ],
    [
      "MAIL_TLS_FAILED",
      "邮箱加密连接失败，请联系管理员检查邮箱服务与网络设置。",
    ],
    [
      "MAIL_CONNECTION_TIMEOUT",
      "连接邮箱超时，请稍后重新监听；如持续失败，请联系管理员检查网络。",
    ],
    [
      "MAIL_FOLDER_FAILED",
      "无法打开邮箱文件夹，请联系管理员检查 INBOX 及访问权限。",
    ],
  ])(
    "distinguishes safe transport failure %s without showing raw details",
    async (errorCode, message) => {
      await renderDashboard();
      await openPage();
      await start();
      statusResponse = { ...listener("FAILED"), errorCode };
      await tick();
      expect(screen.getByText(message)).toBeVisible();
      expect(screen.queryByText(errorCode)).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "再次监听" })).toBeEnabled();
    },
  );

  it("reloads unavailable mailbox configuration after an administrator repairs it without starting a listener", async () => {
    infoResponse = {
      ...available,
      available: false,
      reasonCode: "MAILBOX_DISABLED",
    };
    await renderDashboard();
    fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
    await screen.findByText(/团队登录邮箱已停用/);
    infoResponse = available;
    fireEvent.click(screen.getByRole("button", { name: "重新读取邮箱配置" }));
    await screen.findByText("team@example.com");
    expect(screen.getByRole("button", { name: "监听邮件" })).toBeEnabled();
    expect(calls("login_assistance_info")).toHaveLength(2);
    expect(calls("login_assistance_start")).toHaveLength(0);
  });

  it("allows an inactive failed listener to reload mailbox details without fabricating listener recovery", async () => {
    await renderDashboard();
    await openPage();
    await start();
    statusResponse = {
      ...listener("FAILED"),
      errorCode: "MAIL_CONFIG_CHANGED",
    };
    await tick();
    await screen.findByText(/团队或邮箱配置已变更/);
    infoResponse = { ...available, recipientEmail: "updated@example.com" };
    fireEvent.click(screen.getByRole("button", { name: "重新读取邮箱配置" }));
    await screen.findByText("updated@example.com");
    expect(screen.getByText(/团队或邮箱配置已变更/)).toBeVisible();
    expect(screen.getByRole("button", { name: "再次监听" })).toBeEnabled();
  });

  it("keeps listening on leaving the page and ignores a disposed current response", async () => {
    const pending = deferred<ReturnType<typeof listener>>();
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) =>
      command === "login_assistance_current" && serverListenerExists
        ? pending.promise.then((value) => ({ listener: value }))
        : original(command, args),
    );
    await renderDashboard();
    await openPage();
    await start();
    await tick();
    fireEvent.click(screen.getByRole("button", { name: "额度总览" }));
    expect(calls("login_assistance_cancel")).toHaveLength(0);
    serverListenerExists = false;
    await openPage();
    await act(async () => pending.resolve(listener("RECEIVED")));
    expect(screen.queryByLabelText("本次登录验证码")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "监听邮件" })).toBeEnabled();
  });

  it("ignores a late start response after unmount without cancelling the background task", async () => {
    const pending = deferred<ReturnType<typeof listener>>();
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) =>
      command === "login_assistance_start"
        ? pending.promise
        : original(command, args),
    );
    const { unmount } = await renderDashboard();
    await openPage();
    fireEvent.click(screen.getByRole("button", { name: "监听邮件" }));
    unmount();
    await act(async () => pending.resolve(listener("LISTENING")));
    expect(calls("login_assistance_cancel")).toHaveLength(0);
    expect(calls("login_assistance_status")).toHaveLength(0);
  });

  it("clears a received code when the signed-in product user changes", async () => {
    const { switchUser } = await renderDashboard();
    await openPage();
    await start();
    statusResponse = listener("RECEIVED");
    await tick();
    expect(screen.getByLabelText("本次登录验证码")).toHaveTextContent("123456");
    switchUser();
    expect(screen.queryByLabelText("本次登录验证码")).not.toBeInTheDocument();
    await waitFor(() => expect(calls("login_assistance_info")).toHaveLength(2));
  });

  it("ignores an active old-user current result when the user changes", async () => {
    const pending = deferred<ReturnType<typeof listener>>();
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) =>
      command === "login_assistance_current" && serverListenerExists
        ? pending.promise.then((value) => ({ listener: value }))
        : original(command, args),
    );
    const { switchUser } = await renderDashboard();
    await openPage();
    await start();
    await tick();
    switchUser();
    await act(async () => pending.resolve(listener("RECEIVED")));
    expect(screen.queryByLabelText("本次登录验证码")).not.toBeInTheDocument();
    expect(calls("login_assistance_cancel")).toHaveLength(0);
  });

  it("clears local codes and delegates authenticated cancellation to system logout", async () => {
    const { onLogout } = await renderDashboard();
    await openPage();
    await start();
    fireEvent.click(screen.getByRole("button", { name: "退出系统" }));
    expect(calls("login_assistance_cancel")).toHaveLength(0);
    expect(onLogout).toHaveBeenCalledOnce();
    expect(
      screen.queryByRole("button", { name: "取消监听" }),
    ).not.toBeInTheDocument();
  });

  it("also clears fallback assistance without remounting when an unbound user logs out", async () => {
    const { onLogout } = await renderDashboard(false);
    await start();
    fireEvent.click(screen.getByRole("button", { name: "退出系统" }));
    expect(calls("login_assistance_cancel")).toHaveLength(0);
    expect(onLogout).toHaveBeenCalledOnce();
    expect(
      screen.queryByRole("button", { name: "取消监听" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "额度总览" })).toBeDisabled();
  });

  it("copies a code only on explicit click", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    await renderDashboard();
    await openPage();
    await start();
    statusResponse = listener("RECEIVED");
    await tick();
    expect(writeText).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "复制验证码" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("123456"));
  });

  it.each(["TEAM_UNAVAILABLE", "MAILBOX_UNCONFIGURED"])(
    "guides users when unavailable: %s",
    async (reasonCode) => {
      infoResponse = { ...available, available: false, reasonCode };
      await renderDashboard();
      fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
      expect(await screen.findByText(/联系管理员/)).toBeVisible();
      expect(
        screen.queryByRole("button", { name: "监听邮件" }),
      ).not.toBeInTheDocument();
      expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    },
  );
});
