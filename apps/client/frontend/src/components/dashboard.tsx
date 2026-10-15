import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  useCallback,
  useEffect,
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
  Users,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { AppIcon } from "@/components/app-icon";
import { ClientSettings } from "@/components/client-settings";
import { LoginAssistance } from "@/components/login-assistance";
import { SessionBinding } from "@/components/session-binding";
import { OfficialLoginHelper } from "@/components/official-login-helper";
import { subscribeToAssistanceEvents } from "@/lib/assistance-events";
import {
  api,
  bindingGateEvent,
  getApiErrorMessage,
  type OfficialCodexRateLimits,
  type QuotaOverview,
  type SystemSession,
} from "@/lib/api";
import {
  formatCompactTokenCount,
  formatPercent,
  formatResetTime,
  formatTokenCount,
  cn,
} from "@/lib/utils";
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
import "./quota-layout.css";

function describeBlockReason(reason: string | null): string {
  switch (reason) {
    case "TOKEN_QUOTA_UNCONFIGURED":
      return "7 天基础额度尚未配置。";
    case "TEAM_UNAVAILABLE":
      return "所属团队不可用。";
    case "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED":
      return "已达到个人 5 小时 Token 限额。";
    case "WEEKLY_TOKEN_QUOTA_EXCEEDED":
      return "已达到个人 7 天 Token 限额。";
    case "TEAM_FIVE_HOUR_TOKEN_QUOTA_EXCEEDED":
      return "所属团队的 5 小时共享 Token 额度已用尽。";
    case "TEAM_WEEKLY_TOKEN_QUOTA_EXCEEDED":
      return "所属团队的 7 天共享 Token 额度已用尽。";
    case "TEAM_TOTAL_TOKEN_QUOTA_EXCEEDED":
      return "所属团队的累计 Token 总额度已用尽。";
    default:
      return "服务端暂无法确认当前个人 Token 额度，请联系管理员。";
  }
}

function TokenAmount({ value }: { value: number }) {
  return (
    <span
      className="cqg-token-amount"
      title={`精确值：${formatTokenCount(value)} Token`}
    >
      <span>{formatCompactTokenCount(value)}</span>{" "}
      <span className="cqg-token-unit">Token</span>
    </span>
  );
}

function QuotaProgress({
  remaining,
  limit,
  label,
}: {
  remaining: number | null;
  limit: number | null;
  label: string;
}) {
  if (limit === null || remaining === null) {
    return (
      <div className="flex justify-between gap-3 text-xs text-muted-foreground">
        <span>{label}</span>
        <span>{limit === null ? "不设限" : "暂不可得"}</span>
      </div>
    );
  }
  const percent =
    limit === 0 ? 0 : Math.min(100, Math.max(0, (remaining * 100) / limit));
  const exhausted = remaining <= 0 || limit === 0;
  return (
    <div
      className="flex flex-col gap-2"
      role="group"
      aria-label={`${label}剩余额度`}
    >
      <div className="flex items-center justify-between gap-3 text-xs text-muted-foreground">
        <span>{label}</span>
        <span className="tabular-nums font-medium text-foreground">
          剩余{" "}
          {new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(
            percent,
          )}
          %
        </span>
      </div>
      <Progress
        aria-label={`${label}剩余额度`}
        className="h-2 bg-muted"
        indicatorClassName={
          exhausted
            ? "bg-[var(--cqg-status-error)]"
            : percent <= 20
              ? "bg-[var(--cqg-status-warning)]"
              : "bg-primary"
        }
        value={percent}
      />
    </div>
  );
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

function TeamCard({ overview }: { overview: QuotaOverview }) {
  const { team, teamQuota } = overview;
  return (
    <section
      className="cqg-surface cqg-summary-card min-w-0 p-4"
      aria-label="团队共享额度"
    >
      <div className="cqg-summary-card-header mb-4 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-base font-semibold">团队共享额度</h2>
          <p className="mt-1 truncate text-xs text-muted-foreground">
            {teamQuota
              ? `${teamQuota.name} · ${teamQuota.memberCount} 位成员`
              : team
                ? team.name
                : "未加入团队"}
          </p>
        </div>
        <Users aria-hidden="true" className="size-5 shrink-0 text-primary" />
      </div>
      {teamQuota ? (
        <div className="cqg-summary-card-content space-y-3">
          <div>
            <QuotaProgress
              label="累计总额"
              remaining={teamQuota.totalRemainingTokens}
              limit={teamQuota.totalTokenLimit}
            />
            <div className="mt-3 flex flex-wrap justify-between gap-2 text-xs tabular-nums text-muted-foreground">
              {teamQuota.totalRemainingTokens !== null ? (
                <span>
                  <TokenAmount value={teamQuota.totalRemainingTokens} /> 剩余
                </span>
              ) : null}
              {teamQuota.totalTokenLimit !== null ? (
                <span>
                  上限 <TokenAmount value={teamQuota.totalTokenLimit} />
                </span>
              ) : null}
            </div>
          </div>
          <div className="border-t pt-3">
            <QuotaProgress
              label="5 小时团队"
              remaining={teamQuota.fiveHourRemainingTokens}
              limit={teamQuota.fiveHourTokenLimit}
            />
            <div className="mt-3 flex flex-wrap justify-between gap-2 text-xs tabular-nums text-muted-foreground">
              {teamQuota.fiveHourRemainingTokens !== null ? (
                <span>
                  <TokenAmount value={teamQuota.fiveHourRemainingTokens} /> 剩余
                </span>
              ) : null}
              {teamQuota.fiveHourTokenLimit !== null ? (
                <span>
                  上限 <TokenAmount value={teamQuota.fiveHourTokenLimit} />
                </span>
              ) : null}
            </div>
          </div>
          <div className="border-t pt-3">
            <QuotaProgress
              label="7 天团队"
              remaining={teamQuota.weeklyRemainingTokens}
              limit={teamQuota.weeklyTokenLimit}
            />
            <div className="mt-3 flex flex-wrap justify-between gap-2 text-xs tabular-nums text-muted-foreground">
              {teamQuota.weeklyRemainingTokens !== null ? (
                <span>
                  <TokenAmount value={teamQuota.weeklyRemainingTokens} /> 剩余
                </span>
              ) : null}
              {teamQuota.weeklyTokenLimit !== null ? (
                <span>
                  上限 <TokenAmount value={teamQuota.weeklyTokenLimit} />
                </span>
              ) : null}
            </div>
          </div>
        </div>
      ) : (
        <p className="cqg-summary-card-content text-sm text-muted-foreground">
          暂无团队共享额度
        </p>
      )}
    </section>
  );
}

function UsageWindow({
  label,
  remainingTokens,
  limitTokens,
}: {
  label: string;
  remainingTokens: number | null;
  limitTokens: number | null;
}) {
  const nearLimit =
    remainingTokens !== null &&
    limitTokens !== null &&
    remainingTokens <= Math.max(1, Math.floor(limitTokens * 0.2));
  return (
    <article className="cqg-window cqg-usage-window min-w-0 p-4">
      <header className="cqg-usage-window-header flex items-start justify-between gap-3">
        <h3 className="text-sm font-semibold">{label}</h3>
        <span className="text-xs text-muted-foreground">
          {limitTokens === null ? "不设限" : "个人上限"}
        </span>
      </header>
      <div className="cqg-usage-window-primary">
        <p className="cqg-usage-window-label text-xs text-muted-foreground">
          个人剩余
        </p>
        <p
          className={`cqg-usage-window-value break-words font-semibold tracking-tight tabular-nums ${nearLimit ? "text-[var(--cqg-status-warning)]" : "text-foreground"}`}
        >
          {limitTokens === null ? (
            "不限额"
          ) : remainingTokens === null ? (
            "暂不可得"
          ) : (
            <TokenAmount value={remainingTokens} />
          )}
        </p>
      </div>
      <footer className="cqg-usage-window-footer">
        {limitTokens === null ? (
          <p className="text-xs leading-4 text-muted-foreground">
            本窗口只记录用量，不参与个人限额判断
          </p>
        ) : (
          <>
            <QuotaProgress
              label={`${label}个人额度`}
              remaining={remainingTokens}
              limit={limitTokens}
            />
            <div className="cqg-usage-window-metrics mt-2 flex flex-wrap justify-between gap-x-2 gap-y-1 text-xs tabular-nums text-muted-foreground">
              <span>
                上限 <TokenAmount value={limitTokens} />
              </span>
            </div>
          </>
        )}
      </footer>
    </article>
  );
}

function UsageCard({ overview }: { overview: QuotaOverview }) {
  const { usage, policy } = overview;
  return (
    <section aria-labelledby="personal-quota-heading">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2
            id="personal-quota-heading"
            className="text-base font-semibold"
            title="个人额度按事件发生时间滚动统计"
          >
            个人 Token
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">
            {usage.measuredAt
              ? `最后上报 ${new Date(usage.measuredAt).toLocaleString("zh-CN")}`
              : "尚无已接收的用量"}
          </p>
        </div>
      </div>
      <div className="cqg-quota-grid">
        <UsageWindow
          label="5 小时"
          remainingTokens={usage.fiveHourRemainingTokens}
          limitTokens={policy.fiveHourTokenLimit}
        />
        <UsageWindow
          label="7 天"
          remainingTokens={usage.weeklyRemainingTokens}
          limitTokens={policy.weeklyTokenLimit}
        />
      </div>
    </section>
  );
}

function OfficialRateLimitWindow({
  label,
  window,
}: {
  label: string;
  window: OfficialCodexRateLimits["windows"][number];
}) {
  const remainingPercent = Math.min(100, Math.max(0, 100 - window.usedPercent));
  return (
    <div className="min-w-0">
      <div className="flex items-center justify-between gap-3 text-xs leading-4">
        <span className="font-medium">{label}</span>
        <span className="font-semibold tabular-nums text-primary">
          剩余 {formatPercent(remainingPercent)}
        </span>
      </div>
      <div className="mt-2">
        <Progress
          aria-label={`${label}官方剩余额度`}
          className="h-2 bg-muted"
          indicatorClassName="bg-primary"
          value={remainingPercent}
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
      className="cqg-surface cqg-summary-card min-w-0 p-4"
      aria-labelledby="official-quota-heading"
    >
      <div className="cqg-summary-card-header mb-4 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id="official-quota-heading" className="text-base font-semibold">
            官方 Codex 额度
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">
            {loading
              ? "正在读取…"
              : failed
                ? "读取失败，请检查官方登录"
                : rateLimits?.planType
                  ? `${rateLimits.planType} 套餐 · 账户级参考`
                  : "账户级参考"}
          </p>
        </div>
        <Gauge aria-hidden="true" className="size-5 shrink-0 text-primary" />
      </div>
      <div className="cqg-summary-card-content">
        {fiveHour || weekly ? (
          <div className="grid gap-5">
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
      </div>
      <p className="mt-4 border-t pt-3 text-xs text-muted-foreground">
        官方账户额度仅作参考，不计入个人 Token。
      </p>
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
          尚未达到已设置的 Token 上限
        </span>
      </div>
    );
  }

  const exhausted =
    overview.blockReason === "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "WEEKLY_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_FIVE_HOUR_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_WEEKLY_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_TOTAL_TOKEN_QUOTA_EXCEEDED";
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
          ? "Token 额度已用尽"
          : overview.blockReason === "TOKEN_QUOTA_UNCONFIGURED"
            ? "Token 额度未配置"
            : "额度暂不可用"}
      </span>
      <span className="text-sm text-muted-foreground">
        {describeBlockReason(overview.blockReason)}
        本应用不会退出官方 Codex 应用或 CLI。
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
    description: "个人、团队与官方账户额度，分别查看。",
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
  const statusQuery = useQuery({
    queryKey: ["status"],
    queryFn: api.status,
    retry: false,
    refetchInterval: 30_000,
  });
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
    enabled: bindingAllowed,
  });
  // Keep version detection active even when the diagnostics page is closed.
  const meterVersionQuery = useQuery({
    queryKey: ["meter-service-version"],
    queryFn: api.meterServiceVersion,
    retry: false,
    refetchInterval: 30_000,
    enabled: bindingAllowed,
  });
  const scanMutation = useMutation({
    mutationFn: api.scanUsage,
    onSuccess: async (status) => {
      queryClient.setQueryData(["usage-status"], status);
      void queryClient.invalidateQueries({
        queryKey: ["usage-events", session.user?.id],
      });
      void queryClient.invalidateQueries({
        queryKey: ["meter-runtime-logs", session.user?.id],
      });
      await quotaQuery.refetch();
      toast[
        status.fallback.state === "REPORT_FAILED" ||
        status.fallback.state === "SCAN_FAILED"
          ? "error"
          : "success"
      ]("扫描完成，请查看统计程序状态。");
    },
    onError: (error) => toast.error(`扫描失败：${getApiErrorMessage(error)}`),
  });
  const pairMutation = useMutation({
    mutationFn: api.pairMeter,
    onSuccess: (status) => {
      queryClient.setQueryData(["usage-status"], status);
      toast[status.pairingState === "PAIRING_SENT" ? "success" : "error"](
        status.pairingState === "PAIRING_SENT"
          ? "设备配对已发送，统计程序会在几秒内接收。"
          : "设备配对未完成，请检查统计程序与后台连接。",
      );
    },
    onError: (error) => toast.error(`配对失败：${getApiErrorMessage(error)}`),
  });
  const startMeterMutation = useMutation({
    mutationFn: api.retryMeterService,
    onSuccess: async (result) => {
      if (
        result === "RUNNING" ||
        result === "STARTED" ||
        result === "INSTALLED"
      ) {
        toast.success(
          result === "INSTALLED"
            ? "统计程序已安装或更新并启动。"
            : "统计程序已启动，会在关闭客户端后继续运行。",
        );
        await Promise.all([
          usageStatusQuery.refetch(),
          queryClient.invalidateQueries({
            queryKey: ["meter-service-version"],
          }),
        ]);
      } else if (result === "NEEDS_ADMIN") {
        toast.error(
          "请从托盘退出客户端，再右键以管理员身份运行，然后重试更新统计程序。",
        );
      } else if (result === "NOT_INSTALLED") {
        toast.warning("安装包缺少统计程序文件，请重新安装最新版客户端。");
      } else if (result === "SERVER_UNAVAILABLE") {
        toast.error("无法连接后台，请先检查服务器地址，再重试安装。");
      } else if (result === "INSTALL_FAILED") {
        toast.error(
          (await api.meterSetupError().catch(() => null)) ??
            "统计程序安装失败，请查看应用数据目录中的 meter-setup-status.json。",
        );
      } else {
        toast.error("统计程序仍未运行，请检查 Windows 服务状态。");
      }
      if (
        result !== "RUNNING" &&
        result !== "STARTED" &&
        result !== "INSTALLED"
      ) {
        void queryClient.invalidateQueries({
          queryKey: ["meter-service-version"],
        });
      }
    },
    onError: () => toast.error("统计程序启动请求失败，请稍后重试。"),
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
    statusQuery.isFetching ||
    quotaQuery.isFetching ||
    usageStatusQuery.isFetching ||
    rateLimitsQuery.isFetching;
  const refresh = async () => {
    if (blockingReturn) return;
    window.dispatchEvent(new Event("quota-guard:assistance-refresh"));
    if (!bindingAllowed) {
      recheckAccess();
      return;
    }
    try {
      const results = await Promise.all([
        statusQuery.refetch(),
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

  const requestError = [statusQuery.error, quotaQuery.error].find(Boolean);
  const statusOnline = statusQuery.data?.state === "online";
  const meterState = usageStatusQuery.data?.service.state;
  const meterVersion = meterVersionQuery.data;
  const runtimeVersion = usageStatusQuery.data?.service.version;
  const upgradeRequired =
    meterVersion?.needsUpgrade || meterVersion?.status === "UPGRADE_REQUIRED";
  const fallbackFailed = ["REPORT_FAILED", "SCAN_FAILED"].includes(
    usageStatusQuery.data?.fallback.state ?? "",
  );
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
    !fallbackFailed &&
    !versionUnverified &&
    (meterState === "UP_TO_DATE" ||
      meterState === "REPORTED" ||
      meterState === "BASELINE_READY");
  const meterLabel = usageStatusQuery.isError
    ? "统计状态读取失败"
    : upgradeRequired
      ? "统计程序待更新"
      : fallbackFailed
        ? "回退上报异常"
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
                  <Button
                    variant="outline"
                    size="sm"
                    className="ml-3"
                    disabled={pairMutation.isPending}
                    onClick={() => pairMutation.mutate()}
                  >
                    配对统计程序
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
                  <UsageCard overview={overview} />
                  <div className="cqg-quota-grid">
                    <OfficialRateLimitsCard
                      rateLimits={rateLimitsQuery.data}
                      loading={rateLimitsQuery.isPending}
                      failed={rateLimitsQuery.isError}
                    />
                    <TeamCard overview={overview} />
                  </div>
                </>
              ) : (
                <EmptyState
                  title="暂时无法读取个人额度"
                  description="请刷新重试；如问题持续，请联系管理员。"
                />
              )
            ) : visibleWorkspace === "login-assistance" ? (
              showAssistance ? (
                <>
                  <LoginAssistance
                    key={`assistance:${session.user!.id}`}
                    userId={session.user!.id}
                  />
                  {session.user?.role === "ADMIN" ? (
                    <OfficialLoginHelper
                      key={`official:${session.user.id}`}
                      userId={session.user.id}
                    />
                  ) : null}
                </>
              ) : null
            ) : bindingAllowed && visibleWorkspace === "settings" ? (
              <>
                <ClientSettings />
                {session.user?.role === "ADMIN" ? (
                  <OfficialLoginHelper
                    key={session.user.id}
                    userId={session.user.id}
                  />
                ) : null}
              </>
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
                scanning={scanMutation.isPending}
                pairing={pairMutation.isPending}
                starting={startMeterMutation.isPending}
                onScan={() => scanMutation.mutate()}
                onPair={() => pairMutation.mutate()}
                onStart={() => startMeterMutation.mutate()}
              />
            ) : null}
          </div>
        </div>
      </main>
    </div>
  );
}
