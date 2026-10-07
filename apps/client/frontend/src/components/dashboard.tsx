import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CircleAlert,
  Clock3,
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
  type QuotaTeam,
  type SystemSession,
  type UsageStatus,
} from "@/lib/api";
import { formatPercent, formatResetTime, formatTokenCount } from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";

function describePolicySource(source: string): string {
  switch (source) {
    case "USER":
      return "用户策略";
    case "TEAM":
      return "团队策略";
    case "UNCONFIGURED":
      return "尚未配置";
    default:
      return "服务端策略";
  }
}

function describeBlockReason(reason: string | null): string {
  switch (reason) {
    case "TOKEN_QUOTA_UNCONFIGURED":
      return "个人 5 小时或 7 天 Token 限额尚未配置。";
    case "TEAM_UNAVAILABLE":
      return "所属团队不可用。";
    case "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED":
      return "已达到个人 5 小时 Token 限额。";
    case "WEEKLY_TOKEN_QUOTA_EXCEEDED":
      return "已达到个人 7 天 Token 限额。";
    default:
      return "服务端暂无法确认当前个人 Token 额度，请联系管理员。";
  }
}

function formatLimit(value: number | null): string {
  return value === null ? "未配置" : `${formatTokenCount(value)} Token`;
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

function LocalCodexCard() {
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <CardTitle className="text-lg">本机官方 Codex 登录</CardTitle>
            <CardDescription>请在官方 Codex 应用或 CLI 中自行登录。</CardDescription>
          </div>
          <ShieldCheck aria-hidden="true" className="text-muted-foreground" />
        </div>
      </CardHeader>
      <CardContent>
        <Badge variant="secondary">自行登录</Badge>
      </CardContent>
      <CardFooter className="text-xs leading-5 text-muted-foreground">
        本应用不接收、下发或修改官方登录凭证。官方额度从本机登录状态读取；
        个人 Token 用量仅根据本机可读取的 Codex 会话日志上报。
      </CardFooter>
    </Card>
  );
}

function TeamCard({ team }: { team: QuotaTeam | null }) {
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <CardTitle className="text-lg">所属团队</CardTitle>
            <CardDescription>
              {team ? team.name : "当前账户尚未加入团队。"}
            </CardDescription>
          </div>
          <Users aria-hidden="true" className="text-muted-foreground" />
        </div>
      </CardHeader>
    </Card>
  );
}

function PolicyCard({ overview }: { overview: QuotaOverview }) {
  const { policy } = overview;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">个人 Token 限额</CardTitle>
        <CardDescription>
          按当前系统用户生效；不是官方账户套餐额度。
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-lg border bg-muted/20 p-3">
          <p className="text-sm font-medium">5 小时上限</p>
          <p className="mt-1 text-2xl font-semibold">
            {formatLimit(policy.fiveHourTokenLimit)}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            来源：{describePolicySource(policy.fiveHourSource)}
          </p>
        </div>
        <div className="rounded-lg border bg-muted/20 p-3">
          <p className="text-sm font-medium">7 天上限</p>
          <p className="mt-1 text-2xl font-semibold">
            {formatLimit(policy.weeklyTokenLimit)}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            来源：{describePolicySource(policy.weeklySource)}
          </p>
        </div>
      </CardContent>
    </Card>
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
  if (limitTokens === null) {
    return (
      <div className="flex min-h-32 flex-col justify-center rounded-lg border bg-muted/20 p-3">
        <p className="font-medium">{label}</p>
        <p className="mt-2 text-sm text-muted-foreground">
          个人 Token 限额尚未配置
        </p>
      </div>
    );
  }

  const nearLimit =
    remainingTokens !== null &&
    remainingTokens <= Math.max(1, Math.floor(limitTokens * 0.2));
  return (
    <div className="flex flex-col gap-2 rounded-lg border bg-muted/20 p-3">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="font-medium">{label}</span>
        <Badge variant={nearLimit ? "destructive" : "secondary"}>
          {remainingTokens === null
            ? "等待确认"
            : `${formatTokenCount(remainingTokens)} Token 剩余`}
        </Badge>
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span>{formatTokenCount(usedTokens)} Token 已上报</span>
        <span>上限 {formatTokenCount(limitTokens)} Token</span>
      </div>
    </div>
  );
}

function UsageCard({ overview }: { overview: QuotaOverview }) {
  const { usage, policy } = overview;
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="text-lg">
              客户端上报的个人 Token 用量
            </CardTitle>
            <CardDescription>
              {usage.measuredAt
                ? `服务端收到事件于 ${new Date(usage.measuredAt).toLocaleString("zh-CN")}`
                : "等待服务端确认上报事件"}
            </CardDescription>
          </div>
          <Clock3 aria-hidden="true" className="text-muted-foreground" />
        </div>
      </CardHeader>
      <CardContent className="grid gap-3 md:grid-cols-2">
        <UsageWindow
          label="5 小时用量"
          usedTokens={usage.fiveHourUsedTokens}
          remainingTokens={usage.fiveHourRemainingTokens}
          limitTokens={policy.fiveHourTokenLimit}
        />
        <UsageWindow
          label="7 天用量"
          usedTokens={usage.weeklyUsedTokens}
          remainingTokens={usage.weeklyRemainingTokens}
          limitTokens={policy.weeklyTokenLimit}
        />
      </CardContent>
      <CardFooter className="text-xs text-muted-foreground">
        窗口按服务端收到本机上报事件的时间滚动计算；未生成本机 Codex 会话日志的活动不会计入，不能作为官方精确账单。
      </CardFooter>
    </Card>
  );
}

function UsageDiagnosticsCard({ status, scanning, pairing, onScan, onPair }: {
  status: UsageStatus | undefined;
  scanning: boolean;
  pairing: boolean;
  onScan: () => void;
  onPair: () => void;
}) {
  const serviceState = status?.service.state;
  const serviceLabel = serviceState === "NOT_INSTALLED"
    ? "Rust 常驻计费服务未安装"
    : serviceState === "OFFLINE"
      ? "Rust 常驻计费服务离线"
      : serviceState === "UNPAIRED"
        ? "Rust 常驻计费服务待配对"
        : serviceState === "ERROR"
          ? "Rust 常驻计费服务异常"
          : serviceState ? "Rust 常驻计费服务运行中" : "正在检查计费服务…";
  const fallbackState = status?.fallback.state;
  const fallbackLabel = fallbackState === "BASELINE_READY"
    ? "本机扫描已建立历史基线，后续新用量才会计入"
    : fallbackState === "REPORTED"
      ? `本机扫描刚上报 ${status?.fallback.lastReportedEvents ?? 0} 条事件`
      : fallbackState === "REPORT_FAILED"
        ? "本机扫描发现用量，但上报失败，稍后会重试"
        : fallbackState === "WAITING_FOR_SESSIONS"
          ? "尚未找到官方 Codex 会话目录"
          : fallbackState === "SIGNED_OUT"
            ? "请先登录本系统账户"
            : fallbackState === "SCAN_FAILED"
              ? "本机扫描失败，请检查服务状态"
              : "本机扫描暂无新事件";
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">计费程序状态</CardTitle>
        <CardDescription>{serviceLabel}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        <p>{fallbackLabel}</p>
        {status?.fallback.lastReportedAt ? (
          <p className="text-muted-foreground">最近成功上报：
            {new Date(status.fallback.lastReportedAt).toLocaleString("zh-CN")}</p>
        ) : <p className="text-muted-foreground">后端尚未收到本机 Token 事件。</p>}
        {serviceState === "NOT_INSTALLED" ? (
          <p className="text-muted-foreground">当前只有客户端打开并登录时才能扫描；安装 Rust 服务后可后台持续统计。</p>
        ) : null}
        {status?.service.errorCode ? <p className="text-destructive">服务诊断码：{status.service.errorCode}</p> : null}
        {status?.pairingState === "PAIRING_FAILED" ? (
          <p className="text-destructive">设备配对失败，请确认后台在线后重试。</p>
        ) : null}
      </CardContent>
      <CardFooter>
        <Button size="sm" variant="outline" disabled={scanning} onClick={onScan}>
          <RefreshCw data-icon="inline-start" className={scanning ? "animate-spin" : undefined} />
          {scanning ? "正在扫描…" : "立即扫描"}
        </Button>
        {serviceState === "UNPAIRED" || status?.pairingState === "PAIRING_FAILED" ? (
          <Button size="sm" variant="outline" disabled={pairing} onClick={onPair}>
            {pairing ? "正在配对…" : "重新配对"}
          </Button>
        ) : null}
      </CardFooter>
    </Card>
  );
}

function OfficialRateLimitWindow({
  label,
  window,
}: {
  label: string;
  window: OfficialCodexRateLimits["windows"][number] | undefined;
}) {
  if (!window) {
    return (
      <div className="rounded-lg border bg-muted/20 p-3">
        <p className="font-medium">{label}</p>
        <p className="mt-2 text-sm text-muted-foreground">
          官方账户未提供此窗口
        </p>
      </div>
    );
  }
  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="font-medium">{label}</span>
        <Badge variant="secondary">
          {formatPercent(window.usedPercent)} 已使用
        </Badge>
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
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">官方 Codex 账户额度（参考）</CardTitle>
        <CardDescription>
          {loading
            ? "正在读取官方账户窗口…"
            : failed
              ? "暂时无法读取本机官方额度；请先在官方 Codex 应用或 CLI 登录。"
              : rateLimits?.planType
              ? `官方账户套餐：${rateLimits.planType}`
              : "官方未提供套餐类型"}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 md:grid-cols-2">
        <OfficialRateLimitWindow
          label="5 小时窗口（官方账户）"
          window={fiveHour}
        />
        <OfficialRateLimitWindow label="7 天窗口（官方账户）" window={weekly} />
      </CardContent>
      <CardFooter className="text-xs text-muted-foreground">
        此处是官方账户级百分比参考，绝不会被当作你的个人 Token 用量或个人 Token
        限额。
      </CardFooter>
    </Card>
  );
}

function AccessCard({ overview }: { overview: QuotaOverview }) {
  if (overview.allowed) {
    return (
      <Alert>
        <ShieldCheck aria-hidden="true" />
        <AlertTitle>个人 Token 配额检查通过</AlertTitle>
        <AlertDescription>
          个人配额尚有余量。官方应用或 CLI 仍由用户自行登录和使用。
        </AlertDescription>
      </Alert>
    );
  }

  const exhausted = overview.blockReason === "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED"
    || overview.blockReason === "WEEKLY_TOKEN_QUOTA_EXCEEDED";
  return (
    <Alert variant="destructive">
      <CircleAlert aria-hidden="true" />
      <AlertTitle>
        {exhausted ? "个人 Token 额度已用尽"
          : overview.blockReason === "TOKEN_QUOTA_UNCONFIGURED"
            ? "个人 Token 额度未配置" : "个人 Token 额度暂不可用"}
      </AlertTitle>
      <AlertDescription>
        {describeBlockReason(overview.blockReason)}{" "}
        请联系管理员调整限额。本应用不会退出官方 Codex 应用或 CLI；
        官方使用不经过本服务，因此此限额仅作统计与提醒，不能强制阻止继续使用。
      </AlertDescription>
    </Alert>
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
      await quotaQuery.refetch();
      toast[status.fallback.state === "REPORT_FAILED" || status.fallback.state === "SCAN_FAILED"
        ? "error" : "success"]("扫描完成，请查看计费程序状态。");
    },
    onError: (error) => toast.error(`扫描失败：${getApiErrorMessage(error)}`),
  });
  const pairMutation = useMutation({
    mutationFn: api.pairMeter,
    onSuccess: (status) => {
      queryClient.setQueryData(["usage-status"], status);
      toast[status.pairingState === "PAIRING_SENT" ? "success" : "error"](
        status.pairingState === "PAIRING_SENT"
          ? "设备配对已发送，计费服务会在几秒内接收。"
          : "设备配对未完成，请检查计费服务与后台连接。",
      );
    },
    onError: (error) => toast.error(`配对失败：${getApiErrorMessage(error)}`),
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

  const requestError = [
    statusQuery.error,
    quotaQuery.error,
  ].find(Boolean);
  const statusOnline = statusQuery.data?.state === "online";

  return (
    <main className="min-h-screen bg-background">
      <div className="mx-auto flex w-full max-w-7xl flex-col gap-6 px-4 py-6 sm:px-6 lg:px-8">
        <header className="flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="outline">{t("appName")}</Badge>
              <Badge variant={statusOnline ? "default" : "secondary"}>
                {statusOnline ? t("connected") : t("serviceUnavailable")}
              </Badge>
            </div>
            <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
              {t("dashboard")}
            </h1>
            <p className="max-w-2xl text-muted-foreground">{t("overview")}</p>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <div className="flex items-center gap-2 rounded-md border bg-card px-3 py-2 text-left">
              <UserCircle
                aria-hidden="true"
                className="size-5 text-muted-foreground"
              />
              <div className="flex min-w-0 flex-col">
                <span className="max-w-40 truncate text-sm font-medium">
                  {session.user?.displayName}
                </span>
                <span className="max-w-40 truncate text-xs text-muted-foreground">
                  {session.user?.email}
                </span>
              </div>
            </div>
            <Button variant="outline" onClick={onLogout}>
              <LogOut data-icon="inline-start" />
              退出系统
            </Button>
            <Button
              variant="outline"
              onClick={refresh}
              disabled={isRefreshing}
              aria-label={isRefreshing ? t("refreshing") : t("refresh")}
            >
              <RefreshCw
                data-icon="inline-start"
                className={isRefreshing ? "animate-spin" : undefined}
              />
              {isRefreshing ? t("refreshing") : t("refresh")}
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
            <section className="grid gap-4 lg:grid-cols-2">
              <LocalCodexCard />
              <TeamCard team={overview.team} />
            </section>
            <PolicyCard overview={overview} />
            <UsageDiagnosticsCard
              status={usageStatusQuery.data}
              scanning={scanMutation.isPending}
              pairing={pairMutation.isPending}
              onScan={() => scanMutation.mutate()}
              onPair={() => pairMutation.mutate()}
            />
            <UsageCard overview={overview} />
            <OfficialRateLimitsCard
              rateLimits={rateLimitsQuery.data}
              loading={rateLimitsQuery.isPending}
              failed={rateLimitsQuery.isError}
            />
            <AccessCard overview={overview} />
          </>
        ) : (
          <EmptyState
            title="暂时无法读取个人额度"
            description="请刷新重试；如问题持续，请联系管理员。"
          />
        )}

        <Separator />
      </div>
    </main>
  );
}
