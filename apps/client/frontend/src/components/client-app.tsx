import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  api,
  getApiErrorMessage,
  type StartupGateResult,
  type SystemSession,
} from "@/lib/api";
import { Dashboard } from "@/components/dashboard";
import { SystemLoginPage } from "@/components/system-login";

const signedOutSession: SystemSession = {
  authenticated: false,
  user: null,
};

function SessionLoading() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-muted/30">
      <div className="flex items-center gap-3 text-sm text-muted-foreground">
        <LoaderCircle aria-hidden="true" className="size-5 animate-spin" />
        正在验证系统登录状态…
      </div>
    </main>
  );
}

const guardExplanation =
  "Quota Guard 默认与 CC Switch 共存。仅在确认 CC Switch 接管导致官方会话信息不可读时，统计程序才会停止当前 Windows 用户会话中核实过的 CC Switch，并提示处理结果；未保存更改可能丢失。确认停止后会恢复可识别的连接配置。普通网络、登录或服务错误不会触发停止。恢复后若当前聊天仍报错，请重启 Codex 或重新打开会话以重载连接。";

export function ClientApp() {
  const queryClient = useQueryClient();
  const [logoutError, setLogoutError] = useState<unknown>(null);
  const [localSignedOut, setLocalSignedOut] = useState(false);
  const [gate, setGate] = useState<StartupGateResult | null>(null);
  const [ccSwitchStopped, setCcSwitchStopped] = useState(false);
  const [codexConnectionRestored, setCodexConnectionRestored] = useState(false);
  const gateQuery = useQuery({
    queryKey: ["startup-gate"],
    queryFn: api.startupGateCheck,
    retry: false,
    staleTime: Infinity,
  });
  const sessionQuery = useQuery({
    queryKey: ["system-session"],
    queryFn: api.systemSession,
    retry: false,
    refetchInterval: 30_000,
    enabled: true,
  });
  const pairingErrorQuery = useQuery({
    queryKey: ["meter-pairing-error", sessionQuery.data?.user?.id],
    queryFn: api.meterPairingError,
    retry: false,
    refetchInterval: 10_000,
    enabled: !!sessionQuery.data?.authenticated,
  });

  useEffect(() => {
    if (gateQuery.data) setGate(gateQuery.data);
    else if (gateQuery.isError) {
      setGate({ state: "GUARD_ERROR", ccSwitchTerminated: false });
    }
  }, [gateQuery.data, gateQuery.isError]);

  useEffect(() => {
    const expireSession = () => {
      setLocalSignedOut(true);
      queryClient.removeQueries({
        predicate: (query) =>
          !["startup-gate", "system-session"].includes(
            String(query.queryKey[0]),
          ),
      });
      queryClient.setQueryData(["system-session"], signedOutSession);
      void queryClient.invalidateQueries({ queryKey: ["system-session"] });
    };
    window.addEventListener("quota-guard:session-expired", expireSession);
    return () =>
      window.removeEventListener("quota-guard:session-expired", expireSession);
  }, [queryClient]);

  useEffect(() => {
    if (gate?.ccSwitchTerminated) setCcSwitchStopped(true);
    if (gate?.codexConnectionRestored) setCodexConnectionRestored(true);
  }, [gate]);

  useEffect(() => {
    if (gate?.state !== "READY") return;
    let active = true;
    const check = async () => {
      try {
        const next = await api.startupGuardStatus();
        // Native checks can overlap with the background monitor. CHECKING is
        // transient; leaving READY here would stop this polling loop forever.
        if (active && next.state !== "CHECKING") setGate(next);
      } catch {
        if (active)
          setGate({ state: "GUARD_ERROR", ccSwitchTerminated: false });
      }
    };
    const timer = window.setInterval(() => void check(), 3_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [gate?.state]);

  // The native guard presents an acknowledged Windows dialog independently of
  // renderer visibility. Keep the persistent banner without a duplicate toast.

  const handleAuthenticated = (session: SystemSession) => {
    queryClient.removeQueries({
      predicate: (query) =>
        !["startup-gate", "system-session"].includes(String(query.queryKey[0])),
    });
    setLogoutError(null);
    setLocalSignedOut(false);
    queryClient.setQueryData(["system-session"], session);
  };

  const handleLogout = async () => {
    let failure: unknown = null;
    try {
      await api.systemLogout();
      setLogoutError(null);
    } catch (error) {
      failure = error;
      setLogoutError(error);
    }
    setLocalSignedOut(true);
    queryClient.removeQueries({
      predicate: (query) =>
        !["startup-gate", "system-session"].includes(String(query.queryKey[0])),
    });
    queryClient.setQueryData(["system-session"], signedOutSession);
    await queryClient.invalidateQueries({ queryKey: ["system-session"] });
    if (failure) {
      toast.warning(
        `已清除本机登录状态；中央后台未确认退出：${getApiErrorMessage(failure)}`,
      );
    } else {
      toast.success("已退出系统");
    }
  };

  if (sessionQuery.isPending) {
    return <SessionLoading />;
  }

  const guardNotice = (
    <details className="cqg-guard-notice text-xs text-muted-foreground">
      <summary className="cursor-pointer rounded py-1 leading-5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
        <span role="status">
          {ccSwitchStopped ? "已停止 CC Switch。" : ""}
          {codexConnectionRestored ? "已恢复 Codex 连接配置。" : ""}
          {pairingErrorQuery.data === "PAIRING_IDENTITY_MISMATCH"
            ? "统计设备与中央账户登记不匹配，请联系管理员核对。"
            : pairingErrorQuery.data === "PAIRING_ENROLL_FAILED"
              ? "统计程序设备登记暂未成功，客户端会继续退避重试。"
              : ""}
          {gate && gate.state !== "READY"
            ? "CC Switch 冲突保护暂不可用，客户端仍可正常登录和使用。"
            : "默认与 CC Switch 共存，仅在确认官方会话冲突时停止并提醒。"}
        </span>
        <span className="ml-2 text-primary">查看说明</span>
      </summary>
      <p className="mt-2 max-w-4xl leading-relaxed">{guardExplanation}</p>
    </details>
  );

  const session = sessionQuery.data;
  if (localSignedOut || !session?.authenticated || !session.user) {
    return (
      <div className="cqg-entry-page">
        <div className="border-b bg-muted/50 px-6 py-2">{guardNotice}</div>
        <SystemLoginPage
          onAuthenticated={handleAuthenticated}
          statusError={logoutError ?? sessionQuery.error ?? undefined}
        />
      </div>
    );
  }

  return (
    <>
      {sessionQuery.error ? (
        <Alert variant="destructive" className="rounded-none">
          <AlertTitle>登录状态暂时无法刷新</AlertTitle>
          <AlertDescription>
            已保留当前页面，受保护操作仍需后台核验。
            <Button
              variant="outline"
              size="sm"
              onClick={() => void sessionQuery.refetch()}
            >
              重试登录状态
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      <Dashboard
        key={session.user.id}
        session={session}
        onLogout={() => void handleLogout()}
        guardNotice={guardNotice}
      />
    </>
  );
}
