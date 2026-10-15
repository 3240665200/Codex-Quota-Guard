import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { invoke } from "@tauri-apps/api/core";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { OfficialLoginHelper } from "./official-login-helper";
vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const native = vi.mocked(invoke);
const id = "11111111-1111-4111-8111-111111111111";
beforeEach(() => {
  native.mockReset();
  native.mockImplementation(async (name) => {
    if (name === "official_login_helper_jobs") return [{ id, officialAccountId: id,
      accountName: "管理员团队账户", state: "PENDING", expiresAt: "2026-12-31T00:00:00Z", errorCode: null, proxyNodeId: null }];
    return { jobId: null, state: "IDLE", errorCode: null, accountName: null };
  });
});
afterEach(cleanup);
it("列出当前管理员任务并由用户显式选择浏览器登录", async () => {
  render(<OfficialLoginHelper userId="admin-a" />);
  await screen.findByText("管理员团队账户");
  expect(native.mock.calls.some(([name]) => name === "official_login_helper_start")).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "使用 Edge 登录" }));
  expect(native).toHaveBeenLastCalledWith("official_login_helper_start", { jobId: id, browser: "EDGE" });
  expect(screen.getByText(/独立浏览器窗口/)).toBeVisible();
});
it("代理限制返回固定提示且不显示任意错误内容", async () => {
  native.mockImplementation(async (name) => name === "official_login_helper_jobs" ? []
    : { jobId: id, state: "FAILED", errorCode: "PROXY_UNSUPPORTED", accountName: "安全账户" });
  render(<OfficialLoginHelper userId="admin-a" />);
  await screen.findByText(/固定代理/);
});
