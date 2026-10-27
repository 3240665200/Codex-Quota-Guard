import { UsdQuotaDashboard } from "./usd-quota-dashboard";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  Activity,
  ChartNoAxesCombined,
  CircleAlert,
  Gauge,
  KeyRound,
  ListChecks,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  RefreshCw,
  Settings2,
  ShieldCheck,
  UserCircle,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { AppIcon } from "@/components/app-icon";
import { ClientSettings } from "@/components/client-settings";
import { LoginAssistance } from "@/components/login-assistance-gate";
import { SessionBinding } from "@/components/session-binding";
import { subscribeToAssistanceEvents } from "@/lib/assistance-events";
import {
  api,
  bindingGateEvent,
  getApiErrorMessage,
  type OfficialCodexRateLimits,
  type QuotaOverview,
  type SystemSession,
} from "@/lib/api";
import { formatPercent, formatResetTime, cn } from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import {
  UsageActivityPanel,
  serviceLabels,
} from "@/components/usage-activity-panel";
import { ThemeSwitcher } from "@/components/theme-switcher";
import { useQuotaDisplay } from "@/lib/quota-display";
import { officialQuotaStatus } from "@/lib/official-quota";
import { useClientTheme } from "@/lib/client-theme";
import { useTrayDashboard } from "./tray-dashboard-bridge";
import "./quota-layout.css";

function describeBlockReason(reason: string | null): string {
  switch (reason) {
    case "BILLING_PENDING":
      return "部分用量缺少价格、分项或速度模式，美元余额待核算，请联系管理员。";
    case "OFFICIAL_ACCOUNT_USD_QUOTA_EXCEEDED":
      return "关联官方账户的美元预算已用尽。";
    case "TOKEN_QUOTA_UNCONFIGURED":
      return "7 天基础额度尚未配置。";
    case "TEAM_UNAVAILABLE":
      return "所属团队不可用。";
    case "FIVE_HOUR_USD_QUOTA_EXCEEDED":
      return "已达到个人 5 小时 USD 限额。";
    case "WEEKLY_USD_QUOTA_EXCEEDED":
      return "已达到个人 7 天 USD 限额。";
    case "TEAM_FIVE_HOUR_USD_QUOTA_EXCEEDED":
      return "所属团队的 5 小时共享 USD 额度已用尽。";
    case "TEAM_WEEKLY_USD_QUOTA_EXCEEDED":
      return "所属团队的 7 天共享 USD 额度已用尽。";
    case "TEAM_TOTAL_USD_QUOTA_EXCEEDED":
      return "所属团队的累计 USD 总额度已用尽。";
    default:
      return "服务端暂无法确认当前个人 USD 额度，请联系管理员。";
  }
}

function LoadingCard({ className = "" }: { className?: string }) {
  return (
    <Card className={className} aria-label="正在加载">
      <CardHeader className="gap-3">
        <Skeleton className="h-5 w-2/5" />
        <Skeleton className="h-4 w-3/5" />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Skeleton className="h-3 w-full" />
        <Skeleton className="h-3 w-4/5" />
      </CardContent>
    </Card>
  );
}

function EmptyState({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <Card>
      <CardContent className="flex flex-col items-center justify-center gap-2 py-14 text-center">
        <Gauge className="mb-2 text-muted-foreground" aria-hidden="true" />
        <p className="font-medium">{title}</p>
        <p className="max-w-md text-sm text-muted-foreground">{description}</p>
      </CardContent>
    </Card>
  );
}

function TeamUsdSection({ overview }: { overview: QuotaOverview }) {
  return (
    <UsdQuotaDashboard
      billing={overview.billing}
      currentUserId={overview.user.id}
    />
  );
}

function OfficialRateLimitWindow({
  label,
  window,
}: {
  label: string;
  window: OfficialCodexRateLimits["windows"][number];
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const remainingPercent = Math.min(100, Math.max(0, 100 - window.usedPercent));
  const percent =
    mode === "remaining"
      ? remainingPercent
      : Math.min(100, Math.max(0, window.usedPercent));
  return (
    <div className="min-w-0">
      <div className="flex items-center justify-between gap-3 text-xs leading-4">
        <span className="font-medium">{label}</span>
        <span className="font-semibold tabular-nums text-primary">
          {displayLabel} {formatPercent(percent)}
        </span>
      </div>
      <div className="mt-2">
        <Progress
          aria-label={`${label}官方${displayLabel}额度`}
          className="h-2 bg-muted"
          indicatorClassName="bg-primary"
          value={percent}
        />
      </div>
      <p className="mt-3 text-xs text-muted-foreground">
        {window.resetsAt ? formatResetTime(window.resetsAt) : "重置时间未知"}
      </p>
    </div>
  );
}

function OfficialRateLimitsCard({
  rateLimits,
  loading,
  failed,
}: {
  rateLimits: OfficialCodexRateLimits | undefined;
  loading: boolean;
  failed: boolean;
}) {
  const fiveHour = rateLimits?.windows.find(
    (window) => window.windowDurationMins === 300,
  );
  const weekly = rateLimits?.windows.find(
    (window) => window.windowDurationMins === 10_080,
  );
  return (
    <section
      className="cqg-quota-section min-w-0"
      aria-labelledby="official-quota-heading"
    >
      <header className="cqg-quota-section-heading">
        <Gauge className="cqg-quota-section-icon" aria-hidden="true" />
        <div className="min-w-0">
          <h2 id="official-quota-heading">官方套餐参考</h2>
          <p>
            官方额度百分比 ·{" "}
            {loading
              ? "正在读取…"
              : failed
                ? "服务端套餐数据暂时无法读取"
                : rateLimits?.planType
                  ? `${rateLimits.planType} 套餐 · 账户级参考`
                  : "账户级参考"}
          </p>
        </div>
      </header>
      <div className="cqg-surface min-w-0 p-4">
        {rateLimits ? (
          <p className="mb-3 text-xs text-muted-foreground">
            {officialQuotaStatus(rateLimits)}
          </p>
        ) : null}
        {fiveHour || weekly ? (
          <div className="cqg-quota-grid gap-5">
            {fiveHour ? (
              <OfficialRateLimitWindow label="5 小时窗口" window={fiveHour} />
            ) : null}
            {weekly ? (
              <OfficialRateLimitWindow label="7 天窗口" window={weekly} />
            ) : null}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">暂无可显示的官方额度</p>
        )}
        <p className="mt-4 border-t pt-3 text-xs text-muted-foreground">
          服务端按管理员设置的间隔独立查询，退出桌面客户端后仍会更新。套餐百分比与美元预算单独核算。
        </p>
      </div>
    </section>
  );
}

function AccessCard({ overview }: { overview: QuotaOverview }) {
  if (overview.allowed) {
    return (
      <div
        className="cqg-status-strip cqg-status-ok flex flex-wrap items-center gap-3 px-4 py-3"
        role="status"
      >
        <ShieldCheck
          aria-hidden="true"
          className="size-5 shrink-0 text-[var(--cqg-status-success)]"
        />
        <span className="font-semibold">额度可用</span>
        <span className="text-sm text-muted-foreground">
          尚未达到已设置的 USD 上限
        </span>
      </div>
    );
  }

  const exhausted =
    overview.blockReason === "FIVE_HOUR_USD_QUOTA_EXCEEDED" ||
    overview.blockReason === "WEEKLY_USD_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_FIVE_HOUR_USD_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_WEEKLY_USD_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_TOTAL_USD_QUOTA_EXCEEDED";
  return (
    <div
      className="cqg-status-strip cqg-status-error flex flex-wrap items-center gap-3 px-4 py-3"
      role="alert"
    >
      <CircleAlert
        aria-hidden="true"
        className="size-5 shrink-0 text-[var(--cqg-status-error)]"
      />
      <span className="font-semibold">
        {exhausted
          ? "USD 额度已用尽"
          : overview.blockReason === "TOKEN_QUOTA_UNCONFIGURED"
            ? "USD 额度未配置"
            : "额度暂不可用"}
      </span>
      <span className="text-sm text-muted-foreground">
        {describeBlockReason(overview.blockReason)}
        中央服务器会按管理员配置的额度限制规则处理绑定会话。
      </span>
    </div>
  );
}

export interface DashboardProps {
  session: SystemSession;
  onLogout: () => void;
  guardNotice?: ReactNode;
}

const workspaces = [
  {
    id: "quota",
    label: "额度总览",
    title: "额度仪表盘",
    description: "团队共享额度、成员用量与官方额度百分比。",
    Icon: Gauge,
  },
  {
    id: "login-assistance",
    label: "登录辅助",
    title: "登录辅助",
    description: "接收所属团队官方账户本次登录的新验证码。",
    Icon: KeyRound,
  },
  {
    id: "analytics",
    label: "用量统计",
    title: "用量统计",
    description: "Token 构成、使用趋势与 USD 费用估算。",
    Icon: ChartNoAxesCombined,
  },
  {
    id: "ledger",
    label: "消耗流水",
    title: "消耗流水",
    description: "已接收的用量记录，点击详情查看完整分项。",
    Icon: ListChecks,
  },
  {
    id: "diagnostics",
    label: "统计程序",
    title: "统计程序",
    description: "运行状态、版本更新与本机诊断日志。",
    Icon: Activity,
  },
  {
    id: "settings",
    label: "设置",
    title: "设置",
    description: "管理桌面客户端的启动偏好。",
    Icon: Settings2,
  },
] as const;
type WorkspaceId = (typeof workspaces)[number]["id"];

export function Dashboard({ session, onLogout, guardNotice }: DashboardProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  // Keep the shared invalidation stream alive for this signed-in dashboard;
  // navigating away from the assistance cards must not reopen the native SSE.
  useEffect(
    () => subscribeToAssistanceEvents(session.user!.id, () => {}),
    [session.user!.id],
  );
  const [accessEpoch, setAccessEpoch] = useState(0);
  const [checkingReturn, setCheckingReturn] = useState(false);
  const [now, setNow] = useState(Date.now);
  const accessQuery = useQuery({
    queryKey: ["client-access", session.user!.id, accessEpoch],
    queryFn: api.clientAccess,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchInterval: 5_000,
    refetchOnMount: "always",
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  });
  const recheckAccess = useCallback(
    () => setAccessEpoch((value) => value + 1),
    [],
  );
  const bindingAllowed =
    accessQuery.isSuccess &&
    !accessQuery.isError &&
    accessQuery.data.allowed &&
    now - accessQuery.dataUpdatedAt < 10_000;
  const blockingReturn = bindingAllowed && checkingReturn;
  const { refetch: refetchAccess } = accessQuery;
  const handleBound = (bindingId: string | null) => {
    if (bindingId) window.dispatchEvent(new Event("quota-guard:assistance-revoked"));
    // Restoring the already-authorized record is not a new binding or a revocation.
    if (bindingAllowed && bindingId === accessQuery.data.bindingId) return;
    // A completion is only a hint: the central access result still decides access.
    void refetchAccess({ cancelRefetch: false });
  };
  useEffect(() => {
    let disposed = false;
    let returnCheck = 0;
    setCheckingReturn(false);
    const recheckOnReturn = () => {
      // Native title-bar interactions can refocus the WebView. Revalidate the
      // current user's query without tearing down a still-fresh workspace.
      const check = ++returnCheck;
      setNow(Date.now());
      setCheckingReturn(true);
      void refetchAccess({ cancelRefetch: false }).finally(() => {
        if (!disposed && check === returnCheck) setCheckingReturn(false);
      });
    };
    const onVisible = () => {
      if (document.visibilityState === "visible") recheckOnReturn();
    };
    window.addEventListener(bindingGateEvent, recheckAccess);
    window.addEventListener("online", recheckAccess);
    window.addEventListener("offline", recheckAccess);
    window.addEventListener("focus", recheckOnReturn);
    document.addEventListener("visibilitychange", onVisible);
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => {
      disposed = true;
      window.removeEventListener(bindingGateEvent, recheckAccess);
      window.removeEventListener("online", recheckAccess);
      window.removeEventListener("offline", recheckAccess);
      window.removeEventListener("focus", recheckOnReturn);
      document.removeEventListener("visibilitychange", onVisible);
      window.clearInterval(timer);
    };
  }, [recheckAccess, refetchAccess, accessEpoch, session.user!.id]);
  const [workspace, setWorkspace] = useState<WorkspaceId>("quota");
  const accessResolvedFor = useRef<string | null>(null);
  if (!accessQuery.isPending) accessResolvedFor.current = session.user!.id;
  const [collapsed, setCollapsed] = useState(false);
  const scrollArea = useRef<HTMLDivElement>(null);
  const visibleWorkspace = bindingAllowed ? workspace : "login-assistance";
  const showAssistance =
    visibleWorkspace === "login-assistance" &&
    (workspace === "login-assistance" ||
      accessResolvedFor.current === session.user!.id);
  const currentPage = workspaces.find((page) => page.id === visibleWorkspace)!;
  const navigate = (next: WorkspaceId) => {
    if (blockingReturn) return;
    if (!bindingAllowed && next !== "login-assistance") return;
    setWorkspace(next);
    if (scrollArea.current) scrollArea.current.scrollTop = 0;
  };
  const quotaQuery = useQuery({
    queryKey: ["quota-overview"],
    queryFn: api.quotaOverview,
    retry: false,
    refetchInterval: 10_000,
    enabled: bindingAllowed,
  });
  const usageStatusQuery = useQuery({
    queryKey: ["usage-status"],
    queryFn: api.usageStatus,
    retry: false,
    refetchInterval: 5_000,
  });
  // Keep version detection active even when the diagnostics page is closed.
  const meterVersionQuery = useQuery({
    queryKey: ["meter-service-version"],
    queryFn: api.meterServiceVersion,
    retry: false,
    refetchInterval: 30_000,
  });
  const overview = quotaQuery.data;
  const rateLimitsQuery = useQuery({
    queryKey: ["official-codex-rate-limits"],
    queryFn: api.codexRateLimits,
    retry: false,
    refetchInterval: 60_000,
    enabled: bindingAllowed,
  });
  const isRefreshing =
    quotaQuery.isFetching ||
    usageStatusQuery.isFetching ||
    rateLimitsQuery.isFetching;
  const { mode: trayMode } = useQuotaDisplay();
  const { resolvedTheme } = useClientTheme();
  const traySnapshot = useMemo(
    () => ({
      userName: session.user!.displayName,
      bindingAllowed,
      personal:
        bindingAllowed && overview
          ? {
              fiveHour: overview.billing.personal.fiveHour,
              weekly: overview.billing.personal.weekly,
            }
          : null,
      account:
        bindingAllowed && overview?.billing.officialAccount
          ? {
              label: overview.billing.officialAccount.label,
              total: overview.billing.officialAccount.quota.total,
            }
          : null,
      official: bindingAllowed ? (rateLimitsQuery.data ?? null) : null,
      updatedAt:
        bindingAllowed && quotaQuery.dataUpdatedAt
          ? new Date(quotaQuery.dataUpdatedAt).toISOString()
          : null,
      quotaFailed: quotaQuery.isError,
      officialFailed: rateLimitsQuery.isError,
      meterRunning: ["UP_TO_DATE", "REPORTED", "BASELINE_READY"].includes(
        usageStatusQuery.data?.state ?? "",
      ),
      refreshing: isRefreshing,
      mode: trayMode,
      theme: resolvedTheme,
    }),
    [
      session.user,
      bindingAllowed,
      overview,
      rateLimitsQuery.data,
      quotaQuery.dataUpdatedAt,
      quotaQuery.isError,
      rateLimitsQuery.isError,
      usageStatusQuery.data?.state,
      isRefreshing,
      trayMode,
      resolvedTheme,
    ],
  );
  useTrayDashboard(traySnapshot, (action) => {
    if (action === "refresh") {
      // A hidden main window still owns the Axios session; recheck the device binding before fetching display data.
      void accessQuery
        .refetch({ cancelRefetch: false })
        .then(async (result) => {
          if (!result.data?.allowed || result.isError) return;
          await Promise.all([
            quotaQuery.refetch(),
            rateLimitsQuery.refetch(),
            usageStatusQuery.refetch(),
          ]);
        })
        .catch(() => {});
    } else if (workspaces.some((page) => page.id === action)) {
      // The focus recheck may be in flight after opening from the tray. The render-time binding gate still applies.
      setWorkspace(action as WorkspaceId);
    }
  });
  const refresh = async () => {
    if (blockingReturn) return;
    window.dispatchEvent(new Event("quota-guard:assistance-refresh"));
    if (!bindingAllowed) {
      recheckAccess();
      return;
    }
    try {
      const results = await Promise.all([
        quotaQuery.refetch(),
        usageStatusQuery.refetch(),
        rateLimitsQuery.refetch(),
        meterVersionQuery.refetch(),
      ]);
      await Promise.all(
        ["usage-analytics", "usage-events", "meter-runtime-logs"].map((key) =>
          queryClient.invalidateQueries({ queryKey: [key, session.user?.id] }),
        ),
      );
      const failedResult = results.find((result) => result.error);
      if (failedResult?.error) {
        throw failedResult.error;
      }
      toast.success(t("dataRefreshed"));
    } catch (error) {
      toast.error(`${t("refreshFailed")}：${getApiErrorMessage(error)}`);
    }
  };

  const requestError = quotaQuery.error;
  const statusOnline = accessQuery.isSuccess && !accessQuery.isError;
  const meterState = usageStatusQuery.data?.state;
  const meterVersion = meterVersionQuery.data;
  const runtimeVersion = usageStatusQuery.data?.version;
  const upgradeRequired =
    meterVersion?.needsUpgrade || meterVersion?.status === "UPGRADE_REQUIRED";
  const versionUnverified =
    meterVersionQuery.isError ||
    meterVersionQuery.isPending ||
    meterVersion?.status === "UNKNOWN" ||
    (!!runtimeVersion &&
      !!meterVersion?.installedVersion &&
      runtimeVersion !== meterVersion.installedVersion) ||
    ((meterState === "UP_TO_DATE" || meterState === "REPORTED") &&
      !runtimeVersion);
  const meterHealthy =
    !usageStatusQuery.isError &&
    !upgradeRequired &&
    !versionUnverified &&
    (meterState === "UP_TO_DATE" ||
      meterState === "REPORTED" ||
      meterState === "BASELINE_READY");
  const meterLabel = usageStatusQuery.isError
    ? "统计状态读取失败"
    : upgradeRequired
      ? "统计程序待更新"
      : meterState === "NOT_INSTALLED" ||
          meterState === "OFFLINE" ||
          meterState === "ERROR" ||
          meterState === "UNPAIRED"
        ? serviceLabels[meterState]
        : versionUnverified
          ? "统计版本待确认"
          : meterState
            ? serviceLabels[meterState]
            : "正在读取统计状态…";

  return (
    <div className="cqg-dashboard cqg-shell" data-collapsed={collapsed}>
      <aside
        className="cqg-sidebar cqg-scrollbar-hidden"
        aria-label="客户端导航"
      >
        <div className="cqg-brand">
          <AppIcon className="size-9 shrink-0" />
          <div className="cqg-nav-copy min-w-0">
            <p className="whitespace-nowrap text-sm font-semibold tracking-tight">
              Quota Guard
            </p>
            <p className="mt-0.5 text-[10px] tracking-widest text-muted-foreground">
              用量与额度工作台
            </p>
          </div>
        </div>
        <nav className="flex-1 space-y-1 px-3 pt-5" aria-label="工作区">
          <p className="cqg-nav-copy mb-3 px-3 text-[10px] font-medium tracking-widest text-muted-foreground">
            工作区
          </p>
          {workspaces.map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              disabled={
                blockingReturn || (!bindingAllowed && id !== "login-assistance")
              }
              onClick={() => navigate(id)}
              aria-label={label}
              title={label}
              aria-current={visibleWorkspace === id ? "page" : undefined}
              className={cn(
                "cqg-nav-item",
                visibleWorkspace === id && "cqg-nav-item-active",
                !bindingAllowed &&
                  id !== "login-assistance" &&
                  "cursor-not-allowed opacity-40",
              )}
            >
              <Icon className="size-[18px] shrink-0" aria-hidden="true" />
              <span className="cqg-nav-copy truncate">{label}</span>
            </button>
          ))}
        </nav>
        <div className="cqg-sidebar-bottom">
          <button
            type="button"
            className="cqg-meter-link"
            disabled={!bindingAllowed || blockingReturn}
            onClick={() => navigate("diagnostics")}
            title={meterLabel}
            aria-label={`查看统计程序：${meterLabel}`}
          >
            <span
              aria-hidden="true"
              className={cn(
                "size-2 shrink-0 rounded-full",
                meterHealthy
                  ? "bg-[var(--cqg-status-success)]"
                  : "bg-[var(--cqg-status-warning)]",
              )}
            />
            <span className="cqg-nav-copy truncate text-xs">{meterLabel}</span>
          </button>
          <div className="cqg-theme-row">
            <span className="cqg-nav-copy text-xs text-muted-foreground">
              外观
            </span>
            <ThemeSwitcher compact className="cqg-sidebar-themes" />
          </div>
          <div className="cqg-sidebar-account">
            <UserCircle
              aria-hidden="true"
              className="cqg-account-avatar size-8 shrink-0 rounded-full bg-accent p-1.5 text-accent-foreground"
            />
            <div
              className="cqg-nav-copy min-w-0 flex-1"
              title={session.user?.email}
            >
              <p className="truncate text-xs font-medium">
                {session.user?.displayName}
              </p>
              <p className="mt-0.5 truncate text-[10px] text-muted-foreground">
                {session.user?.email}
              </p>
            </div>
            <Button
              variant="ghost"
              size="icon"
              className="size-8 shrink-0"
              onClick={() => {
                // Clear renderer-only codes; authenticated logout owns server cancellation.
                window.dispatchEvent(new Event("quota-guard:assistance-clear"));
                onLogout();
              }}
              aria-label="退出系统"
              title="退出系统"
            >
              <LogOut className="size-4" aria-hidden="true" />
            </Button>
          </div>
        </div>
      </aside>
      <main className="cqg-workspace">
        <header className="cqg-dashboard-header flex min-h-[60px] shrink-0 items-center justify-between gap-3 border-b px-4 py-2">
          <div className="flex min-w-0 items-center gap-3">
            <Button
              variant="ghost"
              size="icon"
              className="cqg-collapse-button size-8 shrink-0 text-muted-foreground"
              onClick={() => setCollapsed(!collapsed)}
              aria-expanded={!collapsed}
              aria-label={collapsed ? "展开导航" : "收起导航"}
              title={collapsed ? "展开导航" : "收起导航"}
            >
              {collapsed ? (
                <PanelLeftOpen aria-hidden="true" />
              ) : (
                <PanelLeftClose aria-hidden="true" />
              )}
            </Button>
            <div className="min-w-0">
              <h1 className="text-base font-semibold">{currentPage.title}</h1>
              <p className="cqg-page-description mt-1 truncate text-xs text-muted-foreground">
                {blockingReturn
                  ? "正在重新核验本设备绑定…"
                  : currentPage.description}
              </p>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-3">
            <span className="cqg-connection hidden items-center gap-2 text-xs text-muted-foreground sm:inline-flex">
              <span
                aria-hidden="true"
                className={cn(
                  "size-1.5 rounded-full",
                  statusOnline
                    ? "bg-[var(--cqg-status-success)]"
                    : "bg-[var(--cqg-status-warning)]",
                )}
              />
              {statusOnline ? t("connected") : t("serviceUnavailable")}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={refresh}
              disabled={isRefreshing || blockingReturn}
              aria-label={isRefreshing ? t("refreshing") : t("refresh")}
              title={isRefreshing ? t("refreshing") : t("refresh")}
            >
              <RefreshCw
                aria-hidden="true"
                className={
                  isRefreshing ? "motion-safe:animate-spin" : undefined
                }
              />
              <span className="hidden sm:inline">刷新</span>
            </Button>
          </div>
        </header>
        {guardNotice ? (
          <aside
            aria-label="运行注意事项"
            className="cqg-scrollbar-hidden max-h-[30vh] shrink-0 overflow-y-auto border-b bg-muted/50 px-4 py-1.5 text-xs"
          >
            {guardNotice}
          </aside>
        ) : null}
        <div
          className="cqg-workspace-scroll cqg-scrollbar-hidden"
          ref={scrollArea}
          tabIndex={0}
          role="region"
          aria-label={`${currentPage.label}内容`}
          aria-busy={blockingReturn || undefined}
          onClickCapture={(event) => {
            if (blockingReturn) {
              event.preventDefault();
              event.stopPropagation();
            }
          }}
          onSubmitCapture={(event) => {
            if (blockingReturn) {
              event.preventDefault();
              event.stopPropagation();
            }
          }}
        >
          <div
            className="cqg-workspace-content mx-auto flex w-full max-w-[1240px] flex-col gap-4 p-4"
            {...{ inert: blockingReturn ? "" : undefined }}
          >
            {!bindingAllowed ? (
              <Alert>
                <AlertTitle>
                  {accessQuery.isFetching
                    ? "正在核对本设备绑定"
                    : accessQuery.isError
                      ? "绑定状态核验失败"
                      : "请先绑定本设备"}
                </AlertTitle>
                <AlertDescription>
                  {accessQuery.isError
                    ? "无法确认有效绑定，其他菜单暂不可用。已保存的绑定记录不代表当前访问权限已核验通过，请检查连接后重新核对。"
                    : "完成本设备官方会话绑定后，中央后台确认有效才会开放其他菜单。"}
                  {usageStatusQuery.data?.state === "NOT_INSTALLED" ? (
                    <p className="mt-2">
                      统计程序未安装。请重新运行客户端安装器；此处不会自动安装。
                    </p>
                  ) : null}
                  {accessQuery.isError ? (
                    <p>{getApiErrorMessage(accessQuery.error)}</p>
                  ) : null}
                  <Button
                    variant="outline"
                    size="sm"
                    className="ml-3"
                    onClick={recheckAccess}
                  >
                    重新核对绑定
                  </Button>
                </AlertDescription>
              </Alert>
            ) : null}
            {showAssistance ? (
              <SessionBinding
                key={`binding:${session.user!.id}`}
                userId={session.user!.id}
                onBound={handleBound}
              />
            ) : null}
            {bindingAllowed && requestError ? (
              <Alert variant="destructive">
                <CircleAlert aria-hidden="true" />
                <AlertTitle>{t("serviceUnavailable")}</AlertTitle>
                <AlertDescription className="flex flex-wrap items-center gap-3">
                  <span>{getApiErrorMessage(requestError)}</span>
                  <Button variant="outline" size="sm" onClick={refresh}>
                    {t("retry")}
                  </Button>
                </AlertDescription>
              </Alert>
            ) : null}

            {bindingAllowed && visibleWorkspace === "quota" ? (
              quotaQuery.isPending ? (
                <div className="grid gap-4 lg:grid-cols-2">
                  <LoadingCard />
                  <LoadingCard />
                  <LoadingCard className="lg:col-span-2" />
                </div>
              ) : overview ? (
                <>
                  <AccessCard overview={overview} />
                  <TeamUsdSection overview={overview} />
                  <OfficialRateLimitsCard
                    rateLimits={rateLimitsQuery.data}
                    loading={rateLimitsQuery.isPending}
                    failed={rateLimitsQuery.isError}
                  />
                </>
              ) : (
                <EmptyState
                  title="暂时无法读取个人额度"
                  description="请刷新重试；如问题持续，请联系管理员。"
                />
              )
            ) : visibleWorkspace === "login-assistance" ? (
              showAssistance ? (
                <LoginAssistance
                  key={`assistance:${session.user!.id}`}
                  userId={session.user!.id}
                />
              ) : null
            ) : bindingAllowed && visibleWorkspace === "settings" ? (
              <ClientSettings />
            ) : bindingAllowed ? (
              <UsageActivityPanel
                key={session.user!.id}
                view={
                  visibleWorkspace === "analytics"
                    ? "overview"
                    : visibleWorkspace === "ledger"
                      ? "ledger"
                      : "diagnostics"
                }
                userId={session.user!.id}
                measuredAt={overview?.usage.measuredAt ?? null}
                status={usageStatusQuery.data}
                statusError={usageStatusQuery.error}
              />
            ) : null}
          </div>
        </div>
      </main>
    </div>
  );
}
