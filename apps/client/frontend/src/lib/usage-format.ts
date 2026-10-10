import type { UsageAnalytics } from "@/lib/api";

const usd = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 6,
});

export function formatUsd(value: number | null) {
  if (value === null) return "—";
  return value > 0 && value < 0.000001 ? "< 0.000001" : usd.format(value);
}

export function usageTrendPoints(data: UsageAnalytics) {
  const step = data.granularity === "HOUR" ? 3600000 : 86400000;
  const buckets = new Map(
    data.trend.map((item) => [Date.parse(item.bucket), item.stats]),
  );
  const points = [];
  for (
    let at = Math.floor(Date.parse(data.from) / step) * step;
    at < Date.parse(data.to);
    at += step
  ) {
    const bucket = new Date(at).toISOString();
    const stats = buckets.get(at);
    points.push({
      bucket,
      label:
        data.granularity === "HOUR"
          ? `${bucket.slice(5, 10)} ${bucket.slice(11, 16)}`
          : bucket.slice(5, 10),
      totalTokens: stats?.totalTokens ?? 0,
      estimatedCostUsd: stats ? stats.estimatedCostUsd : 0,
    });
  }
  return points;
}
