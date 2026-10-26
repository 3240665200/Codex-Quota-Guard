import { usdFixture } from "@/test/usd-fixtures";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Dashboard } from "@/components/dashboard";
import {
  api,
  type QuotaOverview,
  type SystemSession,
  type UsageStatus,
} from "@/lib/api";
import i18n from "@/lib/i18n";
import { setQuotaDisplayMode } from "@/lib/quota-display";

const apiCalls = vi.fn<(...args: any[]) => Promise<any>>();
afterEach(() => vi.restoreAllMocks());

const status: UsageStatus = {
  state: "UP_TO_DATE",
  lastScanEpochSeconds: 1_791_504_000,
  lastReportEpochSeconds: 1_791_504_000,
  reportedEvents: 2,
  errorCode: null,
  version: "0.1.1",
};
const session: SystemSession = {
  authenticated: true,
  user: {
    id: "11111111-1111-4111-8111-111111111111",
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
};
const quota: QuotaOverview = {
  billing: usdFixture(),
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
  teamMembers: [
    {
      userId: session.user!.id,
      displayName: "Alice",
      status: "ACTIVE",
      fiveHourUsedTokens: 800,
      weeklyUsedTokens: 4_500,
      totalUsedTokens: 8_000,
      fiveHourTokenLimit: 1_000,
      weeklyTokenLimit: 10_000,
      fiveHourRemainingTokens: 200,
      weeklyRemainingTokens: 5_500,
      measuredAt: "2026-10-06T08:00:00Z",
    },
    {
      userId: "22222222-2222-4222-8222-222222222222",
      displayName: "Bob",
      status: "ACTIVE",
      fiveHourUsedTokens: 0,
      weeklyUsedTokens: 0,
      totalUsedTokens: 0,
      fiveHourTokenLimit: null,
      weeklyTokenLimit: null,
      fiveHourRemainingTokens: null,
      weeklyRemainingTokens: null,
      measuredAt: null,
    },
  ],
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

async function renderDashboard(
  guardNotice?: string,
  currentSession: SystemSession = session,
) {
  const currentImplementation = apiCalls.getMockImplementation()!;
  apiCalls.mockImplementation(async (command, args) =>
    command === "clientAccess"
      ? {
          allowed: true,
          bindingId: "11111111-1111-4111-8111-111111111111",
          reason: null,
        }
      : command === "sessionBindingCurrent"
        ? { attempt: null, binding: null }
        : currentImplementation(command, args),
  );
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const result = render(
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
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
  );
  return result;
}

describe("Dashboard", () => {
  it.each(["USER", "ADMIN"])(
    "keeps mail assistance and settings free of the retired browser helper for %s",
    async (role) => {
      await renderDashboard(undefined, {
        ...session,
        user: { ...session.user!, role },
      });
      for (const [page, heading] of [
        ["设置", "启动设置"],
        ["登录辅助", "监听登录邮件"],
      ]) {
        fireEvent.click(screen.getByRole("button", { name: page }));
        await screen.findByRole("heading", { name: heading });
        expect(
          screen.queryByRole("region", { name: "官方登录助手" }),
        ).toBeNull();
        expect(
          apiCalls.mock.calls.some(([name]) =>
            name.startsWith("official_login_helper_"),
          ),
        ).toBe(false);
      }
    },
  );
  it("keeps binding and login assistance identities separate when changing pages", async () => {
    const warnings = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      await renderDashboard();
      await screen.findByRole("heading", { name: "个人额度（USD）" });
      fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
      await screen.findByRole("heading", { name: "监听登录邮件" });
      expect(warnings.mock.calls.flat().join(" ")).not.toContain("same key");
    } finally {
      warnings.mockRestore();
    }
  });
  beforeEach(() => {
    setQuotaDisplayMode("remaining");
    apiCalls.mockReset();
    vi.spyOn(api, "clientAccess").mockImplementation(() =>
      apiCalls("clientAccess"),
    );
    vi.spyOn(api, "quotaOverview").mockImplementation(() =>
      apiCalls("quotaOverview"),
    );
    vi.spyOn(api, "codexRateLimits").mockImplementation(() =>
      apiCalls("codexRateLimits"),
    );
    vi.spyOn(api, "usageStatus").mockImplementation(() =>
      apiCalls("usageStatus"),
    );
    vi.spyOn(api, "meterServiceVersion").mockImplementation(() =>
      apiCalls("meterServiceVersion"),
    );
    vi.spyOn(api, "usageEvents").mockImplementation((page, size) =>
      apiCalls("usageEvents", { page, size }),
    );
    vi.spyOn(api, "usageAnalytics").mockImplementation((days) =>
      apiCalls("usageAnalytics", { days }),
    );
    vi.spyOn(api, "meterRuntimeLogs").mockImplementation(() =>
      apiCalls("meterRuntimeLogs"),
    );
    vi.spyOn(api, "autostartStatus").mockImplementation(() =>
      apiCalls("autostartStatus"),
    );
    vi.spyOn(api, "setAutostart").mockImplementation((enabled) =>
      apiCalls("setAutostart", { enabled }),
    );
    vi.spyOn(api, "serverConfig").mockImplementation(() =>
      apiCalls("serverConfig"),
    );
    vi.spyOn(api, "saveServerConfig").mockImplementation((config) =>
      apiCalls("saveServerConfig", { config }),
    );
    vi.spyOn(api, "sessionBindingCurrent").mockImplementation(() =>
      apiCalls("sessionBindingCurrent"),
    );
    vi.spyOn(api, "loginAssistanceInfo").mockImplementation(() =>
      apiCalls("loginAssistanceInfo"),
    );
    vi.spyOn(api, "loginAssistanceCurrent").mockImplementation(() =>
      apiCalls("loginAssistanceCurrent"),
    );
    quotaResponse = quota;
    apiCalls.mockImplementation(async (command) => {
      switch (command) {
        case "status":
          return status;
        case "autostartStatus":
          return { supported: true, enabled: false };
        case "serverConfig":
          return { url: "https://control.example.test" };
        case "loginAssistanceInfo":
          return {
            available: false,
            reasonCode: "TEAM_ACCOUNT_NOT_CONFIGURED",
            officialAccountId: null,
            accountLabel: null,
            recipientEmail: null,
          };
        case "loginAssistanceCurrent":
          return { listener: null };
        case "meterRuntimeLogs":
          return { state: "READY", entries: [], skippedLines: 0 };
        case "quotaOverview":
          return quotaResponse;
        case "usageAnalytics":
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
        case "usageEvents":
          return {
            items: [],
            page: 0,
            size: 10,
            totalElements: 0,
            totalPages: 0,
          };
        case "meterServiceVersion":
          return {
            installedVersion: null,
            bundledVersion: "0.1.1",
            needsUpgrade: null,
            status: "NOT_INSTALLED",
          };
        case "codexRateLimits":
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
        case "usageStatus":
        case "usage_scan":
        case "usage_pair":
          return {
            state: "NOT_INSTALLED",
            lastScanEpochSeconds: null,
            lastReportEpochSeconds: null,
            reportedEvents: 0,
            errorCode: null,
          };
        default:
          throw new Error(`Unexpected command: ${command}`);
      }
    });
  });

  it.each([
    { used: 0, remaining: 100 },
    { used: 100, remaining: 0 },
  ])(
    "shows official remaining $remaining% for used $used%",
    async ({ used, remaining }) => {
      const original = apiCalls.getMockImplementation()!;
      apiCalls.mockImplementation(async (command, args) => {
        if (command === "codexRateLimits")
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
      await renderDashboard();
      expect(
        await screen.findByRole("progressbar", {
          name: "7 天窗口官方剩余额度",
        }),
      ).toHaveAttribute("aria-valuenow", String(remaining));
      expect(screen.getByText(`剩余 ${remaining}%`)).toBeVisible();
    },
  );

  it("does not offer credential download or managed Codex login", async () => {
    await renderDashboard();
    await screen.findByRole("heading", { name: "官方套餐参考" });
    expect(
      screen.queryByRole("button", { name: "登录 Codex" }),
    ).not.toBeInTheDocument();
    expect(apiCalls).not.toHaveBeenCalledWith("codex_login");
  });

  it("only displays official quota windows returned by the local account", async () => {
    apiCalls.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quotaOverview") return quota;
      if (command === "usageStatus")
        return {
          state: "UP_TO_DATE",
          lastScanEpochSeconds: null,
          lastReportEpochSeconds: null,
          reportedEvents: 0,
          errorCode: null,
        };
      if (command === "codexRateLimits")
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

    await renderDashboard();

    expect(
      await screen.findByRole("progressbar", { name: "7 天窗口官方剩余额度" }),
    ).toBeVisible();
    expect(screen.queryByText("5 小时窗口")).not.toBeInTheDocument();
  });

  it.each(["UNPAIRED", "OFFLINE", "NOT_INSTALLED"] as const)(
    "shows %s diagnostics without installing, starting or pairing from the dashboard",
    async (state) => {
      const original = apiCalls.getMockImplementation()!;
      apiCalls.mockImplementation(async (method, args) =>
        method === "usageStatus"
          ? { ...status, state }
          : original(method, args),
      );
      await renderDashboard();
      fireEvent.click(screen.getByRole("button", { name: "统计程序" }));
      await screen.findByRole("heading", { name: "Rust 常驻统计程序" });
      expect(
        screen.queryByRole("button", {
          name: /安装统计程序|更新统计程序|重新配对|立即扫描/,
        }),
      ).toBeNull();
      if (state === "NOT_INSTALLED")
        expect(screen.getByText(/重新运行客户端安装器/)).toBeVisible();
      expect(
        apiCalls.mock.calls.some(([method]) =>
          /usage_scan|usage_pair|meter_service_retry/.test(method),
        ),
      ).toBe(false);
    },
  );

  it("treats an absent five-hour limit as unlimited", async () => {
    apiCalls.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quotaOverview")
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
      if (command === "codexRateLimits")
        return { planType: null, windows: [], fetchedAt: null };
      throw new Error(`Unexpected command: ${command}`);
    });

    await renderDashboard();

    expect(await screen.findByText("额度可用")).toBeVisible();
    expect(screen.queryByText("Token 额度已用尽")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("progressbar", { name: "5 小时个人额度剩余额度" }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByText("无限制").length).toBeGreaterThan(0);
  });

  it("opens independent workspaces without fetching inactive usage pages", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    await renderDashboard();
    await screen.findByRole("heading", { name: "个人额度（USD）" });
    expect(apiCalls).not.toHaveBeenCalledWith(
      "usageAnalytics",
      expect.anything(),
    );
    expect(screen.queryByLabelText("个人用量统计")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "用量统计" }));
    expect(
      await screen.findByRole("region", { name: "个人用量统计" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("heading", { name: "个人额度（USD）" }),
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
      await screen.findByRole("heading", { name: "个人额度（USD）" }),
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
    apiCalls.mockRejectedValue(new Error("service unavailable"));
    await renderDashboard();
    await user.click(screen.getByRole("button", { name: "统计程序" }));
    expect(
      await screen.findByRole("heading", { name: "统计程序", level: 1 }),
    ).toBeVisible();
    expect(
      await screen.findByRole("button", { name: "刷新运行诊断" }),
    ).toBeVisible();
    expect(screen.queryByRole("button", { name: "立即扫描" })).toBeNull();
  });

  it("opens settings and reads the actual autostart state without changing it", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = apiCalls.getMockImplementation()!;
    let enabled = true;
    apiCalls.mockImplementation(async (command, args) => {
      if (command === "autostartStatus") return { supported: true, enabled };
      if (command === "setAutostart") {
        enabled = (args as { enabled: boolean }).enabled;
        return { supported: true, enabled };
      }
      return original(command, args);
    });
    await renderDashboard();
    await screen.findByRole("heading", { name: "个人额度（USD）" });
    expect(apiCalls).not.toHaveBeenCalledWith("autostartStatus");
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(
      await screen.findByRole("heading", { name: "设置", level: 1 }),
    ).toBeVisible();
    const toggle = await screen.findByRole("switch", { name: "开机自启动" });
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(apiCalls).not.toHaveBeenCalledWith(
      "setAutostart",
      expect.anything(),
    );
    await user.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute("aria-checked", "false"),
    );
    expect(apiCalls).toHaveBeenCalledWith("setAutostart", {
      enabled: false,
    });
    expect(screen.getByText(/不会影响统计服务/)).toBeVisible();
    expect(screen.getByText(/登录 Windows 后自动打开客户端/)).toBeVisible();
    expect(screen.getByText(/故障恢复由 Windows 负责/)).toBeVisible();
    expect(screen.queryByText(/未提权时需确认管理员授权/)).toBeNull();
    expect(screen.queryByText(/安装时默认开启/)).not.toBeInTheDocument();
  });

  it("does not pretend autostart was changed when the native update fails", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = apiCalls.getMockImplementation()!;
    apiCalls.mockImplementation(async (command, args) => {
      if (command === "autostartStatus")
        return { supported: true, enabled: false };
      if (command === "setAutostart")
        throw new Error("无法读取客户端安装路径，请检查安装文件。");
      return original(command, args);
    });
    await renderDashboard();
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
    const original = apiCalls.getMockImplementation()!;
    let requests = 0;
    apiCalls.mockImplementation(async (command, args) => {
      if (command === "autostartStatus") {
        if (++requests === 1) throw new Error("unavailable");
        return { supported: true, enabled: false };
      }
      return original(command, args);
    });
    await renderDashboard();
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
    const original = apiCalls.getMockImplementation()!;
    let enabled = false;
    apiCalls.mockImplementation(async (command, args) => {
      if (command === "autostartStatus") return { supported: true, enabled };
      if (command === "setAutostart") {
        enabled = true;
        throw new Error(
          "无法保存开机自启动安装偏好，请检查 Windows 用户设置权限。",
        );
      }
      return original(command, args);
    });
    await renderDashboard();
    await user.click(screen.getByRole("button", { name: "设置" }));
    const toggle = await screen.findByRole("switch", { name: "开机自启动" });
    await waitFor(() => expect(toggle).toBeEnabled());
    await user.click(toggle);
    expect(await screen.findByRole("alert")).toHaveTextContent("安装偏好");
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));
  });

  it("does not offer unsupported autostart controls", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    const original = apiCalls.getMockImplementation()!;
    apiCalls.mockImplementation(async (command, args) => {
      if (command === "autostartStatus")
        return { supported: false, enabled: false };
      return original(command, args);
    });
    await renderDashboard();
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(
      await screen.findByText("当前系统暂不支持开机自启动设置"),
    ).toBeVisible();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  it("shows a failed independent capture service as unhealthy", async () => {
    const original = apiCalls.getMockImplementation()!;
    apiCalls.mockImplementation(async (method, args) =>
      method === "usageStatus"
        ? {
            ...status,
            state: "ERROR",
            errorCode: "METER_CAPTURE_THREAD_PANICKED",
          }
        : original(method, args),
    );
    await renderDashboard();
    expect(
      await screen.findByRole("button", { name: "查看统计程序：统计程序异常" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "查看统计程序：统计程序运行中" }),
    ).toBeNull();
  });

  it("keeps the conflict notice outside the scrolling workspace on every page", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    await renderDashboard(
      "默认与 CC Switch 共存，仅在确认官方会话冲突时停止并提醒。",
    );
    const notice = screen.getByRole("complementary", { name: "运行注意事项" });
    expect(notice).toBeVisible();
    expect(notice.closest(".cqg-workspace-scroll")).toBeNull();
    await user.click(screen.getByRole("button", { name: "消耗流水" }));
    expect(notice).toBeVisible();
    expect(notice).toHaveTextContent("仅在确认官方会话冲突时停止并提醒");
  });

  it("shows a useful central access error without invoking a local backend", async () => {
    apiCalls.mockRejectedValue(new Error("service unavailable"));

    await renderDashboard();

    await waitFor(() => {
      const serviceAlert = screen
        .getAllByRole("alert")
        .find((alert) => alert.textContent?.includes("中央服务器不可用"));
      expect(serviceAlert).toBeDefined();
      expect(serviceAlert).toHaveTextContent("中央服务器不可用");
    });
    expect(screen.getByRole("button", { name: "重试" })).toBeVisible();
  });
});
vi.mock("@/lib/assistance-events", () => ({
  subscribeToAssistanceEvents: () => () => {},
}));
