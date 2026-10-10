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
  teamQuota: {
    id: "team-1",
    name: "Design",
    status: "ACTIVE",
    memberCount: 2,
    fiveHourTokenLimit: 1_000,
    weeklyTokenLimit: 10_000,
    totalTokenLimit: 20_000,
    fiveHourUsedTokens: 900,
    weeklyUsedTokens: 4_700,
    totalUsedTokens: 10_000,
    fiveHourRemainingTokens: 100,
    weeklyRemainingTokens: 5_300,
    totalRemainingTokens: 10_000,
    measuredAt: "2026-10-06T08:00:00Z",
  },
  policy: {
    fiveHourTokenLimit: 1_000,
    weeklyTokenLimit: 10_000,
    fiveHourSource: "USER",
    weeklySource: "USER",
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
        case "usage_events":
          return {
            items: [],
            page: 0,
            size: 10,
            totalElements: 0,
            totalPages: 0,
          };
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
            fallback: {
              state: "BASELINE_READY",
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
    expect(
      await screen.findByRole("heading", { name: "个人 Token" }),
    ).toBeVisible();
    expect(await screen.findByText(/Design/)).toBeVisible();
    expect(screen.getByRole("heading", { name: "团队共享额度" })).toBeVisible();
    expect(screen.getByText(/计费服务未安装/)).toBeVisible();
    expect(screen.getAllByTitle("精确值：1,000 Token").length).toBeGreaterThan(
      0,
    );
    expect(
      screen.getByTitle("精确值：800 Token").parentElement,
    ).toHaveTextContent("个人已用");
    expect(screen.getAllByTitle("精确值：100 Token").length).toBeGreaterThan(0);
    expect(
      screen.getByTitle("精确值：900 Token").parentElement,
    ).toHaveTextContent("已用");
    expect(
      screen.getByRole("heading", { name: "官方 Codex 额度" }),
    ).toBeVisible();
    expect(await screen.findByText("25%")).toBeVisible();
    expect(
      screen.getByRole("progressbar", { name: "5 小时个人额度使用进度" }),
    ).toHaveAttribute("aria-valuenow", "80");
    expect(
      screen.getByRole("progressbar", { name: "5 小时团队使用进度" }),
    ).toHaveAttribute("aria-valuenow", "90");
    expect(
      screen.getByRole("progressbar", { name: "累计总额使用进度" }),
    ).toHaveAttribute("aria-valuenow", "50");
    expect(
      screen.getByRole("progressbar", { name: "5 小时窗口官方使用进度" }),
    ).toHaveAttribute("aria-valuenow", "25");
    expect(new Set(invokeMock.mock.calls.map(([command]) => command))).toEqual(
      new Set([
        "status",
        "quota_overview",
        "codex_rate_limits",
        "usage_status",
        "usage_events",
      ]),
    );
  });

  it("does not offer credential download or managed Codex login", async () => {
    renderDashboard();
    await screen.findByRole("heading", { name: "官方 Codex 额度" });
    expect(
      screen.queryByRole("button", { name: "登录 Codex" }),
    ).not.toBeInTheDocument();
    expect(invokeMock).not.toHaveBeenCalledWith("codex_login");
  });

  it("only displays official quota windows returned by the local account", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") return quota;
      if (command === "usage_status")
        return {
          fallback: {
            state: "UP_TO_DATE",
            lastScannedAt: null,
            lastReportedAt: null,
            lastReportedEvents: 0,
            issues: [],
          },
          service: {
            state: "UP_TO_DATE",
            lastScanEpochSeconds: null,
            lastReportEpochSeconds: null,
            reportedEvents: 0,
            errorCode: null,
          },
          pairingState: "PAIRING_SENT",
        };
      if (command === "codex_rate_limits")
        return {
          planType: "business",
          windows: [
            {
              limitId: "codex",
              slot: "weekly",
              windowDurationMins: 10_080,
              usedPercent: 42,
              resetsAt: null,
            },
          ],
          fetchedAt: "2026-10-06T08:00:00Z",
        };
      throw new Error(`Unexpected command: ${command}`);
    });

    renderDashboard();

    expect(
      await screen.findByRole("progressbar", { name: "7 天窗口官方使用进度" }),
    ).toBeVisible();
    expect(screen.queryByText("5 小时窗口")).not.toBeInTheDocument();
  });

  it("offers a safe retry when the Rust service is waiting for pairing", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") return quota;
      if (command === "codex_rate_limits")
        return { planType: null, windows: [], fetchedAt: null };
      if (command === "usage_status" || command === "usage_pair")
        return {
          fallback: {
            state: "UP_TO_DATE",
            lastScannedAt: "2026-10-07T00:00:00Z",
            lastReportedAt: null,
            lastReportedEvents: 0,
            issues: [],
          },
          service: {
            state: "UNPAIRED",
            lastScanEpochSeconds: 1791300000,
            lastReportEpochSeconds: null,
            reportedEvents: 0,
            errorCode: null,
          },
          pairingState: "PAIRING_FAILED",
        };
      throw new Error(`Unexpected command: ${command}`);
    });
    renderDashboard();

    await user.click(await screen.findByRole("button", { name: "重新配对" }));

    expect(invokeMock).toHaveBeenCalledWith("usage_pair");
  });

  it("offers a fixed native retry when the installed Rust service is offline", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") return quota;
      if (command === "codex_rate_limits")
        return { planType: null, windows: [], fetchedAt: null };
      if (command === "meter_service_retry") return "STARTED";
      if (command === "usage_status")
        return {
          fallback: {
            state: "UP_TO_DATE",
            lastScannedAt: "2026-10-07T00:00:00Z",
            lastReportedAt: null,
            lastReportedEvents: 0,
            issues: [],
          },
          service: {
            state: "OFFLINE",
            lastScanEpochSeconds: null,
            lastReportEpochSeconds: null,
            reportedEvents: 0,
            errorCode: null,
          },
          pairingState: "PAIRING_SENT",
        };
      throw new Error(`Unexpected command: ${command}`);
    });
    renderDashboard();

    await user.click(
      await screen.findByRole("button", { name: "启动计费服务" }),
    );
    expect(invokeMock).toHaveBeenCalledWith("meter_service_retry");
  });

  it("offers first-run installation when the Rust service is missing", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") return quota;
      if (command === "codex_rate_limits")
        return { planType: null, windows: [], fetchedAt: null };
      if (command === "meter_service_retry") return "INSTALLED";
      if (command === "usage_status")
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
      throw new Error(`Unexpected command: ${command}`);
    });
    renderDashboard();

    await user.click(
      await screen.findByRole("button", { name: "安装计费服务" }),
    );
    expect(invokeMock).toHaveBeenCalledWith("meter_service_retry");
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

    expect(await screen.findByText("Token 额度已用尽")).toBeVisible();
    expect(
      screen.getByText("已达到个人 5 小时 Token 限额。", { exact: false }),
    ).toBeVisible();
    expect(
      screen.getByText(/本应用不会退出官方 Codex 应用或 CLI/),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "登录 Codex" }),
    ).not.toBeInTheDocument();
  });

  it("identifies an exhausted shared team window", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview")
        return {
          ...quota,
          policy: { ...quota.policy, fiveHourSource: "USER" },
          teamQuota: {
            ...quota.teamQuota,
            fiveHourUsedTokens: 1_000,
            fiveHourRemainingTokens: 0,
          },
          usage: { ...quota.usage, fiveHourRemainingTokens: 200 },
          allowed: false,
          blockReason: "TEAM_FIVE_HOUR_TOKEN_QUOTA_EXCEEDED",
        };
      if (command === "codex_rate_limits")
        return { planType: null, windows: [], fetchedAt: null };
      if (command === "usage_status")
        return {
          fallback: {
            state: "UP_TO_DATE",
            lastScannedAt: null,
            lastReportedAt: null,
            lastReportedEvents: 0,
            issues: [],
          },
          service: {
            state: "UP_TO_DATE",
            lastScanEpochSeconds: null,
            lastReportEpochSeconds: null,
            reportedEvents: 0,
            errorCode: null,
          },
          pairingState: "PAIRING_SENT",
        };
      throw new Error(`Unexpected command: ${command}`);
    });
    renderDashboard();
    expect(
      await screen.findByText(/所属团队的 5 小时共享 Token 额度已用尽/),
    ).toBeVisible();
    expect(
      screen.getByRole("progressbar", { name: "5 小时团队使用进度" }),
    ).toHaveAttribute("aria-valuenow", "100");
    expect(
      screen.getByRole("progressbar", { name: "5 小时个人额度使用进度" }),
    ).toHaveAttribute("aria-valuenow", "80");
  });

  it("treats an absent five-hour limit as unlimited", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview")
        return {
          ...quota,
          policy: {
            ...quota.policy,
            fiveHourTokenLimit: null,
            fiveHourSource: "UNCONFIGURED",
          },
          usage: { ...quota.usage, fiveHourRemainingTokens: null },
          teamQuota: {
            ...quota.teamQuota,
            fiveHourTokenLimit: null,
            fiveHourRemainingTokens: null,
          },
          allowed: true,
          blockReason: null,
        };
      if (command === "codex_rate_limits")
        return { planType: null, windows: [], fetchedAt: null };
      throw new Error(`Unexpected command: ${command}`);
    });

    renderDashboard();

    expect(await screen.findByText("额度可用")).toBeVisible();
    expect(screen.queryByText("Token 额度已用尽")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("progressbar", { name: "5 小时个人额度使用进度" }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByText("不设限").length).toBeGreaterThan(0);
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
