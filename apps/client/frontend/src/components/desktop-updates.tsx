import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useQuery } from "@tanstack/react-query";
import { Channel, invoke } from "@tauri-apps/api/core";
import * as Dialog from "@radix-ui/react-dialog";
import { Download, RefreshCw, PackageCheck, X } from "lucide-react";
import { api, getApiErrorMessage } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import metadata from "../../package.json";

type Update = Awaited<ReturnType<typeof api.desktopUpdateCheck>>;
type ProgressEvent = {
  stage: "checking" | "downloading" | "verifying" | "installing";
  downloaded: number;
  total: number;
};
type Context = {
  currentVersion: string;
  data?: Update;
  checking: boolean;
  error?: string;
  notificationError?: string;
  check: () => void;
  open: () => void;
};
const Updates = createContext<Context>({
  currentVersion: metadata.version,
  checking: false,
  check: () => {},
  open: () => {},
});
const stages = {
  checking: "正在确认更新版本…",
  downloading: "正在下载安装包…",
  verifying: "正在校验安装包签名…",
  installing: "正在打开更新程序…",
};
const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MiB`;

export function DesktopUpdatesProvider({ children }: { children: ReactNode }) {
  const [serverEpoch, setServerEpoch] = useState(0);
  const [open, setOpen] = useState(false);
  const [progress, setProgress] = useState<ProgressEvent>();
  const [installError, setInstallError] = useState<string>();
  const [notificationError, setNotificationError] = useState<string>();
  const [installing, setInstalling] = useState(false);
  const busy = useRef(false);
  const query = useQuery({
    queryKey: ["desktop-updates", serverEpoch],
    queryFn: ({ signal }) => api.desktopUpdateCheck(signal),
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  useEffect(() => {
    const changed = () => {
      setServerEpoch((v) => v + 1);
      setNotificationError(undefined);
    };
    window.addEventListener("quota-guard:server-changed", changed);
    return () =>
      window.removeEventListener("quota-guard:server-changed", changed);
  }, []);
  useEffect(() => {
    if (!query.data?.available || !query.data.latestVersion) return;
    let active = true;
    void invoke("desktop_update_notify", {
      version: query.data.latestVersion,
      expectedOrigin: query.data.origin,
    }).catch((error) => {
      if (active) setNotificationError(getApiErrorMessage(error));
    });
    return () => {
      active = false;
    };
  }, [query.data]);

  const install = async () => {
    if (busy.current || !query.data?.available || !query.data.latestVersion)
      return;
    busy.current = true;
    setInstalling(true);
    setInstallError(undefined);
    setProgress({ stage: "checking", downloaded: 0, total: 0 });
    const channel = new Channel<ProgressEvent>();
    channel.onmessage = setProgress;
    try {
      await invoke("desktop_update_install", {
        expectedVersion: query.data.latestVersion,
        expectedOrigin: query.data.origin,
        onProgress: channel,
      });
    } catch (error) {
      setInstallError(getApiErrorMessage(error));
    } finally {
      busy.current = false;
      setInstalling(false);
    }
  };
  const percent = progress?.total
    ? Math.min(100, (progress.downloaded / progress.total) * 100)
    : 0;
  const context: Context = {
    currentVersion: query.data?.currentVersion ?? metadata.version,
    data: query.data,
    checking: query.isFetching,
    error: query.isError
      ? "无法检查版本，请确认后台支持版本管理并检查网络连接。"
      : undefined,
    notificationError,
    check: () => {
      if (!busy.current) void query.refetch();
    },
    open: () => setOpen(true),
  };
  return (
    <Updates.Provider value={context}>
      {children}
      <Dialog.Root
        open={open}
        onOpenChange={(value) => {
          if (!busy.current) {
            setOpen(value);
            setInstallError(undefined);
            setProgress(undefined);
          }
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
          <Dialog.Content
            className="fixed left-1/2 top-1/2 z-50 max-h-[calc(100vh-2rem)] w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-xl border bg-background p-5 shadow-xl"
            onEscapeKeyDown={(event) => {
              if (busy.current) event.preventDefault();
            }}
            onInteractOutside={(event) => {
              if (busy.current) event.preventDefault();
            }}
          >
            <Dialog.Title className="flex items-center gap-2 text-lg font-semibold">
              <PackageCheck className="size-5 text-primary" />
              客户端更新
            </Dialog.Title>
            <Dialog.Description className="mt-2 text-sm text-muted-foreground">
              当前版本 v{context.currentVersion}
              。更新将同时升级安装包内的统计程序。
            </Dialog.Description>
            <div className="mt-5 space-y-4">
              <p className="text-sm font-medium" role="status">
                {query.isFetching
                  ? "正在检查更新…"
                  : query.data?.available
                    ? `发现新版本 v${query.data.latestVersion}`
                    : query.data
                      ? "当前已是最新版本"
                      : "尚未获取到版本信息"}
              </p>
              {query.data?.available && (
                <>
                  <p className="text-xs text-muted-foreground">
                    安装包 {size(query.data.sizeBytes)}
                    {query.data.publishedAt
                      ? ` · ${new Date(query.data.publishedAt).toLocaleString()}`
                      : ""}
                  </p>
                  {query.data.notes && (
                    <p className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-muted/50 p-3 text-sm leading-6">
                      {query.data.notes}
                    </p>
                  )}
                  <p className="text-xs leading-5 text-muted-foreground">
                    下载完成并通过校验后，将关闭客户端并打开更新程序。安装时可能需要系统授权。
                  </p>
                </>
              )}
              {progress && (
                <div className="space-y-2" aria-live="polite">
                  <p className="text-sm">{stages[progress.stage]}</p>
                  <Progress value={percent} aria-label="更新下载进度" />
                  <p className="text-xs tabular-nums text-muted-foreground">
                    {progress.total > 0
                      ? `${size(progress.downloaded)} / ${size(progress.total)} · ${percent.toFixed(1)}%`
                      : "正在连接后台"}
                  </p>
                </div>
              )}
              {(installError || context.error) && (
                <p
                  role="alert"
                  className="text-sm text-[var(--cqg-status-error)]"
                >
                  {installError || context.error}
                </p>
              )}
            </div>
            <div className="mt-6 flex justify-end gap-2">
              <Button
                variant="outline"
                disabled={installing || query.isFetching}
                onClick={context.check}
              >
                <RefreshCw className="size-4" />
                检查更新
              </Button>
              {query.data?.available && (
                <Button
                  disabled={installing || query.isFetching}
                  onClick={() => void install()}
                >
                  <Download className="size-4" />
                  {installing
                    ? "更新中…"
                    : installError
                      ? "重新下载更新"
                      : "下载并更新"}
                </Button>
              )}
            </div>
            <Dialog.Close asChild>
              <Button
                className="absolute right-3 top-3 size-7"
                size="icon"
                variant="ghost"
                disabled={installing}
                aria-label="关闭更新窗口"
              >
                <X className="size-4" />
              </Button>
            </Dialog.Close>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </Updates.Provider>
  );
}

export function DesktopVersionButton() {
  const updates = useContext(Updates);
  return (
    <button
      type="button"
      onClick={updates.open}
      title={`客户端 v${updates.currentVersion}${updates.data?.available ? ` · 新版本 v${updates.data.latestVersion}` : " · 查看更新"}`}
      className="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-xs text-muted-foreground hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      aria-label={`客户端版本 v${updates.currentVersion}，查看更新`}
    >
      <PackageCheck className="size-4 shrink-0" aria-hidden="true" />
      <span className="cqg-nav-copy">v{updates.currentVersion}</span>
      {updates.data?.available && (
        <span className="cqg-nav-copy ml-auto rounded bg-primary/15 px-1.5 py-0.5 text-[10px] text-primary">
          有更新
        </span>
      )}
    </button>
  );
}

export function DesktopUpdateSettings() {
  const updates = useContext(Updates);
  return (
    <section
      className="cqg-surface p-4 sm:p-5"
      aria-labelledby="update-settings-heading"
    >
      <h2 id="update-settings-heading" className="text-base font-semibold">
        版本与更新
      </h2>
      <dl className="mt-4 flex flex-wrap gap-x-8 gap-y-2 text-sm">
        <div>
          <dt className="text-muted-foreground">当前版本</dt>
          <dd className="mt-1 font-medium">v{updates.currentVersion}</dd>
        </div>
        {updates.data?.latestVersion && (
          <div>
            <dt className="text-muted-foreground">最新版本</dt>
            <dd className="mt-1 font-medium">v{updates.data.latestVersion}</dd>
          </div>
        )}
      </dl>
      <p className="mt-3 text-xs leading-5 text-muted-foreground">
        启动时自动检查，每个新版本仅发送一次系统通知。下载和安装由你确认后开始。
      </p>
      {updates.error && (
        <p
          role="alert"
          className="mt-3 text-sm text-[var(--cqg-status-warning)]"
        >
          {updates.error}
        </p>
      )}
      {updates.notificationError && (
        <p className="mt-2 text-xs text-muted-foreground">
          {updates.notificationError}
        </p>
      )}
      <div className="mt-4 flex flex-wrap gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={updates.checking}
          onClick={updates.check}
        >
          <RefreshCw className="size-4" />
          {updates.checking ? "正在检查…" : "检查更新"}
        </Button>
        <Button
          size="sm"
          onClick={updates.open}
          variant={updates.data?.available ? "default" : "outline"}
        >
          <Download className="size-4" />
          {updates.data?.available ? "在线更新" : "查看版本信息"}
        </Button>
      </div>
    </section>
  );
}
