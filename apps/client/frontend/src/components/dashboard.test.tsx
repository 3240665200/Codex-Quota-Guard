import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
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
      await screen.findByRole("heading", { name: "个人 Token" });
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

  it("renders personal token limits separately from official account reference windows", async () => {
    await renderDashboard();

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
    expect(screen.getByRole("heading", { name: "官方 Token" })).toBeVisible();
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
    expect(new Set(apiCalls.mock.calls.map(([command]) => command))).toEqual(
      new Set([
        "clientAccess",
        "quotaOverview",
        "codexRateLimits",
        "usageStatus",
        "meterServiceVersion",
      ]),
    );
  });

  it("groups shared quota, personal quota and all members under Team Token before Official Token", async () => {
    await renderDashboard();
    await screen.findByRole("heading", { name: "个人 Token" });
    expect(
      screen
        .getAllByRole("heading", { level: 2 })
        .map((heading) => heading.textContent),
    ).toEqual(["团队 Token", "官方 Token"]);
    const official = screen.getByRole("region", { name: "官方 Token" });
    const team = screen.getByRole("region", { name: "团队 Token" });
    expect(team.nextElementSibling).toBe(official);
    expect(
      within(team).getByRole("region", { name: "个人 Token" }),
    ).toBeVisible();
    expect(
      within(team).getByRole("region", { name: "团队共享额度" }),
    ).toBeVisible();
    expect(within(team).getByRole("table")).toBeVisible();
    expect(within(official).getByText(/官方额度百分比/)).toBeVisible();
    expect(within(official).queryByText(/精确值/)).toBeNull();
    expect(official.querySelector(".cqg-token-amount")).toBeNull();
  });

  it("switches personal, team, member and official quota displays through Settings", async () => {
    await renderDashboard();
    await screen.findByRole("heading", { name: "个人 Token" });
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    expect(screen.getByRole("radio", { name: "剩余" })).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: "已用" }));
    fireEvent.click(screen.getByRole("button", { name: "额度总览" }));
    expect(
      await screen.findByRole("progressbar", {
        name: "5 小时个人额度已用额度",
      }),
    ).toHaveAttribute("aria-valuenow", "80");
    expect(
      screen.getByRole("progressbar", { name: "5 小时团队已用额度" }),
    ).toHaveAttribute("aria-valuenow", "90");
    expect(
      screen.getByRole("progressbar", { name: "5 小时窗口官方已用额度" }),
    ).toHaveAttribute("aria-valuenow", "25");
    const members = screen.getByRole("region", { name: "成员用量" });
    expect(
      within(members).getByRole("progressbar", { name: "5 小时个人已用额度" }),
    ).toHaveAttribute("aria-valuenow", "80");
    expect(
      within(within(members).getAllByRole("row")[1]).getByRole("progressbar", {
        name: "5 小时团队用量占比",
      }),
    ).toHaveAttribute("aria-valuenow", String((800 * 100) / 900));
    expect(screen.getAllByText("个人已用").length).toBe(2);
  });

  it("shows every teammate including disabled, duplicate-name and zero-usage members", async () => {
    const longName = "跨地区产品与基础架构协作团队的超长名称成员 Alexandra";
    quotaResponse = {
      ...quota,
      teamMembers: [
        ...quota.teamMembers!,
        {
          userId: "33333333-3333-4333-8333-333333333333",
          displayName: "Bob",
          status: "DISABLED",
          fiveHourUsedTokens: 12,
          weeklyUsedTokens: 42,
          totalUsedTokens: 120,
          fiveHourTokenLimit: 0,
          weeklyTokenLimit: 100,
          fiveHourRemainingTokens: 0,
          weeklyRemainingTokens: 58,
          measuredAt: null,
        },
        {
          userId: "44444444-4444-4444-8444-444444444444",
          displayName: longName,
          status: "ACTIVE",
          fiveHourUsedTokens: 1,
          weeklyUsedTokens: 2,
          totalUsedTokens: 3,
          fiveHourTokenLimit: 10,
          weeklyTokenLimit: 20,
          fiveHourRemainingTokens: 9,
          weeklyRemainingTokens: 18,
          measuredAt: null,
        },
      ],
    };
    await renderDashboard();
    const memberRegion = await screen.findByRole("region", {
      name: "成员用量",
    });
    const rows = within(memberRegion).getAllByRole("row");
    expect(rows).toHaveLength(5);
    expect(within(rows[1]).getByRole("rowheader")).toHaveTextContent("Alice我");
    expect(within(rows[2]).getByText("已停用")).toBeVisible();
    expect(within(rows[2]).getByText("剩余 0 / 上限 0 Token")).toBeVisible();
    expect(within(rows[2]).getByText("0% · 已用尽")).toBeVisible();
    expect(within(rows[2]).getByText("已用 12 / 团队 900 Token")).toBeVisible();
    expect(
      within(rows[2]).getByText("已用 42 / 团队 4,700 Token"),
    ).toBeVisible();
    expect(
      within(rows[2]).getByText("已用 120 / 团队 10,000 Token"),
    ).toBeVisible();
    expect(within(rows[3]).getByText(longName)).toBeVisible();
    expect(within(rows[4]).getAllByText("无限制")).toHaveLength(2);
    expect(within(rows[4]).getAllByRole("cell")[0]).not.toHaveTextContent(
      "已用",
    );
    expect(within(rows[4]).getAllByText("0%")).toHaveLength(3);
    expect(within(rows[4]).getByText("已用 0 / 团队 900 Token")).toBeVisible();
    expect(within(memberRegion).getAllByText("Bob")).toHaveLength(2);
    expect(
      within(memberRegion).getByText(/已离队成员的历史用量/),
    ).toBeVisible();
    expect(within(memberRegion).getByRole("table")).toHaveAccessibleName(
      /个人限额留空即无限制/,
    );
  });

  it("keeps large teams fully accessible and filters members locally", async () => {
    quotaResponse = {
      ...quota,
      teamMembers: Array.from({ length: 35 }, (_, index) => ({
        userId: `11111111-1111-4111-8111-${String(index).padStart(12, "0")}`,
        displayName: `成员 ${String(index).padStart(2, "0")}`,
        status: "ACTIVE",
        fiveHourUsedTokens: index,
        weeklyUsedTokens: index,
        totalUsedTokens: index,
        fiveHourTokenLimit: 100,
        weeklyTokenLimit: 1_000,
        fiveHourRemainingTokens: 100 - index,
        weeklyRemainingTokens: 1_000 - index,
        measuredAt: null,
      })),
    };
    await renderDashboard();
    const memberRegion = await screen.findByRole("region", {
      name: "成员用量",
    });
    expect(within(memberRegion).getAllByRole("row")).toHaveLength(36);
    const requestCount = apiCalls.mock.calls.length;
    fireEvent.change(screen.getByRole("searchbox", { name: "搜索成员" }), {
      target: { value: "成员 34" },
    });
    expect(within(memberRegion).getAllByRole("row")).toHaveLength(2);
    expect(within(memberRegion).getByText("成员 34")).toBeVisible();
    expect(
      within(memberRegion).getByText("显示 1 / 35 位成员 · 单位 Token"),
    ).toBeVisible();
    fireEvent.change(screen.getByRole("searchbox", { name: "搜索成员" }), {
      target: { value: "不存在的成员" },
    });
    expect(within(memberRegion).getByText("未找到匹配的成员")).toBeVisible();
    fireEvent.change(screen.getByRole("searchbox", { name: "搜索成员" }), {
      target: { value: "" },
    });
    expect(within(memberRegion).getAllByRole("row")).toHaveLength(36);
    expect(apiCalls.mock.calls).toHaveLength(requestCount);
  });

  it("uses each member's personal limits for remaining bars and team used totals for contribution bars", async () => {
    await renderDashboard();
    const row = (await screen.findAllByRole("row"))[1];
    expect(
      within(row).getByRole("progressbar", { name: "5 小时个人剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "20");
    expect(
      within(row).getByRole("progressbar", { name: "7 天个人剩余额度" }),
    ).toHaveAttribute("aria-valuenow", "55");
    expect(within(row).getByText("剩余 200 / 上限 1,000 Token")).toBeVisible();
    expect(
      within(row).getByText("剩余 5,500 / 上限 10,000 Token"),
    ).toBeVisible();
    const fiveHourShare = within(row).getByRole("progressbar", {
      name: "5 小时团队用量占比",
    });
    expect(Number(fiveHourShare.getAttribute("aria-valuenow"))).toBeCloseTo(
      (800 / 900) * 100,
    );
    expect(fiveHourShare).toHaveAttribute("aria-valuetext", "88.9%");
    expect(
      Number(
        within(row)
          .getByRole("progressbar", { name: "7 天团队用量占比" })
          .getAttribute("aria-valuenow"),
      ),
    ).toBeCloseTo((4_500 / 4_700) * 100);
    expect(
      within(row).getByRole("progressbar", { name: "累计团队用量占比" }),
    ).toHaveAttribute("aria-valuenow", "80");
    expect(
      within(row).getByText("已用 8,000 / 团队 10,000 Token"),
    ).toBeVisible();
    expect(within(row).getAllByRole("progressbar")).toHaveLength(5);
  });

  it("shows zero contribution when team used totals are zero", async () => {
    quotaResponse = {
      ...quota,
      teamQuota: {
        ...quota.teamQuota!,
        fiveHourUsedTokens: 0,
        weeklyUsedTokens: 0,
        totalUsedTokens: 0,
      },
      teamMembers: quota.teamMembers!.map((member) => ({
        ...member,
        fiveHourUsedTokens: 0,
        weeklyUsedTokens: 0,
        totalUsedTokens: 0,
        fiveHourRemainingTokens: member.fiveHourTokenLimit,
        weeklyRemainingTokens: member.weeklyTokenLimit,
      })),
    };
    await renderDashboard();
    const rows = await screen.findAllByRole("row");
    for (const row of rows.slice(1)) {
      for (const label of ["5 小时", "7 天", "累计"]) {
        expect(
          within(row).getByRole("progressbar", {
            name: `${label}团队用量占比`,
          }),
        ).toHaveAttribute("aria-valuenow", "0");
      }
      expect(within(row).getAllByText("已用 0 / 团队 0 Token")).toHaveLength(3);
    }
  });

  it("does not invent contribution percentages without team totals", async () => {
    quotaResponse = {
      ...quota,
      teamQuota: null,
      teamMembers: [quota.teamMembers![0]],
    };
    await renderDashboard();
    const row = (await screen.findAllByRole("row"))[1];
    expect(within(row).getAllByText("暂不可得")).toHaveLength(3);
    expect(within(row).getAllByRole("progressbar")).toHaveLength(2);
    expect(
      within(row).getByText("已用 8,000 Token · 团队总量暂不可得"),
    ).toBeVisible();
  });

  it.each([0, 500])(
    "marks inconsistent team totals %i as pending refresh instead of clipping the share",
    async (teamUsed) => {
      quotaResponse = {
        ...quota,
        teamQuota: { ...quota.teamQuota!, fiveHourUsedTokens: teamUsed },
        teamMembers: [quota.teamMembers![0]],
      };
      await renderDashboard();
      const row = (await screen.findAllByRole("row"))[1];
      const metric = within(row).getByRole("group", {
        name: "5 小时团队用量占比",
      });
      expect(within(metric).getByText("待刷新")).toBeVisible();
      expect(within(metric).queryByRole("progressbar")).toBeNull();
      expect(
        within(metric).getByText(`已用 800 / 团队 ${teamUsed} Token · 待刷新`),
      ).toBeVisible();
    },
  );

  it("clearly reports older servers without inventing an empty team or zero usage", async () => {
    const { teamMembers: _members, ...legacyOverview } = quota;
    const original = apiCalls.getMockImplementation()!;
    apiCalls.mockImplementation(async (command, args) =>
      command === "quotaOverview"
        ? { ...legacyOverview, teamMembers: null }
        : original(command, args),
    );
    await renderDashboard();
    expect(
      await screen.findByText("服务器尚未提供成员用量，请更新服务器"),
    ).toBeVisible();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByText("团队暂无成员")).toBeNull();
    expect(screen.getByRole("region", { name: "个人 Token" })).toBeVisible();
  });

  it("preserves the personal quota when the user has no team", async () => {
    quotaResponse = { ...quota, team: null, teamQuota: null, teamMembers: [] };
    await renderDashboard();
    expect(await screen.findByText("未加入团队")).toBeVisible();
    expect(screen.getByText("加入团队后，可查看团队成员用量。")).toBeVisible();
    expect(
      screen.queryByText("服务器尚未提供成员用量，请更新服务器"),
    ).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.getByRole("region", { name: "个人 Token" })).toBeVisible();
  });

  it("distinguishes a returned empty member list from unavailable member data", async () => {
    quotaResponse = { ...quota, teamMembers: [] };
    await renderDashboard();
    expect(await screen.findByText("团队暂无成员")).toBeVisible();
    expect(
      screen.queryByText("服务器尚未提供成员用量，请更新服务器"),
    ).toBeNull();
    expect(screen.queryByRole("searchbox")).toBeNull();
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

    await renderDashboard();

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

    const unlimitedValue = within(fiveHour).getAllByText("无限制")[1];
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
    expect(team.querySelector(".cqg-shared-windows")?.children).toHaveLength(3);
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
      await renderDashboard();
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
    await screen.findByRole("heading", { name: "官方 Token" });
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

  it("warns when personal quota is exhausted without claiming official logout", async () => {
    apiCalls.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quotaOverview") {
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
      if (command === "codexRateLimits") {
        return { planType: null, windows: [], fetchedAt: null };
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    await renderDashboard();

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
    apiCalls.mockImplementation(async (command) => {
      if (command === "status") return status;
      if (command === "quotaOverview")
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
      if (command === "codexRateLimits")
        return { planType: null, windows: [], fetchedAt: null };
      if (command === "usageStatus")
        return {
          state: "UP_TO_DATE",
          lastScanEpochSeconds: null,
          lastReportEpochSeconds: null,
          reportedEvents: 0,
          errorCode: null,
        };
      throw new Error(`Unexpected command: ${command}`);
    });
    await renderDashboard();
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

  it.each(["remaining", "used"] as const)(
    "keeps unset personal limits unlimited while distinguishing measured team contribution in %s mode",
    async (mode) => {
      setQuotaDisplayMode(mode);
      quotaResponse = {
        ...quota,
        policy: {
          fiveHourTokenLimit: null,
          weeklyTokenLimit: null,
          fiveHourSource: "UNCONFIGURED",
          weeklySource: "UNCONFIGURED",
        },
        usage: {
          ...quota.usage,
          fiveHourRemainingTokens: null,
          weeklyRemainingTokens: null,
        },
        teamQuota: {
          ...quota.teamQuota!,
          fiveHourTokenLimit: null,
          weeklyTokenLimit: null,
          fiveHourRemainingTokens: null,
          weeklyRemainingTokens: null,
        },
        teamMembers: [
          {
            ...quota.teamMembers![0],
            fiveHourTokenLimit: null,
            weeklyTokenLimit: null,
            fiveHourRemainingTokens: null,
            weeklyRemainingTokens: null,
          },
        ],
      };
      await renderDashboard();
      const members = await screen.findByRole("region", { name: "成员用量" });
      const row = within(members).getAllByRole("row")[1];
      const personal = within(row).getAllByRole("cell")[0];
      expect(within(personal).getAllByText("无限制")).toHaveLength(2);
      expect(within(personal).queryByRole("progressbar")).toBeNull();
      expect(within(personal).queryByText("受团队共享额度约束")).toBeNull();
      expect(
        within(row).getByRole("progressbar", {
          name: "5 小时团队用量占比",
        }),
      ).toHaveAttribute("aria-valuetext", "88.9%");
      expect(
        within(members).getByText(
          /用量占比仅表示成员在团队已用 Token 中的占比/,
        ),
      ).toBeVisible();
    },
  );

  it("opens independent workspaces without fetching inactive usage pages", async () => {
    const user = (await import("@testing-library/user-event")).default.setup();
    await renderDashboard();
    await screen.findByRole("heading", { name: "个人 Token" });
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
    await screen.findByRole("heading", { name: "个人 Token" });
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
