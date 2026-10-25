import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, type UsageAnalytics } from "@/lib/api";
import { ApiHttpHarness } from "@/test/api-http-harness";
import { UsageStatistics } from "./usage-statistics";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
vi.mock("recharts", () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
  AreaChart: () => <div />,
  Area: () => null,
  CartesianGrid: () => null,
  Tooltip: () => null,
  XAxis: () => null,
  YAxis: () => null,
}));
const http = new ApiHttpHarness();
const summary: UsageAnalytics["summary"] = {
  eventCount: 2,
  totalTokens: 1200,
  inputTokens: 1000,
  cachedInputTokens: 300,
  cacheWriteInputTokens: 100,
  outputTokens: 200,
  reasoningOutputTokens: 150,
  breakdownEventCount: 1,
  costedEventCount: 1,
  estimatedCostUsd: 0.00325,
  cacheHitRatePercent: 30,
};
const data: UsageAnalytics = {
  from: "2026-10-03T00:00:00Z",
  to: "2026-10-10T00:00:00Z",
  currency: "USD",
  timezone: "UTC",
  granularity: "DAY",
  summary,
  trend: [{ bucket: "2026-10-09T00:00:00Z", stats: summary }],
};

function view(client: QueryClient, userId = "user-a") {
  return (
    <QueryClientProvider client={client}>
      <UsageStatistics userId={userId} />
    </QueryClientProvider>
  );
}
const queryClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });

describe("personal usage statistics", () => {
  beforeEach(async () => {
    await http.prepare(api);
    http.respond(data);
  });

  it("shows USD coverage and token components and switches trend units and ranges", async () => {
    render(view(queryClient()));
    expect(await screen.findByText("0.00325")).toBeVisible();
    expect(screen.getByText("估算费用 (USD)")).toBeVisible();
    expect(screen.getByText("估算费用 (USD)").closest("dl")).toHaveClass(
      "grid-cols-[repeat(auto-fit,minmax(min(100%,15rem),1fr))]",
    );
    expect(screen.getByLabelText("Token 分项汇总")).toHaveClass(
      "grid-cols-[repeat(auto-fit,minmax(min(100%,7rem),1fr))]",
    );
    expect(screen.getByText("可估价 1 / 2 条")).toBeVisible();
    expect(screen.getByText("费用仅包含分项完整且已定价的记录")).toBeVisible();
    for (const value of [1200, 1000, 300, 100, 200, 150]) {
      expect(
        screen.getByTitle(`精确值：${value.toLocaleString("en-US")} Token`),
      ).toBeVisible();
    }
    await userEvent.click(screen.getByRole("button", { name: "USD" }));
    expect(screen.getByRole("img", { name: "USD 用量趋势图" })).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "近 24 小时" }));
    await waitFor(() =>
      expect(
        http.requestsFor("GET", "/api/v1/client/usage/analytics").at(-1)
          ?.params,
      ).toEqual({ days: 1 }),
    );
    await userEvent.click(screen.getByRole("button", { name: "近 30 天" }));
    await waitFor(() =>
      expect(
        http.requestsFor("GET", "/api/v1/client/usage/analytics").at(-1)
          ?.params,
      ).toEqual({ days: 30 }),
    );
  });

  it("does not display the previous user's costs while the next user is loading", async () => {
    const client = queryClient();
    const { rerender } = render(view(client));
    await screen.findByText("0.00325");
    let resolveNext!: (value: UsageAnalytics) => void;
    http.route(
      "GET",
      "/api/v1/client/usage/analytics",
      () =>
        new Promise((resolve) => {
          resolveNext = (value) => resolve({ data: value });
        }),
    );
    rerender(view(client, "user-b"));
    expect(screen.queryByText("0.00325")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("正在读取用量统计");
    await waitFor(() => expect(resolveNext).toBeDefined());
    resolveNext({ ...data, summary: { ...summary, estimatedCostUsd: 0 } });
    expect(await screen.findByText("0.00")).toBeVisible();
    expect(screen.queryByText("0.00325")).not.toBeInTheDocument();
  });

  it("distinguishes failed retrieval from an empty usage range and supports refresh", async () => {
    http.route("GET", "/api/v1/client/usage/analytics", () => {
      throw new Error("synthetic transport failure");
    });
    render(view(queryClient()));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "用量统计读取失败",
    );
    expect(screen.queryByText("当前范围暂无用量")).not.toBeInTheDocument();
    http.respond({
      ...data,
      trend: [],
      summary: {
        ...summary,
        eventCount: 0,
        totalTokens: 0,
        breakdownEventCount: 0,
        costedEventCount: 0,
        estimatedCostUsd: null,
      },
    });
    http.route("GET", "/api/v1/client/usage/analytics", () => ({
      data: http.centralResponse,
    }));
    await userEvent.click(screen.getByRole("button", { name: "刷新用量统计" }));
    expect(await screen.findByText("当前范围暂无用量")).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText("0.00")).not.toBeInTheDocument();
  });
});
