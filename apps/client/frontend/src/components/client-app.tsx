import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { useState } from "react";
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
  const sessionQuery = useQuery({
    queryKey: ["system-session"],
    queryFn: api.systemSession,
    retry: false,
    refetchInterval: 30_000,
  });

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
