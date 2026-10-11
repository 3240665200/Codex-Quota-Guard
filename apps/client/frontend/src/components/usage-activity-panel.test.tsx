import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { invoke } from "@tauri-apps/api/core";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { UsageActivityPanel } from "./usage-activity-panel";
import type { UsageStatus } from "@/lib/api";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const invokeMock = vi.mocked(invoke);
const status = {
  fallback: {
    state: "REPORT_FAILED",
    lastScannedAt: null,
    lastReportedAt: null,
    lastReportedEvents: 0,
    issues: [],
    errorCode: "CONTROL_PLANE_UNAVAILABLE",
  },
  service: {
    state: "REPORTED",
    lastScanEpochSeconds: 1791504000,
    lastReportEpochSeconds: 1791504000,
    reportedEvents: 2,
    version: "0.1.1",
    errorCode: null,
  },
  pairingState: "PAIRING_SENT",
} as const;
function view(
  userId = "user-a",
  override?: UsageStatus,
  onStart: () => void = vi.fn(),
) {
  return (
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <UsageActivityPanel
        userId={userId}
        status={
          override ?? {
            ...status,
            fallback: { ...status.fallback, issues: [] },
          }
        }
        scanning={false}
        pairing={false}
        starting={false}
        onScan={vi.fn()}
        onPair={vi.fn()}
        onStart={onStart}
      />
    </QueryClientProvider>
  );
}
const ledger = {
  items: [
    {
      id: "a".repeat(64),
      occurredAt: "2026-10-09T00:00:00Z",
      receivedAt: "2026-10-09T00:00:01Z",
      totalTokens: 12500,
      source: "TOKEN_USAGE_RECORD",
    },
  ],
  page: 0,
  size: 10,
  totalElements: 11,
  totalPages: 2,
};
describe("UsageActivityPanel", () => {
  beforeEach(() => {
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command) => {
      if (command === "meter_service_version")
        return {
          installedVersion: "0.1.1",
          bundledVersion: "0.1.1",
          needsUpgrade: false,
          status: "CURRENT",
        };
      if (command === "usage_events") return ledger;
      if (command === "usage_analytics")
        return {
          from: "2026-10-03T00:00:00Z",
          to: "2026-10-10T00:00:00Z",
          currency: "USD",
          timezone: "UTC",
          granularity: "DAY",
          trend: [],
          summary: {
            eventCount: 0,
            totalTokens: 0,
            inputTokens: null,
            cachedInputTokens: null,
            cacheWriteInputTokens: null,
            outputTokens: null,
            reasoningOutputTokens: null,
            breakdownEventCount: 0,
            costedEventCount: 0,
            estimatedCostUsd: null,
            cacheHitRatePercent: null,
          },
        };
      if (command === "meter_runtime_logs")
        return {
          state: "READY",
          skippedLines: 0,
          entries: [
            {
              atEpochSeconds: 1791504000,
              kind: "USAGE_REPORTED",
              state: "REPORTED",
              reportedEvents: 2,
              reportedTokens: 12500,
              heartbeatSent: false,
              errorCode: null,
              ccSwitchGuard: null,
            },
          ],
        };
      throw new Error("Unexpected command");
    });
  });

  it("summarizes repeated scanner diagnostics instead of rendering an unbounded wall", async () => {
    render(
      view("user-a", {
        ...status,
        fallback: {
          ...status.fallback,
          issues: [
            ...Array(500).fill("OVERSIZED_JSONL_RECORD"),
            ...Array(12).fill("CUMULATIVE_COUNTER_RESET"),
          ],
        },
      }),
    );
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(screen.getByText(/超大日志记录.*500/)).toBeVisible();
    expect(screen.getByText(/累计计数重置.*12/)).toBeVisible();
    expect(
      screen.queryByText(/OVERSIZED_JSONL_RECORD、OVERSIZED_JSONL_RECORD/),
    ).not.toBeInTheDocument();
  });

  it("shows CC Switch guard actions in the runtime log", async () => {
    const original = invokeMock.getMockImplementation();
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "meter_runtime_logs") {
        return {
          state: "READY",
          skippedLines: 0,
          entries: [
            {
              atEpochSeconds: 1791504000,
              kind: "GUARD_TERMINATED",
              state: null,
              reportedEvents: 0,
              reportedTokens: 0,
              heartbeatSent: false,
              errorCode: null,
              ccSwitchGuard: "TERMINATED",
            },
          ],
        };
      }
      return original?.(command, args);
    });
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(await screen.findByText("已结束 CC Switch")).toBeVisible();
    expect(screen.getByText(/守卫：TERMINATED/)).toBeVisible();
  });

  it("warns when the installed statistics program is older than the bundled version", async () => {
    const onStart = vi.fn();
    invokeMock.mockImplementation(async (command) => {
      if (command === "meter_service_version")
        return {
          installedVersion: "0.1.0",
          bundledVersion: "0.1.1",
          needsUpgrade: true,
          status: "UPGRADE_REQUIRED",
        };
      throw new Error("Unexpected command");
    });
    render(view("user-a", undefined, onStart));
    const warning = (await screen.findByText("统计程序需要更新")).closest(
      '[role="alert"]',
    );
    expect(warning).toHaveTextContent("运行版本 0.1.1");
    expect(warning).toHaveTextContent("已安装版本 0.1.0");
    expect(warning).toHaveTextContent("安装包版本 0.1.1");
    await userEvent.click(screen.getByRole("button", { name: "更新统计程序" }));
    expect(onStart).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(screen.getByText("0.1.0")).toBeVisible();
    expect(screen.getAllByText("0.1.1")).toHaveLength(2);
  });

  it("does not treat an unknown installed version as current", async () => {
    const onStart = vi.fn();
    invokeMock.mockImplementation(async (command) => {
      if (command === "meter_service_version")
        return {
          installedVersion: null,
          bundledVersion: "0.1.1",
          needsUpgrade: null,
          status: "UNKNOWN",
        };
      throw new Error("Unexpected command");
    });
    render(view("user-a", undefined, onStart));
    expect(await screen.findByText("统计程序运行版本待确认")).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "检查并修复" }));
    expect(onStart).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(await screen.findByText(/无法确认当前版本/)).toBeVisible();
    expect(screen.queryByText("统计程序需要更新")).not.toBeInTheDocument();
  });

  it("requests an update when hashes differ even if version numbers match", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "meter_service_version")
        return {
          installedVersion: "0.1.1",
          bundledVersion: "0.1.1",
          needsUpgrade: true,
          status: "UPGRADE_REQUIRED",
        };
      throw new Error("Unexpected command");
    });
    render(view());
    const warning = (await screen.findByText("统计程序需要更新")).closest(
      '[role="alert"]',
    );
    expect(warning).toHaveTextContent("版本号相同，但程序文件校验不一致");
    expect(screen.getByText("需升级")).toBeVisible();
  });

  it("does not mark a healthy-looking old process as current without its runtime version", async () => {
    render(
      view("user-a", {
        ...status,
        fallback: { ...status.fallback, issues: [] },
        service: { ...status.service, version: null },
      }),
    );
    expect(await screen.findByText("统计程序运行版本待确认")).toBeVisible();
    expect(screen.getByText(/后台尚未收到运行版本/)).toBeVisible();
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(screen.getByText("旧版/未知")).toBeVisible();
  });

  it("detects an old running process even when the installed file is current", async () => {
    render(
      view("user-a", {
        ...status,
        fallback: { ...status.fallback, issues: [] },
        service: { ...status.service, version: "0.1.0" },
      }),
    );
    expect(await screen.findByText("统计程序运行版本待确认")).toBeVisible();
    expect(screen.getByText(/运行版本 0.1.0 与已安装版本 0.1.1/)).toBeVisible();
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(screen.getByText("0.1.0")).toBeVisible();
    expect(screen.getAllByText("0.1.1")).toHaveLength(2);
  });

  it("explains a rejected usage request without reporting a network outage", async () => {
    render(
      view("user-a", {
        ...status,
        fallback: {
          ...status.fallback,
          issues: [],
          errorCode: "CONTROL_PLANE_REQUEST_REJECTED",
        },
      }),
    );
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));

    expect(screen.getByText(/后台拒绝了用量请求.*检查请求数据/)).toBeVisible();
    expect(screen.queryByText(/后台不可达/)).not.toBeInTheDocument();
  });

  it("offers pairing recovery for an expired Rust device authorization", async () => {
    render(
      view("user-a", {
        ...status,
        fallback: { ...status.fallback, issues: [] },
        service: {
          ...status.service,
          state: "ERROR",
          errorCode: "METER_PAIRING_EXPIRED",
        },
      }),
    );
    expect(screen.getByRole("button", { name: "重新配对" })).toBeVisible();
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(screen.getByText(/设备授权已失效/)).toBeVisible();
  });
  it("shows accepted token rows and uses the service report time instead of the empty fallback", async () => {
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    expect(
      await screen.findByRole("table", { name: "个人消耗流水" }),
    ).toBeVisible();
    expect(screen.getByTitle("精确值：12,500 Token")).toBeVisible();
    expect(screen.getByText("官方用量记录")).toBeVisible();
    expect(screen.getByText("已接收")).toBeVisible();
    expect(screen.queryByText("暂无上报记录")).not.toBeInTheDocument();
    expect(screen.getByText("回退上报异常")).toBeVisible();
    expect(invokeMock).toHaveBeenCalledWith("usage_events", {
      page: 0,
      size: 10,
    });
  });
  it("keeps model metadata visible while placing all token details in one column", async () => {
    const longModel = `m${"x".repeat(95)}`;
    invokeMock.mockImplementation(async (command) => {
      if (command === "usage_events")
        return {
          ...ledger,
          items: [
            {
              ...ledger.items[0],
              model: longModel,
              reasoningEffort: "high",
            },
            {
              ...ledger.items[0],
              id: "b".repeat(64),
              model: null,
              reasoningEffort: null,
            },
          ],
        };
      if (command === "meter_runtime_logs")
        return { state: "READY", skippedLines: 0, entries: [] };
      throw new Error("Unexpected command");
    });

    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    const table = await screen.findByRole("table", { name: "个人消耗流水" });
    const headers = within(table).getAllByRole("columnheader");
    expect(headers.map((header) => header.textContent)).toEqual([
      "消耗时间",
      "Token 消耗",
      "来源",
      "后台接收",
      "状态",
      "模型名称",
      "思考等级",
    ]);
    expect(
      headers.every((header) => header.getAttribute("scope") === "col"),
    ).toBe(true);
    expect(table).toHaveClass("min-w-[1100px]");
    expect(table.parentElement).toHaveClass("min-w-0", "overflow-x-auto");
    expect(table.closest("section")).toHaveClass("min-w-0");

    const rows = within(table).getAllByRole("row");
    expect(within(rows[1]).getByTitle(longModel)).toHaveTextContent(longModel);
    expect(within(rows[1]).getByText("high")).toBeVisible();
    const cells = within(rows[2]).getAllByRole("cell");
    expect(cells).toHaveLength(7);
    expect(cells[5]).toHaveTextContent("未知");
    expect(cells[6]).toHaveTextContent("未知");
  });
  it("shows token components and USD estimates without replacing total consumption", async () => {
    invokeMock.mockResolvedValue({
      ...ledger,
      items: [
        {
          ...ledger.items[0],
          model: "test-model",
          reasoningEffort: "high",
          inputTokens: 10000,
          cachedInputTokens: 3000,
          cacheWriteInputTokens: 0,
          outputTokens: 2500,
          reasoningOutputTokens: 1800,
          estimatedCostUsd: 0.0355,
          costStatus: "PRICED",
        },
      ],
    });
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    const table = await screen.findByRole("table", { name: "个人消耗流水" });
    const tokenCell = within(table).getAllByRole("cell")[1];
    for (const label of [
      "总消耗",
      "输入（含缓存）",
      "输出（含思考）",
      "缓存读",
      "缓存写",
      "思考",
      "费用估算",
    ]) {
      expect(within(tokenCell).getByText(label)).toBeVisible();
    }
    for (const tokens of [12500, 10000, 3000, 0, 2500, 1800]) {
      expect(
        within(tokenCell).getByTitle(
          `精确值：${tokens.toLocaleString("en-US")} Token`,
        ),
      ).toBeVisible();
    }
    expect(within(tokenCell).getByText("USD 0.0355")).toBeVisible();
  });
  it("does not present missing historical breakdowns as zero", async () => {
    invokeMock.mockResolvedValue({
      ...ledger,
      items: [
        {
          ...ledger.items[0],
          inputTokens: null,
          cachedInputTokens: null,
          cacheWriteInputTokens: 0,
          outputTokens: null,
          reasoningOutputTokens: null,
          estimatedCostUsd: null,
          costStatus: "MISSING_BREAKDOWN",
        },
      ],
    });
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    const table = await screen.findByRole("table", { name: "个人消耗流水" });
    const tokenCell = within(table).getAllByRole("cell")[1];
    expect(within(tokenCell).getAllByText("未知")).toHaveLength(4);
    expect(within(tokenCell).getByTitle("精确值：0 Token")).toBeVisible();
    expect(within(tokenCell).getByText("分项缺失")).toBeVisible();
  });
  it("pages through the authenticated ledger", async () => {
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    await screen.findByRole("table", { name: "个人消耗流水" });
    await userEvent.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith("usage_events", {
        page: 1,
        size: 10,
      }),
    );
  });
  it("distinguishes unreachable ledger from zero usage", async () => {
    invokeMock.mockRejectedValue(
      "中央后台暂不可用，请确认后台已启动且服务器地址正确。",
    );
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    expect(await screen.findByText("消耗流水读取失败")).toBeVisible();
    expect(screen.queryByText("暂无消耗记录")).not.toBeInTheDocument();
  });
  it("shows safe batch logs and a useful fallback error explanation", async () => {
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(await screen.findByText("用量上报成功")).toBeVisible();
    expect(screen.getByText(/后台不可达/)).toBeVisible();
    expect(screen.getByText(/2 条/)).toBeVisible();
    expect(screen.getByTitle("精确值：12,500 Token")).toBeVisible();
  });
  it("explains log permission restrictions without weakening the service ACL", async () => {
    invokeMock.mockResolvedValue({
      state: "ADMIN_REQUIRED",
      skippedLines: 0,
      entries: [],
    });
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "运行诊断" }));
    expect(
      await screen.findByText(/以管理员身份运行客户端.*日志/),
    ).toBeVisible();
  });
});
