import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
  assigned: true,
  user: session.user!,
  codexAccount: {
    id: "codex-1",
    displayName: "Managed Codex",
    status: "ACTIVE",
    updatedAt: "2026-09-21T08:00:00Z",
  },
  team: { id: "team-1", name: "Design" },
  policy: {
    fiveHourLimitPercent: 80,
    weeklyLimitPercent: 90,
    fiveHourSource: "TEAM",
    weeklySource: "DEFAULT",
  },
  usage: {
    measurementStatus: "CURRENT",
    fiveHourUsedPercent: 80,
    weeklyUsedPercent: 45,
    fiveHourRemainingPercent: 20,
    weeklyRemainingPercent: 55,
    fiveHourResetsAt: "2026-09-21T12:00:00Z",
    weeklyResetsAt: "2026-09-28T08:00:00Z",
    measuredAt: "2026-09-21T08:00:00Z",
  },
  allowed: false,
  blockReason: "FIVE_HOUR_QUOTA_EXCEEDED",
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
        default:
          throw new Error(`Unexpected command: ${command}`);
      }
    });
  });

  it("renders the assigned account, team, limits, measured usage, and block reason", async () => {
    renderDashboard();

    expect(
      await screen.findByRole("heading", { name: "额度仪表盘" }),
    ).toBeVisible();
    expect(await screen.findByText("Managed Codex · 启用")).toBeVisible();
    expect(await screen.findByText("Design")).toBeVisible();
    expect(screen.getByText("5 小时限额")).toBeVisible();
    expect(screen.getByText("7 天限额")).toBeVisible();
    expect(screen.getByText("来源：团队策略")).toBeVisible();
    expect(screen.getByText("来源：默认策略")).toBeVisible();
    expect(screen.getByText("80% 已使用")).toBeVisible();
    expect(screen.getByText("20% 剩余")).toBeVisible();
    expect(screen.getByText("已超过 5 小时额度限制。")).toBeVisible();
    expect(new Set(invokeMock.mock.calls.map(([command]) => command))).toEqual(
      new Set(["status", "quota_overview"]),
    );
  });

  it("shows unmeasured usage without presenting a zero-value fallback", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quota_overview") {
        return {
          ...quota,
          usage: {
            measurementStatus: "NOT_MEASURED",
            fiveHourUsedPercent: null,
            weeklyUsedPercent: null,
            fiveHourRemainingPercent: null,
            weeklyRemainingPercent: null,
            fiveHourResetsAt: null,
            weeklyResetsAt: null,
            measuredAt: null,
          },
          allowed: false,
          blockReason: "USAGE_NOT_MEASURED",
        };
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderDashboard();

    expect(
      await screen.findAllByText("尚未采集（暂无服务端用量）"),
    ).not.toHaveLength(0);
    expect(screen.queryByText("0% 已使用")).toBeNull();
    expect(screen.getByText("当前额度暂不可用")).toBeVisible();
  });

  it("does not expose a local Codex login entry point", async () => {
    const user = userEvent.setup();
    renderDashboard();

    expect(screen.queryByRole("button", { name: "登录 Codex" })).toBeNull();
    expect(screen.queryByText("设备登录已创建")).toBeNull();
    await user.click(await screen.findByRole("button", { name: "退出系统" }));
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
