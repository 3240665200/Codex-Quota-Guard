import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { toast } from "sonner";

import { ClientApp } from "@/components/client-app";
import { api, type StartupGateResult, type SystemSession } from "@/lib/api";
import i18n from "@/lib/i18n";

vi.mock("@/components/dashboard", () => ({
  Dashboard: ({
    session,
    onLogout,
    guardNotice,
  }: {
    session: SystemSession;
    onLogout: () => void;
    guardNotice: ReactNode;
  }) => (
    <section aria-label="Quota Guard dashboard">
      <h1>Test dashboard</h1>
      <p>{session.user?.email}</p>
      {guardNotice}
      <button onClick={onLogout}>退出系统</button>
    </section>
  ),
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}));

const signedOut: SystemSession = {
  authenticated: false,
  user: null,
};

const signedIn: SystemSession = {
  authenticated: true,
  user: {
    id: "11111111-1111-4111-8111-111111111111",
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
};

const initialServerConfig = { url: "http://127.0.0.1:8080" };

function gate(
  state: StartupGateResult["state"],
  values: Partial<StartupGateResult> = {},
): StartupGateResult {
  return {
    state,
    ccSwitchTerminated: false,
    ...values,
  };
}

function renderClientApp() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  });
  const rendered = render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <ClientApp />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  return { ...rendered, queryClient };
}

beforeEach(() => {
  vi.spyOn(api, "startupGateCheck").mockResolvedValue(gate("GUARD_ERROR"));
  vi.spyOn(api, "startupGuardStatus").mockResolvedValue(gate("GUARD_ERROR"));
  vi.spyOn(api, "systemSession").mockResolvedValue(signedOut);
  vi.spyOn(api, "systemLogin").mockResolvedValue(signedIn);
  vi.spyOn(api, "systemLogout").mockResolvedValue(undefined);
  vi.spyOn(api, "serverConfig").mockResolvedValue(initialServerConfig);
  vi.spyOn(api, "saveServerConfig").mockImplementation(async (config) => ({
    url: config.url,
    meterSync: "UNCHANGED",
  }));
  vi.spyOn(api, "meterPairingError").mockReturnValue(null);
  vi.mocked(toast.success).mockClear();
  vi.mocked(toast.warning).mockClear();
  vi.mocked(toast.error).mockClear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ClientApp", () => {
  it("restores an encrypted system session directly into the dashboard", async () => {
    vi.mocked(api.systemSession).mockResolvedValue(signedIn);

    renderClientApp();

    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
    expect(screen.getByText("alice@example.com")).toBeVisible();
    expect(api.startupGateCheck).toHaveBeenCalledOnce();
    expect(api.systemLogin).not.toHaveBeenCalled();
  });

  it("keeps login and the restored dashboard available while the optional guard check is pending", async () => {
    vi.mocked(api.startupGateCheck).mockImplementation(
      () => new Promise<StartupGateResult>(() => {}),
    );
    vi.mocked(api.systemSession).mockResolvedValue(signedIn);

    renderClientApp();

    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
    expect(api.systemSession).toHaveBeenCalledOnce();
  });

  it("keeps login available after a central-session read fails and accepts a successful login", async () => {
    const user = userEvent.setup();
    vi.mocked(api.systemSession).mockRejectedValueOnce(
      new Error("central server is offline"),
    );

    renderClientApp();

    expect(await screen.findByText("无法验证登录状态")).toBeVisible();
    await user.type(
      await screen.findByLabelText("系统账号"),
      "alice@example.com",
    );
    await user.type(screen.getByLabelText("密码"), "correct-password");
    await user.click(screen.getByRole("button", { name: "登录系统" }));

    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
    expect(api.systemLogin).toHaveBeenCalledWith(
      "alice@example.com",
      "correct-password",
    );
  });

  it("keeps the dashboard mounted and offers a retry after a background session refresh fails", async () => {
    const user = userEvent.setup();
    vi.mocked(api.systemSession).mockResolvedValue(signedIn);
    const { queryClient } = renderClientApp();
    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();

    vi.mocked(api.systemSession).mockRejectedValueOnce(
      new Error("central server is offline"),
    );
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ["system-session"] });
    });

    expect(await screen.findByText(/登录状态暂时无法刷新/)).toBeVisible();
    expect(
      screen.getByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();

    vi.mocked(api.systemSession).mockResolvedValue(signedIn);
    await user.click(screen.getByRole("button", { name: "重试登录状态" }));
    await waitFor(() =>
      expect(
        screen.queryByText(/登录状态暂时无法刷新/),
      ).not.toBeInTheDocument(),
    );
    expect(
      screen.getByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
  });

  it("shows CC Switch protection as unavailable without blocking an authenticated dashboard", async () => {
    vi.mocked(api.startupGateCheck).mockResolvedValue(gate("GUARD_ERROR"));
    vi.mocked(api.systemSession).mockResolvedValue(signedIn);

    renderClientApp();

    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
    expect(
      screen.getByText(/CC Switch 冲突保护暂不可用，客户端仍可正常登录和使用/),
    ).toBeVisible();
  });

  it("shows confirmed CC Switch recovery in the persistent notice without a duplicate toast", async () => {
    vi.mocked(api.startupGateCheck).mockResolvedValue(
      gate("READY", {
        ccSwitchTerminated: true,
        codexConnectionRestored: true,
      }),
    );
    vi.mocked(api.systemSession).mockResolvedValue(signedIn);

    renderClientApp();

    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
    expect(
      await screen.findByText(/已停止 CC Switch。已恢复 Codex 连接配置。/),
    ).toBeVisible();
    expect(toast.warning).not.toHaveBeenCalled();
  });

  it("switches servers from the login page and explains when the meter is not installed", async () => {
    const user = userEvent.setup();
    vi.mocked(api.saveServerConfig).mockResolvedValue({
      url: "https://quota.example.com",
      meterSync: "NOT_INSTALLED",
    });

    renderClientApp();
    await user.click(await screen.findByRole("button", { name: "服务器设置" }));
    const address = await screen.findByLabelText("服务器地址");
    await user.clear(address);
    await user.type(address, "https://quota.example.com");
    await user.click(screen.getByRole("button", { name: "保存服务器地址" }));

    expect(api.saveServerConfig).toHaveBeenCalledWith({
      url: "https://quota.example.com",
    });
    expect(
      await screen.findByText(/统计程序未安装，请重新运行客户端安装器/),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "登录系统" })).toBeVisible();
  });

  it("clears authenticated views and local cached data after a session-expired event", async () => {
    vi.mocked(api.systemSession).mockResolvedValue(signedIn);
    const { queryClient } = renderClientApp();
    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
    queryClient.setQueryData(["quota-overview"], { cached: true });
    queryClient.setQueryData(["usage-events"], { cached: true });

    await act(async () => {
      window.dispatchEvent(new Event("quota-guard:session-expired"));
    });

    expect(await screen.findByText("登录 Quota Guard")).toBeVisible();
    expect(queryClient.getQueryData(["quota-overview"])).toBeUndefined();
    expect(queryClient.getQueryData(["usage-events"])).toBeUndefined();
    expect(queryClient.getQueryData(["startup-gate"])).toBeDefined();
    expect(api.systemLogout).not.toHaveBeenCalled();
  });

  it("clears local session data and returns to login even when central logout fails", async () => {
    const user = userEvent.setup();
    vi.mocked(api.systemSession).mockResolvedValue(signedIn);
    vi.mocked(api.systemLogout).mockRejectedValue(
      new Error("central server is offline"),
    );
    const { queryClient } = renderClientApp();
    expect(
      await screen.findByRole("heading", { name: "Test dashboard" }),
    ).toBeVisible();
    queryClient.setQueryData(["quota-overview"], { cached: true });
    queryClient.setQueryData(["usage-events"], { cached: true });

    await user.click(screen.getByRole("button", { name: "退出系统" }));

    expect(await screen.findByText("登录 Quota Guard")).toBeVisible();
    expect(api.systemLogout).toHaveBeenCalledOnce();
    expect(queryClient.getQueryData(["quota-overview"])).toBeUndefined();
    expect(queryClient.getQueryData(["usage-events"])).toBeUndefined();
    expect(toast.warning).toHaveBeenCalledWith(
      expect.stringContaining("中央后台未确认退出"),
    );
  });

  it("keeps password visibility separate from the entered password", async () => {
    const user = userEvent.setup();
    renderClientApp();
    const password = await screen.findByLabelText("密码");
    await user.type(password, "correct-password");

    await user.click(screen.getByRole("button", { name: "显示密码" }));
    expect(password).toHaveAttribute("type", "text");
    expect(password).toHaveValue("correct-password");

    await user.click(screen.getByRole("button", { name: "隐藏密码" }));
    expect(password).toHaveAttribute("type", "password");
  });
});
