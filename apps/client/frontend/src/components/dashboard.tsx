import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CircleAlert,
  Gauge,
  LogOut,
  RefreshCw,
  ShieldCheck,
  UserCircle,
  Users,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import {
  api,
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
} from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { UsageActivityPanel } from "@/components/usage-activity-panel";

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
    <span title={`精确值：${formatTokenCount(value)} Token`}>
      {formatCompactTokenCount(value)} Token
    </span>
  );
}

function usagePercent(used: number, limit: number | null): number {
  if (limit === null) return 0;
  if (limit === 0) return 100;
  return Math.min(100, Math.max(0, (used / limit) * 100));
}

function QuotaProgress({
  used,
  limit,
  label,
}: {
  used: number;
  limit: number | null;
  label: string;
}) {
  if (limit === null) {
    return (
      <div className="flex justify-between gap-3 text-xs text-muted-foreground">
        <span>{label}</span>
        <span>不设限</span>
      </div>
    );
  }
  const percent = usagePercent(used, limit);
  const exhausted = percent >= 100;
  return (
    <div
      className="flex flex-col gap-2"
      role="group"
      aria-label={`${label}使用进度`}
    >
      <div className="flex items-center justify-between gap-3 text-xs text-muted-foreground">
        <span>{label}</span>
        <span className="tabular-nums font-medium text-foreground">
          {new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(
            percent,
          )}
          %
        </span>
      </div>
      <Progress
        aria-label={`${label}使用进度`}
        className="h-2 bg-slate-200 dark:bg-slate-800"
        indicatorClassName={
          exhausted
            ? "bg-red-500"
            : percent >= 80
              ? "bg-amber-500"
              : "bg-teal-600"
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
    <section className="cqg-surface min-w-0 p-5" aria-label="团队共享额度">
      <div className="mb-5 flex items-start justify-between gap-3">
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
        <Users
          aria-hidden="true"
          className="size-5 shrink-0 text-teal-700 dark:text-teal-400"
        />
      </div>
      {teamQuota ? (
        <div className="space-y-5">
          <div>
            <QuotaProgress
              label="累计总额"
              used={teamQuota.totalUsedTokens}
              limit={teamQuota.totalTokenLimit}
            />
            <div className="mt-3 flex flex-wrap justify-between gap-2 text-xs tabular-nums text-muted-foreground">
              <span>
                <TokenAmount value={teamQuota.totalUsedTokens} /> 已用
              </span>
              {teamQuota.totalRemainingTokens !== null ? (
                <span>
                  <TokenAmount value={teamQuota.totalRemainingTokens} /> 剩余
                </span>
              ) : null}
            </div>
          </div>
          <div className="border-t pt-4">
            <QuotaProgress
              label="5 小时团队"
              used={teamQuota.fiveHourUsedTokens}
              limit={teamQuota.fiveHourTokenLimit}
            />
            <div className="flex flex-wrap justify-between gap-2 text-xs tabular-nums text-muted-foreground">
              <span>
                <TokenAmount value={teamQuota.fiveHourUsedTokens} /> 已用
              </span>
              {teamQuota.fiveHourRemainingTokens !== null ? (
                <span>
                  <TokenAmount value={teamQuota.fiveHourRemainingTokens} /> 剩余
                </span>
              ) : null}
            </div>
          </div>
          <div className="border-t pt-4">
            <QuotaProgress
              label="7 天团队"
              used={teamQuota.weeklyUsedTokens}
              limit={teamQuota.weeklyTokenLimit}
            />
            <div className="mt-3 flex flex-wrap justify-between gap-2 text-xs tabular-nums text-muted-foreground">
              <span>
                <TokenAmount value={teamQuota.weeklyUsedTokens} /> 已用
              </span>
              {teamQuota.weeklyRemainingTokens !== null ? (
                <span>
                  <TokenAmount value={teamQuota.weeklyRemainingTokens} /> 剩余
                </span>
              ) : null}
            </div>
          </div>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">暂无团队共享额度</p>
      )}
    </section>
  );
}

function UsageWindow({
  label,
  usedTokens,
  remainingTokens,
  limitTokens,
}: {
  label: string;
  usedTokens: number;
  remainingTokens: number | null;
  limitTokens: number | null;
}) {
  const nearLimit =
    remainingTokens !== null &&
    limitTokens !== null &&
    remainingTokens <= Math.max(1, Math.floor(limitTokens * 0.2));
  return (
    <article className="cqg-window flex min-h-[205px] min-w-0 flex-col justify-between p-5">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-sm font-semibold">{label}</h3>
        <span className="text-xs text-muted-foreground">
          {limitTokens === null ? "不设限" : "个人上限"}
        </span>
      </div>
      <div className="mt-5 min-w-0">
        <p className="text-xs text-muted-foreground">
          {limitTokens === null ? "个人已用" : "个人剩余"}
        </p>
        <p
          className={`mt-1 break-words text-3xl font-semibold tabular-nums ${nearLimit ? "text-red-600" : "text-foreground"}`}
        >
          {limitTokens === null ? (
            <TokenAmount value={usedTokens} />
          ) : remainingTokens === null ? (
            "暂不可得"
          ) : (
            <TokenAmount value={remainingTokens} />
          )}
        </p>
      </div>
      <div className="mt-6">
        {limitTokens === null ? (
          <p className="text-xs text-muted-foreground">
            本窗口只记录用量，不参与个人限额判断
          </p>
        ) : (
          <>
            <QuotaProgress
              label={`${label}个人额度`}
              used={usedTokens}
              limit={limitTokens}
            />
            <div className="mt-3 flex flex-wrap justify-between gap-x-3 gap-y-1 text-xs tabular-nums text-muted-foreground">
              <span>
                个人已用 <TokenAmount value={usedTokens} />
              </span>
              <span>
                上限 <TokenAmount value={limitTokens} />
              </span>
            </div>
          </>
        )}
      </div>
    </article>
  );
}

function UsageCard({ overview }: { overview: QuotaOverview }) {
  const { usage, policy } = overview;
  return (
    <section aria-labelledby="personal-quota-heading">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 id="personal-quota-heading" className="text-base font-semibold">
            个人 Token
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">
            {usage.measuredAt
              ? `最后上报 ${new Date(usage.measuredAt).toLocaleString("zh-CN")}`
              : "尚无已接收的用量"}
          </p>
        </div>
        <span className="text-xs text-muted-foreground">
          窗口按事件发生时间滚动统计
        </span>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <UsageWindow
          label="5 小时"
          usedTokens={usage.fiveHourUsedTokens}
          remainingTokens={usage.fiveHourRemainingTokens}
          limitTokens={policy.fiveHourTokenLimit}
        />
        <UsageWindow
          label="7 天"
          usedTokens={usage.weeklyUsedTokens}
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
  return (
    <div className="min-w-0">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="font-medium">{label}</span>
        <span className="font-semibold tabular-nums text-sky-700 dark:text-sky-400">
          {formatPercent(window.usedPercent)}
        </span>
      </div>
      <div className="mt-3">
        <Progress
          aria-label={`${label}官方使用进度`}
          className="h-2 bg-slate-200 dark:bg-slate-800"
          indicatorClassName="bg-sky-600"
          value={window.usedPercent}
        />
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
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
      className="cqg-surface min-w-0 p-5"
      aria-labelledby="official-quota-heading"
    >
      <div className="mb-5 flex items-start justify-between gap-3">
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
        <Gauge
          aria-hidden="true"
          className="size-5 shrink-0 text-sky-700 dark:text-sky-400"
        />
      </div>
      {fiveHour || weekly ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
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
      <p className="mt-5 border-t pt-3 text-xs text-muted-foreground">
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
          className="size-5 shrink-0 text-teal-700 dark:text-teal-400"
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
      className="cqg-status-strip cqg-status-warning flex flex-wrap items-center gap-3 px-4 py-3"
      role="alert"
    >
      <CircleAlert
        aria-hidden="true"
        className="size-5 shrink-0 text-red-600"
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
}

export function Dashboard({ session, onLogout }: DashboardProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
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
  });
  const usageStatusQuery = useQuery({
    queryKey: ["usage-status"],
    queryFn: api.usageStatus,
    retry: false,
    refetchInterval: 5_000,
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
  });
  const isRefreshing =
    statusQuery.isFetching ||
    quotaQuery.isFetching ||
    usageStatusQuery.isFetching ||
    rateLimitsQuery.isFetching;
  const refresh = async () => {
    try {
      const results = await Promise.all([
        statusQuery.refetch(),
        quotaQuery.refetch(),
        usageStatusQuery.refetch(),
        rateLimitsQuery.refetch(),
      ]);
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

  return (
    <main className="cqg-dashboard min-h-screen bg-slate-50 dark:bg-slate-950">
      <div className="mx-auto flex w-full max-w-[1440px] flex-col gap-5 px-4 py-5 sm:px-6 lg:px-8">
        <header className="cqg-dashboard-header flex flex-wrap items-center justify-between gap-4 border-b pb-4">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-foreground text-background">
              <Gauge aria-hidden="true" className="size-5" />
            </div>
            <div className="min-w-0">
              <p className="text-[11px] font-semibold uppercase text-muted-foreground">
                Quota Guard
              </p>
              <h1 className="text-xl font-semibold">{t("dashboard")}</h1>
            </div>
            <span
              className={`cqg-connection ml-2 hidden items-center gap-2 text-xs sm:inline-flex ${statusOnline ? "text-teal-700 dark:text-teal-400" : "text-amber-700 dark:text-amber-400"}`}
            >
              <span
                className={`size-2 rounded-full ${statusOnline ? "bg-teal-600" : "bg-amber-500"}`}
              />
              {statusOnline ? t("connected") : t("serviceUnavailable")}
            </span>
          </div>
          <div className="flex min-w-0 items-center justify-end gap-2">
            <div className="mr-1 flex min-w-0 items-center gap-2 border-r pr-3 text-left">
              <UserCircle
                aria-hidden="true"
                className="size-5 text-muted-foreground"
              />
              <div className="flex min-w-0 flex-col">
                <span className="max-w-36 truncate text-xs font-medium">
                  {session.user?.displayName}
                </span>
                <span className="max-w-36 truncate text-[11px] text-muted-foreground">
                  {session.user?.email}
                </span>
              </div>
            </div>
            <Button
              variant="ghost"
              size="icon"
              onClick={refresh}
              disabled={isRefreshing}
              aria-label={isRefreshing ? t("refreshing") : t("refresh")}
              title={isRefreshing ? t("refreshing") : t("refresh")}
            >
              <RefreshCw
                className={isRefreshing ? "animate-spin" : undefined}
              />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={onLogout}
              aria-label="退出系统"
              title="退出系统"
            >
              <LogOut aria-hidden="true" />
            </Button>
          </div>
        </header>

        {requestError ? (
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

        {quotaQuery.isPending ? (
          <div className="grid gap-4 lg:grid-cols-2">
            <LoadingCard />
            <LoadingCard />
            <LoadingCard className="lg:col-span-2" />
          </div>
        ) : overview ? (
          <>
            <AccessCard overview={overview} />
            <UsageCard overview={overview} />
            <div className="grid min-w-0 gap-4 md:grid-cols-2">
              <TeamCard overview={overview} />
              <OfficialRateLimitsCard
                rateLimits={rateLimitsQuery.data}
                loading={rateLimitsQuery.isPending}
                failed={rateLimitsQuery.isError}
              />
            </div>
            <UsageActivityPanel
              key={session.user!.id}
              userId={session.user!.id}
              measuredAt={overview.usage.measuredAt}
              status={usageStatusQuery.data}
              scanning={scanMutation.isPending}
              pairing={pairMutation.isPending}
              starting={startMeterMutation.isPending}
              onScan={() => scanMutation.mutate()}
              onPair={() => pairMutation.mutate()}
              onStart={() => startMeterMutation.mutate()}
            />
          </>
        ) : (
          <EmptyState
            title="暂时无法读取个人额度"
            description="请刷新重试；如问题持续，请联系管理员。"
          />
        )}
      </div>
    </main>
  );
}
