import { useEffect, useId, useRef, useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ChevronLeft,
  ChevronRight,
  ListChecks,
  RefreshCw,
  X,
} from "lucide-react";
import {
  api,
  getApiErrorMessage,
  type MeterRuntimeLogs,
  type UsageEventPage,
  type UsageStatus,
} from "@/lib/api";
import { formatCompactTokenCount, formatTokenCount } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { UsageStatistics } from "@/components/usage-statistics";
import { formatUsd } from "@/lib/usage-format";

export const serviceLabels: Record<UsageStatus["service"]["state"], string> = {
  NOT_INSTALLED: "统计程序未安装",
  OFFLINE: "统计程序离线",
  BASELINE_READY: "统计基线已建立",
  UNPAIRED: "统计程序待配对",
  UP_TO_DATE: "统计程序运行中",
  REPORTED: "统计程序运行中",
  ERROR: "统计程序异常",
};
const fallbackLabels: Record<UsageStatus["fallback"]["state"], string> = {
  SIGNED_OUT: "请先登录本系统账户",
  WAITING_FOR_SESSIONS: "尚未找到官方 Codex 会话目录",
  BASELINE_READY: "本机扫描已建立基线",
  UP_TO_DATE: "本机扫描暂无新事件",
  REPORTED: "本机用量已上报",
  REPORT_FAILED: "本机扫描发现用量，但上报失败，稍后会重试",
  SCAN_FAILED: "本机扫描失败",
  SERVICE_ACTIVE: "常驻服务负责采集，回退扫描待命",
};
const scannerIssueLabels: Record<string, string> = {
  SESSIONS_DIRECTORY_UNAVAILABLE: "会话目录不可用",
  FILE_UNREADABLE: "日志文件不可读",
  MALFORMED_JSONL_RECORD: "日志行格式无效",
  OVERSIZED_JSONL_RECORD: "超大日志记录",
  INVALID_USAGE_RECORD: "用量记录无效",
  STALE_USAGE_EVENT: "过期用量记录",
  CUMULATIVE_COUNTER_RESET: "累计计数重置",
};
const scannerIssueDescriptions: Record<string, string> = {
  SESSIONS_DIRECTORY_UNAVAILABLE: "当前无法读取官方会话目录，请稍后重新扫描。",
  FILE_UNREADABLE: "日志文件暂时无法读取，请检查文件权限后重新扫描。",
  MALFORMED_JSONL_RECORD: "无法解析该日志行，已跳过，不会读取或展示原始内容。",
  OVERSIZED_JSONL_RECORD: "超长日志行已跳过，不会作为用量上报。",
  INVALID_USAGE_RECORD:
    "记录未通过本地安全校验，已跳过且不会阻断同批有效用量。",
  STALE_USAGE_EVENT: "记录早于允许的 29 天范围，已跳过且不会阻断同批有效用量。",
  CUMULATIVE_COUNTER_RESET:
    "累计计数下降时只重置基线，不会将下降值算作新用量。",
};
const MAX_VISIBLE_ISSUE_TYPES = 20;
const costLabels = {
  MISSING_PRICE: "未定价",
  MISSING_BREAKDOWN: "分项缺失",
  INVALID_BREAKDOWN: "分项不一致",
  PRICED: "已估算",
};

function TokenBreakdown({ value }: { value: number | null }) {
  return value === null ? (
    <span className="text-muted-foreground" title="该记录未提供此项用量">
      未知
    </span>
  ) : (
    <span
      className="font-medium tabular-nums"
      title={`精确值：${formatTokenCount(value)} Token`}
    >
      {formatTokenCount(value)}
    </span>
  );
}

function TokenDetailRow({
  label,
  value,
}: {
  label: string;
  value: number | null;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right [overflow-wrap:anywhere]">
        <TokenBreakdown value={value} />
      </dd>
    </div>
  );
}

function UsageTokens({ item }: { item: UsageEventPage["items"][number] }) {
  const metadata = [
    ["消耗时间", time(item.occurredAt)],
    ["后台接收", time(item.receivedAt)],
    ["模型名称", item.model ?? "未知"],
    ["思考等级", item.reasoningEffort ?? "未知"],
    [
      "来源",
      item.source === "TOKEN_USAGE_RECORD" ? "官方用量记录" : "累计差值补录",
    ],
    ["状态", "已接收"],
  ];
  return (
    <Dialog.Root>
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0 space-y-1">
          <div className="text-primary">
            <Tokens value={item.totalTokens} />
          </div>
          <div className="truncate text-xs text-muted-foreground">
            <EstimatedCost item={item} />
          </div>
        </div>
        <Dialog.Trigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-8 shrink-0 px-2 text-xs text-primary"
          >
            查看详情
          </Button>
        </Dialog.Trigger>
      </div>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100dvh-1rem)] w-[calc(100%_-_2rem)] max-w-xl -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border bg-background text-foreground shadow-2xl focus:outline-none">
          <div className="flex shrink-0 items-start justify-between gap-4 border-b px-4 py-3 sm:px-5 sm:py-4">
            <div className="min-w-0">
              <Dialog.Title className="text-lg font-semibold">
                消耗详情
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-xs text-muted-foreground">
                已被中央服务接收的单笔记录，时间按本地时区显示。
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="size-8 shrink-0"
                aria-label="关闭详情"
              >
                <X aria-hidden="true" />
              </Button>
            </Dialog.Close>
          </div>
          <div
            className="cqg-scrollbar-hidden min-h-0 flex-1 space-y-4 overflow-y-auto overscroll-contain px-4 py-3 sm:px-5 sm:py-4"
            role="region"
            aria-label="消耗详情内容"
            tabIndex={0}
          >
            <dl className="grid gap-4 rounded-xl bg-muted/50 p-4 sm:grid-cols-2">
              <div>
                <dt className="text-xs text-muted-foreground">总消耗</dt>
                <dd className="mt-1 text-xl text-primary">
                  <TokenBreakdown value={item.totalTokens} />{" "}
                  <span className="text-xs text-muted-foreground">Token</span>
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">费用估算</dt>
                <dd className="mt-1 text-lg font-medium">
                  <EstimatedCost item={item} />
                </dd>
              </div>
            </dl>
            <div>
              <h3 className="text-sm font-semibold">Token 明细</h3>
              <dl className="mt-3 space-y-3 text-sm">
                <TokenDetailRow
                  label="输入（含缓存）"
                  value={item.inputTokens}
                />
                <TokenDetailRow
                  label="输出（含思考）"
                  value={item.outputTokens}
                />
                <TokenDetailRow label="缓存读" value={item.cachedInputTokens} />
                <TokenDetailRow
                  label="缓存写"
                  value={item.cacheWriteInputTokens}
                />
                <TokenDetailRow
                  label="思考"
                  value={item.reasoningOutputTokens}
                />
              </dl>
              <p className="mt-3 text-xs leading-relaxed text-muted-foreground">
                输入已包含缓存，输出已包含思考，分项不要重复相加。费用按当前模型单价估算，不是官方账单。
              </p>
            </div>
            <dl className="grid gap-x-5 gap-y-4 border-t pt-4 text-sm sm:grid-cols-2">
              {metadata.map(([label, value]) => (
                <div key={label} className="min-w-0">
                  <dt className="text-xs text-muted-foreground">{label}</dt>
                  <dd className="mt-1 break-words tabular-nums [overflow-wrap:anywhere]">
                    {value}
                  </dd>
                </div>
              ))}
            </dl>
          </div>
          <div className="flex shrink-0 justify-end border-t px-4 py-3 sm:px-5">
            <Dialog.Close asChild>
              <Button type="button" variant="outline" size="sm">
                关闭
              </Button>
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function EstimatedCost({ item }: { item: UsageEventPage["items"][number] }) {
  const value = item.estimatedCostUsd;
  if (value === null)
    return (
      <span className="text-xs text-muted-foreground">
        {item.costStatus ? costLabels[item.costStatus] : "分项缺失"}
      </span>
    );
  return (
    <span className="tabular-nums" title={`USD ${value} · 当前配置单价估算`}>
      USD {formatUsd(value)}
    </span>
  );
}

function summarizeScannerIssues(fallback: UsageStatus["fallback"]) {
  const counts = fallback.issueCounts;
  const aggregated =
    counts && Object.keys(counts).length > 0
      ? counts
      : fallback.issues.reduce<Record<string, number>>((result, code) => {
          result[code] = (result[code] ?? 0) + 1;
          return result;
        }, {});
  return Object.entries(aggregated)
    .filter(([, count]) => Number.isInteger(count) && count > 0)
    .sort(([left], [right]) => left.localeCompare(right));
}

const logLabels: Record<MeterRuntimeLogs["entries"][number]["kind"], string> = {
  SERVICE_STARTED: "统计程序启动",
  SERVICE_STOPPED: "统计程序停止",
  BASELINE_READY: "扫描基线建立",
  UNPAIRED: "等待设备配对",
  USAGE_REPORTED: "用量上报成功",
  HEARTBEAT: "后台心跳成功",
  ERROR: "服务处理失败",
  STATUS_CHANGE: "服务状态变化",
  GUARD_ACTIVE: "CC Switch 检测已启用",
  GUARD_INACTIVE: "CC Switch 检测已停用",
  GUARD_DETECTED: "发现 CC Switch 冲突",
  GUARD_TERMINATED: "已结束 CC Switch",
  GUARD_ERROR: "CC Switch 检测失败",
};
function explainError(code: string | null | undefined) {
  switch (code) {
    case "CONTROL_PLANE_UNAVAILABLE":
    case "METER_HTTP_ERROR":
    case "METER_REPORT_FAILED":
    case "METER_BACKEND_UNAVAILABLE":
    case "METER_HTTP_UNAVAILABLE":
      return "后台不可达或返回异常，请检查服务器地址、网络和后台运行状态。";
    case "SYSTEM_SESSION_REQUIRED":
    case "METER_UNAUTHORIZED":
    case "METER_PAIRING_EXPIRED":
    case "METER_PAIRING_INVALID":
      return "登录或设备授权已失效，请重新登录并配对。";
    case "QUOTA_REPORT_REJECTED":
    case "METER_REPORT_REJECTED":
      return "后台拒绝用量上报，请联系管理员检查用户、团队与设备绑定。";
    case "CONTROL_PLANE_REQUEST_REJECTED":
      return "后台拒绝了用量请求，请检查请求数据；这通常不是网络连接故障。";
    case "STATE_STORAGE_FAILED":
    case "METER_STATE_READ_FAILED":
    case "METER_STATE_WRITE_FAILED":
      return "扫描游标无法保存，请检查客户端数据目录的写入权限。";
    case "METER_STATUS_WRITE_FAILED":
      return "服务状态无法写入，请检查当前 Windows 用户的数据目录权限。";
    case "METER_LOG_WRITE_FAILED":
      return "服务日志无法写入，请检查 ProgramData 目录权限和磁盘空间。";
    case "METER_CC_SWITCH_GUARD_FAILED":
      return "无法确认 CC Switch 冲突已解除，请退出 CC Switch 并检查统计程序。";
    case "METER_GUARD_THREAD_UNAVAILABLE":
    case "METER_GUARD_CHECK_UNAVAILABLE":
    case "METER_GUARD_CHECK_STALE":
      return "CC Switch 检测线程未响应，请重启统计程序后重新检查启动条件。";
    default:
      return code ? `诊断码：${code}，请联系管理员检查服务日志。` : null;
  }
}
function time(value: string | number | null | undefined) {
  if (value == null) return "暂无记录";
  const date = new Date(typeof value === "number" ? value * 1000 : value);
  return Number.isNaN(date.valueOf())
    ? "时间不可用"
    : date.toLocaleString("zh-CN", { hour12: false });
}
function Tokens({ value }: { value: number }) {
  return (
    <span
      className="font-semibold tabular-nums"
      title={`精确值：${formatTokenCount(value)} Token`}
    >
      {formatCompactTokenCount(value)}{" "}
      <span className="text-xs font-normal text-muted-foreground">Token</span>
    </span>
  );
}

export function UsageActivityPanel({
  userId,
  view,
  status,
  statusError,
  measuredAt,
  scanning,
  pairing,
  starting,
  onScan,
  onPair,
  onStart,
}: {
  userId: string;
  view?: "overview" | "ledger" | "diagnostics";
  status: UsageStatus | undefined;
  statusError?: unknown;
  measuredAt?: string | null;
  scanning: boolean;
  pairing: boolean;
  starting: boolean;
  onScan: () => void;
  onPair: () => void;
  onStart: () => void;
}) {
  const [tab, setTab] = useState<"overview" | "ledger" | "diagnostics">(
    "overview",
  );
  const activeView = view ?? tab;
  const legacyView = view === undefined;
  const statusReadFailed = statusError != null;
  const [page, setPage] = useState(0);
  const id = useId();
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const queryClient = useQueryClient();
  useEffect(() => {
    setPage(0);
  }, [userId]);
  const ledger = useQuery({
    queryKey: ["usage-events", userId, page],
    queryFn: () => api.usageEvents(page, 10),
    retry: false,
    enabled: activeView === "ledger",
    refetchInterval: 10000,
  });
  const logs = useQuery({
    queryKey: ["meter-runtime-logs", userId],
    queryFn: api.meterRuntimeLogs,
    retry: false,
    enabled: activeView === "diagnostics",
    refetchInterval: 10000,
  });
  const meterVersion = useQuery({
    queryKey: ["meter-service-version"],
    queryFn: api.meterServiceVersion,
    retry: false,
    enabled: legacyView || activeView === "diagnostics",
    refetchInterval: 30000,
  });
  const serviceState = status?.service.state;
  const healthy = serviceState === "UP_TO_DATE" || serviceState === "REPORTED";
  const upgradeRequired =
    meterVersion.data?.status === "UPGRADE_REQUIRED" ||
    meterVersion.data?.needsUpgrade === true;
  const runtimeVersion = status?.service.version;
  const runtimeVersionMismatch =
    !!runtimeVersion &&
    !!meterVersion.data?.installedVersion &&
    runtimeVersion !== meterVersion.data.installedVersion;
  const runtimeVersionUnknown = healthy && !runtimeVersion;
  const versionUnverified =
    meterVersion.data?.status === "UNKNOWN" ||
    meterVersion.isError ||
    runtimeVersionUnknown;
  const versionNeedsAttention =
    upgradeRequired || runtimeVersionMismatch || versionUnverified;
  const showVersionAction =
    versionNeedsAttention && serviceState !== "NOT_INSTALLED";
  const sameVersionDifferentBinary =
    upgradeRequired &&
    !!meterVersion.data?.installedVersion &&
    meterVersion.data.installedVersion === meterVersion.data.bundledVersion;
  const fallbackFailed =
    status?.fallback.state === "REPORT_FAILED" ||
    status?.fallback.state === "SCAN_FAILED";
  const scannerIssues = status ? summarizeScannerIssues(status.fallback) : [];
  const hasSkippedNonUsageRecords = scannerIssues.some(
    ([code]) =>
      code === "OVERSIZED_JSONL_RECORD" || code === "CUMULATIVE_COUNTER_RESET",
  );
  const fallbackScanCompleted =
    status?.fallback.state === "UP_TO_DATE" ||
    status?.fallback.state === "REPORTED";
  const lastReport =
    status?.service.lastReportEpochSeconds ?? status?.fallback.lastReportedAt;
  const acceptedAt =
    page === 0 ? (ledger.data?.items[0]?.receivedAt ?? measuredAt) : measuredAt;
  const refresh = () => {
    if (legacyView) {
      void queryClient.invalidateQueries({ queryKey: ["usage-status"] });
      void meterVersion.refetch();
      if (activeView === "overview")
        void queryClient.invalidateQueries({
          queryKey: ["usage-analytics", userId],
        });
      else void (activeView === "ledger" ? ledger.refetch() : logs.refetch());
      return;
    }
    if (activeView === "overview")
      void queryClient.invalidateQueries({
        queryKey: ["usage-analytics", userId],
      });
    else if (activeView === "ledger") void ledger.refetch();
    else {
      void queryClient.invalidateQueries({ queryKey: ["usage-status"] });
      void meterVersion.refetch();
      void logs.refetch();
    }
  };
  const fetching =
    activeView === "overview"
      ? false
      : activeView === "ledger"
        ? ledger.isFetching
        : logs.isFetching;

  return (
    <section
      className={legacyView ? "cqg-surface min-w-0 p-4" : "min-w-0"}
      aria-labelledby={
        legacyView || activeView === "diagnostics" ? `${id}-heading` : undefined
      }
      aria-label={
        !legacyView && activeView !== "diagnostics"
          ? activeView === "overview"
            ? "个人用量统计"
            : "个人消耗流水"
          : undefined
      }
    >
      {!legacyView && activeView === "overview" ? (
        <UsageStatistics key={userId} userId={userId} />
      ) : null}
      {(legacyView || activeView === "diagnostics") && (
        <>
          <div className="flex min-w-0 flex-wrap items-start justify-between gap-3">
            <div className="flex min-w-0 items-start gap-3">
              <ListChecks
                className="mt-0.5 size-5 text-primary"
                aria-hidden="true"
              />
              <div className="min-w-0">
                <h2 id={`${id}-heading`} className="text-base font-semibold">
                  {legacyView ? "统计与消耗记录" : "运行诊断"}
                </h2>
                <p className="mt-1 text-xs text-muted-foreground">
                  {statusReadFailed
                    ? `统计状态读取失败${status ? "，当前显示上次读取结果" : ""}`
                    : serviceState
                      ? serviceLabels[serviceState]
                      : "正在检查统计程序…"}{" "}
                  · 每 10 秒更新记录
                </p>
              </div>
            </div>
            <span
              className={`text-xs font-medium ${healthy && !fallbackFailed && !versionNeedsAttention ? "text-primary" : "text-amber-700 dark:text-amber-400"}`}
            >
              {upgradeRequired
                ? "需升级"
                : fallbackFailed
                  ? "回退上报异常"
                  : runtimeVersionMismatch || versionUnverified
                    ? "版本待确认"
                    : healthy
                      ? "正常"
                      : "需检查"}
            </span>
          </div>
          {statusReadFailed ? (
            <div
              role="alert"
              className="mt-3 min-w-0 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950/30 dark:text-amber-200"
            >
              <p className="font-medium">统计状态读取失败</p>
              <p className="mt-1 text-xs [overflow-wrap:anywhere]">
                {getApiErrorMessage(statusError)}
                {status ? " 当前显示的是上次读取结果。" : " 请刷新重试。"}
              </p>
            </div>
          ) : null}
          {upgradeRequired ? (
            <div
              role="alert"
              className="mt-3 min-w-0 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950/30 dark:text-amber-200"
            >
              <p className="font-medium">统计程序需要更新</p>
              <p className="mt-1 text-xs [overflow-wrap:anywhere]">
                运行版本 {runtimeVersion ?? "未知"}，已安装版本{" "}
                {meterVersion.data?.installedVersion ?? "未知"}，安装包版本{" "}
                {meterVersion.data?.bundledVersion ?? "未知"}。
                {sameVersionDifferentBinary
                  ? "版本号相同，但程序文件校验不一致，仍需更新。"
                  : "已安装程序与安装包不一致，旧版可能缺少输入、输出等 Token 分项。"}
                点击更新以安装随客户端打包的版本；如提示权限不足，请从托盘退出客户端，再以管理员身份重新打开。已入库的旧流水不会自动补齐。
              </p>
              {showVersionAction ? (
                <Button
                  size="sm"
                  variant="outline"
                  className="mt-2"
                  disabled={starting}
                  onClick={onStart}
                >
                  <RefreshCw
                    aria-hidden="true"
                    className={starting ? "animate-spin" : undefined}
                  />
                  {starting ? "正在更新…" : "更新统计程序"}
                </Button>
              ) : null}
            </div>
          ) : null}
          {!upgradeRequired && (runtimeVersionMismatch || versionUnverified) ? (
            <div
              role="alert"
              className="mt-3 min-w-0 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950/30 dark:text-amber-200"
            >
              <p className="font-medium">统计程序运行版本待确认</p>
              <p className="mt-1 text-xs [overflow-wrap:anywhere]">
                {runtimeVersionMismatch
                  ? `运行版本 ${runtimeVersion} 与已安装版本 ${meterVersion.data?.installedVersion} 不一致，请重新启动统计程序。`
                  : meterVersion.isError
                    ? "无法读取安装版本，请刷新后重试。"
                    : meterVersion.data?.status === "UNKNOWN"
                      ? "安装版本无法通过程序文件校验，不能确认已是最新版；请检查安装状态。"
                      : "后台尚未收到运行版本，可能仍在运行旧版统计程序或旧版后端。请检查安装与服务状态。"}
              </p>
              {showVersionAction ? (
                <Button
                  size="sm"
                  variant="outline"
                  className="mt-2"
                  disabled={starting}
                  onClick={onStart}
                >
                  <RefreshCw
                    aria-hidden="true"
                    className={starting ? "animate-spin" : undefined}
                  />
                  {starting ? "正在检查…" : "检查并修复"}
                </Button>
              ) : null}
            </div>
          ) : null}
          <dl className="mt-4 grid grid-cols-[repeat(auto-fit,minmax(min(100%,12rem),1fr))] gap-3 rounded-lg border bg-muted/40 p-3 text-sm">
            <div>
              <dt className="text-xs text-muted-foreground">
                统计程序最近扫描
              </dt>
              <dd className="mt-1 tabular-nums">
                {time(status?.service.lastScanEpochSeconds)}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">最近成功上报</dt>
              <dd className="mt-1 tabular-nums">{time(lastReport)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">后台最近接收</dt>
              <dd className="mt-1 tabular-nums">{time(acceptedAt)}</dd>
            </div>
          </dl>
        </>
      )}
      {legacyView || activeView !== "overview" ? (
        <div
          className={`${legacyView ? "mt-4" : activeView === "diagnostics" ? "my-3" : "mb-3"} flex flex-wrap items-center justify-between gap-3 border-b pb-3`}
        >
          {legacyView ? (
            <div
              className="inline-flex gap-1 rounded-lg bg-muted p-1"
              role="tablist"
              aria-label="统计记录视图"
            >
              {(["overview", "ledger", "diagnostics"] as const).map(
                (value, index) => (
                  <button
                    key={value}
                    ref={(element) => {
                      tabs.current[index] = element;
                    }}
                    type="button"
                    id={`${id}-${value}-tab`}
                    role="tab"
                    aria-selected={tab === value}
                    aria-controls={`${id}-${value}`}
                    tabIndex={tab === value ? 0 : -1}
                    className={`rounded-md px-2.5 py-1.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:px-3 ${tab === value ? "bg-background font-medium shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                    onClick={() => setTab(value)}
                    onKeyDown={(event) => {
                      if (
                        ["ArrowLeft", "ArrowRight", "Home", "End"].includes(
                          event.key,
                        )
                      ) {
                        event.preventDefault();
                        const next =
                          event.key === "Home"
                            ? 0
                            : event.key === "End"
                              ? 2
                              : (index + (event.key === "ArrowLeft" ? 2 : 1)) %
                                3;
                        setTab(
                          (["overview", "ledger", "diagnostics"] as const)[
                            next
                          ],
                        );
                        tabs.current[next]?.focus();
                      }
                    }}
                  >
                    {value === "overview"
                      ? "用量统计"
                      : value === "ledger"
                        ? "消耗流水"
                        : "运行诊断"}
                  </button>
                ),
              )}
            </div>
          ) : null}
          <div className="flex flex-wrap gap-2">
            {activeView !== "overview" ? (
              <Button
                size="sm"
                variant="ghost"
                onClick={refresh}
                disabled={fetching}
                aria-label={
                  legacyView
                    ? "刷新统计记录"
                    : activeView === "ledger"
                      ? "刷新消耗流水"
                      : "刷新运行诊断"
                }
              >
                <RefreshCw
                  className={fetching ? "animate-spin" : undefined}
                  aria-hidden="true"
                />
                {legacyView
                  ? "刷新记录"
                  : activeView === "ledger"
                    ? "刷新流水"
                    : "刷新诊断"}
              </Button>
            ) : null}
            {legacyView || activeView === "diagnostics" ? (
              <>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={scanning}
                  onClick={onScan}
                >
                  {scanning ? "正在扫描…" : "立即扫描"}
                </Button>
                {serviceState === "UNPAIRED" ||
                status?.pairingState === "PAIRING_FAILED" ||
                status?.service.errorCode === "METER_PAIRING_EXPIRED" ||
                status?.service.errorCode === "METER_PAIRING_INVALID" ? (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={pairing}
                    onClick={onPair}
                  >
                    {pairing ? "正在配对…" : "重新配对"}
                  </Button>
                ) : null}
                {(serviceState === "OFFLINE" ||
                  serviceState === "NOT_INSTALLED") &&
                !showVersionAction ? (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={starting}
                    onClick={onStart}
                  >
                    {starting
                      ? "正在处理…"
                      : serviceState === "NOT_INSTALLED"
                        ? "安装统计程序"
                        : "启动统计程序"}
                  </Button>
                ) : null}
              </>
            ) : null}
          </div>
        </div>
      ) : null}
      {activeView === "overview" ? (
        legacyView ? (
          <div
            id={`${id}-overview`}
            role="tabpanel"
            aria-labelledby={`${id}-overview-tab`}
          >
            <UsageStatistics key={userId} userId={userId} />
          </div>
        ) : null
      ) : activeView === "ledger" ? (
        <div
          id={`${id}-ledger`}
          {...(legacyView
            ? {
                role: "tabpanel",
                "aria-labelledby": `${id}-ledger-tab`,
              }
            : {})}
          className={legacyView ? "pt-4" : "pt-1"}
        >
          {legacyView ? (
            <p className="mb-3 text-xs text-muted-foreground">
              中央服务已接收的消耗记录；点击“查看详情”查看 Token
              分项与估算费用。
            </p>
          ) : null}
          {ledger.isError ? (
            <div
              role="alert"
              className="mb-3 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950/30 dark:text-amber-200"
            >
              <p className="font-medium">消耗流水读取失败</p>
              <p className="mt-1 text-xs">
                {getApiErrorMessage(ledger.error)}
                {ledger.data ? " 下面保留上次读取的记录。" : ""}
              </p>
            </div>
          ) : null}
          {ledger.isPending ? (
            <p
              className="py-8 text-center text-sm text-muted-foreground"
              role="status"
            >
              正在读取消耗流水…
            </p>
          ) : ledger.data ? (
            <>
              {ledger.data.items.length ? (
                <div
                  className="cqg-scrollbar-hidden min-w-0 overflow-x-auto"
                  role="region"
                  aria-label="流水表格"
                  tabIndex={0}
                >
                  <table
                    className={`w-full table-fixed ${legacyView ? "min-w-[700px]" : "min-w-[640px]"} text-left text-sm`}
                    aria-label="个人消耗流水"
                  >
                    <colgroup>
                      {legacyView ? (
                        <>
                          <col className="w-[108px]" />
                          <col className="w-[160px]" />
                          <col className="w-[76px]" />
                          <col className="w-[108px]" />
                          <col className="w-[44px]" />
                          <col className="w-[116px]" />
                          <col className="w-[76px]" />
                        </>
                      ) : (
                        <>
                          <col className="w-1/4" />
                          <col className="w-[37%]" />
                          <col className="w-1/4" />
                          <col className="w-[13%]" />
                        </>
                      )}
                    </colgroup>
                    <thead className="border-b text-xs text-muted-foreground">
                      <tr>
                        <th className="pb-2 pr-2 font-medium" scope="col">
                          消耗时间
                        </th>
                        <th className="pb-2 pr-2 font-medium" scope="col">
                          Token 消耗
                        </th>
                        {legacyView ? (
                          <>
                            <th className="pb-2 pr-2 font-medium" scope="col">
                              来源
                            </th>
                            <th className="pb-2 pr-2 font-medium" scope="col">
                              后台接收
                            </th>
                            <th className="pb-2 font-medium" scope="col">
                              状态
                            </th>
                          </>
                        ) : null}
                        <th className="pb-2 pr-2 font-medium" scope="col">
                          模型名称
                        </th>
                        <th className="pb-2 font-medium" scope="col">
                          思考等级
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {ledger.data.items.map((item) => (
                        <tr key={item.id} className="border-b last:border-0">
                          <td className="py-2 pr-2 tabular-nums [overflow-wrap:anywhere]">
                            {time(item.occurredAt)}
                          </td>
                          <td className="py-2 pr-2">
                            <UsageTokens item={item} />
                          </td>
                          {legacyView ? (
                            <>
                              <td className="py-2 pr-2 text-xs text-muted-foreground">
                                {item.source === "TOKEN_USAGE_RECORD"
                                  ? "官方用量记录"
                                  : "累计差值补录"}
                              </td>
                              <td className="py-2 pr-2 tabular-nums [overflow-wrap:anywhere] text-muted-foreground">
                                {time(item.receivedAt)}
                              </td>
                              <td className="py-2 text-xs text-primary">
                                已接收
                              </td>
                            </>
                          ) : null}
                          <td
                            className="py-2 pr-2"
                            title={item.model ?? "未知"}
                          >
                            <span className="block max-w-48 truncate">
                              {item.model ?? "未知"}
                            </span>
                          </td>
                          <td className="py-2 [overflow-wrap:anywhere]">
                            {item.reasoningEffort ?? "未知"}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="py-8 text-center text-sm text-muted-foreground">
                  暂无消耗记录
                </p>
              )}
              <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t pt-3 text-xs text-muted-foreground">
                <span>
                  共 {formatTokenCount(ledger.data.totalElements)} 条 · 第{" "}
                  {page + 1} / {Math.max(1, ledger.data.totalPages)} 页
                </span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    aria-label="上一页"
                    disabled={page === 0 || ledger.isFetching}
                    onClick={() => setPage((value) => value - 1)}
                  >
                    <ChevronLeft aria-hidden="true" />
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    aria-label="下一页"
                    disabled={
                      page + 1 >= ledger.data.totalPages || ledger.isFetching
                    }
                    onClick={() => setPage((value) => value + 1)}
                  >
                    下一页
                    <ChevronRight aria-hidden="true" />
                  </Button>
                </div>
              </div>
            </>
          ) : null}
        </div>
      ) : (
        <div
          id={`${id}-diagnostics`}
          {...(legacyView
            ? {
                role: "tabpanel",
                "aria-labelledby": `${id}-diagnostics-tab`,
              }
            : {})}
          className={legacyView ? "pt-4" : "pt-1"}
        >
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,18rem),1fr))] gap-3 text-sm">
            <div className="min-w-0 rounded-lg border p-3">
              <h3 className="font-medium">Rust 常驻统计程序</h3>
              <p className="mt-1 text-xs text-muted-foreground">
                {serviceState
                  ? serviceLabels[serviceState]
                  : statusReadFailed
                    ? "统计状态读取失败"
                    : "正在读取…"}
              </p>
              <dl className="mt-2 grid grid-cols-[repeat(auto-fit,minmax(min(100%,7rem),1fr))] gap-2 text-xs">
                <div>
                  <dt className="text-muted-foreground">运行版本</dt>
                  <dd className="mt-0.5 tabular-nums [overflow-wrap:anywhere]">
                    {runtimeVersion ?? (healthy ? "旧版/未知" : "未运行/未知")}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">已安装版本</dt>
                  <dd className="mt-0.5 tabular-nums [overflow-wrap:anywhere]">
                    {meterVersion.data?.installedVersion ?? "旧版/未知"}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">安装包版本</dt>
                  <dd className="mt-0.5 tabular-nums [overflow-wrap:anywhere]">
                    {meterVersion.data?.bundledVersion ?? "未知"}
                  </dd>
                </div>
              </dl>
              {meterVersion.isError ? (
                <p className="mt-2 text-xs text-amber-700">
                  暂时无法读取统计程序版本，请刷新重试。
                </p>
              ) : meterVersion.data?.status === "UNKNOWN" ? (
                <p className="mt-2 text-xs text-amber-700">
                  无法确认当前版本：程序文件校验信息不足，请检查安装状态。
                </p>
              ) : null}
              <p className="mt-2 text-xs [overflow-wrap:anywhere]">
                {explainError(status?.service.errorCode) ?? "暂无统计程序错误"}
              </p>
              {status?.pairingState === "PAIRING_FAILED" ? (
                <p className="mt-2 text-xs text-destructive [overflow-wrap:anywhere]">
                  设备配对失败，请确认后台在线后重试。
                </p>
              ) : null}
            </div>
            <div className="min-w-0 rounded-lg border p-3">
              <h3 className="font-medium">桌面回退扫描</h3>
              <p className="mt-1 text-xs text-muted-foreground">
                {status
                  ? fallbackLabels[status.fallback.state]
                  : statusReadFailed
                    ? "统计状态读取失败"
                    : "正在读取…"}
              </p>
              <p className="mt-2 text-xs">
                最近扫描：{time(status?.fallback.lastScannedAt)}
              </p>
              {explainError(status?.fallback.errorCode) ? (
                <p className="mt-2 text-xs text-destructive [overflow-wrap:anywhere]">
                  {explainError(status?.fallback.errorCode)}
                </p>
              ) : null}
              {scannerIssues.length ? (
                <div className="mt-3">
                  <p className="text-xs font-medium text-amber-700">
                    扫描诊断（按类型汇总）
                  </p>
                  {fallbackScanCompleted && hasSkippedNonUsageRecords ? (
                    <p className="mt-1 text-xs text-muted-foreground">
                      扫描已完成。超长行和累计计数重置属于已跳过的非用量诊断，不代表网络错误。
                    </p>
                  ) : null}
                  <ul
                    aria-label="扫描诊断汇总"
                    className="cqg-scrollbar-hidden mt-2 max-h-40 min-w-0 divide-y overflow-y-auto rounded-md border px-3"
                    tabIndex={0}
                  >
                    {scannerIssues
                      .slice(0, MAX_VISIBLE_ISSUE_TYPES)
                      .map(([code, count]) => (
                        <li key={code} className="py-2">
                          <p className="break-words text-xs text-amber-800 [overflow-wrap:anywhere]">
                            {scannerIssueLabels[code] ?? "扫描诊断"} · {code} ·{" "}
                            {count} 次
                          </p>
                          <p className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                            {scannerIssueDescriptions[code] ??
                              "仅显示安全诊断码，不包含原始日志内容。"}
                          </p>
                        </li>
                      ))}
                    {scannerIssues.length > MAX_VISIBLE_ISSUE_TYPES ? (
                      <li className="py-2 text-xs text-muted-foreground">
                        另有 {scannerIssues.length - MAX_VISIBLE_ISSUE_TYPES}{" "}
                        种诊断未展开。
                      </li>
                    ) : null}
                  </ul>
                </div>
              ) : null}
            </div>
          </div>
          <h3 className="mb-2 mt-4 text-sm font-medium">
            统计程序日志 · 最近 50 条
          </h3>
          <p className="mb-2 text-xs text-muted-foreground [overflow-wrap:anywhere]">
            仅展示运行摘要。批次 Token
            不是单笔流水，不要重复累加；记录可能因后台去重而不新增入账。
          </p>
          {logs.isPending ? (
            <p className="text-sm text-muted-foreground">
              正在读取统计程序日志…
            </p>
          ) : logs.isError ? (
            <p
              role="alert"
              className="text-sm text-destructive [overflow-wrap:anywhere]"
            >
              日志读取失败：{getApiErrorMessage(logs.error)}
            </p>
          ) : logs.data?.state === "ADMIN_REQUIRED" ? (
            <p className="text-sm text-muted-foreground">
              请以管理员身份运行客户端查看统计程序日志；消耗流水不需要管理员权限。
            </p>
          ) : logs.data?.state === "NOT_AVAILABLE" ? (
            <p className="text-sm text-muted-foreground">
              本机暂无统计程序日志，请先安装并启动统计程序。
            </p>
          ) : logs.data?.state === "READ_FAILED" ? (
            <p role="alert" className="text-sm text-destructive">
              统计程序日志暂不可读，请检查目录权限后刷新。
            </p>
          ) : logs.data?.entries.length ? (
            <ol
              className="cqg-scrollbar-hidden max-h-96 min-w-0 divide-y overflow-y-auto"
              tabIndex={0}
              aria-label="统计程序运行日志"
            >
              {logs.data.entries.map((entry, index) => (
                <li
                  key={`${entry.atEpochSeconds}-${index}`}
                  className="flex min-w-0 flex-wrap items-start justify-between gap-2 py-2 text-sm"
                >
                  <div className="min-w-0 flex-1">
                    <p className="font-medium">{logLabels[entry.kind]}</p>
                    <p className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                      {time(entry.atEpochSeconds)}
                      {entry.state ? ` · ${serviceLabels[entry.state]}` : ""}
                      {entry.ccSwitchGuard
                        ? ` · 守卫：${entry.ccSwitchGuard}`
                        : ""}
                    </p>
                    {entry.errorCode ? (
                      <p className="mt-1 text-xs text-destructive [overflow-wrap:anywhere]">
                        {explainError(entry.errorCode)}
                      </p>
                    ) : null}
                  </div>
                  {entry.kind === "USAGE_REPORTED" ? (
                    <div className="shrink-0 text-right">
                      <Tokens value={entry.reportedTokens} />
                      <p className="mt-1 text-xs text-muted-foreground">
                        {entry.reportedEvents} 条事件 · 上报批次
                      </p>
                    </div>
                  ) : null}
                </li>
              ))}
            </ol>
          ) : (
            <p className="text-sm text-muted-foreground">暂无运行日志</p>
          )}
          {logs.data?.skippedLines ? (
            <p className="mt-3 text-xs text-muted-foreground">
              已跳过 {logs.data.skippedLines} 条不完整或无效日志。
            </p>
          ) : null}
        </div>
      )}
    </section>
  );
}
