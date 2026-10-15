import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { Dashboard } from "@/components/dashboard";
import i18n from "@/lib/i18n";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const mock = vi.mocked(invoke);
const session = { authenticated: true, user: { id: "user-1", email: "alice@example.com", displayName: "Alice", role: "USER", status: "ACTIVE" } };
const bindingId = "11111111-1111-4111-8111-111111111111";
let access: unknown;
let failAccess = false;
function show(role = "USER") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const result = render(<I18nextProvider i18n={i18n}><QueryClientProvider client={client}><Dashboard session={{...session, user:{...session.user, role}}} onLogout={vi.fn()} /></QueryClientProvider></I18nextProvider>);
  return { ...result, client };
}
describe("device binding access gate", () => {
  beforeEach(() => {
    access = { allowed: false, bindingId: null, reason: "SESSION_BINDING_REQUIRED" };
    failAccess = false;
    mock.mockReset();
    mock.mockImplementation(async (command) => {
      if (command === "system_access") { if (failAccess) throw new Error("offline"); return access; }
      if (command === "session_binding_current") return { attempt: null, binding: null };
      if (command === "login_assistance_info") return { available: false, reasonCode: "NO_TEAM", officialAccountId: null, accountLabel: null, recipientEmail: null };
      if (command === "official_login_helper_jobs") return [];
      if (command === "official_login_helper_status") return {jobId:null,state:"IDLE",errorCode:null,accountName:null};
      throw new Error("unsupported");
    });
  });
  it("starts closed and renders only assistance while protected menus and requests stay blocked", async () => {
    show();
    expect(screen.getByRole("button", {name:"额度总览"})).toBeDisabled();
    expect(screen.getByRole("button", {name:"设置"})).toBeDisabled();
    fireEvent.click(screen.getByRole("button", {name:"设置"}));
    await screen.findByRole("heading", {name:"监听登录邮件"});
    expect(screen.queryByRole("heading", {name:"启动设置"})).toBeNull();
    expect(mock.mock.calls.some(([name]) => ["quota_overview", "usage_status", "codex_rate_limits"].includes(name))).toBe(false);
  });
  it("makes the admin official helper available before binding", async () => {
    show("ADMIN");
    await screen.findByRole("heading", {name:"官方登录助手"});
    expect(screen.getByRole("button", {name:"设置"})).toBeDisabled();
  });
  it("locks the current page immediately when access is revoked or cannot be checked", async () => {
    access = {allowed:true,bindingId,reason:null};
    const {client} = show();
    await waitFor(() => expect(screen.getByRole("button", {name:"设置"})).toBeEnabled());
    fireEvent.click(screen.getByRole("button", {name:"设置"}));
    await screen.findByRole("heading", {name:"启动设置"});
    failAccess = true;
    await client.invalidateQueries({queryKey:["client-access"]});
    await waitFor(() => expect(screen.getByRole("button", {name:"设置"})).toBeDisabled());
    expect(screen.queryByRole("heading", {name:"启动设置"})).toBeNull();
    expect(screen.getByRole("heading", {name:"监听登录邮件"})).toBeVisible();
  });
});
