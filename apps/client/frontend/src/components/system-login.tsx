import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Eye,
  EyeOff,
  KeyRound,
  LoaderCircle,
  Settings2,
  ShieldCheck,
} from "lucide-react";
import { FormEvent, useEffect, useState } from "react";
import { toast } from "sonner";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
} from "@/components/ui/card";
import { api, getApiErrorMessage, type SystemSession } from "@/lib/api";

interface SystemLoginPageProps {
  onAuthenticated: (session: SystemSession) => void;
  statusError?: unknown;
}

export function SystemLoginPage({
  onAuthenticated,
  statusError,
}: SystemLoginPageProps) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [showServerSettings, setShowServerSettings] = useState(false);
  const [serverUrl, setServerUrl] = useState("");
  const [needsRestart, setNeedsRestart] = useState(false);
  const serverConfigQuery = useQuery({
    queryKey: ["server-config"],
    queryFn: api.serverConfig,
    retry: false,
  });
  useEffect(() => {
    if (serverConfigQuery.data) {
      setServerUrl(serverConfigQuery.data.url);
    }
  }, [serverConfigQuery.data]);
  const saveServerMutation = useMutation({
    mutationFn: () =>
      api.saveServerConfig({
        url: serverUrl.trim(),
      }),
    onSuccess: (config) => {
      setServerUrl(config.url);
      setNeedsRestart(true);
      toast.success("服务器地址已保存，请重新打开客户端。");
    },
    onError: (error) => toast.error(getApiErrorMessage(error)),
  });
  const loginMutation = useMutation({
    mutationFn: () => api.systemLogin(email.trim(), password),
    onSuccess: (session) => {
      if (!session.authenticated || !session.user) {
        toast.error("账号验证未通过，请检查登录状态。");
        return;
      }
      onAuthenticated(session);
      toast.success("系统登录成功");
    },
    onError: (error) => {
      toast.error(`登录失败：${getApiErrorMessage(error)}`);
    },
  });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (needsRestart) {
      toast.error("服务器地址已更改，请重启客户端后再登录。");
      return;
    }
    if (!email.trim() || !password) {
      toast.error("请输入邮箱和密码。");
      return;
    }
    loginMutation.mutate();
  };

  return (
    <main className="min-h-screen bg-background px-4 py-5 sm:px-8">
      <div className="mx-auto flex min-h-[calc(100vh-2.5rem)] w-full max-w-5xl flex-col">
        <div className="flex items-center gap-2 border-b pb-4 text-sm font-semibold">
          <div className="flex size-8 items-center justify-center rounded-md bg-foreground text-background">
            <ShieldCheck aria-hidden="true" className="size-4" />
          </div>
          Quota Guard
          <span className="ml-2 border-l pl-3 text-xs font-normal text-muted-foreground">
            客户端
          </span>
        </div>
        <div className="flex flex-1 items-center justify-center py-8">
          <Card className="w-full max-w-[430px] shadow-sm">
            <CardHeader className="gap-1 pb-4">
              <h1 className="text-xl font-semibold">登录 Quota Guard</h1>
              <CardDescription>系统账户</CardDescription>
            </CardHeader>

            <CardContent className="flex flex-col gap-4">
              {statusError ? (
                <Alert variant="destructive">
                  <AlertTitle>无法验证登录状态</AlertTitle>
                  <AlertDescription>
                    {getApiErrorMessage(statusError)}
                    。仍可尝试登录，成功后会重新建立会话。
                  </AlertDescription>
                </Alert>
              ) : null}

              <form className="flex flex-col gap-4" onSubmit={submit}>
                <div className="flex flex-col gap-2">
                  <label className="text-sm font-medium" htmlFor="system-email">
                    系统账号
                  </label>
                  <input
                    id="system-email"
                    name="email"
                    type="email"
                    autoComplete="username"
                    autoCapitalize="none"
                    spellCheck={false}
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                    placeholder="name@example.com"
                    required
                    className="h-10 rounded-md border bg-background px-3 text-sm outline-none transition focus-visible:ring-2 focus-visible:ring-ring"
                  />
                </div>

                <div className="flex flex-col gap-2">
                  <label
                    className="text-sm font-medium"
                    htmlFor="system-password"
                  >
                    密码
                  </label>
                  <div className="relative">
                    <input
                      id="system-password"
                      name="password"
                      type={passwordVisible ? "text" : "password"}
                      autoComplete="current-password"
                      value={password}
                      onChange={(event) => setPassword(event.target.value)}
                      placeholder="请输入系统账号密码"
                      required
                      className="h-10 w-full rounded-md border bg-background px-3 pr-11 text-sm outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring"
                    />
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="absolute right-0 top-0"
                      aria-label={passwordVisible ? "隐藏密码" : "显示密码"}
                      title={passwordVisible ? "隐藏密码" : "显示密码"}
                      onClick={() => setPasswordVisible((visible) => !visible)}
                    >
                      {passwordVisible ? (
                        <EyeOff aria-hidden="true" />
                      ) : (
                        <Eye aria-hidden="true" />
                      )}
                    </Button>
                  </div>
                </div>

                <Button
                  type="submit"
                  className="mt-2 w-full"
                  disabled={loginMutation.isPending || needsRestart}
                >
                  {loginMutation.isPending ? (
                    <LoaderCircle
                      data-icon="inline-start"
                      className="animate-spin"
                    />
                  ) : (
                    <KeyRound data-icon="inline-start" />
                  )}
                  {loginMutation.isPending ? "登录中…" : "登录系统"}
                </Button>
              </form>

              <div className="border-t pt-4">
                <Button
                  type="button"
                  variant="ghost"
                  className="w-full justify-start"
                  onClick={() => setShowServerSettings((shown) => !shown)}
                  aria-expanded={showServerSettings}
                >
                  <Settings2 data-icon="inline-start" />
                  服务器设置
                </Button>
                {showServerSettings ? (
                  <div className="mt-3 flex flex-col gap-3">
                    <label className="text-sm font-medium" htmlFor="server-url">
                      服务器地址
                    </label>
                    <input
                      id="server-url"
                      type="url"
                      inputMode="url"
                      autoCapitalize="none"
                      spellCheck={false}
                      value={serverUrl}
                      onChange={(event) => setServerUrl(event.target.value)}
                      placeholder="http://192.168.0.102:8080"
                      className="h-10 rounded-md border bg-background px-3 text-sm outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring"
                    />
                    <p className="text-xs text-muted-foreground">
                      支持 HTTP 和 HTTPS。本机可填
                      http://127.0.0.1:8080；其他电脑请填后端的实际 IP。 0.0.0.0
                      会自动转换为本机地址。
                    </p>
                    <Button
                      type="button"
                      variant="outline"
                      disabled={
                        saveServerMutation.isPending ||
                        serverConfigQuery.isPending
                      }
                      onClick={() => saveServerMutation.mutate()}
                    >
                      {saveServerMutation.isPending
                        ? "保存中…"
                        : "保存服务器地址"}
                    </Button>
                    {needsRestart ? (
                      <p role="status" className="text-sm text-primary">
                        已保存。请关闭并重新打开客户端，再登录系统。
                      </p>
                    ) : null}
                    {serverConfigQuery.isError ? (
                      <p className="text-xs text-destructive">
                        无法读取当前设置，请重启客户端重试。
                      </p>
                    ) : null}
                  </div>
                ) : null}
              </div>
            </CardContent>

            <CardFooter className="justify-center text-center text-xs text-muted-foreground">
              系统账号由管理员创建
            </CardFooter>
          </Card>
        </div>
      </div>
    </main>
  );
}
