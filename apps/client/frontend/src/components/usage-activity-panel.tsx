import { useEffect, useId, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, ListChecks, RefreshCw } from "lucide-react";
import {
  api,
  getApiErrorMessage,
  type MeterRuntimeLogs,
  type UsageStatus,
} from "@/lib/api";
import { formatCompactTokenCount, formatTokenCount } from "@/lib/utils";
import { Button } from "@/components/ui/button";

const serviceLabels: Record<UsageStatus["service"]["state"], string> = {
  NOT_INSTALLED: "计费服务未安装",
  OFFLINE: "计费服务离线",
  BASELINE_READY: "服务基线已建立",
  UNPAIRED: "计费服务待配对",
  UP_TO_DATE: "计费服务运行中",
  REPORTED: "计费服务运行中",
  ERROR: "计费服务异常",
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
  SERVICE_STARTED: "计费服务启动",
  SERVICE_STOPPED: "计费服务停止",
  BASELINE_READY: "扫描基线建立",
  UNPAIRED: "等待设备配对",
  USAGE_REPORTED: "用量上报成功",
  HEARTBEAT: "后台心跳成功",
  ERROR: "服务处理失败",
  STATUS_CHANGE: "服务状态变化",
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
  status,
  measuredAt,
  scanning,
  pairing,
  starting,
  onScan,
  onPair,
  onStart,
}: {
  userId: string;
  status: UsageStatus | undefined;
  measuredAt?: string | null;
  scanning: boolean;
  pairing: boolean;
  starting: boolean;
  onScan: () => void;
  onPair: () => void;
  onStart: () => void;
}) {
  const [tab, setTab] = useState<"ledger" | "diagnostics">("ledger");
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
    enabled: tab === "ledger",
    refetchInterval: 10000,
  });
  const logs = useQuery({
    queryKey: ["meter-runtime-logs", userId],
    queryFn: api.meterRuntimeLogs,
    retry: false,
    enabled: tab === "diagnostics",
    refetchInterval: 10000,
  });
  const serviceState = status?.service.state;
  const healthy = serviceState === "UP_TO_DATE" || serviceState === "REPORTED";
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
    void queryClient.invalidateQueries({ queryKey: ["usage-status"] });
    void (tab === "ledger" ? ledger.refetch() : logs.refetch());
  };
  const fetching = tab === "ledger" ? ledger.isFetching : logs.isFetching;

  return (
    <section
      className="cqg-surface min-w-0 p-5"
      aria-labelledby={`${id}-heading`}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <ListChecks
            className="mt-0.5 size-5 text-teal-700 dark:text-teal-400"
            aria-hidden="true"
          />
          <div>
            <h2 id={`${id}-heading`} className="text-base font-semibold">
              计费与消耗记录
            </h2>
            <p className="mt-1 text-xs text-muted-foreground">
              {serviceState ? serviceLabels[serviceState] : "正在检查计费服务…"}{" "}
              · 每 10 秒更新记录
            </p>
          </div>
        </div>
        <span
          className={`text-xs font-medium ${healthy && !fallbackFailed ? "text-teal-700 dark:text-teal-400" : "text-amber-700 dark:text-amber-400"}`}
        >
          {fallbackFailed ? "回退上报异常" : healthy ? "正常" : "需检查"}
        </span>
      </div>
      <dl className="mt-5 grid gap-3 rounded-lg border bg-slate-50/70 p-4 text-sm dark:bg-slate-900/50 sm:grid-cols-3">
        <div>
          <dt className="text-xs text-muted-foreground">服务最近扫描</dt>
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
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-b pb-3">
        <div
          className="inline-flex gap-1 rounded-lg bg-muted p-1"
          role="tablist"
          aria-label="计费记录视图"
        >
          {(["ledger", "diagnostics"] as const).map((value, index) => (
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
              className={`rounded-md px-3 py-1.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${tab === value ? "bg-background font-medium shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
              onClick={() => setTab(value)}
              onKeyDown={(event) => {
                if (
                  ["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)
                ) {
                  event.preventDefault();
                  const next =
                    event.key === "Home"
                      ? 0
                      : event.key === "End"
                        ? 1
                        : 1 - index;
                  setTab(next === 0 ? "ledger" : "diagnostics");
                  tabs.current[next]?.focus();
                }
              }}
            >
              {value === "ledger" ? "消耗流水" : "运行诊断"}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            variant="ghost"
            onClick={refresh}
            disabled={fetching}
            aria-label="刷新计费记录"
          >
            <RefreshCw
              className={fetching ? "animate-spin" : undefined}
              aria-hidden="true"
            />
            刷新记录
          </Button>
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
          {serviceState === "OFFLINE" || serviceState === "NOT_INSTALLED" ? (
            <Button
              size="sm"
              variant="outline"
              disabled={starting}
              onClick={onStart}
            >
              {starting
                ? "正在处理…"
                : serviceState === "NOT_INSTALLED"
                  ? "安装计费服务"
                  : "启动计费服务"}
            </Button>
          ) : null}
        </div>
      </div>
      {tab === "ledger" ? (
        <div
          id={`${id}-ledger`}
          role="tabpanel"
          aria-labelledby={`${id}-ledger-tab`}
          className="pt-4"
        >
          <p className="mb-3 text-xs text-muted-foreground">
            当前系统用户的已接收用量；未上报或上报失败的记录不会显示为已入账。
          </p>
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
                <div className="min-w-0 overflow-x-auto">
                  <table
                    className="w-full min-w-[960px] table-fixed whitespace-nowrap text-left text-sm"
                    aria-label="个人消耗流水"
                  >
                    <thead className="border-b text-xs text-muted-foreground">
                      <tr>
                        <th className="pb-3 pr-5 font-medium" scope="col">
                          消耗时间
                        </th>
                        <th className="pb-3 pr-5 font-medium" scope="col">
                          Token 消耗
                        </th>
                        <th className="pb-3 pr-5 font-medium" scope="col">
                          来源
                        </th>
                        <th className="pb-3 pr-5 font-medium" scope="col">
                          后台接收
                        </th>
                        <th className="pb-3 font-medium" scope="col">
                          状态
                        </th>
                        <th className="pb-3 pr-5 font-medium" scope="col">
                          模型名称
                        </th>
                        <th className="pb-3 font-medium" scope="col">
                          思考等级
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {ledger.data.items.map((item) => (
                        <tr key={item.id} className="border-b last:border-0">
                          <td className="py-3 pr-5 tabular-nums">
                            {time(item.occurredAt)}
                          </td>
                          <td className="py-3 pr-5">
                            <Tokens value={item.totalTokens} />
                          </td>
                          <td className="py-3 pr-5 text-xs text-muted-foreground">
                            {item.source === "TOKEN_USAGE_RECORD"
                              ? "官方用量记录"
                              : "累计差值补录"}
                          </td>
                          <td className="py-3 pr-5 tabular-nums text-muted-foreground">
                            {time(item.receivedAt)}
                          </td>
                          <td className="py-3 text-xs text-teal-700 dark:text-teal-400">
                            已接收
                          </td>
                          <td
                            className="py-3 pr-5"
                            title={item.model ?? "未知"}
                          >
                            <span className="block max-w-48 truncate">
                              {item.model ?? "未知"}
                            </span>
                          </td>
                          <td className="py-3">
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
          role="tabpanel"
          aria-labelledby={`${id}-diagnostics-tab`}
          className="pt-4"
        >
          <div className="grid gap-3 text-sm sm:grid-cols-2">
            <div className="rounded-lg border p-3">
              <h3 className="font-medium">Rust 常驻服务</h3>
              <p className="mt-1 text-xs text-muted-foreground">
                {serviceState ? serviceLabels[serviceState] : "正在读取…"}
              </p>
              <p className="mt-2 text-xs">
                {explainError(status?.service.errorCode) ?? "暂无服务错误"}
              </p>
              {status?.pairingState === "PAIRING_FAILED" ? (
                <p className="mt-2 text-xs text-destructive">
                  设备配对失败，请确认后台在线后重试。
                </p>
              ) : null}
            </div>
            <div className="rounded-lg border p-3">
              <h3 className="font-medium">桌面回退扫描</h3>
              <p className="mt-1 text-xs text-muted-foreground">
                {status ? fallbackLabels[status.fallback.state] : "正在读取…"}
              </p>
              <p className="mt-2 text-xs">
                最近扫描：{time(status?.fallback.lastScannedAt)}
              </p>
              {explainError(status?.fallback.errorCode) ? (
                <p className="mt-2 text-xs text-destructive">
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
                    className="mt-2 max-h-40 divide-y overflow-y-auto rounded-md border px-3"
                  >
                    {scannerIssues
                      .slice(0, MAX_VISIBLE_ISSUE_TYPES)
                      .map(([code, count]) => (
                        <li key={code} className="py-2">
                          <p className="break-words text-xs text-amber-800">
                            {scannerIssueLabels[code] ?? "扫描诊断"} · {code} ·{" "}
                            {count} 次
                          </p>
                          <p className="mt-1 text-xs text-muted-foreground">
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
          <h3 className="mb-3 mt-5 text-sm font-medium">
            服务日志 · 最近 50 条
          </h3>
          <p className="mb-3 text-xs text-muted-foreground">
            仅展示运行摘要。批次 Token
            不是单笔流水，不要重复累加；记录可能因后台去重而不新增入账。
          </p>
          {logs.isPending ? (
            <p className="text-sm text-muted-foreground">正在读取服务日志…</p>
          ) : logs.isError ? (
            <p role="alert" className="text-sm text-destructive">
              日志读取失败：{getApiErrorMessage(logs.error)}
            </p>
          ) : logs.data?.state === "ADMIN_REQUIRED" ? (
            <p className="text-sm text-muted-foreground">
              请以管理员身份运行客户端查看服务日志；消耗流水不需要管理员权限。
            </p>
          ) : logs.data?.state === "NOT_AVAILABLE" ? (
            <p className="text-sm text-muted-foreground">
              本机暂无服务日志，请先安装并启动计费服务。
            </p>
          ) : logs.data?.state === "READ_FAILED" ? (
            <p role="alert" className="text-sm text-destructive">
              服务日志暂不可读，请检查目录权限后刷新。
            </p>
          ) : logs.data?.entries.length ? (
            <ol className="max-h-96 overflow-y-auto divide-y">
              {logs.data.entries.map((entry, index) => (
                <li
                  key={`${entry.atEpochSeconds}-${index}`}
                  className="flex flex-wrap items-start justify-between gap-2 py-3 text-sm"
                >
                  <div>
                    <p className="font-medium">{logLabels[entry.kind]}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {time(entry.atEpochSeconds)}
                      {entry.state ? ` · ${serviceLabels[entry.state]}` : ""}
                    </p>
                    {entry.errorCode ? (
                      <p className="mt-1 text-xs text-destructive">
                        {explainError(entry.errorCode)}
                      </p>
                    ) : null}
                  </div>
                  {entry.kind === "USAGE_REPORTED" ? (
                    <div className="text-right">
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
