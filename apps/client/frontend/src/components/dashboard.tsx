import { useQuery } from "@tanstack/react-query";
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
  type ManagedCodexAccount,
  type QuotaOverview,
  type QuotaTeam,
  type SystemSession,
} from "@/lib/api";
import { formatPercent, formatResetTime } from "@/lib/utils";
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
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";

const notMeasuredLabel = "尚未采集（暂无服务端用量）";

function describePolicySource(source: string): string {
  switch (source) {
    case "USER":
      return "用户策略";
    case "TEAM":
      return "团队策略";
    case "DEFAULT":
      return "默认策略";
    default:
      return "服务端策略";
  }
}

function describeBlockReason(reason: string | null): string {
  switch (reason) {
    case "USAGE_NOT_MEASURED":
      return notMeasuredLabel;
    case "CODEX_ACCOUNT_UNASSIGNED":
      return "尚未分配 Codex 账户。";
    case "CODEX_ACCOUNT_UNAVAILABLE":
      return "分配的 Codex 账户不可用。";
    case "TEAM_UNAVAILABLE":
      return "所属团队不可用。";
    case "FIVE_HOUR_QUOTA_EXCEEDED":
      return "已超过 5 小时额度限制。";
    case "WEEKLY_QUOTA_EXCEEDED":
      return "已超过 7 天额度限制。";
    default:
      return "服务端暂无法确认当前额度，请联系管理员。";
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

function AssignedAccountCard({
  assigned,
  account,
}: {
  assigned: boolean;
  account: ManagedCodexAccount | null;
}) {
  const active = assigned && account?.status === "ACTIVE";
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <CardTitle className="text-lg">后台分配的 Codex 账户</CardTitle>
            <CardDescription>
              {assigned && account
                ? `${account.displayName} · ${account.status === "ACTIVE" ? "启用" : "不可用"}`
                : "当前系统账号尚未分配 Codex 账户。"}
            </CardDescription>
          </div>
          <ShieldCheck aria-hidden="true" className="text-muted-foreground" />
        </div>
      </CardHeader>
      <CardContent>
        <Badge
          variant={active ? "default" : assigned ? "destructive" : "secondary"}
        >
          {active ? "后台托管" : assigned ? "已停用" : "未分配"}
        </Badge>
      </CardContent>
      <CardFooter className="text-xs text-muted-foreground">
        凭证只保存在服务端，客户端不会获取或保存。
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
        <CardTitle className="text-lg">额度策略</CardTitle>
        <CardDescription>服务端为当前账户生效的限制。</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-lg border bg-muted/20 p-3">
          <p className="text-sm font-medium">5 小时限额</p>
          <p className="mt-1 text-2xl font-semibold">
            {formatPercent(policy.fiveHourLimitPercent)}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            来源：{describePolicySource(policy.fiveHourSource)}
          </p>
        </div>
        <div className="rounded-lg border bg-muted/20 p-3">
          <p className="text-sm font-medium">7 天限额</p>
          <p className="mt-1 text-2xl font-semibold">
            {formatPercent(policy.weeklyLimitPercent)}
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
  usedPercent,
  remainingPercent,
  resetsAt,
}: {
  label: string;
  usedPercent: number | null;
  remainingPercent: number | null;
  resetsAt: string | null;
}) {
  if (usedPercent === null || remainingPercent === null) {
    return (
      <div className="flex min-h-32 flex-col justify-center rounded-lg border bg-muted/20 p-3">
        <p className="font-medium">{label}</p>
        <p className="mt-2 text-sm text-muted-foreground">{notMeasuredLabel}</p>
      </div>
    );
  }

  const nearLimit = remainingPercent <= 20;
  return (
    <div className="flex flex-col gap-2 rounded-lg border bg-muted/20 p-3">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="font-medium">{label}</span>
        <Badge variant={nearLimit ? "destructive" : "secondary"}>
          {formatPercent(remainingPercent)} 剩余
        </Badge>
      </div>
      <Progress
        value={remainingPercent}
        aria-label={`${label}剩余额度 ${formatPercent(remainingPercent)}`}
      />
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span>{formatPercent(usedPercent)} 已使用</span>
        <span>{resetsAt ? formatResetTime(resetsAt) : "重置时间未知"}</span>
      </div>
    </div>
  );
}

function UsageCard({ overview }: { overview: QuotaOverview }) {
  const { usage } = overview;
  const unmeasured = usage.measurementStatus === "NOT_MEASURED";
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="text-lg">真实用量</CardTitle>
            <CardDescription>
              {unmeasured
                ? notMeasuredLabel
                : usage.measuredAt
                  ? `服务端采集于 ${new Date(usage.measuredAt).toLocaleString("zh-CN")}`
                  : "服务端当前用量"}
            </CardDescription>
          </div>
          <Clock3 aria-hidden="true" className="text-muted-foreground" />
        </div>
      </CardHeader>
      <CardContent>
        {unmeasured ? (
          <p className="text-sm text-muted-foreground">{notMeasuredLabel}</p>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            <UsageWindow
              label="5 小时用量"
              usedPercent={usage.fiveHourUsedPercent}
              remainingPercent={usage.fiveHourRemainingPercent}
              resetsAt={usage.fiveHourResetsAt}
            />
            <UsageWindow
              label="7 天用量"
              usedPercent={usage.weeklyUsedPercent}
              remainingPercent={usage.weeklyRemainingPercent}
              resetsAt={usage.weeklyResetsAt}
            />
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function AccessCard({ overview }: { overview: QuotaOverview }) {
  if (overview.allowed) {
    return (
      <Alert>
        <ShieldCheck aria-hidden="true" />
        <AlertTitle>额度策略检查通过</AlertTitle>
        <AlertDescription>任务执行尚未接入。</AlertDescription>
      </Alert>
    );
  }

  return (
    <Alert variant="destructive">
      <CircleAlert aria-hidden="true" />
      <AlertTitle>当前额度暂不可用</AlertTitle>
      <AlertDescription>
        {describeBlockReason(overview.blockReason)}
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
  });
  const isRefreshing = statusQuery.isFetching || quotaQuery.isFetching;
  const refresh = async () => {
    try {
      const results = await Promise.all([
        statusQuery.refetch(),
        quotaQuery.refetch(),
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
  const overview = quotaQuery.data;
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
              <AssignedAccountCard
                assigned={overview.assigned}
                account={overview.codexAccount}
              />
              <TeamCard team={overview.team} />
            </section>
            <PolicyCard overview={overview} />
            <UsageCard overview={overview} />
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
