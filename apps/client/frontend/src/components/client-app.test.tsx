import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { StrictMode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@tauri-apps/api/core";
import { listen, type EventCallback } from "@tauri-apps/api/event";
import { toast } from "sonner";

import { ClientApp } from "@/components/client-app";
import i18n from "@/lib/i18n";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
vi.mock("@/lib/assistance-events", () => ({
  subscribeToAssistanceEvents: () => () => {},
}));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));
vi.mock("sonner", () => ({
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}));

const invokeMock = vi.mocked(invoke);
const listenMock = vi.mocked(listen);

const signedOut = {
  authenticated: false,
  user: null,
};
const signedIn = {
  authenticated: true,
  user: {
    id: "user-1",
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
};
const quotaOverview = {
  user: signedIn.user,
  team: null,
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
    measuredAt: "2026-10-06T00:00:00Z",
  },
  allowed: false,
  blockReason: "TOKEN_QUOTA_UNCONFIGURED",
};

function renderClientApp() {
  const currentImplementation = invokeMock.getMockImplementation()!;
  invokeMock.mockImplementation(async (command, args) =>
    command === "session_binding_current"
      ? { attempt: null, binding: null }
      : currentImplementation(command, args),
  );
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const result = render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <ClientApp />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  return { ...result, queryClient };
}

describe("ClientApp system session gate", () => {
  it("keeps the mounted workspace on a transient background session refresh error", async () => {
    const original = invokeMock.getMockImplementation()!;
    let unavailable = false;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "system_current") {
        if (unavailable) throw new Error("synthetic offline");
        return signedIn;
      }
      return original(command, args);
    });
    const { queryClient } = renderClientApp();
    const heading = await screen.findByRole("heading", { name: "个人 Token" });
    unavailable = true;
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ["system-session"] });
    });
    expect(await screen.findByText(/登录状态暂时无法刷新/)).toBeVisible();
    expect(screen.getByRole("heading", { name: "个人 Token" })).toBe(heading);
  });
  it("explains the seven-day local remembered session on the login page", async () => {
    renderClientApp();
    expect(await screen.findByText(/在本机保留登录状态 7 天/)).toBeVisible();
    expect(screen.getByText(/退出登录将清除/)).toBeVisible();
  });

  it("enters the dashboard from a restored native session without requesting a password", async () => {
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) =>
      command === "system_current" ? signedIn : original(command, args),
    );
    renderClientApp();
    expect(
      await screen.findByRole("heading", { name: "个人 Token" }),
    ).toBeVisible();
    expect(screen.queryByLabelText("密码")).not.toBeInTheDocument();
    expect(
      invokeMock.mock.calls.some(([command]) => command === "system_login"),
    ).toBe(false);
  });
  beforeEach(() => {
    invokeMock.mockClear();
    listenMock.mockReset();
    listenMock.mockResolvedValue(vi.fn());
    vi.mocked(toast.warning).mockClear();
    vi.mocked(toast.success).mockClear();
    vi.mocked(toast.error).mockClear();
    invokeMock.mockImplementation(async (command) => {
      switch (command) {
        case "system_access":
          return {
            allowed: true,
            bindingId: "11111111-1111-4111-8111-111111111111",
            reason: null,
          };
        case "startup_gate_check":
        case "startup_guard_status":
          return { state: "READY", ccSwitchTerminated: false };
        case "system_current":
          return signedOut;
        case "meter_service_version":
          return {
            installedVersion: null,
            bundledVersion: "0.1.1",
            needsUpgrade: null,
            status: "NOT_INSTALLED",
          };
        case "server_config_get":
          return { url: "http://127.0.0.1:8080" };
        case "server_config_save":
          return {
            url: "http://192.168.0.102:8080",
          };
        case "system_login":
          return signedIn;
        case "system_logout":
          return undefined;
        case "status":
          return { service: "codex-quota-guard", state: "online" };
        case "quota_overview":
          return quotaOverview;
        case "usage_analytics":
          return {
            from: "2026-10-03T00:00:00Z",
            to: "2026-10-10T00:00:00Z",
            currency: "USD",
            timezone: "UTC",
            granularity: "DAY",
            trend: [],
            summary: {
              eventCount: 0,
              totalTokens: 0,
              inputTokens: null,
              cachedInputTokens: null,
              cacheWriteInputTokens: null,
              outputTokens: null,
              reasoningOutputTokens: null,
              breakdownEventCount: 0,
              costedEventCount: 0,
              estimatedCostUsd: null,
              cacheHitRatePercent: null,
            },
          };
        case "usage_events":
          return {
            items: [],
            page: 0,
            size: 10,
            totalElements: 0,
            totalPages: 0,
          };
        case "usage_status":
          return {
            fallback: {
              state: "UP_TO_DATE",
              lastScannedAt: "2026-10-07T00:00:00Z",
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
        case "codex_rate_limits":
          return { planType: null, windows: [], fetchedAt: null };
        default:
          throw new Error(`Unexpected command: ${command}`);
      }
    });
  });

  it("blocks Java-backed session requests until native startup conditions pass", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "startup_gate_check") {
        return { state: "ADMIN_REQUIRED", ccSwitchTerminated: false };
      }
      if (command === "server_config_get") {
        return { url: "http://127.0.0.1:8080" };
      }
      throw new Error(`Unexpected command: ${command}`);
    });
    renderClientApp();

    expect(await screen.findByText("启动条件尚未满足")).toBeVisible();
    expect(screen.getByText(/以管理员身份运行/)).toBeVisible();
    expect(screen.getByText(/默认与 CC Switch 共存/)).toBeVisible();
    expect(screen.queryByText(/运行期间，统计程序会自动强制结束/)).toBeNull();
    expect(screen.queryByText("登录 Quota Guard")).toBeNull();
    expect(invokeMock).not.toHaveBeenCalledWith("system_current");
  });

  it("keeps server settings available while the startup gate is blocked", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "startup_gate_check") {
        return { state: "METER_UNAVAILABLE", ccSwitchTerminated: false };
      }
      if (command === "server_config_get") {
        return { url: "http://127.0.0.1:8080" };
      }
      if (command === "server_config_save") {
        return { url: "http://192.168.0.102:8080" };
      }
      throw new Error(`Unexpected command: ${command}`);
    });
    renderClientApp();

    const address = await screen.findByLabelText("中央服务器地址");
    await user.clear(address);
    await user.type(address, "http://192.168.0.102:8080");
    await user.click(screen.getByRole("button", { name: "保存地址" }));
    expect(invokeMock).toHaveBeenCalledWith("server_config_save", {
      config: { url: "http://192.168.0.102:8080" },
    });
    expect(await screen.findByText(/服务器与统计程序地址已同步/)).toBeVisible();
    expect(screen.getByRole("button", { name: "重新检查" })).toBeDisabled();
    expect(invokeMock).not.toHaveBeenCalledWith("system_current");
  });

  it("locks startup settings and retry while the meter address is syncing", async () => {
    const user = userEvent.setup();
    let completeSave!: (config: { url: string }) => void;
    const pendingSave = new Promise<{ url: string }>((resolve) => {
      completeSave = resolve;
    });
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "startup_gate_check") {
        return { state: "METER_UNAVAILABLE", ccSwitchTerminated: false };
      }
      if (command === "server_config_save") return pendingSave;
      return original(command, args);
    });
    renderClientApp();

    const address = await screen.findByLabelText("中央服务器地址");
    await waitFor(() => expect(address).toHaveValue("http://127.0.0.1:8080"));
    await user.clear(address);
    await user.type(address, "https://next.example.com");
    await user.click(screen.getByRole("button", { name: "保存地址" }));

    expect(address).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "正在同步统计程序…" }),
    ).toBeDisabled();
    expect(screen.getByRole("button", { name: "重新检查" })).toBeDisabled();
    expect(screen.queryByText(/地址已同步/)).not.toBeInTheDocument();

    await act(async () => completeSave({ url: "https://next.example.com" }));
    expect(await screen.findByText(/地址已同步/)).toBeVisible();
    expect(address).toHaveValue("https://next.example.com");
    expect(screen.getByRole("button", { name: "重新检查" })).toBeDisabled();
  });

  it("keeps startup retry available when meter address synchronization fails", async () => {
    const user = userEvent.setup();
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "startup_gate_check") {
        return { state: "METER_UNAVAILABLE", ccSwitchTerminated: false };
      }
      if (command === "server_config_save") {
        throw new Error(
          "服务器地址未保存。同步统计程序地址需要管理员授权，取消授权后地址不会保存。",
        );
      }
      return original(command, args);
    });
    renderClientApp();
    await user.click(await screen.findByRole("button", { name: "保存地址" }));

    expect(await screen.findByText(/服务器地址未保存/)).toBeVisible();
    expect(screen.queryByText(/地址已同步/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重新检查" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "保存地址" })).toBeEnabled();
  });

  it("requires the product system account before rendering the dashboard", async () => {
    renderClientApp();

    expect(await screen.findByText("登录 Quota Guard")).toBeVisible();
    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith("startup_gate_check"),
    );
    expect(screen.queryByText("登录 Codex")).toBeNull();
    expect(screen.queryByText("上传 Codex 凭证")).toBeNull();
  });

  it("keeps polling after a busy native check and still blocks a later guard failure", async () => {
    const interval = vi.spyOn(window, "setInterval");
    try {
      renderClientApp();
      await screen.findByText("登录 Quota Guard");
      const poll = interval.mock.calls.find((call) => call[1] === 3_000)?.[0];
      expect(typeof poll).toBe("function");

      invokeMock.mockResolvedValueOnce({
        state: "CHECKING",
        ccSwitchTerminated: false,
      });
      await act(async () => {
        (poll as () => void)();
      });
      expect(screen.getByText("登录 Quota Guard")).toBeVisible();
      expect(screen.queryByText("正在检查启动条件")).toBeNull();

      invokeMock.mockResolvedValueOnce({
        state: "GUARD_ERROR",
        ccSwitchTerminated: false,
      });
      await act(async () => {
        (poll as () => void)();
      });
      expect(await screen.findByText("启动条件尚未满足")).toBeVisible();
      expect(screen.queryByText("登录 Quota Guard")).toBeNull();
    } finally {
      interval.mockRestore();
    }
  });

  it("keeps the terminated banner without duplicating the native acknowledged notice", async () => {
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (
        command === "startup_gate_check" ||
        command === "startup_guard_status"
      ) {
        return { state: "READY", ccSwitchTerminated: true };
      }
      return original(command, args);
    });
    renderClientApp();
    expect(await screen.findByText(/已停止 CC Switch/)).toBeVisible();
    expect(toast.warning).not.toHaveBeenCalled();
    expect(screen.getByText(/仅在确认官方会话冲突时停止并提醒/)).toBeVisible();
  });

  it("blocks session requests on restore failure and retries recovery explicitly", async () => {
    const user = userEvent.setup();
    const original = invokeMock.getMockImplementation()!;
    let attempts = 0;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "startup_gate_check") {
        attempts += 1;
        return attempts === 1
          ? {
              state: "CODEX_RESTORE_FAILED",
              ccSwitchTerminated: true,
              codexConnectionRestored: false,
              codexRestoreError: "无法写入 Codex 配置，请检查文件权限。",
            }
          : {
              state: "READY",
              ccSwitchTerminated: true,
              codexConnectionRestored: true,
              codexRestoreError: null,
            };
      }
      return original(command, args);
    });
    renderClientApp();
    expect(await screen.findByText(/Codex 连接配置恢复失败/)).toBeVisible();
    expect(screen.getByText(/请检查文件权限/)).toBeVisible();
    expect(invokeMock).not.toHaveBeenCalledWith("system_current");
    await user.click(screen.getByRole("button", { name: "重新检查" }));
    expect(await screen.findByText("登录 Quota Guard")).toBeVisible();
    expect(screen.getByText(/已恢复 Codex 连接配置/)).toBeVisible();
    expect(attempts).toBe(2);
  });

  it("shows a background restoration failure when the next monitor result arrives", async () => {
    const interval = vi.spyOn(window, "setInterval");
    try {
      renderClientApp();
      await screen.findByText("登录 Quota Guard");
      const poll = interval.mock.calls.find((call) => call[1] === 3_000)?.[0];
      invokeMock.mockResolvedValueOnce({
        state: "CODEX_RESTORE_FAILED",
        ccSwitchTerminated: true,
        codexRestoreError: "未找到可用的恢复来源。",
      });
      await act(async () => {
        (poll as () => void)();
      });
      expect(await screen.findByText(/Codex 连接配置恢复失败/)).toBeVisible();
      expect(screen.getByText(/未找到可用的恢复来源/)).toBeVisible();
      expect(screen.queryByText("登录 Quota Guard")).toBeNull();
      expect(screen.getByRole("button", { name: "重新检查" })).toBeVisible();
    } finally {
      interval.mockRestore();
    }
  });

  it("shows signed-out tray scan feedback without bypassing the login gate", async () => {
    renderClientApp();
    await screen.findByText("登录 Quota Guard");
    const callback = listenMock.mock.calls[0][1] as EventCallback<unknown>;
    callback({
      event: "cqg-tray-feedback",
      id: 1,
      payload: { action: "scan", outcome: "signed_out" },
    });
    await waitFor(() =>
      expect(toast.warning).toHaveBeenCalledWith(
        "请先登录系统账户，再扫描用量。",
      ),
    );
    expect(screen.queryByRole("heading", { name: "额度仪表盘" })).toBeNull();
    expect(invokeMock).not.toHaveBeenCalledWith(
      "system_login",
      expect.anything(),
    );
  });

  it("refreshes quota, ledger and diagnostics after a tray action", async () => {
    const { queryClient } = renderClientApp();
    await screen.findByText("登录 Quota Guard");
    const invalidation = vi.spyOn(queryClient, "invalidateQueries");
    const callback = listenMock.mock.calls[0][1] as EventCallback<unknown>;
    callback({
      event: "cqg-tray-feedback",
      id: 1,
      payload: { action: "meter", outcome: "running" },
    });
    expect(toast.success).toHaveBeenCalledWith("统计程序正在运行。");
    for (const key of [
      "usage-status",
      "quota-overview",
      "usage-events",
      "meter-runtime-logs",
      "meter-service-version",
    ]) {
      expect(invalidation).toHaveBeenCalledWith({ queryKey: [key] });
    }
    expect(invokeMock).not.toHaveBeenCalledWith("system_logout");
  });

  it("toggles password visibility without changing the entered value", async () => {
    const user = userEvent.setup();
    renderClientApp();
    const password = await screen.findByLabelText("密码");
    await user.type(password, "quota-secret");
    expect(password).toHaveAttribute("type", "password");
    await user.click(screen.getByRole("button", { name: "显示密码" }));
    expect(password).toHaveAttribute("type", "text");
    expect(password).toHaveValue("quota-secret");
    await user.click(screen.getByRole("button", { name: "隐藏密码" }));
    expect(password).toHaveAttribute("type", "password");
  });

  it("does not request a second admin elevation in React StrictMode", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <StrictMode>
        <I18nextProvider i18n={i18n}>
          <QueryClientProvider client={queryClient}>
            <ClientApp />
          </QueryClientProvider>
        </I18nextProvider>
      </StrictMode>,
    );
    await screen.findByText("登录 Quota Guard");
    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith("startup_gate_check"),
    );
    expect(
      invokeMock.mock.calls.filter(
        ([command]) => command === "startup_gate_check",
      ),
    ).toHaveLength(1);
  });

  it("opens the personal quota dashboard only after the system login succeeds", async () => {
    const user = userEvent.setup();
    renderClientApp();

    await user.type(
      await screen.findByLabelText("系统账号"),
      "alice@example.com",
    );
    await user.type(screen.getByLabelText("密码"), "password");
    await user.click(screen.getByRole("button", { name: "登录系统" }));

    expect(
      await screen.findByRole("heading", { name: "额度仪表盘" }),
    ).toBeVisible();
    expect(invokeMock).toHaveBeenCalledWith("system_login", {
      email: "alice@example.com",
      password: "password",
    });
    expect(invokeMock).toHaveBeenCalledWith("quota_overview");
  });

  it("returns to the login gate after a system logout", async () => {
    const user = userEvent.setup();
    renderClientApp();

    await user.type(
      await screen.findByLabelText("系统账号"),
      "alice@example.com",
    );
    await user.type(screen.getByLabelText("密码"), "password");
    await user.click(screen.getByRole("button", { name: "登录系统" }));
    await user.click(await screen.findByRole("button", { name: "退出系统" }));

    await waitFor(() => {
      expect(screen.getByText("登录 Quota Guard")).toBeVisible();
    });
    expect(invokeMock).toHaveBeenCalledWith("system_logout");
  });

  it("lets a signed-out user save HTTP without a confirmation checkbox", async () => {
    const user = userEvent.setup();
    renderClientApp();

    await user.click(await screen.findByRole("button", { name: "服务器设置" }));
    const address = screen.getByLabelText("服务器地址");
    await waitFor(() => expect(address).toHaveValue("http://127.0.0.1:8080"));
    await user.clear(address);
    await user.type(address, "http://192.168.0.102:8080");
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "保存服务器地址" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("server_config_save", {
        config: {
          url: "http://192.168.0.102:8080",
        },
      });
    });
    expect(await screen.findByText(/从托盘退出并重新启动/)).toBeVisible();
    expect(screen.getByRole("button", { name: "登录系统" })).toBeDisabled();
  });

  it("prevents login or another edit until meter synchronization finishes", async () => {
    const user = userEvent.setup();
    let completeSave!: (config: { url: string }) => void;
    const pendingSave = new Promise<{ url: string }>((resolve) => {
      completeSave = resolve;
    });
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) =>
      command === "server_config_save" ? pendingSave : original(command, args),
    );
    renderClientApp();
    await user.click(await screen.findByRole("button", { name: "服务器设置" }));
    const address = screen.getByLabelText("服务器地址");
    await waitFor(() => expect(address).toHaveValue("http://127.0.0.1:8080"));
    await user.clear(address);
    await user.type(address, "https://next.example.com");
    await user.click(screen.getByRole("button", { name: "保存服务器地址" }));

    expect(address).toBeDisabled();
    expect(screen.getByRole("button", { name: "登录系统" })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "正在同步统计程序…" }),
    ).toBeDisabled();
    expect(toast.success).not.toHaveBeenCalled();
    expect(screen.queryByText(/地址已同步/)).not.toBeInTheDocument();

    await act(async () => completeSave({ url: "https://next.example.com" }));
    expect(await screen.findByText(/地址已同步/)).toBeVisible();
    expect(screen.getByRole("button", { name: "登录系统" })).toBeDisabled();
    expect(invokeMock).not.toHaveBeenCalledWith(
      "system_login",
      expect.anything(),
    );
  });

  it("shows a cancelled synchronization as an error and allows saving again", async () => {
    const user = userEvent.setup();
    const original = invokeMock.getMockImplementation()!;
    let rejected = true;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "server_config_save" && rejected) {
        throw new Error(
          "服务器地址未保存。同步统计程序地址需要管理员授权，取消授权后地址不会保存。",
        );
      }
      return original(command, args);
    });
    renderClientApp();
    await user.click(await screen.findByRole("button", { name: "服务器设置" }));
    await user.click(screen.getByRole("button", { name: "保存服务器地址" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "服务器地址未保存",
    );
    expect(screen.queryByText(/地址已同步/)).not.toBeInTheDocument();
    expect(toast.success).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "登录系统" })).toBeEnabled();
    expect(screen.getByLabelText("服务器地址")).toBeEnabled();

    rejected = false;
    await user.click(screen.getByRole("button", { name: "保存服务器地址" }));
    expect(await screen.findByText(/地址已同步/)).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("keeps server synchronization disabled while a login is in progress", async () => {
    const user = userEvent.setup();
    let completeLogin!: (session: typeof signedIn) => void;
    const pendingLogin = new Promise<typeof signedIn>((resolve) => {
      completeLogin = resolve;
    });
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) =>
      command === "system_login" ? pendingLogin : original(command, args),
    );
    renderClientApp();
    await user.click(await screen.findByRole("button", { name: "服务器设置" }));
    await user.type(screen.getByLabelText("系统账号"), "alice@example.com");
    await user.type(screen.getByLabelText("密码"), "password");
    await user.click(screen.getByRole("button", { name: "登录系统" }));

    expect(
      screen.getByRole("button", { name: "保存服务器地址" }),
    ).toBeDisabled();
    expect(screen.getByLabelText("服务器地址")).toBeDisabled();
    expect(invokeMock).not.toHaveBeenCalledWith(
      "server_config_save",
      expect.anything(),
    );

    await act(async () => completeLogin(signedIn));
    expect(
      await screen.findByRole("heading", { name: "个人 Token" }),
    ).toBeVisible();
  });
});
