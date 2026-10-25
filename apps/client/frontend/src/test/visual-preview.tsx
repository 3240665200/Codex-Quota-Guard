/// <reference types="vite/client" />

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { clearMocks, mockIPC } from "@tauri-apps/api/mocks";
import axios, { type AxiosAdapter } from "axios";
import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";

import { Dashboard } from "@/components/dashboard";
import { SystemLoginPage } from "@/components/system-login";
import { Toaster } from "@/components/ui/sonner";
import { api } from "@/lib/api";
import { ClientThemeProvider, useClientTheme } from "@/lib/client-theme";
import i18n from "@/lib/i18n";
import type {
  MeterRuntimeLogs,
  MeterVersion,
  OfficialCodexRateLimits,
  QuotaOverview,
  SystemSession,
  UsageAnalytics,
  UsageEventPage,
  UsageStatus,
} from "@/lib/api";
import "@/index.css";

if (!import.meta.env.DEV) {
  throw new Error("界面预览只允许在 Vite 开发服务器中运行。");
}

const previewRoot = document.getElementById("root");
if (!previewRoot) {
  throw new Error("预览页面缺少 root 节点。");
}

const demoNow = new Date();
const hour = 60 * 60 * 1000;
const day = 24 * hour;
const isoHoursAgo = (hours: number) =>
  new Date(demoNow.getTime() - hours * hour).toISOString();
const epochMinutesAgo = (minutes: number) =>
  Math.floor((demoNow.getTime() - minutes * 60 * 1000) / 1000);

const demoSession = {
  authenticated: true,
  user: {
    id: "11111111-1111-4111-8111-111111111111",
    email: "preview@example.invalid",
    displayName: "演示账户",
    role: "USER",
    status: "ACTIVE",
  },
} satisfies SystemSession;

const demoQuota = {
  user: demoSession.user,
  team: { id: "preview-team", name: "示例团队" },
  teamQuota: {
    id: "preview-team",
    name: "示例团队",
    status: "ACTIVE",
    memberCount: 8,
    fiveHourTokenLimit: 400_000,
    weeklyTokenLimit: 2_400_000,
    totalTokenLimit: 12_000_000,
    fiveHourUsedTokens: 168_000,
    weeklyUsedTokens: 936_000,
    totalUsedTokens: 4_180_000,
    fiveHourRemainingTokens: 232_000,
    weeklyRemainingTokens: 1_464_000,
    totalRemainingTokens: 7_820_000,
    measuredAt: demoNow.toISOString(),
  },
  teamMembers: [
    {
      userId: demoSession.user.id,
      displayName: demoSession.user.displayName,
      status: "ACTIVE",
      fiveHourUsedTokens: 74_200,
      weeklyUsedTokens: 512_600,
      totalUsedTokens: 1_628_400,
      fiveHourTokenLimit: 180_000,
      weeklyTokenLimit: 1_200_000,
      fiveHourRemainingTokens: 105_800,
      weeklyRemainingTokens: 687_400,
      measuredAt: demoNow.toISOString(),
    },
    {
      userId: "22222222-2222-4222-8222-222222222222",
      displayName: "林舟",
      status: "ACTIVE",
      fiveHourUsedTokens: 38_100,
      weeklyUsedTokens: 220_400,
      totalUsedTokens: 1_201_600,
      fiveHourTokenLimit: null,
      weeklyTokenLimit: null,
      fiveHourRemainingTokens: null,
      weeklyRemainingTokens: null,
      measuredAt: isoHoursAgo(0.2),
    },
    {
      userId: "33333333-3333-4333-8333-333333333333",
      displayName: "林舟",
      status: "DISABLED",
      fiveHourUsedTokens: 2_800,
      weeklyUsedTokens: 42_000,
      totalUsedTokens: 420_000,
      fiveHourTokenLimit: 2_800,
      weeklyTokenLimit: 42_000,
      fiveHourRemainingTokens: 0,
      weeklyRemainingTokens: 0,
      measuredAt: isoHoursAgo(0.5),
    },
    {
      userId: "44444444-4444-4444-8444-444444444444",
      displayName: "刚加入的成员",
      status: "ACTIVE",
      fiveHourUsedTokens: 0,
      weeklyUsedTokens: 0,
      totalUsedTokens: 0,
      fiveHourTokenLimit: 100_000,
      weeklyTokenLimit: 500_000,
      fiveHourRemainingTokens: 100_000,
      weeklyRemainingTokens: 500_000,
      measuredAt: null,
    },
    {
      userId: "55555555-5555-4555-8555-555555555555",
      displayName: "跨地区产品与基础架构协作团队的超长名称成员 Alexandra",
      status: "ACTIVE",
      fiveHourUsedTokens: 21_000,
      weeklyUsedTokens: 85_000,
      totalUsedTokens: 380_000,
      fiveHourTokenLimit: 100_000,
      weeklyTokenLimit: 400_000,
      fiveHourRemainingTokens: 79_000,
      weeklyRemainingTokens: 315_000,
      measuredAt: isoHoursAgo(0.25),
    },
    {
      userId: "66666666-6666-4666-8666-666666666666",
      displayName: "陈默",
      status: "ACTIVE",
      fiveHourUsedTokens: 18_500,
      weeklyUsedTokens: 56_000,
      totalUsedTokens: 220_000,
      fiveHourTokenLimit: 25_000,
      weeklyTokenLimit: 100_000,
      fiveHourRemainingTokens: 6_500,
      weeklyRemainingTokens: 44_000,
      measuredAt: isoHoursAgo(0.1),
    },
    {
      userId: "77777777-7777-4777-8777-777777777777",
      displayName: "Maya",
      status: "ACTIVE",
      fiveHourUsedTokens: 9_600,
      weeklyUsedTokens: 20_000,
      totalUsedTokens: 80_000,
      fiveHourTokenLimit: 0,
      weeklyTokenLimit: null,
      fiveHourRemainingTokens: 0,
      weeklyRemainingTokens: null,
      measuredAt: isoHoursAgo(0.3),
    },
    {
      userId: "88888888-8888-4888-8888-888888888888",
      displayName: "测试成员",
      status: "DISABLED",
      fiveHourUsedTokens: 0,
      weeklyUsedTokens: 0,
      totalUsedTokens: 0,
      fiveHourTokenLimit: 0,
      weeklyTokenLimit: 0,
      fiveHourRemainingTokens: 0,
      weeklyRemainingTokens: 0,
      measuredAt: null,
    },
  ],
  policy: {
    fiveHourTokenLimit: 180_000,
    weeklyTokenLimit: 1_200_000,
    fiveHourSource: "USER",
    weeklySource: "USER",
  },
  usage: {
    measurementStatus: "CLIENT_REPORTED",
    fiveHourUsedTokens: 74_200,
    weeklyUsedTokens: 512_600,
    fiveHourRemainingTokens: 105_800,
    weeklyRemainingTokens: 687_400,
    fiveHourResetsAt: new Date(demoNow.getTime() + 2 * hour).toISOString(),
    weeklyResetsAt: new Date(demoNow.getTime() + 3 * day).toISOString(),
    measuredAt: demoNow.toISOString(),
  },
  allowed: true,
  blockReason: null,
} satisfies QuotaOverview;

const unlimitedQuota: QuotaOverview = {
  ...demoQuota,
  policy: {
    ...demoQuota.policy,
    fiveHourTokenLimit: null,
    weeklyTokenLimit: 1_000_000_000,
    fiveHourSource: "UNCONFIGURED",
  },
  usage: {
    ...demoQuota.usage,
    fiveHourUsedTokens: 129_123_456,
    fiveHourRemainingTokens: null,
    weeklyUsedTokens: 470_720_001,
    weeklyRemainingTokens: 529_279_999,
  },
  teamQuota: {
    ...demoQuota.teamQuota,
    fiveHourTokenLimit: null,
    fiveHourUsedTokens: 129_213_456,
    fiveHourRemainingTokens: null,
    weeklyTokenLimit: 1_000_000_000,
    weeklyUsedTokens: 700_000_000,
    weeklyRemainingTokens: 300_000_000,
    totalTokenLimit: 4_000_000_000,
    totalUsedTokens: 2_708_372_415,
    totalRemainingTokens: 1_291_627_585,
  },
  teamMembers: demoQuota.teamMembers.map((member) =>
    member.userId === demoSession.user.id
      ? {
          ...member,
          fiveHourUsedTokens: 129_123_456,
          weeklyUsedTokens: 470_720_001,
          totalUsedTokens: 1_600_000_000,
          fiveHourTokenLimit: null,
          fiveHourRemainingTokens: null,
          weeklyTokenLimit: 1_000_000_000,
          weeklyRemainingTokens: 529_279_999,
        }
      : member,
  ),
};
const zeroTeamQuota: QuotaOverview = {
  ...demoQuota,
  teamQuota: {
    ...demoQuota.teamQuota,
    fiveHourUsedTokens: 0,
    weeklyUsedTokens: 0,
    totalUsedTokens: 0,
    fiveHourRemainingTokens: demoQuota.teamQuota.fiveHourTokenLimit,
    weeklyRemainingTokens: demoQuota.teamQuota.weeklyTokenLimit,
    totalRemainingTokens: demoQuota.teamQuota.totalTokenLimit,
  },
  teamMembers: demoQuota.teamMembers.map((member) => ({
    ...member,
    fiveHourUsedTokens: 0,
    weeklyUsedTokens: 0,
    totalUsedTokens: 0,
    fiveHourRemainingTokens: member.fiveHourTokenLimit,
    weeklyRemainingTokens: member.weeklyTokenLimit,
  })),
  usage: {
    ...demoQuota.usage,
    fiveHourUsedTokens: 0,
    weeklyUsedTokens: 0,
    fiveHourRemainingTokens: demoQuota.policy.fiveHourTokenLimit,
    weeklyRemainingTokens: demoQuota.policy.weeklyTokenLimit,
  },
};
const scenario = new URLSearchParams(window.location.search).get("scenario");
const unlimitedScenario = scenario === "unlimited";
const previewQuota: QuotaOverview =
  scenario === "legacy"
    ? { ...demoQuota, teamMembers: null }
    : scenario === "no-team"
      ? { ...demoQuota, team: null, teamQuota: null, teamMembers: [] }
      : scenario === "zero-team"
        ? zeroTeamQuota
        : scenario === "no-team-quota"
          ? { ...demoQuota, teamQuota: null }
          : unlimitedScenario
            ? unlimitedQuota
            : scenario === "unlimited-team"
              ? {
                  ...unlimitedQuota,
                  teamQuota: {
                    ...unlimitedQuota.teamQuota!,
                    weeklyTokenLimit: null,
                    weeklyRemainingTokens: null,
                  },
                }
              : demoQuota;

const demoUsageStatus: UsageStatus = {
  state: "UP_TO_DATE",
  lastScanEpochSeconds: epochMinutesAgo(8),
  lastReportEpochSeconds: epochMinutesAgo(11),
  reportedEvents: 18,
  version: "0.0.0-preview",
  errorCode: null,
} satisfies UsageStatus;

const demoOfficialLimits = {
  planType: "演示",
  windows: [
    {
      limitId: "preview-five-hour",
      slot: "primary",
      windowDurationMins: 300,
      usedPercent: 38,
      resetsAt: new Date(demoNow.getTime() + 2.2 * hour).toISOString(),
    },
    {
      limitId: "preview-weekly",
      slot: "primary",
      windowDurationMins: 10_080,
      usedPercent: 56,
      resetsAt: new Date(demoNow.getTime() + 3.4 * day).toISOString(),
    },
  ],
  fetchedAt: demoNow.toISOString(),
} satisfies OfficialCodexRateLimits;

const demoMeterVersion = {
  installedVersion: "0.0.0-preview",
  bundledVersion: "0.0.0-preview",
  needsUpgrade: false,
  status: "CURRENT",
} satisfies MeterVersion;

const demoEvents = Array.from({ length: 18 }, (_, index) => {
  const occurredAt = isoHoursAgo(index * 2.5 + 1);
  const totalTokens = 2_180 + ((index * 613) % 4_700);
  const inputTokens = Math.round(totalTokens * 0.72);
  const outputTokens = totalTokens - inputTokens;
  return {
    id: `preview-event-${index + 1}`,
    occurredAt,
    receivedAt: new Date(new Date(occurredAt).getTime() + 35_000).toISOString(),
    totalTokens,
    model: index % 2 === 0 ? "demo-codex-standard" : "demo-codex-reasoning",
    reasoningEffort: index % 2 === 0 ? "medium" : "high",
    inputTokens,
    cachedInputTokens: Math.round(inputTokens * 0.24),
    cacheWriteInputTokens: Math.round(inputTokens * 0.08),
    outputTokens,
    reasoningOutputTokens: Math.round(outputTokens * 0.35),
    estimatedCostUsd: Number((totalTokens * 0.000004).toFixed(5)),
    costStatus: "PRICED",
    source: "TOKEN_USAGE_RECORD",
  };
}) satisfies UsageEventPage["items"];

const demoEventsPage: UsageEventPage = {
  items: demoEvents.slice(0, 10),
  page: 0,
  size: 10,
  totalElements: demoEvents.length,
  totalPages: Math.ceil(demoEvents.length / 10),
};

function makeStats(eventCount: number, bucketIndex: number) {
  const totalTokens = eventCount * (1_420 + bucketIndex * 71);
  const inputTokens = Math.round(totalTokens * 0.69);
  const outputTokens = totalTokens - inputTokens;
  return {
    eventCount,
    totalTokens,
    inputTokens,
    cachedInputTokens: Math.round(inputTokens * 0.27),
    cacheWriteInputTokens: Math.round(inputTokens * 0.09),
    outputTokens,
    reasoningOutputTokens: Math.round(outputTokens * 0.32),
    breakdownEventCount: eventCount,
    costedEventCount: eventCount,
    estimatedCostUsd: Number((totalTokens * 0.0000037).toFixed(6)),
    cacheHitRatePercent: 31.6,
  } satisfies UsageAnalytics["summary"];
}

function makeAnalytics(days: 1 | 7 | 30): UsageAnalytics {
  const granularity = days === 1 ? "HOUR" : "DAY";
  const bucketCount = days === 1 ? 24 : days;
  const stepMs = granularity === "HOUR" ? hour : day;
  const toMs = Math.floor(demoNow.getTime() / stepMs) * stepMs;
  const fromMs = toMs - bucketCount * stepMs;
  const trend = Array.from({ length: bucketCount }, (_, index) => ({
    bucket: new Date(fromMs + index * stepMs).toISOString(),
    stats: makeStats((index % 5) + 1, index),
  }));
  const summary = trend.reduce<UsageAnalytics["summary"]>(
    (total, point) => ({
      eventCount: total.eventCount + point.stats.eventCount,
      totalTokens: total.totalTokens + point.stats.totalTokens,
      inputTokens: total.inputTokens! + point.stats.inputTokens!,
      cachedInputTokens:
        total.cachedInputTokens! + point.stats.cachedInputTokens!,
      cacheWriteInputTokens:
        total.cacheWriteInputTokens! + point.stats.cacheWriteInputTokens!,
      outputTokens: total.outputTokens! + point.stats.outputTokens!,
      reasoningOutputTokens:
        total.reasoningOutputTokens! + point.stats.reasoningOutputTokens!,
      breakdownEventCount:
        total.breakdownEventCount + point.stats.breakdownEventCount,
      costedEventCount: total.costedEventCount + point.stats.costedEventCount,
      estimatedCostUsd: total.estimatedCostUsd! + point.stats.estimatedCostUsd!,
      cacheHitRatePercent: 31.6,
    }),
    {
      eventCount: 0,
      totalTokens: 0,
      inputTokens: 0,
      cachedInputTokens: 0,
      cacheWriteInputTokens: 0,
      outputTokens: 0,
      reasoningOutputTokens: 0,
      breakdownEventCount: 0,
      costedEventCount: 0,
      estimatedCostUsd: 0,
      cacheHitRatePercent: 31.6,
    },
  );
  return {
    from: new Date(fromMs).toISOString(),
    to: new Date(toMs).toISOString(),
    currency: "USD",
    timezone: "UTC",
    granularity,
    summary,
    trend,
  };
}

const demoRuntimeLogs = {
  state: "READY",
  skippedLines: 0,
  entries: [
    {
      atEpochSeconds: epochMinutesAgo(9),
      kind: "HEARTBEAT",
      state: "UP_TO_DATE",
      reportedEvents: 0,
      reportedTokens: 0,
      heartbeatSent: true,
      errorCode: null,
      ccSwitchGuard: "CLEAR",
    },
    {
      atEpochSeconds: epochMinutesAgo(12),
      kind: "USAGE_REPORTED",
      state: "REPORTED",
      reportedEvents: 3,
      reportedTokens: 14_820,
      heartbeatSent: true,
      errorCode: null,
      ccSwitchGuard: "CLEAR",
    },
    {
      atEpochSeconds: epochMinutesAgo(28),
      kind: "BASELINE_READY",
      state: "BASELINE_READY",
      reportedEvents: 0,
      reportedTokens: 0,
      heartbeatSent: false,
      errorCode: null,
      ccSwitchGuard: "INACTIVE",
    },
    {
      atEpochSeconds: epochMinutesAgo(31),
      kind: "SERVICE_STARTED",
      state: "UP_TO_DATE",
      reportedEvents: 0,
      reportedTokens: 0,
      heartbeatSent: false,
      errorCode: null,
      ccSwitchGuard: "CLEAR",
    },
  ],
} satisfies MeterRuntimeLogs;

function numericPayload(payload: unknown, key: string): number | undefined {
  if (
    payload === null ||
    typeof payload !== "object" ||
    Array.isArray(payload) ||
    payload instanceof ArrayBuffer ||
    payload instanceof Uint8Array
  ) {
    return undefined;
  }
  const value = (payload as Record<string, unknown>)[key];
  return typeof value === "number" ? value : undefined;
}

let demoAutostartEnabled = true;
mockIPC((command, payload) => {
  switch (command) {
    case "autostart_status":
      return { supported: true, enabled: demoAutostartEnabled };
    case "autostart_set": {
      const enabled = (payload as { enabled?: unknown } | undefined)?.enabled;
      if (typeof enabled !== "boolean") throw new Error("预览自启动设置无效");
      demoAutostartEnabled = enabled;
      return { supported: true, enabled: demoAutostartEnabled };
    }
    case "server_config_get":
      return { url: "http://preview.example.invalid:8080" };
    case "device_identity":
      return {
        deviceId: "99999999-9999-4999-8999-999999999999",
        enrollmentId: "99999999-9999-4999-8999-999999999999",
      };
    case "session_vault_load":
      return null;
    case "session_vault_save":
    case "session_vault_clear":
    case "meter_pairing_write":
      return null;
    case "meter_status":
      return demoUsageStatus;
    case "codex_rate_limits":
      return demoOfficialLimits;
    case "usage_analytics": {
      const requestedDays = numericPayload(payload, "days");
      return makeAnalytics(
        requestedDays === 1 || requestedDays === 7 || requestedDays === 30
          ? requestedDays
          : 7,
      );
    }
    case "usage_events": {
      const requestedPage = numericPayload(payload, "page") ?? 0;
      const requestedSize = numericPayload(payload, "size") ?? 10;
      return {
        ...demoEventsPage,
        items: demoEvents.slice(
          requestedPage * requestedSize,
          (requestedPage + 1) * requestedSize,
        ),
        page: requestedPage,
        size: requestedSize,
      } satisfies UsageEventPage;
    }
    case "meter_runtime_logs":
      return unlimitedScenario
        ? {
            ...demoRuntimeLogs,
            entries: Array.from({ length: 40 }, (_, index) => ({
              ...demoRuntimeLogs.entries[
                index % demoRuntimeLogs.entries.length
              ],
              atEpochSeconds: epochMinutesAgo(index + 1),
            })),
          }
        : demoRuntimeLogs;
    case "meter_service_version":
      return demoMeterVersion;
    default:
      throw new Error(`[界面预览] 未配置演示响应：${command}`);
  }
});

const previewAdapter: AxiosAdapter = async (config) => {
  const url = new URL(config.url ?? "/", config.baseURL);
  const path = url.pathname;
  let data: unknown;
  let status = 200;
  if (path === "/api/v1/auth/login" || path === "/api/v1/auth/refresh") {
    data = {
      accessToken: "preview-access-token",
      tokenType: "Bearer",
      expiresIn: 3600,
      refreshToken: "preview-refresh-token",
      refreshExpiresIn: 7 * 86400,
      user: demoSession.user,
    };
  } else if (path === "/api/v1/auth/logout") {
    status = 204;
    data = null;
  } else if (path === "/api/v1/users/me") {
    data = demoSession.user;
  } else if (path === "/api/v1/client/access") {
    data = {
      allowed: true,
      bindingId: "99999999-9999-4999-8999-999999999999",
      reason: null,
    };
  } else if (path === "/api/v1/client/quota/overview") {
    data = previewQuota;
  } else if (path === "/api/v1/client/usage/events") {
    const page = Number(config.params?.page ?? 0);
    const size = Number(config.params?.size ?? 10);
    data = {
      ...demoEventsPage,
      items: demoEvents.slice(page * size, (page + 1) * size),
      page,
      size,
    };
  } else if (path === "/api/v1/client/usage/analytics") {
    const days = Number(config.params?.days ?? 7);
    data = makeAnalytics(days === 1 || days === 30 ? days : 7);
  } else if (path === "/api/v1/client/official-quota") {
    status = 204;
    data = null;
  } else if (path === "/api/v1/client/meter/enroll") {
    data = {
      deviceId: "99999999-9999-4999-8999-999999999999",
      deviceToken: "preview-device-token",
      expiresAt: new Date(Date.now() + day).toISOString(),
      userId: demoSession.user.id,
    };
  } else if (path === "/api/v1/client/login-assistance") {
    data = {
      available: true,
      reasonCode: null,
      officialAccountId: "preview-account",
      accountLabel: "演示官方账户",
      recipientEmail: "mail@example.invalid",
    };
  } else if (path === "/api/v1/client/login-assistance/listeners/current") {
    data = { listener: null };
  } else if (path === "/api/v1/client/login-assistance/events") {
    const encoder = new TextEncoder();
    data = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode("event: connected\ndata: {}\n\n"));
      },
    });
  } else if (path === "/api/v1/client/session-bindings/current") {
    data = { attempt: null, binding: null };
  } else {
    throw new Error(`[界面预览] 未配置中央演示响应：${path}`);
  }
  return {
    config,
    data,
    headers: {},
    request: {},
    status,
    statusText: status === 204 ? "No Content" : "OK",
  };
};
axios.defaults.adapter = previewAdapter;

const previewSessionReady = api
  .systemLogin("preview@example.invalid", "preview-password")
  .then(
    () => true,
    () => false,
  );

function PreviewDashboard() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    let active = true;
    void previewSessionReady.then((result) => {
      if (active) setReady(result);
    });
    return () => {
      active = false;
    };
  }, []);
  if (!ready) {
    return (
      <main className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">
        正在准备演示数据…
      </main>
    );
  }
  return (
    <Dashboard
      session={demoSession}
      onLogout={() => undefined}
      guardNotice={<PreviewGuardNotice />}
    />
  );
}

function PreviewGuardNotice() {
  const { resolvedTheme } = useClientTheme();
  const dark = resolvedTheme === "dark";
  return (
    <aside
      aria-label="界面预览提示"
      className={`py-1 text-xs leading-5 ${dark ? "text-sky-100" : "text-sky-900"}`}
      role="note"
    >
      <span className="font-semibold">界面预览 · 演示数据</span>
      <span className="ml-2">
        仅用于视觉验证，不连接后台、本机账户或统计程序。
      </span>
    </aside>
  );
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      retry: false,
    },
  },
});

const root = createRoot(previewRoot);
root.render(
  <StrictMode>
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <ClientThemeProvider>
          {new URLSearchParams(window.location.search).get("view") ===
          "login" ? (
            <div className="cqg-entry-page">
              <div className="border-b bg-muted/50 px-6 py-2">
                <PreviewGuardNotice />
              </div>
              <SystemLoginPage onAuthenticated={() => undefined} />
            </div>
          ) : (
            <PreviewDashboard />
          )}
          <Toaster />
        </ClientThemeProvider>
      </QueryClientProvider>
    </I18nextProvider>
  </StrictMode>,
);

if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    root.unmount();
    queryClient.clear();
    clearMocks();
  });
}
