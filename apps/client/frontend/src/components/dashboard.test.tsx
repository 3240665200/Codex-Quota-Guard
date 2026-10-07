import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@tauri-apps/api/core";

import { Dashboard } from "@/components/dashboard";
import type { QuotaOverview, StatusResponse, SystemSession } from "@/lib/api";
import i18n from "@/lib/i18n";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const invokeMock = vi.mocked(invoke);

const status: StatusResponse = {
  service: "codex-quota-guard",
  state: "online",
};
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
const quota: QuotaOverview = {
  user: session.user!,
  team: { id: "team-1", name: "Design" },
  policy: {
    fiveHourTokenLimit: 1_000,
    weeklyTokenLimit: 10_000,
    fiveHourSource: "TEAM",
    weeklySource: "TEAM",
  },
  usage: {
    measurementStatus: "CLIENT_REPORTED",
    fiveHourUsedTokens: 800,
    weeklyUsedTokens: 4_500,
    fiveHourRemainingTokens: 200,
    weeklyRemainingTokens: 5_500,
    fiveHourResetsAt: null,
    weeklyResetsAt: null,
    measuredAt: "2026-10-06T08:00:00Z",
  },
  allowed: true,
  blockReason: null,
};

function renderDashboard() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <Dashboard session={session} onLogout={vi.fn()} />
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

describe("Dashboard", () => {
  beforeEach(() => {
    invokeMock.mockImplementation(async (command) => {
      switch (command) {
        case "status":
          return status;
        case "quota_overview":
          return quota;
        case "codex_rate_limits":
          return {
            planType: "pro",
            windows: [
              {
                limitId: "codex",
                slot: "primary",
                windowDurationMins: 300,
                usedPercent: 25,
                resetsAt: "2026-10-06T12:00:00Z",
              },
            ],
            fetchedAt: "2026-10-06T08:00:00Z",
          };
        case "usage_status":
        case "usage_scan":
        case "usage_pair":
          return {
            fallback: { state: "BASELINE_READY", lastScannedAt: "2026-10-07T00:00:00Z",
              lastReportedAt: null, lastReportedEvents: 0, issues: [] },
            service: { state: "NOT_INSTALLED", lastScanEpochSeconds: null,
              lastReportEpochSeconds: null, reportedEvents: 0, errorCode: null },
            pairingState: "NOT_INSTALLED",
          };
        default:
          throw new Error(`Unexpected command: ${command}`);
      }
    });
  });

  it("renders personal token limits separately from official account reference windows", async () => {
    renderDashboard();

    expect(
      await screen.findByRole("heading", { name: "额度仪表盘" }),
    ).toBeVisible();
    expect(await screen.findByText(/请在官方 Codex 应用或 CLI 中自行登录/)).toBeVisible();
    expect(await screen.findByText("Design")).toBeVisible();
    expect(screen.getByText("个人 Token 限额")).toBeVisible();
    expect(screen.getByText(/Rust 常驻计费服务未安装/)).toBeVisible();
    expect(screen.getByText("1,000 Token")).toBeVisible();
    expect(screen.getByText("800 Token 已上报")).toBeVisible();
    expect(screen.getByText("200 Token 剩余")).toBeVisible();
    expect(screen.getByText("官方 Codex 账户额度（参考）")).toBeVisible();
    expect(await screen.findByText("25% 已使用")).toBeVisible();
    expect(screen.getByText(/绝不会被当作你的个人 Token 用量/)).toBeVisible();
    expect(new Set(invokeMock.mock.calls.map(([command]) => command))).toEqual(
      new Set([
        "status",
        "quota_overview",
        "codex_rate_limits",
        "usage_status",
      ]),
    );
  });

  it("does not offer credential download or managed Codex login", async () => {
    renderDashboard();
    await screen.findByText(/请在官方 Codex 应用或 CLI 中自行登录/);
    expect(screen.queryByRole("button", { name: "登录 Codex" })).not.toBeInTheDocument();
    expect(invokeMock).not.toHaveBeenCalledWith("codex_login");
  });

  it("offers a safe retry when the Rust service is waiting for pairing", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") return quota;
      if (command === "codex_rate_limits") return { planType: null, windows: [], fetchedAt: null };
      if (command === "usage_status" || command === "usage_pair") return {
        fallback: { state: "UP_TO_DATE", lastScannedAt: "2026-10-07T00:00:00Z",
          lastReportedAt: null, lastReportedEvents: 0, issues: [] },
        service: { state: "UNPAIRED", lastScanEpochSeconds: 1791300000,
          lastReportEpochSeconds: null, reportedEvents: 0, errorCode: null },
        pairingState: "PAIRING_FAILED",
      };
      throw new Error(`Unexpected command: ${command}`);
    });
    renderDashboard();

    await user.click(await screen.findByRole("button", { name: "重新配对" }));

    expect(invokeMock).toHaveBeenCalledWith("usage_pair");
  });

  it("warns when personal quota is exhausted without claiming official logout", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") {
        return {
          ...quota,
          usage: {
            ...quota.usage,
            fiveHourRemainingTokens: 0,
          },
          allowed: false,
          blockReason: "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED",
        };
      }
      if (command === "codex_rate_limits") {
        return { planType: null, windows: [], fetchedAt: null };
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderDashboard();

    expect(await screen.findByText("个人 Token 额度已用尽")).toBeVisible();
    expect(
      screen.getByText("已达到个人 5 小时 Token 限额。", { exact: false }),
    ).toBeVisible();
    expect(
      screen.getByText(/本应用不会退出官方 Codex 应用或 CLI/),
    ).toBeVisible();
    expect(screen.queryByRole("button", { name: "登录 Codex" })).not.toBeInTheDocument();
  });

  it("labels an unconfigured personal limit without calling it exhausted", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") return {
        ...quota,
        policy: { ...quota.policy, fiveHourTokenLimit: null, fiveHourSource: "UNCONFIGURED" },
        usage: { ...quota.usage, fiveHourRemainingTokens: null },
        allowed: false,
        blockReason: "TOKEN_QUOTA_UNCONFIGURED",
      };
      if (command === "codex_rate_limits") return { planType: null, windows: [], fetchedAt: null };
      throw new Error(`Unexpected command: ${command}`);
    });

    renderDashboard();

    expect(await screen.findByText("个人 Token 额度未配置")).toBeVisible();
    expect(screen.queryByText("个人 Token 额度已用尽")).not.toBeInTheDocument();
  });

  it("shows a useful error state when the local service is unavailable", async () => {
    invokeMock.mockRejectedValue(new Error("service unavailable"));

    renderDashboard();

    await waitFor(() => {
      const serviceAlert = screen
        .getAllByRole("alert")
        .find((alert) => alert.textContent?.includes("本地服务不可用"));
      expect(serviceAlert).toBeDefined();
      expect(serviceAlert).toHaveTextContent("本地服务不可用");
    });
    expect(screen.getByRole("button", { name: "重试" })).toBeVisible();
  });
});
