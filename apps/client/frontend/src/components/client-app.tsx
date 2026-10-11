import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { useEffect, useRef, useState } from "react";
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
import { ThemeSwitcher } from "@/components/theme-switcher";
import { subscribeToTrayFeedback, trayFeedbackNotice } from "@/lib/tray-events";

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

function SessionError({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry: () => void;
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-muted/30 px-4">
      <Alert variant="destructive" className="max-w-lg">
        <AlertTitle>无法连接系统服务</AlertTitle>
        <AlertDescription className="flex flex-wrap items-center gap-3">
          <span>{getApiErrorMessage(error)}</span>
          <Button variant="outline" size="sm" onClick={onRetry}>
            重试
          </Button>
        </AlertDescription>
      </Alert>
    </main>
  );
}

const guardExplanation =
  "CC Switch 可改变 Codex 的请求路由，影响用量归属。Quota Guard 运行期间，统计程序会自动强制结束当前 Windows 用户会话中的 CC Switch，未保存的更改可能丢失。若 Codex 仍指向代理，请先退出 Quota Guard 并恢复官方直连。";

function StartupGatePage({
  result,
  retrying,
  onRetry,
}: {
  result: StartupGateResult | null;
  retrying: boolean;
  onRetry: () => void;
}) {
  const [serverUrl, setServerUrl] = useState("");
  const [savingServer, setSavingServer] = useState(false);
  const [serverError, setServerError] = useState("");
  useEffect(() => {
    void api
      .serverConfig()
      .then((config) => setServerUrl(config.url))
      .catch(() => {
        setServerError("无法读取服务器地址，请检查客户端设置目录。");
      });
  }, []);
  const saveServer = async () => {
    setSavingServer(true);
    setServerError("");
    try {
      await api.saveServerConfig({ url: serverUrl.trim() });
      setServerError("地址已保存。点击“重新检查”以应用新地址。");
    } catch (error) {
      setServerError(getApiErrorMessage(error));
    } finally {
      setSavingServer(false);
    }
  };
  const state = result?.state;
  const waiting = state === "CHECKING" || retrying || result === null;
  const detail =
    state === "ADMIN_REQUIRED"
      ? "请从系统托盘退出客户端，右键点击程序并选择“以管理员身份运行”，然后重试。"
      : state === "METER_UNAVAILABLE"
        ? "统计程序未安装、未启动或更新失败。请确认安装包完整，并以管理员身份重试。"
        : state === "BACKEND_UNAVAILABLE"
          ? "本地 Java 服务未能启动，请检查安装包后重试。"
          : state === "UNSUPPORTED"
            ? "当前平台暂不支持 Windows 统计程序门禁。"
            : state === "GUARD_ERROR"
              ? "无法确认 CC Switch 已关闭，或统计服务无法核实守护状态。请手动退出 CC Switch 后重试。"
              : "正在验证管理员权限、统计程序与 CC Switch 状态…";
  return (
    <main className="relative flex min-h-screen items-center justify-center bg-background px-6 py-12">
      <ThemeSwitcher compact className="absolute right-6 top-5" />
      <Alert
        variant={waiting ? "default" : "destructive"}
        className="max-w-xl bg-card p-6 shadow-sm"
      >
        <AlertTitle>
          {waiting ? "正在检查启动条件" : "启动条件尚未满足"}
        </AlertTitle>
        <AlertDescription className="space-y-3">
          <p>{detail}</p>
          <p>{guardExplanation}</p>
          <div className="space-y-2 border-t pt-3">
            <label htmlFor="startup-server-url" className="font-medium">
              中央服务器地址
            </label>
            <div className="flex flex-wrap gap-2">
              <input
                id="startup-server-url"
                value={serverUrl}
                onChange={(event) => setServerUrl(event.target.value)}
                placeholder="https://your-server.example.com"
                className="min-w-64 flex-1 rounded-md border bg-background px-3 py-2 text-sm text-foreground"
              />
              <Button
                variant="outline"
                size="sm"
                disabled={savingServer}
                onClick={() => void saveServer()}
              >
                {savingServer ? "正在保存…" : "保存地址"}
              </Button>
            </div>
            {serverError ? <p role="status">{serverError}</p> : null}
          </div>
          {!waiting ? (
            <Button variant="outline" size="sm" onClick={onRetry}>
              重新检查
            </Button>
          ) : null}
        </AlertDescription>
      </Alert>
    </main>
  );
}

export function ClientApp() {
  const queryClient = useQueryClient();
  const [logoutError, setLogoutError] = useState<unknown>(null);
  const [localSignedOut, setLocalSignedOut] = useState(false);
  const [gate, setGate] = useState<StartupGateResult | null>(null);
  const [gateRetrying, setGateRetrying] = useState(false);
  const [ccSwitchStopped, setCcSwitchStopped] = useState(false);
  const gateAttempt = useRef(0);
  const previouslyReady = useRef(false);
  const gateQuery = useQuery({
    queryKey: ["startup-gate"],
    queryFn: api.startupGateCheck,
    retry: false,
    staleTime: Infinity,
  });
  const gateReady = gate?.state === "READY";
  const sessionQuery = useQuery({
    queryKey: ["system-session"],
    queryFn: api.systemSession,
    retry: false,
    refetchInterval: 30_000,
    enabled: gateReady,
  });

  useEffect(() => {
    if (gateQuery.data) setGate(gateQuery.data);
    else if (gateQuery.isError) {
      setGate({ state: "GUARD_ERROR", ccSwitchTerminated: false });
    }
  }, [gateQuery.data, gateQuery.isError]);

  useEffect(() => {
    if (gate?.ccSwitchTerminated) setCcSwitchStopped(true);
    if (gate?.state === "READY") {
      previouslyReady.current = true;
    } else if (previouslyReady.current) {
      queryClient.removeQueries({ queryKey: ["system-session"] });
      setLocalSignedOut(false);
    }
  }, [gate, queryClient]);

  useEffect(() => {
    if (!gateReady) return;
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
  }, [gateReady]);

  // The native guard presents an acknowledged Windows dialog independently of
  // renderer visibility. Keep the persistent banner without a duplicate toast.

  const retryGate = async () => {
    const attempt = ++gateAttempt.current;
    setGateRetrying(true);
    setGate({ state: "CHECKING", ccSwitchTerminated: false });
    try {
      const result = await api.startupGateCheck();
      if (attempt === gateAttempt.current) setGate(result);
    } catch {
      if (attempt === gateAttempt.current) {
        setGate({ state: "GUARD_ERROR", ccSwitchTerminated: false });
      }
    } finally {
      if (attempt === gateAttempt.current) setGateRetrying(false);
    }
  };

  useEffect(
    () =>
      subscribeToTrayFeedback((feedback) => {
        const notice = trayFeedbackNotice(feedback);
        toast[notice.kind](notice.message);
        for (const key of [
          "usage-status",
          "quota-overview",
          "usage-events",
          "usage-analytics",
          "meter-runtime-logs",
          "meter-service-version",
        ]) {
          void queryClient.invalidateQueries({ queryKey: [key] });
        }
        if (feedback.action === "scan" && feedback.outcome === "signed_out") {
          void queryClient.invalidateQueries({ queryKey: ["system-session"] });
        }
      }),
    [queryClient],
  );

  const handleAuthenticated = (session: SystemSession) => {
    setLogoutError(null);
    setLocalSignedOut(false);
    queryClient.setQueryData(["system-session"], session);
  };

  const handleLogout = async () => {
    try {
      await api.systemLogout();
      setLogoutError(null);
      setLocalSignedOut(true);
      queryClient.setQueryData(["system-session"], signedOutSession);
      await queryClient.invalidateQueries({ queryKey: ["system-session"] });
      toast.success("已退出系统");
    } catch (error) {
      setLogoutError(error);
      toast.error(`退出失败：${getApiErrorMessage(error)}`);
    }
  };

  if (!gateReady) {
    return (
      <StartupGatePage
        result={gate}
        retrying={gateRetrying}
        onRetry={() => void retryGate()}
      />
    );
  }

  if (sessionQuery.isPending) {
    return <SessionLoading />;
  }

  const guardNotice = (
    <details className="cqg-guard-notice text-xs text-muted-foreground">
      <summary className="cursor-pointer rounded py-1 leading-5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
        <span role="status">
          {ccSwitchStopped ? "已停止 CC Switch。" : ""}
          运行期间会强制结束 CC Switch，未保存更改可能丢失。
        </span>
        <span className="ml-2 text-primary">查看说明</span>
      </summary>
      <p className="mt-2 max-w-4xl leading-relaxed">{guardExplanation}</p>
    </details>
  );

  if (sessionQuery.error && !sessionQuery.data) {
    return (
      <div className="cqg-entry-page">
        <div className="border-b bg-muted/50 px-6 py-2">{guardNotice}</div>
        <SessionError
          error={sessionQuery.error}
          onRetry={() => void sessionQuery.refetch()}
        />
      </div>
    );
  }

  const session = sessionQuery.data;
  if (localSignedOut || !session?.authenticated || !session.user) {
    return (
      <div className="cqg-entry-page">
        <div className="border-b bg-muted/50 px-6 py-2">{guardNotice}</div>
        <SystemLoginPage
          onAuthenticated={handleAuthenticated}
          statusError={logoutError ?? undefined}
        />
      </div>
    );
  }

  return (
    <Dashboard
      session={session}
      onLogout={() => void handleLogout()}
      guardNotice={guardNotice}
    />
  );
}
