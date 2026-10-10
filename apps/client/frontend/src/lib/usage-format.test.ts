import { describe, expect, it } from "vitest";
import type { UsageAnalytics } from "./api";
import { formatUsd, usageTrendPoints } from "./usage-format";

const summary: UsageAnalytics["summary"] = {
  eventCount: 1,
  totalTokens: 42,
  inputTokens: null,
  cachedInputTokens: null,
  cacheWriteInputTokens: null,
  outputTokens: null,
  reasoningOutputTokens: null,
  breakdownEventCount: 0,
  costedEventCount: 0,
  estimatedCostUsd: null,
  cacheHitRatePercent: null,
};

describe("usage display values", () => {
  it("distinguishes unknown, zero and positive sub-micro USD costs", () => {
    expect(formatUsd(null)).toBe("—");
    expect(formatUsd(0)).toBe("0.00");
    expect(formatUsd(0.000000000001)).toBe("< 0.000001");
    expect(formatUsd(0.000001)).toBe("0.000001");
    expect(formatUsd(1234.567891)).toBe("1,234.567891");
  });

  it("fills UTC hourly gaps without turning unknown event costs into zero", () => {
    const points = usageTrendPoints({
      from: "2026-10-10T08:30:00+08:00",
      to: "2026-10-10T11:00:00+08:00",
      currency: "USD",
      timezone: "UTC",
      granularity: "HOUR",
      summary,
      trend: [{ bucket: "2026-10-10T00:00:00Z", stats: summary }],
    });
    expect(points).toEqual([
      {
        bucket: "2026-10-10T00:00:00.000Z",
        label: "10-10 00:00",
        totalTokens: 42,
        estimatedCostUsd: null,
      },
      {
        bucket: "2026-10-10T01:00:00.000Z",
        label: "10-10 01:00",
        totalTokens: 0,
        estimatedCostUsd: 0,
      },
      {
        bucket: "2026-10-10T02:00:00.000Z",
        label: "10-10 02:00",
        totalTokens: 0,
        estimatedCostUsd: 0,
      },
    ]);
  });

  it("buckets daily trends at UTC midnight across a month boundary", () => {
    const points = usageTrendPoints({
      from: "2026-09-30T23:30:00Z",
      to: "2026-10-02T00:00:00Z",
      currency: "USD",
      timezone: "UTC",
      granularity: "DAY",
      summary,
      trend: [
        {
          bucket: "2026-10-01T00:00:00Z",
          stats: { ...summary, estimatedCostUsd: 0.00325 },
        },
      ],
    });
    expect(
      points.map((point) => [point.label, point.estimatedCostUsd]),
    ).toEqual([
      ["09-30", 0],
      ["10-01", 0.00325],
    ]);
  });
});
