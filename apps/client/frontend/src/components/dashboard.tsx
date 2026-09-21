import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { openUrl } from "@tauri-apps/plugin-opener";
import { AnimatePresence, motion } from "framer-motion";
import {
  Check,
  CircleAlert,
  Clock3,
  Copy,
  ExternalLink,
  Gauge,
  LogIn,
  LogOut,
  RefreshCw,
  ShieldCheck,
  Users,
} from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import {
  api,
  getApiErrorMessage,
  type QuotaSnapshot,
  type RateLimitBucket,
  type RateLimitWindow,
} from "@/lib/api";
import {
  formatPercent,
  formatResetTime,
  formatWindowDuration,
} from "@/lib/utils";
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

function WindowProgress({
  label,
  window,
}: {
  label: string;
  window: RateLimitWindow;
}) {
  const isNearLimit = window.remainingPercent <= 20;
  return (
    <div className="flex flex-col gap-2 rounded-lg border bg-muted/20 p-3">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="font-medium">{label}</span>
        <Badge variant={isNearLimit ? "destructive" : "secondary"}>
          {formatPercent(window.remainingPercent)} 剩余
        </Badge>
      </div>
      <Progress
        value={window.remainingPercent}
        aria-label={`${label}剩余额度 ${formatPercent(window.remainingPercent)}`}
      />
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
        <span>{formatPercent(window.usedPercent)} 已使用</span>
        <span>{formatWindowDuration(window.windowDurationMins)}</span>
        <span>{formatResetTime(window.resetsAt)}</span>
      </div>
    </div>
  );
}

function QuotaBucket({ bucket }: { bucket: RateLimitBucket }) {
  return (
    <div className="flex flex-col gap-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="font-medium">{bucket.limitName || bucket.limitId}</p>
          <p className="text-xs text-muted-foreground">{bucket.limitId}</p>
        </div>
        {bucket.spendControlReached ? (
          <Badge variant="destructive">已达到支出限制</Badge>
        ) : (
          <Badge variant="outline">{bucket.planType || "默认计划"}</Badge>
        )}
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {bucket.primary ? (
          <WindowProgress label="主窗口" window={bucket.primary} />
        ) : null}
        {bucket.secondary ? (
          <WindowProgress label="次窗口" window={bucket.secondary} />
        ) : null}
      </div>
    </div>
  );
}

function QuotaSummary({ snapshot }: { snapshot: QuotaSnapshot }) {
  const { t } = useTranslation();
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <CardTitle className="text-lg">{t("quota")}</CardTitle>
            <CardDescription>
              {snapshot.source} · {snapshot.planType || "未识别计划"}
            </CardDescription>
          </div>
          <Badge variant={snapshot.authenticated ? "default" : "secondary"}>
            {snapshot.authenticated ? t("signedIn") : t("signedOut")}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {snapshot.buckets.length === 0 ? (
          <p className="rounded-lg border border-dashed p-5 text-center text-sm text-muted-foreground">
            {t("noQuota")}
          </p>
        ) : (
          snapshot.buckets.map((bucket) => (
            <QuotaBucket key={bucket.limitId} bucket={bucket} />
          ))
        )}
      </CardContent>
      <CardFooter className="text-xs text-muted-foreground">
        {t("lastUpdated")}：{formatResetTime(snapshot.capturedAt)}
      </CardFooter>
    </Card>
  );
}

function AccountCard({
  authenticated,
  planType,
  onLogin,
  onLogout,
  isLoggingIn,
  isLoggingOut,
}: {
  authenticated: boolean;
  planType: string;
  onLogin: () => void;
  onLogout: () => void;
  isLoggingIn: boolean;
  isLoggingOut: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <CardTitle className="text-lg">{t("account")}</CardTitle>
            <CardDescription>
              {authenticated ? planType || "Codex" : t("signInHint")}
            </CardDescription>
          </div>
          <ShieldCheck aria-hidden="true" className="text-muted-foreground" />
        </div>
      </CardHeader>
      <CardContent>
        <Badge variant={authenticated ? "default" : "secondary"}>
          {authenticated ? t("signedIn") : t("signedOut")}
        </Badge>
      </CardContent>
      <CardFooter className="gap-2">
        {authenticated ? (
          <Button variant="outline" onClick={onLogout} disabled={isLoggingOut}>
            <LogOut data-icon="inline-start" />
            {isLoggingOut ? "处理中…" : t("signOut")}
          </Button>
        ) : (
          <Button onClick={onLogin} disabled={isLoggingIn}>
            <LogIn data-icon="inline-start" />
            {isLoggingIn ? "准备中…" : t("signIn")}
          </Button>
        )}
      </CardFooter>
    </Card>
  );
}

function MemberCard({
  displayName,
  memberId,
  managedLogin,
  enforcementConsent,
  snapshot,
}: {
  displayName: string;
  memberId: string;
  managedLogin: boolean;
  enforcementConsent: boolean;
  snapshot: QuotaSnapshot | null;
}) {
  const { t } = useTranslation();
  return (
    <Card>
      <CardHeader className="gap-3 pb-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-full bg-secondary text-secondary-foreground">
              <Users aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <CardTitle className="truncate text-base">
                {displayName}
              </CardTitle>
              <CardDescription className="truncate">{memberId}</CardDescription>
            </div>
          </div>
          <Badge variant={snapshot?.authenticated ? "default" : "secondary"}>
            {snapshot?.authenticated ? t("signedIn") : t("pending")}
          </Badge>
        </div>
        <div className="flex flex-wrap gap-2">
          {managedLogin ? (
            <Badge variant="outline">{t("managed")}</Badge>
          ) : null}
          {enforcementConsent ? (
            <Badge variant="outline">
              <Check aria-hidden="true" data-icon="inline-start" />
              {t("enforcement")}
            </Badge>
          ) : null}
        </div>
      </CardHeader>
      <CardContent>
        {snapshot ? (
          <div className="flex flex-col gap-3">
            {snapshot.buckets.map((bucket) => (
              <QuotaBucket key={bucket.limitId} bucket={bucket} />
            ))}
            {snapshot.buckets.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("noQuota")}</p>
            ) : null}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{t("noQuota")}</p>
        )}
      </CardContent>
    </Card>
  );
}

function DeviceLoginCard({
  verificationUrl,
  userCode,
  onDismiss,
}: {
  verificationUrl: string;
  userCode: string;
  onDismiss: () => void;
}) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(userCode);
      setCopied(true);
      toast.success(t("codeCopied"));
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      toast.error("复制失败，请手动复制设备码。");
    }
  };

  const openVerificationUrl = async () => {
    try {
      await openUrl(verificationUrl);
    } catch {
      toast.error("无法打开验证地址，请确认它来自 OpenAI 官方域名。");
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8 }}
    >
      <Alert>
        <LogIn aria-hidden="true" />
        <AlertTitle>{t("loginStarted")}</AlertTitle>
        <AlertDescription className="flex flex-wrap items-center gap-3">
          <span>{t("loginStartedHint")}</span>
          <span className="rounded-md border bg-muted px-2 py-1 font-mono font-medium">
            {userCode}
          </span>
          <Button variant="outline" size="sm" onClick={copyCode}>
            <Copy data-icon="inline-start" />
            {copied ? t("codeCopied") : t("copyCode")}
          </Button>
          <Button variant="outline" size="sm" onClick={openVerificationUrl}>
            {t("signIn")}
            <ExternalLink data-icon="inline-end" />
          </Button>
          <Button variant="ghost" size="sm" onClick={onDismiss}>
            关闭
          </Button>
        </AlertDescription>
      </Alert>
    </motion.div>
  );
}

export function Dashboard() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [deviceLogin, setDeviceLogin] = useState<{
    verificationUrl: string;
    userCode: string;
  } | null>(null);

  const statusQuery = useQuery({
    queryKey: ["status"],
    queryFn: api.status,
    retry: false,
    refetchInterval: 30_000,
  });
  const roomQuery = useQuery({
    queryKey: ["room-overview"],
    queryFn: api.roomOverview,
    retry: false,
  });
  const accountQuery = useQuery({
    queryKey: ["account-overview"],
    queryFn: api.accountOverview,
    retry: false,
  });
  const loginMutation = useMutation({
    mutationFn: api.startDeviceLogin,
    onSuccess: (login) => {
      setDeviceLogin({
        verificationUrl: login.verificationUrl,
        userCode: login.userCode,
      });
      toast.success(t("loginStarted"));
    },
    onError: (error) =>
      toast.error(`${t("loginFailed")}：${getApiErrorMessage(error)}`),
  });
  const logoutMutation = useMutation({
    mutationFn: api.logout,
    onSuccess: async () => {
      toast.success(t("loggedOut"));
      await queryClient.invalidateQueries({ queryKey: ["account-overview"] });
    },
    onError: (error) =>
      toast.error(`${t("logoutFailed")}：${getApiErrorMessage(error)}`),
  });

  const isRefreshing =
    statusQuery.isFetching || roomQuery.isFetching || accountQuery.isFetching;
  const refresh = async () => {
    try {
      const results = await Promise.all([
        statusQuery.refetch(),
        roomQuery.refetch(),
        accountQuery.refetch(),
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
    roomQuery.error,
    accountQuery.error,
  ].find(Boolean);
  const room = roomQuery.data;
  const account = accountQuery.data;
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
        </header>

        <AnimatePresence initial={false}>
          {deviceLogin ? (
            <DeviceLoginCard
              key="device-login"
              verificationUrl={deviceLogin.verificationUrl}
              userCode={deviceLogin.userCode}
              onDismiss={() => setDeviceLogin(null)}
            />
          ) : null}
        </AnimatePresence>

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

        <section className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,0.4fr)]">
          {accountQuery.isPending ? (
            <LoadingCard />
          ) : account ? (
            <QuotaSummary snapshot={account.snapshot} />
          ) : (
            <EmptyState title={t("noQuota")} description={t("signInHint")} />
          )}
          {accountQuery.isPending ? (
            <LoadingCard />
          ) : account ? (
            <AccountCard
              authenticated={account.authenticated}
              planType={account.planType}
              onLogin={() => loginMutation.mutate()}
              onLogout={() => logoutMutation.mutate()}
              isLoggingIn={loginMutation.isPending}
              isLoggingOut={logoutMutation.isPending}
            />
          ) : null}
        </section>

        <Separator />

        <section className="flex flex-col gap-4" aria-labelledby="room-heading">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div className="flex flex-col gap-1">
              <h2 id="room-heading" className="text-xl font-semibold">
                {room?.name || t("room")}
              </h2>
              <p className="text-sm text-muted-foreground">
                {room?.configured
                  ? `${room.roomId} · ${room.members.length} ${t("members")}`
                  : t("noRoomHint")}
              </p>
            </div>
            {room?.configured ? (
              <Badge variant="secondary">
                <Users data-icon="inline-start" />
                {room.members.length} {t("members")}
              </Badge>
            ) : null}
          </div>

          {roomQuery.isPending ? (
            <div className="grid gap-4 md:grid-cols-2">
              <LoadingCard />
              <LoadingCard />
            </div>
          ) : !room?.configured ? (
            <EmptyState title={t("noRoom")} description={t("noRoomHint")} />
          ) : room.members.length === 0 ? (
            <EmptyState title={t("noMembers")} description={t("noRoomHint")} />
          ) : (
            <div className="grid gap-4 md:grid-cols-2">
              {room.members.map((member) => (
                <MemberCard key={member.memberId} {...member} />
              ))}
            </div>
          )}
        </section>

        {room?.warnings.length ? (
          <section
            className="flex flex-col gap-3"
            aria-labelledby="warnings-heading"
          >
            <div className="flex items-center gap-2">
              <Clock3 aria-hidden="true" className="text-muted-foreground" />
              <h2 id="warnings-heading" className="text-lg font-semibold">
                {t("warnings")}
              </h2>
            </div>
            {room.warnings.map((warning) => (
              <Alert key={warning}>
                <CircleAlert aria-hidden="true" />
                <AlertDescription>{warning}</AlertDescription>
              </Alert>
            ))}
          </section>
        ) : null}
      </div>
    </main>
  );
}
