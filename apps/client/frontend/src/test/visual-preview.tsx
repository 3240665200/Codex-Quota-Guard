/// <reference types="vite/client" />

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { clearMocks, mockIPC } from "@tauri-apps/api/mocks";
import { StrictMode } from "react";
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
  StatusResponse,
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
    id: "preview-user",
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
    memberCount: 4,
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
    totalTokenLimit: 4_000_000_000,
    totalUsedTokens: 2_708_372_415,
    totalRemainingTokens: 1_291_627_585,
  },
};
const unlimitedScenario =
  new URLSearchParams(window.location.search).get("scenario") === "unlimited";

const demoUsageStatus = {
  fallback: {
    state: "SERVICE_ACTIVE",
    lastScannedAt: isoHoursAgo(0.2),
    lastReportedAt: isoHoursAgo(0.35),
    lastReportedEvents: 3,
    issues: [],
  },
  service: {
    state: "UP_TO_DATE",
    lastScanEpochSeconds: epochMinutesAgo(8),
    lastReportEpochSeconds: epochMinutesAgo(11),
    reportedEvents: 18,
    version: "0.0.0-preview",
    errorCode: null,
  },
  pairingState: "PAIRING_SENT",
} satisfies UsageStatus;

const demoOfficialLimits = {
  planType: "演示套餐",
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

const demoStatus = {
  service: "codex-quota-guard-preview",
  state: "online",
} satisfies StatusResponse;

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

mockIPC((command, payload) => {
  switch (command) {
    case "system_current":
      return demoSession;
    case "server_config_get":
      return { url: "http://preview.example.invalid:8080" };
    case "status":
      return demoStatus;
    case "quota_overview":
      return unlimitedScenario ? unlimitedQuota : demoQuota;
    case "usage_status":
    case "usage_scan":
    case "usage_pair":
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
    case "meter_service_retry":
      return "RUNNING";
    case "meter_setup_error":
      return null;
    default:
      throw new Error(`[界面预览] 未配置演示响应：${command}`);
  }
});

void Promise.all([
  api.systemSession(),
  api.status(),
  api.quotaOverview(),
  api.usageStatus(),
  api.scanUsage(),
  api.pairMeter(),
  api.codexRateLimits(),
  api.usageAnalytics(1),
  api.usageAnalytics(7),
  api.usageAnalytics(30),
  api.usageEvents(0, 10),
  api.usageEvents(1, 10),
  api.meterRuntimeLogs(),
  api.meterServiceVersion(),
  api.retryMeterService(),
  api.meterSetupError(),
]).catch((error: unknown) => {
  console.error("[界面预览] 演示数据未通过 API 校验。", error);
});

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
            <Dashboard
              session={demoSession}
              onLogout={() => undefined}
              guardNotice={<PreviewGuardNotice />}
            />
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
