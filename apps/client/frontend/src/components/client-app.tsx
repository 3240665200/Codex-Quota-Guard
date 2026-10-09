import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { api, getApiErrorMessage, type SystemSession } from "@/lib/api";
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

export function ClientApp() {
  const queryClient = useQueryClient();
  const [logoutError, setLogoutError] = useState<unknown>(null);
  const [localSignedOut, setLocalSignedOut] = useState(false);
  const meterStartupRequested = useRef(false);
  const sessionQuery = useQuery({
    queryKey: ["system-session"],
    queryFn: api.systemSession,
    retry: false,
    refetchInterval: 30_000,
  });

  useEffect(() => {
    // React StrictMode replays effects during development. The API boundary
    // also coalesces remounts so a launch cannot request UAC twice.
    if (meterStartupRequested.current) return;
    meterStartupRequested.current = true;
    void api
      .ensureMeterService()
      .then(async (result) => {
        switch (result) {
          case "STARTED":
            toast.success("计费服务已自动启动，会在关闭客户端后继续运行。");
            break;
          case "INSTALLED":
            toast.success(
              "计费服务已自动安装并启动，会在关闭客户端后继续运行。",
            );
            break;
          case "NOT_INSTALLED":
            toast.warning("安装包缺少计费服务文件，请重新安装最新版客户端。");
            break;
          case "NEEDS_ADMIN":
            toast.error(
              "安装或更新计费服务配置需要右键以管理员身份运行客户端。",
            );
            break;
          case "SERVER_UNAVAILABLE":
            toast.error(
              "无法连接后台，计费服务暂未安装。请检查服务器地址后重新打开客户端。",
            );
            break;
          case "INSTALL_FAILED":
            toast.error(
              (await api.meterSetupError().catch(() => null)) ??
                "计费服务自动安装失败，请查看应用数据目录中的 meter-setup-status.json。",
            );
            break;
          case "FAILED":
            toast.error("计费服务启动失败，请在服务管理器中检查状态。");
            break;
          default:
            break;
        }
      })
      .catch(() => toast.error("无法检查计费服务状态，请稍后重试。"));
  }, []);

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

  if (sessionQuery.isPending) {
    return <SessionLoading />;
  }

  if (sessionQuery.error && !sessionQuery.data) {
    return (
      <SessionError
        error={sessionQuery.error}
        onRetry={() => void sessionQuery.refetch()}
      />
    );
  }

  const session = sessionQuery.data;
  if (localSignedOut || !session?.authenticated || !session.user) {
    return (
      <SystemLoginPage
        onAuthenticated={handleAuthenticated}
        statusError={logoutError ?? undefined}
      />
    );
  }

  return <Dashboard session={session} onLogout={() => void handleLogout()} />;
}
