import { useMutation, useQuery } from "@tanstack/react-query";
import { Eye, EyeOff, KeyRound, LoaderCircle, Settings2 } from "lucide-react";
import { FormEvent, useEffect, useState } from "react";
import { toast } from "sonner";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ThemeSwitcher } from "@/components/theme-switcher";
import { AppIcon } from "@/components/app-icon";
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
  const [serverSaveNotice, setServerSaveNotice] = useState("");
  const serverConfigQuery = useQuery({
    queryKey: ["server-config"],
    queryFn: api.serverConfig,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
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
      setServerSaveNotice(
        config.meterSync === "NOT_INSTALLED"
          ? "中央服务器地址已保存。统计程序未安装，请重新运行客户端安装器。"
          : "中央服务器地址已保存。",
      );
      toast.success("中央服务器地址已保存");
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
    if (saveServerMutation.isPending) return;
    if (!email.trim() || !password) {
      toast.error("请输入邮箱和密码。");
      return;
    }
    loginMutation.mutate();
  };

  return (
    <main className="flex min-h-screen flex-col bg-background p-4 sm:px-6">
      <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col">
        <div className="flex flex-wrap items-center gap-2 border-b border-border pb-3 text-sm font-semibold">
          <AppIcon className="size-8 shrink-0" />
          Quota Guard
          <span className="ml-2 border-l pl-3 text-xs font-normal text-muted-foreground">
            客户端
          </span>
          <ThemeSwitcher compact className="ml-auto" />
        </div>
        <div className="flex flex-1 items-center justify-center py-5">
          <Card className="w-full max-w-[430px] border-border shadow-sm">
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
                  disabled={
                    loginMutation.isPending || saveServerMutation.isPending
                  }
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
                  disabled={saveServerMutation.isPending}
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
                      disabled={
                        saveServerMutation.isPending || loginMutation.isPending
                      }
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
                        serverConfigQuery.isPending ||
                        loginMutation.isPending
                      }
                      onClick={() => saveServerMutation.mutate()}
                    >
                      {saveServerMutation.isPending
                        ? "正在保存…"
                        : "保存服务器地址"}
                    </Button>
                    {serverSaveNotice &&
                    !saveServerMutation.isPending &&
                    !saveServerMutation.isError ? (
                      <p role="status" className="text-sm text-primary">
                        {serverSaveNotice}
                      </p>
                    ) : null}
                    {saveServerMutation.isError ? (
                      <p role="alert" className="text-sm text-destructive">
                        {getApiErrorMessage(saveServerMutation.error)}
                      </p>
                    ) : null}
                    {serverConfigQuery.isError ? (
                      <p className="text-xs text-destructive">
                        无法读取当前设置，请稍后重试。
                      </p>
                    ) : null}
                  </div>
                ) : null}
              </div>
            </CardContent>

            <CardFooter className="flex-col justify-center gap-1 text-center text-xs text-muted-foreground">
              <p>
                系统登录状态在本机加密保存 7 天；退出登录或更换服务器后清除。
              </p>
              <p>系统账号由管理员创建</p>
            </CardFooter>
          </Card>
        </div>
      </div>
    </main>
  );
}
