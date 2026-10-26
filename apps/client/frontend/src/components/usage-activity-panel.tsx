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
import { MeterMaintenance } from "@/components/meter-maintenance";
import { formatUsd } from "@/lib/usage-format";

export const serviceLabels: Record<UsageStatus["state"], string> = {
  NOT_INSTALLED: "统计程序未安装",
  OFFLINE: "统计程序离线",
  BASELINE_READY: "统计基线已建立",
  UNPAIRED: "统计程序待配对",
  UP_TO_DATE: "统计程序运行中",
  REPORTED: "统计程序运行中",
  ERROR: "统计程序异常",
};
const costLabels = {
  MISSING_PRICE: "未定价",
  MISSING_BREAKDOWN: "分项缺失",
  INVALID_BREAKDOWN: "分项不一致",
  PRICED: "已入账",
  PENDING_BILLING: "待核算",
  MISSING_SERVICE_TIER: "待确认速度模式",
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
    ["速度模式", item.billingModeStatus === 'FAST' ? `快速 ×${item.billingMultiplier}` : item.billingModeStatus === 'STANDARD' ? '普通 ×1' : item.billingOrigin === 'HISTORICAL_RECALC' ? '历史未记录（普通费率回算）' : '未记录（待确认）'],
    ["模式依据", item.billingModeSource === 'REPORTED' ? '日志明确记录' : item.billingModeSource === 'ADMIN_DECLARED' ? '管理员设备设置' : item.billingModeSource === 'ADMIN_CONFIRMED' ? '管理员逐笔确认' : '日志未记录'],
    ["计费来源", item.billingOrigin === 'HISTORICAL_RECALC' ? '历史回算' : item.billingOrigin === 'ADMIN_REVIEW' ? '管理员核对入账' : '实时入账'],
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
    <span className="tabular-nums" title={`USD ${value} · 入账时价格快照`}>
      USD {formatUsd(value)}
    </span>
  );
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
    case "METER_PAIRING_INVALID":
      return "登录或设备授权已失效，请重新登录并配对。";
    case "METER_PAIRING_EXPIRED":
      return "设备授权已失效，可能已过期、被撤销或账户已停用。待传用量已保留，请联系管理员确认状态，允许后再重新登录配对。";
    case "QUOTA_REPORT_REJECTED":
      return "后台拒绝用量上报，请联系管理员检查用户、团队与设备绑定。";
    case "METER_REPORT_REJECTED":
      return "后台拒绝的用量已隔离保留，后续有效用量仍会继续上报。请联系管理员核对被拒记录。";
    case "METER_OUTBOX_READ_FAILED":
      return "待传用量无法读取，请检查统计数据目录的权限并联系管理员；不要删除待传队列。";
    case "METER_OUTBOX_WRITE_FAILED":
      return "待传用量无法保存，请检查统计数据目录权限和磁盘空间；不要删除现有数据，必要时联系管理员。";
    case "METER_OUTBOX_INVALID":
      return "待传队列格式异常，请联系管理员核对并恢复；请保留现有统计数据，不要清空队列。";
    case "METER_OUTBOX_TOO_LARGE":
      return "待传队列已达容量上限，请联系管理员核对未上传和隔离记录；请保留现有统计数据，不要清空队列。";
    case "METER_OUTBOX_IDENTITY_MISMATCH":
      return "待传队列与当前设备、服务器或本地目录不一致，已暂停上传。请检查服务器地址并联系管理员核对，不要删除队列或修改记录归属。";
    case "METER_OUTBOX_OWNER_MISMATCH":
      return "设备配对账户发生变化，旧账户用量已隔离保留，后续有效用量仍会继续上报。请联系管理员核对旧记录归属。";
    case "METER_OUTBOX_OWNER_UNKNOWN":
      return "部分用量无法确认归属，已隔离保留，后续有效用量仍会继续上报。请联系管理员核对，不能直接计入新账户。";
    case "METER_PAIRING_OWNER_MISMATCH":
      return "设备授权账户与待传用量归属不一致，已暂停上传并保留记录。请联系管理员核对账户后重新配对。";
    case "METER_PAIRING_SERVER_MISMATCH":
      return "配对授权与当前服务器不一致，已暂停上传并保留授权和用量记录。请确认服务器地址后重新登录配对。";
    case "METER_RENEWAL_INVALID":
      return "设备续期结果未通过校验，待传用量已保留。请确认后台与统计程序版本匹配，联系管理员核对后重新配对。";
    case "METER_EVENT_OUTSIDE_REPORT_WINDOW":
      return "部分用量超过后台接收时间范围或时间超前，记录已保留，后续有效用量仍会继续上报。请检查系统时间并联系管理员核对历史记录，不要修改记录时间。";
    case "METER_REVIEW_SERVER_UPGRADE_REQUIRED":
      return "中央后台尚不支持用量核对，请升级后台。原始记录仍保留在本机，正常用量继续上报。";
    case "METER_REVIEW_RECEIPT_INVALID":
      return "后台未返回完整核对收件凭据，记录仍保留在本机，统计程序会自动重试。";
    case "METER_REVIEW_UPLOAD_FAILED":
      return "待核对记录暂未成功提交，统计程序会自动重试；正常用量继续上报。";
    case "CONTROL_PLANE_REQUEST_REJECTED":
      return "后台拒绝了用量请求，请检查请求数据；这通常不是网络连接故障。";
    case "METER_LEGACY_DIRECT_LATE":
      return "部分记录与旧累计统计可能重叠，已保留待核对，未重复计费。";
    case "METER_CAPTURE_THREAD_PANICKED":
      return "统计程序的会话采集任务异常退出，请检查 Windows 服务状态；如持续发生，请重新运行客户端安装器。";
    case "METER_CAPTURE_THREAD_STOPPED":
      return "统计程序的会话采集任务已停止，请刷新状态并检查 Windows 服务。";
    case "STATE_STORAGE_FAILED":
    case "METER_STATE_READ_FAILED":
    case "METER_STATE_WRITE_FAILED":
      return "扫描游标无法保存，请检查客户端数据目录的写入权限。";
    case "METER_STATUS_WRITE_FAILED":
      return "服务状态无法写入，请检查当前 Windows 用户的数据目录权限。";
    case "METER_LOG_WRITE_FAILED":
      return "服务日志无法写入，请检查 ProgramData 目录权限和磁盘空间。";
    case "METER_CC_SWITCH_GUARD_FAILED":
      return "无法核实客户端冲突检查状态，请检查统计程序后重试。";
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
}: {
  userId: string;
  view?: "overview" | "ledger" | "diagnostics";
  status: UsageStatus | undefined;
  statusError?: unknown;
  measuredAt?: string | null;
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
  const pairingError = useQuery({
    queryKey: ["meter-pairing-error", userId],
    queryFn: api.meterPairingError,
    retry: false,
    enabled: legacyView || activeView === "diagnostics",
    refetchInterval: 10_000,
  });
  const serviceState = status?.state;
  const healthy = serviceState === "UP_TO_DATE" || serviceState === "REPORTED";
  const upgradeRequired =
    meterVersion.data?.status === "UPGRADE_REQUIRED" ||
    meterVersion.data?.needsUpgrade === true;
  const runtimeVersion = status?.version;
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
  const sameVersionDifferentBinary =
    upgradeRequired &&
    !!meterVersion.data?.installedVersion &&
    meterVersion.data.installedVersion === meterVersion.data.bundledVersion;
  const lastReport = status?.lastReportEpochSeconds;
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
              className={`text-xs font-medium ${healthy && !versionNeedsAttention ? "text-primary" : "text-amber-700 dark:text-amber-400"}`}
            >
              {upgradeRequired
                ? "需升级"
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
                请重新运行客户端安装器以更新统计程序。已入库的旧流水不会自动补齐。
              </p>
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
                      : "后台尚未收到运行版本，可能仍在运行旧版统计程序。请重新运行客户端安装器完成检查。"}
              </p>
            </div>
          ) : null}
          <dl className="mt-4 grid grid-cols-[repeat(auto-fit,minmax(min(100%,12rem),1fr))] gap-3 rounded-lg border bg-muted/40 p-3 text-sm">
            <div>
              <dt className="text-xs text-muted-foreground">
                统计程序最近扫描
              </dt>
              <dd className="mt-1 tabular-nums">
                {time(status?.lastScanEpochSeconds)}
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
                {serviceState === "NOT_INSTALLED" ? (
                  <p className="self-center text-xs text-muted-foreground">
                    请重新运行客户端安装器安装统计程序。
                  </p>
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
                {explainError(status?.errorCode) ?? "暂无统计程序错误"}
              </p>
              {status?.warningCode ? (
                <div role="status" className="mt-2 text-xs text-amber-700 dark:text-amber-400 [overflow-wrap:anywhere]">
                  <p>统计服务持续运行，待核对记录：{formatTokenCount(status.deferredEvents ?? 0)} 条。</p>
                  <p className="mt-1">{explainError(status.warningCode)}</p>
                </div>
              ) : null}
              {(status?.reviewSubmittedEvents ?? 0) > 0 ? <p role="status" className="mt-2 text-xs text-muted-foreground">
                已提交后台核对：{formatTokenCount(status!.reviewSubmittedEvents!)} 条（本设备累计收件数，非未处理数量）。请在后台“用量核对”查看、批量归档或按原始时间入账；收件不会自动计费。
              </p> : null}
              {status?.reviewErrorCode ? <p className="mt-2 text-xs text-amber-700 dark:text-amber-400">{explainError(status.reviewErrorCode)}</p> : null}
              {serviceState === "UNPAIRED" || pairingError.data ? (
                <p className="mt-2 text-xs text-destructive [overflow-wrap:anywhere]">
                  {pairingError.data === "PAIRING_IDENTITY_MISMATCH"
                    ? "中央后台返回的设备或账户与本机不匹配，请联系管理员核对设备登记。"
                    : pairingError.data === "PAIRING_ENROLL_FAILED"
                      ? "向中央后台登记统计设备暂未成功；客户端会在后续登录状态核验时重试。请检查服务器地址与网络。"
                      : "统计程序尚未收到本设备配对信息，请确认服务器连接；客户端会在后续登录状态核验时重试。"}
                </p>
              ) : null}
              <MeterMaintenance />
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
