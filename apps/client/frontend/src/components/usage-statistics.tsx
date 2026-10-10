import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, getApiErrorMessage } from "@/lib/api";
import { formatCompactTokenCount, formatTokenCount } from "@/lib/utils";
import { formatUsd, usageTrendPoints } from "@/lib/usage-format";
import { Button } from "@/components/ui/button";

export function UsageStatistics({ userId }: { userId: string }) {
  const [days, setDays] = useState<1 | 7 | 30>(7);
  const [unit, setUnit] = useState<"Token" | "USD">("Token");
  const query = useQuery({
    queryKey: ["usage-analytics", userId, days],
    queryFn: () => api.usageAnalytics(days),
    refetchInterval: 10000,
    retry: false,
  });
  const data = query.data;
  const summary = data?.summary;
  const points = useMemo(() => (data ? usageTrendPoints(data) : []), [data]);
  const token = (value: number | null) => (
    <span
      title={
        value === null ? "分项缺失" : `精确值：${formatTokenCount(value)} Token`
      }
    >
      {value === null ? "—" : formatCompactTokenCount(value)}
    </span>
  );
  return (
    <div className="min-w-0 pt-4" aria-label="个人用量统计">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div
          role="group"
          aria-label="统计时间范围"
          className="inline-flex rounded-md bg-muted p-1"
        >
          {([1, 7, 30] as const).map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={days === value}
              className={`rounded px-3 py-1.5 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${days === value ? "bg-background font-medium shadow-sm" : "text-muted-foreground"}`}
              onClick={() => setDays(value)}
            >
              {value === 1 ? "近 24 小时" : `近 ${value} 天`}
            </button>
          ))}
        </div>
        <Button
          size="icon"
          variant="ghost"
          title="刷新用量统计"
          aria-label="刷新用量统计"
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
        >
          <RefreshCw
            className={query.isFetching ? "animate-spin" : undefined}
            aria-hidden="true"
          />
        </Button>
      </div>
      {query.isError ? (
        <div
          role="alert"
          className="mb-4 border-l-2 border-amber-500 pl-3 text-sm"
        >
          <p className="font-medium">用量统计读取失败</p>
          <p className="mt-1 text-xs text-muted-foreground">
            {getApiErrorMessage(query.error)}
            {data ? " 显示上次读取结果。" : ""}
          </p>
        </div>
      ) : null}
      {query.isPending ? (
        <p
          role="status"
          className="py-10 text-center text-sm text-muted-foreground"
        >
          正在读取用量统计…
        </p>
      ) : null}
      {summary ? (
        <>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-5 border-b pb-5 lg:grid-cols-4">
            <div>
              <dt className="text-xs text-muted-foreground">估算费用 (USD)</dt>
              <dd
                className="mt-1 break-words text-lg font-semibold tabular-nums"
                title="当前配置单价 · 非官方账单"
              >
                {formatUsd(summary.estimatedCostUsd)}
              </dd>
              <p className="mt-1 text-xs text-muted-foreground">
                可估价 {summary.costedEventCount} / {summary.eventCount} 条
              </p>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">总 Token</dt>
              <dd className="mt-1 text-lg font-semibold tabular-nums">
                {token(summary.totalTokens)}
              </dd>
              <p className="mt-1 text-xs text-muted-foreground">
                分项完整 {summary.breakdownEventCount} / {summary.eventCount} 条
              </p>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">用量事件</dt>
              <dd className="mt-1 text-lg font-semibold tabular-nums">
                {formatTokenCount(summary.eventCount)}
              </dd>
              <p className="mt-1 text-xs text-muted-foreground">已接收并去重</p>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">缓存命中率</dt>
              <dd className="mt-1 text-lg font-semibold tabular-nums">
                {summary.cacheHitRatePercent === null
                  ? "—"
                  : `${summary.cacheHitRatePercent}%`}
              </dd>
            </div>
          </dl>
          <dl
            className="grid grid-cols-2 gap-4 py-4 sm:grid-cols-5"
            aria-label="Token 分项汇总"
          >
            {(
              [
                ["输入", summary.inputTokens],
                ["缓存读", summary.cachedInputTokens],
                ["缓存写", summary.cacheWriteInputTokens],
                ["输出", summary.outputTokens],
                ["思考", summary.reasoningOutputTokens],
              ] as const
            ).map(([label, value]) => (
              <div key={label}>
                <dt className="text-xs text-muted-foreground">{label} Token</dt>
                <dd className="mt-1 text-sm font-medium tabular-nums">
                  {token(value)}
                </dd>
              </div>
            ))}
          </dl>
          {summary.costedEventCount < summary.eventCount ? (
            <p className="mb-4 text-xs text-amber-700 dark:text-amber-400">
              费用仅包含分项完整且已定价的记录
            </p>
          ) : null}
          <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
            <div>
              <h3 className="text-sm font-medium">用量趋势</h3>
              <p className="mt-1 text-xs text-muted-foreground">
                {data?.granularity === "HOUR" ? "按小时" : "按天"} · UTC
              </p>
            </div>
            <div
              role="group"
              aria-label="趋势单位"
              className="inline-flex rounded-md bg-muted p-1"
            >
              {(["Token", "USD"] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={unit === value}
                  className={`rounded px-3 py-1 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${unit === value ? "bg-background font-medium shadow-sm" : "text-muted-foreground"}`}
                  onClick={() => setUnit(value)}
                >
                  {value}
                </button>
              ))}
            </div>
          </div>
          {summary.eventCount === 0 ? (
            <p className="py-12 text-center text-sm text-muted-foreground">
              当前范围暂无用量
            </p>
          ) : (
            <div
              className="mt-3 h-48 w-full min-w-0"
              role="img"
              aria-label={`${unit} 用量趋势图`}
            >
              <ResponsiveContainer
                width="100%"
                height="100%"
                minWidth={0}
                initialDimension={{ width: 640, height: 192 }}
              >
                <AreaChart
                  data={points}
                  margin={{ top: 8, right: 8, bottom: 4, left: 0 }}
                >
                  <CartesianGrid
                    vertical={false}
                    strokeDasharray="3 3"
                    stroke="var(--cqg-chart-grid, #cbd5e1)"
                  />
                  <XAxis
                    dataKey="label"
                    minTickGap={44}
                    tick={{ fontSize: 11 }}
                  />
                  <YAxis
                    width={68}
                    tick={{ fontSize: 11 }}
                    tickFormatter={(value: number) =>
                      unit === "USD"
                        ? formatUsd(value)
                        : formatCompactTokenCount(value)
                    }
                  />
                  <Tooltip
                    labelFormatter={(_, payload) =>
                      `${payload[0]?.payload.bucket ?? ""} (UTC)`
                    }
                    formatter={(value) => [
                      unit === "USD"
                        ? `USD ${formatUsd(Number(value))}`
                        : `${formatTokenCount(Number(value))} Token`,
                      unit === "USD" ? "估算费用" : "总 Token",
                    ]}
                  />
                  <Area
                    type="monotone"
                    dataKey={
                      unit === "USD" ? "estimatedCostUsd" : "totalTokens"
                    }
                    stroke={unit === "USD" ? "#2563eb" : "#0d9488"}
                    fill={unit === "USD" ? "#dbeafe" : "#ccfbf1"}
                    strokeWidth={2}
                    connectNulls={false}
                    isAnimationActive={false}
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          )}
        </>
      ) : null}
    </div>
  );
}
