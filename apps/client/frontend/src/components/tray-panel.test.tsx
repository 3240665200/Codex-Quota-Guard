import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TrayPanelView } from "./tray-panel";
import { moneyWindow } from "@/test/usd-fixtures";
import { traySnapshotSchema, type TraySnapshot } from "@/lib/tray-panel";

const example = (): TraySnapshot => ({
  userName: "Administrator",
  bindingAllowed: true,
  personal: {
    fiveHour: moneyWindow(null, "2.5"),
    weekly: moneyWindow("667", "7.5", "659.5"),
  },
  account: { label: "Pro100x", total: moneyWindow("8000", "20", "7980") },
  official: {
    source: "SERVER",
    status: "SUCCESS",
    stale: false,
    accountLabel: "Pro100x",
    planType: "pro",
    windows: [
      {
        limitId: "codex",
        slot: "primary",
        windowDurationMins: 300,
        usedPercent: 25,
        resetsAt: null,
      },
    ],
    fetchedAt: "2026-10-27T04:00:00Z",
  },
  updatedAt: "2026-10-27T04:00:00Z",
  quotaFailed: false,
  officialFailed: false,
  meterRunning: true,
  refreshing: false,
  mode: "remaining",
  theme: "dark",
});

describe("tray quota panel", () => {
  it("switches the left detail pane without mixing dollars and official percentages", () => {
    render(<TrayPanelView snapshot={example()} onAction={vi.fn()} />);
    const personal = screen.getByRole("region", { name: "个人额度" });
    expect(within(personal).getByText("无限制")).toBeVisible();
    expect(
      within(personal).getByRole("progressbar", { name: "7 天剩余额度" }),
    ).toHaveAttribute("aria-valuenow", String((659.5 / 667) * 100));
    fireEvent.click(screen.getByRole("button", { name: "查看官方套餐参考" }));
    expect(
      screen.getByRole("progressbar", { name: "5 小时窗口官方套餐额度" }),
    ).toHaveAttribute("aria-valuenow", "75");
    expect(
      screen.queryByRole("region", { name: "个人额度" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "查看账户美元预算" }));
    expect(
      screen.getByRole("region", { name: "官方账户美元预算" }),
    ).toBeVisible();
    expect(screen.getByText("我")).toBeVisible();
  });
  it("honors used display mode without mixing dollars with plan percentages", () => {
    render(
      <TrayPanelView
        snapshot={{ ...example(), mode: "used" }}
        onAction={vi.fn()}
      />,
    );
    expect(
      screen.getByRole("progressbar", { name: "7 天已用额度" }),
    ).toHaveAttribute("aria-valuenow", String((7.5 / 667) * 100));
    fireEvent.click(screen.getByRole("button", { name: "查看官方套餐参考" }));
    expect(
      screen.getByRole("progressbar", { name: "5 小时窗口官方套餐额度" }),
    ).toHaveAttribute("aria-valuenow", "25");
  });
  it("labels failed and stale snapshots rather than presenting them as current", () => {
    const snapshot = example();
    snapshot.quotaFailed = true;
    snapshot.official!.stale = true;
    render(<TrayPanelView snapshot={snapshot} onAction={vi.fn()} />);
    expect(screen.getByText("连接暂不可用，显示上次数据")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "查看官方套餐参考" }));
    expect(screen.getByText(/上次成功数据，更新延迟/)).toBeVisible();
  });
  it("does not fabricate a balance or progress for pending billing", () => {
    const snapshot = example();
    snapshot.personal!.weekly = {
      ...moneyWindow("667", "7.5", null),
      pendingEvents: 3,
    };
    render(<TrayPanelView snapshot={snapshot} onAction={vi.fn()} />);
    expect(screen.getByText("待核算")).toBeVisible();
    expect(
      screen.queryByRole("progressbar", { name: "7 天剩余额度" }),
    ).not.toBeInTheDocument();
  });
  it.each([null, { ...example(), bindingAllowed: false }])(
    "hides quota information before login or binding",
    (snapshot) => {
      render(<TrayPanelView snapshot={snapshot} onAction={vi.fn()} />);
      expect(
        screen.queryByRole("region", { name: "个人额度" }),
      ).not.toBeInTheDocument();
      expect(screen.queryByText("Pro100x")).not.toBeInTheDocument();
    },
  );
  it("routes useful shortcuts and quits only the client", () => {
    const action = vi.fn();
    render(<TrayPanelView snapshot={example()} onAction={action} />);
    fireEvent.click(screen.getByRole("tab", { name: "快捷" }));
    for (const [label, value] of [
      ["刷新实时用量", "refresh"],
      ["额度总览", "quota"],
      ["消耗流水", "ledger"],
      ["统计程序", "diagnostics"],
      ["登录辅助", "login-assistance"],
      ["设置", "settings"],
      ["打开主窗口", "show"],
      ["关闭速览", "close"],
      ["退出客户端", "quit"],
    ]) {
      fireEvent.click(
        screen.getByRole("button", { name: new RegExp(`^${label}`) }),
      );
      expect(action).toHaveBeenLastCalledWith(value);
    }
    expect(screen.getByText("统计服务独立运行")).toBeVisible();
  });
  it("filters details and clears the search when changing category", () => {
    render(<TrayPanelView snapshot={example()} onAction={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox", { name: "搜索额度" }), {
      target: { value: "7 天" },
    });
    expect(screen.getByText("7 天")).toBeVisible();
    expect(screen.queryByText("5 小时")).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("textbox", { name: "搜索额度" }), {
      target: { value: "不存在" },
    });
    expect(screen.getByText("没有匹配的额度")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "查看官方套餐参考" }));
    expect(screen.getByRole("textbox", { name: "搜索额度" })).toHaveValue("");
    expect(
      screen.getByRole("progressbar", { name: "5 小时窗口官方套餐额度" }),
    ).toBeVisible();
  });
  it("supports keyboard navigation between menu tabs", () => {
    render(<TrayPanelView snapshot={example()} onAction={vi.fn()} />);
    fireEvent.keyDown(screen.getByRole("tab", { name: "用量" }), {
      key: "ArrowRight",
    });
    expect(screen.getByRole("tab", { name: "快捷" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("tab", { name: "快捷" })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("tab", { name: "快捷" }), {
      key: "Home",
    });
    expect(screen.getByRole("tab", { name: "用量" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });
  it("rejects unexpected top-level fields in cross-window display messages", () => {
    expect(
      traySnapshotSchema.safeParse({
        ...example(),
        accessToken: "synthetic-never-send",
      }).success,
    ).toBe(false);
  });
});
