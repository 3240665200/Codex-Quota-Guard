import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
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
let quotaResponse = quota;

function renderDashboard(
  guardNotice?: string,
  currentSession: SystemSession = session,
) {
  const currentImplementation = invokeMock.getMockImplementation()!;
  invokeMock.mockImplementation(async (command, args) =>
    command === "session_binding_current"
      ? { attempt: null, binding: null }
      : currentImplementation(command, args),
  );
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <Dashboard
          session={currentSession}
          onLogout={vi.fn()}
          guardNotice={guardNotice}
        />
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

describe("Dashboard", () => {
  it("does not show the administrator login helper to a regular system user", async () => {
    renderDashboard();
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "启动设置" });
    expect(screen.queryByRole("heading", { name: "官方登录助手" })).toBeNull();
    expect(
      invokeMock.mock.calls.some(
        ([name]) => name === "official_login_helper_jobs",
      ),
    ).toBe(false);
  });
  it("shows the administrator helper in settings without starting a browser", async () => {
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (name, args) => {
      if (name === "official_login_helper_jobs") return [];
      if (name === "official_login_helper_status")
        return {
          jobId: null,
          state: "IDLE",
          errorCode: null,
          accountName: null,
        };
      return original(name, args);
    });
    renderDashboard(undefined, {
      ...session,
      user: { ...session.user!, role: "ADMIN" },
    });
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "官方登录助手" });
    expect(
      invokeMock.mock.calls.some(
        ([name]) => name === "official_login_helper_start",
      ),
    ).toBe(false);
  });
  it("keeps binding and login assistance identities separate when changing pages", async () => {
    const warnings = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      renderDashboard();
      await screen.findByRole("heading", { name: "个人 Token" });
      fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
      await screen.findByRole("heading", { name: "监听登录邮件" });
      expect(warnings.mock.calls.flat().join(" ")).not.toContain("same key");
    } finally {
      warnings.mockRestore();
    }
  });
  beforeEach(() => {
    invokeMock.mockClear();
    quotaResponse = quota;
    invokeMock.mockImplementation(async (command) => {
      switch (command) {
        case "status":
          return status;
        case "quota_overview":
          return quotaResponse;
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
        case "meter_service_version":
          return {
            installedVersion: null,
            bundledVersion: "0.1.1",
            needsUpgrade: null,
            status: "NOT_INSTALLED",
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
    expect(screen.getByText(/统计程序未安装/)).toBeVisible();
    expect(screen.getAllByTitle("精确值：1,000 Token").length).toBeGreaterThan(
      0,
    );
    const personalFiveHour = screen
      .getByRole("heading", { name: "5 小时" })
      .closest("article")!;
    expect(within(personalFiveHour).getByText("个人剩余")).toBeVisible();
    expect(screen.getByRole("heading", { name: "个人 Token" })).toHaveAttribute(
      "title",
      "个人额度按事件发生时间滚动统计",
    );
    expect(screen.getAllByTitle("精确值：100 Token").length).toBeGreaterThan(0);
    expect(
      screen.getByTitle("精确值：100 Token").parentElement,
    ).toHaveTextContent("剩余");
    expect(
      screen.getByRole("heading", { name: "官方 Codex 额度" }),
    ).toBeVisible();
    expect(await screen.findByText("剩余 75%")).toBeVisible();
    expect(
      screen.getByRole("progressbar", { name: "5 小时个人额度剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "20");
    expect(
      screen.getByRole("progressbar", { name: "5 小时团队剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "10");
    expect(
      screen.getByRole("progressbar", { name: "累计总额剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "50");
    expect(
      screen.getByRole("progressbar", { name: "5 小时窗口官方剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "75");
    expect(new Set(invokeMock.mock.calls.map(([command]) => command))).toEqual(
      new Set([
        "session_binding_current",
        "status",
        "quota_overview",
        "codex_rate_limits",
        "usage_status",
        "meter_service_version",
      ]),
    );
  });

  it("keeps personal windows first and places official quota before shared team quota", async () => {
    renderDashboard();
    await screen.findByRole("heading", { name: "个人 Token" });
    expect(
      screen
        .getAllByRole("heading", { level: 2 })
        .map((heading) => heading.textContent),
    ).toEqual([
      "本设备官方会话绑定",
      "个人 Token",
      "官方 Codex 额度",
      "团队共享额度",
    ]);
    const official = screen.getByRole("region", { name: "官方 Codex 额度" });
    const team = screen.getByRole("region", { name: "团队共享额度" });
    expect(official.nextElementSibling).toBe(team);
    expect(official.parentElement?.previousElementSibling).toBe(
      screen.getByRole("region", { name: "个人 Token" }),
    );
  });

  it("aligns unlimited and capped personal windows while preserving their quota semantics", async () => {
    quotaResponse = {
      ...quota,
      policy: {
        ...quota.policy,
        fiveHourTokenLimit: null,
        fiveHourSource: "UNCONFIGURED",
      },
      usage: { ...quota.usage, fiveHourRemainingTokens: null },
      allowed: true,
      blockReason: null,
    };

    renderDashboard();

    await screen.findByRole("heading", { name: "个人 Token" });
    const fiveHour = screen
      .getByRole("heading", { name: "5 小时" })
      .closest("article")!;
    const weekly = screen
      .getByRole("heading", { name: "7 天" })
      .closest("article")!;
    for (const window of [fiveHour, weekly]) {
      expect(window.children).toHaveLength(3);
      expect(window.children[0]).toHaveClass("cqg-usage-window-header");
      expect(window.children[1]).toHaveClass("cqg-usage-window-primary");
      expect(window.children[2]).toHaveClass("cqg-usage-window-footer");
    }

    const unlimitedValue = within(fiveHour).getByText("不限额");
    expect(
      unlimitedValue.closest(".cqg-usage-window-primary"),
    ).toHaveTextContent("个人剩余");
    expect(
      within(fiveHour).queryByTitle("精确值：800 Token"),
    ).not.toBeInTheDocument();
    expect(
      within(fiveHour).getByText("本窗口只记录用量，不参与个人限额判断"),
    ).toBeVisible();
    expect(fiveHour.querySelector('[role="progressbar"]')).toBeNull();

    const cappedValue = within(weekly).getByTitle("精确值：5,500 Token");
    expect(cappedValue.closest(".cqg-usage-window-primary")).toHaveTextContent(
      "个人剩余",
    );
    expect(
      within(weekly).getByRole("progressbar", {
        name: "7 天个人额度剩余额度",
      }),
    ).toHaveAttribute("aria-valuenow", "55");
    const weeklyMetrics = weekly.querySelector(".cqg-usage-window-metrics");
    expect(weeklyMetrics).not.toHaveTextContent("个人已用");
    expect(weeklyMetrics).toHaveTextContent("上限 1万 Token");
    expect(within(weekly).getByTitle("精确值：10,000 Token")).toBeVisible();

    const team = screen.getByRole("region", { name: "团队共享额度" });
    const official = screen.getByRole("region", {
      name: "官方 Codex 额度",
    });
    const teamHeader = team.querySelector(".cqg-summary-card-header");
    const officialHeader = official.querySelector(".cqg-summary-card-header");
    expect(teamHeader).not.toBeNull();
    expect(officialHeader).not.toBeNull();
    expect(teamHeader?.nextElementSibling).toHaveClass(
      "cqg-summary-card-content",
    );
    expect(officialHeader?.nextElementSibling).toHaveClass(
      "cqg-summary-card-content",
    );
  });

  it.each([
    { limit: 0, used: 0, remaining: 0, percent: 0 },
    { limit: 1_000, used: 1_000, remaining: 0, percent: 0 },
    { limit: 1_000, used: 1_500, remaining: 0, percent: 0 },
    { limit: 1_000, used: 0, remaining: 1_000, percent: 100 },
  ])(
    "shows remaining quota for limit $limit and usage $used",
    async ({ limit, used, remaining, percent }) => {
      quotaResponse = {
        ...quota,
        policy: { ...quota.policy, fiveHourTokenLimit: limit },
        usage: {
          ...quota.usage,
          fiveHourUsedTokens: used,
          fiveHourRemainingTokens: remaining,
        },
        teamQuota: {
          ...quota.teamQuota!,
          totalTokenLimit: limit,
          totalUsedTokens: used,
          totalRemainingTokens: remaining,
        },
        allowed: remaining > 0,
        blockReason: remaining > 0 ? null : "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED",
      };
      renderDashboard();
      const personal = await screen.findByRole("progressbar", {
        name: "5 小时个人额度剩余额度",
      });
      const team = screen.getByRole("progressbar", {
        name: "累计总额剩余额度",
      });
      for (const bar of [personal, team]) {
        expect(bar).toHaveAttribute("aria-valuenow", String(percent));
        if (remaining === 0) {
          expect(bar.firstElementChild).toHaveClass(
            "bg-[var(--cqg-status-error)]",
          );
        }
      }
    },
  );

  it.each([
    { used: 0, remaining: 100 },
    { used: 100, remaining: 0 },
  ])(
    "shows official remaining $remaining% for used $used%",
    async ({ used, remaining }) => {
      const original = invokeMock.getMockImplementation()!;
      invokeMock.mockImplementation(async (command, args) => {
        if (command === "codex_rate_limits")
          return {
            planType: "pro",
            fetchedAt: null,
            windows: [
              {
                limitId: "codex",
                slot: "weekly",
                windowDurationMins: 10_080,
                usedPercent: used,
                resetsAt: null,
              },
            ],
          };
        return original(command, args);
      });
      renderDashboard();
      expect(
        await screen.findByRole("progressbar", {
          name: "7 天窗口官方剩余额度",
        }),
      ).toHaveAttribute("aria-valuenow", String(remaining));
      expect(screen.getByText(`剩余 ${remaining}%`)).toBeVisible();
    },
  );

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
      await screen.findByRole("progressbar", { name: "7 天窗口官方剩余额度" }),
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

    await user.click(screen.getByRole("button", { name: "统计程序" }));
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
      if (command === "meter_service_version")
        return {
          installedVersion: "0.1.1",
          bundledVersion: "0.2.0",
          needsUpgrade: true,
          status: "UPGRADE_REQUIRED",
        };
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

    await user.click(screen.getByRole("button", { name: "统计程序" }));
    await user.click(
      await screen.findByRole("button", { name: "更新统计程序" }),
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

    await user.click(screen.getByRole("button", { name: "统计程序" }));
    await user.click(
      await screen.findByRole("button", { name: "安装统计程序" }),
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
      screen.getByRole("progressbar", { name: "5 小时团队剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "0");
    expect(
      screen.getByRole("progressbar", { name: "5 小时个人额度剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "20");
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
      screen.queryByRole("progressbar", { name: "5 小时个人额度剩余额度" }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByText("不设限").length).toBeGreaterThan(0);
  });

  it("opens independent workspaces without fetching inactive usage pages", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    renderDashboard();
    await screen.findByRole("heading", { name: "个人 Token" });
    expect(invokeMock).not.toHaveBeenCalledWith(
      "usage_analytics",
      expect.anything(),
    );
    expect(screen.queryByLabelText("个人用量统计")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "用量统计" }));
    expect(
      await screen.findByRole("region", { name: "个人用量统计" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("heading", { name: "个人 Token" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "用量统计" })).toHaveAttribute(
      "aria-current",
      "page",
    );

    await user.click(screen.getByRole("button", { name: "消耗流水" }));
    expect(await screen.findByText("暂无消耗记录")).toBeVisible();
    expect(screen.queryByLabelText("个人用量统计")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "额度总览" }));
    expect(
      await screen.findByRole("heading", { name: "个人 Token" }),
    ).toBeVisible();
    await user.click(screen.getByRole("button", { name: "收起导航" }));
    expect(screen.getByRole("button", { name: "展开导航" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(screen.getByRole("button", { name: "额度总览" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("keeps diagnostics reachable when the quota request fails", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    invokeMock.mockRejectedValue(new Error("service unavailable"));
    renderDashboard();
    await user.click(screen.getByRole("button", { name: "统计程序" }));
    expect(
      await screen.findByRole("heading", { name: "统计程序", level: 1 }),
    ).toBeVisible();
    expect(
      await screen.findByRole("button", { name: "立即扫描" }),
    ).toBeVisible();
  });

  it("opens settings and reads the actual autostart state without changing it", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = invokeMock.getMockImplementation()!;
    let enabled = true;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "autostart_status") return { supported: true, enabled };
      if (command === "autostart_set") {
        enabled = (args as { enabled: boolean }).enabled;
        return { supported: true, enabled };
      }
      return original(command, args);
    });
    renderDashboard();
    await screen.findByRole("heading", { name: "个人 Token" });
    expect(invokeMock).not.toHaveBeenCalledWith("autostart_status");
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(
      await screen.findByRole("heading", { name: "设置", level: 1 }),
    ).toBeVisible();
    const toggle = await screen.findByRole("switch", { name: "开机自启动" });
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(invokeMock).not.toHaveBeenCalledWith(
      "autostart_set",
      expect.anything(),
    );
    await user.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute("aria-checked", "false"),
    );
    expect(invokeMock).toHaveBeenCalledWith("autostart_set", {
      enabled: false,
    });
    expect(screen.getByText(/不会影响统计服务/)).toBeVisible();
    expect(screen.getByText(/首次以管理员身份启动时默认开启/)).toBeVisible();
    expect(screen.getByText(/未提权时需确认管理员授权/)).toBeVisible();
    expect(screen.queryByText(/安装时默认开启/)).not.toBeInTheDocument();
  });

  it("does not pretend autostart was changed when the native update fails", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "autostart_status")
        return { supported: true, enabled: false };
      if (command === "autostart_set")
        throw new Error("无法读取客户端安装路径，请检查安装文件。");
      return original(command, args);
    });
    renderDashboard();
    await user.click(screen.getByRole("button", { name: "设置" }));
    const toggle = await screen.findByRole("switch", { name: "开机自启动" });
    await waitFor(() => expect(toggle).toBeEnabled());
    await user.click(toggle);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "自启动设置失败",
    );
    expect(screen.getByRole("alert")).toHaveTextContent("请检查安装文件");
    expect(toggle).toHaveAttribute("aria-checked", "false");
  });

  it("reports an unavailable autostart status and lets the user retry", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = invokeMock.getMockImplementation()!;
    let requests = 0;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "autostart_status") {
        if (++requests === 1) throw new Error("unavailable");
        return { supported: true, enabled: false };
      }
      return original(command, args);
    });
    renderDashboard();
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "无法读取自启动状态",
    );
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试读取" }));
    expect(
      await screen.findByRole("switch", { name: "开机自启动" }),
    ).toHaveAttribute("aria-checked", "false");
  });

  it("rechecks actual task state when saving the installation preference fails", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = invokeMock.getMockImplementation()!;
    let enabled = false;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "autostart_status") return { supported: true, enabled };
      if (command === "autostart_set") {
        enabled = true;
        throw new Error(
          "无法保存开机自启动安装偏好，请检查 Windows 用户设置权限。",
        );
      }
      return original(command, args);
    });
    renderDashboard();
    await user.click(screen.getByRole("button", { name: "设置" }));
    const toggle = await screen.findByRole("switch", { name: "开机自启动" });
    await waitFor(() => expect(toggle).toBeEnabled());
    await user.click(toggle);
    expect(await screen.findByRole("alert")).toHaveTextContent("安装偏好");
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));
  });

  it("does not offer unsupported autostart controls", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "autostart_status")
        return { supported: false, enabled: false };
      return original(command, args);
    });
    renderDashboard();
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(
      await screen.findByText("当前系统暂不支持开机自启动设置"),
    ).toBeVisible();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  it("does not mark a running service healthy when fallback reporting fails", async () => {
    const original = invokeMock.getMockImplementation()!;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "usage_status")
        return {
          fallback: {
            state: "REPORT_FAILED",
            lastScannedAt: null,
            lastReportedAt: null,
            lastReportedEvents: 0,
            issues: [],
          },
          service: {
            state: "UP_TO_DATE",
            version: "0.2.1",
            lastScanEpochSeconds: null,
            lastReportEpochSeconds: null,
            reportedEvents: 0,
            errorCode: null,
          },
          pairingState: "PAIRING_SENT",
        };
      if (command === "meter_service_version")
        return {
          installedVersion: "0.2.1",
          bundledVersion: "0.2.1",
          needsUpgrade: false,
          status: "CURRENT",
        };
      return original(command, args);
    });
    renderDashboard();
    expect(
      await screen.findByRole("button", { name: "查看统计程序：回退上报异常" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "查看统计程序：统计程序运行中" }),
    ).not.toBeInTheDocument();
  });

  it("keeps the conflict notice outside the scrolling workspace on every page", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    renderDashboard("运行期间会强制结束 CC Switch，未保存更改可能丢失。");
    const notice = screen.getByRole("complementary", { name: "运行注意事项" });
    expect(notice).toBeVisible();
    expect(notice.closest(".cqg-workspace-scroll")).toBeNull();
    await user.click(screen.getByRole("button", { name: "消耗流水" }));
    expect(notice).toBeVisible();
    expect(notice).toHaveTextContent("未保存更改可能丢失");
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
