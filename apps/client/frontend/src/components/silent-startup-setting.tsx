import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, getApiErrorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Button } from "./ui/button";

const queryKey = ["desktop-launch-preferences"];

export function SilentStartupSetting() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey,
    queryFn: api.launchPreferences,
    retry: false,
    refetchOnMount: "always",
    refetchOnWindowFocus: "always",
  });
  const mutation = useMutation({
    mutationKey: queryKey,
    mutationFn: api.setSilentStartup,
    onMutate: () => client.cancelQueries({ queryKey }),
    onSuccess: (preferences) => client.setQueryData(queryKey, preferences),
    onSettled: () => client.invalidateQueries({ queryKey }),
  });
  const busy = query.isFetching || mutation.isPending;
  return (
    <div className="mt-5 border-t pt-5">
      {query.isPending ? (
        <p role="status" className="text-sm text-muted-foreground">
          正在读取静默启动设置…
        </p>
      ) : query.isError ? (
        <div className="space-y-3">
          <p role="alert" className="text-sm text-[var(--cqg-status-error)]">
            无法读取静默启动设置。{getApiErrorMessage(query.error)}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => {
                mutation.reset();
                void query.refetch();
              }}
            >
              重试静默启动设置
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busy}
              onClick={() => mutation.mutate(false)}
            >
              恢复正常启动
            </Button>
          </div>
        </div>
      ) : (
        <>
          <div className="flex items-center justify-between gap-6 rounded-lg border bg-muted/30 p-4">
            <div className="min-w-0">
              <p className="text-sm font-medium">静默启动</p>
              <p
                id="silent-startup-description"
                className="mt-1 text-xs leading-5 text-muted-foreground"
              >
                启动时不显示主窗口，仅显示托盘图标。可点击托盘图标或“打开主窗口”进入客户端。
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-label="静默启动"
              aria-describedby="silent-startup-description"
              aria-checked={query.data.silentStartup}
              aria-busy={mutation.isPending}
              disabled={busy}
              onClick={() => mutation.mutate(!query.data.silentStartup)}
              className={cn(
                "inline-flex h-7 w-12 shrink-0 items-center rounded-full border-2 border-transparent p-0.5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-wait disabled:opacity-50",
                query.data.silentStartup
                  ? "bg-primary"
                  : "bg-muted-foreground/35",
              )}
            >
              <span
                aria-hidden="true"
                className={cn(
                  "size-5 rounded-full bg-white shadow-sm transition-transform",
                  query.data.silentStartup ? "translate-x-5" : "translate-x-0",
                )}
              />
            </button>
          </div>
          <p role="status" className="mt-3 text-xs text-muted-foreground">
            {mutation.isPending
              ? "正在保存…"
              : query.data.silentStartup
                ? "已开启，下次启动时仅显示托盘图标。"
                : "已关闭，下次启动时显示主窗口。"}
          </p>
          <p className="mt-2 text-xs leading-5 text-muted-foreground">
            此选项不改变开机自启动开关，下次启动客户端时生效。
          </p>
        </>
      )}
      {mutation.isError && (
        <p role="alert" className="mt-3 text-sm text-[var(--cqg-status-error)]">
          静默启动设置保存失败。{getApiErrorMessage(mutation.error)}
        </p>
      )}
    </div>
  );
}
