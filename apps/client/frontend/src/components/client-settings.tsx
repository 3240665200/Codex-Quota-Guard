import { useEffect, useId } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Power, RefreshCw } from "lucide-react";

import { api, getApiErrorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useQuotaDisplay } from "@/lib/quota-display";

const autostartQueryKey = ["desktop-autostart"];

export function ClientSettings() {
  const { mode, setMode } = useQuotaDisplay();
  const labelId = useId();
  const descriptionId = useId();
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: autostartQueryKey,
    queryFn: api.autostartStatus,
    retry: false,
    refetchOnMount: "always",
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const mutation = useMutation({
    mutationKey: autostartQueryKey,
    mutationFn: api.setAutostart,
    onMutate: () => queryClient.cancelQueries({ queryKey: autostartQueryKey }),
    onSuccess: (result) => queryClient.setQueryData(autostartQueryKey, result),
    // Re-read Windows after either outcome; the registration may have changed
    // even if saving its upgrade preference failed. Keep the actionable error.
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: autostartQueryKey }),
  });
  const { refetch } = query;

  useEffect(() => {
    const refreshOnReturn = () => {
      const status =
        queryClient.getQueryData<
          Awaited<ReturnType<typeof api.autostartStatus>>
        >(autostartQueryKey);
      if (
        document.visibilityState !== "visible" ||
        status?.supported === false ||
        queryClient.isMutating({ mutationKey: autostartQueryKey }) > 0 ||
        queryClient.isFetching({ queryKey: autostartQueryKey }) > 0
      ) {
        return;
      }
      void refetch();
    };

    window.addEventListener("focus", refreshOnReturn);
    document.addEventListener("visibilitychange", refreshOnReturn);
    return () => {
      window.removeEventListener("focus", refreshOnReturn);
      document.removeEventListener("visibilitychange", refreshOnReturn);
    };
  }, [queryClient, refetch]);

  const refresh = () => {
    mutation.reset();
    void query.refetch();
  };

  return (
    <div className="space-y-6">
      <section
        className="cqg-surface p-4 sm:p-5"
        aria-labelledby="display-settings-heading"
      >
        <h2 id="display-settings-heading" className="text-base font-semibold">
          显示设置
        </h2>
        <fieldset className="mt-5">
          <legend className="text-sm font-medium">额度显示</legend>
          <div className="mt-3 flex flex-wrap gap-6">
            {(
              [
                { value: "remaining", label: "剩余" },
                { value: "used", label: "已用" },
              ] as const
            ).map((item) => (
              <label
                key={item.value}
                className="flex cursor-pointer items-center gap-2 text-sm"
              >
                <input
                  type="radio"
                  name="quota-display"
                  value={item.value}
                  checked={mode === item.value}
                  onChange={() => setMode(item.value)}
                  className="size-4 accent-primary"
                />
                {item.label}
              </label>
            ))}
          </div>
          <p className="mt-3 text-xs leading-5 text-muted-foreground">
            调整个人、团队和官方额度的显示数值与进度条，选择会保存在本机。
          </p>
        </fieldset>
      </section>
      <section
        className="cqg-surface p-4 sm:p-5"
        aria-labelledby="startup-settings-heading"
      >
        <div className="mb-5 flex items-start justify-between gap-3">
          <div>
            <h2
              id="startup-settings-heading"
              className="text-base font-semibold"
            >
              启动设置
            </h2>
            <p className="mt-1 text-xs text-muted-foreground">
              管理本机桌面程序的启动方式。
            </p>
          </div>
          <Power className="size-5 shrink-0 text-primary" aria-hidden="true" />
        </div>

        {query.isPending ? (
          <p className="text-sm text-muted-foreground" role="status">
            正在读取系统自启动状态…
          </p>
        ) : query.isError ? (
          <div className="space-y-3">
            <p className="text-sm text-[var(--cqg-status-error)]" role="alert">
              无法读取自启动状态。{getApiErrorMessage(query.error)}
            </p>
            <Button
              size="sm"
              variant="outline"
              onClick={refresh}
              disabled={query.isFetching}
            >
              重试读取
            </Button>
          </div>
        ) : !query.data.supported ? (
          <p className="text-sm text-muted-foreground">
            当前系统暂不支持开机自启动设置
          </p>
        ) : (
          <>
            <div className="flex items-center justify-between gap-6 rounded-lg border bg-muted/30 p-4">
              <div className="min-w-0">
                <p id={labelId} className="text-sm font-medium">
                  开机自启动
                </p>
                <p
                  id={descriptionId}
                  className="mt-1 text-xs leading-5 text-muted-foreground"
                >
                  登录 Windows 后自动打开客户端。
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-labelledby={labelId}
                aria-describedby={descriptionId}
                aria-checked={query.data.enabled}
                aria-busy={mutation.isPending}
                disabled={query.isFetching || mutation.isPending}
                onClick={() => mutation.mutate(!query.data.enabled)}
                className={cn(
                  "inline-flex h-7 w-12 shrink-0 items-center rounded-full border-2 border-transparent p-0.5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-wait disabled:opacity-50",
                  query.data.enabled ? "bg-primary" : "bg-muted-foreground/35",
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "size-5 rounded-full bg-white shadow-sm transition-transform",
                    query.data.enabled ? "translate-x-5" : "translate-x-0",
                  )}
                />
              </button>
            </div>
            <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
              <span role="status" className="text-xs text-muted-foreground">
                {mutation.isPending
                  ? "正在保存…"
                  : query.data.enabled
                    ? "已开启"
                    : "已关闭"}
              </span>
              <Button
                size="sm"
                variant="ghost"
                onClick={refresh}
                disabled={query.isFetching || mutation.isPending}
              >
                <RefreshCw
                  aria-hidden="true"
                  className={query.isFetching ? "animate-spin" : undefined}
                />
                刷新状态
              </Button>
            </div>
            {mutation.isError ? (
              <p
                className="mt-3 text-sm text-[var(--cqg-status-error)]"
                role="alert"
              >
                自启动设置失败。{getApiErrorMessage(mutation.error)}{" "}
                请刷新状态后重试。
              </p>
            ) : null}
          </>
        )}
        {query.data?.supported ? (
          <p className="mt-3 text-xs leading-5 text-muted-foreground">
            也可在任务管理器 → 启动应用 → Codex Quota Guard
            中启用或禁用；返回此页会自动刷新状态。
          </p>
        ) : null}
        <p className="mt-5 border-t pt-4 text-xs leading-5 text-muted-foreground">
          此开关只控制桌面界面，关闭不会影响统计服务在后台运行。统计服务随
          Windows 自动启动，故障恢复由 Windows 负责。
        </p>
      </section>
    </div>
  );
}
