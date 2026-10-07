import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@tauri-apps/api/core";

import { ClientApp } from "@/components/client-app";
import i18n from "@/lib/i18n";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const invokeMock = vi.mocked(invoke);

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
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <ClientApp />
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

describe("ClientApp system session gate", () => {
  beforeEach(() => {
    invokeMock.mockImplementation(async (command) => {
      switch (command) {
        case "system_current":
          return signedOut;
        case "server_config_get":
          return { url: "http://127.0.0.1:8080", allowInsecureLanHttp: false };
        case "server_config_save":
          return {
            url: "http://192.168.0.102:8080",
            allowInsecureLanHttp: true,
          };
        case "system_login":
          return signedIn;
        case "system_logout":
          return undefined;
        case "status":
          return { service: "codex-quota-guard", state: "online" };
        case "quota_overview":
          return quotaOverview;
        case "usage_status":
          return {
            fallback: { state: "UP_TO_DATE", lastScannedAt: "2026-10-07T00:00:00Z",
              lastReportedAt: null, lastReportedEvents: 0, issues: [] },
            service: { state: "NOT_INSTALLED", lastScanEpochSeconds: null,
              lastReportEpochSeconds: null, reportedEvents: 0, errorCode: null },
            pairingState: "NOT_INSTALLED",
          };
        case "codex_rate_limits":
          return { planType: null, windows: [], fetchedAt: null };
        default:
          throw new Error(`Unexpected command: ${command}`);
      }
    });
  });

  it("requires the product system account before rendering the dashboard", async () => {
    renderClientApp();

    expect(await screen.findByText("登录 Quota Guard")).toBeVisible();
    expect(screen.queryByText("登录 Codex")).toBeNull();
    expect(screen.queryByText("上传 Codex 凭证")).toBeNull();
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

  it("lets a signed-out user save a LAN server address before login", async () => {
    const user = userEvent.setup();
    renderClientApp();

    await user.click(await screen.findByRole("button", { name: "服务器设置" }));
    const address = screen.getByLabelText("服务器地址");
    await waitFor(() => expect(address).toHaveValue("http://127.0.0.1:8080"));
    await user.clear(address);
    await user.type(address, "http://192.168.0.102:8080");
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "保存服务器地址" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("server_config_save", {
        config: {
          url: "http://192.168.0.102:8080",
          allowInsecureLanHttp: true,
        },
      });
    });
    expect(await screen.findByRole("status")).toHaveTextContent(
      "重新打开客户端",
    );
    expect(screen.getByRole("button", { name: "登录系统" })).toBeDisabled();
  });
});
