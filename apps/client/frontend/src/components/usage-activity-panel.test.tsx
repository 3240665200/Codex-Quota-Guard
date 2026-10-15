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
  override?: UsageStatus | null,
  onStart: () => void = vi.fn(),
  selectedView?: "overview" | "ledger" | "diagnostics",
  statusError?: unknown,
) {
  return (
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <UsageActivityPanel
        userId={userId}
        view={selectedView}
        status={
          override === null
            ? undefined
            : (override ?? {
                ...status,
                fallback: { ...status.fallback, issues: [] },
              })
        }
        statusError={statusError}
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
    expect(screen.getByRole("list", { name: "扫描诊断汇总" })).toHaveClass(
      "cqg-scrollbar-hidden",
      "overflow-y-auto",
    );
    expect(screen.getByRole("list", { name: "扫描诊断汇总" })).toHaveAttribute(
      "tabindex",
      "0",
    );
    expect(
      screen.queryByText(/OVERSIZED_JSONL_RECORD、OVERSIZED_JSONL_RECORD/),
    ).not.toBeInTheDocument();
  });

  it("keeps hidden-scrollbar diagnostics keyboard scrollable", async () => {
    render(view("user-a", undefined, vi.fn(), "diagnostics"));
    const logs = await screen.findByRole("list", { name: "统计程序运行日志" });
    expect(logs).toHaveClass("cqg-scrollbar-hidden", "overflow-y-auto");
    expect(logs).toHaveAttribute("tabindex", "0");
    expect(within(logs).getByTitle("精确值：12,500 Token")).toBeVisible();
  });

  it("renders the external overview as analytics only and refreshes its analytics query", async () => {
    render(view("user-a", undefined, vi.fn(), "overview"));

    expect(await screen.findByText("当前范围暂无用量")).toBeVisible();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.queryByText("统计程序最近扫描")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "立即扫描" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("统计程序日志 · 最近 50 条"),
    ).not.toBeInTheDocument();
    expect(invokeMock.mock.calls.map(([command]) => command)).toEqual([
      "usage_analytics",
    ]);

    await userEvent.click(screen.getByRole("button", { name: "刷新用量统计" }));
    await waitFor(() =>
      expect(
        invokeMock.mock.calls.filter(
          ([command]) => command === "usage_analytics",
        ),
      ).toHaveLength(2),
    );
    expect(invokeMock.mock.calls.map(([command]) => command)).toEqual([
      "usage_analytics",
      "usage_analytics",
    ]);
  });

  it("renders the external ledger without diagnostics and keeps model metadata", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "usage_events")
        return {
          ...ledger,
          items: [
            {
              ...ledger.items[0],
              model: "gpt-5-codex",
              reasoningEffort: "high",
            },
          ],
        };
      throw new Error(`Unexpected command: ${command}`);
    });

    render(view("user-a", undefined, vi.fn(), "ledger"));
    const table = await screen.findByRole("table", { name: "个人消耗流水" });
    expect(table).toHaveClass("min-w-[640px]", "table-fixed");
    const ledgerToolbar = screen.getByRole("button", { name: "刷新消耗流水" })
      .parentElement!.parentElement!;
    expect(ledgerToolbar).toHaveClass("mb-3");
    expect(ledgerToolbar).not.toHaveClass("my-3");

    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.queryByText("统计程序最近扫描")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "立即扫描" }),
    ).not.toBeInTheDocument();
    expect(invokeMock.mock.calls.map(([command]) => command)).toEqual([
      "usage_events",
    ]);
    expect(
      within(table)
        .getAllByRole("columnheader")
        .map((header) => header.textContent),
    ).toEqual(["消耗时间", "Token 消耗", "模型名称", "思考等级"]);
    expect(within(table).getByText("gpt-5-codex")).toBeVisible();
    expect(within(table).getByText("high")).toBeVisible();
    expect(within(table).getByTitle("精确值：12,500 Token")).toBeVisible();
  });

  it("concentrates service recovery, scan reports, and logs in the external diagnostics view", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "meter_service_version")
        return {
          installedVersion: "0.1.0",
          bundledVersion: "0.1.1",
          needsUpgrade: true,
          status: "UPGRADE_REQUIRED",
        };
      if (command === "meter_runtime_logs")
        return {
          state: "READY",
          skippedLines: 0,
          entries: [
            {
              atEpochSeconds: 1791504000,
              kind: "SERVICE_STARTED",
              state: "UNPAIRED",
              reportedEvents: 0,
              reportedTokens: 0,
              heartbeatSent: false,
              errorCode: null,
              ccSwitchGuard: null,
            },
          ],
        };
      throw new Error(`Unexpected command: ${command}`);
    });
    const diagnosticStatus = {
      ...status,
      service: {
        ...status.service,
        state: "UNPAIRED",
        errorCode: "METER_PAIRING_EXPIRED",
      },
      pairingState: "PAIRING_FAILED",
      fallback: {
        ...status.fallback,
        state: "UP_TO_DATE",
        issues: ["OVERSIZED_JSONL_RECORD"],
      },
    } as UsageStatus;

    render(
      view(
        "user-a",
        diagnosticStatus,
        vi.fn(),
        "diagnostics",
        new Error("status request failed"),
      ),
    );

    expect(screen.getAllByText(/统计状态读取失败/)).toHaveLength(2);
    expect(screen.getByText(/当前显示的是上次读取结果/)).toBeVisible();
    expect(await screen.findByText("统计程序需要更新")).toBeVisible();
    expect(await screen.findByText("统计程序启动")).toBeVisible();
    expect(screen.getByText(/超大日志记录/)).toBeVisible();
    expect(screen.getByRole("button", { name: "更新统计程序" })).toBeVisible();
    expect(screen.getByRole("button", { name: "重新配对" })).toBeVisible();
    expect(screen.getByRole("button", { name: "立即扫描" })).toBeVisible();
    const toolbar = screen.getByRole("button", { name: "刷新运行诊断" })
      .parentElement!.parentElement!;
    expect(toolbar.previousElementSibling?.tagName).toBe("DL");
    expect(toolbar).toHaveClass("my-3");
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(invokeMock.mock.calls.map(([command]) => command).sort()).toEqual([
      "meter_runtime_logs",
      "meter_service_version",
    ]);
  });

  it("shows a status query error instead of a loading label when no prior status exists", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "meter_service_version")
        return {
          installedVersion: null,
          bundledVersion: "0.1.1",
          needsUpgrade: null,
          status: "UNKNOWN",
        };
      if (command === "meter_runtime_logs")
        return { state: "NOT_AVAILABLE", skippedLines: 0, entries: [] };
      throw new Error(`Unexpected command: ${command}`);
    });

    render(
      view(
        "user-a",
        null,
        vi.fn(),
        "diagnostics",
        new Error("status request failed"),
      ),
    );

    expect(
      await screen.findByText("本机暂无统计程序日志，请先安装并启动统计程序。"),
    ).toBeVisible();
    const statusAlert = screen
      .getByText("请刷新重试。", { exact: false })
      .closest('[role="alert"]') as HTMLElement;
    expect(within(statusAlert).getByText("统计状态读取失败")).toBeVisible();
    expect(screen.getByText("请刷新重试。", { exact: false })).toBeVisible();
    expect(screen.queryByText("正在检查统计程序…")).not.toBeInTheDocument();
    expect(screen.queryByText("正在读取…")).not.toBeInTheDocument();
    expect(await screen.findByText(/无法确认当前版本/)).toBeVisible();
    expect(screen.getByRole("button", { name: "检查并修复" })).toBeVisible();
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
  it.each([
    ["METER_OUTBOX_READ_FAILED", /待传用量无法读取.*权限.*不要删除/],
    ["METER_OUTBOX_WRITE_FAILED", /待传用量无法保存.*磁盘空间.*不要删除/],
    ["METER_OUTBOX_INVALID", /待传队列格式异常.*管理员.*保留/],
    ["METER_OUTBOX_TOO_LARGE", /待传队列已达容量上限.*管理员.*保留/],
    ["METER_OUTBOX_IDENTITY_MISMATCH", /设备、服务器或本地目录不一致.*暂停.*管理员/],
    ["METER_OUTBOX_OWNER_MISMATCH", /配对账户发生变化.*隔离保留.*后续有效用量.*管理员/],
    ["METER_OUTBOX_OWNER_UNKNOWN", /无法确认归属.*隔离保留.*后续有效用量.*管理员/],
    ["METER_PAIRING_OWNER_MISMATCH", /授权账户与待传用量归属不一致.*暂停.*管理员/],
    ["METER_RENEWAL_INVALID", /设备续期结果未通过校验.*保留.*版本.*管理员/],
    ["METER_EVENT_OUTSIDE_REPORT_WINDOW", /超过后台接收时间范围.*保留.*后续有效用量.*管理员/],
    ["METER_PAIRING_EXPIRED", /设备授权已失效.*撤销.*停用.*管理员/],
    ["METER_REPORT_REJECTED", /拒绝的用量已隔离保留.*后续有效用量.*管理员/],
  ])("explains retained meter failure %s with a safe recovery action", (code, message) => {
    render(view("user-a", {
      ...status,
      fallback: { ...status.fallback, issues: [], errorCode: null },
      service: { ...status.service, state: "ERROR", errorCode: code as string },
    }, vi.fn(), "diagnostics"));
    expect(screen.getByText(message as RegExp)).toBeVisible();
    expect(screen.queryByText(new RegExp(`诊断码：${code}`))).not.toBeInTheDocument();
    expect(screen.queryByText(/deviceToken|refresh_token|原始日志内容/)).not.toBeInTheDocument();
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
  it("keeps model metadata visible in the seven-column compact ledger", async () => {
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
    expect(table).toHaveClass("min-w-[700px]", "table-fixed");
    expect(table.parentElement).toHaveClass("min-w-0", "overflow-x-auto");
    expect(table.closest("section")).toHaveClass("min-w-0");

    const rows = within(table).getAllByRole("row");
    const modelCell = within(rows[1]).getByTitle(longModel);
    expect(modelCell).toHaveTextContent(longModel);
    expect(modelCell.firstElementChild).toHaveClass("truncate");
    expect(within(rows[1]).getByText("high")).toBeVisible();
    const cells = within(rows[2]).getAllByRole("cell");
    expect(cells).toHaveLength(7);
    expect(cells[5]).toHaveTextContent("未知");
    expect(cells[6]).toHaveTextContent("未知");
    const tokenCell = within(rows[1]).getAllByRole("cell")[1];
    expect(
      within(tokenCell).getByRole("button", { name: "查看详情" }),
    ).toBeVisible();
    for (const label of [
      "输入（含缓存）",
      "输出（含思考）",
      "缓存读",
      "缓存写",
      "思考",
    ]) {
      expect(within(tokenCell).queryByText(label)).not.toBeInTheDocument();
    }
  });
  it("shows compact token summaries and opens details for the selected row", async () => {
    invokeMock.mockResolvedValue({
      ...ledger,
      items: [
        {
          ...ledger.items[0],
          model: "first-model",
          reasoningEffort: "high",
          inputTokens: 10000,
          cachedInputTokens: 3000,
          cacheWriteInputTokens: 0,
          outputTokens: 2500,
          reasoningOutputTokens: 1800,
          estimatedCostUsd: 0.0355,
          costStatus: "PRICED",
        },
        {
          ...ledger.items[0],
          id: "b".repeat(64),
          occurredAt: "2026-10-08T00:00:00Z",
          receivedAt: "2026-10-08T00:00:01Z",
          totalTokens: 7200,
          source: "CUMULATIVE_TOKEN_COUNT_FALLBACK",
          model: "selected-model",
          reasoningEffort: "medium",
          inputTokens: 6000,
          cachedInputTokens: 1500,
          cacheWriteInputTokens: 250,
          outputTokens: 1200,
          reasoningOutputTokens: 400,
          estimatedCostUsd: 0.0123,
          costStatus: "PRICED",
        },
      ],
    });
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    const table = await screen.findByRole("table", { name: "个人消耗流水" });
    const rows = within(table).getAllByRole("row");
    const selectedRow = rows[2];
    const tokenCell = within(selectedRow).getAllByRole("cell")[1];
    expect(within(tokenCell).getByTitle("精确值：7,200 Token")).toBeVisible();
    expect(within(tokenCell).getByText("USD 0.0123")).toBeVisible();
    const trigger = within(tokenCell).getByRole("button", {
      name: "查看详情",
    });
    expect(trigger).toBeVisible();
    for (const label of [
      "输入（含缓存）",
      "输出（含思考）",
      "缓存读",
      "缓存写",
      "思考",
    ]) {
      expect(within(tokenCell).queryByText(label)).not.toBeInTheDocument();
    }

    await userEvent.click(trigger);
    const dialog = await screen.findByRole("dialog", { name: "消耗详情" });
    expect(dialog).toHaveClass("max-h-[calc(100dvh-1rem)]");
    expect(dialog.querySelector(".overflow-y-auto")).toHaveClass(
      "cqg-scrollbar-hidden",
      "min-h-0",
      "flex-1",
    );
    expect(
      within(dialog).getByRole("button", { name: "关闭详情" }).closest("div"),
    ).toHaveClass("shrink-0");
    for (const label of [
      "总消耗",
      "费用估算",
      "输入（含缓存）",
      "输出（含思考）",
      "缓存读",
      "缓存写",
      "思考",
      "消耗时间",
      "后台接收",
      "来源",
      "状态",
      "模型名称",
      "思考等级",
    ]) {
      expect(within(dialog).getByText(label)).toBeVisible();
    }
    for (const tokens of [7200, 6000, 1500, 250, 1200, 400]) {
      expect(
        within(dialog).getByTitle(
          `精确值：${tokens.toLocaleString("en-US")} Token`,
        ),
      ).toBeVisible();
    }
    expect(within(dialog).getByText("USD 0.0123")).toBeVisible();
    expect(within(dialog).getByText("selected-model")).toBeVisible();
    expect(within(dialog).getByText("medium")).toBeVisible();
    expect(within(dialog).getByText("累计差值补录")).toBeVisible();
    expect(within(dialog).getByText("已接收")).toBeVisible();
    expect(
      within(dialog).getByRole("button", { name: "关闭详情" }),
    ).toBeVisible();
    const occurredAt = within(dialog).getByText("消耗时间").parentElement;
    const receivedAt = within(dialog).getByText("后台接收").parentElement;
    expect(occurredAt).toHaveTextContent("2026");
    expect(receivedAt).toHaveTextContent("2026");

    await userEvent.click(
      within(dialog).getByRole("button", { name: /^关闭$/ }),
    );
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
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
    expect(within(tokenCell).getByText("分项缺失")).toBeVisible();
    await userEvent.click(
      within(tokenCell).getByRole("button", { name: "查看详情" }),
    );
    const dialog = await screen.findByRole("dialog", { name: "消耗详情" });
    expect(within(dialog).getAllByText("未知")).toHaveLength(6);
    expect(within(dialog).getByTitle("精确值：0 Token")).toBeVisible();
  });
  it("closes usage details with the header button and returns focus after Escape", async () => {
    render(view());
    await userEvent.click(screen.getByRole("tab", { name: "消耗流水" }));
    const table = await screen.findByRole("table", { name: "个人消耗流水" });
    const tokenCell = within(table).getAllByRole("cell")[1];
    const trigger = within(tokenCell).getByRole("button", {
      name: "查看详情",
    });

    await userEvent.click(trigger);
    const dialog = await screen.findByRole("dialog", { name: "消耗详情" });
    await userEvent.click(
      within(dialog).getByRole("button", { name: "关闭详情" }),
    );
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );

    await userEvent.click(trigger);
    await screen.findByRole("dialog", { name: "消耗详情" });
    await userEvent.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(trigger).toHaveFocus();
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
